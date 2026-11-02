package com.iaaorgmx.paynani.roster;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.view.View;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.iaaorgmx.paynani.R;

/**
 * El Catch-All (paynani PRD #535, FR-A1 a FR-A4): la casilla, las instrucciones para el agente,
 * la advertencia de los códigos de verificación y quién lo cambió por última vez. Lee con
 * catchall.get y guarda con catchall.save; no pide contraseña, como el editor de reglas (FR-A3).
 * Nada se da por guardado hasta que la pasarela responde.
 *
 * Desde el PRD #576 (FR-11) hay uno por canal, y cada uno se abre desde su pantalla (paynani #588,
 * Q232=A): el de SMS desde «Mensajes», el de WhatsApp desde «Mensajes de WhatsApp» y el de
 * Telegram desde «Mensajes de Telegram» (PaynaniApp #164, Q264 = A). La pantalla muestra sólo el
 * canal con el que se abrió y dice dónde están los otros. Una pasarela vieja no manda `channels`:
 * se muestra el único que tiene, con un texto que dice que aplica a SMS y WhatsApp. Telegram no
 * tiene esa salida: sin telegram en `channels`, la pantalla no lee ni guarda nada.
 */
public class CatchAllActivity extends AppCompatActivity {

    public static final String EXTRA_CHANNEL = "channel";
    public static final String SMS = CatchAllChannels.SMS;
    public static final String WHATSAPP = CatchAllChannels.WHATSAPP;
    public static final String TELEGRAM = CatchAllChannels.TELEGRAM;

    /**
     * El Catch-All de `channel` (sms, whatsapp o telegram). Sin FLAG_ACTIVITY_NO_USER_ACTION, Main2Activity
     * abriría «Mensajes» al volver (#125).
     */
    public static Intent intent(Context c, String channel) {
        return new Intent(c, CatchAllActivity.class).addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                .putExtra(EXTRA_CHANNEL, CatchAllChannels.normalize(channel));
    }

    private RosterSource source;
    private LinearProgressIndicator progress;
    private MaterialSwitch enabled;
    private TextView status;
    private TextInputEditText body;
    private TextView size;
    private TextView error;
    private MaterialButton saveButton;
    private TextView scope;

    /** El canal de esta pantalla, fijo: sms, whatsapp o telegram. Una pasarela vieja ignora `channel` (FR-12). */
    private String channel = SMS;
    private boolean perChannel;

    private String etag = "";
    private boolean loadedEnabled;
    private String loadedText = "";
    private boolean loaded;
    private boolean busy;
    /** Lo que había en pantalla al recrear la actividad (rotación), para volver a ponerlo tras leer. */
    private Boolean restoredEnabled;
    private String restoredText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_Paynani_M3);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_catch_all);
        source = RosterSources.get(this);

        MaterialToolbar toolbar = findViewById(R.id.catchall_toolbar);
        View bar = findViewById(R.id.catchall_bar);
        // De borde a borde (targetSdk 35), como RulesEditorActivity.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            androidx.core.graphics.Insets sys = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars() | androidx.core.view.WindowInsetsCompat.Type.ime());
            toolbar.setPadding(0, sys.top, 0, 0);
            int pad = (int) (12 * getResources().getDisplayMetrics().density);
            bar.setPadding(pad, pad, pad, pad + sys.bottom);
            return insets;
        });
        toolbar.setNavigationOnClickListener(v -> leave());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                leave();
            }
        });

        progress = findViewById(R.id.catchall_progress);
        enabled = findViewById(R.id.catchall_enabled);
        status = findViewById(R.id.catchall_status);
        body = findViewById(R.id.catchall_body);
        size = findViewById(R.id.catchall_size);
        error = findViewById(R.id.catchall_error);
        saveButton = findViewById(R.id.catchall_save);
        scope = findViewById(R.id.catchall_scope);
        channel = CatchAllChannels.normalize(getIntent().getStringExtra(EXTRA_CHANNEL));
        if (savedInstanceState != null && savedInstanceState.containsKey("draft_text")) {
            restoredEnabled = savedInstanceState.getBoolean("draft_enabled");
            restoredText = savedInstanceState.getString("draft_text");
        }
        body.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { updateSize(); }
        });
        saveButton.setOnClickListener(v -> save());
        load(null);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (loaded) {
            out.putBoolean("draft_enabled", enabled.isChecked());
            out.putString("draft_text", text());
        }
    }

    // --- Lectura ----------------------------------------------------------------------------------

    /** Lee el Catch-All del canal. Con `afterConflict`, dice que alguien más lo cambió (FR-A2). */
    private void load(String afterConflict) {
        setBusy(true);
        String asked = channel;
        source.catchAllGet(asked, new RosterSource.Callback<RosterSource.CatchAll>() {
            @Override
            public void onResult(RosterSource.CatchAll c) {
                setBusy(false);
                if (!CatchAllChannels.supported(asked, c)) {
                    // Sería el Catch-All de otro canal: no se muestra ni se puede guardar.
                    fatal(getString(R.string.catchall_err_no_telegram));
                    return;
                }
                show(c);
                if (restoredText != null) {
                    enabled.setChecked(restoredEnabled);
                    body.setText(restoredText);
                    restoredEnabled = null;
                    restoredText = null;
                }
                if (afterConflict != null) showError(afterConflict);
                else if ("invalid".equals(c.status)) showError(getString(R.string.catchall_err_invalid_file,
                        c.file.isEmpty() ? "rules/catch-all.md" : c.file));
            }

            @Override
            public void onError(RosterSource.Failure f) {
                setBusy(false);
                fatal(failureText(f));
            }
        });
    }

    private void show(RosterSource.CatchAll c) {
        etag = c.etag;
        loadedEnabled = c.enabled;
        loadedText = c.text;
        enabled.setChecked(c.enabled);
        body.setText(c.text);
        loaded = true;
        error.setVisibility(View.GONE);
        status.setText(statusText(c));
        perChannel = c.perChannel();
        MaterialToolbar toolbar = findViewById(R.id.catchall_toolbar);
        toolbar.setSubtitle(perChannel ? channelName() : null);
        String what = !perChannel ? getString(R.string.catchall_scope_both)
                : getString(TELEGRAM.equals(channel) ? R.string.catchall_scope_telegram
                : WHATSAPP.equals(channel) ? R.string.catchall_scope_whatsapp : R.string.catchall_scope_sms);
        for (String other : CatchAllChannels.others(channel, c.channels)) {
            what += "\n\n" + getString(R.string.catchall_elsewhere, name(other), screen(other));
        }
        scope.setText(perChannel && c.legacyFile() ? what + "\n\n" + getString(R.string.catchall_legacy) : what);
        updateSize();
    }

    /** El nombre del canal para los avisos; con una pasarela vieja, los dos. */
    private String channelName() {
        if (!perChannel) return name(SMS) + " / " + name(WHATSAPP);
        return name(channel);
    }

    private String name(String ch) {
        return getString(TELEGRAM.equals(ch) ? R.string.catchall_channel_telegram
                : WHATSAPP.equals(ch) ? R.string.catchall_channel_whatsapp : R.string.catchall_channel_sms);
    }

    /** La pantalla desde donde se abre el Catch-All de ese canal. */
    private String screen(String ch) {
        return getString(TELEGRAM.equals(ch) ? R.string.opt_telegram_messages
                : WHATSAPP.equals(ch) ? R.string.opt_whatsapp_messages : R.string.opt_messages);
    }

    private String statusText(RosterSource.CatchAll c) {
        if (!c.exists) return getString(R.string.catchall_status_never);
        String state = getString("active".equals(c.status) ? R.string.catchall_status_on
                : "invalid".equals(c.status) ? R.string.catchall_status_invalid : R.string.catchall_status_off);
        if (c.updatedAt.isEmpty()) return state;
        return getString(R.string.catchall_status_changed, state, c.updatedAt, c.updatedBy);
    }

    // --- Guardado ---------------------------------------------------------------------------------

    private void save() {
        boolean on = enabled.isChecked();
        String t = text();
        String problem = CatchAllForm.problem(on, t);
        if (CatchAllForm.EMPTY.equals(problem)) {
            showError(getString(R.string.catchall_err_empty));
            return;
        }
        if (CatchAllForm.TOO_LARGE.equals(problem)) {
            showError(getString(R.string.rules_err_too_large));
            return;
        }
        error.setVisibility(View.GONE);
        setBusy(true);
        source.catchAllSave(channel, etag, on, t, new RosterSource.Callback<RosterSource.CatchAll>() {
            @Override
            public void onResult(RosterSource.CatchAll c) {
                setBusy(false);
                if (!CatchAllChannels.supported(channel, c)) {
                    // La misma revisión que al leer: una respuesta de otro canal no se muestra ni se da por guardada.
                    fatal(getString(R.string.catchall_err_no_telegram));
                    return;
                }
                show(c);
                setResult(RESULT_OK);
                Snackbar.make(saveButton, getString(c.enabled ? R.string.catchall_saved_on : R.string.catchall_saved_off,
                        channelName()), Snackbar.LENGTH_LONG).show();
            }

            @Override
            public void onError(RosterSource.Failure f) {
                setBusy(false);
                if ("conflict".equals(f.code) || "reload".equals(f.code)) {
                    load(getString(R.string.catchall_err_conflict));
                    return;
                }
                showError(failureText(f));
            }
        });
    }

    // --- Utilidades -------------------------------------------------------------------------------

    private String text() {
        return body.getText() == null ? "" : body.getText().toString();
    }

    private void leave() {
        if (loaded && !busy && CatchAllForm.changed(loadedEnabled, loadedText, enabled.isChecked(), text())) {
            AlertDialog d = new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_Paynani_M3_Dialog)
                    .setTitle(R.string.rules_unsaved_title)
                    .setMessage(R.string.rules_unsaved_message)
                    .setNegativeButton(R.string.roster_cancel, null)
                    .setPositiveButton(R.string.rules_discard, (x, w) -> finish())
                    .create();
            d.setOnShowListener(x -> tintButtons(d));
            d.show();
            return;
        }
        finish();
    }

    private void fatal(String message) {
        showError(message);
        enabled.setEnabled(false);
        body.setEnabled(false);
        saveButton.setEnabled(false);
    }

    private void setBusy(boolean b) {
        busy = b;
        progress.setVisibility(b ? View.VISIBLE : View.GONE);
        saveButton.setEnabled(!b && source.available());
    }

    private void showError(String text) {
        error.setText(text);
        error.setVisibility(View.VISIBLE);
    }

    private void updateSize() {
        int bytes = CatchAllForm.bytes(text());
        size.setText(getString(R.string.rules_size, Formatter.formatShortFileSize(this, bytes)));
        size.setTextColor(getResources().getColor(bytes > CatchAllForm.MAX_BYTES ? R.color.msg_failed
                : R.color.paynani_gris, null));
    }

    private String failureText(RosterSource.Failure f) {
        switch (f.code) {
            case "offline": return getString(R.string.roster_err_offline);
            case "timeout": return getString(R.string.roster_err_timeout);
            case "write_failed": return getString(R.string.roster_err_write_failed);
            case "invalid":
                if ("too_large".equals(f.reason)) return getString(R.string.rules_err_too_large);
                if ("empty".equals(f.reason)) return getString(R.string.catchall_err_empty);
                if ("reserved_in_use".equals(f.reason)) return getString(R.string.catchall_err_reserved);
                return getString(R.string.rules_err_header, f.field + (f.reason.isEmpty() ? "" : ": " + f.reason));
            default: return getString(R.string.roster_err_other, f.code);
        }
    }

    private void tintButtons(AlertDialog d) {
        int color = getResources().getColor(R.color.paynani_grana, null);
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            android.widget.Button b = d.getButton(which);
            if (b != null) b.setTextColor(color);
        }
    }
}
