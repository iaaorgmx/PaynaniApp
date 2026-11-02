package com.iaaorgmx.paynani.roster;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** PRD #409, I-2 e I-6: la cabecera de un archivo de reglas, separada del cuerpo y vuelta a armar. */
public class RulesTextTest {

    private static final String FILE = "---\n"
            + "version: 1\n"
            + "enabled: true\n"
            + "reply:\n"
            + "  email: normal\n"
            + "  sms: never\n"
            + "updated_at: 2026-10-20T07:00:00Z\n"
            + "updated_by: phone d_fb89b8\n"
            + "---\n"
            + "\n"
            + "## General\n"
            + "Solo dispara el flujo de pagos.\n";

    @Test
    public void theHeaderIsReadAndTheBodyKept() {
        RulesText r = RulesText.parse(FILE);
        assertTrue(r.enabled);
        assertEquals("normal", r.replyEmail);
        assertEquals("never", r.replySms);
        assertEquals("## General\nSolo dispara el flujo de pagos.\n", r.body);
    }

    @Test
    public void composeWritesWhatTheGatewayAcceptsWithoutUpdatedFields() {
        String out = RulesText.parse(FILE).compose();
        assertTrue(out.startsWith("---\nversion: 1\nenabled: true\nreply:\n  email: normal\n  sms: never\n---\n\n"));
        assertTrue(out.endsWith("## General\nSolo dispara el flujo de pagos.\n"));
        assertFalse(out.contains("updated_at"));
        assertFalse(out.contains("updated_by"));
    }

    @Test
    public void parseAndComposeAreStable() {
        String once = RulesText.parse(FILE).compose();
        assertEquals(once, RulesText.parse(once).compose());
    }

    @Test
    public void disabledAndQuotedValuesAreRead() {
        RulesText r = RulesText.parse("---\nversion: 1\nenabled: false\nreply:\n  email: \"never\"\n  sms: 'normal' # comentario\n---\nTexto\n");
        assertFalse(r.enabled);
        assertEquals("never", r.replyEmail);
        assertEquals("normal", r.replySms);
        assertEquals("Texto\n", r.body);
    }

    @Test
    public void withoutHeaderEverythingIsBody() {
        RulesText r = RulesText.parse("## General\nhola\n");
        assertTrue(r.enabled);
        assertEquals("normal", r.replyEmail);
        assertEquals("## General\nhola\n", r.body);
    }

    @Test
    public void anEmptyFileIsANewOneWithoutSections() {
        // Un «## General» vacío sustituiría a la general con nada (I-4b).
        RulesText r = RulesText.parse("");
        assertTrue(r.enabled);
        assertEquals("", r.body);
    }

    @Test
    public void unknownReplyValuesBecomeNormal() {
        assertEquals("normal", new RulesText(true, "a veces", "x", "").replyEmail);
    }

    @Test
    public void theLimitIs32KbOfUtf8() {
        StringBuilder body = new StringBuilder();
        while (new RulesText(true, "normal", "normal", body.toString()).bytes() < RulesText.MAX_BYTES) body.append('a');
        assertFalse(new RulesText(true, "normal", "normal", body.toString()).tooLarge());
        body.append('ñ'); // 2 bytes en UTF-8
        assertTrue(new RulesText(true, "normal", "normal", body.toString()).tooLarge());
    }
}
