package com.iaaorgmx.paynani.telegram;

import com.iaaorgmx.paynani.whatsapp.WhatsAppSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Telegram de la pasarela (paynani TELEGRAM.md §6, PRD #609 FR-29a): telegram.status,
 * telegram.threads, telegram.thread y telegram.send por el WebSocket del teléfono emparejado. La
 * app no configura el token, no ve ids de Telegram y no agrega al roster. La pantalla también se
 * prueba con {@link DemoTelegramSource}.
 *
 * Las páginas usan {@link WhatsAppSource.Page}: es sólo «elementos y cursor», y así la lista y el
 * hilo reutilizan PagedList. Los mensajes de una página van del más reciente al más viejo, como en
 * WhatsApp (TELEGRAM.md §4).
 *
 * Las respuestas llegan en el hilo principal.
 */
public interface TelegramSource {

    /** Lo que responde telegram.status. Nunca trae el token ni ids. */
    final class Status {
        /** starting, running, offline, conflict, token_invalid, journal_mismatch, stopped o service_down. */
        public final String state;
        /** El @usuario del bot, sin la @, o "". */
        public final String botUsername;

        public Status(String state, String botUsername) {
            this.state = state == null ? "" : state;
            this.botUsername = botUsername == null ? "" : botUsername;
        }
    }

    /** Un hilo de telegram.threads: una persona. */
    final class ThreadSummary {
        /** Id opaco del hilo (t_ + 32 hex). Nunca un id de Telegram. */
        public final String thread;
        /** Del roster, o el que la persona puso con «(no verificado)»; null si oculto. */
        public final String name;
        public final boolean inRoster;
        public final String lastAt;
        /** in u out. */
        public final String lastDir;
        /** null si es un archivo o si está oculto. */
        public final String lastExcerpt;
        /** text, image, video, audio, voice, document, sticker, location, contact u other (TELEGRAM.md §3); null si está oculto. */
        public final String lastKind;
        public final boolean canReply;
        /**
         * Fuera del roster sin el Catch-All de Telegram (TELEGRAM.md §4): sólo la hora. Basta con
         * hidden: aunque la respuesta traiga nombre, texto, archivo o can_reply, aquí no pasan.
         */
        public final boolean hidden;

        public ThreadSummary(String thread, String name, boolean inRoster, String lastAt, String lastDir,
                             String lastExcerpt, String lastKind, boolean canReply, boolean hidden) {
            this.thread = thread == null ? "" : thread;
            this.name = hidden ? null : name;
            this.inRoster = inRoster && !hidden;
            this.lastAt = lastAt == null ? "" : lastAt;
            this.lastDir = lastDir == null ? "" : lastDir;
            this.lastExcerpt = hidden ? null : lastExcerpt;
            this.lastKind = hidden ? null : lastKind;
            this.canReply = !hidden && inRoster && canReply;
            this.hidden = hidden;
        }
    }

    /** Un mensaje de telegram.thread o el que regresa telegram.send. */
    final class Message {
        /** tg:… si entró, tgo:… si salió. */
        public final String ref;
        /** in u out. */
        public final String dir;
        /** agent, owner, api o phone:…, en los que salen; "" en los que entran. */
        public final String by;
        public final String at;
        /** text, image, video, audio, voice, document, sticker, location, contact u other; "" si está oculto. */
        public final String kind;
        /** null si es un archivo sin texto o si está oculto. */
        public final String text;
        /** sending, sent o failed, en los que salen (Telegram no da «entregado» ni «leído»). */
        public final String status;
        /** La persona lo editó; text es el último. */
        public final boolean edited;
        public final boolean hidden;

        public Message(String ref, String dir, String by, String at, String kind, String text, String status,
                       boolean edited, boolean hidden) {
            this.ref = ref == null ? "" : ref;
            this.dir = dir == null ? "" : dir;
            this.by = by == null ? "" : by;
            this.at = at == null ? "" : at;
            this.kind = hidden || kind == null ? "" : kind;
            this.text = hidden ? null : text;
            this.status = status == null ? "" : status;
            this.edited = !hidden && edited;
            this.hidden = hidden;
        }

        public boolean out() {
            return "out".equals(dir);
        }
    }

    /**
     * Lo que responde telegram.thread. Fuera del roster (o hidden) no hay nombre, no se puede
     * contestar y cada mensaje queda oculto, traiga lo que traiga la respuesta.
     */
    final class Thread {
        public final String thread;
        public final String name;
        public final boolean inRoster;
        public final boolean canReply;
        public final boolean hidden;
        public final WhatsAppSource.Page<Message> messages;

        public Thread(String thread, String name, boolean inRoster, boolean canReply, boolean hidden,
                      WhatsAppSource.Page<Message> messages) {
            boolean off = hidden || !inRoster;
            this.thread = thread == null ? "" : thread;
            this.name = hidden ? null : name;
            this.inRoster = inRoster && !hidden;
            this.canReply = !off && canReply;
            this.hidden = hidden;
            this.messages = hidden ? hideAll(messages) : messages;
        }

        private static WhatsAppSource.Page<Message> hideAll(WhatsAppSource.Page<Message> page) {
            List<Message> out = new ArrayList<>();
            for (Message m : page.items) {
                out.add(m.hidden ? m : new Message(m.ref, m.dir, m.by, m.at, m.kind, m.text, m.status, m.edited, true));
            }
            return new WhatsAppSource.Page<>(out, page.nextBefore);
        }
    }

    /** Si hay conexión y la pasarela anuncia telegram.threads. */
    boolean available();

    /** Si hay conexión con paynani, aunque la pasarela no anuncie Telegram (no configurado). */
    default boolean connected() {
        return available();
    }

    /** Si la pasarela anuncia telegram.send: sin él, los hilos se ven sin cuadro de responder. */
    boolean canSend();

    void status(WhatsAppSource.Callback<Status> cb);

    void threads(String before, WhatsAppSource.Callback<WhatsAppSource.Page<ThreadSummary>> cb);

    void thread(String thread, String before, WhatsAppSource.Callback<Thread> cb);

    void send(String thread, String text, WhatsAppSource.Callback<Message> cb);
}
