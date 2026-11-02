package com.iaaorgmx.paynani.gateway;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.SmsManager;

import com.iaaorgmx.paynani.EventLogRepository;

/**
 * Recibe el sentIntent y el deliveryIntent de un SMS enviado por orden de la
 * pasarela y los convierte en sms.status (APP-5, SMS_GATEWAY.md §4).
 */
public class SmsStatusReceiver extends BroadcastReceiver {

    static final String ACTION = "com.iaaorgmx.paynani.SMS_STATUS";

    static Intent intent(Context context, String orderId, String kind, int parts, String to, String text) {
        return new Intent(ACTION).setClass(context, SmsStatusReceiver.class)
                .putExtra("order_id", orderId).putExtra("kind", kind).putExtra("parts", parts)
                .putExtra("to", to).putExtra("text", text)
                .setData(android.net.Uri.parse("paynani://sms/" + orderId + "/" + kind));
    }

    /** Lo mismo para un SMS del dueño (PaynaniApp#111): lleva local_id en lugar de order_id. */
    static Intent humanIntent(Context context, String localId, String kind) {
        return new Intent(ACTION).setClass(context, SmsStatusReceiver.class)
                .putExtra("local_id", localId).putExtra("kind", kind)
                .setData(android.net.Uri.parse("paynani://sms-human/" + localId + "/" + kind));
    }

    @Override public void onReceive(Context context, Intent intent) {
        String localId = intent.getStringExtra("local_id");
        if (localId != null) {
            onHuman(context, intent, localId);
            return;
        }
        String orderId = intent.getStringExtra("order_id");
        String kind = intent.getStringExtra("kind");
        int parts = intent.getIntExtra("parts", 1);
        if (orderId == null || kind == null) return;
        GatewayClient client = GatewayClient.get(context);
        int code = getResultCode();
        final String status;
        final String error;
        if ("sent".equals(kind)) {
            if (code == Activity.RESULT_OK) {
                status = "sent";
                error = null;
                EventLogRepository eventLog = new EventLogRepository(context);
                eventLog.saveEvent(EventLogRepository.EVENT_SENT_TO_CONTACT, intent.getStringExtra("text"), intent.getStringExtra("to"));
            } else {
                status = "failed";
                error = errorName(code);
            }
        } else if ("delivered".equals(kind) && code == Activity.RESULT_OK) {
            status = "delivered";
            error = null;
        } else {
            return;
        }
        // La cola no se toca desde el hilo principal (#105).
        final PendingResult pending = goAsync();
        GatewayQueue.run(() -> {
            try {
                client.finish(orderId, status, error, parts);
            } finally {
                pending.finish();
            }
        });
    }

    private void onHuman(Context context, Intent intent, String localId) {
        String kind = intent.getStringExtra("kind");
        int code = getResultCode();
        final String status;
        final String error;
        if ("sent".equals(kind)) {
            status = code == Activity.RESULT_OK ? "sent" : "failed";
            error = code == Activity.RESULT_OK ? null : errorName(code);
        } else if ("delivered".equals(kind) && code == Activity.RESULT_OK) {
            status = "delivered";
            error = null;
        } else {
            return;
        }
        final Context app = context.getApplicationContext();
        final PendingResult pending = goAsync();
        GatewayQueue.run(() -> {
            try {
                HumanSms.onStatus(app, localId, status, error);
            } finally {
                pending.finish();
            }
        });
    }

    static String errorName(int code) {
        switch (code) {
            case SmsManager.RESULT_ERROR_GENERIC_FAILURE: return "RESULT_ERROR_GENERIC_FAILURE";
            case SmsManager.RESULT_ERROR_NO_SERVICE: return "RESULT_ERROR_NO_SERVICE";
            case SmsManager.RESULT_ERROR_NULL_PDU: return "RESULT_ERROR_NULL_PDU";
            case SmsManager.RESULT_ERROR_RADIO_OFF: return "RESULT_ERROR_RADIO_OFF";
            default: return "RESULT_ERROR_" + code;
        }
    }
}
