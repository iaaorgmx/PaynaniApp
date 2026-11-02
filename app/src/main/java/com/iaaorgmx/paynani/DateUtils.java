package com.iaaorgmx.paynani;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

/** Fechas de la bitácora (#104). Sin Context: los textos llegan como parámetros. */
public final class DateUtils {

    private DateUtils() {
    }

    /** Días completos de date1 a today. */
    public static int calculateDays(Date date1, Date today) {
        long diff = today.getTime() - date1.getTime();
        // Como siempre: el (int) va antes de dividir (#104 no cambia el comportamiento).
        return (int) diff / (1000 * 60 * 60 * 24);
    }

    /**
     * «Hoy, 3:05 PM», «Ayer, 3:05 PM» o «12 oct, 2026, 3:05 PM» para un
     * timestamp «yyyy-MM-dd HH:mm:ss». Si no se puede leer, devuelve «, ».
     */
    public static String formatDate(String timestamp, String todayLabel,
                                    String yesterdayLabel, String datePattern) {
        return formatDate(timestamp, todayLabel, yesterdayLabel, datePattern, new Date());
    }

    static String formatDate(String timestamp, String todayLabel, String yesterdayLabel,
                             String datePattern, Date now) {
        String dat = "";
        String tim = "";

        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        SimpleDateFormat df2 = new SimpleDateFormat("yyyy-MM-dd");
        String stToday = df2.format(now);

        try {
            Date fullDate = df.parse(timestamp);
            Date date1 = df2.parse(timestamp);
            Date today = df2.parse(stToday);

            switch (calculateDays(date1, today)) {
                case 0:
                    dat = todayLabel;
                    break;
                case 1:
                    dat = yesterdayLabel;
                    break;
                default:
                    df.applyPattern(datePattern);
                    dat = df.format(date1);
                    break;
            }

            df.applyPattern("h:mm a");
            tim = df.format(fullDate);
        } catch (ParseException e) {
            // Se queda vacío, como antes.
        }

        return dat + ", " + tim;
    }
}
