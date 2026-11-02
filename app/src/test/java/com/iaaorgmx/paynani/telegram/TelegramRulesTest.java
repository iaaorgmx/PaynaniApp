package com.iaaorgmx.paynani.telegram;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.iaaorgmx.paynani.whatsapp.PagedList;
import com.iaaorgmx.paynani.whatsapp.WhatsAppSource;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/** paynani PRD #609 FR-29a, TELEGRAM.md §4 y §6: lo que las pantallas de Telegram muestran. */
public class TelegramRulesTest {

    private static final TelegramRules.Labels LABELS = new TelegramRules.Labels() {
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

        @Override
        public String edited() {
            return " (edited)";
        }
    };

    private static final String T = "t_0123456789abcdef0123456789abcdef";

    @Test
    public void botStatesMapToWhatTheListShows() {
        assertEquals(TelegramRules.State.RUNNING, TelegramRules.state("running"));
        assertEquals(TelegramRules.State.RECONNECTING, TelegramRules.state("starting"));
        assertEquals(TelegramRules.State.RECONNECTING, TelegramRules.state("offline"));
        assertEquals(TelegramRules.State.CONFLICT, TelegramRules.state("conflict"));
        assertEquals(TelegramRules.State.TOKEN_INVALID, TelegramRules.state("token_invalid"));
        assertEquals(TelegramRules.State.SERVICE_DOWN, TelegramRules.state("stopped"));
        assertEquals(TelegramRules.State.SERVICE_DOWN, TelegramRules.state("journal_mismatch"));
        assertEquals(TelegramRules.State.SERVICE_DOWN, TelegramRules.state("service_down"));
        assertEquals(TelegramRules.State.UNKNOWN, TelegramRules.state("algo_nuevo"));
        assertEquals(TelegramRules.State.UNKNOWN, TelegramRules.state(null));
    }

    @Test
    public void onlyARunningBotHasNoWarning() {
        for (TelegramRules.State s : TelegramRules.State.values()) {
            assertEquals(s.name(), s != TelegramRules.State.RUNNING, TelegramRules.showsWarning(s));
        }
    }

    @Test
    public void hiddenSummaryDropsNameTextMediaAndReply() {
        TelegramSource.ThreadSummary t = new TelegramSource.ThreadSummary(T, "Nombre colado", true,
                "2026-11-02T18:40:11Z", "in", "texto colado", "image", true, true);
        assertNull(t.name);
        assertNull(t.lastExcerpt);
        assertNull(t.lastKind);
        assertFalse(t.canReply);
        assertFalse(t.inRoster);
        assertEquals("HIDDEN", TelegramRules.preview(t, LABELS));
        assertEquals("2026-11-02T18:40:11Z", t.lastAt);
    }

    @Test
    public void outsideTheRosterWithCatchAllIsVisibleButCannotBeAnswered() {
        // Con el Catch-All de Telegram activo, alguien fuera del roster se ve, pero nunca se contesta (FR-25).
        TelegramSource.ThreadSummary t = new TelegramSource.ThreadSummary(T, "Pedro (no verificado)", false,
                "2026-11-02T18:40:11Z", "in", "Hola", "text", true, false);
        assertEquals("Pedro (no verificado)", t.name);
        assertEquals("Hola", TelegramRules.preview(t, LABELS));
        assertFalse(t.canReply);
    }

    @Test
    public void previewShowsTextMediaAndOutPrefix() {
        assertEquals("Hola", TelegramRules.preview(summary("in", "Hola", "text"), LABELS));
        assertEquals("You: Sí, a las 8", TelegramRules.preview(summary("out", "Sí, a las 8", "text"), LABELS));
        assertEquals("[image]", TelegramRules.preview(summary("in", null, "image"), LABELS));
        assertEquals("[image] El ticket", TelegramRules.preview(summary("in", "El ticket", "image"), LABELS));
        assertEquals("", TelegramRules.preview(summary("in", null, null), LABELS));
    }

    @Test
    public void bubbleWithCaptionEditedOrHidden() {
        assertEquals("[image] El ticket", TelegramRules.bubble(msg("in", "image", "El ticket", false, false), LABELS));
        assertEquals("[voice]", TelegramRules.bubble(msg("in", "voice", null, false, false), LABELS));
        assertEquals("¿A las 9? (edited)", TelegramRules.bubble(msg("in", "text", "¿A las 9?", true, false), LABELS));
        TelegramSource.Message hidden = msg("in", "text", "secreto", true, true);
        assertNull(hidden.text);
        assertFalse(hidden.edited);
        assertEquals("HIDDEN", TelegramRules.bubble(hidden, LABELS));
    }

    @Test
    public void hiddenThreadHidesEveryMessageAndCannotReply() {
        TelegramSource.Message leaked = msg("in", "image", "texto colado", false, false);
        TelegramSource.Thread th = new TelegramSource.Thread(T, "Nombre colado", false, true, true,
                new WhatsAppSource.Page<>(Collections.singletonList(leaked), "cursor"));
        assertNull(th.name);
        assertFalse(th.canReply);
        TelegramSource.Message m = th.messages.items.get(0);
        assertTrue(m.hidden);
        assertNull(m.text);
        assertEquals("", m.kind);
        assertEquals("cursor", th.messages.nextBefore);
        assertFalse(TelegramRules.replyVisible(th.canReply, true));
    }

    @Test
    public void rosterThreadKeepsItsDataAndCanReply() {
        TelegramSource.Thread th = new TelegramSource.Thread(T, "Ana López", true, true, false,
                new WhatsAppSource.Page<>(Collections.singletonList(msg("in", "text", "Hola", false, false)), null));
        assertEquals("Ana López", th.name);
        assertTrue(th.canReply);
        assertEquals("Hola", th.messages.items.get(0).text);
    }

    @Test
    public void replyOnlyWithCanReplyAndSend() {
        assertTrue(TelegramRules.replyVisible(true, true));
        assertFalse(TelegramRules.replyVisible(false, true));
        assertFalse(TelegramRules.replyVisible(true, false));
    }

    @Test
    public void ownerIsThePhoneOrTheWeb() {
        assertTrue(TelegramRules.byOwner("phone:d_c05073"));
        assertTrue(TelegramRules.byOwner("web"));
        assertTrue(TelegramRules.byOwner("owner"));
        assertFalse(TelegramRules.byOwner("agent"));
        assertFalse(TelegramRules.byOwner("terminal"));
        assertFalse(TelegramRules.byOwner("api"));
        assertFalse(TelegramRules.byOwner(null));
    }

    @Test
    public void sendableUpToOneTelegramMessage() {
        assertTrue(TelegramRules.sendable("Hola"));
        assertFalse(TelegramRules.sendable("   "));
        assertFalse(TelegramRules.sendable(null));
        assertTrue(TelegramRules.sendable(repeat(TelegramRules.MAX_TEXT)));
        assertFalse(TelegramRules.sendable(repeat(TelegramRules.MAX_TEXT + 1)));
        assertEquals(4096, TelegramRules.MAX_TEXT);
    }

    @Test
    public void olderPagesGoOnTopLikeWhatsApp() {
        // telegram.thread va del más reciente al más viejo, como whatsapp.thread (TELEGRAM.md §4).
        PagedList<String> messages = new PagedList<>(true);
        messages.open();
        int t = messages.reload();
        messages.first(t, new WhatsAppSource.Page<>(Arrays.asList("m4", "m3"), "c1"));
        int more = messages.more();
        messages.append(more, new WhatsAppSource.Page<>(Arrays.asList("m2", "m1"), null));
        assertEquals(Arrays.asList("m1", "m2", "m3", "m4"), messages.items());
    }

    @Test
    public void parseIso() {
        assertEquals(0, TelegramRules.parseIso(""));
        assertEquals(0, TelegramRules.parseIso("no es fecha"));
        assertTrue(TelegramRules.parseIso("2026-11-02T19:13:05Z") > 0);
    }

    private static TelegramSource.ThreadSummary summary(String dir, String excerpt, String kind) {
        return new TelegramSource.ThreadSummary(T, "Ana López", true, "2026-11-02T19:13:05Z", dir, excerpt, kind,
                true, false);
    }

    private static TelegramSource.Message msg(String dir, String kind, String text, boolean edited, boolean hidden) {
        return new TelegramSource.Message("tg:1", dir, "", "2026-11-02T19:10:01Z", kind, text, "", edited, hidden);
    }

    private static String repeat(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append('a');
        return b.toString();
    }
}
