package com.iaaorgmx.paynani.messages;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.iaaorgmx.paynani.R;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Fechas y avatares de «Mensajes» (#83), como los muestra Google Messages. */
public final class Fmt {

    /** Colores de los avatares; cada número cae siempre en el mismo. */
    private static final int[] AVATAR = {
            0xFFE8833A, 0xFF4CAF7D, 0xFF8E6BD8, 0xFF3F8FD2, 0xFFD25E8A, 0xFF8F2D2B,
    };

    private Fmt() {}

    /** «5:52 a.m.» (o «17:52» si el teléfono usa 24 horas). */
    public static String time(Context context, long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        if (DateFormat.is24HourFormat(context)) {
            return String.format(Locale.ROOT, "%d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
        }
        int h = c.get(Calendar.HOUR);
        return String.format(Locale.ROOT, "%d:%02d ", h == 0 ? 12 : h, c.get(Calendar.MINUTE))
                + context.getString(c.get(Calendar.AM_PM) == Calendar.AM ? R.string.msg_am : R.string.msg_pm);
    }

    /** Lista: la hora si es de hoy, el día corto («lun») en los últimos 7 días, si no la fecha corta («3 oct»). */
    public static String listDate(Context context, long millis) {
        int days = daysAgo(millis);
        if (days == 0) return time(context, millis);
        if (days < 7) return pattern("EEE", millis);
        return pattern(sameYear(millis) ? "dMMM" : "dMMMy", millis);
    }

    /** Separador del hilo: «12:08 a.m.», «Ayer • 10:22 p.m.», «lun • …», «3 oct • …». */
    public static String separator(Context context, long millis) {
        int days = daysAgo(millis);
        String t = time(context, millis);
        if (days == 0) return t;
        String day;
        if (days == 1) day = context.getString(R.string.msg_yesterday);
        else if (days < 7) day = pattern("EEE", millis);
        else day = pattern(sameYear(millis) ? "dMMM" : "dMMMy", millis);
        return day + " • " + t;
    }

    private static String pattern(String skeleton, long millis) {
        Locale locale = Locale.getDefault();
        String p = DateFormat.getBestDateTimePattern(locale, skeleton);
        // ICU abrevia con punto («lun.», «oct.»); Messages no lo muestra.
        return new SimpleDateFormat(p, locale).format(new Date(millis)).replace(".", "");
    }

    private static int daysAgo(long millis) {
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(millis);
        startOfDay(then);
        Calendar today = Calendar.getInstance();
        startOfDay(today);
        long diff = today.getTimeInMillis() - then.getTimeInMillis();
        return (int) Math.round(diff / 86400000.0);
    }

    private static boolean sameYear(long millis) {
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(millis);
        return then.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR);
    }

    private static void startOfDay(Calendar c) {
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
    }

    /**
     * Círculo de color con la inicial del nombre; si sólo hay número, el ícono
     * de persona. El color sale del número, así que no cambia entre pantallas.
     */
    public static void avatar(TextView initial, ImageView person, String address, String name) {
        int color = AVATAR[Math.abs((address == null ? "" : address).hashCode() % AVATAR.length)];
        if (name != null && name.trim().isEmpty()) name = null;
        View circle = name != null ? initial : person;
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(color);
        circle.setBackground(bg);
        if (name != null) {
            int cp = name.trim().codePointAt(0);
            initial.setText(new String(Character.toChars(Character.toUpperCase(cp))));
            initial.setVisibility(View.VISIBLE);
            person.setVisibility(View.GONE);
        } else {
            initial.setVisibility(View.GONE);
            person.setVisibility(View.VISIBLE);
        }
    }
}
