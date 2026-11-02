package com.iaaorgmx.paynani.roster;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CatchAllFormTest {

    @Test
    public void activoSinInstruccionesNoSeGuarda() {
        assertEquals(CatchAllForm.EMPTY, CatchAllForm.problem(true, ""));
        assertEquals(CatchAllForm.EMPTY, CatchAllForm.problem(true, "  \n\t "));
        assertEquals(CatchAllForm.OK, CatchAllForm.problem(true, "Avísale a Julián."));
    }

    @Test
    public void apagadoSinInstruccionesSiSeGuarda() {
        assertEquals(CatchAllForm.OK, CatchAllForm.problem(false, ""));
    }

    @Test
    public void elTopeEsEnBytesNoEnCaracteres() {
        StringBuilder ascii = new StringBuilder();
        while (ascii.length() < CatchAllForm.MAX_BYTES) ascii.append('a');
        assertEquals(CatchAllForm.OK, CatchAllForm.problem(true, ascii.toString()));
        assertEquals(CatchAllForm.TOO_LARGE, CatchAllForm.problem(true, ascii + "a"));
        StringBuilder accents = new StringBuilder();
        while (accents.length() < CatchAllForm.MAX_BYTES / 2 + 1) accents.append('á');
        assertEquals(CatchAllForm.TOO_LARGE, CatchAllForm.problem(false, accents.toString()));
    }

    @Test
    public void cambiosEnLaCasillaOElTexto() {
        assertFalse(CatchAllForm.changed(true, "x", true, "x"));
        assertTrue(CatchAllForm.changed(true, "x", false, "x"));
        assertTrue(CatchAllForm.changed(false, "x", false, "y"));
        assertFalse(CatchAllForm.changed(false, null, false, ""));
    }
}
