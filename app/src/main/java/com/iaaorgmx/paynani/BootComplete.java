package com.iaaorgmx.paynani;
//PaynaniApp
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.iaaorgmx.paynani.gateway.GatewayPrefs;

/**
 * APP-6 (REL-4): al encender el teléfono o al actualizar la app, arranca el
 * servicio en primer plano directamente, sin abrir ninguna pantalla. Sin
 * emparejamiento no arranca nada.
 */
public class BootComplete extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        try {
            if (!new GatewayPrefs(context).isPaired()) return;
            context.startForegroundService(new Intent(context, SMSService.class));
        } catch (Exception e) {
            L.e("BootComplete", "no arrancó el servicio: " + e);
        }
    }
}
