package com.iaaorgmx.paynani.gateway;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import org.json.JSONArray;

/**
 * Lo que la app sabe de la pasarela, en preferencias cifradas: la dirección, el
 * device_id y el token del emparejamiento. El token nunca se guarda en claro ni se
 * escribe en el log.
 *
 * El archivo y las llaves son los de PaynaniApp-Agentic: un teléfono que se actualiza
 * desde esa app conserva su emparejamiento.
 */
public final class GatewayPrefs {

    private static final String FILE = "paynani_gateway";
    private static SharedPreferences cached;
    private final SharedPreferences prefs;

    public GatewayPrefs(Context context) {
        this.prefs = open(context);
    }

    /** Abrir las preferencias cifradas cuesta: se abren una vez por proceso. */
    private static synchronized SharedPreferences open(Context context) {
        if (cached != null) return cached;
        try {
            String master = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            cached = EncryptedSharedPreferences.create(FILE, master, context.getApplicationContext(),
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            // Sin Keystore usable no se guarda nada: es preferible no emparejar a
            // dejar el token en claro.
            throw new IllegalStateException("No se pudieron abrir las preferencias cifradas", e);
        }
        return cached;
    }

    public boolean isPaired() {
        return !getToken().isEmpty() && !getWsUrl().isEmpty();
    }

    public void savePairing(String wsUrl, String deviceId, String token) {
        prefs.edit().putString("ws_url", wsUrl).putString("device_id", deviceId)
                .putString("token", token).putBoolean("revoked", false).apply();
    }

    public void clear() {
        prefs.edit().clear().apply();
    }

    /**
     * Las llaves de PaynaniApp-Agentic que esta app no usa: la lista de teléfonos del roster, la
     * SIM, la batería, los contadores y el último remitente (Metis en PaynaniApp#1). El
     * emparejamiento (ws_url, device_id, token, revoked) se queda.
     */
    static final String[] LEGACY_KEYS = {"allowed", "battery_sent_at", "battery_sent_charging",
            "battery_sent_pct", "clock_offset_ms", "delivered_calls", "delivered_day", "delivered_sms",
            "device_status", "last_delivered_at", "last_delivered_from", "last_delivered_type",
            "max_out_per_hour", "sim_number", "sim_slot", "sweep_mark"};

    public void dropLegacy() {
        SharedPreferences.Editor e = prefs.edit();
        for (String key : LEGACY_KEYS) e.remove(key);
        e.apply();
    }

    public String getWsUrl() { return prefs.getString("ws_url", ""); }
    public String getDeviceId() { return prefs.getString("device_id", ""); }
    public String getToken() { return prefs.getString("token", ""); }

    public boolean isRevoked() { return prefs.getBoolean("revoked", false); }
    public void setRevoked(boolean revoked) { prefs.edit().putBoolean("revoked", revoked).apply(); }

    // Los tipos que la pasarela acepta de esta conexión, del welcome (SMS_GATEWAY.md §2).
    public void setAccepts(JSONArray accepts) {
        prefs.edit().putString("accepts", accepts == null ? "[]" : accepts.toString()).apply();
    }
    public boolean accepts(String type) {
        try {
            JSONArray a = new JSONArray(prefs.getString("accepts", "[]"));
            for (int i = 0; i < a.length(); i++) if (type.equals(a.optString(i))) return true;
        } catch (Exception ignored) {}
        return false;
    }

    // Las capacidades que la pasarela concede a esta conexión, del welcome (webchat.owner,
    // SMS_GATEWAY.md §13). Una pasarela anterior no las manda: lista vacía.
    public void setGrants(JSONArray grants) {
        prefs.edit().putString("grants", grants == null ? "[]" : grants.toString()).apply();
    }
    public boolean granted(String capability) {
        try {
            JSONArray a = new JSONArray(prefs.getString("grants", "[]"));
            for (int i = 0; i < a.length(); i++) if (capability.equals(a.optString(i))) return true;
        } catch (Exception ignored) {}
        return false;
    }

    // La versión de paynani que anunció el último welcome, o "" si la pasarela no la
    // informa. Para mostrarla en Estado.
    public void setServerVersion(String version) {
        prefs.edit().putString("server_version", version == null ? "" : version).apply();
    }
    public String getServerVersion() { return prefs.getString("server_version", ""); }

    /** Cuándo se cortó la última conexión que sí llegó a welcome, o 0 si sigue o nunca hubo. */
    public long getDisconnectedAt() { return prefs.getLong("disconnected_at", 0L); }
    public void setDisconnectedAt(long ms) { prefs.edit().putLong("disconnected_at", ms).apply(); }

    public void setLastConnected(long ms) { prefs.edit().putLong("last_connected", ms).apply(); }
    public long getLastConnected() { return prefs.getLong("last_connected", 0L); }
}
