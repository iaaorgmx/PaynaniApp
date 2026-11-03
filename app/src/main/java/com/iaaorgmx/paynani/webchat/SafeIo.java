package com.iaaorgmx.paynani.webchat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * El hilo de Webchat para leer y escribir archivos y decodificar fotos (#5). Una descarga puede
 * terminar después de que la pantalla se destruyó y apagó el executor: entonces el trabajo se
 * descarta, en lugar de lanzar RejectedExecutionException y cerrar la app.
 */
final class SafeIo {

    private final ExecutorService io;

    SafeIo() {
        this(Executors.newSingleThreadExecutor());
    }

    SafeIo(ExecutorService io) {
        this.io = io;
    }

    /** Corre `task` en el hilo de fondo; si ya se apagó, no hace nada. Devuelve si lo aceptó. */
    boolean run(Runnable task) {
        if (io.isShutdown()) return false;
        try {
            io.execute(task);
            return true;
        } catch (RejectedExecutionException ignored) {
            // Se apagó entre la revisión y execute.
            return false;
        }
    }

    void shutdown() {
        io.shutdownNow();
    }
}
