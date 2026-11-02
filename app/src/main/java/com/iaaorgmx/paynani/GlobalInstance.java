package com.iaaorgmx.paynani;
//PaynaniApp

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.iaaorgmx.paynani.gateway.GatewayClient;
import com.iaaorgmx.paynani.webchat.WebchatNotifier;

/**
 * Application de la app. Sin servicio en primer plano (PaynaniApp-Agentic#175, Q342 = B):
 * la conexión con la pasarela vive mientras alguna pantalla de la app está a la vista, y
 * se cierra STOP_DELAY_MS después de la última. El margen cubre girar la pantalla y elegir
 * un archivo con otra app para Webchat.
 */
public class GlobalInstance extends Application {

    static final long STOP_DELAY_MS = 60_000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private int started = 0;
    private final Runnable stop = () -> {
        GatewayClient.get(this).stop();
        WebchatNotifier.stop();
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Leftovers.clean(this);
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity activity) {
                if (started++ == 0) foreground();
            }

            @Override public void onActivityStopped(Activity activity) {
                if (--started == 0) main.postDelayed(stop, STOP_DELAY_MS);
            }

            @Override public void onActivityCreated(Activity activity, Bundle state) {}
            @Override public void onActivityResumed(Activity activity) {}
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
            @Override public void onActivityDestroyed(Activity activity) {}
        });
    }

    /** Una pantalla de la app quedó a la vista: conecta, si hay emparejamiento vigente. */
    private void foreground() {
        main.removeCallbacks(stop);
        if (!new SessionManager(this).isLoggedIn()) return;
        GatewayClient.get(this).start();
        // Webchat (paynani PRD #653 FR-11): avisa si el agente contesta y su pantalla no está abierta.
        WebchatNotifier.start(this);
    }

    /** Recién emparejada, con la app a la vista: conecta sin esperar otra pantalla. */
    public static void paired(Activity activity) {
        ((GlobalInstance) activity.getApplication()).foreground();
    }
}
