package com.iaaorgmx.paynani.whatsapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/** Revisión de PR #150: el código tras pausar (FR-6a), números fuera del roster (FR-16a) y sin conexión. */
public class WhatsAppGuardsTest {

    private static final WhatsAppSource.LinkCode CODE =
            new WhatsAppSource.LinkCode("ABCD-EFGH", "2026-10-28T19:03:00Z", 160, 0);

    // --- 1. El código no vuelve a memoria después de salir de la pantalla -------------------------

    @Test
    public void linkResponseAfterPauseIsDropped() {
        CodeSlot slot = new CodeSlot();
        slot.open();
        int t = slot.request();
        assertTrue(slot.busy());
        slot.close(); // onPause antes de que llegue la respuesta
        assertFalse(slot.busy());
        assertFalse(slot.deliver(t, CODE));
        assertNull(slot.code());
        assertFalse(slot.busy());
    }

    @Test
    public void linkResponseAfterPauseAndResumeIsDroppedToo() {
        CodeSlot slot = new CodeSlot();
        slot.open();
        int t = slot.request();
        slot.close();
        slot.open(); // vuelve a la pantalla: la respuesta vieja tampoco sirve
        assertFalse(slot.deliver(t, CODE));
        assertNull(slot.code());
        assertFalse(slot.busy());
    }

    @Test
    public void linkErrorAfterPauseDoesNothing() {
        CodeSlot slot = new CodeSlot();
        slot.open();
        int t = slot.request();
        slot.close();
        assertFalse(slot.fail(t));
        assertFalse(slot.busy());
    }

    @Test
    public void linkResponseWhileVisibleIsShown() {
        CodeSlot slot = new CodeSlot();
        slot.open();
        int t = slot.request();
        assertTrue(slot.deliver(t, CODE));
        assertSame(CODE, slot.code());
        assertFalse(slot.busy());
        slot.close();
        assertNull(slot.code());
    }

    @Test
    public void onlyTheLastLinkRequestCounts() {
        CodeSlot slot = new CodeSlot();
        slot.open();
        int first = slot.request();
        int second = slot.request();
        assertFalse(slot.deliver(first, CODE));
        assertTrue(slot.busy());
        assertTrue(slot.deliver(second, CODE));
    }

    // --- 2. Fuera del roster sólo número y hora, aunque la respuesta traiga más -------------------

    @Test
    public void hiddenSummaryDropsNameTextMediaAndReply() {
        WhatsAppSource.ThreadSummary t = new WhatsAppSource.ThreadSummary("+12105550199", "Nombre colado", true,
                "2026-10-28T18:40:11Z", "in", "texto colado", "image", true, true);
        assertNull(t.name);
        assertNull(t.lastExcerpt);
        assertNull(t.lastKind);
        assertFalse(t.canReply);
        assertFalse(t.inRoster);
        assertTrue(t.hidden);
        assertEquals("+12105550199", t.number);
        assertEquals("2026-10-28T18:40:11Z", t.lastAt);
    }

    @Test
    public void offRosterSummaryIsHiddenEvenWithoutTheFlag() {
        WhatsAppSource.ThreadSummary t = new WhatsAppSource.ThreadSummary("+12105550199", "Nombre colado", false,
                "2026-10-28T18:40:11Z", "in", "texto colado", "text", true, false);
        assertNull(t.name);
        assertNull(t.lastExcerpt);
        assertNull(t.lastKind);
        assertFalse(t.canReply);
        assertTrue(t.hidden);
    }

    @Test
    public void offRosterThreadHidesEverythingAndCannotReply() {
        WhatsAppSource.Message leaked = new WhatsAppSource.Message("wa:1", "in", "", "2026-10-28T18:40:11Z",
                "image", "texto colado", "", false);
        WhatsAppSource.Thread t = new WhatsAppSource.Thread("+12105550199", "Nombre colado", false, true,
                new WhatsAppSource.Page<>(Collections.singletonList(leaked), "cursor"));
        assertNull(t.name);
        assertFalse(t.canReply);
        assertEquals(1, t.messages.items.size());
        WhatsAppSource.Message m = t.messages.items.get(0);
        assertTrue(m.hidden);
        assertNull(m.text);
        assertEquals("", m.kind);
        assertEquals("2026-10-28T18:40:11Z", m.at);
        assertEquals("cursor", t.messages.nextBefore);
        assertFalse(WhatsAppRules.replyVisible(t.canReply, true));
    }

    @Test
    public void rosterThreadKeepsItsData() {
        WhatsAppSource.Message m = new WhatsAppSource.Message("wa:2", "in", "", "2026-10-28T18:40:11Z",
                "text", "Hola", "", false);
        WhatsAppSource.Thread t = new WhatsAppSource.Thread("+12105550101", "Ana López", true, true,
                new WhatsAppSource.Page<>(Collections.singletonList(m), null));
        assertEquals("Ana López", t.name);
        assertTrue(t.canReply);
        assertEquals("Hola", t.messages.items.get(0).text);
    }

    // --- 3. Sin conexión no queda nada visible ni vuelve por una respuesta tardía -----------------

    @Test
    public void lateThreadsResponseAfterOfflineDoesNotRefill() {
        PagedList<String> threads = new PagedList<>(false);
        threads.open();
        int t = threads.reload();
        threads.clear(); // se perdió la conexión
        assertFalse(threads.first(t, page("cursor", "b", "a")));
        assertTrue(threads.items().isEmpty());
        assertNull(threads.nextBefore());
    }

    @Test
    public void lateNextPageAfterOfflineDoesNotRefill() {
        PagedList<String> threads = new PagedList<>(false);
        threads.open();
        int t = threads.reload();
        assertTrue(threads.first(t, page("cursor", "b", "a")));
        int more = threads.more();
        assertTrue(more >= 0);
        threads.clear();
        assertEquals(-1, threads.append(more, page(null, "z")));
        assertTrue(threads.items().isEmpty());
        assertEquals(-1, threads.more()); // sin cursor no se pide más
    }

    @Test
    public void threadIsClearedOnOffline() {
        PagedList<String> messages = new PagedList<>(true);
        messages.open();
        int t = messages.reload();
        assertTrue(messages.first(t, page("cursor", "m3", "m2")));
        assertEquals(Arrays.asList("m2", "m3"), messages.items());
        messages.clear();
        assertTrue(messages.items().isEmpty());
        assertNull(messages.nextBefore());
        assertFalse(messages.accepts(t));
    }

    @Test
    public void lateResponseAfterPauseIsIgnored() {
        PagedList<String> messages = new PagedList<>(true);
        messages.open();
        int t = messages.reload();
        messages.close();
        assertFalse(messages.first(t, page(null, "m1")));
        messages.open();
        assertFalse(messages.first(t, page(null, "m1")));
        assertTrue(messages.items().isEmpty());
    }

    @Test
    public void olderMessagesGoOnTopInOrder() {
        PagedList<String> messages = new PagedList<>(true);
        messages.open();
        int t = messages.reload();
        messages.first(t, page("c1", "m4", "m3"));
        int more = messages.more();
        assertEquals(-1, messages.more()); // ya se está pidiendo
        assertEquals(2, messages.append(more, page(null, "m2", "m1")));
        assertEquals(Arrays.asList("m1", "m2", "m3", "m4"), messages.items());
        assertEquals(-1, messages.more());
    }

    @Test
    public void reloadSupersedesAPendingPage() {
        PagedList<String> threads = new PagedList<>(false);
        threads.open();
        threads.first(threads.reload(), page("c1", "b", "a"));
        int more = threads.more();
        int again = threads.reload();
        assertEquals(-1, threads.append(more, page(null, "z")));
        assertTrue(threads.first(again, page(null, "c")));
        assertEquals(Collections.singletonList("c"), threads.items());
    }

    @Test
    public void failedPageCanBeRequestedAgain() {
        PagedList<String> threads = new PagedList<>(false);
        threads.open();
        threads.first(threads.reload(), page("c1", "a"));
        int more = threads.more();
        threads.moreFailed(more);
        assertTrue(threads.more() >= 0);
    }

    private static WhatsAppSource.Page<String> page(String nextBefore, String... newestFirst) {
        return new WhatsAppSource.Page<>(Arrays.asList(newestFirst), nextBefore);
    }
}
