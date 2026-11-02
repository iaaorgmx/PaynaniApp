package com.iaaorgmx.paynani.roster;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.iaaorgmx.paynani.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Alta y edición de un contacto del roster (paynani #409, R-3 a R-5; #133), en pantalla completa
 * como {@link RulesEditorActivity}. Lee la lista al abrir, para validar contra ella (RosterValidation)
 * y guardar con su etag; nada se da por guardado hasta que la pasarela responde (R-5). Al guardar o
 * borrar regresa con {@link #EXTRA_MESSAGE} para que «Roster» lo diga.
 */
public class ContactEditorActivity extends AppCompatActivity {

    /** El texto que muestra «Roster» al volver con RESULT_OK. */
    public static final String EXTRA_MESSAGE = "message";

    private static final String EXTRA_ACCOUNT = "account";
    private static final String EXTRA_ROW = "row";
    private static final String EXTRA_NAME = "name";
    private static final String EXTRA_EMAIL = "email";
    private static final String EXTRA_PHONE = "phone";
    private static final String EXTRA_READ_ONLY = "read_only";
    private static final String EXTRA_TYPE = "type";
    private static final String EXTRA_GITHUB = "github";
    private static final String EXTRA_PHONES = "phones";
    private static final String EXTRA_HAS_TELEGRAM = "has_telegram";
    private static final String EXTRA_TELEGRAM_USERNAME = "telegram_username";
    private static final String EXTRA_OWNER = "owner";
    private static final String EXTRA_HAS_RULES = "has_rules";
    private static final String EXTRA_RULES_ENABLED = "rules_enabled";

    /** Un contacto nuevo en esa cuenta (null es la del agente). */
    public static Intent forNew(Context c, String account) {
        return forNew(c, account, null, null, null);
    }

    /**
     * Un contacto nuevo con lo que ya se sabe de él, por ejemplo desde una conversación (PRD #137,
     * FR-9 y FR-15): nombre, correo y teléfono en E.164, cada uno null si no se tiene. Nada se
     * guarda hasta «Save».
     */
    public static Intent forNew(Context c, String account, String name, String email, String phone) {
        return base(c).putExtra(EXTRA_ACCOUNT, account).putExtra(EXTRA_ROW, 0)
                .putExtra(EXTRA_NAME, name).putExtra(EXTRA_EMAIL, email).putExtra(EXTRA_PHONE, phone);
    }

    /**
     * Un contacto de una cuenta que no se puede editar (unreadable, o sin pasarela; FR-12 y FR-16):
     * se muestra tal como lo dio roster.list, sin volver a leer, con todo deshabilitado.
     */
    public static Intent forReadOnly(Context c, String account, RosterMember m) {
        return forMember(c, account, m)
                .putExtra(EXTRA_READ_ONLY, true)
                .putExtra(EXTRA_TYPE, m.type)
                .putExtra(EXTRA_GITHUB, m.github)
                .putExtra(EXTRA_PHONES, m.phones.toArray(new String[0]))
                .putExtra(EXTRA_HAS_TELEGRAM, m.hasTelegram)
                .putExtra(EXTRA_TELEGRAM_USERNAME, m.telegramUsername)
                .putExtra(EXTRA_OWNER, m.owner)
                .putExtra(EXTRA_HAS_RULES, m.hasRules)
                .putExtra(EXTRA_RULES_ENABLED, m.rulesEnabled);
    }

    /** Un contacto de roster.list: la fila, y su nombre y correo para encontrarlo si la fila se movió. */
    public static Intent forMember(Context c, String account, RosterMember m) {
        return base(c)
                .putExtra(EXTRA_ACCOUNT, account)
                .putExtra(EXTRA_ROW, m.row)
                .putExtra(EXTRA_NAME, m.name)
                .putExtra(EXTRA_EMAIL, m.email);
    }

    /** Sin FLAG_ACTIVITY_NO_USER_ACTION, Main2Activity toma la salida por un «dejar la app» (#125). */
    private static Intent base(Context c) {
        return new Intent(c, ContactEditorActivity.class).addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION);
    }

    private RosterSource source;
    private String account;
    /** 0 mientras es un alta; la fila del contacto si no. */
    private int row;
    private String name;
    private String email;

    private MaterialToolbar toolbar;
    private LinearProgressIndicator progress;
    private TextInputLayout nameL;
    private TextInputLayout emailL;
    private TextInputLayout phonesL;
    private TextInputLayout githubL;
    private TextInputLayout telegramL;
    private TextInputEditText nameE;
    private TextInputEditText emailE;
    private TextInputEditText phonesE;
    private TextInputEditText githubE;
    private TextInputEditText telegramE;
    private RadioGroup type;
    private View rulesRow;
    private TextView rulesStatus;
    private TextView error;
    private MaterialButton saveButton;
    private MaterialButton deleteButton;

    private RosterSource.Roster roster;
    /** La fila en la lista leída; null en un alta. */
    private RosterMember current;
    /** Si los campos ya tienen lo leído (o lo que había antes de rotar): una lectura nueva no los pisa. */
    private boolean filled;
    /** Los campos como se llenaron, para saber si hay cambios sin guardar. */
    private String filledKey = "";
    private boolean busy;
    /** Guardando o borrando: busy también cubre las lecturas, y de esas sí se puede salir. */
    private boolean writing;
    private boolean broken;
    /** FR-16: se ve, no se edita. */
    private boolean readOnly;

    private final ActivityResultLauncher<Intent> rulesLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            // Guardar instrucciones puede cambiar el roster (la columna Rules) y su etag: se vuelve a leer.
            r -> load(null));

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_Paynani_M3);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_contact);
        source = RosterSources.get(this);
        Intent i = getIntent();
        account = i.getStringExtra(EXTRA_ACCOUNT);
        row = i.getIntExtra(EXTRA_ROW, 0);
        name = i.getStringExtra(EXTRA_NAME);
        email = i.getStringExtra(EXTRA_EMAIL);

        toolbar = findViewById(R.id.contact_toolbar);
        View bar = findViewById(R.id.contact_bar);
        // De borde a borde (targetSdk 35), como el editor de instrucciones; el teclado con adjustResize.
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

        progress = findViewById(R.id.contact_progress);
        nameL = findViewById(R.id.roster_edit_name_layout);
        emailL = findViewById(R.id.roster_edit_email_layout);
        phonesL = findViewById(R.id.roster_edit_phones_layout);
        githubL = findViewById(R.id.roster_edit_github_layout);
        telegramL = findViewById(R.id.roster_edit_telegram_layout);
        nameE = findViewById(R.id.roster_edit_name);
        emailE = findViewById(R.id.roster_edit_email);
        phonesE = findViewById(R.id.roster_edit_phones);
        githubE = findViewById(R.id.roster_edit_github);
        telegramE = findViewById(R.id.roster_edit_telegram);
        type = findViewById(R.id.roster_edit_type);
        rulesRow = findViewById(R.id.contact_rules_row);
        rulesStatus = findViewById(R.id.contact_rules_status);
        error = findViewById(R.id.roster_edit_error);
        saveButton = findViewById(R.id.contact_save);
        deleteButton = findViewById(R.id.contact_delete);
        saveButton.setOnClickListener(v -> save());
        deleteButton.setOnClickListener(v -> confirmDelete());
        rulesRow.setOnClickListener(v -> openRules());

        if (savedInstanceState != null) {
            // Los campos con id se restauran solos; esto es lo que no está en una vista.
            row = savedInstanceState.getInt("row", row);
            name = savedInstanceState.getString("name", name);
            email = savedInstanceState.getString("email", email);
            filled = savedInstanceState.getBoolean("filled");
            filledKey = savedInstanceState.getString("filled_key", "");
            CharSequence e = savedInstanceState.getCharSequence("error");
            if (e != null) showError(e, savedInstanceState.getInt("error_color"));
        } else {
            type.check(R.id.roster_edit_type_human);
            if (row == 0) prefill(i);
        }
        readOnly = i.getBooleanExtra(EXTRA_READ_ONLY, false);
        updateTitle();
        if (readOnly) showReadOnly(i);
        else load(null);
    }

    /** Lo que trae un alta desde una conversación (FR-15). En un alta, name y email no buscan una fila. */
    private void prefill(Intent i) {
        if (name != null) nameE.setText(name);
        if (email != null) emailE.setText(email);
        String phone = i.getStringExtra(EXTRA_PHONE);
        if (phone != null) phonesE.setText(phone);
        name = null;
        email = null;
    }

    /** FR-16: el contacto como llegó, sin pedir la lista (la cuenta no se puede leer o no hay pasarela). */
    private void showReadOnly(Intent i) {
        String[] phones = i.getStringArrayExtra(EXTRA_PHONES);
        current = new RosterMember(row, name, email, i.getStringExtra(EXTRA_TYPE), i.getStringExtra(EXTRA_GITHUB),
                phones == null ? new ArrayList<>() : Arrays.asList(phones), i.getBooleanExtra(EXTRA_OWNER, false),
                i.getBooleanExtra(EXTRA_HAS_RULES, false), i.getBooleanExtra(EXTRA_RULES_ENABLED, false),
                i.getBooleanExtra(EXTRA_HAS_TELEGRAM, false), i.getStringExtra(EXTRA_TELEGRAM_USERNAME), null);
        fill(current);
        filled = true;
        filledKey = typed().key();
        broken = true; // nada se guarda ni se pregunta al salir
        disableAll();
        deleteButton.setVisibility(current.owner ? View.GONE : View.VISIBLE);
        rulesRow.setVisibility(View.VISIBLE);
        rulesRow.setAlpha(0.5f);
        rulesStatus.setText(!current.hasRules ? R.string.rules_member_none
                : current.rulesEnabled ? R.string.rules_member_on : R.string.rules_member_off);
        showError(getString(R.string.roster_read_only), getResources().getColor(R.color.paynani_gris, null));
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("row", row);
        out.putString("name", name);
        out.putString("email", email);
        out.putBoolean("filled", filled);
        out.putString("filled_key", filledKey);
        if (error.getVisibility() == View.VISIBLE) {
            out.putCharSequence("error", error.getText());
            out.putInt("error_color", error.getCurrentTextColor());
        }
    }

    // --- Lectura ----------------------------------------------------------------------------------

    /**
     * Lee la lista y busca el contacto: por su correo (o su nombre si no tiene), o, tras un
     * conflict, por el correo o un teléfono de lo que el dueño quiso guardar. Los campos solo se
     * llenan la primera vez: después se conserva lo escrito.
     */
    private void load(RosterMember relocateBy) {
        if (!source.available()) {
            fatal(getString(R.string.roster_err_offline));
            return;
        }
        setBusy(true);
        source.list(account, new RosterSource.Callback<RosterSource.Roster>() {
            @Override
            public void onResult(RosterSource.Roster r) {
                if (isFinishing()) return;
                roster = r; // antes de setBusy, que habilita «Save» solo con la lista leída
                setBusy(false);
                current = relocateBy != null ? find(r.members, relocateBy) : locate(r.members);
                if (row > 0 && relocateBy == null && current == null) {
                    fatal(getString(R.string.rules_err_contact_gone));
                    return;
                }
                if (relocateBy != null && current == null && !relocateBy.isNew() && !canRelocate(relocateBy)) {
                    // Sin correo, teléfono ni @usuario (sólo un id que la app no ve), no hay cómo
                    // encontrarlo: no se vuelve un alta, que duplicaría el contacto (Ximena en #167).
                    fatal(getString(R.string.roster_err_reopen));
                    return;
                }
                if (current != null) {
                    row = current.row;
                    name = current.name;
                    email = current.email;
                } else {
                    row = 0; // tras un conflict ya no está: se guarda como alta, como antes en el diálogo
                }
                if (!filled) {
                    fill(current);
                    filledKey = typed().key();
                    filled = true;
                }
                applyRoster();
            }

            @Override
            public void onError(RosterSource.Failure f) {
                if (isFinishing()) return;
                setBusy(false);
                fatal(failureText(f));
            }
        });
    }

    private RosterMember locate(List<RosterMember> members) {
        if (row == 0) return null;
        for (RosterMember m : members) {
            boolean same = TextUtils.isEmpty(email) ? m.email.isEmpty() && m.name.equals(name)
                    : m.email.equalsIgnoreCase(email);
            if (same) return m;
        }
        return null;
    }

    /**
     * La fila que corresponde a un borrador en la lista nueva: por correo, por teléfono, o por el
     * @usuario de Telegram (el que tenía la fila, o el que se escribió).
     */
    static RosterMember find(List<RosterMember> members, RosterMember draft) {
        for (RosterMember m : members) {
            if (!draft.email.isEmpty() && draft.email.equalsIgnoreCase(m.email)) return m;
        }
        for (RosterMember m : members) {
            for (String p : draft.phones) if (m.phones.contains(p)) return m;
        }
        for (String tg : telegramKeys(draft)) {
            for (RosterMember m : members) if (tg.equalsIgnoreCase(m.telegramUsername)) return m;
        }
        return null;
    }

    /** Si find() tiene con qué buscar este borrador: correo, teléfono o un @usuario. */
    static boolean canRelocate(RosterMember draft) {
        return !draft.email.isEmpty() || !draft.phones.isEmpty() || !telegramKeys(draft).isEmpty();
    }

    private static List<String> telegramKeys(RosterMember draft) {
        List<String> out = new ArrayList<>();
        if (!draft.telegramUsername.isEmpty()) out.add(draft.telegramUsername);
        String typed = draft.telegramEdit == null ? null : RosterValidation.telegramUsername(draft.telegramEdit);
        if (typed != null && !out.contains(typed)) out.add(typed);
        return out;
    }

    private void fill(RosterMember m) {
        if (m == null) return;
        nameE.setText(m.name);
        emailE.setText(m.email);
        phonesE.setText(TextUtils.join(", ", m.phones));
        githubE.setText(m.github);
        fillTelegram(m);
        type.check(RosterMember.AI_AGENT.equals(m.type) ? R.id.roster_edit_type_agent : R.id.roster_edit_type_human);
    }

    /**
     * El campo Telegram (paynani #628, Q270 = A): el @usuario se ve y se edita. Un id no llega a la
     * app; con has_telegram y sin @usuario el campo dice «Configurado en PaynaniWeb» y no se edita.
     * Sin la columna Telegram el campo sí se edita: la pasarela la crea al guardar (Q269 = A).
     */
    private void fillTelegram(RosterMember m) {
        boolean id = m.telegramIsId();
        telegramE.setText(id ? "" : m.telegramUsername);
        telegramL.setEnabled(!id && !readOnly);
        telegramL.setHelperText(getString(id ? R.string.roster_field_telegram_id : R.string.roster_field_telegram_help));
    }

    /**
     * El estado del campo Telegram según la fila leída, también después de recargar (un conflict):
     * si ahora la celda es un id, el campo se vacía y se deshabilita; si dejó de serlo, se habilita
     * con su @usuario. Lo escrito en una fila con @usuario se queda.
     */
    private void applyTelegram() {
        boolean id = current != null && current.telegramIsId();
        if (id) telegramE.setText("");
        else if (!telegramL.isEnabled() && current != null) telegramE.setText(current.telegramUsername);
        telegramL.setEnabled(!id && !readOnly);
        telegramL.setHelperText(getString(id ? R.string.roster_field_telegram_id : R.string.roster_field_telegram_help));
    }

    /** Lo que depende de la lista leída: el dueño, las columnas, Remove y la fila de instrucciones. */
    private void applyRoster() {
        updateTitle();
        boolean owner = current != null && current.owner;
        findViewById(R.id.roster_edit_type_agent).setEnabled(!owner);
        if (owner && error.getVisibility() != View.VISIBLE) {
            showError(getString(R.string.roster_owner_locked), getResources().getColor(R.color.paynani_gris, null));
        }
        // Sin la columna en el archivo, el campo no se edita (§11, «Columnas»).
        disableIfNoColumn(phonesL, "Phone");
        applyTelegram();
        disableIfNoColumn(githubL, "GitHub");
        deleteButton.setVisibility(current != null && !owner ? View.VISIBLE : View.GONE);

        boolean rules = source.rulesAvailable();
        rulesRow.setVisibility(rules ? View.VISIBLE : View.GONE);
        if (rules) {
            boolean can = current != null;
            rulesRow.setEnabled(can);
            rulesRow.setAlpha(can ? 1f : 0.5f);
            rulesStatus.setText(!can ? R.string.roster_rules_save_first
                    : !current.hasRules ? R.string.rules_member_none
                    : current.rulesEnabled ? R.string.rules_member_on : R.string.rules_member_off);
        }
    }

    private void updateTitle() {
        toolbar.setTitle(row == 0 ? R.string.roster_edit_title_new : R.string.roster_edit_title);
        toolbar.setSubtitle(row == 0 ? null : name);
    }

    private void disableIfNoColumn(TextInputLayout l, String column) {
        if (roster.hasColumn(column)) return;
        l.setEnabled(false);
        l.setHelperText(getString(R.string.roster_field_no_column, column));
    }

    // --- Guardado y baja ----------------------------------------------------------------------------

    private RosterMember typed() {
        return RosterMember.draft(row, text(nameE), text(emailE),
                type.getCheckedRadioButtonId() == R.id.roster_edit_type_agent ? RosterMember.AI_AGENT : RosterMember.HUMAN,
                text(githubE), splitPhones(text(phonesE)), current, text(telegramE));
    }

    private void save() {
        if (roster == null || busy) return;
        RosterMember draft = typed();
        for (TextInputLayout l : Arrays.asList(nameL, emailL, phonesL, githubL, telegramL)) l.setError(null);
        RosterValidation.Problem p = RosterValidation.check(draft, roster.members);
        if (p != null) {
            fieldFor(p.field).setError(reasonText(p.reason));
            return;
        }
        error.setVisibility(View.GONE);
        setBusy(true);
        writing = true;
        source.save(account, roster.etag, draft, new RosterSource.Callback<RosterSource.Roster>() {
            @Override
            public void onResult(RosterSource.Roster result) {
                done(R.string.roster_saved);
            }

            @Override
            public void onError(RosterSource.Failure f) {
                if (isFinishing()) return;
                writing = false;
                setBusy(false);
                if ("invalid".equals(f.code)) {
                    // Un error de Telegram va en su campo; el detalle de la pasarela no se muestra
                    // (podría nombrar el valor).
                    if ("telegram".equals(f.field)) telegramL.setError(reasonText(f.reason.isEmpty() ? "telegram" : f.reason));
                    else showFailure(reasonText(f.reason));
                    return;
                }
                if ("conflict".equals(f.code)) {
                    // El roster cambió entretanto (§11): se vuelve a leer y lo escrito se queda, sobre
                    // la misma fila si sigue (por su correo o un teléfono), o como alta.
                    showFailure(getString(R.string.roster_err_conflict));
                    load(draft);
                    return;
                }
                // timeout u otro: se vuelve a leer, y lo escrito se queda. «reload» no es un error que mostrar.
                if (!"reload".equals(f.code)) showFailure(failureText(f));
                load(draft);
            }
        });
    }

    private void confirmDelete() {
        if (current == null || current.owner || busy) return;
        RosterMember m = current;
        AlertDialog d = new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_Paynani_M3_Dialog)
                .setTitle(getString(R.string.roster_delete_title, m.name))
                .setMessage(R.string.roster_delete_message)
                .setNegativeButton(R.string.roster_cancel, null)
                .setPositiveButton(R.string.roster_delete, (x, w) -> delete(m))
                .create();
        d.setOnShowListener(x -> tintButtons(d));
        d.show();
    }

    private void delete(RosterMember m) {
        error.setVisibility(View.GONE);
        setBusy(true);
        writing = true;
        source.delete(account, roster.etag, m.row, new RosterSource.Callback<RosterSource.Roster>() {
            @Override
            public void onResult(RosterSource.Roster result) {
                done(R.string.roster_deleted);
            }

            @Override
            public void onError(RosterSource.Failure f) {
                if (isFinishing()) return;
                writing = false;
                setBusy(false);
                if (!"reload".equals(f.code)) showFailure(failureText(f));
                load(null);
            }
        });
    }

    /** La pasarela lo confirmó (R-5): de regreso a «Roster», que vuelve a leer la lista. */
    private void done(int message) {
        if (isFinishing()) return;
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_MESSAGE, getString(message)));
        finish();
    }

    private void openRules() {
        if (current == null || roster == null || busy) return;
        rulesLauncher.launch(RulesEditorActivity.forMember(this, account, current, roster.etag));
    }

    // --- Utilidades -------------------------------------------------------------------------------

    private void leave() {
        // Mientras guarda o borra no se sale: la respuesta trae el aviso para «Roster» (revisión de Metis).
        // Mientras solo lee, sí: no hay nada escrito que perder.
        if (writing) return;
        if (filled && !broken && !typed().key().equals(filledKey)) {
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
        broken = true;
        showFailure(message);
        disableAll();
    }

    private void disableAll() {
        for (View v : Arrays.asList(nameL, emailL, phonesL, githubL, telegramL, type, rulesRow, saveButton, deleteButton)) {
            v.setEnabled(false);
        }
        for (int id : new int[]{R.id.roster_edit_type_human, R.id.roster_edit_type_agent}) {
            findViewById(id).setEnabled(false);
        }
    }

    private void setBusy(boolean b) {
        busy = b;
        progress.setVisibility(b ? View.VISIBLE : View.GONE);
        saveButton.setEnabled(!b && !broken && roster != null);
        deleteButton.setEnabled(!b && !broken);
    }

    private void showFailure(CharSequence text) {
        showError(text, getResources().getColor(R.color.msg_failed, null));
    }

    private void showError(CharSequence text, int color) {
        error.setText(text);
        error.setTextColor(color);
        error.setVisibility(View.VISIBLE);
    }

    private TextInputLayout fieldFor(String field) {
        switch (field) {
            case "name": return nameL;
            case "phones": return phonesL;
            case "github": return githubL;
            case "telegram": return telegramL;
            default: return emailL;
        }
    }

    /** El texto de un reason de §11, en el idioma de la app. */
    private String reasonText(String reason) {
        switch (reason) {
            case RosterValidation.REQUIRED: return getString(R.string.roster_err_required);
            case RosterValidation.EMAIL_FORMAT: return getString(R.string.roster_err_email_format);
            case RosterValidation.PHONE_E164: return getString(R.string.roster_err_phone_e164);
            case RosterValidation.NO_CHANNEL: return getString(R.string.roster_err_no_channel);
            case RosterValidation.DUPLICATE_EMAIL: return getString(R.string.roster_err_duplicate_email);
            case RosterValidation.DUPLICATE_PHONE: return getString(R.string.roster_err_duplicate_phone);
            case RosterValidation.GITHUB: return getString(R.string.roster_err_github);
            case RosterValidation.TELEGRAM: return getString(R.string.roster_err_telegram);
            case RosterValidation.TELEGRAM_USERNAME_ONLY: return getString(R.string.roster_err_telegram_username_only);
            case RosterValidation.DUPLICATE_TELEGRAM: return getString(R.string.roster_err_duplicate_telegram);
            case "owner_type": return getString(R.string.roster_err_owner_type);
            default: return getString(R.string.roster_err_other, reason);
        }
    }

    private String failureText(RosterSource.Failure f) {
        switch (f.code) {
            case "conflict": return getString(R.string.roster_err_conflict);
            case "offline": return getString(R.string.roster_err_offline);
            case "timeout": return getString(R.string.roster_err_timeout);
            case "write_failed": return getString(R.string.roster_err_write_failed);
            case "owner_row": return getString(R.string.roster_owner_locked);
            default: return getString(R.string.roster_err_other, f.code);
        }
    }

    /** Los botones del diálogo en grana (sobre el tema AppCompat de la app, M3 los pinta magenta). */
    private void tintButtons(AlertDialog d) {
        int color = getResources().getColor(R.color.paynani_grana, null);
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            android.widget.Button b = d.getButton(which);
            if (b != null) b.setTextColor(color);
        }
    }

    private static String text(TextInputEditText e) {
        return e.getText() == null ? "" : e.getText().toString().trim();
    }

    /** «+52…, +1…» o con espacios y saltos: cada número por separado, sin vacíos. */
    static List<String> splitPhones(String s) {
        List<String> out = new ArrayList<>();
        for (String p : s.split("[,;\\n]")) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
