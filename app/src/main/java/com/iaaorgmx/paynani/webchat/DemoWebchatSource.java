package com.iaaorgmx.paynani.webchat;

import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import com.iaaorgmx.paynani.webchat.WebchatSource.Callback;
import com.iaaorgmx.paynani.webchat.WebchatSource.Failure;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Una conversación de prueba para la pantalla de Webchat, sin pasarela: sólo en depuración, con la
 * preferencia webchat_demo. Al mandar algo, el «agente» escribe (typing), va mostrando el borrador
 * (Q325) y al final contesta, con los mismos avisos que la pasarela (FR-6).
 */
public final class DemoWebchatSource implements WebchatSource {

    /** Un PNG de 48×48 a cuadros, para la foto de ejemplo. */
    private static final String PNG = "iVBORw0KGgoAAAANSUhEUgAAADAAAAAwCAIAAADYYG7QAAAAU0lEQVR42u3WsQkAIAwEwMwjOKsz2ohjuEAsxSIHKQO56vMxektnr5nO6/0AAgIqB/p1+LYPBARUDySpgYCA9CGvAwhIH5LUQEBA+pDXAQRUGnQAdNMfcTGYPEgAAAAASUVORK5CYII=";

    /** Refs con el formato real (f_ y 32 hex), para que pasen la misma validación. */
    private static final String PHOTO = "f_00000000000000000000000000000001";
    private static final String PDF = "f_00000000000000000000000000000002";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final long delayMs;
    private final List<Message> messages = new ArrayList<>();
    private final Map<String, byte[]> files = new HashMap<>();
    private final Map<String, FileRef> uploads = new HashMap<>();
    private boolean typing;
    private String draft;
    private int seq;

    public DemoWebchatSource(long delayMs) {
        this.delayMs = delayMs;
        files.put(PHOTO, Base64.decode(PNG, Base64.DEFAULT));
        files.put(PDF, "%PDF-1.4\n%demo\n".getBytes());
        messages.add(new Message("w_demo1", "in", ago(26 * 60), "¿Cómo vamos con los pedidos de hoy?", null));
        messages.add(new Message("w_demo2", "out", ago(25 * 60),
                "Van 12 pedidos y 2 pendientes de pago. Te mando el resumen.",
                Arrays.asList(new FileRef(0, "resumen.pdf", "document", 15, PDF))));
        messages.add(new Message("w_demo3", "in", ago(3), "Gracias. ¿Y esta foto del ticket?",
                Collections.singletonList(new FileRef(0, "ticket.png", "image", 70, PHOTO))));
        messages.add(new Message("w_demo4", "out", ago(2), "Es el ticket del pedido 1042, ya quedó pagado.", null,
                new ReplyTo("w_demo3", "Julian", "Gracias. ¿Y esta foto del ticket?", false),
                Collections.singletonList(new Reaction("👍", 1, true))));
    }

    @Override
    public boolean canReply() {
        return true;
    }

    @Override
    public boolean canReact() {
        return true;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public boolean connected() {
        return true;
    }

    @Override
    public void thread(String after, String before, Callback<Thread> cb) {
        List<Message> out = new ArrayList<>();
        boolean take = after == null;
        for (Message m : messages) {
            if (take) out.add(m);
            if (m.id.equals(after)) take = true;
        }
        Thread t = new Thread(out, null, typing, draft, "Andy");
        later(() -> cb.onResult(t));
    }

    @Override
    public void upload(String name, String mime, byte[] data, Callback<String> cb) {
        String problem = WebchatRules.fileProblem(name, data.length);
        if (problem != null) {
            later(() -> cb.onError(Failure.of(problem)));
            return;
        }
        String ref = String.format(java.util.Locale.ROOT, "f_%032x", 0x1000 + (++seq));
        files.put(ref, data);
        uploads.put(ref, new FileRef(uploads.size(), name, WebchatRules.isImage(name) ? "image" : "document",
                data.length, ref));
        later(() -> cb.onResult(ref));
    }

    @Override
    public void send(String text, List<String> refs, String replyTo, Callback<Message> cb) {
        List<FileRef> attached = new ArrayList<>();
        if (refs != null) for (String r : refs) if (uploads.containsKey(r)) attached.add(uploads.remove(r));
        List<Long> sizes = new ArrayList<>();
        for (FileRef f : attached) sizes.add(f.size);
        String problem = WebchatRules.sendProblem(text, sizes);
        if (problem != null) {
            later(() -> cb.onError(Failure.of(problem)));
            return;
        }
        Message m = new Message("w_demo_in" + (++seq), "in", Instant.now().toString(), text, attached,
                quote(replyTo), null);
        messages.add(m);
        later(() -> {
            cb.onResult(m);
            WebchatEvents.changed(WebchatEvents.MESSAGES, null);
            reply();
        });
    }

    @Override
    public void file(String ref, Callback<byte[]> cb) {
        byte[] data = files.get(ref);
        later(() -> {
            if (data == null) cb.onError(Failure.of("not_found"));
            else cb.onResult(data);
        });
    }

    @Override
    public void react(String ref, String emoji, Callback<List<Reaction>> cb) {
        for (int i = 0; i < messages.size(); i++) {
            Message m = messages.get(i);
            if (!m.id.equals(ref)) continue;
            // Como el almacén: una reacción del dueño por mensaje; "" la quita.
            List<Reaction> out = new ArrayList<>();
            for (Reaction r : m.reactions) {
                if (!r.mine) out.add(r);
                else if (r.count > 1) out.add(new Reaction(r.emoji, r.count - 1, false));
            }
            if (emoji != null && !emoji.isEmpty()) {
                boolean merged = false;
                for (int j = 0; j < out.size(); j++) {
                    if (out.get(j).emoji.equals(emoji)) {
                        out.set(j, new Reaction(emoji, out.get(j).count + 1, true));
                        merged = true;
                    }
                }
                if (!merged) out.add(new Reaction(emoji, 1, true));
            }
            messages.set(i, m.withReactions(out));
            later(() -> {
                cb.onResult(out);
                WebchatEvents.changed(WebchatEvents.REACTIONS, null);
            });
            return;
        }
        later(() -> cb.onError(Failure.of("not_found")));
    }

    /** La cita de un w_… del demo, como la arma el almacén; un id que ya no está queda missing. */
    private ReplyTo quote(String ref) {
        if (ref == null || ref.isEmpty()) return null;
        for (Message m : messages) {
            if (!m.id.equals(ref)) continue;
            String excerpt = m.text.isEmpty() && !m.files.isEmpty() ? "[" + m.files.get(0).name + "]" : m.text;
            return new ReplyTo(m.id, m.mine() ? "Julian" : "Andy", excerpt, false);
        }
        return new ReplyTo(null, "", "", true);
    }

    /** El «agente»: escribe, va mostrando el borrador y contesta. */
    private void reply() {
        String answer = "Listo, lo reviso y te aviso en cuanto tenga la respuesta.";
        main.postDelayed(() -> {
            typing = true;
            WebchatEvents.changed(WebchatEvents.TYPING, null);
        }, 2000);
        String[] words = answer.split(" ");
        for (int i = 1; i <= words.length; i++) {
            String partial = String.join(" ", Arrays.copyOf(words, i));
            main.postDelayed(() -> {
                draft = partial;
                WebchatEvents.changed(WebchatEvents.DRAFT, partial);
            }, 4000 + i * 250L);
        }
        main.postDelayed(() -> {
            typing = false;
            draft = null;
            messages.add(new Message("w_demo_out" + (++seq), "out", Instant.now().toString(), answer, null));
            WebchatEvents.changed(WebchatEvents.DRAFT, null);
            WebchatEvents.changed(WebchatEvents.MESSAGES, null);
        }, 4400 + words.length * 250L);
    }

    private static String ago(int minutes) {
        return Instant.now().minusSeconds(minutes * 60L).toString();
    }

    private void later(Runnable r) {
        main.postDelayed(r, delayMs);
    }
}
