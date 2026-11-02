package com.iaaorgmx.paynani.whatsapp;

import com.iaaorgmx.paynani.messages.OutgoingText;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Lo que la app decide sobre WhatsApp sin Android ni JSON, para probarlo con JUnit
 * (SMS_GATEWAY.md §12, PRD #550 FR-6a, FR-8, FR-16a y FR-25).
 */
public final class WhatsAppRules {

    private WhatsAppRules() {
    }

    /** Cómo se muestra el estado de la cuenta en la sección «WhatsApp». */
    public enum State {
        /** Vinculada y conectada. */
        CONNECTED,
        /** Vinculada, pero el puente se está conectando o perdió la conexión. */
        RECONNECTING,
        /** Hay una vinculación en curso. */
        LINKING,
        /** Sin vincular, o WhatsApp cerró la sesión: hay que vincular. */
        NOT_LINKED,
        /** El servicio paynani-whatsapp no corre en el host. */
        SERVICE_DOWN
    }

    public static State state(String state) {
        if (state == null) return State.NOT_LINKED;
        switch (state) {
            case "connected":
                return State.CONNECTED;
            case "connecting":
            case "disconnected":
                return State.RECONNECTING;
            case "linking":
                return State.LINKING;
            case "service_down":
                return State.SERVICE_DOWN;
            case "unlinked":
            case "logged_out":
            default:
                return State.NOT_LINKED;
        }
    }

    /** Qué botones muestra la sección, según el estado y si la pasarela concedió whatsapp.admin (FR-6a). */
    public static final class Actions {
        public final boolean requestCode;
        public final boolean cancelLink;
        public final boolean unlink;

        Actions(boolean requestCode, boolean cancelLink, boolean unlink) {
            this.requestCode = requestCode;
            this.cancelLink = cancelLink;
            this.unlink = unlink;
        }
    }

    public static Actions actions(State state, boolean admin) {
        if (!admin) return new Actions(false, false, false);
        switch (state) {
            case NOT_LINKED:
                return new Actions(true, false, false);
            case LINKING:
                // Pedir otro código cancela el anterior (FR-6a), así que también se puede.
                return new Actions(true, true, false);
            case CONNECTED:
            case RECONNECTING:
                return new Actions(false, false, true);
            case SERVICE_DOWN:
            default:
                return new Actions(false, false, false);
        }
    }

    /** Los textos que necesita {@link #preview}, de strings.xml. */
    public interface Labels {
        /** Fuera del roster (FR-16a): no se muestra nada del mensaje. */
        String hidden();

        /** Un archivo, por su tipo: image, audio, video, document, sticker… */
        String media(String kind);

        /** Lo que salió de aquí lleva un prefijo, como en «Messages». */
        String outPrefix();
    }

    /** El renglón de un hilo en «WhatsApp messages». */
    public static String preview(WhatsAppSource.ThreadSummary t, Labels labels) {
        if (t.hidden) return labels.hidden();
        String body;
        if (t.lastExcerpt != null && !t.lastExcerpt.isEmpty()) body = t.lastExcerpt;
        else if (t.lastKind != null && !t.lastKind.isEmpty() && !"text".equals(t.lastKind)) {
            body = labels.media(t.lastKind);
        } else body = "";
        return "out".equals(t.lastDir) && !body.isEmpty() ? labels.outPrefix() + body : body;
    }

    /** El texto de una burbuja: el mensaje, el tipo de archivo o el aviso de oculto. */
    public static String bubble(WhatsAppSource.Message m, Labels labels) {
        if (m.hidden) return labels.hidden();
        if (m.text != null && !m.text.isEmpty()) return m.text;
        return m.kind.isEmpty() || "text".equals(m.kind) ? "" : labels.media(m.kind);
    }

    /**
     * Segundos que le quedan al código: expiresAt es hora del host, y clockOffsetMs la diferencia
     * entre el host y el teléfono que la app calcula en el welcome (server_time). 0 si ya venció o
     * si la fecha no se entiende.
     */
    public static long remainingSeconds(String expiresAt, long nowMs, long clockOffsetMs) {
        long expires = parseIso(expiresAt);
        if (expires <= 0) return 0;
        long left = expires - (nowMs + clockOffsetMs);
        return left <= 0 ? 0 : (left + 999) / 1000;
    }

    /**
     * Segundos que le quedan al código con expires_in_s (§12): la cuenta empieza al recibir la
     * respuesta y no depende del reloj del teléfono ni del host. -1 si no hay expires_in_s.
     */
    public static long remainingSeconds(long expiresInS, long receivedElapsedMs, long nowElapsedMs) {
        if (expiresInS < 0) return -1;
        long left = expiresInS * 1000 - (nowElapsedMs - receivedElapsedMs);
        return left <= 0 ? 0 : (left + 999) / 1000;
    }

    /** mm:ss para la cuenta regresiva. */
    public static String countdown(long seconds) {
        long s = Math.max(0, seconds);
        return String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60);
    }

    /** Si el hilo deja contestar: la pasarela lo permite (can_reply) y anuncia whatsapp.send. */
    public static boolean replyVisible(boolean canReply, boolean canSend) {
        return canReply && canSend;
    }

    /** Lo que se puede mandar: de 1 a 4,000 caracteres, sin contar espacios de los extremos (§12). */
    /** Si se puede mandar: con algo además de espacios y, ya sin lo del final, dentro del límite. */
    public static boolean sendable(String text) {
        String t = OutgoingText.trimEnd(text);
        return !t.trim().isEmpty() && t.length() <= MAX_TEXT;
    }

    public static final int MAX_TEXT = 4000;

    static long parseIso(String iso) {
        if (iso == null || iso.isEmpty()) return 0;
        try {
            return Instant.parse(iso).toEpochMilli();
        } catch (DateTimeParseException e) {
            return 0;
        }
    }
}
