package com.iaaorgmx.paynani;

import android.content.Context;
import android.content.Intent;

import com.iaaorgmx.paynani.db.AppDatabase;
import com.iaaorgmx.paynani.db.EventLog;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Bitácora local: tabla event_log de la base DBPaynaniAppNew1 (#104, Room desde #105). */
public class EventLogRepository {

    public static final String EVENT_CALL_NOT_ANSWERED = "call_not_answered";
    public static final String EVENT_CALLNOT_TO_SERVER = "sent_server_incoming";

    public static final String EVENT_CALL_ANSWERED = "call_answered";
    public static final String EVENT_CALL_TO_SERVER = "sent_server_answered";

    public static final String EVENT_INCOMING_SMS = "incoming_sms";
    public static final String EVENT_SENT_TO_SERVER = "sent_server";
    public static final String EVENT_SENT_TO_CONTACT = "reply_contact";

    // Room no deja tocar la base desde el hilo principal, y saveEvent se llama
    // desde receptores. Un solo hilo guarda las filas en el orden en que llegan.
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor();

    private final Context context;

    public EventLogRepository(Context context) {
        this.context = context.getApplicationContext();
    }

    public void saveEvent(String event, String msg, String from) {
        final EventLog row = new EventLog();
        row.evento = event;
        row.msg = msg;
        row.phone = from;
        row.timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Calendar.getInstance().getTime());

        WRITER.execute(new Runnable() {
            @Override
            public void run() {
                AppDatabase.get(context).eventLogDao().insert(row);
                notifyLogUpdated();
            }
        });
    }

    /** Los últimos 7 días, lo más nuevo primero. No llamar desde el hilo principal. */
    public List<EventLog> lastWeek() {
        return AppDatabase.get(context).eventLogDao().lastWeek();
    }

    /** Avisa a EventLogFragment que recargue la lista. */
    private void notifyLogUpdated() {
        L.e("BROADCAST  UPDATELOG", "UPDATELOG");
        Intent intent = new Intent("com.iaaorgmx.paynani.UPDATELOG");
        intent.setPackage(context.getPackageName());
        context.sendBroadcast(intent);
    }
}
