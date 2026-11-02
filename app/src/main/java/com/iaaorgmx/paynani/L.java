package com.iaaorgmx.paynani;

import android.util.Log;

/**
 * Log que sólo escribe en compilaciones de debug (SEC-6, #3).
 *
 * El código registra textos de SMS, teléfonos y parámetros que van al
 * servidor. En release no debe salir nada de eso a logcat.
 */
public final class L {

    private L() {}

    public static void e(String tag, String msg) { if (BuildConfig.DEBUG) Log.e(tag, String.valueOf(msg)); }
    public static void e(String tag, String msg, Throwable tr) { if (BuildConfig.DEBUG) Log.e(tag, String.valueOf(msg), tr); }
    public static void w(String tag, String msg) { if (BuildConfig.DEBUG) Log.w(tag, String.valueOf(msg)); }
    public static void w(String tag, String msg, Throwable tr) { if (BuildConfig.DEBUG) Log.w(tag, String.valueOf(msg), tr); }
    public static void i(String tag, String msg) { if (BuildConfig.DEBUG) Log.i(tag, String.valueOf(msg)); }
    public static void i(String tag, String msg, Throwable tr) { if (BuildConfig.DEBUG) Log.i(tag, String.valueOf(msg), tr); }
    public static void d(String tag, String msg) { if (BuildConfig.DEBUG) Log.d(tag, String.valueOf(msg)); }
    public static void d(String tag, String msg, Throwable tr) { if (BuildConfig.DEBUG) Log.d(tag, String.valueOf(msg), tr); }
}
