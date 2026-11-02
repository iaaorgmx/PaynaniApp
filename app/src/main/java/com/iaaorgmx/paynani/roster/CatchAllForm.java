package com.iaaorgmx.paynani.roster;

import java.nio.charset.StandardCharsets;

/**
 * Lo que la pantalla «Catch-All» revisa antes de mandar catchall.save (paynani PRD #535 FR-5,
 * FR-6). La pasarela vuelve a revisarlo; esto sólo evita un viaje para decir lo mismo.
 */
public final class CatchAllForm {

    /** El tope de rules.py para un archivo de reglas, cabecera incluida; se deja margen para ella. */
    public static final int MAX_BYTES = 32 * 1024 - 256;

    public static final String OK = "";
    public static final String EMPTY = "empty";
    public static final String TOO_LARGE = "too_large";

    private CatchAllForm() {
    }

    /** OK, EMPTY (activo sin instrucciones; sólo espacios cuenta como vacío) o TOO_LARGE. */
    public static String problem(boolean enabled, String text) {
        String t = text == null ? "" : text;
        if (bytes(t) > MAX_BYTES) return TOO_LARGE;
        if (enabled && t.trim().isEmpty()) return EMPTY;
        return OK;
    }

    public static int bytes(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }

    /** Si lo que hay en pantalla es distinto de lo que se leyó. */
    public static boolean changed(boolean loadedEnabled, String loadedText, boolean enabled, String text) {
        return loadedEnabled != enabled || !(loadedText == null ? "" : loadedText).equals(text == null ? "" : text);
    }
}
