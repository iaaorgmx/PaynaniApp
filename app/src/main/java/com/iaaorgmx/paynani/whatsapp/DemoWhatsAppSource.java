package com.iaaorgmx.paynani.whatsapp;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Datos de prueba para las pantallas de WhatsApp, sin pasarela: sólo en depuración, con la
 * preferencia whatsapp_demo (como roster_demo, paynani #416). Los números son de los reservados
 * para ficción (555-01xx).
 */
public final class DemoWhatsAppSource implements WhatsAppSource {

    private static final String OWNER = "+12105550100";
    private static final String ANA = "+12105550101";
    private static final String LUIS = "+12105550102";
    private static final String UNKNOWN = "+12105550199";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final long delayMs;
    private final boolean admin;

    private String state = "unlinked";
    private String me;
    private String linkingExpiresAt = "";
    private final Map<String, List<Message>> threads = new LinkedHashMap<>();
    private int sent;

    public DemoWhatsAppSource(long delayMs, boolean admin) {
        this.delayMs = delayMs;
        this.admin = admin;
        threads.put(ANA, new ArrayList<>(Arrays.asList(
                new Message("wo_demo1", "out", "agent", ago(12), "text", "Sí, a las 8 tenemos lugar.", "read", false),
                new Message("wa:demo:1", "in", "", ago(15), "text", "Hola, ¿tienen mesa para 4 hoy?", "", false))));
        threads.put(LUIS, new ArrayList<>(Arrays.asList(
                new Message("wa:demo:2", "in", "", ago(60), "image", null, "", false))));
        threads.put(UNKNOWN, new ArrayList<>(Arrays.asList(
                new Message("wa:demo:3", "in", "", ago(90), "", null, "", true))));
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public boolean admin() {
        return admin;
    }

    @Override
    public boolean canSend() {
        return true;
    }

    @Override
    public void status(Callback<Status> cb) {
        long left = linkingExpiresAt.isEmpty() ? -1
                : Math.max(0, (Instant.parse(linkingExpiresAt).toEpochMilli() - System.currentTimeMillis()) / 1000);
        later(() -> cb.onResult(new Status(state, me, ago(5), "linking".equals(state) ? "phone" : null,
                linkingExpiresAt, left, SystemClock.elapsedRealtime(), OWNER)));
    }

    @Override
    public void link(String number, Callback<LinkCode> cb) {
        if (!admin) {
            later(() -> cb.onError(Failure.of("forbidden")));
            return;
        }
        if ("connected".equals(state)) {
            later(() -> cb.onError(Failure.of("already_linked")));
            return;
        }
        state = "linking";
        linkingExpiresAt = Instant.now().plusSeconds(160).toString();
        String expires = linkingExpiresAt;
        later(() -> {
            cb.onResult(new LinkCode("DEMO-7K2Q", expires, 160, SystemClock.elapsedRealtime()));
            // Como si el dueño hubiera metido el código en WhatsApp a los pocos segundos.
            main.postDelayed(() -> {
                if (!"linking".equals(state)) return;
                state = "connected";
                me = number == null ? OWNER : number;
                linkingExpiresAt = "";
                WhatsAppEvents.changed("status");
            }, 20_000);
        });
    }

    @Override
    public void linkCancel(Callback<Void> cb) {
        if (!admin) {
            later(() -> cb.onError(Failure.of("forbidden")));
            return;
        }
        if ("linking".equals(state)) state = "unlinked";
        linkingExpiresAt = "";
        later(() -> cb.onResult(null));
    }

    @Override
    public void unlink(Callback<Boolean> cb) {
        if (!admin) {
            later(() -> cb.onError(Failure.of("forbidden")));
            return;
        }
        state = "unlinked";
        me = null;
        later(() -> cb.onResult(true));
    }

    @Override
    public void threads(String before, Callback<Page<ThreadSummary>> cb) {
        List<ThreadSummary> out = new ArrayList<>();
        for (Map.Entry<String, List<Message>> e : threads.entrySet()) {
            Message last = e.getValue().get(0);
            boolean hidden = UNKNOWN.equals(e.getKey());
            out.add(new ThreadSummary(e.getKey(), name(e.getKey()), !hidden, last.at, last.dir, last.text,
                    last.kind, ANA.equals(e.getKey()), hidden));
        }
        later(() -> cb.onResult(new Page<>(out, null)));
    }

    @Override
    public void thread(String number, String before, Callback<Thread> cb) {
        List<Message> messages = threads.get(number);
        if (messages == null) {
            later(() -> cb.onError(Failure.of("not_found")));
            return;
        }
        boolean hidden = UNKNOWN.equals(number);
        later(() -> cb.onResult(new Thread(number, name(number), !hidden, ANA.equals(number),
                new Page<>(new ArrayList<>(messages), null))));
    }

    @Override
    public void send(String number, String text, Callback<Message> cb) {
        if (!ANA.equals(number)) {
            later(() -> cb.onError(Failure.of(UNKNOWN.equals(number) ? "not_allowed" : "not_started")));
            return;
        }
        Message m = new Message("wo_demo_" + (++sent), "out", "owner", Instant.now().toString(), "text", text,
                "sending", false);
        threads.get(number).add(0, m);
        later(() -> cb.onResult(m));
    }

    private static String name(String number) {
        if (ANA.equals(number)) return "Ana López";
        if (LUIS.equals(number)) return "Luis Pérez";
        return null;
    }

    private static String ago(int minutes) {
        return Instant.now().minusSeconds(minutes * 60L).toString();
    }

    private void later(Runnable r) {
        main.postDelayed(r, delayMs);
    }
}
