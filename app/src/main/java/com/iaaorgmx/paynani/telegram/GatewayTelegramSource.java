package com.iaaorgmx.paynani.telegram;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.iaaorgmx.paynani.gateway.GatewayClient;
import com.iaaorgmx.paynani.gateway.GatewayPrefs;
import com.iaaorgmx.paynani.gateway.GatewayRequests;
import com.iaaorgmx.paynani.whatsapp.WhatsAppSource;
import com.iaaorgmx.paynani.whatsapp.WhatsAppSource.Callback;
import com.iaaorgmx.paynani.whatsapp.WhatsAppSource.Failure;
import com.iaaorgmx.paynani.whatsapp.WhatsAppSource.Page;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Telegram por la pasarela (TELEGRAM.md §6) por el WebSocket del teléfono emparejado. Las
 * respuestas llegan en el hilo principal. La app sólo manda thread y text: nunca contact ni file,
 * y by lo pone la pasarela.
 */
public final class GatewayTelegramSource implements TelegramSource {

    static final int PAGE = 50;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());

    public GatewayTelegramSource(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Si la pasarela anuncia telegram.threads: el menú muestra «Mensajes de Telegram». */
    public static boolean threadsAnnounced(Context context) {
        return new GatewayPrefs(context).accepts("telegram.threads");
    }

    @Override
    public boolean available() {
        return GatewayClient.get(context).isConnected() && threadsAnnounced(context);
    }

    @Override
    public boolean connected() {
        return GatewayClient.get(context).isConnected();
    }

    @Override
    public boolean canSend() {
        return new GatewayPrefs(context).accepts("telegram.send");
    }

    @Override
    public void status(Callback<Status> cb) {
        send("telegram.status", msg("telegram.status"), cb, GatewayTelegramSource::status);
    }

    @Override
    public void threads(String before, Callback<Page<ThreadSummary>> cb) {
        JSONObject m = msg("telegram.threads");
        put(m, "limit", PAGE);
        put(m, "before", before);
        send("telegram.threads", m, cb, d -> {
            List<ThreadSummary> out = new ArrayList<>();
            JSONArray a = d.optJSONArray("threads");
            for (int i = 0; a != null && i < a.length(); i++) out.add(summary(a.getJSONObject(i)));
            return new Page<>(out, nullable(d, "next_before"));
        });
    }

    @Override
    public void thread(String thread, String before, Callback<Thread> cb) {
        JSONObject m = msg("telegram.thread");
        put(m, "thread", thread);
        put(m, "limit", PAGE);
        put(m, "before", before);
        send("telegram.thread", m, cb, d -> thread(d, thread));
    }

    @Override
    public void send(String thread, String text, Callback<Message> cb) {
        JSONObject m = msg("telegram.send");
        put(m, "thread", thread);
        put(m, "text", text);
        send("telegram.send", m, cb, d -> new Message(d.optString("ref"), "out", "", d.optString("at"), "text",
                text, d.optString("status", "sending"), false, false));
    }

    // --- JSON -----------------------------------------------------------------------------------

    static Status status(JSONObject d) {
        return new Status(d.optString("state"), nullable(d, "bot_username"));
    }

    static ThreadSummary summary(JSONObject o) {
        return new ThreadSummary(o.optString("thread"), nullable(o, "name"), o.optBoolean("in_roster"),
                o.optString("last_at"), o.optString("last_dir"), nullable(o, "last_excerpt"),
                nullable(o, "last_kind"), o.optBoolean("can_reply"), o.optBoolean("hidden"));
    }

    static Message message(JSONObject o) {
        return new Message(o.optString("ref"), o.optString("dir"), o.optString("by"), o.optString("at"),
                o.optString("kind"), nullable(o, "text"), o.optString("status"), o.optBoolean("edited"),
                o.optBoolean("hidden"));
    }

    /**
     * telegram.thread manda los mensajes del más reciente al más viejo, como whatsapp.thread, y
     * next_before pide la página siguiente, más vieja (TELEGRAM.md §4).
     */
    static Thread thread(JSONObject d, String asked) throws Exception {
        List<Message> out = new ArrayList<>();
        JSONArray a = d.optJSONArray("messages");
        for (int i = 0; a != null && i < a.length(); i++) out.add(message(a.getJSONObject(i)));
        return new Thread(d.optString("thread", asked), nullable(d, "name"), d.optBoolean("in_roster"),
                d.optBoolean("can_reply"), d.optBoolean("hidden"), new Page<>(out, nullable(d, "next_before")));
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
