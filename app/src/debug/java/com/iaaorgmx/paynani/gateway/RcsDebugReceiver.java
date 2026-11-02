package com.iaaorgmx.paynani.gateway;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.iaaorgmx.paynani.L;

/**
 * Sólo en debug (#55, A3): simula una notificación para RcsWatcher.posted y corre
 * RcsWatcher.check, porque desde otra app no se puede publicar una notificación
 * con el paquete de Google Messages. No existe en el APK de release.
 *
 *   adb shell am broadcast -n com.iaaorgmx.paynani/.gateway.RcsDebugReceiver \
 *       --es pkg com.google.android.apps.messaging --es key k1 --el when 123 \
 *       --el posted_ago_ms 61000 --es title "Ana" [--ez summary true] [--es category msg] [--ez check true]
 *   adb shell am broadcast -n com.iaaorgmx.paynani/.gateway.RcsDebugReceiver --ei sim_slot 1
 */
public class RcsDebugReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        PendingResult result = goAsync();
        new Thread(() -> {
            try {
                Context app = context.getApplicationContext();
                if (intent.hasExtra("sim_slot")) {
                    // La SIM elegida en APP-10 (-1 = todas), para probar la otra SIM.
                    new GatewayPrefs(app).setSimSlot(intent.getIntExtra("sim_slot", -1));
                    L.d("RcsDebug", "sim_slot -> " + new GatewayPrefs(app).getSimSlot());
                }
                if (intent.hasExtra("key")) {
                    long posted = System.currentTimeMillis() - intent.getLongExtra("posted_ago_ms", 0);
                    boolean tracked = RcsWatcher.posted(app, intent.getStringExtra("pkg"),
                            intent.getBooleanExtra("summary", false), intent.getBooleanExtra("ongoing", false),
                            intent.getStringExtra("category"), intent.getStringExtra("key"),
                            intent.getLongExtra("when", posted), posted, intent.getStringExtra("title"));
                    L.d("RcsDebug", "posted -> " + tracked);
                }
                if (intent.getBooleanExtra("check", false)) {
                    RcsWatcher.check(app);
                    L.d("RcsDebug", "check hecho");
                }
            } finally {
                result.finish();
            }
        }).start();
    }
}
