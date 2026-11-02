package com.iaaorgmx.paynani;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * De dónde vino una llamada entrante sin número (PaynaniApp #151). Samsung manda PHONE_STATE
 * (RINGING → IDLE, sin número) también cuando suena una llamada de WhatsApp, que es self-managed:
 * parecía una llamada de la red con número oculto.
 *
 * Primero cuenta lo que dijo TelecomManager.isInManagedCall() mientras sonaba: true sólo con una
 * llamada de una SIM (u otra cuenta administrada), false si lo único que suena es self-managed.
 * Si no se pudo saber, se mira de qué cuenta la anotó el registro de llamadas (los teléfonos que
 * sí anotan las self-managed). Ante la duda se reporta: una llamada real con número oculto nunca
 * se pierde.
 */
final class CallOrigin {

    /** isInManagedCall() no se pudo consultar (sin permiso o sin TelecomManager). */
    static final int MANAGED_UNKNOWN = -1;
    /** Se consultó mientras sonaba y nunca dijo true: sólo sonaba una llamada self-managed. */
    static final int MANAGED_NO = 0;
    /** Alguna consulta dijo true: es una llamada de la SIM. */
    static final int MANAGED_YES = 1;

    enum Decision { REPORT, DROP, CHECK_CALL_LOG }

    /** Hasta cuánto antes del RINGING puede decir la entrada que empezó la llamada. */
    static final long BEFORE_MS = 15_000;
    /** Hasta cuánto después del RINGING. */
    static final long AFTER_MS = 5_000;

    /** Una entrada de CallLog.Calls: su DATE y su PHONE_ACCOUNT_COMPONENT_NAME (o null). */
    static final class Entry {
        final long date;
        final String component;

        Entry(long date, String component) {
            this.date = date;
            this.component = component;
        }
    }

    /** Una cuenta de getCallCapablePhoneAccounts(): su componente y si es de una SIM. */
    static final class Account {
        final String component;
        /** PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION, que sólo registra el sistema. */
        final boolean simSubscription;

        Account(String component, boolean simSubscription) {
            this.component = component;
            this.simSubscription = simSubscription;
        }
    }

    private CallOrigin() {
    }

    /**
     * Los componentes de las SIM. getCallCapablePhoneAccounts() también trae cuentas de voz por
     * internet que el usuario habilitó; ésas no cuentan.
     */
    static Set<String> simComponents(List<Account> accounts) {
        Set<String> out = new HashSet<>();
        for (Account a : accounts) {
            if (a.simSubscription && a.component != null && !a.component.isEmpty()) out.add(a.component);
        }
        return out;
    }

    /**
     * Junta dos consultas de la misma llamada (PHONE_STATE llega dos veces por cambio, y también
     * en OFFHOOK): basta un true; si alguna no se pudo hacer y ninguna dijo true, no se sabe.
     */
    static int merge(int a, int b) {
        if (a == MANAGED_YES || b == MANAGED_YES) return MANAGED_YES;
        if (a == MANAGED_UNKNOWN || b == MANAGED_UNKNOWN) return MANAGED_UNKNOWN;
        return MANAGED_NO;
    }

    /** Qué hacer con una llamada sin número al colgar, según isInManagedCall() mientras sonaba. */
    static Decision decide(int managed) {
        if (managed == MANAGED_YES) return Decision.REPORT;
        if (managed == MANAGED_NO) return Decision.DROP;
        return Decision.CHECK_CALL_LOG;
    }

    /** La entrada de esta llamada: la más cercana a started dentro de la ventana, o null. */
    static Entry match(List<Entry> entries, long started) {
        Entry best = null;
        for (Entry e : entries) {
            if (e.date < started - BEFORE_MS || e.date > started + AFTER_MS) continue;
            if (best == null || Math.abs(e.date - started) < Math.abs(best.date - started)) best = e;
        }
        return best;
    }

    /** Si la llamada se reporta, según la cuenta de su entrada y los componentes de las SIM. */
    static boolean report(Entry entry, Set<String> simComponents) {
        if (entry == null || entry.component == null || entry.component.isEmpty()) return true;
        if (simComponents.isEmpty()) return true; // no se pudo saber cuáles son las SIM
        return simComponents.contains(entry.component);
    }
}
