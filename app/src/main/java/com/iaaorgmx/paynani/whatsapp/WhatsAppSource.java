package com.iaaorgmx.paynani.whatsapp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * WhatsApp de la pasarela (SMS_GATEWAY.md §12, paynani PRD #550): whatsapp.status, whatsapp.link,
 * whatsapp.link_cancel, whatsapp.unlink, whatsapp.threads, whatsapp.thread y whatsapp.send por el
 * WebSocket del teléfono emparejado. La pantalla también se prueba con {@link DemoWhatsAppSource}.
 *
 * Las respuestas llegan en el hilo principal.
 */
public interface WhatsAppSource {

    /** Lo que responde whatsapp.status. */
    final class Status {
        /** unlinked, linking, connecting, connected, disconnected, logged_out o service_down. */
        public final String state;
        /** El número vinculado, o null. */
        public final String me;
        /** Desde cuándo está en este estado, ISO 8601, o "". */
        public final String since;
        /** Quién vincula ahora (phone, web o terminal), o null si no hay vinculación en curso. */
        public final String linkingBy;
        /** Cuándo vence la vinculación en curso, ISO 8601, o "". Nunca trae el código (FR-6a). */
        public final String linkingExpiresAt;
        /** Segundos que le quedaban al recibir la respuesta, o -1 si la pasarela no lo manda. */
        public final long linkingExpiresInS;
        /** SystemClock.elapsedRealtime() al recibir la respuesta, para contar sin depender de relojes. */
        public final long receivedElapsedMs;
        /** El número que usaría el código, o null: entonces la app lo pide (FR-6). */
        public final String linkNumber;

        public Status(String state, String me, String since, String linkingBy, String linkingExpiresAt,
                      long linkingExpiresInS, long receivedElapsedMs, String linkNumber) {
            this.state = state == null ? "" : state;
            this.me = me;
            this.since = since == null ? "" : since;
            this.linkingBy = linkingBy;
            this.linkingExpiresAt = linkingExpiresAt == null ? "" : linkingExpiresAt;
            this.linkingExpiresInS = linkingExpiresInS;
            this.receivedElapsedMs = receivedElapsedMs;
            this.linkNumber = linkNumber;
        }
    }

    /**
     * Lo que responde whatsapp.link: el código de vinculación, credencial de un solo uso (FR-6a).
     * Sólo vive en memoria mientras la pantalla lo muestra; no se guarda ni se escribe en el log.
     */
    final class LinkCode {
        public final String code;
        public final String expiresAt;
        /** Segundos que le quedaban al recibirlo, o -1 si la pasarela no lo manda. */
        public final long expiresInS;
        /** SystemClock.elapsedRealtime() al recibirlo. */
        public final long receivedElapsedMs;

        public LinkCode(String code, String expiresAt, long expiresInS, long receivedElapsedMs) {
            this.code = code == null ? "" : code;
            this.expiresAt = expiresAt == null ? "" : expiresAt;
            this.expiresInS = expiresInS;
            this.receivedElapsedMs = receivedElapsedMs;
        }

        @Override
        public String toString() {
            // Por si alguien lo concatena en un log: nunca el código.
            return "LinkCode{expiresAt=" + expiresAt + "}";
        }
    }

    /** Un hilo de whatsapp.threads. */
    final class ThreadSummary {
        public final String number;
        /** Del roster; null si no está. Nunca el push_name de WhatsApp. */
        public final String name;
        public final boolean inRoster;
        public final String lastAt;
        /** in u out. */
        public final String lastDir;
        /** null si es un archivo o si está oculto. */
        public final String lastExcerpt;
        /** text, image, audio, document…; null si está oculto. */
        public final String lastKind;
        public final boolean canReply;
        /**
         * Fuera del roster (FR-16a): sólo el número y la hora. Basta con hidden o con !inRoster:
         * aunque la respuesta traiga nombre, texto, archivo o can_reply, aquí no pasan.
         */
        public final boolean hidden;

        public ThreadSummary(String number, String name, boolean inRoster, String lastAt, String lastDir,
                             String lastExcerpt, String lastKind, boolean canReply, boolean hidden) {
            boolean off = hidden || !inRoster;
            this.number = number == null ? "" : number;
            this.name = off ? null : name;
            this.inRoster = inRoster && !hidden;
            this.lastAt = lastAt == null ? "" : lastAt;
            this.lastDir = lastDir == null ? "" : lastDir;
            this.lastExcerpt = off ? null : lastExcerpt;
            this.lastKind = off ? null : lastKind;
            this.canReply = !off && canReply;
            this.hidden = off;
        }
    }

    /** Un mensaje de whatsapp.thread o el que regresa whatsapp.send. */
    final class Message {
        public final String id;
        /** in u out. */
        public final String dir;
        /** agent, owner o api, en los que salen; "" en los que entran. */
        public final String by;
        public final String at;
        /** text, image, audio, document…; "" si está oculto. */
        public final String kind;
        /** null si es un archivo o si está oculto. */
        public final String text;
        /** sending, sent, delivered, read o failed, en los que salen; "" en los demás. */
        public final String status;
        /** Fuera del roster (FR-16a): sin text ni media. */
        public final boolean hidden;

        public Message(String id, String dir, String by, String at, String kind, String text, String status,
                       boolean hidden) {
            this.id = id == null ? "" : id;
            this.dir = dir == null ? "" : dir;
            this.by = by == null ? "" : by;
            this.at = at == null ? "" : at;
            this.kind = hidden || kind == null ? "" : kind;
            this.text = hidden ? null : text;
            this.status = status == null ? "" : status;
            this.hidden = hidden;
        }

        public boolean out() {
            return "out".equals(dir);
        }
    }

    /** Una página de whatsapp.threads o whatsapp.thread, del más reciente al más viejo. */
    final class Page<T> {
        public final List<T> items;
        /** Para pedir la siguiente página, o null si no hay más. */
        public final String nextBefore;

        public Page(List<T> items, String nextBefore) {
            this.items = Collections.unmodifiableList(items);
            this.nextBefore = nextBefore;
        }
    }

    /**
     * Lo que responde whatsapp.thread: el contacto y una página de mensajes. Fuera del roster
     * (FR-16a) no hay nombre, no se puede contestar y cada mensaje queda oculto, traiga lo que
     * traiga la respuesta.
     */
    final class Thread {
        public final String number;
        public final String name;
        public final boolean inRoster;
        public final boolean canReply;
        public final Page<Message> messages;

        public Thread(String number, String name, boolean inRoster, boolean canReply, Page<Message> messages) {
            this.number = number == null ? "" : number;
            this.name = inRoster ? name : null;
            this.inRoster = inRoster;
            this.canReply = inRoster && canReply;
            this.messages = inRoster ? messages : hideAll(messages);
        }

        private static Page<Message> hideAll(Page<Message> page) {
            List<Message> out = new ArrayList<>();
            for (Message m : page.items) {
                out.add(m.hidden ? m : new Message(m.id, m.dir, m.by, m.at, m.kind, m.text, m.status, true));
            }
            return new Page<>(out, page.nextBefore);
        }
    }

    /**
     * Un error de §12 (forbidden, unavailable, already_linked, not_allowed, not_started,
     * rate_limited, not_found, invalid, conflict…), o offline / timeout de la app.
     */
    final class Failure {
        public final String code;
        public final String field;
        public final int retryAfterS;

        public Failure(String code, String field, int retryAfterS) {
            this.code = code == null ? "" : code;
            this.field = field == null ? "" : field;
            this.retryAfterS = retryAfterS;
        }

        public static Failure of(String code) {
            return new Failure(code, "", 0);
        }
    }

    interface Callback<T> {
        void onResult(T result);

        void onError(Failure failure);
    }

    /** Si hay conexión y la pasarela anuncia whatsapp.status. */
    boolean available();

    /** Si la pasarela concedió whatsapp.admin a esta conexión (FR-6a): vincular, cancelar y desvincular. */
    boolean admin();

    /** Si la pasarela anuncia whatsapp.send: sin él, los hilos se ven sin cuadro de responder. */
    boolean canSend();

    void status(Callback<Status> cb);

    /** number puede ser null si status trajo link_number. */
    void link(String number, Callback<LinkCode> cb);

    void linkCancel(Callback<Void> cb);

    /** El resultado dice si WhatsApp confirmó el cierre de sesión (remote). */
    void unlink(Callback<Boolean> cb);

    void threads(String before, Callback<Page<ThreadSummary>> cb);

    void thread(String number, String before, Callback<Thread> cb);

    void send(String number, String text, Callback<Message> cb);
}
