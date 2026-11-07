package com.iaaorgmx.paynani.webchat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Las reglas de la pantalla de Webchat (paynani PRD #653, WEBCHAT.md §3), sin Android, para
 * probarlas con JUnit: el orden, qué se puede mandar y cuándo poner un separador de día.
 */
public final class WebchatRules {

    /** WEBCHAT.md §3, «Límites». */
    public static final int MAX_TEXT = 4000;
    public static final long MAX_FILE = 20L * 1024 * 1024;
    public static final long MAX_TOTAL = 25L * 1024 * 1024;
    /** SMS_GATEWAY.md §13: hasta 10 uploads por mensaje. */
    public static final int MAX_FILES = 10;

    /** Por qué no se puede agregar o mandar algo; los mismos códigos que el almacén. */
    public static final String EMPTY = "empty";
    public static final String TOO_LONG = "too_long";
    public static final String TOO_BIG = "too_big";
    public static final String BAD_TYPE = "bad_type";
    public static final String TOO_MANY = "too_many";

    /** Las extensiones que acepta el almacén y su tipo MIME. La pasarela vuelve a revisar los bytes. */
    private static final Map<String, String> TYPES = new LinkedHashMap<>();

    static {
        TYPES.put("jpg", "image/jpeg");
        TYPES.put("jpeg", "image/jpeg");
        TYPES.put("png", "image/png");
        TYPES.put("webp", "image/webp");
        TYPES.put("gif", "image/gif");
        TYPES.put("pdf", "application/pdf");
        TYPES.put("txt", "text/plain");
        TYPES.put("csv", "text/csv");
        TYPES.put("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        TYPES.put("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        TYPES.put("zip", "application/zip");
    }

    private static final List<String> IMAGES = Arrays.asList("jpg", "jpeg", "png", "webp", "gif");
    /** El ref de un archivo, como lo valida la web antes de file_path() (WEBCHAT.md §4). */
    private static final Pattern REF = Pattern.compile("^f_[0-9a-f]{32}$");

    private WebchatRules() {
    }

    /** La extensión en minúsculas, o "". */
    public static String extension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** El MIME de un nombre aceptado, o null si su tipo no se acepta. */
    public static String mime(String name) {
        return TYPES.get(extension(name));
    }

    /** Si es un ref de archivo válido. Con otro no se pide ni se escribe nada. */
    public static boolean validRef(String ref) {
        return ref != null && REF.matcher(ref).matches();
    }

    /**
     * La extensión para guardar el archivo en el teléfono: sólo una de los tipos aceptados, o bin.
     * Nunca el texto del nombre tal cual, que podría traer diagonales («a.x/../../y»).
     */
    public static String safeExtension(String name) {
        String ext = extension(name);
        return TYPES.containsKey(ext) ? ext : "bin";
    }

    public static boolean isImage(String name) {
        return IMAGES.contains(extension(name));
    }

    /** null si el archivo se puede adjuntar; si no, bad_type o too_big. */
    public static String fileProblem(String name, long size) {
        if (mime(name) == null) return BAD_TYPE;
        if (size > MAX_FILE) return TOO_BIG;
        return null;
    }

    /** null si se puede mandar; si no, empty, too_long o too_big (los archivos juntos pasan de 25 MB). */
    public static String sendProblem(String text, List<Long> sizes) {
        String t = text == null ? "" : text;
        boolean noFiles = sizes == null || sizes.isEmpty();
        if (blank(t) && noFiles) return EMPTY;
        // Como el almacén (Python): caracteres, no unidades UTF-16; un emoji cuenta 1.
        if (t.codePointCount(0, t.length()) > MAX_TEXT) return TOO_LONG;
        long total = 0;
        if (sizes != null) for (long s : sizes) total += s;
        if (total > MAX_TOTAL) return TOO_BIG;
        if (sizes != null && sizes.size() > MAX_FILES) return TOO_MANY;
        return null;
    }

    /**
     * Del más viejo al más reciente por su hora, sin depender del orden en que vengan. Dos con la
     * misma hora conservan el orden en que llegaron.
     */
    public static List<WebchatSource.Message> chronological(List<WebchatSource.Message> in) {
        List<WebchatSource.Message> out = in == null ? new ArrayList<>() : new ArrayList<>(in);
        Collections.sort(out, (a, b) -> Long.compare(parseIso(a.at), parseIso(b.at)));
        return Collections.unmodifiableList(out);
    }

    /**
     * Agrega a lo que ya se ve lo que llegó (por after, o un webchat.send): sin repetir ningún id, y
     * en orden.
     */
    public static List<WebchatSource.Message> merge(List<WebchatSource.Message> shown,
                                                    List<WebchatSource.Message> incoming) {
        Map<String, WebchatSource.Message> byId = new LinkedHashMap<>();
        if (shown != null) for (WebchatSource.Message m : shown) byId.put(m.id, m);
        if (incoming != null) for (WebchatSource.Message m : incoming) byId.put(m.id, m);
        return chronological(new ArrayList<>(byId.values()));
    }

    /** El id del último mensaje, para pedir sólo lo posterior (after), o null. */
    public static String lastId(List<WebchatSource.Message> messages) {
        return messages == null || messages.isEmpty() ? null : messages.get(messages.size() - 1).id;
    }

    /** Si va un separador de día antes del mensaje en position. */
    public static boolean newDay(List<WebchatSource.Message> messages, int position, ZoneId zone) {
        if (position == 0) return true;
        LocalDate prev = day(messages.get(position - 1).at, zone);
        LocalDate here = day(messages.get(position).at, zone);
        return here != null && !here.equals(prev);
    }

    public static LocalDate day(String iso, ZoneId zone) {
        long ms = parseIso(iso);
        return ms <= 0 ? null : Instant.ofEpochMilli(ms).atZone(zone).toLocalDate();
    }

    /**
     * Los mensajes del agente que llegaron después de lastSeen (el id del último que el dueño vio),
     * para la notificación (FR-11). Sin lastSeen, ninguno: no se avisa de lo viejo.
     */
    public static List<WebchatSource.Message> unseenFromAgent(List<WebchatSource.Message> messages, String lastSeen) {
        List<WebchatSource.Message> out = new ArrayList<>();
        if (messages == null || lastSeen == null) return out;
        boolean after = false;
        for (WebchatSource.Message m : messages) {
            if (after && !m.mine()) out.add(m);
            if (m.id.equals(lastSeen)) after = true;
        }
        // lastSeen ya no está (otra página): todo lo que llegó por after es nuevo.
        if (!after) for (WebchatSource.Message m : messages) if (!m.mine()) out.add(m);
        return out;
    }

    /** El texto de una burbuja o de la notificación: el texto, o los nombres de los archivos. */
    public static String preview(WebchatSource.Message m) {
        if (!m.text.trim().isEmpty()) return m.text;
        List<String> names = new ArrayList<>();
        for (WebchatSource.FileRef f : m.files) names.add(f.name);
        return String.join(", ", names);
    }

    /** Sólo espacios, también los de Unicode (como str.strip() de Python). */
    public static boolean blank(String text) {
        if (text == null) return true;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (!Character.isWhitespace(cp) && !Character.isSpaceChar(cp)) return false;
            i += Character.charCount(cp);
        }
        return true;
    }

    /** Milisegundos de una fecha ISO 8601, o 0. */
    public static long parseIso(String iso) {
        if (iso == null || iso.isEmpty()) return 0;
        try {
            return Instant.parse(iso).toEpochMilli();
        } catch (DateTimeParseException e) {
            return 0;
        }
    }

    // --- Citas y reacciones (paynani #745) ------------------------------------------------------

    /** La fila del menú (FR-9): los mismos 7 de PaynaniWeb, en su orden (web/msgmenu.py ROW). */
    public static final List<String> QUICK_REACTIONS = Collections.unmodifiableList(
            Arrays.asList("👍", "👌", "❤️", "👀", "🔥", "😭", "👎"));

    /**
     * Lo que se manda a webchat.react al elegir picked (FR-9): una reacción del dueño por mensaje,
     * así que elegir la que ya tiene la quita ("") y otra la reemplaza.
     */
    public static String reactionChoice(String current, String picked) {
        if (picked == null || picked.isEmpty()) return "";
        return picked.equals(current) ? "" : picked;
    }

    /**
     * Si s es un solo emoji, para el «+» (FR-9): una bandera (dos indicadores regionales), un keycap
     * (1️⃣), o un símbolo con lo que se le puede pegar: selector de variación, tono de piel,
     * etiquetas y otros símbolos unidos con ZWJ. Se revisa a mano y no con BreakIterator, que no
     * junta igual los tonos de piel en todas las versiones de Java. La pasarela lo vuelve a revisar
     * (bad_emoji); esto sólo evita mandar texto.
     */
    public static boolean singleEmoji(String s) {
        if (s == null) return false;
        int[] cp = s.trim().codePoints().toArray();
        if (cp.length == 0) return false;
        if (regional(cp[0])) return cp.length == 2 && regional(cp[1]);
        if (cp[0] == '#' || cp[0] == '*' || (cp[0] >= '0' && cp[0] <= '9')) {
            return (cp.length == 2 && cp[1] == 0x20E3) || (cp.length == 3 && cp[1] == 0xFE0F && cp[2] == 0x20E3);
        }
        if (!pictograph(cp[0])) return false;
        for (int i = 1; i < cp.length; i++) {
            int c = cp[i];
            if (c == 0xFE0F || skinTone(c) || (c >= 0xE0020 && c <= 0xE007F)) continue;
            if (c == 0x200D && i + 1 < cp.length && pictograph(cp[i + 1])) {
                i++;
                continue;
            }
            return false;
        }
        return true;
    }

    private static boolean regional(int c) {
        return c >= 0x1F1E6 && c <= 0x1F1FF;
    }

    private static boolean skinTone(int c) {
        return c >= 0x1F3FB && c <= 0x1F3FF;
    }

    /** Un símbolo que puede ser la base de un emoji: no letra, dígito, espacio ni puntuación. */
    private static boolean pictograph(int c) {
        if (skinTone(c) || regional(c) || c == 0x200D || c == 0xFE0F) return false;
        int type = Character.getType(c);
        return type == Character.OTHER_SYMBOL || type == Character.MATH_SYMBOL && c > 0x2000
                || (c >= 0x1F000 && c <= 0x1FAFF) || (c >= 0x2600 && c <= 0x27BF);
    }

    /** La posición del mensaje id en messages, o -1 si no está cargado (FR-12). */
    public static int indexOf(List<WebchatSource.Message> messages, String id) {
        if (messages == null || id == null || id.isEmpty()) return -1;
        for (int i = 0; i < messages.size(); i++) if (id.equals(messages.get(i).id)) return i;
        return -1;
    }

    /**
     * Una línea de un mensaje para la barra de cita antes de mandar (FR-11): la primera línea del
     * texto, o el nombre del primer archivo entre corchetes, como el extracto del almacén.
     */
    public static String excerpt(WebchatSource.Message m) {
        if (m == null) return "";
        String text = m.text.trim();
        if (!text.isEmpty()) {
            int nl = text.indexOf('\n');
            return nl < 0 ? text : text.substring(0, nl).trim();
        }
        return m.files.isEmpty() ? "" : "[" + m.files.get(0).name + "]";
    }
}
