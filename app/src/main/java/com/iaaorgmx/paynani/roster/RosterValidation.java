package com.iaaorgmx.paynani.roster;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * La validación de una fila antes de mandarla (PRD #409, R-3). Los field y reason son los de
 * SMS_GATEWAY.md §11 (roster.save), así el mismo texto sirve para un error de aquí o de la
 * pasarela, que vuelve a validar.
 */
public final class RosterValidation {

    public static final String REQUIRED = "required";
    public static final String EMAIL_FORMAT = "email_format";
    public static final String PHONE_E164 = "phone_e164";
    public static final String NO_CHANNEL = "no_channel";
    public static final String DUPLICATE_EMAIL = "duplicate_email";
    public static final String DUPLICATE_PHONE = "duplicate_phone";
    public static final String GITHUB = "github";
    public static final String TELEGRAM = "telegram";
    public static final String TELEGRAM_USERNAME_ONLY = "telegram_username_only";
    public static final String DUPLICATE_TELEGRAM = "duplicate_telegram";

    // El mismo patrón que la pasarela (roster_edit.EMAIL): sin espacios ni |<>,;, que romperían
    // la tabla o la dirección.
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s|<>,;]+@[^@\\s|<>,;]+\\.[^@\\s|<>,;]+$");
    private static final Pattern E164 = Pattern.compile("^\\+[1-9][0-9]{7,14}$");
    // Login de GitHub: letras, números y guiones, sin guion al inicio, hasta 39.
    private static final Pattern GITHUB_LOGIN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9-]{0,38}$");
    // Como roster.telegram_value: un @usuario de Telegram tiene 5 a 32 caracteres, letras, dígitos
    // o _, y empieza con letra; sólo dígitos es un id, que desde la app no se escribe (Q270 = A).
    private static final Pattern TG_USERNAME = Pattern.compile("^[a-z][a-z0-9_]{4,31}$");
    private static final Pattern TG_ID = Pattern.compile("^[0-9]{1,20}$");

    /** Un problema: el campo ("name", "email", "phones", "github" o "telegram") y el reason del protocolo. */
    public static final class Problem {
        public final String field;
        public final String reason;

        public Problem(String field, String reason) {
            this.field = field;
            this.reason = reason;
        }
    }

    private RosterValidation() {
    }

    /**
     * @param roster las filas del mismo roster; la que se edita (mismo row) no cuenta como duplicado
     * @return el primer problema, o null si la fila se puede mandar
     */
    public static Problem check(RosterMember m, List<RosterMember> roster) {
        if (m.name.isEmpty() || m.name.contains("|") || m.name.contains("\n")) return new Problem("name", REQUIRED);
        if (!m.email.isEmpty() && !EMAIL.matcher(m.email).matches()) return new Problem("email", EMAIL_FORMAT);
        for (String p : m.phones) if (!E164.matcher(p).matches()) return new Problem("phones", PHONE_E164);
        String tg = m.telegramEdit == null || m.telegramEdit.isEmpty() ? null : telegramUsername(m.telegramEdit);
        if (m.telegramEdit != null && !m.telegramEdit.isEmpty()) {
            String bare = m.telegramEdit.startsWith("@") ? m.telegramEdit.substring(1) : m.telegramEdit;
            if (TG_ID.matcher(bare).matches()) return new Problem("telegram", TELEGRAM_USERNAME_ONLY);
            if (tg == null) return new Problem("telegram", TELEGRAM);
        }
        // Telegram cuenta como canal (paynani PRD #609): una fila sólo con Telegram se guarda.
        if (m.email.isEmpty() && m.phones.isEmpty() && !m.telegramAfterSave()) return new Problem("email", NO_CHANNEL);
        if (!m.github.isEmpty() && !GITHUB_LOGIN.matcher(m.github).matches()) return new Problem("github", GITHUB);
        for (RosterMember o : roster) {
            if (!m.isNew() && m.row == o.row) continue;
            if (!m.email.isEmpty() && m.email.toLowerCase(Locale.ROOT).equals(o.email.toLowerCase(Locale.ROOT))) {
                return new Problem("email", DUPLICATE_EMAIL);
            }
            for (String p : m.phones) if (o.phones.contains(p)) return new Problem("phones", DUPLICATE_PHONE);
            if (tg != null && tg.equalsIgnoreCase(o.telegramUsername)) return new Problem("telegram", DUPLICATE_TELEGRAM);
        }
        return null;
    }

    /** El @usuario normalizado como lo guarda la pasarela ("@" y minúsculas), o null si no es válido. */
    public static String telegramUsername(String raw) {
        String v = raw == null ? "" : raw.trim();
        if (v.startsWith("@")) v = v.substring(1);
        v = v.toLowerCase(Locale.ROOT);
        return TG_USERNAME.matcher(v).matches() ? "@" + v : null;
    }
}
