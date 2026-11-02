package com.iaaorgmx.paynani.gateway;

import android.app.PendingIntent;
import android.content.Context;
import android.telephony.SmsManager;

import com.iaaorgmx.paynani.AppPrefs;
import com.iaaorgmx.paynani.EventLogRepository;
import com.iaaorgmx.paynani.L;
import com.iaaorgmx.paynani.gateway.db.HumanSend;

import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Los SMS que el dueño manda desde «Mensajes» (PaynaniApp#111, APP-C2 y APP-C3).
 *
 * - Se guardan en human_sends antes de mandarse, así el estado no se pierde si la
 *   app se cierra.
 * - Salen como los de sms.out (sendMultipartTextMessage con SmsStatusReceiver),
 *   pero sin la lista allowed ni el tope por hora (Q98 y Q99): los decide el dueño.
 * - Cada estado (sent, failed, delivered) va a la pasarela como sms.human, por la
 *   cola con ack, sólo si welcome.accepts lo trae (SMS_GATEWAY.md §3). Con una
 *   pasarela anterior el SMS sale igual y no se avisa.
 *
 * Todo fuera del hilo principal (GatewayQueue.run).
 */
public final class HumanSms {

    public static final String TYPE = "sms.human";
    public static final int MAX_TEXT = 1000;
    public static final int PAUSE_DEFAULT_MIN = 30;
    public static final int PAUSE_MAX_MIN = 1440;

    public static final String SENDING = "sending";
    public static final String SENT = "sent";
    public static final String FAILED = "failed";
    public static final String DELIVERED = "delivered";

    /**
     * Quién quiere saber que cambió el estado de un SMS del dueño: el hilo abierto. Un
     * envío que falla no siempre toca el almacén de SMS (en el teléfono de Rock,
     * Android 9, no), así que sin este aviso «No se envió · Reintentar» salía hasta
     * volver a abrir el hilo. Es en el mismo proceso: un ContentObserver sobre una
     * autoridad sin ContentProvider truena desde Android 14.
     */
    private static final java.util.Set<Runnable> LISTENERS = new java.util.concurrent.CopyOnWriteArraySet<>();

    public static void addListener(Runnable listener) {
        LISTENERS.add(listener);
    }

    public static void removeListener(Runnable listener) {
        LISTENERS.remove(listener);
    }

    private static final Pattern LOCAL_ID = Pattern.compile("^h_[0-9a-f]{32}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private HumanSms() {}

    /** h_ + 16 bytes al azar en hex: nunca se confunde con una orden del agente (o_…). */
    public static String newLocalId() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder("h_");
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x));
        return sb.toString();
    }

    public static boolean validLocalId(String id) {
        return id != null && LOCAL_ID.matcher(id).matches();
    }

    /** id de cada sms.human: sha256(local_id + "\n" + status), igual que sms.status. */
    public static String statusId(String localId, String status) {
        return Protocol.statusId(localId, status);
    }

    public static int clampPause(int minutes) {
        return Math.max(0, Math.min(PAUSE_MAX_MIN, minutes));
    }

    /** Por qué el texto no se puede mandar, o null: vacío o de más de 1,000 caracteres. */
    public static String textProblem(String text) {
        if (text == null || text.trim().isEmpty()) return "empty_text";
        if (text.length() > MAX_TEXT) return "text_too_long";
        return null;
    }

    /**
     * Guarda y manda un SMS del dueño. Devuelve su local_id. El estado llega después
     * por SmsStatusReceiver; si Android no lo deja ni intentar, queda failed ya.
     */
    public static String send(Context context, String toRaw, String text) {
        String problem = textProblem(text);
        if (problem != null) throw new IllegalArgumentException(problem);
        if (toRaw == null || toRaw.trim().isEmpty()) throw new IllegalArgumentException("empty_to");
        Context app = context.getApplicationContext();
        GatewayPrefs prefs = new GatewayPrefs(app);
        GatewayQueue queue = GatewayQueue.get(app);
        String raw = toRaw.trim();
        String e164 = Phone.toE164(raw, Protocol.simCountry(app));
        int slot = prefs.getSimSlot();
        SmsManager sms = GatewayClient.smsManagerFor(app, slot);
        ArrayList<String> parts = sms.divideMessage(text);

        HumanSend row = new HumanSend();
        row.local_id = newLocalId();
        row.to_raw = raw;
        row.to_e164 = e164;
        row.text = text;
        row.status = SENDING;
        row.parts = parts.size();
        row.sim_slot = slot;
        // A qué mensaje contesta: el último sms.in de ese número que la app vio (7 días).
        row.in_reply_to = queue.lastSeenId(Protocol.senderKey(raw, e164));
        row.created_at = row.updated_at = System.currentTimeMillis();
        queue.insertHuman(row);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        ArrayList<PendingIntent> sent = new ArrayList<>();
        ArrayList<PendingIntent> delivered = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            boolean last = i == parts.size() - 1;
            sent.add(last ? PendingIntent.getBroadcast(app, row.local_id.hashCode(),
                    SmsStatusReceiver.humanIntent(app, row.local_id, "sent"), flags) : null);
            delivered.add(last ? PendingIntent.getBroadcast(app, row.local_id.hashCode() + 1,
                    SmsStatusReceiver.humanIntent(app, row.local_id, "delivered"), flags) : null);
        }
        try {
            sms.sendMultipartTextMessage(e164 != null ? e164 : raw, null, parts, sent, delivered);
        } catch (Exception e) {
            onStatus(app, row.local_id, FAILED, e.getClass().getSimpleName());
        }
        return row.local_id;
    }

    /** Reintentar es mandarlo otra vez, con un local_id nuevo: el que falló ya se avisó como failed. */
    public static String retry(Context context, String localId) {
        HumanSend row = GatewayQueue.get(context).getHuman(localId);
        if (row == null) return null;
        return send(context, row.to_raw, row.text);
    }

    /** Un estado de Android: se guarda, se anota en el registro y se avisa a la pasarela. */
    static void onStatus(Context context, String localId, String status, String error) {
        GatewayQueue queue = GatewayQueue.get(context);
        HumanSend row = queue.getHuman(localId);
        if (row == null) return;
        // delivered no baja a sent; un sent tardío no tapa un delivered.
        if (!(DELIVERED.equals(row.status) && SENT.equals(status))) {
            queue.setHumanStatus(localId, status, error);
            for (Runnable listener : LISTENERS) listener.run();
        }
        if (SENT.equals(status)) {
            new EventLogRepository(context).saveEvent(EventLogRepository.EVENT_SENT_TO_CONTACT, row.text, row.to_raw);
        }
        report(context, row, status, error);
    }

    private static void report(Context context, HumanSend row, String status, String error) {
        GatewayPrefs prefs = new GatewayPrefs(context);
        if (!prefs.accepts(TYPE)) return;
        try {
            JSONObject to = new JSONObject();
            to.put("e164", row.to_e164 != null ? row.to_e164 : JSONObject.NULL);
            to.put("raw", row.to_raw);
            JSONObject m = new JSONObject();
            String id = statusId(row.local_id, status);
            m.put("type", TYPE);
            m.put("id", id);
            m.put("local_id", row.local_id);
            m.put("to", to);
            m.put("text", row.text);
            m.put("status", status);
            m.put("parts", Math.max(1, row.parts));
            // En la hora de la pasarela, como device.status.
            m.put("at", System.currentTimeMillis() + prefs.getClockOffsetMs());
            m.put("agent_pause_min", new AppPrefs(context).getAgentPauseMin());
            if (row.in_reply_to != null) m.put("in_reply_to", row.in_reply_to);
            if (error != null) m.put("error", error);
            GatewayClient.enqueue(context, id, TYPE, m);
        } catch (Exception e) {
            L.e("HumanSms", "sms.human no se encoló: " + e);
        }
    }
}
