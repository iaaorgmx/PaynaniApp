package com.iaaorgmx.paynani.gateway;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Lo que la app sabe de la pasarela, en preferencias cifradas: la dirección, el
 * device_id y el token del emparejamiento (APP-2). El token nunca se guarda en
 * claro ni se escribe en el log.
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
                .putString("token", token).putBoolean("revoked", false)
                // El barrido de la bandeja empieza aquí: no se sube el historial.
                .putLong("sweep_mark", System.currentTimeMillis()).apply();
    }

    public void clear() {
        prefs.edit().clear().apply();
    }

    public String getWsUrl() { return prefs.getString("ws_url", ""); }
    public String getDeviceId() { return prefs.getString("device_id", ""); }
    public String getToken() { return prefs.getString("token", ""); }

    public boolean isRevoked() { return prefs.getBoolean("revoked", false); }
    public void setRevoked(boolean revoked) { prefs.edit().putBoolean("revoked", revoked).apply(); }

    /** Diferencia (ms) entre el reloj de la pasarela y el del teléfono. */
    public long getClockOffsetMs() { return prefs.getLong("clock_offset_ms", 0L); }
    public void setClockOffsetMs(long offset) { prefs.edit().putLong("clock_offset_ms", offset).apply(); }

    public void setAllowed(JSONArray allowed) { prefs.edit().putString("allowed", allowed.toString()).apply(); }
    public List<String> getAllowed() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("allowed", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) {}
        return out;
    }

    // Los tipos que la pasarela acepta del teléfono, del welcome (SMS_GATEWAY.md §2).
    // Una pasarela anterior a accepts no lo trae y queda vacío: no se manda nada nuevo.
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

    // Las capacidades que la pasarela concede a esta conexión, del welcome (SMS_GATEWAY.md §12,
    // whatsapp.admin). Una pasarela anterior no las manda: lista vacía.
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

    // La última lectura de batería (device.status sin type ni id, #109), para el hello.
    public void setDeviceStatus(JSONObject fields) {
        prefs.edit().putString("device_status", fields == null ? "" : fields.toString()).apply();
    }
    public JSONObject getDeviceStatus() {
        try {
            String s = prefs.getString("device_status", "");
            return s.isEmpty() ? null : new JSONObject(s);
        } catch (Exception e) {
            return null;
        }
    }

    // La última device.status que salió a la cola: cuándo, con qué porcentaje y carga.
    public void setBatterySent(long ms, int pct, boolean charging) {
        prefs.edit().putLong("battery_sent_at", ms).putInt("battery_sent_pct", pct)
                .putBoolean("battery_sent_charging", charging).apply();
    }
    public long getBatterySentAt() { return prefs.getLong("battery_sent_at", 0L); }
    public int getBatterySentPct() { return prefs.getInt("battery_sent_pct", -1); }
    public boolean getBatterySentCharging() { return prefs.getBoolean("battery_sent_charging", false); }

    // La versión de paynani que anunció el último welcome (paynani#367), o "" si
    // la pasarela no la informa (0.11.0 o anterior). Para mostrarla en Estado (#63).
    public void setServerVersion(String version) {
        prefs.edit().putString("server_version", version == null ? "" : version).apply();
    }
    public String getServerVersion() { return prefs.getString("server_version", ""); }

    public void setMaxOutPerHour(int n) { prefs.edit().putInt("max_out_per_hour", n).apply(); }
    public int getMaxOutPerHour() { return prefs.getInt("max_out_per_hour", 60); }

    /** Cuándo se cortó la última conexión que sí llegó a welcome, o 0 si sigue o nunca hubo. */
    public long getDisconnectedAt() { return prefs.getLong("disconnected_at", 0L); }
    public void setDisconnectedAt(long ms) { prefs.edit().putLong("disconnected_at", ms).apply(); }

    public void setLastConnected(long ms) { prefs.edit().putLong("last_connected", ms).apply(); }
    public long getLastConnected() { return prefs.getLong("last_connected", 0L); }

    /** Marca de agua del barrido de la bandeja: date_sent del último SMS procesado. */
    /** APP-10: la SIM de la pasarela (ranura), o -1 para todas. */
    public int getSimSlot() { return prefs.getInt("sim_slot", -1); }
    public void setSimSlot(int slot) { prefs.edit().putInt("sim_slot", slot).apply(); }

    /** El número que escribió el usuario para la SIM elegida, cuando Android no lo da. */
    public String getSimNumber() { return prefs.getString("sim_number", ""); }
    public void setSimNumber(String e164) { prefs.edit().putString("sim_number", e164 == null ? "" : e164).apply(); }

    /**
     * Lo entregado a paynani (APP-8): se cuenta sólo con el ack «stored», así que
     * un reenvío que vuelve como «duplicate» no se cuenta dos veces. Los contadores
     * son del día local y vuelven a cero al cambiar de día.
     */
    public synchronized void countDelivered(String type, String from, long at) {
        String today = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT).format(new java.util.Date(at));
        boolean sameDay = today.equals(prefs.getString("delivered_day", ""));
        int sms = sameDay ? prefs.getInt("delivered_sms", 0) : 0;
        int calls = sameDay ? prefs.getInt("delivered_calls", 0) : 0;
        if ("sms.in".equals(type)) sms++; else calls++;
        prefs.edit().putString("delivered_day", today).putInt("delivered_sms", sms).putInt("delivered_calls", calls)
                .putLong("last_delivered_at", at).putString("last_delivered_type", type)
                .putString("last_delivered_from", from == null ? "" : from).apply();
    }

    /** {sms, llamadas} entregados hoy. */
    public int[] deliveredToday() {
        String today = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT).format(new java.util.Date());
        if (!today.equals(prefs.getString("delivered_day", ""))) return new int[]{0, 0};
        return new int[]{prefs.getInt("delivered_sms", 0), prefs.getInt("delivered_calls", 0)};
    }

    public long getLastDeliveredAt() { return prefs.getLong("last_delivered_at", 0L); }
    public String getLastDeliveredType() { return prefs.getString("last_delivered_type", ""); }
    public String getLastDeliveredFrom() { return prefs.getString("last_delivered_from", ""); }

    public void setSweepMark(long ms) { prefs.edit().putLong("sweep_mark", ms).apply(); }
    public long getSweepMark() { return prefs.getLong("sweep_mark", 0L); }
}
