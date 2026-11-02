package com.iaaorgmx.paynani;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** PaynaniApp #151: el ciclo de una llamada entrante, de RINGING a IDLE. */
public class CallCycleTest {

    private static CallCycle ringing(int managedFirst) {
        CallCycle c = new CallCycle(CallOrigin.MANAGED_UNKNOWN, "");
        c.start();
        c.observe(null, managedFirst, true);
        return c;
    }

    @Test
    public void selfManagedWithoutNumberIsDropped() {
        // WhatsApp en el S21+: las dos difusiones de RINGING sin número, isInManagedCall() false.
        CallCycle c = ringing(CallOrigin.MANAGED_NO);
        c.observe(null, CallOrigin.MANAGED_NO, false);
        assertEquals(CallOrigin.Decision.DROP, c.end());
    }

    @Test
    public void managedWithoutNumberIsReported() {
        // Llamada de la red con número oculto: Telecom la ve como administrada.
        CallCycle c = ringing(CallOrigin.MANAGED_YES);
        c.observe(null, CallOrigin.MANAGED_YES, false);
        assertEquals(CallOrigin.Decision.REPORT, c.end());
    }

    @Test
    public void unknownOrErrorGoesToTheFallbackAndThenIsReported() {
        CallCycle c = ringing(CallOrigin.MANAGED_UNKNOWN);
        c.observe(null, CallOrigin.MANAGED_NO, false);
        assertEquals(CallOrigin.Decision.CHECK_CALL_LOG, c.end());
    }

    @Test
    public void laterNumberForcesTheReport() {
        // La primera difusión, sin número y sin Telecom listo; la segunda trae el número.
        CallCycle c = ringing(CallOrigin.MANAGED_NO);
        c.observe("+12105550101", CallOrigin.MANAGED_NO, false);
        assertEquals("+12105550101", c.number());
        assertEquals(CallOrigin.Decision.REPORT, c.end());
    }

    @Test
    public void numberIsNotLostByALaterBroadcastWithoutIt() {
        CallCycle c = ringing(CallOrigin.MANAGED_NO);
        c.observe("+12105550101", CallOrigin.MANAGED_NO, false);
        c.observe(null, CallOrigin.MANAGED_NO, false); // OFFHOOK sin número
        assertEquals(CallOrigin.Decision.REPORT, c.end());
    }

    @Test
    public void answeredCallCountsTheOffhookCheckToo() {
        CallCycle c = ringing(CallOrigin.MANAGED_NO);
        c.observe(null, CallOrigin.MANAGED_YES, false); // al contestar, Telecom ya la ve
        assertEquals(CallOrigin.Decision.REPORT, c.end());
    }

    @Test
    public void endLeavesTheCycleClean() {
        CallCycle c = ringing(CallOrigin.MANAGED_YES);
        c.observe("+12105550101", CallOrigin.MANAGED_YES, false);
        c.end();
        assertEquals(CallOrigin.MANAGED_UNKNOWN, c.managed());
        assertEquals("", c.number());
    }

    @Test
    public void newCallDoesNotInheritThePreviousOne() {
        // Lo guardado de una llamada anterior (número y «administrada») no cuenta en la siguiente.
        CallCycle c = new CallCycle(CallOrigin.MANAGED_YES, "+12105550101");
        c.start();
        c.observe(null, CallOrigin.MANAGED_NO, true);
        assertEquals(CallOrigin.Decision.DROP, c.end());
    }
}
