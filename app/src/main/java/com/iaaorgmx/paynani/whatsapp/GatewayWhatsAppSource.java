package com.iaaorgmx.paynani.whatsapp;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.iaaorgmx.paynani.gateway.GatewayClient;
import com.iaaorgmx.paynani.gateway.GatewayPrefs;
import com.iaaorgmx.paynani.gateway.GatewayRequests;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * WhatsApp por la pasarela (SMS_GATEWAY.md §12) por el WebSocket del teléfono emparejado. Las
 * respuestas llegan en el hilo principal.
 *
 * El código de whatsapp.link sólo pasa de la respuesta a la pantalla: no se guarda ni se escribe
 * en el log (FR-6a).
 */
public final class GatewayWhatsAppSource implements WhatsAppSource {

    static final int PAGE = 50;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());

    public GatewayWhatsAppSource(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Si la pasarela anuncia whatsapp.status: WhatsApp está configurado en la instalación (§12). */
    public static boolean statusAnnounced(Context context) {
        return new GatewayPrefs(context).accepts("whatsapp.status");
    }

    /** Si la pasarela anuncia whatsapp.threads: el menú muestra «WhatsApp messages» (FR-25). */
    public static boolean threadsAnnounced(Context context) {
        return new GatewayPrefs(context).accepts("whatsapp.threads");
    }

    @Override
    public boolean available() {
        return GatewayClient.get(context).isConnected() && statusAnnounced(context);
    }

    @Override
    public boolean admin() {
        return new GatewayPrefs(context).granted("whatsapp.admin");
    }

    @Override
    public boolean canSend() {
        return new GatewayPrefs(context).accepts("whatsapp.send");
    }

    @Override
    public void status(Callback<Status> cb) {
        send("whatsapp.status", msg("whatsapp.status"), cb, GatewayWhatsAppSource::status);
    }

    @Override
    public void link(String number, Callback<LinkCode> cb) {
        JSONObject m = msg("whatsapp.link");
        if (number != null) put(m, "number", number);
        send("whatsapp.link", m, cb, d -> new LinkCode(d.optString("code"), d.optString("expires_at"),
                d.optLong("expires_in_s", -1), SystemClock.elapsedRealtime()));
    }

    @Override
    public void linkCancel(Callback<Void> cb) {
        send("whatsapp.link_cancel", msg("whatsapp.link_cancel"), cb, d -> null);
    }

    @Override
    public void unlink(Callback<Boolean> cb) {
        send("whatsapp.unlink", msg("whatsapp.unlink"), cb, d -> d.optBoolean("remote"));
    }

    @Override
    public void threads(String before, Callback<Page<ThreadSummary>> cb) {
        JSONObject m = msg("whatsapp.threads");
        put(m, "limit", PAGE);
        put(m, "before", before);
        send("whatsapp.threads", m, cb, d -> {
            List<ThreadSummary> out = new ArrayList<>();
            JSONArray a = d.optJSONArray("threads");
            for (int i = 0; a != null && i < a.length(); i++) out.add(summary(a.getJSONObject(i)));
            return new Page<>(out, nullable(d, "next_before"));
        });
    }

    @Override
    public void thread(String number, String before, Callback<Thread> cb) {
        JSONObject m = msg("whatsapp.thread");
        put(m, "number", number);
        put(m, "limit", PAGE);
        put(m, "before", before);
        send("whatsapp.thread", m, cb, d -> {
            List<Message> out = new ArrayList<>();
            JSONArray a = d.optJSONArray("messages");
            for (int i = 0; a != null && i < a.length(); i++) out.add(message(a.getJSONObject(i)));
            boolean inRoster = d.optBoolean("in_roster") && !d.optBoolean("hidden");
            return new Thread(d.optString("number", number), nullable(d, "name"), inRoster,
                    d.optBoolean("can_reply"), new Page<>(out, nullable(d, "next_before")));
        });
    }

    @Override
    public void send(String number, String text, Callback<Message> cb) {
        JSONObject m = msg("whatsapp.send");
        put(m, "number", number);
        put(m, "text", text);
        send("whatsapp.send", m, cb, d -> {
            JSONObject o = d.optJSONObject("message");
            return o == null ? null : message(o);
        });
    }

    // --- JSON -----------------------------------------------------------------------------------

    static Status status(JSONObject d) {
        JSONObject linking = d.optJSONObject("linking");
        return new Status(d.optString("state"), nullable(d, "me"), d.optString("since"),
                linking == null ? null : linking.optString("by"),
                linking == null ? "" : linking.optString("expires_at"),
                linking == null ? -1 : linking.optLong("expires_in_s", -1), SystemClock.elapsedRealtime(),
                nullable(d, "link_number"));
    }

    static ThreadSummary summary(JSONObject o) {
        return new ThreadSummary(o.optString("number"), nullable(o, "name"), o.optBoolean("in_roster"),
                o.optString("last_at"), o.optString("last_dir"), nullable(o, "last_excerpt"),
                nullable(o, "last_kind"), o.optBoolean("can_reply"), o.optBoolean("hidden"));
    }

    static Message message(JSONObject o) {
        JSONObject media = o.optJSONObject("media");
        String kind = o.optString("kind", media == null ? "" : media.optString("kind"));
        return new Message(o.optString("id"), o.optString("dir"), o.optString("by"), o.optString("at"), kind,
                nullable(o, "text"), o.optString("status"), o.optBoolean("hidden"));
    }

    static Failure failure(JSONObject err) {
        return new Failure(err.optString("code"), err.optString("field"), err.optInt("retry_after_s"));
    }

    // --- Envío ----------------------------------------------------------------------------------

    private interface Parser<T> {
        T parse(JSONObject data) throws Exception;
    }

    private <T> void send(String type, JSONObject m, Callback<T> cb, Parser<T> parser) {
        if (!GatewayClient.get(context).isConnected() || !new GatewayPrefs(context).accepts(type)) {
            main.post(() -> cb.onError(Failure.of("offline")));
            return;
        }
        GatewayClient.get(context).request(m, new GatewayRequests.Callback<JSONObject>() {
            @Override
            public void onResponse(JSONObject message, boolean resent) {
                if ("result".equals(message.optString("type"))) {
                    T result;
                    try {
                        JSONObject data = message.optJSONObject("data");
                        result = parser.parse(data == null ? new JSONObject() : data);
                    } catch (Exception e) {
                        main.post(() -> cb.onError(Failure.of("bad_response")));
                        return;
                    }
                    main.post(() -> cb.onResult(result));
                    return;
                }
                // Tras el reenvío de whatsapp.link, conflict es que el código ya se entregó y no se
                // repite (§12): la pantalla pide uno nuevo.
                Failure f = failure(message);
                main.post(() -> cb.onError(f));
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

    private static String nullable(JSONObject o, String k) {
        return o.isNull(k) || !o.has(k) ? null : o.optString(k);
    }
}
