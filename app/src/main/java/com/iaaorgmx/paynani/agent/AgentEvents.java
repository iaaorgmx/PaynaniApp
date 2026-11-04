package com.iaaorgmx.paynani.agent;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Avisos del agente en el hilo principal: `agent.changed` de la pasarela (paynani #692) con el
 * estado nuevo, y CONNECTION cuando la conexión se abre o se corta.
 */
public final class AgentEvents {

    public static final String CONNECTION = "connection";

    public interface Listener {
        /** `status` es el estado nuevo, o CONNECTION. */
        void onAgentChanged(String status);
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AgentEvents() {}

    public static void add(Listener l) { LISTENERS.addIfAbsent(l); }

    public static void remove(Listener l) { LISTENERS.remove(l); }

    public static void changed(String status) {
        MAIN.post(() -> {
            for (Listener l : LISTENERS) l.onAgentChanged(status);
        });
    }
}
