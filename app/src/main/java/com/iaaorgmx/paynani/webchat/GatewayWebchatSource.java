package com.iaaorgmx.paynani.webchat;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.iaaorgmx.paynani.gateway.GatewayClient;
import com.iaaorgmx.paynani.gateway.GatewayPrefs;
import com.iaaorgmx.paynani.gateway.GatewayRequests;
import com.iaaorgmx.paynani.webchat.WebchatSource.Callback;
import com.iaaorgmx.paynani.webchat.WebchatSource.Failure;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Webchat por la pasarela (paynani PRD #653): webchat.thread y webchat.send por el WebSocket del
 * teléfono emparejado (FR-3, FR-4), y los archivos por HTTP en la misma pasarela con el token del
 * teléfono (FR-5). Las respuestas llegan en el hilo principal. El texto de los mensajes nunca va al
 * log.
 */
public final class GatewayWebchatSource implements WebchatSource {

    static final int PAGE = 50;
    /** FR-1: el grant que sólo tiene el teléfono emparejado del dueño. */
    static final String GRANT = "webchat.owner";

    private static OkHttpClient http;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());

    public GatewayWebchatSource(Context context) {
        this.context = context.getApplicationContext();
    }

    /** FR-1: la pasarela anuncia webchat.thread y este teléfono tiene webchat.owner. */
    public static boolean announced(Context context) {
        GatewayPrefs prefs = new GatewayPrefs(context);
        return prefs.accepts("webchat.thread") && prefs.granted(GRANT);
    }

    @Override
    public boolean available() {
        return GatewayClient.get(context).isConnected() && announced(context);
    }

    @Override
    public boolean connected() {
        return GatewayClient.get(context).isConnected();
    }

    @Override
    public void thread(String after, String before, Callback<Thread> cb) {
        JSONObject m = msg("webchat.thread");
        put(m, "limit", PAGE);
        if (after != null) put(m, "after", after);
        if (before != null) put(m, "before", before);
        ask("webchat.thread", m, cb, GatewayWebchatSource::thread);
    }

    @Override
    public void send(String text, List<String> uploads, Callback<Message> cb) {
        JSONObject m = msg("webchat.send");
        put(m, "text", text == null ? "" : text);
        // SMS_GATEWAY.md §13: los up_… de POST /sms/webchat/upload.
        put(m, "uploads", new JSONArray(uploads == null ? new ArrayList<>() : uploads));
        ask("webchat.send", m, cb, d -> message(d.has("message") ? d.getJSONObject("message") : d));
    }

    @Override
    public void upload(String name, String mime, byte[] data, Callback<String> cb) {
        HttpUrl url = httpUrl("/sms/webchat/upload");
        if (url == null || !available()) {
            main.post(() -> cb.onError(Failure.of("offline")));
            return;
        }
        RequestBody file = RequestBody.create(data, MediaType.parse(mime == null ? "application/octet-stream" : mime));
        RequestBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", name, file).build();
        call(new Request.Builder().url(url).post(body), cb, bytes -> {
            // §13: {"upload": "up_…", "name", "kind", "size"}.
            String upload = new JSONObject(new String(bytes, "UTF-8")).optString("upload");
            if (upload.isEmpty()) throw new IOException("sin upload");
            return upload;
        });
    }

    @Override
    public void file(String ref, Callback<byte[]> cb) {
        HttpUrl base = httpUrl("/sms/webchat/file");
        if (base == null || !connected()) {
            main.post(() -> cb.onError(Failure.of("offline")));
            return;
        }
        call(new Request.Builder().url(base.newBuilder().addQueryParameter("ref", ref).build()).get(), cb,
                bytes -> bytes);
    }

    // --- JSON -----------------------------------------------------------------------------------

    static FileRef fileRef(JSONObject o) {
        return new FileRef(o.optInt("n"), o.optString("name"), o.optString("kind"), o.optLong("size"),
                o.optString("ref"));
    }

    static Message message(JSONObject o) throws Exception {
        List<FileRef> files = new ArrayList<>();
        JSONArray a = o.optJSONArray("files");
        for (int i = 0; a != null && i < a.length(); i++) files.add(fileRef(a.getJSONObject(i)));
        return new Message(o.optString("id"), o.optString("dir"), o.optString("at"), o.optString("text"), files);
    }

    /** FR-3: los mensajes (en cualquier orden: Thread los ordena), typing, el borrador y el agente. */
    static Thread thread(JSONObject d) throws Exception {
        List<Message> out = new ArrayList<>();
        JSONArray a = d.optJSONArray("messages");
        for (int i = 0; a != null && i < a.length(); i++) out.add(message(a.getJSONObject(i)));
        return new Thread(out, nullable(d, "next_before"), d.optBoolean("typing"), draftText(d.opt("draft")),
                agentName(d));
    }

    /** El borrador viene como {"text", "at"} (WEBCHAT.md §4) o como texto; null si no hay. */
    public static String draftText(Object draft) {
        if (draft instanceof JSONObject) return nullable((JSONObject) draft, "text");
        if (draft instanceof String && !((String) draft).isEmpty()) return (String) draft;
        return null;
    }

    /** §13: el nombre del agente como texto, o null. */
    private static String agentName(JSONObject d) {
        return nullable(d, "agent");
    }

    static Failure failure(JSONObject err) {
        return new Failure(err.optString("code"), err.optString("field"), err.optInt("retry_after_s"));
    }

    // --- WebSocket ------------------------------------------------------------------------------

    private interface Parser<T> {
        T parse(JSONObject data) throws Exception;
    }

    private <T> void ask(String type, JSONObject m, Callback<T> cb, Parser<T> parser) {
        if (!available() || !new GatewayPrefs(context).accepts(type)) {
            main.post(() -> cb.onError(Failure.of("offline")));
            return;
        }
        // GatewayClient pone el id q_… y lo reenvía igual al reconectar: webchat.send con el mismo id
        // no se guarda dos veces (FR-4).
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

    // --- HTTP (FR-5) ----------------------------------------------------------------------------

    private interface Bytes<T> {
        T parse(byte[] body) throws Exception;
    }

    /** La dirección HTTP de la pasarela, la misma del WebSocket emparejado (ws→http, wss→https). */
    private HttpUrl httpUrl(String path) {
        return httpUrl(new GatewayPrefs(context).getWsUrl(), path);
    }

    static HttpUrl httpUrl(String wsUrl, String path) {
        try {
            URI ws = URI.create(wsUrl);
            String scheme = "wss".equals(ws.getScheme()) ? "https" : "ws".equals(ws.getScheme()) ? "http" : null;
            if (scheme == null || ws.getHost() == null) return null;
            HttpUrl.Builder b = new HttpUrl.Builder().scheme(scheme).host(ws.getHost());
            if (ws.getPort() > 0) b.port(ws.getPort());
            return b.encodedPath(path).build();
        } catch (Exception e) {
            return null;
        }
    }

    private static synchronized OkHttpClient http() {
        if (http == null) {
            // Hasta 20 MB por el túnel y por datos móviles: sin tope total, pero cortando si se detiene.
            http = new OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).build();
        }
        return http;
    }

    private <T> void call(Request.Builder request, Callback<T> cb, Bytes<T> parser) {
        String token = new GatewayPrefs(context).getToken();
        http().newCall(request.header("Authorization", "Bearer " + token).build()).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                main.post(() -> cb.onError(Failure.of("offline")));
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (ResponseBody body = response.body()) {
                    byte[] bytes = body == null ? new byte[0] : body.bytes();
                    if (!response.isSuccessful()) {
                        Failure f = httpFailure(response.code(), bytes);
                        main.post(() -> cb.onError(f));
                        return;
                    }
                    T result = parser.parse(bytes);
                    main.post(() -> cb.onResult(result));
                } catch (Exception e) {
                    main.post(() -> cb.onError(Failure.of("bad_response")));
                }
            }
        });
    }

    /** El error de una respuesta HTTP: su code si trae JSON, o uno por el estado. */
    static Failure httpFailure(int status, byte[] body) {
        try {
            // §13: los errores HTTP traen {"error": "<code>"}.
            JSONObject o = new JSONObject(new String(body, "UTF-8"));
            String code = o.optString("error");
            if (!code.isEmpty()) return new Failure(code, o.optString("field"), o.optInt("retry_after_s"));
        } catch (Exception ignored) {
        }
        switch (status) {
            case 401:
            case 403:
                return Failure.of("forbidden");
            case 404:
                return Failure.of("not_found");
            case 413:
                return Failure.of("too_big");
            case 429:
                return Failure.of("rate_limited");
            default:
                return Failure.of("unavailable");
        }
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
