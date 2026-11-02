package com.iaaorgmx.paynani;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Fechas de la bitácora con vectores fijos (#104). */
public class DateUtilsTest {

    private Locale locale;
    private TimeZone zone;

    @Before
    public void fixLocaleAndZone() {
        locale = Locale.getDefault();
        zone = TimeZone.getDefault();
        Locale.setDefault(Locale.US);
        TimeZone.setDefault(TimeZone.getTimeZone("America/Mexico_City"));
    }

    @After
    public void restore() {
        Locale.setDefault(locale);
        TimeZone.setDefault(zone);
    }

    private static Date at(String timestamp) throws Exception {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(timestamp);
    }

    private static String format(String timestamp, String pattern, String now) throws Exception {
        return DateUtils.formatDate(timestamp, "Today", "Yesterday", pattern, at(now));
    }

    @Test
    public void today() throws Exception {
        assertEquals("Today, 3:05 PM", format("2026-10-15 15:05:00", "MMM dd, yyyy", "2026-10-15 23:47:00"));
        assertEquals("Today, 12:00 AM", format("2026-10-15 00:00:00", "MMM dd, yyyy", "2026-10-15 23:59:59"));
    }

    @Test
    public void yesterday() throws Exception {
        assertEquals("Yesterday, 11:46 PM", format("2026-10-14 23:46:44", "MMM dd, yyyy", "2026-10-15 00:10:00"));
    }

    @Test
    public void olderUsesThePattern() throws Exception {
        assertEquals("Oct 12, 2026, 8:00 AM", format("2026-10-12 08:00:00", "MMM dd, yyyy", "2026-10-15 09:00:00"));
        assertEquals("12 Oct, 2026, 8:00 AM", format("2026-10-12 08:00:00", "dd MMM, yyyy", "2026-10-15 09:00:00"));
    }

    @Test
    public void unreadableTimestampGivesOnlyTheComma() throws Exception {
        assertEquals(", ", format("", "MMM dd, yyyy", "2026-10-15 09:00:00"));
        assertEquals(", ", format("15/10/2026", "MMM dd, yyyy", "2026-10-15 09:00:00"));
    }

    @Test
    public void calculateDays() throws Exception {
        assertEquals(0, DateUtils.calculateDays(at("2026-10-15 00:00:00"), at("2026-10-15 00:00:00")));
        assertEquals(1, DateUtils.calculateDays(at("2026-10-14 00:00:00"), at("2026-10-15 00:00:00")));
        assertEquals(7, DateUtils.calculateDays(at("2026-10-08 00:00:00"), at("2026-10-15 00:00:00")));
        assertEquals(0, DateUtils.calculateDays(at("2026-10-15 08:00:00"), at("2026-10-15 23:00:00")));
    }
}
