package com.iaaorgmx.paynani.gateway;

import com.iaaorgmx.paynani.agent.AgentEvents;
import com.iaaorgmx.paynani.agent.GatewayAgent;
import com.iaaorgmx.paynani.webchat.GatewayWebchatSource;
import com.iaaorgmx.paynani.webchat.WebchatEvents;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

import com.iaaorgmx.paynani.BuildConfig;
import com.iaaorgmx.paynani.L;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Random;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * La conexión con la pasarela de paynani: un WebSocket con latido y reconexión con
 * espera creciente (SMS_GATEWAY.md §2). Esta app sólo hace Webchat (PaynaniApp-Agentic#175):
 * su saludo dice `"sms": false`, y la conexión vive mientras la app está abierta
 * (GlobalInstance la arranca y la detiene).
 */
public final class GatewayClient {

    private static final String TAG = "GatewayClient";
    private static final int[] BACKOFF_S = {1, 2, 4, 8, 16, 32, 60};

    private static GatewayClient instance;

    /** Lo que muestra la pantalla de Estado. */
    public enum State { UNPAIRED, DISCONNECTED, CONNECTED, REVOKED }

    public interface StateListener { void onGatewayState(State state); }

    public static synchronized GatewayClient get(Context context) {
        if (instance == null) instance = new GatewayClient(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final GatewayPrefs prefs;
    private final Handler handler;
    private final Random random = new Random();
    private OkHttpClient http;
    private WebSocket socket;
    private volatile boolean welcomed = false;
    private volatile boolean started = false;
    private int attempt = 0;
    /**
     * La pasarela cierra con 1012 (Service Restart) cuando cambian accepts o grants del welcome,
     * por ejemplo al crear o borrar el usuario de PaynaniWeb (SMS_GATEWAY.md §13).
     */
    static final int CLOSE_WELCOME_CHANGED = 1012;
    private int heartbeatS = 30;
    private volatile StateListener listener;
    private volatile long connectedSince = 0;
    private final Runnable reconnect = this::connect;
    /** Pregunta y respuesta sin cola (SMS_GATEWAY.md §13): webchat.thread y webchat.send. */
    private final GatewayRequests<JSONObject> requests;

    private GatewayClient(Context context) {
        this.context = context;
        this.prefs = new GatewayPrefs(context);
        HandlerThread t = new HandlerThread("paynani-gateway");
        t.start();
        this.handler = new Handler(t.getLooper());
        this.requests = new GatewayRequests<>(
                json -> socket != null && welcomed && socket.send(json),
                new GatewayRequests.Scheduler() {
                    @Override public Object schedule(Runnable r, long delayMs) {
                        handler.postDelayed(r, delayMs);
                        return r;
                    }

                    @Override public void cancel(Object token) {
                        if (token != null) handler.removeCallbacks((Runnable) token);
                    }
                });
    }

    /**
     * Un pedido (webchat.thread, webchat.send…). Le pone el id q_… y lo manda por el WebSocket,
     * sin cola: sin conexión falla en el acto con "offline". La respuesta llega en el hilo de la
     * pasarela.
     */
    public void request(JSONObject msg, GatewayRequests.Callback<JSONObject> cb) {
        handler.post(() -> {
            String id = GatewayRequests.newId();
            try {
                msg.put("id", id);
            } catch (Exception e) {
                cb.onFailure("offline");
                return;
            }
            requests.request(id, msg.toString(), cb);
        });
    }

    public boolean isConnected() { return socket != null && welcomed; }

    /** Desde cuándo está conectada (welcome), o 0. OkHttp manda el latido y corta si no hay respuesta. */
    public long connectedSince() { return isConnected() ? connectedSince : 0; }

    public State state() {
        if (!prefs.isPaired()) return State.UNPAIRED;
        if (prefs.isRevoked()) return State.REVOKED;
        return isConnected() ? State.CONNECTED : State.DISCONNECTED;
    }

    public void setStateListener(StateListener l) {
        listener = l;
        if (l != null) l.onGatewayState(state());
    }

    private void notifyState() {
        StateListener l = listener;
        if (l != null) l.onGatewayState(state());
        // Webchat vacía lo mostrado al perder la conexión y relee al volver.
        WebchatEvents.changed(WebchatEvents.CONNECTION, null);
        AgentEvents.changed(AgentEvents.CONNECTION);
    }

    /** Arranca la conexión si hay emparejamiento. Idempotente. */
    public void start() {
        handler.post(() -> {
            if (started) return;
            started = true;
            attempt = 0;
            connect();
        });
    }

    /** La app pasó a segundo plano: cierra la conexión y deja de reintentar. */
    public void stop() {
        handler.post(() -> {
            started = false;
            handler.removeCallbacks(reconnect);
            if (socket != null) socket.close(1000, "app");
            if (welcomed) prefs.setDisconnectedAt(System.currentTimeMillis());
            socket = null;
            welcomed = false;
            requests.disconnected();
            notifyState();
        });
    }

    /** Desemparejar: cierra la conexión y olvida el token. */
    public void unpair() {
        // El token se borra ya, para que quien llama vea el cambio al volver.
        prefs.clear();
        handler.post(() -> {
            started = false;
            if (socket != null) socket.close(1000, "unpaired");
            socket = null;
            welcomed = false;
            requests.disconnected();
            notifyState();
        });
    }

    private void connect() {
        if (!started || socket != null) return;
        if (!prefs.isPaired() || prefs.isRevoked()) {
            L.e(TAG, "sin emparejamiento vigente; no se conecta");
            started = false;
            notifyState();
            return;
        }
        http = new OkHttpClient.Builder()
                .pingInterval(heartbeatS, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();
        Request request = new Request.Builder()
                .url(prefs.getWsUrl())
                .header("Authorization", "Bearer " + prefs.getToken())
                .header("Sec-WebSocket-Protocol", Protocol.SUBPROTOCOL)
                .build();
        socket = http.newWebSocket(request, new Listener());
    }

    /** La conexión que llegó a welcome se cortó: se anota la hora para la pantalla de Estado. */
    private void markDropped() {
        if (welcomed) prefs.setDisconnectedAt(System.currentTimeMillis());
        requests.disconnected();
    }

    private void scheduleReconnect() {
        int base = BACKOFF_S[Math.min(attempt, BACKOFF_S.length - 1)];
        attempt++;
        scheduleReconnect((long) (base * 1000 * (0.8 + 0.4 * random.nextDouble())));
    }

    private void scheduleReconnect(long delay) {
        markDropped();
        socket = null;
        welcomed = false;
        notifyState();
        if (!started || prefs.isRevoked()) return;
        L.e(TAG, "reconecta en " + delay + " ms");
        handler.postDelayed(reconnect, delay);
    }

    /** El saludo: sin SIM ni cola, y `"sms": false` (sólo Webchat, PaynaniApp-Agentic#175). */
    static JSONObject hello(String deviceId, String appVersion) throws Exception {
        JSONObject hello = new JSONObject();
        hello.put("type", "hello");
        hello.put("protocol", Protocol.VERSION);
        hello.put("device_id", deviceId);
        hello.put("app_version", appVersion);
        hello.put("sims", new JSONArray());
        hello.put("sms", false);
        return hello;
    }

    private final class Listener extends WebSocketListener {
        @Override public void onOpen(WebSocket ws, Response response) {
            handler.post(() -> {
                try {
                    ws.send(hello(prefs.getDeviceId(), BuildConfig.VERSION_NAME).toString());
                } catch (Exception e) {
                    L.e(TAG, "hello: " + e);
                }
            });
        }

        @Override public void onMessage(WebSocket ws, String text) {
            handler.post(() -> handle(text));
        }

        @Override public void onClosing(WebSocket ws, int code, String reason) {
            ws.close(code, null);
        }

        @Override public void onClosed(WebSocket ws, int code, String reason) {
            handler.post(() -> {
                if (ws != socket) return;
                L.e(TAG, "cerrada " + code);
                if (code == 4401) revoked();
                else if (code == 4409) { markDropped(); socket = null; welcomed = false; notifyState(); } // otra conexión nuestra la reemplazó
                // Cambiaron accepts o grants: se reconecta en seguida, sin la espera creciente,
                // para recibir el welcome nuevo.
                else if (code == CLOSE_WELCOME_CHANGED) { attempt = 0; scheduleReconnect(0); }
                else scheduleReconnect();
            });
        }

        @Override public void onFailure(WebSocket ws, Throwable t, Response response) {
            handler.post(() -> {
                if (ws != socket) return;
                int status = response != null ? response.code() : 0;
                L.e(TAG, "falla " + status + " " + t);
                if (status == 401) revoked();
                else scheduleReconnect();
            });
        }
    }

    /** sms.status rejected de una orden (SMS_GATEWAY.md §4), con el id de estado sha256(order_id + "\n" + status). */
    private void reject(String orderId) {
        WebSocket ws = socket;
        if (ws == null || orderId.isEmpty()) return;
        try {
            JSONObject m = new JSONObject();
            m.put("type", "sms.status");
            m.put("id", Protocol.statusId(orderId, "rejected"));
            m.put("order_id", orderId);
            m.put("status", "rejected");
            m.put("at", Protocol.iso(System.currentTimeMillis()));
            m.put("error", "no_sms");
            ws.send(m.toString());
        } catch (Exception e) {
            L.e(TAG, "sms.status: " + e);
        }
    }

    private void revoked() {
        // El token ya no sirve: hay que volver a emparejar. Sin reintentos.
        markDropped();
        prefs.setRevoked(true);
        socket = null;
        welcomed = false;
        started = false;
        notifyState();
    }

    private void handle(String text) {
        JSONObject msg;
        try {
            msg = new JSONObject(text);
        } catch (Exception e) {
            return;
        }
        String type = msg.optString("type");
        // La respuesta a un pedido: result, o error con ref q_….
        String ref = msg.optString("ref");
        if (("result".equals(type) || "error".equals(type)) && ref.startsWith("q_")
                && requests.respond(ref, msg)) {
            return;
        }
        switch (type) {
            case "welcome":
                welcomed = true;
                connectedSince = System.currentTimeMillis();
                prefs.setDisconnectedAt(0);
                attempt = 0;
                heartbeatS = msg.optInt("heartbeat_s", 30);
                prefs.setLastConnected(System.currentTimeMillis());
                prefs.setAccepts(msg.optJSONArray("accepts"));
                prefs.setGrants(msg.optJSONArray("grants"));
                // paynani #745: citas y reacciones se saben en el primer webchat.thread de esta
                // conexión; hasta entonces no, por si ahora es otro paynani o uno anterior.
                prefs.setWebchatFeatures(false, false);
                prefs.setServerVersion(msg.optString("server_version", ""));
                notifyState();
                break;
            case "webchat.changed":
                // paynani PRD #653 FR-6: a toda conexión con webchat.owner; con draft trae su texto.
                WebchatEvents.changed(msg.optString("what"), GatewayWebchatSource.draftText(msg.opt("draft")));
                break;
            case "sms.out":
                // Una pasarela anterior a paynani #671 no conoce "sms": false y puede mandar órdenes:
                // se rechazan en el acto, para que no queden pendientes hasta vencer (Iris en #1).
                reject(msg.optString("id"));
                break;
            case "agent.changed":
                // paynani #692: el estado nuevo del agente, sin id; la pantalla «Agente» vuelve a pedir agent.get.
                GatewayAgent.statusChanged(context, msg.optString("status"));
                break;
            case "error":
                L.e(TAG, "error de la pasarela: " + msg.optString("code"));
                break;
            default:
                break;
        }
    }
}
