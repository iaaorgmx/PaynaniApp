package com.iaaorgmx.paynani.telegram;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * El aviso telegram.changed de la pasarela (TELEGRAM.md §6): llega sin datos, sólo con what
 * (threads o status), y la pantalla que muestre Telegram vuelve a leer. También avisa cuando se
 * conecta o se pierde la conexión con la pasarela.
 */
public final class TelegramEvents {

    /** telegram.changed de los hilos. */
    public static final String THREADS = "threads";
    /** telegram.changed del estado del bot. */
    public static final String STATUS = "status";
    /** No viene de la pasarela: se conectó o se perdió la conexión con ella. */
    public static final String CONNECTION = "connection";

    public interface Listener {
        /** En el hilo principal. what es THREADS, STATUS o CONNECTION. */
        void onChanged(String what);
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private TelegramEvents() {
    }

    public static void add(Listener l) {
        LISTENERS.addIfAbsent(l);
    }

    public static void remove(Listener l) {
        LISTENERS.remove(l);
    }

    /** Desde el hilo de la pasarela. */
    public static void changed(String what) {
        MAIN.post(() -> {
            for (Listener l : LISTENERS) l.onChanged(what);
        });
    }
}
