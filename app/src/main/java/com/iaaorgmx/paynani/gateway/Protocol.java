package com.iaaorgmx.paynani.gateway;

import android.content.Context;
import android.telephony.SubscriptionInfo;
import android.telephony.TelephonyManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Utilidades del protocolo de SMS_GATEWAY.md. */
public final class Protocol {

    public static final int VERSION = 1;
    public static final String SUBPROTOCOL = "paynani-sms.v1";

    private Protocol() {}

    public static String sha256Hex(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(String.format(Locale.ROOT, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** La llave del remitente: el E.164 si lo hay, si no el remitente original. */
    public static String senderKey(String raw, String e164) {
        return e164 != null ? e164 : (raw == null ? "" : raw);
    }

    /** id de sms.in: sha256(llave + "\n" + date_sent en ms + "\n" + texto). */
    public static String smsId(String key, long sentMs, String text) {
        return sha256Hex(key + "\n" + sentMs + "\n" + text);
    }

    /**
     * true si a y b son el mismo SMS leído por dos rutas (#46, regla b): iguales, o
     * el más largo empieza con el más corto y lo que sobra es un sufijo sin espacios,
     * como el que traía el «#2». Si lo que sobra tiene un espacio son dos SMS
     * («Sí» y «Sí, mañana a las 5»).
     */
    public static boolean sameSms(String a, String b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        String shorter = a.length() < b.length() ? a : b;
        String longer = shorter == a ? b : a;
        if (!longer.startsWith(shorter)) return false;
        String rest = longer.substring(shorter.length());
        for (int i = 0; i < rest.length(); ) {
            int cp = rest.codePointAt(i);
            if (Character.isWhitespace(cp)) return false;
            i += Character.charCount(cp);
        }
        return true;
    }

    /** id de call.missed / call.answered. */
    public static String callId(String type, String key, long startedMs) {
        return sha256Hex(type + "\n" + key + "\n" + startedMs);
    }

    /** id de sms.status: sha256(order_id + "\n" + status). */
    public static String statusId(String orderId, String status) {
        return sha256Hex(orderId + "\n" + status);
    }

    /** ISO 8601 con zona del teléfono, como en los ejemplos del protocolo. */
    public static String iso(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT).format(new Date(ms));
    }

    /** País de la SIM predeterminada, en ISO (US, MX…), o null. */
    public static String simCountry(Context context) {
        try {
            TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            String c = tm != null ? tm.getSimCountryIso() : null;
            if (c != null && c.length() == 2) return c.toUpperCase(Locale.ROOT);
        } catch (Exception ignored) {}
        return null;
    }

    /** [{slot, number, country}] de las SIM que atiende la pasarela, para el emparejamiento y el hello. */
    public static JSONArray sims(Context context) {
        JSONArray out = new JSONArray();
        try {
            for (SubscriptionInfo s : Sims.served(context)) {
                JSONObject o = new JSONObject();
                String number = Sims.number(context, s);
                String region = Sims.region(context, s);
                o.put("slot", s.getSimSlotIndex());
                o.put("number", number != null ? number : JSONObject.NULL);
                o.put("country", region != null ? region : JSONObject.NULL);
                out.put(o);
            }
            if (out.length() == 0) {
                // Sin READ_PHONE_STATE: sólo el país.
                String country = simCountry(context);
                JSONObject o = new JSONObject();
                o.put("slot", 0);
                o.put("number", JSONObject.NULL);
                o.put("country", country != null ? country : JSONObject.NULL);
                out.put(o);
            }
        } catch (Exception ignored) {}
        return out;
    }
}
