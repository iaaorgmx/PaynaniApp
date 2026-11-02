package com.iaaorgmx.paynani.roster;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

/**
 * El campo Telegram de la ficha (paynani #628, Q270 = A; SMS_GATEWAY.md §11): sólo un @usuario
 * desde la app, un id nunca se ve ni se manda, y sin cambio no va la llave telegram.
 */
public class RosterTelegramTest {

    private static final List<RosterMember> NONE = Collections.emptyList();

    /** Una fila leída de roster.list. */
    private static RosterMember read(int row, String email, boolean hasTelegram, String username) {
        return new RosterMember(row, "Ana Prueba", email, RosterMember.HUMAN, "", null, false, false, false,
                hasTelegram, username, null);
    }

    /** Lo que queda escrito en la ficha sobre `was`. */
    private static RosterMember typed(RosterMember was, String email, String telegram) {
        return RosterMember.draft(was == null ? 0 : was.row, "Ana Prueba", email, RosterMember.HUMAN, "", null,
                was, telegram);
    }

    private static String problem(RosterMember m, List<RosterMember> roster) {
        RosterValidation.Problem p = RosterValidation.check(m, roster);
        return p == null ? null : p.field + ":" + p.reason;
    }

    @Test
    public void usernameIsNormalizedLikeTheGateway() {
        assertEquals("@ana_lopez", RosterValidation.telegramUsername("@Ana_Lopez"));
        assertEquals("@ana_lopez", RosterValidation.telegramUsername("  ana_lopez "));
        assertNull(RosterValidation.telegramUsername("@ana"));            // menos de 5
        assertNull(RosterValidation.telegramUsername("@1ana_lopez"));     // empieza con número
        assertNull(RosterValidation.telegramUsername("@ana-lopez"));      // guion
        assertNull(RosterValidation.telegramUsername("123456789"));       // un id no es @usuario
        assertNull(RosterValidation.telegramUsername(""));
    }

    @Test
    public void untouchedFieldDoesNotSendTheKey() {
        RosterMember ana = read(1, "ana@example.com", true, "@ana_lopez");
        assertNull(typed(ana, "ana@example.com", "@ana_lopez").telegramEdit);
        // Mayúsculas o sin @ es el mismo @usuario: tampoco se manda.
        assertNull(typed(ana, "ana@example.com", "Ana_Lopez").telegramEdit);
        // Cambiar otro campo no toca Telegram.
        assertNull(typed(ana, "ana.nueva@example.com", "@ana_lopez").telegramEdit);
        // Un alta sin Telegram no manda la llave.
        assertNull(typed(null, "ana@example.com", "").telegramEdit);
    }

    @Test
    public void changingOrClearingTheUsername() {
        RosterMember ana = read(1, "ana@example.com", true, "@ana_lopez");
        assertEquals("@ana_ruiz", typed(ana, "ana@example.com", "@ana_ruiz").telegramEdit);
        assertEquals("", typed(ana, "ana@example.com", "").telegramEdit);
        assertEquals("@ana_ruiz", typed(null, "ana@example.com", "@ana_ruiz").telegramEdit);
    }

    @Test
    public void anIdCellIsNeverSentNorShown() {
        RosterMember withId = read(1, "", true, "");
        assertTrue(withId.telegramIsId());
        // El campo está deshabilitado, pero aunque llegara texto, una celda con id no se manda.
        assertNull(typed(withId, "", "").telegramEdit);
        assertNull(typed(withId, "", "@otro_usuario").telegramEdit);
        assertFalse(read(1, "", true, "@ana_lopez").telegramIsId());
        assertFalse(read(1, "", false, "").telegramIsId());
    }

    @Test
    public void aNumberFromTheAppIsRejectedWithoutSending() {
        assertEquals("telegram:telegram_username_only", problem(typed(null, "ana@example.com", "123456789"), NONE));
        assertEquals("telegram:telegram_username_only", problem(typed(null, "ana@example.com", "@123456789"), NONE));
        assertEquals("telegram:telegram", problem(typed(null, "ana@example.com", "@ana"), NONE));
        assertNull(problem(typed(null, "ana@example.com", "@ana_lopez"), NONE));
    }

    @Test
    public void telegramCountsAsAChannel() {
        // Sólo con Telegram: un @usuario nuevo, o un id que la fila conserva.
        assertNull(problem(typed(null, "", "@ana_lopez"), NONE));
        assertNull(problem(typed(read(1, "", true, ""), "", ""), NONE));
        assertNull(problem(typed(read(1, "", true, "@ana_lopez"), "", "@ana_lopez"), NONE));
        // Borrar el único canal no se puede.
        assertEquals("email:no_channel", problem(typed(read(1, "", true, "@ana_lopez"), "", ""), NONE));
        assertEquals("email:no_channel", problem(typed(null, "", ""), NONE));
    }

    @Test
    public void duplicateUsernameInAnotherRow() {
        List<RosterMember> roster = Collections.singletonList(read(2, "luis@example.com", true, "@ana_lopez"));
        assertEquals("telegram:duplicate_telegram", problem(typed(null, "ana@example.com", "@Ana_Lopez"), roster));
        // La misma fila no choca consigo misma.
        RosterMember self = read(2, "luis@example.com", true, "@ana_lopez");
        assertNull(problem(typed(self, "luis@example.com", "@ana_lopez"), Collections.singletonList(self)));
    }

    @Test
    public void draftKeyChangesWithTelegram() {
        RosterMember ana = read(1, "ana@example.com", true, "@ana_lopez");
        assertEquals(typed(ana, "ana@example.com", "@ana_lopez").key(), typed(ana, "ana@example.com", "@ana_lopez").key());
        assertFalse(typed(ana, "ana@example.com", "@ana_lopez").key()
                .equals(typed(ana, "ana@example.com", "@ana_ruiz").key()));
    }
}
