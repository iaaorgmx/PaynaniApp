package com.iaaorgmx.paynani.webchat;

import com.iaaorgmx.paynani.whatsapp.WhatsAppSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Webchat por la pasarela (paynani PRD #653): el dueño platica con su propio agente, en la misma
 * conversación que PaynaniWeb (WEBCHAT.md). webchat.thread y webchat.send por el WebSocket del
 * teléfono emparejado; los archivos por HTTP en la misma pasarela (FR-5), porque el WebSocket tiene
 * un tope de 256 KB por mensaje. La pantalla también se prueba con {@link DemoWebchatSource}.
 *
 * Las respuestas llegan en el hilo principal. Los errores son {@link WhatsAppSource.Failure}.
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

    /** Un mensaje de la conversación (WEBCHAT.md §3). */
    final class Message {
        /** w_ y 32 hex. */
        public final String id;
        /** in lo escribe el dueño, out el agente. */
        public final String dir;
        public final String at;
        public final String text;
        public final List<FileRef> files;

        public Message(String id, String dir, String at, String text, List<FileRef> files) {
            this.id = id == null ? "" : id;
            this.dir = "out".equals(dir) ? "out" : "in";
            this.at = at == null ? "" : at;
            this.text = text == null ? "" : text;
            this.files = files == null ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(files));
        }

        /** Lo escribió el dueño (desde PaynaniWeb o desde aquí): va a la derecha. */
        public boolean mine() {
            return "in".equals(dir);
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

        public Thread(List<Message> messages, String nextBefore, boolean typing, String draft, String agentName) {
            this.messages = WebchatRules.chronological(messages);
            this.nextBefore = nextBefore;
            this.typing = typing;
            this.draft = draft == null || draft.isEmpty() ? null : draft;
            this.agentName = agentName == null ? "" : agentName;
        }
    }

    /** Si hay conexión, la pasarela anuncia webchat.thread y este teléfono tiene webchat.owner (FR-1). */
    boolean available();

    /** Si hay conexión con paynani, aunque no haya Webchat. */
    boolean connected();

    /**
     * webchat.thread: con after, sólo lo posterior a ese id (para refrescar); con before, lo
     * anterior (para «Mensajes anteriores»); sin los dos, los últimos.
     */
    void thread(String after, String before, WhatsAppSource.Callback<Thread> cb);

    /**
     * Sube un archivo (FR-5, POST /sms/webchat/upload) y devuelve su up_…: de un solo uso, vence a
     * los 10 minutos y sólo se consume cuando el mensaje se guarda (SMS_GATEWAY.md §13).
     */
    void upload(String name, String mime, byte[] data, WhatsAppSource.Callback<String> cb);

    /** webchat.send (FR-4): texto y los up_… ya subidos. */
    void send(String text, List<String> uploads, WhatsAppSource.Callback<Message> cb);

    /** Los bytes de un archivo de la conversación (FR-5). */
    void file(String ref, WhatsAppSource.Callback<byte[]> cb);
}
