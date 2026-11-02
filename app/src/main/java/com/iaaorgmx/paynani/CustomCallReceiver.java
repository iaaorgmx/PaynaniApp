package com.iaaorgmx.paynani;
//PaynaniApp
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.SystemClock;
import android.provider.CallLog;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.iaaorgmx.paynani.gateway.GatewayQueue;
import com.iaaorgmx.paynani.gateway.Inbound;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Llamadas entrantes (APP-11, DEC-4): la perdida (call.missed) y la contestada
 * (call.answered, con duración, al colgar). La saliente no se reporta.
 *
 * Android manda PHONE_STATE dos veces por cambio desde la API 29, una con el número
 * y otra sin él, y puede matar el proceso a media llamada; por eso el estado vive en
 * SharedPreferences y el número se toma de la primera difusión que lo traiga.
 *
 * Una llamada sin número puede ser de otra app (WhatsApp en Samsung, #151): mientras suena se
 * pregunta a Telecom si es una llamada administrada (de la SIM) y, si no se pudo saber, al colgar
 * se mira de qué cuenta la anotó el registro de llamadas ({@link CallOrigin}).
 */
public class CustomCallReceiver extends BroadcastReceiver {

    private static final String PREFS = "paynani_call_state";
    private static final String TAG = "CallReceiver";
    /** Cuánto se espera a que Android anote la llamada en el registro después de colgar. */
    private static final long CALL_LOG_WAIT_MS = 4_000;
    private static final long CALL_LOG_POLL_MS = 500;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(intent.getAction())) return;
        String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
        if (state == null) return;

        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String last = p.getString("state", TelephonyManager.EXTRA_STATE_IDLE);
        long now = System.currentTimeMillis();
        int subId = intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX",
                intent.getIntExtra("subscription", -1));

        String number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
        boolean ringingOrAnswering = TelephonyManager.EXTRA_STATE_RINGING.equals(state)
                || (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(state) && p.getBoolean("incoming", false));
        boolean changed = !state.equals(last);
        boolean newCall = changed && TelephonyManager.EXTRA_STATE_RINGING.equals(state);

        // El número y, mientras suena o se contesta, si Telecom la ve como llamada de la SIM (#151).
        CallCycle cycle = new CallCycle(p.getInt("managed", CallOrigin.MANAGED_UNKNOWN), p.getString("number", ""));
        if (newCall) cycle.start();
        if (ringingOrAnswering) {
            cycle.observe(number, managedCall(context), newCall);
            p.edit().putString("number", cycle.number()).putInt("managed", cycle.managed()).apply();
        }
        if (!changed) return; // la difusión repetida del mismo cambio

        SharedPreferences.Editor e = p.edit().putString("state", state);
        if (TelephonyManager.EXTRA_STATE_RINGING.equals(state)) {
            e.putBoolean("incoming", true).putLong("started", now).putLong("answered", 0)
                    .putInt("sub_id", subId);
        } else if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(state)) {
            if (TelephonyManager.EXTRA_STATE_RINGING.equals(last)) e.putLong("answered", now);
            else e.putBoolean("incoming", false); // saliente: no se reporta
        } else if (TelephonyManager.EXTRA_STATE_IDLE.equals(state)) {
            if (p.getBoolean("incoming", false)) {
                String caller = cycle.number();
                CallOrigin.Decision decision = cycle.end();
                long started = p.getLong("started", now);
                long answered = p.getLong("answered", 0);
                int sub = p.getInt("sub_id", -1);
                EventLogRepository eventLog = new EventLogRepository(context);
                final boolean missed = TelephonyManager.EXTRA_STATE_RINGING.equals(last) || answered == 0;
                final String type = missed ? "call.missed" : "call.answered";
                final int duration = missed ? -1 : (int) Math.max(0, (now - answered) / 1000);
                // La cola no se toca desde el hilo principal (#105), y el registro de llamadas
                // tampoco se espera ahí.
                final PendingResult pending = goAsync();
                GatewayQueue.run(() -> {
                    try {
                        if (!report(context, decision, started)) {
                            Log.i(TAG, "llamada de otra app, no de la SIM: no se reporta");
                            return;
                        }
                        eventLog.saveEvent(missed ? EventLogRepository.EVENT_CALL_NOT_ANSWERED
                                : EventLogRepository.EVENT_CALL_ANSWERED, "", caller);
                        Inbound.call(context, type, caller, started, duration, sub);
                    } finally {
                        pending.finish();
                    }
                });
            }
            // El ciclo termina limpio: nada de esta llamada cuenta para la siguiente.
            e.putBoolean("incoming", false).putString("number", "")
                    .putInt("managed", CallOrigin.MANAGED_UNKNOWN);
        }
        e.apply();
    }

    /**
     * isInManagedCall(): true si hay una llamada de una SIM (u otra cuenta administrada); false si
     * lo único que hay es una llamada self-managed, como las de WhatsApp.
     */
    private static int managedCall(Context context) {
        try {
            TelecomManager tm = context.getSystemService(TelecomManager.class);
            if (tm == null) return CallOrigin.MANAGED_UNKNOWN;
            return tm.isInManagedCall() ? CallOrigin.MANAGED_YES : CallOrigin.MANAGED_NO;
        } catch (RuntimeException e) { // SecurityException u otro error: no se sabe, se reporta
            Log.w(TAG, "no se pudo preguntar a Telecom por la llamada", e);
            return CallOrigin.MANAGED_UNKNOWN;
        }
    }

    /**
     * Si la llamada se reporta. Con CHECK_CALL_LOG (Telecom no dijo nada), según su entrada en el
     * registro de llamadas: sin entrada, sin cuenta o sin permiso, se reporta. Fuera del hilo
     * principal: espera hasta CALL_LOG_WAIT_MS a que Android la anote.
     */
    static boolean report(Context context, CallOrigin.Decision decision, long started) {
        if (decision != CallOrigin.Decision.CHECK_CALL_LOG) return decision == CallOrigin.Decision.REPORT;
        Set<String> sims = simComponents(context);
        if (sims.isEmpty()) return true;
        long until = SystemClock.elapsedRealtime() + CALL_LOG_WAIT_MS;
        while (true) {
            CallOrigin.Entry entry = CallOrigin.match(callLog(context, started), started);
            if (entry != null) return CallOrigin.report(entry, sims);
            if (SystemClock.elapsedRealtime() >= until) return true;
            SystemClock.sleep(CALL_LOG_POLL_MS);
        }
    }

    /** Los componentes de las cuentas de las SIM (CAPABILITY_SIM_SUBSCRIPTION), normalizados. */
    private static Set<String> simComponents(Context context) {
        List<CallOrigin.Account> accounts = new ArrayList<>();
        try {
            TelecomManager tm = context.getSystemService(TelecomManager.class);
            if (tm == null) return CallOrigin.simComponents(accounts);
            for (PhoneAccountHandle h : tm.getCallCapablePhoneAccounts()) {
                if (h.getComponentName() == null) continue;
                PhoneAccount account = tm.getPhoneAccount(h);
                accounts.add(new CallOrigin.Account(h.getComponentName().flattenToString(), account != null
                        && account.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)));
            }
        } catch (SecurityException e) {
            Log.w(TAG, "sin permiso para leer las cuentas de llamada");
        }
        return CallOrigin.simComponents(accounts);
    }

    /** Las entradas que no son salientes alrededor de started. */
    private static List<CallOrigin.Entry> callLog(Context context, long started) {
        List<CallOrigin.Entry> out = new ArrayList<>();
        String[] projection = {CallLog.Calls.DATE, CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME};
        String selection = CallLog.Calls.DATE + " >= ? AND " + CallLog.Calls.DATE + " <= ? AND "
                + CallLog.Calls.TYPE + " != " + CallLog.Calls.OUTGOING_TYPE;
        String[] args = {String.valueOf(started - CallOrigin.BEFORE_MS), String.valueOf(started + CallOrigin.AFTER_MS)};
        try (Cursor c = context.getContentResolver().query(CallLog.Calls.CONTENT_URI, projection, selection, args,
                CallLog.Calls.DATE + " DESC")) {
            while (c != null && c.moveToNext()) {
                String raw = c.getString(1);
                ComponentName cn = raw == null ? null : ComponentName.unflattenFromString(raw);
                out.add(new CallOrigin.Entry(c.getLong(0), cn == null ? raw : cn.flattenToString()));
            }
        } catch (SecurityException e) {
            Log.w(TAG, "sin permiso para leer el registro de llamadas");
        }
        return out;
    }
}
