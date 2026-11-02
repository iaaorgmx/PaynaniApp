package com.iaaorgmx.paynani.whatsapp;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * El aviso whatsapp.changed de la pasarela (SMS_GATEWAY.md §12): llega sin datos, sólo con
 * what (threads o status), y la pantalla que muestre WhatsApp vuelve a leer. Si se pierde, la
 * pantalla lo nota al volver a abrirse.
 */
public final class WhatsAppEvents {

    /** whatsapp.changed de los hilos. */
    public static final String THREADS = "threads";
    /** whatsapp.changed del estado de la cuenta. */
    public static final String STATUS = "status";
    /** No viene de la pasarela: se conectó o se perdió la conexión con ella. */
    public static final String CONNECTION = "connection";

    public interface Listener {
        /** En el hilo principal. what es THREADS, STATUS o CONNECTION. */
        void onChanged(String what);
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private WhatsAppEvents() {
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
