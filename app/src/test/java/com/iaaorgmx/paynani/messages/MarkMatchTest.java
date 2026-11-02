package com.iaaorgmx.paynani.messages;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

/** Las burbujas de «Mensajes» que llevan la marca (paynani #489 FR-19, #491 FR-19). */
public class MarkMatchTest {

    private static final long MIN = 60_000L;

    @Test public void mismoTextoYHoraCercana() {
        Map<Long, String> m = MarkMatch.match(
                Arrays.asList(new MarkMatch.Sent(1, "Hola", 1000), new MarkMatch.Sent(2, "Otro", 1000)),
                Collections.singletonList(new MarkMatch.Mark("desde PaynaniWeb", "Hola", 900)));
        assertEquals("desde PaynaniWeb", m.get(1L));
        assertNull(m.get(2L));
    }

    @Test public void fueraDeLaVentanaNoSeMarca() {
        Map<Long, String> m = MarkMatch.match(
                Collections.singletonList(new MarkMatch.Sent(1, "Hola", 0)),
                Collections.singletonList(new MarkMatch.Mark("API", "Hola", 11 * MIN)));
        assertTrue(m.isEmpty());
    }

    @Test public void cadaOrdenMarcaUnaSolaBurbujaLaMasCercana() {
        // El agente mandó «Gracias» a las 0:00 y el dueño, desde la web, a las 0:05.
        Map<Long, String> m = MarkMatch.match(
                Arrays.asList(new MarkMatch.Sent(1, "Gracias", 0), new MarkMatch.Sent(2, "Gracias", 5 * MIN)),
                Collections.singletonList(new MarkMatch.Mark("desde PaynaniWeb", "Gracias", 5 * MIN - 2000)));
        assertEquals(1, m.size());
        assertEquals("desde PaynaniWeb", m.get(2L));
    }

    @Test public void dosOrdenesConElMismoTexto() {
        Map<Long, String> m = MarkMatch.match(
                Arrays.asList(new MarkMatch.Sent(1, "Listo", 0), new MarkMatch.Sent(2, "Listo", 3 * MIN)),
                Arrays.asList(new MarkMatch.Mark("API: citas", "Listo", 0), new MarkMatch.Mark("API: citas", "Listo", 3 * MIN)));
        assertEquals(2, m.size());
    }
}
