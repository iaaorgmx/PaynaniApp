package com.iaaorgmx.paynani;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Ajustes de la app en SharedPreferences «loginPrefs_» (#104). Las llaves no
 * cambian: una app que se actualiza conserva su configuración.
 */
public class AppPrefs {

    private static final String FILE = "loginPrefs_";

    private final SharedPreferences prefs;

    public AppPrefs(Context context) {
        prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public boolean isFirstLaunch() {
        return prefs.getString("first_launch", "yes").equals("yes");
    }

    public void saveFirstLaunch(String value) {
        prefs.edit().putString("first_launch", value).commit();
    }

    public String getFirstLaunchTime() {
        return prefs.getString("first_launch_time", "-1");
    }

    public void saveFirstLaunchTime(String value) {
        prefs.edit().putString("first_launch_time", value).commit();
    }

    /**
     * Cuántos minutos el agente no le escribe a un número después de que el dueño
     * le contesta desde «Mensajes» (PaynaniApp#111, APP-C4): de 0 (sin pausa) a 1440.
     */
    public int getAgentPauseMin() {
        return com.iaaorgmx.paynani.gateway.HumanSms.clampPause(
                prefs.getInt("agent_pause_min", com.iaaorgmx.paynani.gateway.HumanSms.PAUSE_DEFAULT_MIN));
    }

    public void setAgentPauseMin(int minutes) {
        prefs.edit().putInt("agent_pause_min", com.iaaorgmx.paynani.gateway.HumanSms.clampPause(minutes)).apply();
    }

    public boolean getSwitchSetting(String setting, boolean def) {
        return prefs.getBoolean(setting, def);
    }

    public void saveSwitchSetting(String setting, boolean value) {
        prefs.edit().putBoolean(setting, value).commit();
    }

    /** Intervalo del servicio en minutos, como texto. */
    public String getTimeServiceValue() {
        return prefs.getString("TimeServiceValue", "1");
    }

    public void saveTimeServiceValue(String value) {
        prefs.edit().putString("TimeServiceValue", value).commit();
    }

    /** Posición del intervalo en el selector de GatewaysFragment. */
    public String getTimeServicePos() {
        return prefs.getString("TimeServiceValue_pos", "0");
    }

    public void saveTimeServicePos(String value) {
        prefs.edit().putString("TimeServiceValue_pos", value).commit();
    }
}
