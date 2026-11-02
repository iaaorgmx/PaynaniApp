package com.iaaorgmx.paynani.whatsapp;

import android.content.Context;

import com.iaaorgmx.paynani.BuildConfig;

/**
 * De dónde leen las pantallas de WhatsApp: la pasarela (SMS_GATEWAY.md §12). En un build de
 * depuración, con la preferencia whatsapp_demo, {@link DemoWhatsAppSource} con datos de prueba.
 */
public final class WhatsAppSources {

    private static DemoWhatsAppSource demo;

    private WhatsAppSources() {
    }

    /** Si el menú muestra «WhatsApp»: la pasarela anuncia whatsapp.status (FR-8), o el modo de prueba. */
    public static boolean sectionVisible(Context context) {
        return GatewayWhatsAppSource.statusAnnounced(context) || demoEnabled(context);
    }

    /** Si el menú muestra «WhatsApp messages»: la pasarela anuncia whatsapp.threads (FR-25), o el modo de prueba. */
    public static boolean messagesVisible(Context context) {
        return GatewayWhatsAppSource.threadsAnnounced(context) || demoEnabled(context);
    }

    public static synchronized WhatsAppSource get(Context context) {
        if (!demoEnabled(context)) return new GatewayWhatsAppSource(context);
        if (demo == null) demo = new DemoWhatsAppSource(400, demoAdmin(context));
        return demo;
    }

    /** Sólo en depuración, con la preferencia whatsapp_demo en paynani_debug.xml. */
    static boolean demoEnabled(Context context) {
        return BuildConfig.DEBUG && context.getSharedPreferences("paynani_debug", Context.MODE_PRIVATE)
                .getBoolean("whatsapp_demo", false);
    }

    /** En el modo de prueba, whatsapp_demo_admin=false simula una conexión sin whatsapp.admin. */
    private static boolean demoAdmin(Context context) {
        return context.getSharedPreferences("paynani_debug", Context.MODE_PRIVATE)
                .getBoolean("whatsapp_demo_admin", true);
    }
}
