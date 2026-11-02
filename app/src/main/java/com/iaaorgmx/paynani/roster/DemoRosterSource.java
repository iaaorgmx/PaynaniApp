package com.iaaorgmx.paynani.roster;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Un roster en memoria con datos de prueba, que se comporta como la pasarela de SMS_GATEWAY.md §11:
 * etag por escritura, conflict, validación, owner_row. Sirve para la pantalla mientras no hay
 * servidor (R2) y para las pruebas. Nunca son datos reales (#410).
 */
public final class DemoRosterSource implements RosterSource {

    private static final List<String> ALL_COLUMNS = Arrays.asList("Name", "Email", "Type", "GitHub", "Phone", "Rules");

    private final Handler main = new Handler(Looper.getMainLooper());
    private final long delayMs;
    private final Map<String, List<RosterMember>> rosters = new HashMap<>();
    private final Map<String, Integer> versions = new HashMap<>();
    /** Las reglas por cuenta y fila ("cuenta#fila"), y las generales. */
    private final Map<String, String> rules = new HashMap<>();
    private String defaultRules = "---\nversion: 1\nenabled: true\nreply:\n  email: normal\n  sms: normal\n---\n\n"
            + "## General\nResponde en español y con respeto.\n\n## SMS\nMensajes cortos, sin enlaces.\n";
    private int rulesVersion;
    public boolean online = true;

    public DemoRosterSource(long delayMs) {
        this.delayMs = delayMs;
        rosters.put(key(null), new ArrayList<>(Arrays.asList(
                new RosterMember(1, "Dueño Prueba", "duenio@example.com", RosterMember.HUMAN, "",
                        Collections.singletonList("+12105550100"), true, false, false),
                new RosterMember(2, "Agente Prueba", "agente2@example.com", RosterMember.AI_AGENT, "agente-prueba",
                        null, false, true, true, true, "@agente_prueba", null),
                // Con un id de Telegram: la app sólo sabe que tiene (has_telegram), como con la pasarela.
                new RosterMember(3, "Cliente Prueba", "", RosterMember.HUMAN, "",
                        Arrays.asList("+5215512345678", "+5215512345679"), false, false, false, true, "", null))));
        rules.put(key(null) + "#2", "---\nversion: 1\nenabled: true\nreply:\n  email: never\n  sms: normal\n---\n\n"
                + "## General\nSus correos solo disparan el flujo de pagos: no se contestan.\n");
        rosters.put(key("ventas"), new ArrayList<>(Collections.singletonList(
                new RosterMember(1, "Dueño Prueba", "duenio@example.com", RosterMember.HUMAN, "", null, true, false, false))));
    }

    /** El @usuario que queda después de guardar `m` sobre `old` (null en un alta). */
    private static String telegramAfter(RosterMember old, RosterMember m) {
        if (m.telegramEdit == null) return old == null ? "" : old.telegramUsername;
        String tg = RosterValidation.telegramUsername(m.telegramEdit);
        return tg == null ? "" : tg;
    }

    @Override
    public boolean available() {
        return online;
    }

    @Override
    public void accounts(Callback<List<Account>> cb) {
        later(() -> {
            if (!online) { cb.onError(Failure.of("offline")); return; }
            cb.onResult(Arrays.asList(
                    new Account(null, "agente@example.com", rosters.get(key(null)).size(), "ok"),
                    new Account("ventas", "ventas@example.com", rosters.get(key("ventas")).size(), "ok")));
        });
    }

    @Override
    public void list(String account, Callback<Roster> cb) {
        later(() -> {
            if (!online) { cb.onError(Failure.of("offline")); return; }
            List<RosterMember> r = rosters.get(key(account));
            if (r == null) cb.onError(Failure.of("not_found"));
            else cb.onResult(snapshot(account, 0));
        });
    }

    @Override
    public void save(String account, String etag, RosterMember m, Callback<Roster> cb) {
        later(() -> {
            if (!online) { cb.onError(Failure.of("offline")); return; }
            List<RosterMember> r = rosters.get(key(account));
            if (r == null) { cb.onError(Failure.of("not_found")); return; }
            if (!etag(account).equals(etag)) { cb.onError(Failure.of("conflict")); return; }
            RosterValidation.Problem p = RosterValidation.check(m, r);
            if (p != null) { cb.onError(new Failure("invalid", p.field, p.reason, "")); return; }
            int row;
            if (m.isNew()) {
                row = r.size() + 1;
                r.add(new RosterMember(row, m.name, m.email, m.type, m.github, m.phones, false, false, false,
                        m.telegramAfterSave(), telegramAfter(null, m), null));
            } else {
                if (m.row > r.size()) { cb.onError(Failure.of("not_found")); return; }
                RosterMember old = r.get(m.row - 1);
                if (old.owner && !RosterMember.HUMAN.equals(m.type)) {
                    cb.onError(new Failure("invalid", "type", "owner_type", "")); return;
                }
                row = m.row;
                // Como la pasarela: sin la llave telegram la fila conserva su valor (también un id).
                r.set(row - 1, new RosterMember(row, m.name, m.email, m.type, m.github, m.phones,
                        old.owner, old.hasRules, old.rulesEnabled,
                        m.telegramEdit == null ? old.hasTelegram : m.telegramAfterSave(), telegramAfter(old, m), null));
            }
            bump(account);
            cb.onResult(snapshot(account, row));
        });
    }

    @Override
    public void delete(String account, String etag, int row, Callback<Roster> cb) {
        later(() -> {
            if (!online) { cb.onError(Failure.of("offline")); return; }
            List<RosterMember> r = rosters.get(key(account));
            if (r == null || row < 1 || row > r.size()) { cb.onError(Failure.of("not_found")); return; }
            if (!etag(account).equals(etag)) { cb.onError(Failure.of("conflict")); return; }
            if (r.get(row - 1).owner) { cb.onError(Failure.of("owner_row")); return; }
            r.remove(row - 1);
            for (int i = row - 1; i < r.size(); i++) {
                RosterMember o = r.get(i);
                r.set(i, new RosterMember(i + 1, o.name, o.email, o.type, o.github, o.phones, o.owner, o.hasRules,
                        o.rulesEnabled, o.hasTelegram, o.telegramUsername, null));
            }
            bump(account);
            cb.onResult(snapshot(account, 0));
        });
    }

    @Override
    public boolean rulesAvailable() {
        return true;
    }

    @Override
    public boolean previewAvailable() {
        return true;
    }

    @Override
    public void rulesGet(String account, int row, String rosterEtag, Callback<Rules> cb) {
        later(() -> {
            RosterMember m = rowOf(account, row, rosterEtag, cb);
            if (m == null) return;
            String text = rules.get(key(account) + "#" + row);
            cb.onResult(new Rules(row, m.name, m.email, text == null ? "" : "demo-" + row, text != null,
                    text == null ? "" : "r" + rulesVersion, text == null ? "" : text,
                    effective(defaultRules, text), ""));
        });
    }

    @Override
    public void rulesPreview(String account, int row, String rosterEtag, String text, Callback<Rules> cb) {
        later(() -> {
            RosterMember m = rowOf(account, row, rosterEtag, cb);
            if (m == null) return;
            if (text.length() > RulesText.MAX_BYTES) { cb.onError(new Failure("invalid", "text", "too_large", "")); return; }
            cb.onResult(new Rules(row, m.name, m.email, "", true, "", text, effective(defaultRules, text), ""));
        });
    }

    @Override
    public void rulesSave(String account, int row, String etag, String rosterEtag, String text, Callback<Rules> cb) {
        later(() -> {
            RosterMember m = rowOf(account, row, rosterEtag, cb);
            if (m == null) return;
            String k = key(account) + "#" + row;
            String now = rules.get(k);
            if (!etag.equals(now == null ? "" : "r" + rulesVersion)) { cb.onError(Failure.of("conflict")); return; }
            if (text.length() > RulesText.MAX_BYTES) { cb.onError(new Failure("invalid", "text", "too_large", "")); return; }
            rules.put(k, text);
            rulesVersion++;
            List<RosterMember> r = rosters.get(key(account));
            r.set(row - 1, new RosterMember(row, m.name, m.email, m.type, m.github, m.phones, m.owner, true,
                    RulesText.parse(text).enabled, m.hasTelegram, m.telegramUsername, null));
            bump(account);
            cb.onResult(new Rules(row, m.name, m.email, "demo-" + row, true, "r" + rulesVersion, text,
                    effective(defaultRules, text), etag(account)));
        });
    }

    @Override
    public void defaultGet(Callback<Rules> cb) {
        later(() -> {
            if (!online) { cb.onError(Failure.of("offline")); return; }
            cb.onResult(new Rules(0, "", "", "default", true, "d" + rulesVersion, defaultRules,
                    effective(defaultRules, null), ""));
        });
    }

    @Override
    public void defaultSave(String etag, String text, Callback<Rules> cb) {
        later(() -> {
            if (!online) { cb.onError(Failure.of("offline")); return; }
            if (!etag.equals("d" + rulesVersion)) { cb.onError(Failure.of("conflict")); return; }
            defaultRules = text;
            rulesVersion++;
            cb.onResult(new Rules(0, "", "", "default", true, "d" + rulesVersion, text, effective(text, null), ""));
        });
    }

    private RosterMember rowOf(String account, int row, String rosterEtag, Callback<?> cb) {
        if (!online) { cb.onError(Failure.of("offline")); return null; }
        List<RosterMember> r = rosters.get(key(account));
        if (r == null || row < 1 || row > r.size()) { cb.onError(Failure.of("not_found")); return null; }
        if (!etag(account).equals(rosterEtag)) { cb.onError(Failure.of("conflict")); return null; }
        return r.get(row - 1);
    }

    /**
     * La regla efectiva de I-4b, como la calcularía la pasarela: las secciones de default.md, y
     * una sección del contacto con el mismo nombre la sustituye completa. Con enabled: false, no cuenta.
     */
    static String effective(String general, String contact) {
        Map<String, String> sections = new java.util.LinkedHashMap<>();
        if (general != null && RulesText.parse(general).enabled) sections(RulesText.parse(general).body, sections);
        if (contact != null && RulesText.parse(contact).enabled) sections(RulesText.parse(contact).body, sections);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : sections.entrySet()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(e.getKey()).append('\n').append(e.getValue().trim()).append('\n');
        }
        return sb.toString();
    }

    private static void sections(String body, Map<String, String> into) {
        String name = null;
        StringBuilder cur = new StringBuilder();
        for (String line : body.split("\n", -1)) {
            if (line.startsWith("## ")) {
                if (name != null) into.put(name, cur.toString());
                name = line.trim();
                cur.setLength(0);
            } else if (name != null) {
                cur.append(line).append('\n');
            }
        }
        if (name != null) into.put(name, cur.toString());
    }

    /** Lo que haría «paynani roster add» en la terminal entretanto: cambia el etag. */
    public void touchFromTerminal(String account) {
        bump(account);
    }

    private Roster snapshot(String account, int savedRow) {
        return new Roster(etag(account), new ArrayList<>(rosters.get(key(account))), ALL_COLUMNS, true, savedRow);
    }

    private String etag(String account) {
        return "demo-" + key(account) + "-" + versions.getOrDefault(key(account), 0);
    }

    private void bump(String account) {
        versions.put(key(account), versions.getOrDefault(key(account), 0) + 1);
    }

    private static String key(String account) {
        return account == null ? "" : account;
    }

    private void later(Runnable r) {
        main.postDelayed(r, delayMs);
    }

    // --- Catch-All (paynani PRD #535; uno por canal, PRD #576) -----------------------------------

    private static final List<String> CATCH_ALL_CHANNELS = java.util.Arrays.asList("sms", "whatsapp", "telegram");
    private final java.util.Map<String, Boolean> catchAllEnabled = new java.util.HashMap<>();
    private final java.util.Map<String, String> catchAllText = new java.util.HashMap<>();
    private final java.util.Map<String, Integer> catchAllVersion = new java.util.HashMap<>();

    @Override
    public boolean catchAllAvailable() {
        return true;
    }

    @Override
    public void catchAllGet(String channel, Callback<CatchAll> cb) {
        later(() -> {
            if (!online) { cb.onError(Failure.of("offline")); return; }
            cb.onResult(catchAll(channel == null ? "sms" : channel));
        });
    }

    @Override
    public void catchAllSave(String channel, String etag, boolean enabled, String text, Callback<CatchAll> cb) {
        String ch = channel == null ? "sms" : channel;
        later(() -> {
            if (!online) { cb.onError(Failure.of("offline")); return; }
            int version = catchAllVersion.getOrDefault(ch, 0);
            if (!etag.equals(version == 0 ? "" : ch + version)) {
                cb.onError(Failure.of("conflict"));
                return;
            }
            if (enabled && text.trim().isEmpty()) {
                cb.onError(new Failure("invalid", "text", "empty", ""));
                return;
            }
            catchAllEnabled.put(ch, enabled);
            catchAllText.put(ch, text);
            catchAllVersion.put(ch, version + 1);
            cb.onResult(catchAll(ch));
        });
    }

    private CatchAll catchAll(String ch) {
        int version = catchAllVersion.getOrDefault(ch, 0);
        boolean exists = version > 0;
        boolean on = catchAllEnabled.getOrDefault(ch, false);
        return new CatchAll(exists, on, catchAllText.getOrDefault(ch, ""), on ? "active" : "off",
                exists ? "2026-10-25T23:00:00Z" : "", exists ? "phone demo" : "",
                exists ? ch + version : "", ch, "rules/catch-all-" + ch + ".md", CATCH_ALL_CHANNELS);
    }
}
