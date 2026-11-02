package com.iaaorgmx.paynani.messages;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Empareja las burbujas enviadas de un hilo con las órdenes del dueño y de la API
 * que la app guardó (paynani PRD #489 FR-19, PRD #491 FR-19). El almacén de SMS no
 * dice quién pidió el envío, así que se reconoce por el texto y la hora: el mismo
 * texto, a menos de {@link #WINDOW_MS} de cuando llegó la orden. Cada orden marca
 * una sola burbuja, la más cercana en tiempo.
 */
public final class MarkMatch {

    public static final long WINDOW_MS = 10 * 60_000L;

    private MarkMatch() {}

    /** Una burbuja enviada: su id, texto y hora. */
    public static final class Sent {
        final long id;
        final String body;
        final long date;

        public Sent(long id, String body, long date) {
            this.id = id;
            this.body = body;
            this.date = date;
        }
    }

    /** Una orden marcada: la etiqueta que se muestra, su texto y cuándo llegó. */
    public static final class Mark {
        final String label;
        final String text;
        final long at;

        public Mark(String label, String text, long at) {
            this.label = label;
            this.text = text;
            this.at = at;
        }
    }

    /** id de burbuja → etiqueta. */
    public static Map<Long, String> match(List<Sent> sent, List<Mark> marks) {
        Map<Long, String> out = new HashMap<>();
        Set<Long> used = new HashSet<>();
        for (Mark mark : marks) {
            Sent best = null;
            for (Sent s : sent) {
                if (used.contains(s.id) || s.body == null || !s.body.equals(mark.text)) continue;
                long gap = Math.abs(s.date - mark.at);
                if (gap > WINDOW_MS) continue;
                if (best == null || gap < Math.abs(best.date - mark.at)) best = s;
            }
            if (best != null) {
                used.add(best.id);
                out.put(best.id, mark.label);
            }
        }
        return out;
    }
}
