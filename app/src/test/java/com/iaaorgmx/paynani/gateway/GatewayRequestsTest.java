package com.iaaorgmx.paynani.gateway;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SMS_GATEWAY.md §11: pregunta y respuesta por ref, reenvío único tras 15 s y fallo al desconectarse. */
public class GatewayRequestsTest {

    private final List<String> sent = new ArrayList<>();
    private final List<String> results = new ArrayList<>();
    private boolean connected = true;
    private final Map<Object, Runnable> timers = new LinkedHashMap<>();
    private int nextTimer;
    private GatewayRequests<String> requests;

    @Before
    public void setUp() {
        requests = new GatewayRequests<>(json -> {
            if (!connected) return false;
            sent.add(json);
            return true;
        }, new GatewayRequests.Scheduler() {
            @Override public Object schedule(Runnable r, long delayMs) {
                assertEquals(GatewayRequests.TIMEOUT_MS, delayMs);
                Object t = nextTimer++;
                timers.put(t, r);
                return t;
            }

            @Override public void cancel(Object token) {
                timers.remove(token);
            }
        });
    }

    private GatewayRequests.Callback<String> cb(String name) {
        return new GatewayRequests.Callback<String>() {
            @Override public void onResponse(String m, boolean resent) { results.add(name + ":" + m + (resent ? "+resent" : "")); }
            @Override public void onFailure(String code) { results.add(name + ":" + code); }
        };
    }

    /** Lo que haría el reloj al pasar 15 s. */
    private void elapse() {
        List<Runnable> due = new ArrayList<>(timers.values());
        timers.clear();
        for (Runnable r : due) r.run();
    }

    @Test
    public void idsMatchTheProtocol() {
        String id = GatewayRequests.newId();
        assertTrue(id, id.matches("^q_[0-9a-f]{32}$"));
        assertFalse(id.equals(GatewayRequests.newId()));
    }

    @Test
    public void aResponseGoesToItsRequestByRef() {
        requests.request("q_a", "{a}", cb("a"));
        requests.request("q_b", "{b}", cb("b"));
        assertTrue(requests.respond("q_b", "result-b"));
        assertTrue(requests.respond("q_a", "result-a"));
        assertEquals(List.of("b:result-b", "a:result-a"), results);
        assertTrue(timers.isEmpty());
    }

    @Test
    public void anUnknownRefIsNotOurs() {
        assertFalse(requests.respond("q_none", "x"));
    }

    @Test
    public void withoutConnectionItFailsAtOnce() {
        connected = false;
        requests.request("q_a", "{a}", cb("a"));
        assertEquals(List.of("a:offline"), results);
        assertEquals(0, requests.pendingCount());
    }

    @Test
    public void afterATimeoutItIsResentOnceWithTheSameJson() {
        requests.request("q_a", "{\"id\":\"q_a\"}", cb("a"));
        elapse();
        assertEquals(List.of("{\"id\":\"q_a\"}", "{\"id\":\"q_a\"}"), sent);
        assertTrue(results.isEmpty());
        // La pasarela contesta replayed al reenvío: llega como cualquier respuesta.
        assertTrue(requests.respond("q_a", "replayed"));
        assertEquals(List.of("a:replayed+resent"), results);
    }

    @Test
    public void aSecondTimeoutFails() {
        requests.request("q_a", "{a}", cb("a"));
        elapse();
        elapse();
        assertEquals(2, sent.size());
        assertEquals(List.of("a:timeout"), results);
        assertFalse(requests.respond("q_a", "late"));
    }

    @Test
    public void aDisconnectFailsEverythingPending() {
        requests.request("q_a", "{a}", cb("a"));
        requests.request("q_b", "{b}", cb("b"));
        requests.disconnected();
        assertEquals(List.of("a:offline", "b:offline"), results);
        assertTrue(timers.isEmpty());
        assertFalse(requests.respond("q_a", "late"));
    }
}
