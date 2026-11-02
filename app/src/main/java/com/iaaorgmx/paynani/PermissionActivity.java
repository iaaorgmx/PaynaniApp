package com.iaaorgmx.paynani;
//PaynaniApp
import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;


import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Una sola pantalla de permisos (APP-7, AND-2): dice para qué es cada uno, los pide
 * todos de una vez, después pide quitar la optimización de batería (APP-6) y, si el
 * fabricante trae su propio ahorro de energía, lleva a su pantalla (#86).
 * La cámara la pide el lector de QR cuando se usa.
 */
public class PermissionActivity extends AppCompatActivity {

    /** Sin estos la pasarela no puede recibir ni enviar. */
    static String[] required() {
        List<String> p = new ArrayList<>();
        p.add(Manifest.permission.RECEIVE_SMS);
        p.add(Manifest.permission.READ_SMS);
        p.add(Manifest.permission.SEND_SMS);
        p.add(Manifest.permission.READ_PHONE_STATE);
        p.add(Manifest.permission.READ_PHONE_NUMBERS);
        p.add(Manifest.permission.READ_CALL_LOG);
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS);
        return p.toArray(new String[0]);
    }

    /** Permisos que faltan; vacío si están todos. */
    static List<String> missing(Context context) {
        List<String> out = new ArrayList<>();
        for (String p : required()) {
            if (ContextCompat.checkSelfPermission(context, p) != PackageManager.PERMISSION_GRANTED) out.add(p);
        }
        return out;
    }

    /** El nombre corto de un permiso, para la pantalla de estado. */
    static int label(String permission) {
        switch (permission) {
            case Manifest.permission.READ_PHONE_STATE:
            case Manifest.permission.READ_PHONE_NUMBERS: return R.string.perm_name_phone;
            case Manifest.permission.READ_CALL_LOG: return R.string.perm_name_call_log;
            case "android.permission.POST_NOTIFICATIONS": return R.string.perm_name_notifications;
            default: return R.string.perm_name_sms;
        }
    }

    private static final String RCS_OFF_CONFIRMED = "rcs_off_confirmed";

    /**
     * Si el usuario confirmó que apagó los chats RCS en Google Messages (#54).
     * Va en las preferencias por omisión y no en GatewayPrefs porque es del
     * teléfono, no del emparejamiento: desemparejar no la borra.
     */
    static boolean rcsOffConfirmed(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(RCS_OFF_CONFIRMED, false);
    }

    static void setRcsOffConfirmed(Context context, boolean confirmed) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(RCS_OFF_CONFIRMED, confirmed)
                .apply();
    }

    static boolean batteryOptimized(Context context) {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return pm != null && !pm.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    private final ActivityResultLauncher<String[]> permissionsLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), this::onPermissions);

    private final ActivityResultLauncher<Intent> batteryLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> askBackground());

    private BackgroundScreens.Screen background;

    // Al volver de la pantalla del fabricante no hay forma de saber qué hizo el
    // usuario: se le pregunta.
    private final ActivityResultLauncher<Intent> backgroundLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> confirmBackground());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        background = BackgroundScreens.confirmed(this) ? null : BackgroundScreens.find(this);
        if (missing(this).isEmpty() && !batteryOptimized(this) && background == null) {
            done();
            return;
        }
        setContentView(R.layout.activity_permissions);
        LinearLayout rows = findViewById(R.id.perm_rows);
        int[] reasons = {R.string.perm_sms, R.string.perm_phone, R.string.perm_call_log,
                R.string.perm_notifications, R.string.perm_battery, R.string.perm_rcs};
        for (int r : reasons) {
            if (r == R.string.perm_notifications && Build.VERSION.SDK_INT < 33) continue;
            addReason(rows, getString(r));
            if (r == R.string.perm_battery && background != null) {
                addReason(rows, getString(R.string.perm_background, background.vendor));
            }
        }
        findViewById(R.id.perm_continue).setOnClickListener(v -> {
            List<String> ask = missing(this);
            if (ask.isEmpty()) onPermissions(null);
            else permissionsLauncher.launch(ask.toArray(new String[0]));
        });
    }

    private void addReason(LinearLayout rows, String text) {
        TextView t = new TextView(this);
        t.setText("• " + text);
        t.setTextSize(15);
        t.setPadding(0, 0, 0, 24);
        rows.addView(t);
    }

    private void onPermissions(Map<String, Boolean> result) {
        PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putBoolean("request_permissions", false)
                .apply();
        if (batteryOptimized(this)) askBattery();
        else askBackground();
    }

    @SuppressLint("BatteryLife") // la app es una pasarela: sin conexión permanente no sirve
    private void askBattery() {
        try {
            batteryLauncher.launch(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            askBackground();
        }
    }

    /** Si el teléfono tiene pantalla de fabricante y falta confirmarla (#86). */
    private void askBackground() {
        if (background == null) {
            done();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.bg_guide_title)
                .setMessage(BackgroundScreens.steps(this, background))
                .setCancelable(false)
                .setPositiveButton(R.string.bg_guide_open, (d, w) -> {
                    try {
                        backgroundLauncher.launch(BackgroundScreens.openIntent(this, background));
                    } catch (RuntimeException e) {
                        // ActivityNotFoundException o SecurityException: la página de la marca.
                        try {
                            backgroundLauncher.launch(BackgroundScreens.dkmaIntent(background));
                        } catch (RuntimeException e2) {
                            done();
                        }
                    }
                })
                .setNegativeButton(R.string.bg_guide_later, (d, w) -> done())
                .show();
    }

    private void confirmBackground() {
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.bg_confirm, background.vendor))
                .setCancelable(false)
                .setPositiveButton(R.string.bg_guide_done, (d, w) -> {
                    BackgroundScreens.setConfirmed(this, true);
                    done();
                })
                .setNegativeButton(R.string.bg_confirm_not_yet, (d, w) -> done())
                .show();
    }

    private void done() {
        PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putBoolean("request_permissions", false)
                .apply();
        // Emparejada o no, la pantalla principal (#122).
        startActivity(new Intent(this, Main2Activity.class).setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
        finish();
    }
}
