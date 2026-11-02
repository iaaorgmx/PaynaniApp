package com.iaaorgmx.paynani.roster;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** La pantalla de contacto (#133): teléfonos escritos, la fila tras un conflict y si hay cambios. */
public class ContactEditorTest {

    private static RosterMember m(int row, String name, String email, String... phones) {
        return RosterMember.draft(row, name, email, RosterMember.HUMAN, "", Arrays.asList(phones));
    }

    @Test
    public void phonesAreSplitByCommaSemicolonOrLine() {
        assertEquals(Arrays.asList("+5215512345678", "+12105550100"),
                ContactEditorActivity.splitPhones(" +5215512345678, +12105550100 "));
        assertEquals(Arrays.asList("+1", "+2", "+3"), ContactEditorActivity.splitPhones("+1;+2\n+3"));
        assertEquals(Collections.emptyList(), ContactEditorActivity.splitPhones(" , ;"));
    }

    @Test
    public void afterAConflictTheRowIsFoundByEmailThenPhone() {
        List<RosterMember> roster = Arrays.asList(
                m(1, "Ana", "Ana@Example.com"),
                m(2, "Beto", "", "+12105550100"));
        assertEquals(1, ContactEditorActivity.find(roster, m(5, "Ana R", "ana@example.com")).row);
        assertEquals(2, ContactEditorActivity.find(roster, m(5, "Beto", "", "+12105550100")).row);
        assertNull(ContactEditorActivity.find(roster, m(5, "Otro", "otro@example.com", "+12105550199")));
    }

    /** Una fila leída con Telegram (has_telegram y, si no es un id, su @usuario). */
    private static RosterMember tg(int row, String name, String email, String username) {
        return new RosterMember(row, name, email, RosterMember.HUMAN, "", null, false, false, false, true, username, null);
    }

    @Test
    public void afterAConflictATelegramOnlyRowIsFoundByItsUsername() {
        // Ximena en #167: una fila sólo con @usuario no debe volverse alta tras un conflict.
        RosterMember was = tg(3, "Ana", "", "@ana_lopez");
        List<RosterMember> moved = Arrays.asList(m(1, "Dueño", "duenio@example.com"), tg(2, "Ana", "", "@ana_lopez"));
        RosterMember untouched = RosterMember.draft(3, "Ana López", "", RosterMember.HUMAN, "", null, was, "@ana_lopez");
        assertEquals(2, ContactEditorActivity.find(moved, untouched).row);
        // También si el dueño escribió otro @usuario y otra escritura ya lo puso.
        RosterMember changed = RosterMember.draft(3, "Ana", "", RosterMember.HUMAN, "", null, was, "@ana_ruiz");
        List<RosterMember> renamed = Collections.singletonList(tg(2, "Ana", "", "@ana_ruiz"));
        assertEquals(2, ContactEditorActivity.find(renamed, changed).row);
        assertTrue(ContactEditorActivity.canRelocate(untouched));
    }

    @Test
    public void anIdOnlyRowCannotBeRelocatedAndMustNotBecomeANewContact() {
        // Sólo un id, sin correo ni teléfono: la app no tiene con qué buscarla.
        RosterMember was = tg(3, "Luis", "", "");
        RosterMember draft = RosterMember.draft(3, "Luis Garza", "", RosterMember.HUMAN, "", null, was, "");
        assertFalse(ContactEditorActivity.canRelocate(draft));
        assertNull(ContactEditorActivity.find(Collections.singletonList(tg(2, "Luis", "", "")), draft));
        // Con correo sí se encuentra, aunque Telegram sea un id.
        RosterMember withEmail = RosterMember.draft(3, "Luis", "luis@example.com", RosterMember.HUMAN, "", null,
                tg(3, "Luis", "luis@example.com", ""), "");
        assertTrue(ContactEditorActivity.canRelocate(withEmail));
        assertEquals(2, ContactEditorActivity.find(
                Collections.singletonList(tg(2, "Luis", "luis@example.com", "")), withEmail).row);
    }

    @Test
    public void theKeyChangesWithAnyField() {
        RosterMember a = m(1, "Ana", "ana@example.com", "+12105550100");
        assertEquals(a.key(), m(3, "Ana", "ana@example.com", "+12105550100").key()); // la fila no cuenta
        assertNotEquals(a.key(), m(1, "Ana", "ana@example.com", "+12105550101").key());
        assertNotEquals(a.key(), RosterMember.draft(1, "Ana", "ana@example.com", RosterMember.AI_AGENT, "",
                Arrays.asList("+12105550100")).key());
        assertNotEquals(a.key(), RosterMember.draft(1, "Ana", "ana@example.com", RosterMember.HUMAN, "ana-bot",
                Arrays.asList("+12105550100")).key());
    }
}
