package com.iaaorgmx.paynani.whatsapp;

/**
 * El código de vinculación de la pantalla «WhatsApp» (FR-6a): sólo existe mientras la pantalla
 * se ve. Si la respuesta de whatsapp.link llega después de salir de la pantalla, se descarta y
 * el código no vuelve a memoria.
 */
final class CodeSlot {

    private final Gate gate = new Gate();
    private WhatsAppSource.LinkCode code;
    private boolean busy;

    void open() {
        gate.open();
    }

    /** Al salir de la pantalla: olvida el código y la petición pendiente. */
    void close() {
        gate.close();
        code = null;
        busy = false;
    }

    /** Antes de whatsapp.link: el código anterior ya no sirve. */
    int request() {
        code = null;
        busy = true;
        return gate.next();
    }

    /** La respuesta de whatsapp.link. false si ya no le toca a la pantalla: no guarda nada. */
    boolean deliver(int token, WhatsAppSource.LinkCode result) {
        if (!gate.accepts(token)) return false;
        busy = false;
        code = result;
        return true;
    }

    /** Un error de whatsapp.link. false si ya no le toca a la pantalla. */
    boolean fail(int token) {
        if (!gate.accepts(token)) return false;
        busy = false;
        return true;
    }

    void clear() {
        code = null;
    }

    WhatsAppSource.LinkCode code() {
        return code;
    }

    boolean busy() {
        return busy;
    }
}
