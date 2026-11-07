package com.iaaorgmx.paynani.webchat;

import android.content.Context;

import com.iaaorgmx.paynani.BuildConfig;

/**
 * De dónde lee la pantalla de Webchat: la pasarela (paynani PRD #653). En un build de depuración,
 * con la preferencia webchat_demo, {@link DemoWebchatSource} con una conversación de prueba.
 */
public final class WebchatSources {

    private static DemoWebchatSource demo;

    private WebchatSources() {
    }

    /** Si el menú muestra la sección «AI Agent» con Webchat (Q324): FR-1, o el modo de prueba. */
    public static boolean menuVisible(Context context) {
        return GatewayWebchatSource.announced(context) || demoEnabled(context);
    }

    public static synchronized WebchatSource get(Context context) {
        if (!demoEnabled(context)) return new GatewayWebchatSource(context);
        if (demo == null) demo = new DemoWebchatSource(400);
        return demo;
    }

    /** Sólo en depuración, con la preferencia webchat_demo en paynani_debug.xml. */
    public static boolean demoEnabled(Context context) {
        return BuildConfig.DEBUG && context.getSharedPreferences("paynani_debug", Context.MODE_PRIVATE)
                .getBoolean("webchat_demo", false);
    }
}
