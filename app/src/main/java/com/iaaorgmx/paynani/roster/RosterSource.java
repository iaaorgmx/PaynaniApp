package com.iaaorgmx.paynani.roster;

import java.util.Collections;
import java.util.List;

/**
 * El roster de la pasarela (SMS_GATEWAY.md §11): roster.accounts, roster.list, roster.save y
 * roster.delete. La implementación real habla por el WebSocket del teléfono emparejado; la
 * pantalla también se prueba con {@link DemoRosterSource}.
 *
 * Las respuestas llegan en el hilo principal. Un cambio se da por guardado solo con onResult (R-5).
 */
public interface RosterSource {

    /** Una cuenta de la instalación (R-2, Q2). id null es la cuenta del agente (roster.md). */
    final class Account {
        public final String id;
        public final String email;
        public final int members;
        /** ok, missing (todavía sin archivo) o unreadable (no se puede editar). */
        public final String status;

        public Account(String id, String email, int members, String status) {
            this.id = id;
            this.email = email == null ? "" : email;
            this.members = members;
            this.status = status == null ? "ok" : status;
        }

        public boolean primary() {
            return id == null;
        }

        public boolean editable() {
            return !"unreadable".equals(status);
        }
    }

    /** Lo que responden roster.list, roster.save y roster.delete. */
    final class Roster {
        public final String etag;
        public final List<RosterMember> members;
        /** Las columnas del archivo: sin Phone o GitHub, esos campos no se editan (§11). */
        public final List<String> columns;
        public final boolean defaultRules;
        /** La fila guardada, en roster.save; 0 si no aplica. */
        public final int savedRow;

        public Roster(String etag, List<RosterMember> members, List<String> columns, boolean defaultRules,
                      int savedRow) {
            this.etag = etag == null ? "" : etag;
            this.members = Collections.unmodifiableList(members);
            this.columns = Collections.unmodifiableList(columns);
            this.defaultRules = defaultRules;
            this.savedRow = savedRow;
        }

        public boolean hasColumn(String name) {
            return columns.contains(name);
        }
    }

    /** Un error de la pasarela: code de §11 (invalid, conflict…), o offline / timeout de la app. */
    final class Failure {
        public final String code;
        public final String field;
        public final String reason;
        public final String detail;

        public Failure(String code, String field, String reason, String detail) {
            this.code = code == null ? "" : code;
            this.field = field == null ? "" : field;
            this.reason = reason == null ? "" : reason;
            this.detail = detail == null ? "" : detail;
        }

        public static Failure of(String code) {
            return new Failure(code, "", "", "");
        }
    }

    /**
     * Lo que responden rules.get, rules.preview, rules.save y sus versiones _default (§11).
     * row 0 es rules/default.md.
     */
    final class Rules {
        public final int row;
        public final String name;
        public final String email;
        /** El id de la columna Rules, o "" si todavía no tiene. */
        public final String id;
        public final boolean exists;
        public final String etag;
        public final String text;
        public final String effective;
        /** El etag de roster.md después de rules.save (la primera vez escribe la columna Rules); "" si no aplica. */
        public final String rosterEtag;

        public Rules(int row, String name, String email, String id, boolean exists, String etag, String text,
                     String effective, String rosterEtag) {
            this.row = row;
            this.name = name == null ? "" : name;
            this.email = email == null ? "" : email;
            this.id = id == null ? "" : id;
            this.exists = exists;
            this.etag = etag == null ? "" : etag;
            this.text = text == null ? "" : text;
            this.effective = effective == null ? "" : effective;
            this.rosterEtag = rosterEtag == null ? "" : rosterEtag;
        }
    }

    /**
     * Lo que responden catchall.get y catchall.save (paynani PRD #535 FR-0a; uno por canal desde el
     * PRD #576). status es off, active o invalid; un invalid trae el archivo entero en text, para
     * corregirlo. Una pasarela con un Catch-All por canal manda `channel`, `file` y `channels`; una
     * vieja no, y entonces `channels` va vacío y el único Catch-All aplica a SMS y WhatsApp (FR-11).
     */
    final class CatchAll {
        public final boolean exists;
        public final boolean enabled;
        public final String text;
        public final String status;
        public final String updatedAt;
        public final String updatedBy;
        public final String etag;
        public final String channel;
        public final String file;
        public final List<String> channels;

        public CatchAll(boolean exists, boolean enabled, String text, String status, String updatedAt,
                        String updatedBy, String etag) {
            this(exists, enabled, text, status, updatedAt, updatedBy, etag, "", "", null);
        }

        public CatchAll(boolean exists, boolean enabled, String text, String status, String updatedAt,
                        String updatedBy, String etag, String channel, String file, List<String> channels) {
            this.exists = exists;
            this.enabled = enabled;
            this.text = text == null ? "" : text;
            this.status = status == null || status.isEmpty() ? "off" : status;
            this.updatedAt = updatedAt == null ? "" : updatedAt;
            this.updatedBy = updatedBy == null ? "" : updatedBy;
            this.etag = etag == null ? "" : etag;
            this.channel = channel == null ? "" : channel;
            this.file = file == null ? "" : file;
            this.channels = channels == null ? java.util.Collections.emptyList()
                    : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(channels));
        }

        /** Si la pasarela tiene un Catch-All por canal (PRD #576). */
        public boolean perChannel() {
            return !channels.isEmpty();
        }

        /** Si este canal todavía lee el archivo único de antes, rules/catch-all.md (FR-4). */
        public boolean legacyFile() {
            return "rules/catch-all.md".equals(file);
        }
    }

    interface Callback<T> {
        void onResult(T result);

        void onError(Failure failure);
    }

    /** Con la pasarela conectada y anunciando roster.list (R-1, Q7). */
    boolean available();

    void accounts(Callback<List<Account>> cb);

    void list(String account, Callback<Roster> cb);

    /** Alta (member.row 0) o edición de esa fila, con el etag que se leyó. */
    void save(String account, String etag, RosterMember member, Callback<Roster> cb);

    void delete(String account, String etag, int row, Callback<Roster> cb);

    /** Si la pasarela anuncia rules.get (el editor de instrucciones, R-6). */
    boolean rulesAvailable();

    /** Si anuncia rules.preview; sin él, la vista previa usa el effective de rules.get (§11). */
    boolean previewAvailable();

    void rulesGet(String account, int row, String rosterEtag, Callback<Rules> cb);

    void rulesPreview(String account, int row, String rosterEtag, String text, Callback<Rules> cb);

    void rulesSave(String account, int row, String etag, String rosterEtag, String text, Callback<Rules> cb);

    void defaultGet(Callback<Rules> cb);

    void defaultSave(String etag, String text, Callback<Rules> cb);

    /**
     * Si la pasarela anuncia catchall.get (paynani PRD #535 FR-A1): sin él, «Catch-All» no aparece.
     * Por omisión no lo hay, para que una fuente que no lo implementa siga compilando.
     */
    default boolean catchAllAvailable() {
        return false;
    }

    /** `channel` es sms o whatsapp; null para una pasarela vieja, que sólo tiene uno (FR-11). */
    default void catchAllGet(String channel, Callback<CatchAll> cb) {
        cb.onError(Failure.of("unsupported"));
    }

    default void catchAllSave(String channel, String etag, boolean enabled, String text, Callback<CatchAll> cb) {
        cb.onError(Failure.of("unsupported"));
    }
}
