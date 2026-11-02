package com.iaaorgmx.paynani.telegram;

import com.iaaorgmx.paynani.messages.OutgoingText;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Las reglas de las pantallas de Telegram (paynani TELEGRAM.md §4 y §6), sin Android, para
 * probarlas con JUnit: qué dice el estado del bot, qué se ve de un hilo y cuándo se puede contestar.
 */
public final class TelegramRules {

    private TelegramRules() {
    }

    /** Lo que la pantalla dice del bot, a partir del state de telegram.status. */
    public enum State {
        /** running: no se muestra aviso. */
        RUNNING,
        /** starting u offline: Telegram no responde por ahora; el servicio reintenta solo. */
        RECONNECTING,
        /** conflict: el token lo usa otro programa (webhook o una segunda conexión). */
        CONFLICT,
        /** token_invalid: Telegram rechazó el token; se cambia desde PaynaniWeb o la terminal. */
        TOKEN_INVALID,
        /** stopped, journal_mismatch o service_down: el servicio no está atendiendo. */
        SERVICE_DOWN,
        /** Un estado que esta versión no conoce. */
        UNKNOWN
    }

    public static State state(String state) {
        if (state == null) return State.UNKNOWN;
        switch (state) {
            case "running":
                return State.RUNNING;
            case "starting":
            case "offline":
                return State.RECONNECTING;
            case "conflict":
                return State.CONFLICT;
            case "token_invalid":
                return State.TOKEN_INVALID;
            case "stopped":
            case "journal_mismatch":
            case "service_down":
                return State.SERVICE_DOWN;
            default:
                return State.UNKNOWN;
        }
    }

    /** Si la lista muestra un aviso arriba con el estado del bot. */
    public static boolean showsWarning(State state) {
        return state != State.RUNNING;
    }

    /** Los textos que dependen del idioma, para que las reglas no necesiten Android. */
    public interface Labels {
        /** Lo que se ve en lugar del mensaje de alguien fuera del roster. */
        String hidden();

        /** «[foto]», «[documento]»…, según kind; «[archivo]» para un kind desconocido. */
        String media(String kind);

        /** «Tú: » delante de lo último que salió. */
        String outPrefix();

        /** « (editado)» después del texto de un mensaje editado. */
        String edited();
    }

    /** El renglón de un hilo en la lista. */
    public static String preview(TelegramSource.ThreadSummary t, Labels labels) {
        if (t.hidden) return labels.hidden();
        boolean media = t.lastKind != null && !t.lastKind.isEmpty() && !"text".equals(t.lastKind);
        String excerpt = t.lastExcerpt == null ? "" : t.lastExcerpt;
        // Un archivo con pie: «[foto] El ticket de ayer», como en la burbuja.
        String body = media ? (excerpt.isEmpty() ? labels.media(t.lastKind) : labels.media(t.lastKind) + " " + excerpt)
                : excerpt;
        return "out".equals(t.lastDir) && !body.isEmpty() ? labels.outPrefix() + body : body;
    }

    /** El texto de una burbuja: el mensaje, el archivo con su pie, o el aviso de oculto. */
    public static String bubble(TelegramSource.Message m, Labels labels) {
        if (m.hidden) return labels.hidden();
        String text = m.text == null ? "" : m.text;
        String body;
        if (m.kind.isEmpty() || "text".equals(m.kind)) body = text;
        else body = text.isEmpty() ? labels.media(m.kind) : labels.media(m.kind) + " " + text;
        return m.edited ? body + labels.edited() : body;
    }

    /**
     * Si un mensaje que salió lo mandó el dueño: by es phone:<device_id> o web (TELEGRAM.md §4),
     * u owner. Lo del agente, la terminal y la API no.
     */
    public static boolean byOwner(String by) {
        return by != null && ("owner".equals(by) || "web".equals(by) || by.startsWith("phone"));
    }

    /** El cuadro de responder sólo si el hilo lo permite y la pasarela anuncia telegram.send (FR-25). */
    public static boolean replyVisible(boolean canReply, boolean canSend) {
        return canReply && canSend;
    }

    /**
     * Hasta 4096 caracteres, el límite de un mensaje de Telegram: lo que escribe el dueño sale en
     * un solo mensaje aunque el servicio sepa partir uno más largo.
     */
    public static final int MAX_TEXT = 4096;

    /** Si se puede mandar: con algo además de espacios y, ya sin lo del final, dentro del límite. */
    public static boolean sendable(String text) {
        String t = OutgoingText.trimEnd(text);
        return !t.trim().isEmpty() && t.length() <= MAX_TEXT;
    }

    /** Milisegundos de una fecha ISO 8601, o 0. */
    public static long parseIso(String iso) {
        if (iso == null || iso.isEmpty()) return 0;
        try {
            return Instant.parse(iso).toEpochMilli();
        } catch (DateTimeParseException e) {
            return 0;
        }
    }
}
