package com.iaaorgmx.paynani.gateway;

import com.iaaorgmx.paynani.telegram.TelegramEvents;
import com.iaaorgmx.paynani.webchat.GatewayWebchatSource;
import com.iaaorgmx.paynani.webchat.WebchatEvents;
import com.iaaorgmx.paynani.whatsapp.WhatsAppEvents;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;

import com.iaaorgmx.paynani.BuildConfig;
import com.iaaorgmx.paynani.EventLogRepository;
import com.iaaorgmx.paynani.L;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * La conexión con la pasarela de paynani (APP-3): un WebSocket que abre el
 * teléfono y mantiene abierto, con latido, reconexión con espera creciente, la
 * cola de entrada (APP-4) y las órdenes de salida (APP-5). SMS_GATEWAY.md §2 a §5.
 */
public final class GatewayClient {

    private static final String TAG = "GatewayClient";
    private static final long RETRY_UNACKED_MS = 30_000L;
    private static final int[] BACKOFF_S = {1, 2, 4, 8, 16, 32, 60};

    private static GatewayClient instance;

    /** Lo que muestra la notificación del servicio. */
    public enum State { UNPAIRED, DISCONNECTED, CONNECTED, REVOKED }

    public interface StateListener { void onGatewayState(State state); }

    public static synchronized GatewayClient get(Context context) {
        if (instance == null) instance = new GatewayClient(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final GatewayPrefs prefs;
    private final GatewayQueue queue;
    private final Handler handler;
    private final Random random = new Random();
    private OkHttpClient http;
    private WebSocket socket;
    private volatile boolean welcomed = false;
    private volatile boolean started = false;
    private int attempt = 0;
    /**
     * La pasarela cierra con 1012 (Service Restart) cuando cambian accepts o grants del welcome, al
     * activar o desactivar WhatsApp (SMS_GATEWAY.md §5, §12).
     */
    static final int CLOSE_WELCOME_CHANGED = 1012;
    private int heartbeatS = 30;
    private volatile StateListener listener;
    private volatile long connectedSince = 0;
    private final Runnable tickRunnable = this::tick;
    /** Pregunta y respuesta sin cola (SMS_GATEWAY.md §11): roster y reglas. */
    private final GatewayRequests<JSONObject> requests;

    private GatewayClient(Context context) {
        this.context = context;
        this.prefs = new GatewayPrefs(context);
        this.queue = GatewayQueue.get(context);
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
     * Un pedido de §11 (roster.list, roster.save…). Le pone el id q_… y lo manda por el WebSocket,
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
        // Las pantallas de WhatsApp y Telegram vacían lo mostrado al perder la conexión y releen al volver.
        WhatsAppEvents.changed(WhatsAppEvents.CONNECTION);
        TelegramEvents.changed(TelegramEvents.CONNECTION);
        WebchatEvents.changed(WebchatEvents.CONNECTION, null);
    }

    /** Arranca la conexión si hay emparejamiento. Idempotente. */
    public void start() {
        handler.post(() -> {
            if (started) return;
            started = true;
            connect();
            handler.removeCallbacks(tickRunnable);
            handler.postDelayed(tickRunnable, 5_000);
        });
    }

    public void stop() {
        handler.post(() -> {
            started = false;
            if (socket != null) socket.close(1000, "app");
            socket = null;
            welcomed = false;
            requests.disconnected();
            notifyState();
        });
    }

    /** Desemparejar: cierra la conexión, olvida el token y vacía la cola. */
    public void unpair() {
        // El token se borra ya, para que quien llama vea el cambio al volver. La
        // cola se vacía en el hilo de la pasarela: no desde el principal (#105).
        prefs.clear();
        handler.post(() -> {
            queue.clearAll();
            started = false;
            if (socket != null) socket.close(1000, "unpaired");
            socket = null;
            welcomed = false;
            notifyState();
        });
    }

    /** Reconecta ya, para que la pasarela reciba un hello nuevo (cambió la SIM elegida). */
    public void reconnect() {
        handler.post(() -> {
            if (socket != null) socket.close(1000, "sim");
        });
    }

    /** Hay algo nuevo en la cola: mándalo ya si hay conexión. */
    public void kick() {
        handler.post(this::flush);
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
        handler.postDelayed(this::connect, delay);
    }

    private void tick() {
        if (!started) return;
        flush();
        handler.postDelayed(tickRunnable, 5_000);
    }

    private void flush() {
        WebSocket ws = socket;
        if (ws == null || !welcomed) return;
        long now = System.currentTimeMillis();
        for (String[] item : queue.due(now, RETRY_UNACKED_MS, 50)) {
            if (ws.send(item[1])) queue.markSent(item[0], now);
        }
    }

    private void send(JSONObject message) {
        WebSocket ws = socket;
        if (ws != null) ws.send(message.toString());
    }

    private final class Listener extends WebSocketListener {
        @Override public void onOpen(WebSocket ws, Response response) {
            handler.post(() -> {
                try {
                    JSONObject hello = new JSONObject();
                    hello.put("type", "hello");
                    hello.put("protocol", Protocol.VERSION);
                    hello.put("device_id", prefs.getDeviceId());
                    hello.put("app_version", BuildConfig.VERSION_NAME);
                    hello.put("sims", Protocol.sims(context));
                    // Acepta órdenes del dueño (PaynaniWeb) y de la API (paynani #489 FR-17, #491 FR-19).
                    hello.put("accepts_owner_out", true);
                    hello.put("accepts_api_out", true);
                    JSONObject pending = new JSONObject();
                    pending.put("in", queue.count(null));
                    pending.put("status", queue.count("sms.status"));
                    hello.put("pending", pending);
                    // La última batería, para que la pasarela la tenga al reconectar (#109).
                    JSONObject device = prefs.getDeviceStatus();
                    if (device != null && prefs.accepts(DeviceStatus.TYPE)) hello.put("device_status", device);
                    ws.send(hello.toString());
                } catch (Exception e) {
                    L.e(TAG, "hello: " + e);
                }
            });
        }

        @Override public void onMessage(WebSocket ws, String text) {
            handler.post(() -> handle(ws, text));
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
                // Cambiaron accepts o grants (se activó o desactivó WhatsApp, SMS_GATEWAY.md §12):
                // se reconecta en seguida, sin la espera creciente, para recibir el welcome nuevo.
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

    /** El registro local anota «enviado a paynani» sólo cuando llega el ack. */
    private void logAck(String type, String json, String result, String error) {
        EventLogRepository eventLog = new EventLogRepository(context);
        if ("rejected".equals(result)) {
            L.e(TAG, "la pasarela rechazó un " + type + ": " + error);
            eventLog.saveEvent("gateway_rejected", type + (error.isEmpty() ? "" : " (" + error + ")"), "");
            return;
        }
        if ("duplicate".equals(result)) return; // ya se anotó la primera vez
        try {
            JSONObject m = new JSONObject(json);
            JSONObject from = m.optJSONObject("from");
            String raw = from != null ? from.optString("raw") : "";
            if (type.equals("sms.in") || type.startsWith("call.")) {
                String shown = from != null && !from.isNull("e164") ? from.optString("e164") : raw;
                prefs.countDelivered(type, shown, System.currentTimeMillis());
            }
            switch (type) {
                case "sms.in": eventLog.saveEvent(EventLogRepository.EVENT_SENT_TO_SERVER, m.optString("text"), raw); break;
                case "call.missed": eventLog.saveEvent(EventLogRepository.EVENT_CALLNOT_TO_SERVER, "", raw); break;
                case "call.answered": eventLog.saveEvent(EventLogRepository.EVENT_CALL_TO_SERVER, "", raw); break;
                default: break;
            }
        } catch (Exception ignored) {}
    }

    private void revoked() {
        // El token ya no sirve: hay que volver a emparejar. Sin reintentos.
        markDropped();
        prefs.setRevoked(true);
        socket = null;
        welcomed = false;
        started = false;
        notifyState();
        // Sin texto: el registro lo pone al mostrarlo, en el idioma de la app (#102).
        new EventLogRepository(context).saveEvent("gateway_revoked", "", "");
    }

    private void handle(WebSocket ws, String text) {
        JSONObject msg;
        try {
            msg = new JSONObject(text);
        } catch (Exception e) {
            return;
        }
        String type = msg.optString("type");
        // La respuesta a un pedido de §11: result, o error con ref q_….
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
                JSONArray allowed = msg.optJSONArray("allowed");
                if (allowed != null) prefs.setAllowed(allowed);
                prefs.setMaxOutPerHour(msg.optInt("max_out_per_hour", 60));
                // Una pasarela sin sms.unseen en accepts no contesta ack a ese tipo, y lo
                // que quedara en la cola se reenviaría para siempre (#55).
                prefs.setAccepts(msg.optJSONArray("accepts"));
                prefs.setGrants(msg.optJSONArray("grants"));
                prefs.setServerVersion(msg.optString("server_version", ""));
                RcsWatcher.newConnection();
                if (!prefs.accepts(Unseen.TYPE)) {
                    int dropped = queue.removeType(Unseen.TYPE);
                    if (dropped > 0) L.d(TAG, "la pasarela no acepta sms.unseen: " + dropped + " fuera de la cola");
                }
                if (!prefs.accepts(DeviceStatus.TYPE)) {
                    // Una pasarela anterior a #109: la batería no sale, y la que hubiera se va.
                    queue.removeType(DeviceStatus.TYPE);
                    prefs.setBatterySent(0, -1, false);
                }
                long server = parseIso(msg.optString("server_time"));
                if (server > 0) prefs.setClockOffsetMs(server - System.currentTimeMillis());
                queue.resetSent();
                notifyState();
                flush();
                break;
            case "allowed":
                JSONArray a = msg.optJSONArray("allowed");
                if (a != null) prefs.setAllowed(a);
                break;
            case "ack":
                String id = msg.optString("id");
                if (id.isEmpty()) break;
                String[] item = queue.get(id);
                queue.remove(id);
                if (item != null) logAck(item[0], item[1], msg.optString("result"), msg.optString("error"));
                break;
            case "sms.out":
                smsOut(msg);
                break;
            case "whatsapp.changed":
                // Sin id ni ack (§12): la pantalla que muestre WhatsApp vuelve a leer.
                WhatsAppEvents.changed(msg.optString("what"));
                break;
            case "telegram.changed":
                // Igual que whatsapp.changed (TELEGRAM.md §6): sin datos; la pantalla vuelve a leer.
                TelegramEvents.changed(msg.optString("what"));
                break;
            case "webchat.changed":
                // paynani PRD #653 FR-6: a toda conexión con webchat.owner; con draft trae su texto.
                WebchatEvents.changed(msg.optString("what"), GatewayWebchatSource.draftText(msg.opt("draft")));
                break;
            case "error":
                L.e(TAG, "error de la pasarela: " + msg.optString("code"));
                break;
            default:
                break;
        }
    }

    // --- APP-5: salida ----------------------------------------------------

    private void smsOut(JSONObject msg) {
        String orderId = msg.optString("id");
        if (OrderOrigin.of(orderId) == null) return;
        String known = queue.orderStatus(orderId);
        if (known != null) {
            // Ya recibida: la pasarela la reenvía al reconectar. No se envía otra vez.
            status(orderId, known, null, 0);
            return;
        }
        String to = Phone.toE164(msg.optString("to"), Protocol.simCountry(context));
        String text = msg.optString("text");
        long now = System.currentTimeMillis();
        long expires = parseIso(msg.optString("expires_at"));
        if (expires > 0 && now + prefs.getClockOffsetMs() > expires) {
            finish(orderId, "expired", null, 0);
            return;
        }
        // El origin debe coincidir con el prefijo del id: una w_ que dice «api» no sale.
        String origin = OrderOrigin.check(orderId, msg.optString("origin", ""));
        if (origin == null) {
            finish(orderId, "rejected", "bad_origin", 0);
            return;
        }
        // Las del dueño y la API no pasan por «allowed» ni por el tope del agente (FR-18).
        boolean limited = OrderOrigin.limited(origin);
        if (to == null || (limited && !prefs.getAllowed().contains(to))) {
            finish(orderId, "rejected", to == null && !limited ? "invalid_number" : "not_allowed", 0);
            return;
        }
        if (text.isEmpty() || text.length() > 1000) {
            finish(orderId, "rejected", text.isEmpty() ? "empty_text" : "text_too_long", 0);
            return;
        }
        if (limited && queue.sentLastHour(now) >= prefs.getMaxOutPerHour()) {
            finish(orderId, "rejected", "rate_limited", 0);
            return;
        }
        // La marca de «Mensajes» se guarda antes de enviar (FR-19). La app no manda
        // sms.human por estas órdenes: la pasarela ya las registra (FR-20).
        if (!limited) queue.addMark(orderId, origin, msg.optString("token", null), to, text);
        // Sin sim_slot en la orden, sale por la SIM elegida (APP-10), o por la predeterminada.
        SmsManager sms = smsManager(msg.has("sim_slot") ? msg.optInt("sim_slot") : prefs.getSimSlot());
        ArrayList<String> parts = sms.divideMessage(text);
        finish(orderId, "accepted", null, parts.size());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        ArrayList<PendingIntent> sent = new ArrayList<>();
        ArrayList<PendingIntent> delivered = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            boolean last = i == parts.size() - 1;
            sent.add(last ? PendingIntent.getBroadcast(context, orderId.hashCode(),
                    SmsStatusReceiver.intent(context, orderId, "sent", parts.size(), to, text), flags) : null);
            delivered.add(last ? PendingIntent.getBroadcast(context, orderId.hashCode() + 1,
                    SmsStatusReceiver.intent(context, orderId, "delivered", parts.size(), to, null), flags) : null);
        }
        try {
            sms.sendMultipartTextMessage(to, null, parts, sent, delivered);
        } catch (Exception e) {
            finish(orderId, "failed", e.getClass().getSimpleName(), parts.size());
        }
    }

    private SmsManager smsManager(int slot) {
        return smsManagerFor(context, slot);
    }

    /** El SmsManager de esa ranura de SIM, o el predeterminado. También para los SMS del dueño (#111). */
    static SmsManager smsManagerFor(Context context, int slot) {
        if (slot >= 0) {
            try {
                SubscriptionManager sm = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
                SubscriptionInfo info = sm.getActiveSubscriptionInfoForSimSlotIndex(slot);
                if (info != null) return SmsManager.getSmsManagerForSubscriptionId(info.getSubscriptionId());
            } catch (SecurityException ignored) {}
        }
        return SmsManager.getSmsManagerForSubscriptionId(SmsManager.getDefaultSmsSubscriptionId());
    }

    /** Guarda el estado de una orden y lo encola hacia la pasarela. */
    public void finish(String orderId, String status, String error, int parts) {
        queue.setOrderStatus(orderId, status);
        status(orderId, status, error, parts);
    }

    private void status(String orderId, String status, String error, int parts) {
        try {
            JSONObject m = new JSONObject();
            String sid = Protocol.statusId(orderId, status);
            m.put("type", "sms.status");
            m.put("id", sid);
            m.put("order_id", orderId);
            m.put("status", status);
            m.put("at", Protocol.iso(System.currentTimeMillis()));
            if (parts > 0) m.put("parts", parts);
            if (error != null) m.put("error", error);
            queue.enqueue(sid, "sms.status", m);
            flush();
        } catch (Exception e) {
            L.e(TAG, "status: " + e);
        }
    }

    // --- APP-4: entrada ---------------------------------------------------

    /**
     * Encola un mensaje para la pasarela (sms.in, call.*). Devuelve true si entró
     * nuevo y false si ese id ya estaba en la cola.
     */
    public static boolean enqueue(Context context, String id, String type, JSONObject message) {
        boolean fresh = GatewayQueue.get(context).enqueue(id, type, message);
        if (fresh) get(context).kick();
        return fresh;
    }

    static long parseIso(String value) {
        if (value == null || value.isEmpty()) return 0;
        String[] patterns = {"yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ssXXX"};
        for (String p : patterns) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(p, Locale.ROOT);
                if (p.endsWith("'Z'")) f.setTimeZone(TimeZone.getTimeZone("UTC"));
                return f.parse(value).getTime();
            } catch (Exception ignored) {}
        }
        return 0;
    }
}
