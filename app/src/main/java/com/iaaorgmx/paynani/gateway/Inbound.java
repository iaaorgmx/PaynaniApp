package com.iaaorgmx.paynani.gateway;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.telephony.SmsMessage;
import android.telephony.SubscriptionInfo;

import com.iaaorgmx.paynani.L;

import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * Lo que entra al teléfono y va hacia la pasarela (APP-4, APP-11): SMS recibidos y
 * llamadas perdidas o contestadas. SMS_GATEWAY.md §3.
 *
 * El receptor en tiempo real y el barrido de la bandeja pasan por aquí, así que
 * los dos calculan el mismo id para el mismo SMS (REL-1).
 */
public final class Inbound {

    private static final String TAG = "Inbound";

    // «*/=» y 6 caracteres al final del texto: lo traen algunos SMS a un número que
    // usó RCS y Google Messages no lo enseña (#76). \z y no $: un salto de línea
    // final no cuenta como fin.
    private static final Pattern RCS_SUFFIX = Pattern.compile("\\*/=[A-Za-z0-9+/_-]{6}\\z");

    private Inbound() {}

    /**
     * Encola un sms.in. Devuelve el id si lo encoló nuevo; null si no hay
     * emparejamiento, si es de otra SIM, si ya se había visto o si falló.
     */
    public static String sms(Context context, String raw, long sentMs, long receivedMs,
                             String text, int parts, int subId) {
        String clean = stripRcsSuffix(text);
        if (clean != null && !clean.equals(text)) {
            L.d(TAG, "sufijo quitado: " + text.substring(clean.length()));
        }
        text = clean;
        if (!new GatewayPrefs(context).isPaired()) return null;
        if (!Sims.accepts(context, subId)) {
            // De la SIM que paynani no atiende: no se encola, pero se anota en seen_in
            // para que RcsWatcher no lo tome por un mensaje que la app no vio (#55).
            try {
                String e164 = Phone.toE164(raw, regionFor(context, subId));
                String key = Protocol.senderKey(raw, e164);
                GatewayQueue.get(context).seenSms(key, sentMs, text, Protocol.smsId(key, sentMs, text));
            } catch (Exception e) {
                L.e(TAG, "sms de la otra SIM: " + e);
            }
            return null;
        }
        try {
            String region = regionFor(context, subId);
            String e164 = Phone.toE164(raw, region);
            String key = Protocol.senderKey(raw, e164);
            String id = Protocol.smsId(key, sentMs, text);
            JSONObject m = new JSONObject();
            m.put("type", "sms.in");
            m.put("id", id);
            m.put("from", from(raw, e164));
            m.put("sim", sim(context, subId));
            m.put("text", text);
            m.put("sent_at", Protocol.iso(sentMs));
            m.put("received_at", Protocol.iso(receivedMs));
            m.put("parts", Math.max(1, parts));
            // El mismo SMS por la otra ruta con el texto un poco distinto (#46):
            // gana el primero que llegó. Se anota justo antes de encolar y se
            // olvida si encolar falla: un duplicado se ve, una pérdida no.
            GatewayQueue queue = GatewayQueue.get(context);
            String seen = queue.seenSms(key, sentMs, text, id);
            if (seen != null) {
                L.d(TAG, "sms duplicado de " + seen);
                return null;
            }
            boolean fresh;
            try {
                fresh = GatewayClient.enqueue(context, id, "sms.in", m);
            } catch (RuntimeException e) {
                queue.forgetSeenSms(id);
                throw e;
            }
            if (!fresh) {
                L.d(TAG, "sms ya estaba en la cola: " + id);
                return null;
            }
            return id;
        } catch (Exception e) {
            L.e(TAG, "sms: " + e);
            return null;
        }
    }

    /**
     * El texto sin el sufijo de RCS del final (RCS_SUFFIX, #76). Sólo el final, y nunca deja
     * un SMS vacío: si el texto es nada más el sufijo, se queda como vino.
     */
    static String stripRcsSuffix(String text) {
        if (text == null) return null;
        Matcher m = RCS_SUFFIX.matcher(text);
        if (!m.find() || m.start() == 0) return text;
        return text.substring(0, m.start());
    }

    /**
     * Encola un sms.unseen: Google Messages publicó un mensaje y en la ventana no
     * entró ningún SMS (#55). No lleva el texto de la notificación, sólo su title.
     * La SIM es la predeterminada: de una notificación no se sabe por cuál llegó.
     */
    static String unseen(Context context, String notificationKey, long when, long postedMs, String title) {
        try {
            String id = Unseen.id(notificationKey, when);
            JSONObject m = new JSONObject();
            m.put("type", Unseen.TYPE);
            m.put("id", id);
            m.put("app", Unseen.MESSAGES);
            m.put("posted_at", Protocol.iso(postedMs));
            m.put("title", title);
            m.put("sim", sim(context, -1));
            GatewayClient.enqueue(context, id, Unseen.TYPE, m);
            return id;
        } catch (Exception e) {
            L.e(TAG, "unseen: " + e);
            return null;
        }
    }

    /** Encola un call.missed o call.answered (durationS < 0 para la perdida). */
    public static String call(Context context, String type, String raw, long startedMs,
                              int durationS, int subId) {
        if (!new GatewayPrefs(context).isPaired() || !Sims.accepts(context, subId)) return null;
        try {
            String rawNumber = raw == null ? "" : raw;
            String e164 = rawNumber.isEmpty() ? null : Phone.toE164(rawNumber, regionFor(context, subId));
            String id = Protocol.callId(type, Protocol.senderKey(rawNumber, e164), startedMs);
            JSONObject m = new JSONObject();
            m.put("type", type);
            m.put("id", id);
            m.put("from", from(rawNumber, e164));
            m.put("sim", sim(context, subId));
            m.put("started_at", Protocol.iso(startedMs));
            if (durationS >= 0) m.put("duration_s", durationS);
            GatewayClient.enqueue(context, id, type, m);
            return id;
        } catch (Exception e) {
            L.e(TAG, "call: " + e);
            return null;
        }
    }

    /**
     * Barrido de respaldo de la bandeja (REL-3): sólo lo que llegó después de la
     * última marca. La marca arranca al emparejar, así que no sube el historial.
     * Devuelve cuántos SMS encoló.
     */
    public static synchronized int sweep(Context context) {
        GatewayPrefs prefs = new GatewayPrefs(context);
        if (!prefs.isPaired()) return 0;
        long mark = prefs.getSweepMark();
        if (mark <= 0) {
            prefs.setSweepMark(System.currentTimeMillis());
            return 0;
        }
        int count = 0;
        long newest = mark;
        Cursor c = null;
        try {
            c = context.getContentResolver().query(Uri.parse("content://sms/inbox"),
                    new String[]{"address", "body", "date", "date_sent", "sub_id"},
                    "date > ?", new String[]{String.valueOf(mark)}, "date ASC");
            if (c == null) return 0;
            while (c.moveToNext()) {
                String raw = c.getString(0);
                String body = c.getString(1);
                long received = c.getLong(2);
                long sent = c.getLong(3);
                int subId = c.isNull(4) ? -1 : c.getInt(4);
                if (body == null) body = "";
                int parts = SmsMessage.calculateLength(body, false)[0];
                if (sms(context, raw == null ? "" : raw, sent > 0 ? sent : received, received, body, parts, subId) != null) {
                    count++;
                }
                newest = Math.max(newest, received);
            }
        } catch (SecurityException e) {
            L.e(TAG, "sweep sin permiso READ_SMS");
        } catch (Exception e) {
            L.e(TAG, "sweep: " + e);
        } finally {
            if (c != null) c.close();
        }
        if (newest > mark) prefs.setSweepMark(newest);
        return count;
    }

    private static JSONObject from(String raw, String e164) throws Exception {
        JSONObject f = new JSONObject();
        f.put("e164", e164 != null ? e164 : JSONObject.NULL);
        f.put("raw", raw == null ? "" : raw);
        return f;
    }

    /** {slot, number} de la SIM por la que entró, o la predeterminada. */
    private static JSONObject sim(Context context, int subId) throws Exception {
        JSONObject s = new JSONObject();
        SubscriptionInfo info = Sims.bySubId(context, subId);
        String number = info != null ? Sims.number(context, info) : null;
        s.put("slot", info != null ? info.getSimSlotIndex() : 0);
        s.put("number", number != null ? number : JSONObject.NULL);
        return s;
    }

    private static String regionFor(Context context, int subId) {
        return Sims.region(context, Sims.bySubId(context, subId));
    }
}
