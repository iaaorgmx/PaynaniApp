package com.iaaorgmx.paynani.gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Constantes y utilidades del protocolo de SMS_GATEWAY.md en paynani. */
public final class Protocol {

    public static final int VERSION = 1;
    public static final String SUBPROTOCOL = "paynani-sms.v1";

    private Protocol() {}

    static String sha256Hex(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(String.format(Locale.ROOT, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** id de sms.status: sha256(order_id + "\n" + status). */
    static String statusId(String orderId, String status) {
        return sha256Hex(orderId + "\n" + status);
    }

    /** ISO 8601 con zona del teléfono, como en los ejemplos del protocolo. */
    static String iso(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT).format(new Date(ms));
    }
}
