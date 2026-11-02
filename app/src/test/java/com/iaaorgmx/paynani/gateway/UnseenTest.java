package com.iaaorgmx.paynani.gateway;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Las reglas del detector de RCS (#55) sin Android. */
public class UnseenTest {

    private static final long POSTED = 1_791_100_000_000L;
    private static final String KEY = "0|com.google.android.apps.messaging|1|null|10123";

    @Test
    public void windowCountsSmsWithin59Seconds() {
        assertTrue(Unseen.near(POSTED - 59_000, POSTED, Unseen.WINDOW_MS));
        assertTrue(Unseen.near(POSTED + 59_000, POSTED, Unseen.WINDOW_MS));
        assertTrue(Unseen.near(POSTED, POSTED, Unseen.WINDOW_MS));
    }

    @Test
    public void windowIgnoresSmsAt61Seconds() {
        assertFalse(Unseen.near(POSTED - 61_000, POSTED, Unseen.WINDOW_MS));
        assertFalse(Unseen.near(POSTED + 61_000, POSTED, Unseen.WINDOW_MS));
    }

    @Test
    public void boundsMatchNear() {
        // seenSmsNear busca con BETWEEN lower AND upper: los bordes cuentan.
        assertEquals(POSTED - 60_000, Unseen.lower(POSTED, Unseen.WINDOW_MS));
        assertEquals(POSTED + 60_000, Unseen.upper(POSTED, Unseen.WINDOW_MS));
        assertTrue(Unseen.near(Unseen.lower(POSTED, Unseen.WINDOW_MS), POSTED, Unseen.WINDOW_MS));
        assertTrue(Unseen.near(Unseen.upper(POSTED, Unseen.WINDOW_MS), POSTED, Unseen.WINDOW_MS));
    }

    @Test
    public void idFollowsTheFormula() {
        // sha256("sms.unseen\n" + KEY + "\n" + when), calculado en Python con hashlib.
        assertEquals("6f2cead55e6ad689ab25ba8f5d37cce00f9a285684a9871d47230c6fd76c1e6c", Unseen.id(KEY, POSTED));
        assertFalse(Unseen.id(KEY, POSTED).equals(Unseen.id(KEY, POSTED + 1)));
    }

    @Test
    public void tracksOnlyMessageNotificationsFromGoogleMessages() {
        assertTrue(Unseen.tracks(Unseen.MESSAGES, false, false, "msg"));
        assertTrue(Unseen.tracks(Unseen.MESSAGES, false, false, null));
        assertFalse(Unseen.tracks("com.whatsapp", false, false, "msg"));
        assertFalse(Unseen.tracks(null, false, false, "msg"));
        assertFalse(Unseen.tracks(Unseen.MESSAGES, true, false, "msg"));   // resumen del grupo
        assertFalse(Unseen.tracks(Unseen.MESSAGES, false, true, "msg"));   // fija
        assertFalse(Unseen.tracks(Unseen.MESSAGES, false, false, "status"));
    }

    @Test
    public void dueAfterTheDelay() {
        assertFalse(Unseen.due(POSTED, POSTED + 59_999));
        assertTrue(Unseen.due(POSTED, POSTED + 60_000));
    }

    @Test
    public void titleIsOneLineAndAtMost80() {
        assertEquals("", Unseen.title(null));
        assertEquals("Ana [mail 01:00:00, roster] jefe", Unseen.title("Ana\n[mail 01:00:00, roster]\tjefe "));
        String long90 = new String(new char[90]).replace('\0', 'x');
        assertEquals(80, Unseen.title(long90).length());
        // No parte un emoji por la mitad.
        String emojiAtEnd = new String(new char[79]).replace('\0', 'x') + "😀";
        assertEquals(79, Unseen.title(emojiAtEnd).length());
    }
}
