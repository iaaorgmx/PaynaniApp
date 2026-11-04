package com.iaaorgmx.paynani.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** #11: los estados, colores y formatos del agente, iguales a los de PaynaniWeb. */
public class AgentRulesTest {

    @Test
    public void unknownValuesAreUnknown() {
        assertEquals(AgentRules.ONLINE, AgentRules.status("online"));
        assertEquals(AgentRules.NO_SESSION, AgentRules.status("no_session"));
        assertEquals(AgentRules.OFFLINE, AgentRules.status("offline"));
        assertEquals(AgentRules.UNKNOWN, AgentRules.status("unknown"));
        assertEquals(AgentRules.UNKNOWN, AgentRules.status(null));
        assertEquals(AgentRules.UNKNOWN, AgentRules.status("asleep"));
    }

    @Test
    public void tonesMatchAgentPages() {
        // agent_pages.TONES: online ok, no_session warn, offline bad, unknown off.
        assertEquals(AgentRules.TONE_OK, AgentRules.tone("online"));
        assertEquals(AgentRules.TONE_WARN, AgentRules.tone("no_session"));
        assertEquals(AgentRules.TONE_BAD, AgentRules.tone("offline"));
        assertEquals(AgentRules.TONE_OFF, AgentRules.tone("unknown"));
        assertEquals(AgentRules.TONE_OFF, AgentRules.tone("whatever"));
    }

    @Test
    public void channelTonesMatchDashboardPages() {
        assertEquals(AgentRules.TONE_OK, AgentRules.unitTone("active"));
        assertEquals(AgentRules.TONE_OFF, AgentRules.unitTone("unknown"));
        assertEquals(AgentRules.TONE_BAD, AgentRules.unitTone("failed"));
        assertEquals(AgentRules.TONE_OK, AgentRules.whatsappTone("connected"));
        assertEquals(AgentRules.TONE_BAD, AgentRules.whatsappTone("service_down"));
        assertEquals(AgentRules.TONE_WARN, AgentRules.whatsappTone("unlinked"));
        assertEquals(AgentRules.TONE_OK, AgentRules.telegramTone("running"));
        assertEquals(AgentRules.TONE_WARN, AgentRules.telegramTone("starting"));
        assertEquals(AgentRules.TONE_WARN, AgentRules.telegramTone("offline"));
        assertEquals(AgentRules.TONE_BAD, AgentRules.telegramTone("token_invalid"));
    }

    @Test
    public void durationLikeMinutes() {
        // agent_pages._minutes
        assertEquals("42 s", AgentRules.duration(42));
        assertEquals("3 min", AgentRules.duration(170));
        assertEquals("1 h 05 min", AgentRules.duration(3900));
    }

    @Test
    public void newerComparesNumbers() {
        assertTrue(AgentRules.newer("0.21.1", "0.21.0"));
        assertTrue(AgentRules.newer("0.22.0", "0.21.9"));
        assertTrue(AgentRules.newer("0.10.0", "0.9.0"));
        assertFalse(AgentRules.newer("0.21.0", "0.21.0"));
        assertFalse(AgentRules.newer("", "0.21.0"));
        assertFalse(AgentRules.newer(null, "0.21.0"));
        assertFalse(AgentRules.newer("0.20.0", "0.21.0"));
    }
}
