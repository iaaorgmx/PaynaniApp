package com.iaaorgmx.paynani.gateway;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

import com.iaaorgmx.paynani.L;

import org.json.JSONObject;

/**
 * device.status: la batería del teléfono (PaynaniApp#109, SMS_GATEWAY.md sección 3).
 * Se lee en el Timer del servicio y al conectar o desconectar la corriente; sale sólo
 * cuando cambia el porcentaje o la carga, o cada 10 minutos para que el dato no envejezca.
 */
public final class DeviceStatus {

    public static final String TYPE = "device.status";
    /** Aunque no cambie, una lectura cada 10 minutos (la pasarela la da por vieja a los 20). */
    public static final long REFRESH_MS = 10 * 60_000L;
    /** Tras conectar o desconectar, Android tarda un momento en cambiar el estado de carga. */
    public static final long POWER_DELAY_MS = 3_000L;

    private static final String TAG = "DeviceStatus";

    private DeviceStatus() {}

    /** Una lectura de la batería. */
    public static final class Reading {
        public final int pct;
        public final boolean charging;
        public final String plugged;
        /** Grados Celsius, o NaN si Android no la da. */
        public final double tempC;
        public final long measuredAt;

        public Reading(int pct, boolean charging, String plugged, double tempC, long measuredAt) {
            this.pct = pct;
            this.charging = charging;
            this.plugged = plugged;
            this.tempC = tempC;
            this.measuredAt = measuredAt;
        }
    }

    /** id de device.status: sha256("device.status\n" + measured_at). */
    public static String id(long measuredMs) {
        return Protocol.sha256Hex(TYPE + "\n" + measuredMs);
    }

    /** El porcentaje entero de 0 a 100, o -1 si Android no dio nivel o escala. */
    public static int pct(int level, int scale) {
        if (level < 0 || scale <= 0) return -1;
        return Math.max(0, Math.min(100, Math.round(level * 100f / scale)));
    }

    /** EXTRA_PLUGGED como lo pide el protocolo: ac, usb, wireless o none. */
    public static String plugged(int plugged) {
        switch (plugged) {
            case BatteryManager.BATTERY_PLUGGED_AC: return "ac";
            case BatteryManager.BATTERY_PLUGGED_USB: return "usb";
            case BatteryManager.BATTERY_PLUGGED_WIRELESS: return "wireless";
            default: return "none";
        }
    }

    /** Cargando: Android dice que carga o que ya está llena con la corriente puesta. */
    public static boolean charging(int status) {
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
    }

    /** EXTRA_TEMPERATURE viene en décimas de grado; NaN si no la hay. */
    public static double tempC(int tenths) {
        return tenths == Integer.MIN_VALUE ? Double.NaN : tenths / 10.0;
    }

    /**
     * Si esta lectura sale: la primera, la que cambia porcentaje o carga, y la que
     * llega {@link #REFRESH_MS} después de la última que salió.
     */
    public static boolean shouldSend(long lastSentMs, int lastPct, boolean lastCharging,
                                     int pct, boolean charging, long nowMs) {
        if (lastSentMs <= 0) return true;
        if (pct != lastPct || charging != lastCharging) return true;
        return nowMs - lastSentMs >= REFRESH_MS || nowMs < lastSentMs;
    }

    /** La lectura del broadcast pegajoso de la batería, o null si no hay. */
    public static Reading read(Context context, long nowMs) {
        Intent i = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (i == null) return null;
        int pct = pct(i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), i.getIntExtra(BatteryManager.EXTRA_SCALE, -1));
        if (pct < 0) return null;
        return new Reading(pct, charging(i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)),
                plugged(i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)),
                tempC(i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE)), nowMs);
    }

    /** Los campos de device.status sin type ni id: así van también en hello.device_status. */
    public static JSONObject fields(Reading r) throws Exception {
        JSONObject o = new JSONObject();
        o.put("battery_pct", r.pct);
        o.put("charging", r.charging);
        o.put("plugged", r.plugged);
        if (!Double.isNaN(r.tempC)) o.put("battery_temp_c", r.tempC);
        o.put("measured_at", r.measuredAt);
        return o;
    }

    /**
     * Lee la batería, la guarda como la última y, si toca y la pasarela acepta
     * device.status, la encola en lugar de la que aún no salió. force: al conectar o
     * desconectar la corriente sale siempre. Fuera del hilo principal.
     */
    public static synchronized void report(Context context, boolean force) {
        try {
            GatewayPrefs prefs = new GatewayPrefs(context);
            if (!prefs.isPaired()) return;
            long now = System.currentTimeMillis();
            // measured_at en la hora de la pasarela (welcome.server_time): con el reloj del
            // teléfono mal puesto, la pasarela la rechazaría (SMS_GATEWAY.md sección 3).
            Reading r = read(context, now + prefs.getClockOffsetMs());
            if (r == null) return;
            JSONObject f = fields(r);
            prefs.setDeviceStatus(f);
            if (!prefs.accepts(TYPE)) return;
            if (!force && !shouldSend(prefs.getBatterySentAt(), prefs.getBatterySentPct(),
                    prefs.getBatterySentCharging(), r.pct, r.charging, now)) return;
            JSONObject m = new JSONObject(f.toString());
            String id = id(r.measuredAt);
            m.put("type", TYPE);
            m.put("id", id);
            // Sólo cuenta la más nueva: la pasarela descarta las viejas (ack duplicate).
            GatewayQueue.get(context).removeType(TYPE);
            GatewayClient.enqueue(context, id, TYPE, m);
            prefs.setBatterySent(now, r.pct, r.charging);
            L.d(TAG, r.pct + " % " + (r.charging ? "cargando" : "sin cargar") + " (" + r.plugged + ")"
                    + (force ? ", corriente" : ""));
        } catch (Exception e) {
            L.e(TAG, "report: " + e);
        }
    }
}
