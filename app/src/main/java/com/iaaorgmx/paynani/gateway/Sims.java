package com.iaaorgmx.paynani.gateway;

import android.content.Context;
import android.os.Build;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Las SIM del teléfono y cuál atiende la pasarela (APP-10, PROD-3).
 *
 * Con una SIM elegida, la app sólo reporta esa SIM al emparejar y en el hello,
 * sólo manda a paynani lo que entra por ella, y envía por ella cuando la orden
 * no trae sim_slot. Sin elegir (-1), atiende todas, como antes.
 */
public final class Sims {

    private Sims() {}

    /** Las SIM activas, en orden de ranura. Vacío sin READ_PHONE_STATE. */
    public static List<SubscriptionInfo> active(Context context) {
        try {
            SubscriptionManager sm = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            List<SubscriptionInfo> subs = sm != null ? sm.getActiveSubscriptionInfoList() : null;
            if (subs == null) return Collections.emptyList();
            List<SubscriptionInfo> out = new ArrayList<>(subs);
            Collections.sort(out, (a, b) -> Integer.compare(a.getSimSlotIndex(), b.getSimSlotIndex()));
            return out;
        } catch (SecurityException e) {
            return Collections.emptyList();
        }
    }

    /** Las SIM que atiende la pasarela: la elegida, o todas. */
    public static List<SubscriptionInfo> served(Context context) {
        int chosen = new GatewayPrefs(context).getSimSlot();
        List<SubscriptionInfo> all = active(context);
        if (chosen < 0) return all;
        List<SubscriptionInfo> out = new ArrayList<>();
        for (SubscriptionInfo s : all) if (s.getSimSlotIndex() == chosen) out.add(s);
        return out;
    }

    public static SubscriptionInfo bySubId(Context context, int subId) {
        try {
            SubscriptionManager sm = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            if (sm == null) return null;
            int id = subId >= 0 ? subId : SubscriptionManager.getDefaultSmsSubscriptionId();
            return sm.getActiveSubscriptionInfo(id);
        } catch (Exception e) {
            return null;
        }
    }

    /** ¿Se manda a paynani lo que entró por esta suscripción? Si no se sabe por cuál, sí. */
    public static boolean accepts(Context context, int subId) {
        int chosen = new GatewayPrefs(context).getSimSlot();
        if (chosen < 0 || subId < 0) return true;
        SubscriptionInfo info = bySubId(context, subId);
        return info == null || info.getSimSlotIndex() == chosen;
    }

    /** País ISO de una SIM, o el de la SIM predeterminada. */
    public static String region(Context context, SubscriptionInfo info) {
        if (info != null) {
            String c = info.getCountryIso();
            if (c != null && c.length() == 2) return c.toUpperCase(Locale.ROOT);
        }
        return Protocol.simCountry(context);
    }

    /**
     * El número de una SIM en E.164, o null. Android no siempre lo sabe (muchas
     * SIM no lo traen grabado); entonces vale el que escribió el usuario para la
     * SIM elegida, o para la única SIM.
     */
    public static String number(Context context, SubscriptionInfo info) {
        String e164 = androidNumber(context, info);
        if (e164 != null) return e164;
        GatewayPrefs prefs = new GatewayPrefs(context);
        String typed = prefs.getSimNumber();
        boolean applies = prefs.getSimSlot() == info.getSimSlotIndex() || active(context).size() == 1;
        return applies && !typed.isEmpty() ? typed : null;
    }

    /** Sólo el número que da Android, en E.164, o null. */
    public static String androidNumber(Context context, SubscriptionInfo info) {
        String raw = null;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                SubscriptionManager sm = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
                if (sm != null) raw = sm.getPhoneNumber(info.getSubscriptionId());
            } else {
                raw = info.getNumber();
            }
        } catch (SecurityException ignored) {
            // Sin READ_PHONE_NUMBERS: queda el número escrito a mano.
        }
        return raw != null && !raw.isEmpty() ? Phone.toE164(raw, region(context, info)) : null;
    }
}
