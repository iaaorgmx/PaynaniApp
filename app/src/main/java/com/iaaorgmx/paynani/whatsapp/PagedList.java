package com.iaaorgmx.paynani.whatsapp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Los hilos o los mensajes de un hilo, como se muestran, con la paginación de next_before. La
 * pasarela manda cada página del más reciente al más viejo; los mensajes se muestran al revés.
 *
 * Sin conexión, {@link #clear()} vacía todo e invalida las respuestas pendientes: una respuesta
 * que llegue tarde no vuelve a llenar la pantalla (§12: sin conexión no se muestra nada viejo).
 */
public final class PagedList<T> {

    private final boolean oldestFirst;
    private final Gate gate = new Gate();
    private final List<T> items = new ArrayList<>();
    private String nextBefore;
    private boolean loadingMore;

    /** oldestFirst: los mensajes de un hilo; si no, los hilos. */
    public PagedList(boolean oldestFirst) {
        this.oldestFirst = oldestFirst;
    }

    public void open() {
        gate.open();
    }

    /** Al salir de la pantalla: lo pendiente se ignora; lo mostrado se queda hasta volver. */
    public void close() {
        gate.close();
        loadingMore = false;
    }

    /** Sin conexión: nada de lo mostrado ni de lo pendiente sirve. */
    public void clear() {
        gate.invalidate();
        items.clear();
        nextBefore = null;
        loadingMore = false;
    }

    /** Ficha para leer desde el principio; sustituye a cualquier lectura pendiente. */
    public int reload() {
        loadingMore = false;
        return gate.next();
    }

    /** La primera página. false si ya no le toca a la pantalla. */
    public boolean first(int token, WhatsAppSource.Page<T> page) {
        if (!gate.accepts(token)) return false;
        items.clear();
        items.addAll(ordered(page.items));
        nextBefore = page.nextBefore;
        return true;
    }

    /** Ficha para la página siguiente, o -1 si no hay más o ya se está pidiendo. */
    public int more() {
        if (nextBefore == null || loadingMore || !gate.isOpen()) return -1;
        loadingMore = true;
        return gate.current();
    }

    /** La página siguiente. Devuelve cuántos elementos agregó, o -1 si ya no le toca. */
    public int append(int token, WhatsAppSource.Page<T> page) {
        if (!gate.accepts(token)) return -1;
        loadingMore = false;
        List<T> add = ordered(page.items);
        if (oldestFirst) items.addAll(0, add);
        else items.addAll(add);
        nextBefore = page.nextBefore;
        return add.size();
    }

    /** Falló la página siguiente: se puede volver a pedir. */
    public void moreFailed(int token) {
        if (gate.accepts(token)) loadingMore = false;
    }

    /** Si la ficha es la de la última lectura y la pantalla está abierta. */
    public boolean accepts(int token) {
        return gate.accepts(token);
    }

    public String nextBefore() {
        return nextBefore;
    }

    public List<T> items() {
        return Collections.unmodifiableList(items);
    }

    private List<T> ordered(List<T> newestFirst) {
        List<T> out = new ArrayList<>(newestFirst);
        if (oldestFirst) Collections.reverse(out);
        return out;
    }
}
