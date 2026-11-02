package com.iaaorgmx.paynani.gateway;

import android.content.Context;

import com.iaaorgmx.paynani.gateway.db.GatewayDao;
import com.iaaorgmx.paynani.gateway.db.GatewayDatabase;
import com.iaaorgmx.paynani.gateway.db.OutItem;
import com.iaaorgmx.paynani.gateway.db.Order;
import com.iaaorgmx.paynani.gateway.db.SeenSms;
import com.iaaorgmx.paynani.gateway.db.UnseenPending;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * La cola de lo que la app le debe a la pasarela (sms.in, call.*, sms.status).
 * Un mensaje sale de aquí sólo con su ack (SMS_GATEWAY.md §3), así que nada se
 * pierde si se corta la conexión o se apaga el teléfono. El id es la llave
 * primaria: encolar dos veces el mismo SMS no lo duplica.
 *
 * Desde #105 guarda con Room (GatewayDatabase); la lógica es la misma. Room no
 * deja usar la base desde el hilo principal: quien está en él pasa su trabajo
 * a run().
 */
public final class GatewayQueue {

    private static GatewayQueue instance;

    public static synchronized GatewayQueue get(Context context) {
        if (instance == null) instance = new GatewayQueue(context.getApplicationContext());
        return instance;
    }

    // Un solo hilo para lo que llega desde el hilo principal (receptores, pantallas):
    // conserva el orden en que llegó.
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    /** Corre work fuera del hilo principal, en orden de llegada. */
    public static void run(Runnable work) {
        IO.execute(work);
    }

    /** Cuánto se recuerda un SMS visto para reconocer su duplicado. */
    private static final long SEEN_KEEP_MS = 7L * 24 * 3_600_000L;

    private final GatewayDatabase db;
    private final GatewayDao dao;

    private GatewayQueue(Context context) {
        db = GatewayDatabase.get(context);
        dao = db.dao();
    }

    /**
     * El id del SMS ya visto con la misma llave y sent_ms cuyo texto cumple
     * Protocol.sameSms, o null si es nuevo; en ese caso lo anota con ese id.
     * De paso olvida lo visto hace más de 7 días.
     */
    public synchronized String seenSms(String key, long sentMs, String text, String id) {
        long now = System.currentTimeMillis();
        return db.runInTransaction(() -> {
            dao.forgetSeenBefore(now - SEEN_KEEP_MS);
            for (SeenSms s : dao.seen(key, sentMs)) {
                if (Protocol.sameSms(s.text, text)) return s.id;
            }
            SeenSms row = new SeenSms();
            row.key = key;
            row.sentMs = sentMs;
            row.text = text;
            row.id = id;
            row.at = now;
            dao.insertSeen(row);
            return null;
        });
    }

    /** true si se vio algún SMS a menos de windowMs de ms (Unseen.near). */
    public synchronized boolean seenSmsNear(long ms, long windowMs) {
        return dao.seenBetween(Unseen.lower(ms, windowMs), Unseen.upper(ms, windowMs));
    }

    /**
     * Anota una notificación por revisar. false si esa pareja (llave, when) ya
     * estaba: Google Messages vuelve a publicar la misma al actualizarla. De paso
     * olvida las revisadas hace más de 7 días.
     */
    public synchronized boolean trackUnseen(String nkey, long whenMs, long postedMs, String title) {
        dao.forgetUnseenBefore(System.currentTimeMillis() - SEEN_KEEP_MS);
        UnseenPending row = new UnseenPending();
        row.nkey = nkey;
        row.whenMs = whenMs;
        row.postedMs = postedMs;
        row.title = title;
        return dao.insertUnseen(row) != -1;
    }

    /** {nkey, when_ms, posted_ms, title} de las notificaciones sin revisar cuya espera ya pasó. */
    public synchronized List<Object[]> dueUnseen(long now) {
        List<Object[]> out = new ArrayList<>();
        for (UnseenPending p : dao.dueUnseen(now - Unseen.DELAY_MS)) {
            out.add(new Object[]{p.nkey, p.whenMs, p.postedMs, p.title});
        }
        return out;
    }

    public synchronized void doneUnseen(String nkey, long whenMs) {
        dao.doneUnseen(nkey, whenMs);
    }

    /** Saca de la cola todo lo de un tipo; devuelve cuántos. */
    public synchronized int removeType(String type) {
        return dao.removeOutType(type);
    }

    /** Olvida un SMS anotado por seenSms que al final no se pudo encolar. */
    public synchronized void forgetSeenSms(String id) {
        dao.forgetSeen(id);
    }

    /** true si entró; false si ese id ya estaba en la cola. */
    public synchronized boolean enqueue(String id, String type, JSONObject message) {
        OutItem item = new OutItem();
        item.id = id;
        item.type = type;
        item.json = message.toString();
        item.createdAt = System.currentTimeMillis();
        return dao.insertOut(item) != -1;
    }

    /** Mensajes que hay que (re)enviar: nunca enviados, o sin ack tras retryMs. */
    public synchronized List<String[]> due(long now, long retryMs, int limit) {
        List<String[]> out = new ArrayList<>();
        for (OutItem item : dao.due(now - retryMs, limit)) out.add(new String[]{item.id, item.json});
        return out;
    }

    public synchronized void markSent(String id, long now) {
        dao.markSent(id, now);
    }

    /** {type, json} de un mensaje en la cola, o null. */
    public synchronized String[] get(String id) {
        OutItem item = dao.getOut(id);
        return item != null ? new String[]{item.type, item.json} : null;
    }

    /** Sale de la cola (ack). */
    public synchronized void remove(String id) {
        dao.removeOut(id);
    }

    /** Al desemparejar: lo pendiente era para esa pasarela y ya no tiene a dónde ir. */
    public synchronized void clearAll() {
        dao.clearOut();
        dao.clearOrders();
        dao.clearSeen();
        dao.clearUnseen();
        dao.clearMarks();
    }

    // --- SMS del dueño (PaynaniApp#111) --------------------------------------
    public synchronized void insertHuman(com.iaaorgmx.paynani.gateway.db.HumanSend row) {
        dao.insertHuman(row);
    }

    public synchronized com.iaaorgmx.paynani.gateway.db.HumanSend getHuman(String localId) {
        return dao.getHuman(localId);
    }

    public synchronized void setHumanStatus(String localId, String status, String error) {
        dao.setHumanStatus(localId, status, error, System.currentTimeMillis());
    }

    public synchronized List<com.iaaorgmx.paynani.gateway.db.HumanSend> humanTo(String toRaw, String e164) {
        return dao.humanTo(toRaw, e164);
    }

    /** El id del último sms.in de esa llave que la app vio en los últimos 7 días, o null. */
    public synchronized String lastSeenId(String key) {
        return dao.lastSeenId(key);
    }

    /** Al reconectar, todo lo pendiente vuelve a mandarse ya. */
    public synchronized void resetSent() {
        dao.resetSent();
    }

    public synchronized int count(String type) {
        return type == null ? dao.countOut() : dao.countOut(type);
    }

    // --- órdenes de envío -------------------------------------------------

    /** El último estado conocido de una orden, o null si nunca llegó. */
    public synchronized String orderStatus(String orderId) {
        return dao.orderStatus(orderId);
    }

    public synchronized void setOrderStatus(String orderId, String status) {
        Order order = new Order();
        order.id = orderId;
        order.status = status;
        order.at = System.currentTimeMillis();
        dao.putOrder(order);
    }

    // --- marcas de «Mensajes» (paynani #489 FR-19, #491 FR-19) ---------------

    /** Cuánto se recuerda la marca de una orden del dueño o de la API. */
    private static final long MARK_KEEP_MS = 90L * 24 * 3_600_000L;

    public synchronized void addMark(String orderId, String origin, String token, String toE164, String text) {
        com.iaaorgmx.paynani.gateway.db.OrderMark row = new com.iaaorgmx.paynani.gateway.db.OrderMark();
        row.order_id = orderId;
        row.origin = origin;
        row.token = token;
        row.to_e164 = toE164;
        row.text = text;
        row.at = System.currentTimeMillis();
        dao.insertMark(row);
    }

    /** Las marcas de ese número, de la más vieja a la más nueva. De paso olvida las de hace más de 90 días. */
    public synchronized List<com.iaaorgmx.paynani.gateway.db.OrderMark> marksTo(String e164) {
        dao.forgetMarksBefore(System.currentTimeMillis() - MARK_KEEP_MS);
        return dao.marksTo(e164);
    }

    /** Envíos del agente en la última hora (para max_out_per_hour). */
    public synchronized int sentLastHour(long now) {
        return dao.sentSince(now - 3_600_000L);
    }
}
