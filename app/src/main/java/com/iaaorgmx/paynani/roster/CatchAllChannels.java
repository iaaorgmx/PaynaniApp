package com.iaaorgmx.paynani.roster;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Los canales del Catch-All (paynani PRD #576 y, para Telegram, PaynaniApp #164, Q264 = A), sin
 * Android, para probarlos con JUnit: qué canal abre una pantalla, si la pasarela lo tiene y dónde
 * están los otros.
 */
public final class CatchAllChannels {

    public static final String SMS = "sms";
    public static final String WHATSAPP = "whatsapp";
    public static final String TELEGRAM = "telegram";
    /** El orden en que la pantalla nombra a los otros canales. */
    private static final List<String> ORDER = Arrays.asList(SMS, WHATSAPP, TELEGRAM);

    private CatchAllChannels() {
    }

    /** sms, whatsapp o telegram; cualquier otra cosa es sms, como antes de Telegram. */
    public static String normalize(String channel) {
        return WHATSAPP.equals(channel) || TELEGRAM.equals(channel) ? channel : SMS;
    }

    /**
     * Si esta respuesta de catchall.get o catchall.save es la del canal que se pidió; la pantalla
     * la revisa en las dos antes de mostrarla o de decir «Guardado». SMS y WhatsApp aceptan una
     * pasarela vieja (un solo Catch-All para los dos, FR-11). Telegram no: una pasarela que no
     * anuncia telegram en `channels` ignora `channel` y contestaría con el de SMS, y guardar ahí
     * cambiaría otro canal.
     */
    public static boolean supported(String channel, RosterSource.CatchAll c) {
        if (!TELEGRAM.equals(channel)) return true;
        return c.channels.contains(TELEGRAM) && (c.channel.isEmpty() || TELEGRAM.equals(c.channel));
    }

    /** Los otros canales que tiene la pasarela, en orden; vacío con una pasarela vieja. */
    public static List<String> others(String channel, List<String> channels) {
        List<String> out = new ArrayList<>();
        for (String ch : ORDER) {
            if (!ch.equals(channel) && channels.contains(ch)) out.add(ch);
        }
        return out;
    }
}
