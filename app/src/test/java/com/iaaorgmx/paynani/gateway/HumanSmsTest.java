package com.iaaorgmx.paynani.gateway;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Las reglas de los SMS del dueño (PaynaniApp#111, SMS_GATEWAY.md §3) sin Android. */
public class HumanSmsTest {

    @Test
    public void localIdIsH_And32Hex() {
        String id = HumanSms.newLocalId();
        assertTrue(id.matches("^h_[0-9a-f]{32}$"));
        assertTrue(HumanSms.validLocalId(id));
        assertNotEquals(id, HumanSms.newLocalId());
        assertFalse(HumanSms.validLocalId("o_" + id.substring(2)));
        assertFalse(HumanSms.validLocalId(null));
    }

    @Test
    public void statusIdIsSha256OfLocalIdAndStatus() {
        // El mismo cálculo que la pasarela (gateway.human_status_id).
        String id = "h_" + "1".repeat(32);
        assertEquals(Protocol.sha256Hex(id + "\n" + "sent"), HumanSms.statusId(id, "sent"));
        assertNotEquals(HumanSms.statusId(id, "sent"), HumanSms.statusId(id, "delivered"));
    }

    @Test
    public void pauseStaysFrom0To1440() {
        assertEquals(0, HumanSms.clampPause(-5));
        assertEquals(30, HumanSms.clampPause(30));
        assertEquals(1440, HumanSms.clampPause(5000));
    }

    @Test
    public void textMustHaveSomethingAndAtMost1000() {
        assertEquals("empty_text", HumanSms.textProblem("   "));
        assertEquals("empty_text", HumanSms.textProblem(null));
        assertEquals("text_too_long", HumanSms.textProblem("x".repeat(1001)));
        assertNull(HumanSms.textProblem("x".repeat(1000)));
    }
}
