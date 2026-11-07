package com.iaaorgmx.paynani.webchat;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * El aviso webchat.changed de la pasarela (paynani PRD #653 FR-6): what es messages, typing o
 * draft, y llega a toda conexión con el grant webchat.owner aunque la pantalla no esté abierta,
 * para la notificación (FR-11). Con draft trae además el texto del borrador. También avisa cuando
 * se conecta o se pierde la conexión con la pasarela.
 */
public final class WebchatEvents {

    public static final String MESSAGES = "messages";
    public static final String TYPING = "typing";
    public static final String DRAFT = "draft";
    /** paynani #745 FR-5: cambió una reacción, quizá de un mensaje viejo. */
    public static final String REACTIONS = "reactions";
    /** No viene de la pasarela: se conectó o se perdió la conexión con ella. */
    public static final String CONNECTION = "connection";

    public interface Listener {
        /** En el hilo principal. draft sólo con what DRAFT (null si ya no hay borrador). */
        void onChanged(String what, String draft);
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private WebchatEvents() {
    }

    public static void add(Listener l) {
        LISTENERS.addIfAbsent(l);
    }

    public static void remove(Listener l) {
        LISTENERS.remove(l);
    }

    /** Desde el hilo de la pasarela. */
    public static void changed(String what, String draft) {
        MAIN.post(() -> {
            for (Listener l : LISTENERS) l.onChanged(what, draft);
        });
    }
}
