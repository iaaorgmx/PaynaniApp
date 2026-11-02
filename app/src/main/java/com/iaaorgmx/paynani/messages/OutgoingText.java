package com.iaaorgmx.paynani.messages;

/**
 * El texto que se manda desde «Mensajes de WhatsApp» y «Mensajes de Telegram» (paynani #612): sin
 * los espacios ni los saltos de línea del final, que el teclado deja a veces. Lo del principio y lo
 * de en medio se manda tal cual.
 */
public final class OutgoingText {

    private OutgoingText() {
    }

    /** `text` sin los espacios en blanco del final; "" si es null o no queda nada. */
    public static String trimEnd(String text) {
        if (text == null) return "";
        int end = text.length();
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) end--;
        return text.substring(0, end);
    }
}
