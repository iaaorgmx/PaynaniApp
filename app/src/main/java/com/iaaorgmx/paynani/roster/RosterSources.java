package com.iaaorgmx.paynani.roster;

import android.content.Context;

import com.iaaorgmx.paynani.BuildConfig;

/**
 * De dónde lee «Roster»: la pasarela (SMS_GATEWAY.md §11). En un build de depuración, con la
 * preferencia roster_demo, {@link DemoRosterSource} con datos de prueba (paynani #416).
 */
public final class RosterSources {

    private static DemoRosterSource demo;

    private RosterSources() {
    }

    /** Si el menú muestra «Roster»: la pasarela anuncia roster.list (R-1), o el modo de prueba. */
    public static boolean menuVisible(Context context) {
        return GatewayRosterSource.announced(context) || demoEnabled(context);
    }

    public static synchronized RosterSource get(Context context) {
        if (!demoEnabled(context)) return new GatewayRosterSource(context);
        if (demo == null) demo = new DemoRosterSource(400);
        return demo;
    }

    /** Sólo en depuración, con la preferencia roster_demo en paynani_debug.xml. */
    static boolean demoEnabled(Context context) {
        return BuildConfig.DEBUG && context.getSharedPreferences("paynani_debug", Context.MODE_PRIVATE)
                .getBoolean("roster_demo", false);
    }
}
