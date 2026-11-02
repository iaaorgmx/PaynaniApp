package com.iaaorgmx.paynani.gateway;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Números de teléfono en E.164, con las reglas de SMS_GATEWAY.md («Números de
 * teléfono»). Es la misma lógica que harness/phone.py en paynani, y las dos
 * corren la misma tabla de casos (PhoneTest): si una cambia, cambia la otra.
 */
public final class Phone {

    public static final String DEFAULT_REGION = "MX";

    private Phone() {}

    /** {@code raw} en E.164, o null si no es un número (alfanumérico o código corto). */
    public static String toE164(String raw, String region) {
        if (raw == null) return null;
        String text = raw.trim();
        if (text.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || c == ' ' || c == '-' || c == '.' || c == '(' || c == ')') continue;
            sb.append(c);
        }
        String compact = sb.toString();
        if (compact.startsWith("00")) compact = "+" + compact.substring(2);
        boolean plus = compact.startsWith("+");
        String digits = plus ? compact.substring(1) : compact;
        if (digits.isEmpty()) return null;
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') return null; // sólo dígitos ASCII
        }
        String r = (region == null || region.trim().isEmpty() ? DEFAULT_REGION : region.trim()).toUpperCase(Locale.ROOT);

        String number;
        if (plus) {
            if (digits.startsWith("0")) return null; // ningún código de país empieza con 0
            number = digits;
        } else if (digits.length() <= 6) {
            return null; // código corto
        } else if (r.equals("MX") && digits.length() == 10) {
            number = "52" + digits;
        } else if ((r.equals("US") || r.equals("CA")) && digits.length() == 10) {
            number = "1" + digits;
        } else if ((r.equals("US") || r.equals("CA")) && digits.length() == 11 && digits.startsWith("1")) {
            number = digits;
        } else if (r.equals("MX") && digits.length() == 12 && digits.startsWith("52")) {
            number = digits;
        } else {
            return null; // sin "+" y sin regla de la región: no se adivina
        }
        if (number.startsWith("521") && number.length() == 13) {
            number = "52" + number.substring(3); // el "1" viejo de los móviles de México
        }
        if (number.length() < 8 || number.length() > 15) return null;
        return "+" + number;
    }

    /** Los números de una celda separados por coma. */
    public static List<String> splitCell(String cell) {
        List<String> out = new ArrayList<>();
        if (cell == null) return out;
        for (String part : cell.split(",")) {
            String p = part.trim();
            if (!p.isEmpty()) out.add(p);
        }
        return out;
    }
}
