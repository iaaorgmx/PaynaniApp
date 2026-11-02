package com.iaaorgmx.paynani;
//PaynaniApp
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;

import com.iaaorgmx.paynani.gateway.GatewayQueue;
import com.iaaorgmx.paynani.gateway.Inbound;

import java.util.Locale;

/**
 * Receptor de SMS en tiempo real (APP-4). Arma el SMS completo con todas sus
 * partes y lo encola para la pasarela una sola vez; el envío y el reintento son
 * de la cola, no de aquí.
 */
public class SmsListener extends BroadcastReceiver {

    private static final String TAG = "SmsListener";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) return;

        AppPrefs prefs = new AppPrefs(context);
        if (!prefs.getSwitchSetting("switch_listener", true)) return;

        SmsMessage[] msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (msgs == null || msgs.length == 0) return;

        // Las partes de un SMS largo llegan juntas en el mismo intent.
        StringBuilder body = new StringBuilder();
        for (SmsMessage m : msgs) {
            if (m != null && m.getMessageBody() != null) body.append(m.getMessageBody());
        }
        // A6 (#51): las PDU en crudo, para ver de dónde sale un sufijo como el
        // del «#2». Sólo en debug; en release no sale ni la PDU ni el texto.
        if (BuildConfig.DEBUG) {
            for (int i = 0; i < msgs.length; i++) {
                if (msgs[i] == null) continue;
                L.d(TAG, "pdu[" + i + "] " + hex(msgs[i].getPdu()));
                L.d(TAG, "userData[" + i + "] " + hex(msgs[i].getUserData()));
            }
        }
        SmsMessage first = msgs[0];
        String raw = first.getOriginatingAddress() == null ? "" : first.getOriginatingAddress();
        String text = body.toString();
        int subId = intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX",
                intent.getIntExtra("subscription", -1));

        long sentMs = first.getTimestampMillis();
        long receivedMs = System.currentTimeMillis();
        int parts = msgs.length;

        // «Enviado a paynani» lo anota GatewayClient cuando llega el ack. La cola
        // no se toca desde el hilo principal (#105): goAsync mantiene vivo el
        // receptor hasta que el SMS quedó encolado.
        final PendingResult pending = goAsync();
        GatewayQueue.run(() -> {
            try {
                Inbound.sms(context, raw, sentMs, receivedMs, text, parts, subId);
            } finally {
                pending.finish();
            }
        });
        new EventLogRepository(context).saveEvent(EventLogRepository.EVENT_INCOMING_SMS, text, raw);
    }

    private static String hex(byte[] b) {
        if (b == null) return "null";
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x));
        return sb.toString();
    }
}
