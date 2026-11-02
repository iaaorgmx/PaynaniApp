package com.iaaorgmx.paynani.gateway;

import android.app.Notification;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import androidx.core.app.NotificationManagerCompat;

import com.iaaorgmx.paynani.EventLogRepository;
import com.iaaorgmx.paynani.L;

import java.util.List;

/**
 * Detector de mensajes que Google Messages recibió y la app no vio (#46 §4.3, #55).
 * Lo que llega por RCS viaja por datos, nunca como SMS: la única huella que deja
 * en el teléfono es la notificación de Google Messages. Si a los 60 s no entró
 * ningún SMS cerca de esa hora, se avisa a paynani con un sms.unseen.
 *
 * Nunca lee ni guarda el texto de la notificación, sólo su título.
 */
public class RcsWatcher extends NotificationListenerService {

    private static final String TAG = "RcsWatcher";

    // Para avisar una sola vez por conexión que la pasarela no acepta sms.unseen.
    private static volatile boolean noticed;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable checkLater = () -> new Thread(() -> check(getApplicationContext())).start();

    /** true si el usuario le dio a la app el acceso a las notificaciones. */
    public static boolean enabled(Context context) {
        return NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.getPackageName());
    }

    /** GatewayClient la llama en cada welcome. */
    static void newConnection() {
        noticed = false;
    }

    @Override
    public void onListenerConnected() {
        // Lo que quedó pendiente si Android mató el servicio antes de los 60 s.
        handler.post(checkLater);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        Notification n = sbn.getNotification();
        if (n == null) return;
        boolean summary = (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0;
        boolean ongoing = (n.flags & Notification.FLAG_ONGOING_EVENT) != 0;
        CharSequence title = n.extras != null ? n.extras.getCharSequence(Notification.EXTRA_TITLE) : null;
        Context context = getApplicationContext();
        String pkg = sbn.getPackageName();
        String key = sbn.getKey();
        long postedMs = sbn.getPostTime();
        String category = n.category;
        long when = n.when;
        // La cola no se toca desde el hilo principal (#105).
        GatewayQueue.run(() -> {
            if (posted(context, pkg, summary, ongoing, category, key, when, postedMs, title)) {
                // Uno por notificación: check revisa todo lo vencido y no repite lo revisado.
                handler.postDelayed(checkLater, Unseen.DELAY_MS + 1000);
            }
        });
    }

    /**
     * La decisión de vigilar una notificación, aparte de StatusBarNotification para
     * poder probarla. true si quedó anotada para revisarla a los 60 s.
     */
    public static boolean posted(Context context, String pkg, boolean summary, boolean ongoing, String category,
                                 String key, long when, long postedMs, CharSequence title) {
        if (!Unseen.tracks(pkg, summary, ongoing, category)) return false;
        return GatewayQueue.get(context).trackUnseen(key, when, postedMs, Unseen.title(title));
    }

    /**
     * Revisa las notificaciones cuya espera ya pasó: barre la bandeja por si el SMS
     * llegó y el receptor no lo vio, y si en la ventana no hay ningún SMS, encola el
     * sms.unseen. La llaman este servicio y el barrido periódico de SMSService, así
     * que también corre si Android reinició el servicio entre medio.
     */
    public static synchronized void check(Context context) {
        GatewayQueue queue = GatewayQueue.get(context);
        List<Object[]> due = queue.dueUnseen(System.currentTimeMillis());
        if (due.isEmpty()) return;
        Inbound.sweep(context);
        GatewayPrefs prefs = new GatewayPrefs(context);
        for (Object[] p : due) {
            String key = (String) p[0];
            long when = (Long) p[1];
            long postedMs = (Long) p[2];
            String title = (String) p[3];
            if (queue.seenSmsNear(postedMs, Unseen.WINDOW_MS)) {
                L.d(TAG, "la notificación tuvo su SMS");
            } else if (!prefs.isPaired()) {
                L.d(TAG, "sin emparejar: no se avisa");
            } else if (!prefs.accepts(Unseen.TYPE)) {
                if (!noticed) {
                    noticed = true;
                    L.d(TAG, "la pasarela no acepta sms.unseen: actualiza paynani");
                }
            } else if (Inbound.unseen(context, key, when, postedMs, title) != null) {
                new EventLogRepository(context).saveEvent("rcs_unseen", "", title);
            } else {
                continue;   // no se pudo encolar: se reintenta en la siguiente revisión
            }
            queue.doneUnseen(key, when);
        }
    }
}
