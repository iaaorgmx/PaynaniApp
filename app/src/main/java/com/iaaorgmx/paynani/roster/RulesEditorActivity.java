package com.iaaorgmx.paynani.roster;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.view.View;
import android.widget.ArrayAdapter;
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
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.iaaorgmx.paynani.R;

import java.util.Arrays;

/**
 * El editor de instrucciones de un contacto, o de las reglas generales (paynani #409, R-6, R-7
 * e I-2): la cabecera con controles, el Markdown como texto, el tamaño contra los 32 KB y la vista
 * previa de la regla efectiva que calcula la pasarela. Nada se da por guardado hasta que la
 * pasarela responde (R-5).
 */
public class RulesEditorActivity extends AppCompatActivity {

    private static final String EXTRA_ACCOUNT = "account";
    private static final String EXTRA_ROW = "row";
    private static final String EXTRA_ROSTER_ETAG = "roster_etag";
    private static final String EXTRA_NAME = "name";
    private static final String EXTRA_EMAIL = "email";

    /** Las instrucciones de un contacto: la fila de roster.list y el etag con que se leyó. */
    public static Intent forMember(Context c, String account, RosterMember m, String rosterEtag) {
        return base(c)
                .putExtra(EXTRA_ACCOUNT, account)
                .putExtra(EXTRA_ROW, m.row)
                .putExtra(EXTRA_ROSTER_ETAG, rosterEtag)
                .putExtra(EXTRA_NAME, m.name)
                .putExtra(EXTRA_EMAIL, m.email);
    }

    /** rules/default.md. */
    public static Intent forDefault(Context c) {
        return base(c).putExtra(EXTRA_ROW, 0);
    }

    /**
     * Sin FLAG_ACTIVITY_NO_USER_ACTION, Main2Activity recibe onUserLeaveHint al abrir el editor y,
     * al volver, abre «Mensajes» como si el usuario hubiera salido de la app (#125).
     */
    private static Intent base(Context c) {
        return new Intent(c, RulesEditorActivity.class).addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION);
    }

    private RosterSource source;
    private String account;
    private int row;
    private String rosterEtag;
    private String name;
    private String email;

    private LinearProgressIndicator progress;
    private TextView note;
    private MaterialSwitch enabled;
    private MaterialAutoCompleteTextView replyEmail;
    private MaterialAutoCompleteTextView replySms;
    private TextInputEditText body;
    private TextView size;
    private TextView error;
    private MaterialButton previewButton;
    private MaterialButton saveButton;

    /** El etag del archivo leído ("" si no existía), y el texto tal como llegó, para saber si hay cambios. */
    private String etag = "";
    private String loadedText = "";
    private boolean loaded;
    private boolean busy;
    /**
     * Lo que el dueño quiso guardar cuando hubo conflict, para «Recuperar mi versión». Sólo en
     * memoria y en el estado de la actividad: nunca en el portapapeles, que leen el teclado y otras
     * apps y algunos sincronizan a la nube (las reglas no salen del teléfono ni van al log, §11).
     */
    private String mine;
    /** Lo que estaba en el editor al recrear la actividad (rotación), para volver a ponerlo tras leer. */
    private String restoredDraft;
    private MaterialButton recoverButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_Paynani_M3);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rules);
        source = RosterSources.get(this);
        Intent i = getIntent();
        account = i.getStringExtra(EXTRA_ACCOUNT);
        row = i.getIntExtra(EXTRA_ROW, 0);
        rosterEtag = i.getStringExtra(EXTRA_ROSTER_ETAG);
        name = i.getStringExtra(EXTRA_NAME);
        email = i.getStringExtra(EXTRA_EMAIL);

        MaterialToolbar toolbar = findViewById(R.id.rules_toolbar);
        View bar = findViewById(R.id.rules_bar);
        // Con targetSdk 35 la ventana va de borde a borde: la barra de estado y la de navegación
        // se descuentan aquí, y el teclado con adjustResize.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            androidx.core.graphics.Insets sys = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars() | androidx.core.view.WindowInsetsCompat.Type.ime());
            toolbar.setPadding(0, sys.top, 0, 0);
            int pad = (int) (12 * getResources().getDisplayMetrics().density);
            bar.setPadding(pad, pad, pad, pad + sys.bottom);
            return insets;
        });
        toolbar.setTitle(isDefault() ? R.string.rules_default_title : R.string.rules_title);
        toolbar.setSubtitle(isDefault() ? getString(R.string.rules_default_subtitle) : name);
        toolbar.setNavigationOnClickListener(v -> leave());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                leave();
            }
        });

        progress = findViewById(R.id.rules_progress);
        note = findViewById(R.id.rules_note);
        enabled = findViewById(R.id.rules_enabled);
        replyEmail = findViewById(R.id.rules_reply_email);
        replySms = findViewById(R.id.rules_reply_sms);
        body = findViewById(R.id.rules_body);
        size = findViewById(R.id.rules_size);
        error = findViewById(R.id.rules_error);
        previewButton = findViewById(R.id.rules_preview);
        saveButton = findViewById(R.id.rules_save);
        recoverButton = findViewById(R.id.rules_recover);
        recoverButton.setOnClickListener(v -> recover());
        if (savedInstanceState != null) {
            mine = savedInstanceState.getString("mine");
            restoredDraft = savedInstanceState.getString("draft");
            row = savedInstanceState.getInt("row", row);
            String re = savedInstanceState.getString("roster_etag");
            if (re != null) rosterEtag = re;
        }

        String[] options = {getString(R.string.rules_reply_normal), getString(R.string.rules_reply_never)};
        for (MaterialAutoCompleteTextView v : Arrays.asList(replyEmail, replySms)) {
            v.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, options));
        }
        body.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { updateSize(); }
        });
        previewButton.setOnClickListener(v -> preview());
        saveButton.setOnClickListener(v -> save());
        load();
    }

    private boolean isDefault() {
        return row == 0;
    }

    // --- Lectura ----------------------------------------------------------------------------------

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("mine", mine);
        out.putInt("row", row);
        out.putString("roster_etag", rosterEtag);
        if (loaded) out.putString("draft", current().compose());
    }

    private void load() {
        read(false);
    }

    /**
     * Lee las reglas. Un conflict aquí es de roster_etag: el roster cambió desde la lista (por
     * ejemplo, la primera vez que se guardan reglas de cualquier contacto, la pasarela escribe la
     * columna Rules). Entonces se vuelve a pedir roster.list, se busca la fila por correo y se lee
     * otra vez, una sola vez.
     */
    private void read(boolean relocated) {
        setBusy(true);
        RosterSource.Callback<RosterSource.Rules> cb = new RosterSource.Callback<RosterSource.Rules>() {
            @Override
            public void onResult(RosterSource.Rules r) {
                setBusy(false);
                // §11: la respuesta trae nombre y correo de la fila, para no mostrar las reglas de otro.
                if (!isDefault() && (!r.name.equals(name) || !r.email.equals(email))) {
                    fatal(getString(R.string.rules_err_wrong_contact));
                    return;
                }
                show(r);
                if (restoredDraft != null) {
                    apply(RulesText.parse(restoredDraft));
                    restoredDraft = null;
                }
                if (mine != null) {
                    showError(getString(R.string.rules_err_conflict));
                    recoverButton.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onError(RosterSource.Failure f) {
                setBusy(false);
                if (!isDefault() && !relocated && ("conflict".equals(f.code) || "reload".equals(f.code))) {
                    relocate();
                    return;
                }
                fatal("conflict".equals(f.code) ? getString(R.string.rules_err_wrong_contact) : failureText(f));
            }
        };
        if (isDefault()) source.defaultGet(cb);
        else source.rulesGet(account, row, rosterEtag, cb);
    }

    /** Busca la fila del contacto en la lista nueva, por correo (o por nombre si no tiene), y vuelve a leer. */
    private void relocate() {
        setBusy(true);
        source.list(account, new RosterSource.Callback<RosterSource.Roster>() {
            @Override
            public void onResult(RosterSource.Roster r) {
                setBusy(false);
                for (RosterMember m : r.members) {
                    boolean same = email.isEmpty() ? m.email.isEmpty() && m.name.equals(name)
                            : m.email.equalsIgnoreCase(email);
                    if (same) {
                        row = m.row;
                        rosterEtag = r.etag;
                        read(true);
                        return;
                    }
                }
                fatal(getString(R.string.rules_err_contact_gone));
            }

            @Override
            public void onError(RosterSource.Failure f) {
                setBusy(false);
                fatal(failureText(f));
            }
        });
    }

    private void show(RosterSource.Rules r) {
        etag = r.etag;
        RulesText t = r.exists ? RulesText.parse(r.text) : RulesText.blank();
        apply(t);
        loadedText = current().compose();
        loaded = true;
        if (isDefault()) showNote(getString(R.string.rules_note_default));
        else if (!r.exists) showNote(getString(R.string.rules_note_new));
        else note.setVisibility(View.GONE);
        updateSize();
    }

    private void apply(RulesText t) {
        enabled.setChecked(t.enabled);
        replyEmail.setText(label(t.replyEmail), false);
        replySms.setText(label(t.replySms), false);
        body.setText(t.body);
    }

    /** Vuelve a poner lo que el dueño quiso guardar, sobre la versión nueva (su etag ya está). */
    private void recover() {
        if (mine == null) return;
        apply(RulesText.parse(mine));
        mine = null;
        recoverButton.setVisibility(View.GONE);
        error.setVisibility(View.GONE);
    }

    // --- Vista previa y guardado ------------------------------------------------------------------

    private RulesText current() {
        return new RulesText(enabled.isChecked(), value(replyEmail), value(replySms),
                body.getText() == null ? "" : body.getText().toString());
    }

    private void preview() {
        RulesText t = current();
        if (t.tooLarge()) {
            showError(getString(R.string.rules_err_too_large));
            return;
        }
        // Las generales se ven tal cual; sin rules.preview, la del contacto es la última guardada (§11).
        if (isDefault() || !source.previewAvailable()) {
            if (isDefault()) showPreview(t.enabled ? t.body : "");
            else source.rulesGet(account, row, rosterEtag, previewCallback());
            return;
        }
        setBusy(true);
        source.rulesPreview(account, row, rosterEtag, t.compose(), previewCallback());
    }

    private RosterSource.Callback<RosterSource.Rules> previewCallback() {
        return new RosterSource.Callback<RosterSource.Rules>() {
            @Override
            public void onResult(RosterSource.Rules r) {
                setBusy(false);
                showPreview(r.effective);
            }

            @Override
            public void onError(RosterSource.Failure f) {
                setBusy(false);
                showError(failureText(f));
            }
        };
    }

    private void showPreview(String effective) {
        TextView v = new TextView(this);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        v.setPadding(pad, pad / 2, pad, 0);
        v.setTypeface(android.graphics.Typeface.MONOSPACE);
        v.setTextIsSelectable(true);
        v.setText(effective.trim().isEmpty() ? getString(R.string.rules_preview_empty) : effective);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(v);
        AlertDialog d = new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_Paynani_M3_Dialog)
                .setTitle(R.string.rules_preview_title)
                .setView(sv)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        d.setOnShowListener(x -> tintButtons(d));
        d.show();
    }

    private void save() {
        RulesText t = current();
        if (t.tooLarge()) {
            showError(getString(R.string.rules_err_too_large));
            return;
        }
        error.setVisibility(View.GONE);
        setBusy(true);
        String text = t.compose();
        RosterSource.Callback<RosterSource.Rules> cb = new RosterSource.Callback<RosterSource.Rules>() {
            @Override
            public void onResult(RosterSource.Rules r) {
                setBusy(false);
                etag = r.etag;
                if (!r.rosterEtag.isEmpty()) rosterEtag = r.rosterEtag;
                loadedText = text;
                mine = null;
                recoverButton.setVisibility(View.GONE);
                note.setVisibility(isDefault() ? View.VISIBLE : View.GONE);
                setResult(RESULT_OK);
                Snackbar.make(saveButton, R.string.rules_saved, Snackbar.LENGTH_LONG).show();
            }

            @Override
            public void onError(RosterSource.Failure f) {
                setBusy(false);
                if ("conflict".equals(f.code) || "reload".equals(f.code)) {
                    conflict(text);
                    return;
                }
                showError(failureText(f));
            }
        };
        if (isDefault()) source.defaultSave(etag, text, cb);
        else source.rulesSave(account, row, etag, rosterEtag, text, cb);
    }

    /**
     * Cambió el archivo de reglas o el roster entretanto (§11). Se vuelve a leer, buscando la fila
     * otra vez si hace falta, y se ofrece «Recuperar mi versión» con lo que el dueño quiso guardar.
     */
    private void conflict(String text) {
        mine = text;
        read(false);
    }

    // --- Utilidades -------------------------------------------------------------------------------

    private void leave() {
        if (loaded && !busy && !current().compose().equals(loadedText)) {
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
        replyEmail.setEnabled(false);
        replySms.setEnabled(false);
        body.setEnabled(false);
        previewButton.setEnabled(false);
        saveButton.setEnabled(false);
    }

    private void setBusy(boolean b) {
        busy = b;
        progress.setVisibility(b ? View.VISIBLE : View.GONE);
        boolean can = !b && source.available();
        previewButton.setEnabled(can);
        saveButton.setEnabled(can);
    }

    private void showNote(String text) {
        note.setText(text);
        note.setVisibility(View.VISIBLE);
    }

    private void showError(String text) {
        error.setText(text);
        error.setVisibility(View.VISIBLE);
    }

    private void updateSize() {
        RulesText t = current();
        size.setText(getString(R.string.rules_size, Formatter.formatShortFileSize(this, t.bytes())));
        size.setTextColor(getResources().getColor(t.tooLarge() ? R.color.msg_failed : R.color.paynani_gris, null));
    }

    private String label(String reply) {
        return getString(RulesText.NEVER.equals(reply) ? R.string.rules_reply_never : R.string.rules_reply_normal);
    }

    private String value(MaterialAutoCompleteTextView v) {
        return getString(R.string.rules_reply_never).contentEquals(v.getText()) ? RulesText.NEVER : RulesText.NORMAL;
    }

    private String failureText(RosterSource.Failure f) {
        switch (f.code) {
            case "offline": return getString(R.string.roster_err_offline);
            case "timeout": return getString(R.string.roster_err_timeout);
            case "write_failed": return getString(R.string.roster_err_write_failed);
            case "invalid":
                if ("too_large".equals(f.reason)) return getString(R.string.rules_err_too_large);
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
