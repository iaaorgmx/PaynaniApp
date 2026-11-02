package com.iaaorgmx.paynani.roster;

import java.nio.charset.StandardCharsets;

/**
 * Un archivo de reglas (PRD #409, I-2; SMS_GATEWAY.md §11, rules.save): una cabecera YAML entre
 * «---» y Markdown libre debajo. La pantalla edita la cabecera con controles y el cuerpo como
 * texto; esto separa y vuelve a armar las dos partes. La pasarela es la que valida de verdad.
 */
public final class RulesText {

    public static final String NORMAL = "normal";
    public static final String NEVER = "never";
    /** I-6: 32 KB en UTF-8, el archivo completo. */
    public static final int MAX_BYTES = 32 * 1024;

    public final boolean enabled;
    public final String replyEmail;
    public final String replySms;
    /** El Markdown debajo de la cabecera. */
    public final String body;

    public RulesText(boolean enabled, String replyEmail, String replySms, String body) {
        this.enabled = enabled;
        this.replyEmail = NEVER.equals(replyEmail) ? NEVER : NORMAL;
        this.replySms = NEVER.equals(replySms) ? NEVER : NORMAL;
        this.body = body == null ? "" : body;
    }

    /**
     * Un archivo nuevo: activo, respuesta normal por los dos canales y sin secciones. No lleva un
     * «## General» vacío: por I-4b, al guardarlo así sustituiría a la general con nada.
     */
    public static RulesText blank() {
        return new RulesText(true, NORMAL, NORMAL, "");
    }

    /**
     * Lee lo que trae rules.get. updated_at y updated_by se descartan: los vuelve a escribir la
     * pasarela. Sin cabecera, todo es cuerpo y la cabecera queda como en blank().
     */
    public static RulesText parse(String text) {
        if (text == null || text.isEmpty()) return blank();
        String t = text.replace("\r\n", "\n");
        if (!t.startsWith("---\n")) return new RulesText(true, NORMAL, NORMAL, t);
        int end = t.indexOf("\n---", 3);
        if (end < 0) return new RulesText(true, NORMAL, NORMAL, t);
        String header = t.substring(4, end);
        int after = end + 4;
        if (after < t.length() && t.charAt(after) == '\n') after++;
        String body = after <= t.length() ? t.substring(after) : "";
        if (body.startsWith("\n")) body = body.substring(1);

        boolean enabled = true;
        String email = NORMAL;
        String sms = NORMAL;
        boolean inReply = false;
        for (String raw : header.split("\n")) {
            String line = stripComment(raw);
            if (line.trim().isEmpty()) continue;
            boolean nested = line.startsWith(" ") || line.startsWith("\t");
            String k = key(line);
            String v = value(line);
            if (!nested) {
                inReply = "reply".equals(k);
                if ("enabled".equals(k)) enabled = !"false".equalsIgnoreCase(v);
            } else if (inReply) {
                if ("email".equals(k)) email = v;
                else if ("sms".equals(k)) sms = v;
            }
        }
        return new RulesText(enabled, email, sms, body);
    }

    /** El archivo para rules.save y rules.preview, con la cabecera que acepta la pasarela. */
    public String compose() {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("version: 1\n");
        sb.append("enabled: ").append(enabled ? "true" : "false").append('\n');
        sb.append("reply:\n");
        sb.append("  email: ").append(replyEmail).append('\n');
        sb.append("  sms: ").append(replySms).append('\n');
        sb.append("---\n\n");
        sb.append(body);
        return sb.toString();
    }

    public int bytes() {
        return compose().getBytes(StandardCharsets.UTF_8).length;
    }

    public boolean tooLarge() {
        return bytes() > MAX_BYTES;
    }

    private static String stripComment(String line) {
        int hash = line.indexOf(" #");
        return hash >= 0 ? line.substring(0, hash) : line;
    }

    private static String key(String line) {
        int colon = line.indexOf(':');
        return (colon < 0 ? line : line.substring(0, colon)).trim();
    }

    private static String value(String line) {
        int colon = line.indexOf(':');
        if (colon < 0) return "";
        String v = line.substring(colon + 1).trim();
        if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"))) {
            v = v.substring(1, v.length() - 1);
        }
        return v;
    }
}
