package com.iaaorgmx.paynani.roster;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Una fila del roster de paynani (PRD #409, R-2 y R-3; SMS_GATEWAY.md §11): los cinco campos de
 * roster.md, más lo que la pasarela dice de ella. Los textos vacíos son "", nunca null.
 */
public final class RosterMember {

    public static final String HUMAN = "Human";
    public static final String AI_AGENT = "AI Agent";

    /** La posición en la tabla de contactos, desde 1, como la dio roster.list; 0 en un alta. */
    public final int row;
    public final String name;
    public final String email;
    public final String type;
    public final String github;
    /** La celda Phone, cada número en E.164. */
    public final List<String> phones;
    /** La fila del dueño (el primer Human): no se borra y sigue siendo Human (R-4). */
    public final boolean owner;
    /** Tiene archivo de instrucciones propio (R-6). */
    public final boolean hasRules;
    public final boolean rulesEnabled;
    /**
     * La celda Telegram tiene algo (has_telegram). La app nunca ve un id numérico (paynani FR-15):
     * si la celda es un id, hasTelegram es true y telegramUsername "".
     */
    public final boolean hasTelegram;
    /** El @usuario de la celda Telegram (telegram_username, con la @), o "" (paynani #628, Q270 = A). */
    public final String telegramUsername;
    /**
     * En un borrador, lo que se manda en la llave telegram de roster.save: null no la manda (la fila
     * conserva su valor, también un id), "" la borra y "@usuario" la cambia. En una fila leída, null.
     */
    public final String telegramEdit;

    public RosterMember(int row, String name, String email, String type, String github, List<String> phones,
                        boolean owner, boolean hasRules, boolean rulesEnabled) {
        this(row, name, email, type, github, phones, owner, hasRules, rulesEnabled, false, "", null);
    }

    public RosterMember(int row, String name, String email, String type, String github, List<String> phones,
                        boolean owner, boolean hasRules, boolean rulesEnabled, boolean hasTelegram,
                        String telegramUsername, String telegramEdit) {
        this.row = Math.max(0, row);
        this.name = clean(name);
        this.email = clean(email);
        this.type = AI_AGENT.equals(clean(type)) ? AI_AGENT : HUMAN;
        this.github = clean(github);
        List<String> p = new ArrayList<>();
        if (phones != null) for (String s : phones) if (!clean(s).isEmpty()) p.add(clean(s));
        this.phones = Collections.unmodifiableList(p);
        this.owner = owner;
        this.hasRules = hasRules;
        this.rulesEnabled = rulesEnabled;
        this.telegramUsername = clean(telegramUsername);
        this.hasTelegram = hasTelegram || !this.telegramUsername.isEmpty();
        this.telegramEdit = telegramEdit == null ? null : clean(telegramEdit);
    }

    /** Una fila con lo que se escribió en el formulario; row 0 es un alta. */
    public static RosterMember draft(int row, String name, String email, String type, String github,
                                     List<String> phones) {
        return new RosterMember(row, name, email, type, github, phones, false, false, false);
    }

    /**
     * Un borrador con el campo Telegram: `was` es la fila como se leyó (null en un alta) y `typed` lo
     * que quedó escrito. Si no cambió, telegramEdit es null y la llave no se manda.
     */
    public static RosterMember draft(int row, String name, String email, String type, String github,
                                     List<String> phones, RosterMember was, String typed) {
        boolean had = was != null && was.hasTelegram;
        String username = was == null ? "" : was.telegramUsername;
        String t = clean(typed);
        String edit = sameUsername(t, username) ? null : t;
        // Una celda con id no se ve ni se cambia desde aquí: nunca se manda la llave.
        if (had && username.isEmpty()) edit = null;
        return new RosterMember(row, name, email, type, github, phones, false, false, false, had, username, edit);
    }

    /** La celda Telegram después de guardar este borrador tiene algo (cuenta como canal). */
    public boolean telegramAfterSave() {
        if (telegramEdit == null) return hasTelegram;
        return !telegramEdit.isEmpty();
    }

    /** Si la celda es un id numérico: hay Telegram pero la app no ve el @usuario. */
    public boolean telegramIsId() {
        return hasTelegram && telegramUsername.isEmpty();
    }

    private static boolean sameUsername(String a, String b) {
        String x = a.startsWith("@") ? a.substring(1) : a;
        String y = b.startsWith("@") ? b.substring(1) : b;
        return x.equalsIgnoreCase(y);
    }

    public boolean isNew() {
        return row == 0;
    }

    /** Los campos en una cadena (con Telegram como queda escrito): dos borradores con la misma son el mismo contacto escrito (#133). */
    public String key() {
        return String.join("\n", name, email, type, github, String.join(",", phones),
                telegramEdit == null ? telegramUsername : telegramEdit);
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim();
    }
}
