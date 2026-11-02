package com.iaaorgmx.paynani;

import android.content.Context;

import com.iaaorgmx.paynani.gateway.GatewayPrefs;

/**
 * Lo que deja PaynaniApp-Agentic al actualizarse a esta app (Metis en PaynaniApp#1). La
 * actualización conserva los datos, y con ellos el emparejamiento, pero también las bases y
 * las preferencias de SMS, llamadas y Event log, con textos y números de otras personas.
 * Nadie las vuelve a leer: se borran al arrancar. Todo es idempotente y no falla si no existen.
 */
final class Leftovers {

    /** Las bases de Room de PaynaniApp-Agentic: el Event log y la cola de SMS y llamadas. */
    static final String[] DATABASES = {"DBPaynaniAppNew1", "paynani_gateway.db"};
    /** Ajustes de la app (AppPrefs) y las preferencias por omisión (permisos, RCS, segundo plano). */
    static final String[] PREFERENCES = {"loginPrefs_", "com.iaaorgmx.paynani_preferences"};

    private Leftovers() {}

    static void clean(Context context) {
        for (String db : DATABASES) context.deleteDatabase(db);
        for (String prefs : PREFERENCES) context.deleteSharedPreferences(prefs);
        new GatewayPrefs(context).dropLegacy();
    }
}
