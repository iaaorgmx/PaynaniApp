package com.iaaorgmx.paynani;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.ApplicationExitInfo;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.preference.PreferenceManager;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;

/**
 * «Permitir en segundo plano» (#86). Muchos fabricantes traen su propio ahorro
 * de energía (DuraSpeed de MediaTek, autostart de MIUI, Huawei, ColorOS…) que
 * cierra a la fuerza a Paynani aunque tenga servicio en primer plano y la
 * exención de batería (#84). No se puede evitar desde la app: sólo llevar al
 * usuario a esa pantalla.
 *
 * Las pantallas están en res/raw/background_screens.json, no en el código, y
 * se usa la primera que el teléfono tenga. Si no tiene ninguna, no hay paso.
 */
public final class BackgroundScreens {

    private static final String CONFIRMED = "background_confirmed";
    private static final String CONFIRMED_VERSION = "background_confirmed_version";
    private static final String LAST_EXIT = "background_last_exit";
    private static final String DKMA = "https://dontkillmyapp.com/";

    private BackgroundScreens() {}

    // Estado se redibuja cada 2 s: la tabla se lee una sola vez por proceso.
    // Las apps de sistema que nombra no cambian mientras la app corre.
    private static volatile boolean loaded;
    private static volatile Screen cached;

    /** Una pantalla de fabricante que existe en este teléfono. */
    public static final class Screen {
        public final String vendor;
        final Intent intent;
        final String dkma;
        final String guide;

        Screen(String vendor, Intent intent, String dkma, String guide) {
            this.vendor = vendor;
            this.intent = intent;
            this.dkma = dkma;
            this.guide = guide;
        }
    }

    /**
     * El texto de la guía. Samsung tiene el suyo (#97): su pantalla de batería
     * trae una lista que duerme las apps («Sleeping apps») junto a la que las
     * protege, y hay que nombrar la buena.
     */
    public static String steps(Context context, Screen screen) {
        if ("samsung".equals(screen.guide)) return context.getString(samsungSteps(Build.VERSION.SDK_INT));
        return context.getString(R.string.bg_guide_steps, screen.vendor);
    }

    /**
     * Los pasos de Samsung según la versión de Android (#124): los menús cambian con
     * One UI. Android 9 (One UI 1) se confirmó en el Note 8 de John; Android 10
     * (One UI 2) sale de la documentación de Samsung; 11+ (One UI 3+) es el de #97.
     */
    static int samsungSteps(int sdk) {
        if (sdk <= 28) return R.string.bg_guide_steps_samsung_9;
        if (sdk == 29) return R.string.bg_guide_steps_samsung_10;
        return R.string.bg_guide_steps_samsung;
    }

    /** La primera pantalla de la tabla que este teléfono tiene, o null. */
    public static Screen find(Context context) {
        if (!loaded) {
            cached = lookup(context);
            loaded = true;
        }
        return cached;
    }

    private static Screen lookup(Context context) {
        try {
            JSONArray screens = new JSONObject(read(context)).getJSONArray("screens");
            PackageManager pm = context.getPackageManager();
            for (int i = 0; i < screens.length(); i++) {
                JSONObject s = screens.getJSONObject(i);
                String maker = s.optString("manufacturer", "");
                if (!maker.isEmpty() && !maker.equalsIgnoreCase(Build.MANUFACTURER)) continue;
                Intent intent = new Intent();
                if (s.optBoolean("app_details", false)) {
                    intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.fromParts("package", context.getPackageName(), null));
                } else if (s.has("component")) {
                    ComponentName cn = ComponentName.unflattenFromString(s.getString("component"));
                    if (cn == null) continue;
                    intent.setComponent(cn);
                } else {
                    intent.setAction(s.getString("action"));
                }
                if (pm.resolveActivity(intent, 0) != null) {
                    return new Screen(s.getString("vendor"), intent, s.optString("dkma", ""), s.optString("guide", ""));
                }
            }
        } catch (Exception e) {
            L.e("BackgroundScreens", "tabla: " + e);
        }
        return null;
    }

    private static String read(Context context) throws java.io.IOException {
        try (InputStream in = context.getResources().openRawResource(R.raw.background_screens)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    /**
     * Si el usuario dijo que ya permitió a Paynani en la pantalla del fabricante,
     * con esta misma versión de la app. Una actualización puede apagar el permiso
     * (DuraSpeed lo hizo de la 0.4.0 a la 0.6.1, #92), así que después de cada
     * una se vuelve a pedir. Una confirmación sin versión cuenta como no hecha.
     */
    public static boolean confirmed(Context context) {
        android.content.SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getBoolean(CONFIRMED, false)
                && prefs.getInt(CONFIRMED_VERSION, -1) == BuildConfig.VERSION_CODE;
    }

    public static void setConfirmed(Context context, boolean confirmed) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(CONFIRMED, confirmed)
                .putInt(CONFIRMED_VERSION, BuildConfig.VERSION_CODE)
                .apply();
    }

    /**
     * ¿La última vez que terminó el proceso fue un cierre forzado? (Android 11+, #86)
     * Cuenta REASON_USER_REQUESTED y REASON_OTHER, que es como se ve el cierre de
     * DuraSpeed y parecidos. No distingue si lo cerró el usuario o el fabricante:
     * mostrar la guía de más no hace daño. Cada salida cuenta una sola vez.
     */
    public static boolean forceStoppedSinceLastCheck(Context context) {
        if (Build.VERSION.SDK_INT < 30) return false;
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;
        List<ApplicationExitInfo> exits = am.getHistoricalProcessExitReasons(context.getPackageName(), 0, 1);
        if (exits.isEmpty()) return false;
        ApplicationExitInfo last = exits.get(0);
        android.content.SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (last.getTimestamp() <= prefs.getLong(LAST_EXIT, 0)) return false;
        prefs.edit().putLong(LAST_EXIT, last.getTimestamp()).apply();
        int reason = last.getReason();
        return reason == ApplicationExitInfo.REASON_USER_REQUESTED || reason == ApplicationExitInfo.REASON_OTHER;
    }

    /** Hay pantalla de fabricante y el usuario todavía no confirmó. */
    public static boolean pending(Context context) {
        return !confirmed(context) && find(context) != null;
    }

    /**
     * El Intent que abre la pantalla. Si el fabricante la bloquea o ya no
     * existe, el de la página de dontkillmyapp.com de la marca.
     */
    public static Intent openIntent(Context context, Screen screen) {
        Intent i = new Intent(screen.intent);
        try {
            PackageManager pm = context.getPackageManager();
            if (pm.resolveActivity(i, 0) != null) return i;
        } catch (RuntimeException ignored) {
        }
        return dkmaIntent(screen);
    }

    /** dontkillmyapp.com/<marca>: la de la tabla, o la del teléfono. */
    public static Intent dkmaIntent(Screen screen) {
        String slug = screen.dkma.isEmpty() ? Build.MANUFACTURER.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "") : screen.dkma;
        return new Intent(Intent.ACTION_VIEW, Uri.parse(DKMA + slug));
    }

    /** Abre la pantalla; si falla (ActivityNotFound o SecurityException), la página de la marca. */
    public static void open(Context context, Screen screen) {
        try {
            context.startActivity(openIntent(context, screen));
        } catch (ActivityNotFoundException | SecurityException e) {
            try {
                context.startActivity(dkmaIntent(screen));
            } catch (ActivityNotFoundException ignored) {
            }
        }
    }

    /**
     * La guía, igual que la de RCS (#54): qué hacer, atajo a la pantalla y
     * «Ya lo permití». {@code onConfirmed} corre después de confirmar.
     */
    public static void showGuide(Context context, Screen screen, Runnable onConfirmed) {
        new AlertDialog.Builder(context)
                .setTitle(R.string.bg_guide_title)
                .setMessage(steps(context, screen))
                .setPositiveButton(R.string.bg_guide_done, (d, w) -> {
                    setConfirmed(context, true);
                    if (onConfirmed != null) onConfirmed.run();
                })
                .setNegativeButton(R.string.bg_guide_later, null)
                .setNeutralButton(R.string.bg_guide_open, (d, w) -> open(context, screen))
                .show();
    }
}
