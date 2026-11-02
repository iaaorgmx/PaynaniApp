package com.iaaorgmx.paynani.whatsapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Instant;

public class WhatsAppRulesTest {

    private static final WhatsAppRules.Labels LABELS = new WhatsAppRules.Labels() {
        @Override
        public String hidden() {
            return "HIDDEN";
        }

        @Override
        public String media(String kind) {
            return "[" + kind + "]";
        }

        @Override
        public String outPrefix() {
            return "You: ";
        }
    };

    @Test
    public void bridgeStatesMapToWhatTheScreenShows() {
        assertEquals(WhatsAppRules.State.CONNECTED, WhatsAppRules.state("connected"));
        assertEquals(WhatsAppRules.State.RECONNECTING, WhatsAppRules.state("connecting"));
        assertEquals(WhatsAppRules.State.RECONNECTING, WhatsAppRules.state("disconnected"));
        assertEquals(WhatsAppRules.State.LINKING, WhatsAppRules.state("linking"));
        assertEquals(WhatsAppRules.State.NOT_LINKED, WhatsAppRules.state("unlinked"));
        assertEquals(WhatsAppRules.State.NOT_LINKED, WhatsAppRules.state("logged_out"));
        assertEquals(WhatsAppRules.State.SERVICE_DOWN, WhatsAppRules.state("service_down"));
        assertEquals(WhatsAppRules.State.NOT_LINKED, WhatsAppRules.state(null));
        assertEquals(WhatsAppRules.State.NOT_LINKED, WhatsAppRules.state("something_new"));
    }

    @Test
    public void withoutAdminThereAreNoLinkButtons() {
        // FR-6a: sin whatsapp.admin ni vincular, ni cancelar, ni desvincular, en ningún estado.
        for (WhatsAppRules.State s : WhatsAppRules.State.values()) {
            WhatsAppRules.Actions a = WhatsAppRules.actions(s, false);
            assertFalse(s.name(), a.requestCode);
            assertFalse(s.name(), a.cancelLink);
            assertFalse(s.name(), a.unlink);
        }
    }

    @Test
    public void adminButtonsFollowTheState() {
        WhatsAppRules.Actions notLinked = WhatsAppRules.actions(WhatsAppRules.State.NOT_LINKED, true);
        assertTrue(notLinked.requestCode);
        assertFalse(notLinked.cancelLink);
        assertFalse(notLinked.unlink);

        WhatsAppRules.Actions linking = WhatsAppRules.actions(WhatsAppRules.State.LINKING, true);
        assertTrue(linking.requestCode);
        assertTrue(linking.cancelLink);
        assertFalse(linking.unlink);

        WhatsAppRules.Actions connected = WhatsAppRules.actions(WhatsAppRules.State.CONNECTED, true);
        assertFalse(connected.requestCode);
        assertTrue(connected.unlink);

        WhatsAppRules.Actions down = WhatsAppRules.actions(WhatsAppRules.State.SERVICE_DOWN, true);
        assertFalse(down.requestCode);
        assertFalse(down.unlink);
    }

    @Test
    public void hiddenThreadShowsNothingOfTheMessage() {
        // FR-16a: aunque la respuesta trajera texto, el renglón oculto no lo muestra.
        WhatsAppSource.ThreadSummary t = new WhatsAppSource.ThreadSummary("+12105550199", null, false,
                "2026-10-28T18:40:11Z", "in", "secreto", "text", false, true);
        assertNull(t.lastExcerpt);
        assertNull(t.lastKind);
        assertEquals("HIDDEN", WhatsAppRules.preview(t, LABELS));
    }

    @Test
    public void previewShowsTextMediaAndOutPrefix() {
        assertEquals("Hola", WhatsAppRules.preview(summary("in", "Hola", "text"), LABELS));
        assertEquals("You: Sí, a las 8", WhatsAppRules.preview(summary("out", "Sí, a las 8", "text"), LABELS));
        assertEquals("[image]", WhatsAppRules.preview(summary("in", null, "image"), LABELS));
        assertEquals("", WhatsAppRules.preview(summary("in", null, null), LABELS));
    }

    @Test
    public void bubbleOfHiddenOrMediaMessage() {
        WhatsAppSource.Message hidden = new WhatsAppSource.Message("wa:1", "in", "", "2026-10-28T18:40:11Z",
                "text", "secreto", "", true);
        assertNull(hidden.text);
        assertEquals("HIDDEN", WhatsAppRules.bubble(hidden, LABELS));
        WhatsAppSource.Message photo = new WhatsAppSource.Message("wa:2", "in", "", "2026-10-28T18:40:11Z",
                "image", null, "", false);
        assertEquals("[image]", WhatsAppRules.bubble(photo, LABELS));
        WhatsAppSource.Message text = new WhatsAppSource.Message("wo_1", "out", "owner", "2026-10-28T18:40:11Z",
                "text", "Con gusto", "sent", false);
        assertEquals("Con gusto", WhatsAppRules.bubble(text, LABELS));
        assertTrue(text.out());
    }

    @Test
    public void countdownWithExpiresInSDoesNotDependOnClocks() {
        long received = 1_000_000;
        assertEquals(160, WhatsAppRules.remainingSeconds(160, received, received));
        assertEquals(100, WhatsAppRules.remainingSeconds(160, received, received + 60_000));
        assertEquals(1, WhatsAppRules.remainingSeconds(160, received, received + 159_500));
        assertEquals(0, WhatsAppRules.remainingSeconds(160, received, received + 160_000));
        assertEquals(-1, WhatsAppRules.remainingSeconds(-1, received, received));
    }

    @Test
    public void countdownWithExpiresAtUsesTheClockOffset() {
        long now = Instant.parse("2026-10-28T19:00:00Z").toEpochMilli();
        // El host va 30 s adelante del teléfono.
        assertEquals(150, WhatsAppRules.remainingSeconds("2026-10-28T19:03:00Z", now, 30_000));
        assertEquals(0, WhatsAppRules.remainingSeconds("2026-10-28T18:59:00Z", now, 0));
        assertEquals(0, WhatsAppRules.remainingSeconds("no es fecha", now, 0));
        assertEquals(0, WhatsAppRules.remainingSeconds("", now, 0));
    }

    @Test
    public void countdownFormat() {
        assertEquals("2:40", WhatsAppRules.countdown(160));
        assertEquals("0:05", WhatsAppRules.countdown(5));
        assertEquals("0:00", WhatsAppRules.countdown(-3));
    }

    @Test
    public void replyOnlyWithCanReplyAndSend() {
        // Q189=A: no hay mensaje nuevo; sólo se contesta donde el contacto ya escribió.
        assertTrue(WhatsAppRules.replyVisible(true, true));
        assertFalse(WhatsAppRules.replyVisible(false, true));
        assertFalse(WhatsAppRules.replyVisible(true, false));
    }

    @Test
    public void sendableText() {
        assertTrue(WhatsAppRules.sendable("Hola"));
        assertFalse(WhatsAppRules.sendable("   "));
        assertFalse(WhatsAppRules.sendable(null));
        assertTrue(WhatsAppRules.sendable(repeat(WhatsAppRules.MAX_TEXT)));
        assertFalse(WhatsAppRules.sendable(repeat(WhatsAppRules.MAX_TEXT + 1)));
    }

    @Test
    public void linkCodeToStringNeverShowsTheCode() {
        WhatsAppSource.LinkCode code = new WhatsAppSource.LinkCode("ABCD-EFGH", "2026-10-28T19:03:00Z", 160, 0);
        assertFalse(code.toString().contains("ABCD"));
    }

    private static WhatsAppSource.ThreadSummary summary(String dir, String excerpt, String kind) {
        return new WhatsAppSource.ThreadSummary("+12105550101", "Ana López", true, "2026-10-28T19:13:05Z", dir,
                excerpt, kind, true, false);
    }

    private static String repeat(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append('a');
        return b.toString();
    }
}
