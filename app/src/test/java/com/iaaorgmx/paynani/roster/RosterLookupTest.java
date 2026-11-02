package com.iaaorgmx.paynani.roster;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** PRD #137, FR-6 y NFR-2: el número de una conversación en los Roster de todas las cuentas. */
public class RosterLookupTest {

    private static final RosterSource.Account AGENT = new RosterSource.Account(null, "agente@example.com", 2, "ok");
    private static final RosterSource.Account SALES = new RosterSource.Account("ventas", "ventas@example.com", 1, "ok");
    private static final RosterSource.Account BROKEN = new RosterSource.Account("rota", "rota@example.com", 0, "unreadable");

    private static RosterMember m(int row, String name, String... phones) {
        return new RosterMember(row, name, name.toLowerCase().replace(' ', '.') + "@example.com",
                RosterMember.HUMAN, "", Arrays.asList(phones), false, false, false);
    }

    private static Map<RosterSource.Account, List<RosterMember>> rosters(Object... pairs) {
        Map<RosterSource.Account, List<RosterMember>> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            @SuppressWarnings("unchecked") List<RosterMember> members = (List<RosterMember>) pairs[i + 1];
            out.put((RosterSource.Account) pairs[i], members);
        }
        return out;
    }

    @Test
    public void aLocalNumberInTheRosterMatchesTheE164OfTheConversation() {
        // Una fila escrita a mano sin "+52": con la región de la SIM es el mismo número.
        Map<RosterSource.Account, List<RosterMember>> r = rosters(AGENT, Arrays.asList(m(1, "Ana", "5512345678")));
        assertEquals(1, RosterLookup.find("+525512345678", "MX", r).size());
        // El "1" viejo de los móviles de México también.
        r = rosters(AGENT, Arrays.asList(m(1, "Ana", "+5215512345678")));
        assertEquals(1, RosterLookup.find("+525512345678", "MX", r).size());
        // En EE. UU., 10 dígitos son +1.
        r = rosters(AGENT, Arrays.asList(m(1, "Bob", "(210) 555-0100")));
        assertEquals(1, RosterLookup.find("+12105550100", "US", r).size());
    }

    @Test
    public void anyOfAMembersPhonesCountsOnce() {
        Map<RosterSource.Account, List<RosterMember>> r = rosters(AGENT,
                Arrays.asList(m(1, "Ana", "+12105550100", "+525512345678", "+525512345678")));
        List<RosterLookup.Match> found = RosterLookup.find("+525512345678", "MX", r);
        assertEquals(1, found.size());
        assertEquals("Ana", found.get(0).member.name);
    }

    @Test
    public void theSameNumberInTwoAccountsGivesBothInAccountOrder() {
        Map<RosterSource.Account, List<RosterMember>> r = rosters(
                AGENT, Arrays.asList(m(1, "Ana", "+525512345678"), m(2, "Otro", "+12105550100")),
                SALES, Arrays.asList(m(1, "Ana Ventas", "+525512345678")));
        List<RosterLookup.Match> found = RosterLookup.find("+525512345678", "MX", r);
        assertEquals(2, found.size());
        assertEquals(AGENT, found.get(0).account);
        assertEquals(SALES, found.get(1).account);
        assertEquals("Ana Ventas", found.get(1).member.name);
    }

    @Test
    public void theNameDoesNotCountAndNoNumberFindsNothing() {
        Map<RosterSource.Account, List<RosterMember>> r = rosters(AGENT, Arrays.asList(m(1, "+525512345678")));
        assertTrue(RosterLookup.find("+525512345678", "MX", r).isEmpty());
        assertTrue(RosterLookup.find(null, "MX", r).isEmpty());
    }

    // --- run(): las cuentas que se leen, las que se ofrecen y las que fallan ---------------------

    /** Una pasarela que responde en el acto: cada cuenta con su lista, o con un error. */
    private static final class FakeSource implements RosterSource {
        boolean available = true;
        List<Account> accounts = new ArrayList<>();
        final Map<String, Object> lists = new HashMap<>(); // id ("" la principal) -> List<RosterMember> o Failure
        final List<String> asked = new ArrayList<>();

        @Override public boolean available() { return available; }
        @Override public void accounts(Callback<List<Account>> cb) { cb.onResult(accounts); }

        @Override
        @SuppressWarnings("unchecked")
        public void list(String account, Callback<Roster> cb) {
            String key = account == null ? "" : account;
            asked.add(key);
            Object v = lists.get(key);
            if (v instanceof Failure) cb.onError((Failure) v);
            else cb.onResult(new Roster("e", (List<RosterMember>) v, Collections.emptyList(), false, 0));
        }

        @Override public void save(String a, String e, RosterMember m, Callback<Roster> cb) { }
        @Override public void delete(String a, String e, int row, Callback<Roster> cb) { }
        @Override public boolean rulesAvailable() { return false; }
        @Override public boolean previewAvailable() { return false; }
        @Override public void rulesGet(String a, int row, String re, Callback<Rules> cb) { }
        @Override public void rulesPreview(String a, int row, String re, String t, Callback<Rules> cb) { }
        @Override public void rulesSave(String a, int row, String e, String re, String t, Callback<Rules> cb) { }
        @Override public void defaultGet(Callback<Rules> cb) { }
        @Override public void defaultSave(String e, String t, Callback<Rules> cb) { }
    }

    private static final class Capture implements RosterSource.Callback<RosterLookup.Result> {
        RosterLookup.Result result;
        RosterSource.Failure failure;

        @Override public void onResult(RosterLookup.Result r) { result = r; }
        @Override public void onError(RosterSource.Failure f) { failure = f; }
    }

    @Test
    public void anUnreadableAccountIsNotReadNorOfferedAndAFailedListIsNotOffered() {
        FakeSource s = new FakeSource();
        s.accounts = Arrays.asList(AGENT, BROKEN, SALES);
        s.lists.put("", Arrays.asList(m(1, "Ana", "+525512345678")));
        s.lists.put("ventas", RosterSource.Failure.of("timeout"));
        Capture c = new Capture();
        RosterLookup.run(s, "+525512345678", "MX", c);
        assertEquals(Arrays.asList("", "ventas"), s.asked);
        assertEquals(1, c.result.matches.size());
        assertEquals(Collections.singletonList(AGENT), c.result.addable);
        assertTrue(c.result.actionable());
    }

    @Test
    public void onlyUnreadableAccountsLeaveNothingToOffer() {
        FakeSource s = new FakeSource();
        s.accounts = Collections.singletonList(BROKEN);
        Capture c = new Capture();
        RosterLookup.run(s, "+525512345678", "MX", c);
        assertTrue(s.asked.isEmpty());
        assertFalse(c.result.actionable());
    }

    @Test
    public void withoutAGatewayThereIsNoResult() {
        FakeSource s = new FakeSource();
        s.available = false;
        Capture c = new Capture();
        RosterLookup.run(s, "+525512345678", "MX", c);
        assertEquals("offline", c.failure.code);
    }
}
