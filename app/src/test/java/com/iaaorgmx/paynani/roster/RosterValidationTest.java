package com.iaaorgmx.paynani.roster;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** PRD #409, R-3, con los field y reason de SMS_GATEWAY.md §11. */
public class RosterValidationTest {

    private static final List<RosterMember> NONE = Collections.emptyList();

    private static RosterMember m(int row, String email, String... phones) {
        return RosterMember.draft(row, "Ana Prueba", email, RosterMember.HUMAN, "", Arrays.asList(phones));
    }

    private static String problem(RosterMember m, List<RosterMember> roster) {
        RosterValidation.Problem p = RosterValidation.check(m, roster);
        return p == null ? null : p.field + ":" + p.reason;
    }

    @Test
    public void aValidRowPasses() {
        assertNull(problem(m(0, "ana@example.com", "+5215512345678"), NONE));
        assertNull(problem(m(0, "ana@example.com"), NONE));
        assertNull(problem(m(0, "", "+12105550100", "+12105550101"), NONE));
    }

    @Test
    public void nameIsRequiredAndCannotBreakTheTable() {
        assertEquals("name:required", problem(
                RosterMember.draft(0, "  ", "ana@example.com", RosterMember.HUMAN, "", null), NONE));
        assertEquals("name:required", problem(
                RosterMember.draft(0, "Ana | Ruiz", "ana@example.com", RosterMember.HUMAN, "", null), NONE));
    }

    @Test
    public void emailMustLookLikeAnAddress() {
        assertEquals("email:email_format", problem(m(0, "ana@example"), NONE));
        assertEquals("email:email_format", problem(m(0, "ana example.com"), NONE));
    }

    @Test
    public void emailUsesTheGatewayPattern() {
        // roster_edit.EMAIL: sin |<>,; que romperían la tabla de roster.md o la dirección.
        for (String bad : new String[]{"Ana <ana@example.com>", "ana@example.com,otra@example.com",
                "ana;x@example.com", "a|b@example.com"}) {
            assertEquals(bad, "email:email_format", problem(m(0, bad), NONE));
        }
    }

    @Test
    public void everyPhoneMustBeE164() {
        assertEquals("phones:phone_e164", problem(m(0, "", "5512345678"), NONE));
        assertEquals("phones:phone_e164", problem(m(0, "", "+12105550100", "+52 55 1234 5678"), NONE));
        assertEquals("phones:phone_e164", problem(m(0, "", "+0123456789"), NONE));
    }

    @Test
    public void oneChannelIsRequired() {
        assertEquals("email:no_channel", problem(m(0, ""), NONE));
    }

    @Test
    public void githubLoginIsChecked() {
        assertEquals("github:github", problem(
                RosterMember.draft(0, "Ana", "ana@example.com", RosterMember.AI_AGENT, "-ana", null), NONE));
        assertNull(problem(RosterMember.draft(0, "Ana", "ana@example.com", RosterMember.AI_AGENT, "ana-bot", null), NONE));
    }

    @Test
    public void duplicatesInTheSameRosterAreRejected() {
        List<RosterMember> roster = Arrays.asList(m(1, "Ana@Example.com", "+12105550100"));
        assertEquals("email:duplicate_email", problem(m(0, "ana@example.com"), roster));
        assertEquals("phones:duplicate_phone", problem(m(0, "otra@example.com", "+12105550100"), roster));
    }

    @Test
    public void editingARowDoesNotClashWithItself() {
        List<RosterMember> roster = Arrays.asList(m(1, "ana@example.com", "+12105550100"));
        assertNull(problem(m(1, "ana@example.com", "+12105550100"), roster));
    }

    @Test
    public void typeIsHumanUnlessAiAgent() {
        assertEquals(RosterMember.HUMAN, RosterMember.draft(0, "A", "a@b.co", "robot", "", null).type);
        assertEquals(RosterMember.AI_AGENT, RosterMember.draft(0, "A", "a@b.co", "AI Agent", "", null).type);
    }
}
