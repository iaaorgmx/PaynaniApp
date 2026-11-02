package com.iaaorgmx.paynani.telegram;

import android.content.Context;

import com.iaaorgmx.paynani.BuildConfig;

/**
 * De dónde leen las pantallas de Telegram: la pasarela (TELEGRAM.md §6). En un build de
 * depuración, con la preferencia telegram_demo, {@link DemoTelegramSource} con datos de prueba.
 */
public final class TelegramSources {

    private static DemoTelegramSource demo;

    private TelegramSources() {
    }

    /** Si el menú muestra «Mensajes de Telegram»: la pasarela anuncia telegram.threads, o el modo de prueba. */
    public static boolean messagesVisible(Context context) {
        return GatewayTelegramSource.threadsAnnounced(context) || demoEnabled(context);
    }

    public static synchronized TelegramSource get(Context context) {
        if (!demoEnabled(context)) return new GatewayTelegramSource(context);
        if (demo == null) demo = new DemoTelegramSource(400, demoState(context));
        return demo;
    }

    /** Sólo en depuración, con la preferencia telegram_demo en paynani_debug.xml. */
    static boolean demoEnabled(Context context) {
        return BuildConfig.DEBUG && context.getSharedPreferences("paynani_debug", Context.MODE_PRIVATE)
                .getBoolean("telegram_demo", false);
    }

    /** En el modo de prueba, telegram_demo_state (p. ej. conflict) simula otro estado del bot. */
    private static String demoState(Context context) {
        return context.getSharedPreferences("paynani_debug", Context.MODE_PRIVATE)
                .getString("telegram_demo_state", "running");
    }
}
