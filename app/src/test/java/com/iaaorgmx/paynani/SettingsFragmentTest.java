package com.iaaorgmx.paynani;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** La pausa del agente en Ajustes (PaynaniApp#111, APP-C4): un entero de 0 a 1440. */
public class SettingsFragmentTest {

    @Test
    public void onlyWholeMinutesFrom0To1440() {
        assertEquals(Integer.valueOf(0), SettingsFragment.parse("0"));
        assertEquals(Integer.valueOf(30), SettingsFragment.parse(" 30 "));
        assertEquals(Integer.valueOf(1440), SettingsFragment.parse("1440"));
        assertNull(SettingsFragment.parse("1441"));
        assertNull(SettingsFragment.parse(""));
        assertNull(SettingsFragment.parse("-1"));
        assertNull(SettingsFragment.parse("15.5"));
    }

    @Test
    public void theWhatsAppCardFollowsWhatTheGatewayAnnounces() {
        // Ximena en #172: false→true y true→false sin salir de Ajustes, e idempotente.
        assertEquals(SettingsFragment.WA_ADD, SettingsFragment.whatsAppAction(true, false));
        assertEquals(SettingsFragment.WA_REMOVE, SettingsFragment.whatsAppAction(false, true));
        // Ya puesta (también el hijo restaurado tras rotar) o ya quitada: no se toca.
        assertEquals(SettingsFragment.WA_NONE, SettingsFragment.whatsAppAction(true, true));
        assertEquals(SettingsFragment.WA_NONE, SettingsFragment.whatsAppAction(false, false));
    }
}
