package com.iaaorgmx.paynani.gateway;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AppCompatActivity;

import com.iaaorgmx.paynani.BuildConfig;
import com.iaaorgmx.paynani.Main2Activity;
import com.iaaorgmx.paynani.R;
import com.iaaorgmx.paynani.GlobalInstance;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import org.json.JSONObject;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Emparejar el teléfono con la pasarela de paynani (APP-2, SMS_GATEWAY.md §1).
 * Reemplaza al login de nicebot.co: no hay usuario ni contraseña, sólo el código
 * de un solo uso que muestra `paynani sms pair`.
 */
public class PairActivity extends AppCompatActivity {

    private EditText address, code;
    private TextView status;
    private Button pair;

    private final ActivityResultLauncher<ScanOptions> scanner = registerForActivityResult(new ScanContract(), result -> {
        if (result.getContents() != null) fromQr(result.getContents());
    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pair);
        address = findViewById(R.id.pair_address);
        code = findViewById(R.id.pair_code);
        status = findViewById(R.id.pair_status);
        pair = findViewById(R.id.pair_button);
        findViewById(R.id.pair_scan).setOnClickListener(v -> {
            ScanOptions options = new ScanOptions();
            options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
            options.setPrompt(getString(R.string.pair_scan_prompt));
            options.setBeepEnabled(false);
            options.setOrientationLocked(true);
            scanner.launch(options);
        });
        pair.setOnClickListener(v -> doPair(pairUrl(address.getText().toString()), code.getText().toString()));

        // Hallazgo de QA-4: abierta desde el lanzador después de una revocación, la
        // pantalla no decía por qué había que emparejar otra vez.
        GatewayPrefs prefs = new GatewayPrefs(this);
        if (prefs.isRevoked()) {
            status.setText(R.string.pair_after_revoke);
            String ws = prefs.getWsUrl();
            if (!ws.isEmpty()) {
                address.setText(ws.replaceFirst("^wss://", "https://").replaceFirst("^ws://", "http://")
                        .replaceFirst("/sms/ws$", ""));
            }
        }
    }

    /** El QR trae {"v":1,"pair":…,"ws":…,"code":…}. */
    private void fromQr(String contents) {
        try {
            JSONObject qr = new JSONObject(contents);
            String pairUrl = qr.getString("pair");
            address.setText(pairUrl.replaceFirst("/sms/pair$", ""));
            code.setText(qr.getString("code"));
            doPair(pairUrl, qr.getString("code"));
        } catch (Exception e) {
            status.setText(R.string.pair_bad_qr);
        }
    }

    private static String pairUrl(String base) {
        String b = base.trim().replaceAll("/+$", "");
        if (!b.startsWith("http://") && !b.startsWith("https://")) b = "https://" + b;
        return b.endsWith("/sms/pair") ? b : b + "/sms/pair";
    }

    private void doPair(String url, String pairCode) {
        if (pairCode.trim().isEmpty()) {
            status.setText(R.string.pair_missing_code);
            return;
        }
        pair.setEnabled(false);
        status.setText(R.string.pair_working);
        Executors.newSingleThreadExecutor().execute(() -> {
            String message;
            boolean ok = false;
            try {
                JSONObject device = new JSONObject();
                device.put("model", Build.MANUFACTURER + " " + Build.MODEL);
                device.put("android", Build.VERSION.RELEASE);
                device.put("app_version", BuildConfig.VERSION_NAME);
                JSONObject body = new JSONObject();
                body.put("code", pairCode.trim().toUpperCase());
                body.put("device", device);
                // Sólo Webchat (PaynaniApp-Agentic#175): sin SIM; el hello dice "sms": false.
                body.put("sims", new org.json.JSONArray());
                OkHttpClient http = new OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build();
                Request request = new Request.Builder().url(url)
                        .post(RequestBody.create(body.toString(), MediaType.get("application/json; charset=utf-8")))
                        .build();
                try (Response response = http.newCall(request).execute()) {
                    String text = response.body() != null ? response.body().string() : "";
                    switch (response.code()) {
                        case 201:
                            JSONObject r = new JSONObject(text);
                            new GatewayPrefs(this).savePairing(r.getString("ws"), r.getString("device_id"), r.getString("token"));
                            ok = true;
                            message = getString(R.string.pair_ok);
                            break;
                        case 409: message = getString(R.string.pair_device_exists); break;
                        case 410: message = getString(R.string.pair_code_expired); break;
                        case 429: message = getString(R.string.pair_blocked); break;
                        default: message = getString(R.string.pair_failed, response.code());
                    }
                }
            } catch (Exception e) {
                message = getString(R.string.pair_unreachable);
            }
            final String m = message;
            final boolean success = ok;
            runOnUiThread(() -> {
                status.setText(m);
                pair.setEnabled(true);
                if (success) {
                    GlobalInstance.paired(this);
                    startActivity(new Intent(this, Main2Activity.class).setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
                    finish();
                }
            });
        });
    }
}
