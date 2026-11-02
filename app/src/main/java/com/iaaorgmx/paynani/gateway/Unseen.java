package com.iaaorgmx.paynani.gateway;

/**
 * Las reglas del detector de mensajes que Google Messages recibió y la app no vio
 * (#46 §4.3, #55), sin Android para que se prueben con JUnit. RcsWatcher y
 * GatewayQueue las usan.
 */
public final class Unseen {

    public static final String MESSAGES = "com.google.android.apps.messaging";
    public static final String TYPE = "sms.unseen";

    /** Cuánto se espera a que llegue el SMS antes de avisar. */
    public static final long DELAY_MS = 60_000L;
    /** Qué tan lejos de la notificación puede haber llegado el SMS para contar. */
    public static final long WINDOW_MS = 60_000L;
    /** El title de la notificación se recorta a esto. */
    public static final int TITLE_MAX = 80;

    private Unseen() {}

    /**
     * true si es una notificación de mensaje de Google Messages que hay que vigilar:
     * no es el resumen de un grupo ni una notificación fija, y su categoría es de
     * mensaje (o no trae categoría).
     */
    public static boolean tracks(String pkg, boolean groupSummary, boolean ongoing, String category) {
        if (!MESSAGES.equals(pkg) || groupSummary || ongoing) return false;
        return category == null || "msg".equals(category);
    }

    /** id de sms.unseen: sha256("sms.unseen\n" + llave de la notificación + "\n" + when). */
    public static String id(String notificationKey, long when) {
        return Protocol.sha256Hex(TYPE + "\n" + notificationKey + "\n" + when);
    }

    /** true si un SMS visto en atMs cae en la ventana de una notificación de postedMs. */
    public static boolean near(long atMs, long postedMs, long windowMs) {
        return atMs >= lower(postedMs, windowMs) && atMs <= upper(postedMs, windowMs);
    }

    public static long lower(long postedMs, long windowMs) { return postedMs - windowMs; }

    public static long upper(long postedMs, long windowMs) { return postedMs + windowMs; }

    /** true si ya pasó la espera y toca revisar esa notificación. */
    public static boolean due(long postedMs, long nowMs) {
        return nowMs - postedMs >= DELAY_MS;
    }

    /** El title recortado a TITLE_MAX, sin saltos de línea; "" si no hay. */
    public static String title(CharSequence raw) {
        if (raw == null) return "";
        String t = raw.toString().replaceAll("\\s+", " ").trim();
        if (t.length() <= TITLE_MAX) return t;
        int end = Character.isHighSurrogate(t.charAt(TITLE_MAX - 1)) ? TITLE_MAX - 1 : TITLE_MAX;
        return t.substring(0, end);
    }
}
