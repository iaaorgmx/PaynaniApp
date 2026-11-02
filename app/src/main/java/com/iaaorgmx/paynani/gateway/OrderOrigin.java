package com.iaaorgmx.paynani.gateway;

/**
 * De dónde viene una orden sms.out (paynani PRD #489 FR-17 a FR-20, PRD #491 FR-19).
 *
 * El prefijo del id lo dice: o_ es del agente, w_ del dueño desde PaynaniWeb y a_ de
 * la API. Las del dueño y la API no pasan por «allowed» ni por el tope por hora del
 * agente, y tampoco cuentan para ese tope.
 */
public final class OrderOrigin {

    public static final String AGENT = "agent";
    public static final String OWNER = "owner";
    public static final String API = "api";

    private OrderOrigin() {}

    /** agent, owner o api según el id; null si el id no es de una orden. */
    public static String of(String orderId) {
        if (orderId == null || !orderId.matches("^[owa]_[0-9a-f]{32}$")) return null;
        switch (orderId.charAt(0)) {
            case 'w': return OWNER;
            case 'a': return API;
            default: return AGENT;
        }
    }

    /**
     * El origen de la orden, o null si el id no sirve o el campo origin no coincide
     * con el prefijo (una w_ que dice «api» no se envía). Las del agente no traen origin.
     */
    public static String check(String orderId, String origin) {
        String byId = of(orderId);
        if (byId == null) return null;
        String said = origin == null || origin.isEmpty() ? AGENT : origin;
        return byId.equals(said) ? byId : null;
    }

    /** true si la orden pasa por «allowed» y por el tope por hora: sólo las del agente. */
    public static boolean limited(String origin) {
        return AGENT.equals(origin);
    }

    /**
     * La marca de «Mensajes»: «desde PaynaniWeb» para el dueño, «API: <token>» para la
     * API (el nombre del token, sin él sólo «API»), null para el agente.
     */
    public static String mark(String origin, String token, String webLabel, String apiLabel) {
        if (OWNER.equals(origin)) return webLabel;
        if (API.equals(origin)) {
            String t = token == null ? "" : token.trim();
            return t.isEmpty() ? apiLabel : apiLabel + ": " + t;
        }
        return null;
    }
}
