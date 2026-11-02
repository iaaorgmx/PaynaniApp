package com.iaaorgmx.paynani.roster;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.iaaorgmx.paynani.gateway.GatewayClient;
import com.iaaorgmx.paynani.gateway.GatewayPrefs;
import com.iaaorgmx.paynani.gateway.GatewayRequests;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * El roster por la pasarela (SMS_GATEWAY.md §11): roster.accounts, roster.list, roster.save y
 * roster.delete por el WebSocket del teléfono emparejado. Las respuestas llegan en el hilo
 * principal.
 */
public final class GatewayRosterSource implements RosterSource {

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());

    public GatewayRosterSource(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Si la pasarela anuncia roster.list en el welcome (§11, «Anuncio»). */
    public static boolean announced(Context context) {
        return new GatewayPrefs(context).accepts("roster.list");
    }

    @Override
    public boolean available() {
        return GatewayClient.get(context).isConnected() && announced(context);
    }

    @Override
    public void accounts(Callback<List<Account>> cb) {
        send(msg("roster.accounts"), cb, data -> {
            List<Account> out = new ArrayList<>();
            JSONArray a = data.optJSONArray("accounts");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Account(optNullableString(o, "account"), o.optString("email"), o.optInt("members"),
                        o.optString("status", "ok")));
            }
            return out;
        });
    }

    @Override
    public void list(String account, Callback<Roster> cb) {
        JSONObject m = msg("roster.list");
        put(m, "account", account);
        send(m, cb, data -> roster(data));
    }

    @Override
    public void save(String account, String etag, RosterMember member, Callback<Roster> cb) {
        JSONObject m = msg("roster.save");
        put(m, "account", account);
        put(m, "etag", etag);
        put(m, "row", member.isNew() ? null : member.row);
        JSONObject f = new JSONObject();
        put(f, "name", member.name);
        put(f, "email", member.email);
        put(f, "type", member.type);
        put(f, "github", member.github);
        put(f, "phones", new JSONArray(member.phones));
        // Sin cambio en el campo no va la llave: la fila conserva su valor, también un id (Q270 = A).
        // Nunca un id: RosterValidation ya lo rechazó, y aquí sólo sale un @usuario válido o "".
        if (member.telegramEdit != null) {
            String tg = RosterValidation.telegramUsername(member.telegramEdit);
            if (member.telegramEdit.isEmpty()) put(f, "telegram", "");
            else if (tg != null) put(f, "telegram", tg);
        }
        put(m, "member", f);
        send(m, cb, data -> roster(data));
    }

    @Override
    public void delete(String account, String etag, int row, Callback<Roster> cb) {
        JSONObject m = msg("roster.delete");
        put(m, "account", account);
        put(m, "etag", etag);
        put(m, "row", row);
        send(m, cb, data -> roster(data));
    }

    @Override
    public boolean rulesAvailable() {
        return new GatewayPrefs(context).accepts("rules.get");
    }

    @Override
    public boolean previewAvailable() {
        return new GatewayPrefs(context).accepts("rules.preview");
    }

    @Override
    public void rulesGet(String account, int row, String rosterEtag, Callback<Rules> cb) {
        JSONObject m = msg("rules.get");
        put(m, "account", account);
        put(m, "row", row);
        put(m, "roster_etag", rosterEtag);
        send(m, cb, data -> rules(data, row));
    }

    @Override
    public void rulesPreview(String account, int row, String rosterEtag, String text, Callback<Rules> cb) {
        JSONObject m = msg("rules.preview");
        put(m, "account", account);
        put(m, "row", row);
        put(m, "roster_etag", rosterEtag);
        put(m, "text", text);
        send(m, cb, data -> rules(data, row));
    }

    @Override
    public void rulesSave(String account, int row, String etag, String rosterEtag, String text, Callback<Rules> cb) {
        JSONObject m = msg("rules.save");
        put(m, "account", account);
        put(m, "row", row);
        put(m, "etag", etag);
        put(m, "roster_etag", rosterEtag);
        put(m, "text", text);
        send(m, cb, data -> rules(data, row));
    }

    @Override
    public void defaultGet(Callback<Rules> cb) {
        send(msg("rules.get_default"), cb, data -> rules(data, 0));
    }

    @Override
    public void defaultSave(String etag, String text, Callback<Rules> cb) {
        JSONObject m = msg("rules.save_default");
        put(m, "etag", etag);
        put(m, "text", text);
        send(m, cb, data -> rules(data, 0));
    }

    /** Si la pasarela anuncia catchall.get en el welcome (paynani PRD #535 FR-A1). */
    public static boolean catchAllAnnounced(Context context) {
        return new GatewayPrefs(context).accepts("catchall.get");
    }

    @Override
    public boolean catchAllAvailable() {
        return catchAllAnnounced(context);
    }

    @Override
    public void catchAllGet(String channel, Callback<CatchAll> cb) {
        JSONObject m = msg("catchall.get");
        if (channel != null) put(m, "channel", channel);
        send(m, cb, GatewayRosterSource::catchAll);
    }

    @Override
    public void catchAllSave(String channel, String etag, boolean enabled, String text, Callback<CatchAll> cb) {
        JSONObject m = msg("catchall.save");
        if (channel != null) put(m, "channel", channel);
        put(m, "etag", etag);
        put(m, "enabled", enabled);
        put(m, "text", text);
        send(m, cb, GatewayRosterSource::catchAll);
    }

    // --- JSON -----------------------------------------------------------------------------------

    /** catchall.get/save (paynani PRD #535 FR-0a; `channel`, `file` y `channels` desde el PRD #576). */
    static CatchAll catchAll(JSONObject d) {
        List<String> channels = new ArrayList<>();
        JSONArray a = d.optJSONArray("channels");
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                String c = a.optString(i);
                if (!c.isEmpty()) channels.add(c);
            }
        }
        return new CatchAll(d.optBoolean("exists"), d.optBoolean("enabled"), d.optString("text"),
                d.optString("status", "off"), d.optString("updated_at"), d.optString("updated_by"),
                d.optString("etag"), d.optString("channel"), d.optString("file"), channels);
    }

    /** rules.get/preview/save (§11). La respuesta de save no trae text: lo pone quien guardó. */
    static Rules rules(JSONObject d, int row) {
        return new Rules(d.optInt("row", row), d.optString("name"), d.optString("email"),
                d.isNull("rules") ? "" : d.optString("rules"), d.optBoolean("exists", d.has("rules")),
                d.optString("etag"), d.optString("text"), d.optString("effective"), d.optString("roster_etag"));
    }

    /** Lo que traen roster.list, roster.save y roster.delete. */
    static Roster roster(JSONObject data) throws Exception {
        List<RosterMember> members = new ArrayList<>();
        JSONArray a = data.optJSONArray("members");
        for (int i = 0; a != null && i < a.length(); i++) members.add(member(a.getJSONObject(i)));
        List<String> columns = new ArrayList<>();
        JSONArray c = data.optJSONArray("columns");
        for (int i = 0; c != null && i < c.length(); i++) columns.add(c.getString(i));
        return new Roster(data.optString("etag"), members, columns, data.optBoolean("default_rules"),
                data.optInt("row", 0));
    }

    static RosterMember member(JSONObject o) throws Exception {
        List<String> phones = new ArrayList<>();
        JSONArray p = o.optJSONArray("phones");
        for (int i = 0; p != null && i < p.length(); i++) phones.add(p.getString(i));
        // Sólo telegram_username, y sólo si es un @usuario: la llave telegram (la celda entera) es de
        // PaynaniWeb y un id nunca se guarda ni se muestra aquí (FR-15).
        String username = RosterValidation.telegramUsername(o.optString("telegram_username"));
        boolean hasTelegram = o.optBoolean("has_telegram") || username != null;
        return new RosterMember(o.optInt("row"), o.optString("name"), o.optString("email"), o.optString("type"),
                o.optString("github"), phones, o.optBoolean("owner"), o.optBoolean("has_rules"),
                o.optBoolean("rules_enabled"), hasTelegram,
                username != null && o.optString("telegram_username").trim().startsWith("@") ? username : "", null);
    }

    /** Un error de §11 (o "offline"/"timeout" de la app) en lo que entiende la pantalla. */
    static Failure failure(JSONObject err) {
        return new Failure(err.optString("code"), err.optString("field"), err.optString("reason"),
                err.optString("detail"));
    }

    // --- Envío ----------------------------------------------------------------------------------

    private interface Parser<T> {
        T parse(JSONObject data) throws Exception;
    }

    private <T> void send(JSONObject m, Callback<T> cb, Parser<T> parser) {
        if (!available()) {
            main.post(() -> cb.onError(Failure.of("offline")));
            return;
        }
        GatewayClient.get(context).request(m, new GatewayRequests.Callback<JSONObject>() {
            @Override
            public void onResponse(JSONObject message, boolean resent) {
                if ("result".equals(message.optString("type"))) {
                    T result;
                    try {
                        result = parser.parse(message.optJSONObject("data") == null ? new JSONObject()
                                : message.optJSONObject("data"));
                    } catch (Exception e) {
                        main.post(() -> cb.onError(Failure.of("bad_response")));
                        return;
                    }
                    main.post(() -> cb.onResult(result));
                    return;
                }
                Failure f = failure(message);
                // Tras el reenvío, conflict puede ser la propia escritura ya aplicada: se vuelve a
                // leer, sin mostrarlo como error (§11, «Reenvío seguro»).
                Failure shown = resent && "conflict".equals(f.code) ? Failure.of("reload") : f;
                main.post(() -> cb.onError(shown));
            }

            @Override
            public void onFailure(String code) {
                main.post(() -> cb.onError(Failure.of(code)));
            }
        });
    }

    private static JSONObject msg(String type) {
        JSONObject m = new JSONObject();
        put(m, "type", type);
        return m;
    }

    private static void put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v == null ? JSONObject.NULL : v);
        } catch (Exception ignored) {
        }
    }

    private static String optNullableString(JSONObject o, String k) {
        return o.isNull(k) ? null : o.optString(k);
    }
}
