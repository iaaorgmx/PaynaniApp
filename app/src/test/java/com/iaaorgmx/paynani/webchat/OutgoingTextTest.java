package com.iaaorgmx.paynani.webchat;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class OutgoingTextTest {

    @Test
    public void onlyTheEndIsTrimmed() {
        assertEquals("Claro, a las 8 hay lugar", OutgoingText.trimEnd("Claro, a las 8 hay lugar\n"));
        assertEquals("Hola", OutgoingText.trimEnd("Hola \t\r\n\n  "));
        assertEquals("  Hola\n\nAdiós", OutgoingText.trimEnd("  Hola\n\nAdiós\n"));
        assertEquals("Hola", OutgoingText.trimEnd("Hola"));
        assertEquals("", OutgoingText.trimEnd("  \n "));
        assertEquals("", OutgoingText.trimEnd(""));
        assertEquals("", OutgoingText.trimEnd(null));
    }
}
