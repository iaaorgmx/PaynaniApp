package com.iaaorgmx.paynani.webchat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** paynani #745: citas y reacciones en Webchat (FR-9 a FR-13). */
public class WebchatRepliesTest {

    private static WebchatSource.Message m(String id, String text, List<WebchatSource.Reaction> reactions) {
        return new WebchatSource.Message(id, "out", "2026-11-07T19:00:00Z", text, null, null, reactions);
    }

    @Test
    public void theRowIsTheSameAsPaynaniWeb() {
        // web/msgmenu.py ROW, en el mismo orden.
        assertEquals(Arrays.asList("👍", "👌", "❤️", "👀", "🔥", "😭", "👎"), WebchatRules.QUICK_REACTIONS);
    }

    @Test
    public void oneReactionPerMessage() {
        // FR-9: elegir otra la reemplaza, la misma la quita, y sin reacción se pone.
        assertEquals("🔥", WebchatRules.reactionChoice("👍", "🔥"));
        assertEquals("", WebchatRules.reactionChoice("👍", "👍"));
        assertEquals("👍", WebchatRules.reactionChoice("", "👍"));
        assertEquals("", WebchatRules.reactionChoice("👍", ""));
        assertEquals("", WebchatRules.reactionChoice(null, null));
    }

    @Test
    public void theOwnersReactionIsTheOneMarkedMine() {
        WebchatSource.Message none = m("w_1", "hola", null);
        assertEquals("", none.myReaction());
        assertTrue(none.reactions.isEmpty());
        WebchatSource.Message some = m("w_1", "hola", Arrays.asList(
                new WebchatSource.Reaction("👍", 2, false), new WebchatSource.Reaction("🔥", 1, true)));
        assertEquals("🔥", some.myReaction());
    }

    @Test
    public void withReactionsKeepsTheRestOfTheMessage() {
        WebchatSource.ReplyTo q = new WebchatSource.ReplyTo("w_0", "Andy", "antes", false);
        WebchatSource.Message original = new WebchatSource.Message("w_1", "in", "2026-11-07T19:00:00Z", "hola",
                null, q, null);
        WebchatSource.Message updated = original.withReactions(
                Collections.singletonList(new WebchatSource.Reaction("👀", 1, true)));
        assertEquals("w_1", updated.id);
        assertEquals("hola", updated.text);
        assertTrue(updated.mine());
        assertEquals(q, updated.replyTo);
        assertEquals("👀", updated.myReaction());
    }

    @Test
    public void aQuoteWithoutRefIsMissing() {
        // FR-12: una cita missing (o sin ref) no lleva a ningún lado.
        assertTrue(new WebchatSource.ReplyTo(null, "", "", false).missing);
        assertTrue(new WebchatSource.ReplyTo("w_0", "Andy", "x", true).missing);
        assertFalse(new WebchatSource.ReplyTo("w_0", "Andy", "x", false).missing);
    }

    @Test
    public void aReactionCountsAtLeastOne() {
        assertEquals(1, new WebchatSource.Reaction("👍", 0, false).count);
        assertEquals(3, new WebchatSource.Reaction("👍", 3, false).count);
    }

    @Test
    public void onlyOneEmojiGoesToReact() {
        assertTrue(WebchatRules.singleEmoji("👍"));
        assertTrue(WebchatRules.singleEmoji("❤️"));             // con selector de variación
        assertTrue(WebchatRules.singleEmoji("👍🏽"));            // con tono de piel
        assertTrue(WebchatRules.singleEmoji("👨‍👩‍👧"));           // con ZWJ
        assertTrue(WebchatRules.singleEmoji("🇲🇽"));             // bandera
        assertTrue(WebchatRules.singleEmoji("1️⃣"));             // keycap
        assertTrue(WebchatRules.singleEmoji(" 🔥 "));
        assertFalse(WebchatRules.singleEmoji("👍👍"));
        assertFalse(WebchatRules.singleEmoji("a"));
        assertFalse(WebchatRules.singleEmoji("1"));
        assertFalse(WebchatRules.singleEmoji("ok"));
        assertFalse(WebchatRules.singleEmoji("!"));
        assertFalse(WebchatRules.singleEmoji(""));
        assertFalse(WebchatRules.singleEmoji("   "));
        assertFalse(WebchatRules.singleEmoji(null));
    }

    @Test
    public void theQuotedMessageIsFoundById() {
        List<WebchatSource.Message> list = Arrays.asList(m("w_1", "a", null), m("w_2", "b", null));
        assertEquals(1, WebchatRules.indexOf(list, "w_2"));
        assertEquals(-1, WebchatRules.indexOf(list, "w_9"));
        assertEquals(-1, WebchatRules.indexOf(list, ""));
        assertEquals(-1, WebchatRules.indexOf(null, "w_1"));
    }

    @Test
    public void theReplyBarShowsOneLine() {
        // FR-11: la primera línea del texto o, sin texto, el primer archivo, como el almacén.
        assertEquals("Primera línea", WebchatRules.excerpt(m("w_1", "Primera línea\nsegunda", null)));
        WebchatSource.Message photo = new WebchatSource.Message("w_2", "in", "2026-11-07T19:00:00Z", "",
                Collections.singletonList(new WebchatSource.FileRef(0, "ticket.png", "image", 70, "f_x")));
        assertEquals("[ticket.png]", WebchatRules.excerpt(photo));
        assertEquals("", WebchatRules.excerpt(null));
    }
}
