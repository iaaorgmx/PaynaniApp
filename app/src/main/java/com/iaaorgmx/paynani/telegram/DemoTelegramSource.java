package com.iaaorgmx.paynani.telegram;

import android.os.Handler;
import android.os.Looper;

import com.iaaorgmx.paynani.whatsapp.WhatsAppSource.Callback;
import com.iaaorgmx.paynani.whatsapp.WhatsAppSource.Failure;
import com.iaaorgmx.paynani.whatsapp.WhatsAppSource.Page;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Datos de prueba para las pantallas de Telegram, sin pasarela: sólo en depuración, con la
 * preferencia telegram_demo (como whatsapp_demo). Personas y bot de ficción.
 */
public final class DemoTelegramSource implements TelegramSource {

    private static final String ANA = "t_00000000000000000000000000000001";
    private static final String LUIS = "t_00000000000000000000000000000002";
    private static final String PEDRO = "t_00000000000000000000000000000003";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final long delayMs;
    private final String state;
    /** Del más reciente al más viejo, como las páginas de TelegramSource. */
    private final Map<String, List<Message>> threads = new LinkedHashMap<>();
    private int sent;

    public DemoTelegramSource(long delayMs, String state) {
        this.delayMs = delayMs;
        this.state = state == null ? "running" : state;
        threads.put(ANA, new ArrayList<>(Arrays.asList(
                new Message("tgo:demo1", "out", "agent", ago(12), "text", "Sí, a las 8 tenemos lugar.", "sent", false, false),
                new Message("tg:demo1", "in", "", ago(15), "text", "¿Tienen mesa para 4 a las 8?", "", true, false))));
        threads.put(LUIS, new ArrayList<>(Arrays.asList(
                new Message("tg:demo2", "in", "", ago(60), "image", "El ticket de ayer", "", false, false))));
        threads.put(PEDRO, new ArrayList<>(Arrays.asList(
                new Message("tg:demo3", "in", "", ago(90), "", null, "", false, true))));
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public boolean canSend() {
        return true;
    }

    @Override
    public void status(Callback<Status> cb) {
        later(() -> cb.onResult(new Status(state, "PaynaniDemoBot")));
    }

    @Override
    public void threads(String before, Callback<Page<ThreadSummary>> cb) {
        List<ThreadSummary> out = new ArrayList<>();
        for (Map.Entry<String, List<Message>> e : threads.entrySet()) {
            Message last = e.getValue().get(0);
            boolean hidden = PEDRO.equals(e.getKey());
            out.add(new ThreadSummary(e.getKey(), name(e.getKey()), !hidden, last.at, last.dir, last.text,
                    last.kind, ANA.equals(e.getKey()), hidden));
        }
        later(() -> cb.onResult(new Page<>(out, null)));
    }

    @Override
    public void thread(String thread, String before, Callback<Thread> cb) {
        List<Message> messages = threads.get(thread);
        if (messages == null) {
            later(() -> cb.onError(Failure.of("not_found")));
            return;
        }
        boolean hidden = PEDRO.equals(thread);
        later(() -> cb.onResult(new Thread(thread, name(thread), !hidden, ANA.equals(thread), hidden,
                new Page<>(new ArrayList<>(messages), null))));
    }

    @Override
    public void send(String thread, String text, Callback<Message> cb) {
        if (!ANA.equals(thread)) {
            later(() -> cb.onError(Failure.of(PEDRO.equals(thread) ? "not_allowed" : "not_started")));
            return;
        }
        Message m = new Message("tgo:demo_" + (++sent), "out", "phone", Instant.now().toString(), "text", text,
                "sent", false, false);
        threads.get(thread).add(0, m);
        later(() -> {
            cb.onResult(m);
            TelegramEvents.changed(TelegramEvents.THREADS);
        });
    }

    private static String name(String thread) {
        if (ANA.equals(thread)) return "Ana López";
        if (LUIS.equals(thread)) return "Luis Pérez";
        return null;
    }

    private static String ago(int minutes) {
        return Instant.now().minusSeconds(minutes * 60L).toString();
    }

    private void later(Runnable r) {
        main.postDelayed(r, delayMs);
    }
}
