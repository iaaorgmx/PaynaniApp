package com.iaaorgmx.paynani.agent;

/** Reglas sin Android para el agente (#11): el estado, su color y el formato de tiempos. */
public final class AgentRules {

    public static final String ONLINE = "online";
    public static final String NO_SESSION = "no_session";
    public static final String OFFLINE = "offline";
    public static final String UNKNOWN = "unknown";

    /** Los tonos de PaynaniWeb (agent_pages.TONES): verde, amarillo, rojo y gris. */
    public static final int TONE_OK = 0, TONE_WARN = 1, TONE_BAD = 2, TONE_OFF = 3;

    private AgentRules() {}

    /** Un estado conocido; cualquier otro valor, o ninguno, es «unknown». */
    public static String status(String s) {
        if (ONLINE.equals(s) || NO_SESSION.equals(s) || OFFLINE.equals(s)) return s;
        return UNKNOWN;
    }

    public static int tone(String status) {
        switch (status(status)) {
            case ONLINE: return TONE_OK;
            case NO_SESSION: return TONE_WARN;
            case OFFLINE: return TONE_BAD;
            default: return TONE_OFF;
        }
    }

    /** `_unit_pill` de PaynaniWeb: active verde, unknown gris, lo demás rojo. */
    public static int unitTone(String unit) {
        if ("active".equals(unit)) return TONE_OK;
        if (unit == null || "unknown".equals(unit)) return TONE_OFF;
        return TONE_BAD;
    }

    /** `whatsapp_row`: connected verde, service_down rojo, lo demás ámbar. */
    public static int whatsappTone(String state) {
        if ("connected".equals(state)) return TONE_OK;
        if ("service_down".equals(state)) return TONE_BAD;
        return TONE_WARN;
    }

    /** `telegram_pages.state_tone`: running verde, starting u offline ámbar, lo demás rojo. */
    public static int telegramTone(String state) {
        if ("running".equals(state)) return TONE_OK;
        if ("starting".equals(state) || "offline".equals(state)) return TONE_WARN;
        return TONE_BAD;
    }

    /** Como `_minutes` de agent_pages.py: «42 s», «3 min», «1 h 05 min». */
    public static String duration(int seconds) {
        if (seconds < 60) return seconds + " s";
        if (seconds < 3600) return Math.round(seconds / 60.0) + " min";
        return seconds / 3600 + " h " + String.format(java.util.Locale.ROOT, "%02d", seconds % 3600 / 60) + " min";
    }

    /** ¿La versión `latest` es más nueva que `installed`? Compara números, no texto. */
    public static boolean newer(String latest, String installed) {
        if (latest == null || latest.isEmpty()) return false;
        int[] a = parts(latest), b = parts(installed);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0, y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int[] parts(String v) {
        if (v == null) return new int[0];
        String[] p = v.replaceFirst("^v", "").split("[.-]");
        int[] out = new int[p.length];
        for (int i = 0; i < p.length; i++) {
            try {
                out[i] = Integer.parseInt(p[i]);
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }
}
