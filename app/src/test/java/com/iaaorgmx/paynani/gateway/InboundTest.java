package com.iaaorgmx.paynani.gateway;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** El sufijo «*\/=XXXXXX» que traen algunos SMS de un número que usó RCS (#76). */
public class InboundTest {

    @Test
    public void stripsSuffixAtTheEnd() {
        assertEquals("RCS46 sms 10:33 PM", Inbound.stripRcsSuffix("RCS46 sms 10:33 PM*/=GV939w"));
        assertEquals("Prueba #2 con PaynaniApp", Inbound.stripRcsSuffix("Prueba #2 con PaynaniApp*/=0QcMMQ"));
    }

    @Test
    public void leavesSuffixInTheMiddle() {
        assertEquals("a */=0QcMMQ b", Inbound.stripRcsSuffix("a */=0QcMMQ b"));
    }

    @Test
    public void leavesOtherLengths() {
        assertEquals("hola*/=GV939", Inbound.stripRcsSuffix("hola*/=GV939"));
        assertEquals("hola*/=GV939wX", Inbound.stripRcsSuffix("hola*/=GV939wX"));
    }

    @Test
    public void neverLeavesAnEmptySms() {
        assertEquals("*/=GV939w", Inbound.stripRcsSuffix("*/=GV939w"));
    }

    @Test
    public void trailingNewlineIsNotTheEnd() {
        assertEquals("hola*/=GV939w\n", Inbound.stripRcsSuffix("hola*/=GV939w\n"));
    }

    @Test
    public void nullStaysNull() {
        assertNull(Inbound.stripRcsSuffix(null));
    }
}
