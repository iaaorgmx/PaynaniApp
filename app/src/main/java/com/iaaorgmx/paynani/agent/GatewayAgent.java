package com.iaaorgmx.paynani.agent;

import android.content.Context;
import android.content.SharedPreferences;
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
 * El agente por la pasarela (paynani #692): `agent.get` por el WebSocket del teléfono emparejado.
 * Va sólo a la conexión con `webchat.owner`, como Webchat. El nombre y el estado se guardan para
 * que el renglón del menú se dibuje sin esperar a la red; `agent.changed` actualiza el estado.
 */
public final class GatewayAgent {

    static final String TYPE = "agent.get";
    static final String GRANT = "webchat.owner";
    private static final String PREFS = "agent";

    public interface Callback {
        void onResult(Agent agent);

        /** `code`: offline, forbidden, unavailable, bad_response… */
        void onError(String code);
    }

    private GatewayAgent() {}

    /** La pasarela anuncia agent.get y este teléfono tiene webchat.owner. */
    public static boolean announced(Context context) {
        GatewayPrefs prefs = new GatewayPrefs(context);
        return prefs.accepts(TYPE) && prefs.granted(GRANT);
    }

    public static boolean connected(Context context) {
        return GatewayClient.get(context).isConnected();
    }

    /** El último nombre conocido, o "" si nunca llegó. */
    public static String cachedName(Context context) {
        return prefs(context).getString("name", "");
    }

    /** El último estado conocido; «unknown» si nunca llegó o no hay conexión. */
    public static String cachedStatus(Context context) {
        if (!connected(context)) return AgentRules.UNKNOWN;
        return AgentRules.status(prefs(context).getString("status", AgentRules.UNKNOWN));
    }

    /** `agent.changed`: guarda el estado nuevo y avisa. */
    public static void statusChanged(Context context, String status) {
        prefs(context).edit().putString("status", AgentRules.status(status)).apply();
        AgentEvents.changed(AgentRules.status(status));
    }

    public static void get(Context context, Callback cb) {
        Context app = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        if (!connected(app) || !announced(app)) {
            main.post(() -> cb.onError("offline"));
            return;
        }
        JSONObject m = new JSONObject();
        try {
            m.put("type", TYPE);
        } catch (Exception ignored) {
        }
        GatewayClient.get(app).request(m, new GatewayRequests.Callback<JSONObject>() {
            @Override
            public void onResponse(JSONObject message, boolean resent) {
                if (!"result".equals(message.optString("type"))) {
                    String code = message.optString("code", "unavailable");
                    main.post(() -> cb.onError(code));
                    return;
                }
                Agent agent;
                try {
                    JSONObject data = message.optJSONObject("data");
                    agent = parse(data == null ? new JSONObject() : data);
                } catch (Exception e) {
                    main.post(() -> cb.onError("bad_response"));
                    return;
                }
                prefs(app).edit().putString("name", agent.name).putString("status", agent.status).apply();
                main.post(() -> cb.onResult(agent));
            }

            @Override
            public void onFailure(String code) {
                main.post(() -> cb.onError(code));
            }
        });
    }

    static Agent parse(JSONObject d) {
        JSONObject session = d.optJSONObject("session");
        JSONObject handled = d.optJSONObject("handled");
        JSONObject last = d.optJSONObject("last_reply");
        // version: la cadena de #692, o {"installed", "latest"} si la pasarela ya la manda así.
        Object v = d.opt("version");
        String installed = null, latest = null;
        if (v instanceof JSONObject) {
            installed = str((JSONObject) v, "installed");
            latest = str((JSONObject) v, "latest");
        } else if (v instanceof String) {
            installed = (String) v;
        }
        JSONObject ph = d.optJSONObject("phone");
        Agent.Phone phone = ph == null ? null : new Agent.Phone(ph.optBoolean("online"), str(ph, "model"),
                str(ph, "app_version"), integer(ph, "battery"), integer(ph, "last_seen_age_s"));
        JSONObject tg = d.optJSONObject("telegram");
        List<Agent.Activity> activity = new ArrayList<>();
        JSONArray a = d.optJSONArray("activity");
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                activity.add(new Agent.Activity(str(o, "at"), str(o, "channel"), str(o, "state"),
                        str(o, "who"), str(o, "what")));
            }
        }
        return new Agent(
                d.optString("name", ""), str(d, "email"), d.optString("runtime_label", d.optString("runtime", "")),
                d.optString("status", AgentRules.UNKNOWN), str(d, "kind"),
                session != null && session.optBoolean("live"), session == null ? null : str(session, "armed_at"),
                session == null ? null : str(session, "expires_at"), str(d, "last_accepted"),
                handled == null ? 0 : handled.optInt("replied"), handled == null ? 0 : handled.optInt("closed"),
                d.optInt("waiting"),
                last == null ? null : str(last, "at"), last == null ? null : str(last, "who"),
                last == null ? null : str(last, "channel"),
                integer(d, "reply_median_s"),
                installed, latest, str(d, "dispatcher"), str(d, "listener"), d.optInt("roster_count"), phone,
                str(d, "whatsapp"), tg == null ? null : str(tg, "state"), tg == null ? null : str(tg, "bot_username"),
                activity);
    }

    /** Un número entero o con decimales («42.0»), redondeado; null si falta. */
    private static Integer integer(JSONObject o, String k) {
        if (o == null || !o.has(k) || o.isNull(k)) return null;
        double v = o.optDouble(k, Double.NaN);
        return Double.isNaN(v) ? null : (int) Math.round(v);
    }

    private static String str(JSONObject o, String k) {
        return o == null || !o.has(k) || o.isNull(k) ? null : o.optString(k);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
