package com.iaaorgmx.paynani.webchat;

/**
 * Decide si una respuesta de la pasarela todavía le toca a la pantalla. Cada petición se lleva una
 * ficha; la respuesta sólo se usa si la ficha sigue siendo la última y la pantalla está abierta.
 * Cerrar (pausa, sin conexión) invalida todas las fichas pendientes.
 */
public final class Gate {

    private int token;
    private boolean open;

    /** La pantalla se ve: abre una vuelta nueva; nada de la anterior se acepta. */
    public void open() {
        open = true;
        token++;
    }

    /** La pantalla deja de verse o se pierde la conexión: nada pendiente se acepta. */
    public void close() {
        open = false;
        token++;
    }

    /** Invalida lo pendiente sin cerrar (otra petición la sustituye). */
    public void invalidate() {
        token++;
    }

    /** Ficha para una petición nueva que sustituye a las anteriores. */
    public int next() {
        return ++token;
    }

    /** Ficha para una petición que continúa la última (la página siguiente). */
    public int current() {
        return token;
    }

    public boolean isOpen() {
        return open;
    }

    public boolean accepts(int t) {
        return open && t == token;
    }
}
