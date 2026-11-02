package com.iaaorgmx.paynani.gateway;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pregunta y respuesta por el WebSocket, sin cola (SMS_GATEWAY.md §11, «Cómo se habla»): cada
 * pedido lleva un id q_…, y la pasarela contesta una vez con result o error y ref igual a ese id.
 *
 * - Sin respuesta en 15 s, se reenvía una vez con el mismo id: si ya se aplicó, la pasarela
 *   contesta replayed. Si tampoco llega, falla con "timeout".
 * - Si se cae la conexión, todo lo pendiente falla con "offline": quien pidió vuelve a leer.
 *
 * Sin Android ni JSON, para probarlo con JUnit. Todo en el hilo de la pasarela.
 */
public final class GatewayRequests<M> {

    public static final long TIMEOUT_MS = 15_000L;

    public interface Sender {
        /** false si no hay conexión. */
        boolean send(String json);
    }

    public interface Scheduler {
        Object schedule(Runnable r, long delayMs);

        void cancel(Object token);
    }

    public interface Callback<M> {
        /**
         * La respuesta, result o error: quien pidió la interpreta. resent dice si llegó después del
         * reenvío: un conflict entonces puede ser la propia escritura ya aplicada (§11), y se vuelve a leer.
         */
        void onResponse(M message, boolean resent);

        /** "offline" o "timeout": no llegó respuesta y no se sabe si se aplicó. */
        void onFailure(String code);
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Sender sender;
    private final Scheduler scheduler;
    private final Map<String, Pending<M>> pending = new HashMap<>();

    private static final class Pending<M> {
        final String json;
        final Callback<M> cb;
        int attempts = 1;
        Object timer;

        Pending(String json, Callback<M> cb) {
            this.json = json;
            this.cb = cb;
        }
    }

    public GatewayRequests(Sender sender, Scheduler scheduler) {
        this.sender = sender;
        this.scheduler = scheduler;
    }

    /** q_ y 16 bytes al azar en hex (^q_[0-9a-f]{32}$). */
    public static String newId() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder("q_");
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x));
        return sb.toString();
    }

    /** Manda json, que ya lleva "id": id. */
    public void request(String id, String json, Callback<M> cb) {
        if (!sender.send(json)) {
            cb.onFailure("offline");
            return;
        }
        Pending<M> p = new Pending<>(json, cb);
        pending.put(id, p);
        p.timer = scheduler.schedule(() -> timeout(id), TIMEOUT_MS);
    }

    /** Una respuesta con ref. true si era de un pedido pendiente. */
    public boolean respond(String ref, M message) {
        Pending<M> p = pending.remove(ref);
        if (p == null) return false;
        scheduler.cancel(p.timer);
        p.cb.onResponse(message, p.attempts > 1);
        return true;
    }

    /** Se cayó la conexión: nada pendiente va a recibir respuesta. */
    public void disconnected() {
        List<Pending<M>> all = new ArrayList<>(pending.values());
        pending.clear();
        for (Pending<M> p : all) {
            scheduler.cancel(p.timer);
            p.cb.onFailure("offline");
        }
    }

    public int pendingCount() {
        return pending.size();
    }

    private void timeout(String id) {
        Pending<M> p = pending.get(id);
        if (p == null) return;
        if (p.attempts < 2 && sender.send(p.json)) {
            p.attempts++;
            p.timer = scheduler.schedule(() -> timeout(id), TIMEOUT_MS);
            return;
        }
        pending.remove(id);
        p.cb.onFailure("timeout");
    }
}
