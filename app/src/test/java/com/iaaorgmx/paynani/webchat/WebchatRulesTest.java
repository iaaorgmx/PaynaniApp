package com.iaaorgmx.paynani.webchat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** paynani PRD #653 y WEBCHAT.md §3: lo que la pantalla de Webchat muestra y deja mandar. */
public class WebchatRulesTest {

    private static final ZoneId CDMX = ZoneId.of("America/Mexico_City");

    private static WebchatSource.Message m(String id, String dir, String at, String text) {
        return new WebchatSource.Message(id, dir, at, text, null);
    }

    @Test
    public void onlyTheStoreTypesAreAccepted() {
        assertEquals("image/jpeg", WebchatRules.mime("Foto.JPG"));
        assertEquals("application/pdf", WebchatRules.mime("factura.pdf"));
        assertNull(WebchatRules.mime("virus.exe"));
        assertNull(WebchatRules.mime("sin_extension"));
        assertNull(WebchatRules.mime("audio.ogg"));          // audio queda fuera de esta versión
        assertTrue(WebchatRules.isImage("a.webp"));
        assertFalse(WebchatRules.isImage("a.pdf"));
        assertEquals(WebchatRules.BAD_TYPE, WebchatRules.fileProblem("x.exe", 10));
        assertEquals(WebchatRules.TOO_BIG, WebchatRules.fileProblem("x.png", WebchatRules.MAX_FILE + 1));
        assertNull(WebchatRules.fileProblem("x.png", WebchatRules.MAX_FILE));
    }

    @Test
    public void aFileNameOrRefNeverBuildsAPathOutsideTheCache() {
        // Iris en #174: la extensión sólo puede ser uno de los tipos; si no, bin.
        assertTrue(WebchatRules.extension("a.x/../../y").contains("/"));   // el texto crudo trae diagonales
        assertEquals("bin", WebchatRules.safeExtension("a.x/../../y"));
        assertEquals("bin", WebchatRules.safeExtension("sin_extension"));
        assertEquals("pdf", WebchatRules.safeExtension("Factura.PDF"));
        assertTrue(WebchatRules.validRef("f_0123456789abcdef0123456789abcdef"));
        assertFalse(WebchatRules.validRef("f_0123456789ABCDEF0123456789abcdef"));
        assertFalse(WebchatRules.validRef("../f_0123456789abcdef0123456789abcdef"));
        assertFalse(WebchatRules.validRef("f_demo_photo"));
        assertFalse(WebchatRules.validRef(null));
    }

    @Test
    public void lengthAndBlanksLikeTheStore() {
        // Un emoji es un carácter, como en Python, aunque en Java sean dos unidades UTF-16.
        assertNull(WebchatRules.sendProblem(repeat(WebchatRules.MAX_TEXT - 1) + "😀", null));
        assertEquals(WebchatRules.TOO_LONG, WebchatRules.sendProblem(repeat(WebchatRules.MAX_TEXT) + "😀", null));
        // Sólo espacios de Unicode (como el de no separación) también es vacío.
        assertEquals(WebchatRules.EMPTY, WebchatRules.sendProblem("\u00a0\u2003 \n", null));
        assertTrue(WebchatRules.blank("\u00a0"));
        assertFalse(WebchatRules.blank(" a "));
    }

    @Test
    public void whatCanBeSent() {
        assertEquals(WebchatRules.EMPTY, WebchatRules.sendProblem("  ", null));
        assertNull(WebchatRules.sendProblem("", Collections.singletonList(10L)));   // sólo una foto
        assertNull(WebchatRules.sendProblem("Hola", null));
        assertEquals(WebchatRules.TOO_LONG, WebchatRules.sendProblem(repeat(WebchatRules.MAX_TEXT + 1), null));
        assertNull(WebchatRules.sendProblem(repeat(WebchatRules.MAX_TEXT), null));
        assertEquals(WebchatRules.TOO_BIG, WebchatRules.sendProblem("", Arrays.asList(WebchatRules.MAX_FILE,
                WebchatRules.MAX_FILE)));
        // §13: hasta 10 uploads por mensaje.
        Long[] eleven = new Long[WebchatRules.MAX_FILES + 1];
        Arrays.fill(eleven, 10L);
        assertEquals(WebchatRules.TOO_MANY, WebchatRules.sendProblem("", Arrays.asList(eleven)));
        assertNull(WebchatRules.sendProblem("", Arrays.asList(Arrays.copyOf(eleven, WebchatRules.MAX_FILES))));
    }

    @Test
    public void messagesAreShownOldestFirstWhateverTheOrderTheyCome() {
        List<WebchatSource.Message> newestFirst = Arrays.asList(
                m("w_3", "out", "2026-11-02T10:02:00Z", "c"),
                m("w_2", "in", "2026-11-02T10:01:00Z", "b"),
                m("w_1", "in", "2026-11-02T10:00:00Z", "a"));
        List<WebchatSource.Message> shown = WebchatRules.chronological(newestFirst);
        assertEquals("w_1", shown.get(0).id);
        assertEquals("w_3", shown.get(2).id);
        assertEquals("w_3", WebchatRules.lastId(shown));
        assertNull(WebchatRules.lastId(Collections.emptyList()));
    }

    @Test
    public void mergeDoesNotRepeatAMessage() {
        List<WebchatSource.Message> shown = Arrays.asList(m("w_1", "in", "2026-11-02T10:00:00Z", "a"),
                m("w_2", "out", "2026-11-02T10:01:00Z", "b"));
        // El send regresa el mensaje y después llega otra vez por after.
        List<WebchatSource.Message> merged = WebchatRules.merge(shown, Arrays.asList(
                m("w_2", "out", "2026-11-02T10:01:00Z", "b"), m("w_3", "in", "2026-11-02T10:02:00Z", "c")));
        assertEquals(3, merged.size());
        assertEquals("w_3", merged.get(2).id);
    }

    @Test
    public void aDaySeparatorWhenTheLocalDayChanges() {
        // 23:30 y 00:10 hora de la Ciudad de México: dos días aunque en UTC sea el mismo.
        List<WebchatSource.Message> list = Arrays.asList(
                m("w_1", "in", "2026-11-02T05:30:00Z", "a"),   // 1 nov 23:30
                m("w_2", "out", "2026-11-02T05:50:00Z", "b"),  // 1 nov 23:50
                m("w_3", "in", "2026-11-02T06:10:00Z", "c"));  // 2 nov 00:10
        assertTrue(WebchatRules.newDay(list, 0, CDMX));
        assertFalse(WebchatRules.newDay(list, 1, CDMX));
        assertTrue(WebchatRules.newDay(list, 2, CDMX));
    }

    @Test
    public void notifyOnlyAgentRepliesAfterWhatWasSeen() {
        List<WebchatSource.Message> list = Arrays.asList(
                m("w_1", "in", "2026-11-02T10:00:00Z", "¿Cómo vamos?"),
                m("w_2", "out", "2026-11-02T10:01:00Z", "Ya casi"),
                m("w_3", "in", "2026-11-02T10:02:00Z", "Gracias"),
                m("w_4", "out", "2026-11-02T10:03:00Z", "De nada"));
        assertEquals(Collections.singletonList("w_4"), ids(WebchatRules.unseenFromAgent(list, "w_3")));
        assertEquals(Arrays.asList("w_2", "w_4"), ids(WebchatRules.unseenFromAgent(list, "w_1")));
        assertTrue(WebchatRules.unseenFromAgent(list, "w_4").isEmpty());
        // Sin nada visto todavía no se avisa de lo viejo.
        assertTrue(WebchatRules.unseenFromAgent(list, null).isEmpty());
    }

    @Test
    public void previewIsTheTextOrTheFileNames() {
        WebchatSource.Message withFiles = new WebchatSource.Message("w_1", "out", "", "", Arrays.asList(
                new WebchatSource.FileRef(0, "factura.pdf", "document", 10, "f_1"),
                new WebchatSource.FileRef(1, "foto.jpg", "image", 10, "f_2")));
        assertEquals("factura.pdf, foto.jpg", WebchatRules.preview(withFiles));
        assertEquals("Hola", WebchatRules.preview(m("w_2", "out", "", "Hola")));
        assertTrue(withFiles.files.get(1).image());
        assertFalse(withFiles.mine());
    }

    private static List<String> ids(List<WebchatSource.Message> list) {
        String[] out = new String[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i).id;
        return Arrays.asList(out);
    }

    private static String repeat(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append('a');
        return b.toString();
    }
}
