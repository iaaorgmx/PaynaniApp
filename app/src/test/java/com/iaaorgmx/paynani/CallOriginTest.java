package com.iaaorgmx.paynani;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** PaynaniApp #151: una llamada de WhatsApp no sale como llamada de la red con número oculto. */
public class CallOriginTest {

    private static final String SIM = "com.android.phone/com.android.services.telephony.TelephonyConnectionService";
    private static final String WHATSAPP = "com.whatsapp/com.whatsapp.calling.telecom.SelfManagedConnectionService";
    private static final Set<String> SIMS = new HashSet<>(Collections.singletonList(SIM));
    private static final long STARTED = 1_000_000_000L;

    @Test
    public void whatsAppCallIsNotReported() {
        CallOrigin.Entry e = new CallOrigin.Entry(STARTED + 300, WHATSAPP);
        assertFalse(CallOrigin.report(e, SIMS));
    }

    @Test
    public void hiddenNumberFromTheSimIsReported() {
        CallOrigin.Entry e = new CallOrigin.Entry(STARTED - 200, SIM);
        assertTrue(CallOrigin.report(e, SIMS));
    }

    @Test
    public void inDoubtTheCallIsReported() {
        assertTrue(CallOrigin.report(null, SIMS)); // Android no la anotó a tiempo
        assertTrue(CallOrigin.report(new CallOrigin.Entry(STARTED, null), SIMS)); // sin cuenta
        assertTrue(CallOrigin.report(new CallOrigin.Entry(STARTED, ""), SIMS));
        assertTrue(CallOrigin.report(new CallOrigin.Entry(STARTED, WHATSAPP), Collections.emptySet())); // sin SIM conocidas
    }

    @Test
    public void secondSimIsAlsoTheNetwork() {
        String sim2 = "com.android.phone/com.android.services.telephony.TelephonyConnectionService2";
        Set<String> sims = new HashSet<>(Arrays.asList(SIM, sim2));
        assertTrue(CallOrigin.report(new CallOrigin.Entry(STARTED, sim2), sims));
    }

    @Test
    public void matchPicksTheClosestEntryInTheWindow() {
        CallOrigin.Entry before = new CallOrigin.Entry(STARTED - 9_000, SIM);
        CallOrigin.Entry close = new CallOrigin.Entry(STARTED + 400, WHATSAPP);
        CallOrigin.Entry tooOld = new CallOrigin.Entry(STARTED - CallOrigin.BEFORE_MS - 1, WHATSAPP);
        CallOrigin.Entry tooNew = new CallOrigin.Entry(STARTED + CallOrigin.AFTER_MS + 1, WHATSAPP);
        assertSame(close, CallOrigin.match(Arrays.asList(tooOld, before, close, tooNew), STARTED));
        assertSame(before, CallOrigin.match(Arrays.asList(tooOld, before, tooNew), STARTED));
        assertNull(CallOrigin.match(Arrays.asList(tooOld, tooNew), STARTED));
        assertNull(CallOrigin.match(Collections.emptyList(), STARTED));
    }

    @Test
    public void windowEdgesCount() {
        CallOrigin.Entry edge = new CallOrigin.Entry(STARTED - CallOrigin.BEFORE_MS, SIM);
        assertEquals(edge, CallOrigin.match(Collections.singletonList(edge), STARTED));
    }

    // --- isInManagedCall() mientras suena (la llamada de WhatsApp no queda en el registro) -------

    @Test
    public void selfManagedOnlyIsDropped() {
        // Las dos difusiones de RINGING dijeron false: sólo sonaba WhatsApp.
        int managed = CallOrigin.merge(CallOrigin.MANAGED_NO, CallOrigin.MANAGED_NO);
        assertEquals(CallOrigin.Decision.DROP, CallOrigin.decide(managed));
    }

    @Test
    public void oneTrueMeansTheSim() {
        // La primera difusión llegó antes de que Telecom la registrara; la segunda ya dijo true.
        assertEquals(CallOrigin.MANAGED_YES, CallOrigin.merge(CallOrigin.MANAGED_NO, CallOrigin.MANAGED_YES));
        assertEquals(CallOrigin.MANAGED_YES, CallOrigin.merge(CallOrigin.MANAGED_UNKNOWN, CallOrigin.MANAGED_YES));
        assertEquals(CallOrigin.Decision.REPORT, CallOrigin.decide(CallOrigin.MANAGED_YES));
    }

    @Test
    public void unknownFallsBackToTheCallLog() {
        assertEquals(CallOrigin.MANAGED_UNKNOWN, CallOrigin.merge(CallOrigin.MANAGED_NO, CallOrigin.MANAGED_UNKNOWN));
        assertEquals(CallOrigin.Decision.CHECK_CALL_LOG, CallOrigin.decide(CallOrigin.MANAGED_UNKNOWN));
    }

    // --- Sólo las cuentas de SIM cuentan como SIM (revisión de Ximena) ----------------------------

    @Test
    public void voipAccountIsNotASim() {
        String voip = "com.example.voip/com.example.voip.ConnectionService";
        Set<String> sims = CallOrigin.simComponents(Arrays.asList(
                new CallOrigin.Account(SIM, true), new CallOrigin.Account(voip, false)));
        assertEquals(Collections.singleton(SIM), sims);
        assertFalse(CallOrigin.report(new CallOrigin.Entry(STARTED, voip), sims));
        assertTrue(CallOrigin.report(new CallOrigin.Entry(STARTED, SIM), sims));
    }

    @Test
    public void withoutSimAccountsTheCallIsReported() {
        String voip = "com.example.voip/com.example.voip.ConnectionService";
        Set<String> sims = CallOrigin.simComponents(Collections.singletonList(new CallOrigin.Account(voip, false)));
        assertTrue(sims.isEmpty());
        assertTrue(CallOrigin.report(new CallOrigin.Entry(STARTED, voip), sims));
    }
}
