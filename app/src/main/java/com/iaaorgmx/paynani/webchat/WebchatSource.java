package com.iaaorgmx.paynani.webchat;


import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Webchat por la pasarela (paynani PRD #653): el dueño platica con su propio agente, en la misma
 * conversación que PaynaniWeb (WEBCHAT.md). webchat.thread y webchat.send por el WebSocket del
 * teléfono emparejado; los archivos por HTTP en la misma pasarela (FR-5), porque el WebSocket tiene
 * un tope de 256 KB por mensaje. La pantalla también se prueba con {@link DemoWebchatSource}.
 *
 * Las respuestas llegan en el hilo principal. Los errores son {@link Failure}.
 */
public interface WebchatSource {

    /** Un archivo de un mensaje (WEBCHAT.md §3): nunca una ruta, sólo su ref. */
    final class FileRef {
        public final int n;
        public final String name;
        /** image (se ve en la burbuja) o document (tarjeta para descargar). */
        public final String kind;
        public final long size;
        /** f_ y 32 hex. */
        public final String ref;

        public FileRef(int n, String name, String kind, long size, String ref) {
            this.n = n;
            this.name = name == null ? "" : name;
            this.kind = "image".equals(kind) ? "image" : "document";
            this.size = Math.max(0, size);
            this.ref = ref == null ? "" : ref;
        }

        public boolean image() {
            return "image".equals(kind);
        }
    }

    /**
     * La cita de una respuesta (paynani PRD #685 F2, #745 FR-12): el mensaje citado, quién lo
     * escribió y una línea. missing: ya no está, y la cita no lleva a ningún lado.
     */
    final class ReplyTo {
        /** El w_… citado, o "" si missing. */
        public final String ref;
        public final String name;
        public final String excerpt;
        public final boolean missing;

        public ReplyTo(String ref, String name, String excerpt, boolean missing) {
            this.ref = ref == null ? "" : ref;
            this.name = name == null ? "" : name;
            this.excerpt = excerpt == null ? "" : excerpt;
            this.missing = missing || this.ref.isEmpty();
        }
    }

    /** Una reacción de un mensaje (paynani PRD #685 F3, #745 FR-10): el emoji, cuántos y si es del dueño. */
    final class Reaction {
        public final String emoji;
        public final int count;
        public final boolean mine;

        public Reaction(String emoji, int count, boolean mine) {
            this.emoji = emoji == null ? "" : emoji;
            this.count = Math.max(1, count);
            this.mine = mine;
        }
    }

    /** Un mensaje de la conversación (WEBCHAT.md §3). */
    final class Message {
        /** w_ y 32 hex. */
        public final String id;
        /** in lo escribe el dueño, out el agente. */
        public final String dir;
        public final String at;
        public final String text;
        public final List<FileRef> files;
        /** La cita, o null si no es una respuesta. */
        public final ReplyTo replyTo;
        /** Las reacciones, en el orden de la pasarela; vacía si no hay. */
        public final List<Reaction> reactions;

        public Message(String id, String dir, String at, String text, List<FileRef> files) {
            this(id, dir, at, text, files, null, null);
        }

        public Message(String id, String dir, String at, String text, List<FileRef> files, ReplyTo replyTo,
                       List<Reaction> reactions) {
            this.id = id == null ? "" : id;
            this.dir = "out".equals(dir) ? "out" : "in";
            this.at = at == null ? "" : at;
            this.text = text == null ? "" : text;
            this.files = files == null ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(files));
            this.replyTo = replyTo;
            this.reactions = reactions == null ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(reactions));
        }

        /** Lo escribió el dueño (desde PaynaniWeb o desde aquí): va a la derecha. */
        public boolean mine() {
            return "in".equals(dir);
        }

        /** El mismo mensaje con otras reacciones (lo que responde webchat.react). */
        public Message withReactions(List<Reaction> reactions) {
            return new Message(id, dir, at, text, files, replyTo, reactions);
        }

        /** La reacción del dueño, o "" si no tiene (hay una por mensaje, FR-9). */
        public String myReaction() {
            for (Reaction r : reactions) if (r.mine) return r.emoji;
            return "";
        }
    }

    /** Lo que responde webchat.thread (FR-3). */
    final class Thread {
        /** Del más viejo al más reciente, como se muestran. */
        public final List<Message> messages;
        /** Para «Mensajes anteriores»; null si no hay más. */
        public final String nextBefore;
        public final boolean typing;
        /** La respuesta que el agente va escribiendo (Q325), o null. Es el texto completo. */
        public final String draft;
        /** El nombre que muestra el agente, o "". */
        public final String agentName;
        /** #745: si este paynani cita y reacciona; uno anterior no lo dice y es false. */
        public final boolean replies;
        public final boolean reactions;

        public Thread(List<Message> messages, String nextBefore, boolean typing, String draft, String agentName) {
            this(messages, nextBefore, typing, draft, agentName, false, false);
        }

        public Thread(List<Message> messages, String nextBefore, boolean typing, String draft, String agentName,
                      boolean replies, boolean reactions) {
            this.messages = WebchatRules.chronological(messages);
            this.nextBefore = nextBefore;
            this.typing = typing;
            this.draft = draft == null || draft.isEmpty() ? null : draft;
            this.agentName = agentName == null ? "" : agentName;
            this.replies = replies;
            this.reactions = reactions;
        }
    }

    /** Un error de la pasarela o de la conexión: su `code` (offline, forbidden, too_big…) y el campo. */
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

    /** Si hay conexión, la pasarela anuncia webchat.thread y este teléfono tiene webchat.owner (FR-1). */
    boolean available();

    /** Si hay conexión con paynani, aunque no haya Webchat. */
    boolean connected();

    /**
     * Si este paynani guarda citas (#745 FR-1, FR-14), según el último webchat.thread: sin eso no
     * hay «Reply» ni deslizar.
     */
    boolean canReply();

    /**
     * Si este paynani reacciona (#745 FR-1, FR-14): webchat.react en el welcome y reactions en el
     * último webchat.thread. Sin eso no sale la fila de reacciones.
     */
    boolean canReact();

    /**
     * webchat.thread: con after, sólo lo posterior a ese id (para refrescar); con before, lo
     * anterior (para «Mensajes anteriores»); sin los dos, los últimos.
     */
    void thread(String after, String before, Callback<Thread> cb);

    /**
     * Sube un archivo (FR-5, POST /sms/webchat/upload) y devuelve su up_…: de un solo uso, vence a
     * los 10 minutos y sólo se consume cuando el mensaje se guarda (SMS_GATEWAY.md §13).
     */
    void upload(String name, String mime, byte[] data, Callback<String> cb);

    /** webchat.send (FR-4): texto, los up_… ya subidos y el w_… que cita (#745 FR-3), o null. */
    void send(String text, List<String> uploads, String replyTo, Callback<Message> cb);

    /**
     * webchat.react (#745 FR-4): la reacción del dueño a ref; "" la quita. Responde las reacciones
     * del mensaje como quedaron.
     */
    void react(String ref, String emoji, Callback<List<Reaction>> cb);

    /** Los bytes de un archivo de la conversación (FR-5). */
    void file(String ref, Callback<byte[]> cb);
}
