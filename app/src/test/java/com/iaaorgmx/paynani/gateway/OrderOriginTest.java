package com.iaaorgmx.paynani.gateway;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** paynani PRD #489 FR-17 a FR-20 y PRD #491 FR-19. */
public class OrderOriginTest {

    private static final String HEX = "0123456789abcdef0123456789abcdef";

    @Test public void elPrefijoDiceElOrigen() {
        assertEquals("agent", OrderOrigin.of("o_" + HEX));
        assertEquals("owner", OrderOrigin.of("w_" + HEX));
        assertEquals("api", OrderOrigin.of("a_" + HEX));
        assertNull(OrderOrigin.of("h_" + HEX));
        assertNull(OrderOrigin.of("w_" + HEX.toUpperCase()));
        assertNull(OrderOrigin.of("w_123"));
        assertNull(OrderOrigin.of(null));
    }

    @Test public void elOriginDebeCoincidirConElPrefijo() {
        assertEquals("agent", OrderOrigin.check("o_" + HEX, ""));
        assertEquals("agent", OrderOrigin.check("o_" + HEX, null));
        assertEquals("owner", OrderOrigin.check("w_" + HEX, "owner"));
        assertEquals("api", OrderOrigin.check("a_" + HEX, "api"));
        // Una w_ que dice api, o una sin origin, no sale.
        assertNull(OrderOrigin.check("w_" + HEX, "api"));
        assertNull(OrderOrigin.check("w_" + HEX, ""));
        assertNull(OrderOrigin.check("o_" + HEX, "owner"));
        assertNull(OrderOrigin.check("a_" + HEX, "owner"));
    }

    @Test public void soloLasDelAgentePasanPorAllowedYElTope() {
        assertTrue(OrderOrigin.limited("agent"));
        assertFalse(OrderOrigin.limited("owner"));
        assertFalse(OrderOrigin.limited("api"));
    }

    @Test public void laMarcaDeMensajes() {
        assertEquals("desde PaynaniWeb", OrderOrigin.mark("owner", null, "desde PaynaniWeb", "API"));
        assertEquals("API: citas", OrderOrigin.mark("api", "citas", "desde PaynaniWeb", "API"));
        assertEquals("API", OrderOrigin.mark("api", "  ", "desde PaynaniWeb", "API"));
        assertNull(OrderOrigin.mark("agent", null, "desde PaynaniWeb", "API"));
    }
}
