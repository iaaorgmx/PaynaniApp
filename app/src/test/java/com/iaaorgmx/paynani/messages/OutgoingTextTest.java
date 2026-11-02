package com.iaaorgmx.paynani.messages;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.iaaorgmx.paynani.telegram.TelegramRules;
import com.iaaorgmx.paynani.whatsapp.WhatsAppRules;

import org.junit.Test;

/** paynani #612: lo que sale de WhatsApp y Telegram va sin espacios ni saltos de línea al final. */
public class OutgoingTextTest {

    @Test
    public void onlyTheEndIsTrimmed() {
        assertEquals("Claro, a las 8 hay lugar", OutgoingText.trimEnd("Claro, a las 8 hay lugar\n"));
        assertEquals("Hola", OutgoingText.trimEnd("Hola \t\r\n\n  "));
        // Lo del principio y lo de en medio se queda.
        assertEquals("  Hola\n\nAdiós", OutgoingText.trimEnd("  Hola\n\nAdiós\n"));
        assertEquals("Hola", OutgoingText.trimEnd("Hola"));
        assertEquals("", OutgoingText.trimEnd("  \n "));
        assertEquals("", OutgoingText.trimEnd(""));
        assertEquals("", OutgoingText.trimEnd(null));
    }

    @Test
    public void whitespaceOnlyIsNotSendableOnBothScreens() {
        for (String blank : new String[]{"", " ", "\n", " \n\t "}) {
            assertFalse(blank, WhatsAppRules.sendable(blank));
            assertFalse(blank, TelegramRules.sendable(blank));
        }
    }

    @Test
    public void theLimitCountsTheTextWithoutTheEnd() {
        // Un texto justo en el límite con un salto de línea de más se manda (sin el salto).
        assertTrue(WhatsAppRules.sendable(repeat(WhatsAppRules.MAX_TEXT) + "\n"));
        assertTrue(TelegramRules.sendable(repeat(TelegramRules.MAX_TEXT) + " \n"));
        assertFalse(WhatsAppRules.sendable(repeat(WhatsAppRules.MAX_TEXT + 1) + "\n"));
        assertFalse(TelegramRules.sendable(repeat(TelegramRules.MAX_TEXT + 1)));
    }

    private static String repeat(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append('a');
        return b.toString();
    }
}
