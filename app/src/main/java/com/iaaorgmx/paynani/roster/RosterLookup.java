package com.iaaorgmx.paynani.roster;

import com.iaaorgmx.paynani.gateway.Phone;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Un número en los Roster de todas las cuentas (PRD #137, FR-6, FR-11 y NFR-1 a NFR-2): para el
 * icono del Roster en una conversación. Compara en E.164 con el país de la SIM; el nombre no cuenta.
 * Una cuenta unreadable no se puede leer (SMS_GATEWAY.md §11): no se busca ni se ofrece para agregar.
 */
public final class RosterLookup {

    private RosterLookup() {
    }

    /** Un miembro de una cuenta que tiene el número. */
    public static final class Match {
        public final RosterSource.Account account;
        public final RosterMember member;

        Match(RosterSource.Account account, RosterMember member) {
            this.account = account;
            this.member = member;
        }
    }

    public static final class Result {
        /** Los miembros con el número, en el orden de las cuentas y de sus filas. */
        public final List<Match> matches;
        /** Las cuentas donde se puede agregar (se leyeron bien). */
        public final List<RosterSource.Account> addable;

        Result(List<Match> matches, List<RosterSource.Account> addable) {
            this.matches = Collections.unmodifiableList(matches);
            this.addable = Collections.unmodifiableList(addable);
        }

        /** Si hay algo que ofrecer: «Edit» si está, «Add» si hay dónde agregarlo. */
        public boolean actionable() {
            return !matches.isEmpty() || !addable.isEmpty();
        }
    }

    /**
     * Los miembros que tienen el número, en las listas ya leídas. Cada teléfono del roster se
     * pasa por {@link Phone#toE164} con la misma región, por si alguno no viniera en E.164.
     */
    static List<Match> find(String e164, String region, Map<RosterSource.Account, List<RosterMember>> rosters) {
        List<Match> out = new ArrayList<>();
        if (e164 == null) return out;
        for (Map.Entry<RosterSource.Account, List<RosterMember>> e : rosters.entrySet()) {
            for (RosterMember m : e.getValue()) {
                for (String p : m.phones) {
                    String n = Phone.toE164(p, region);
                    if (e164.equals(n != null ? n : p)) {
                        out.add(new Match(e.getKey(), m));
                        break;
                    }
                }
            }
        }
        return out;
    }

    /**
     * Lee las cuentas y el roster de cada una, y busca el número. Sin pasarela, o si no responde
     * roster.accounts, llega onError: la conversación no muestra icono ni error (FR-11). Una cuenta
     * cuyo roster.list falla tampoco se ofrece para agregar. Las respuestas llegan en el hilo principal.
     */
    public static void run(RosterSource source, String e164, String region, RosterSource.Callback<Result> cb) {
        if (!source.available() || e164 == null) {
            cb.onError(RosterSource.Failure.of("offline"));
            return;
        }
        source.accounts(new RosterSource.Callback<List<RosterSource.Account>>() {
            @Override
            public void onResult(List<RosterSource.Account> accounts) {
                List<RosterSource.Account> readable = new ArrayList<>();
                for (RosterSource.Account a : accounts) if (a.editable()) readable.add(a);
                if (readable.isEmpty()) {
                    cb.onResult(new Result(new ArrayList<>(), new ArrayList<>()));
                    return;
                }
                // Cada lista en su lugar, para que el orden no dependa de qué respuesta llega primero.
                Map<RosterSource.Account, List<RosterMember>> rosters = new LinkedHashMap<>();
                for (RosterSource.Account a : readable) rosters.put(a, null);
                int[] pending = {readable.size()};
                for (RosterSource.Account a : readable) {
                    source.list(a.id, new RosterSource.Callback<RosterSource.Roster>() {
                        @Override
                        public void onResult(RosterSource.Roster r) {
                            rosters.put(a, r.members);
                            if (--pending[0] == 0) finish();
                        }

                        @Override
                        public void onError(RosterSource.Failure f) {
                            rosters.remove(a);
                            if (--pending[0] == 0) finish();
                        }

                        private void finish() {
                            cb.onResult(new Result(find(e164, region, rosters), new ArrayList<>(rosters.keySet())));
                        }
                    });
                }
            }

            @Override
            public void onError(RosterSource.Failure f) {
                cb.onError(f);
            }
        });
    }
}
