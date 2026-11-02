package com.iaaorgmx.paynani.webchat;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.iaaorgmx.paynani.Main2Activity;
import com.iaaorgmx.paynani.R;
import com.iaaorgmx.paynani.whatsapp.WhatsAppSource;

import java.util.List;

/**
 * La notificación de Android cuando el agente contesta en Webchat y la pantalla no está abierta
 * (paynani PRD #653 FR-11, Q323 = A), mientras la app esté conectada a paynani. La pasarela manda
 * webchat.changed a toda conexión con webchat.owner (FR-6); con messages se piden sólo los
 * mensajes posteriores al último que el dueño vio. Vive con SMSService, que siempre corre.
 */
public final class WebchatNotifier implements WebchatEvents.Listener {

    static final String CHANNEL_ID = "webchat";
    static final int NOTIFICATION_ID = 6530;
    private static final String PREFS = "webchat";
    private static final String LAST_SEEN = "last_seen";

    private static WebchatNotifier instance;
    /** La pantalla de Webchat está a la vista: lo que llega ya se ve y no se avisa. */
    private static volatile boolean screenVisible;

    private final Context context;
    private boolean busy;

    private WebchatNotifier(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Desde SMSService.onCreate, en el hilo principal. */
    public static synchronized void start(Context context) {
        if (instance != null) return;
        instance = new WebchatNotifier(context);
        createChannel(instance.context);
        WebchatEvents.add(instance);
    }

    public static synchronized void stop() {
        if (instance == null) return;
        WebchatEvents.remove(instance);
        instance = null;
    }

    /** La pantalla se abre o se cierra. Al abrirse, la notificación ya no hace falta. */
    static void setScreenVisible(Context context, boolean visible) {
        screenVisible = visible;
        if (visible) NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID);
    }

    /** El último mensaje que el dueño ya vio en la pantalla. */
    static void markSeen(Context context, String id) {
        if (id == null) return;
        prefs(context).edit().putString(LAST_SEEN, id).apply();
    }

    @Override
    public void onChanged(String what, String draft) {
        if (!WebchatEvents.MESSAGES.equals(what) || screenVisible || busy) return;
        WebchatSource source = WebchatSources.get(context);
        if (!source.available()) return;
        String lastSeen = prefs(context).getString(LAST_SEEN, null);
        busy = true;
        source.thread(lastSeen, null, new WhatsAppSource.Callback<WebchatSource.Thread>() {
            @Override
            public void onResult(WebchatSource.Thread t) {
                busy = false;
                String newest = WebchatRules.lastId(t.messages);
                if (lastSeen == null) {
                    // La primera vez sólo se toma la marca: no se avisa de lo que ya había.
                    markSeen(context, newest);
                    return;
                }
                List<WebchatSource.Message> unseen = WebchatRules.unseenFromAgent(t.messages, lastSeen);
                if (newest != null) markSeen(context, newest);
                if (!unseen.isEmpty() && !screenVisible) show(t.agentName, unseen);
            }

            @Override
            public void onError(WhatsAppSource.Failure failure) {
                busy = false;
            }
        });
    }

    private void show(String agentName, List<WebchatSource.Message> unseen) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        String title = agentName.isEmpty() ? context.getString(R.string.wc_notification_title_default) : agentName;
        String text = WebchatRules.preview(unseen.get(unseen.size() - 1));
        Intent open = new Intent(context, Main2Activity.class).putExtra(Main2Activity.EXTRA_OPEN, Main2Activity.OPEN_WEBCHAT)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(context, NOTIFICATION_ID, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        // En la pantalla bloqueada no se ve lo que dijo el agente, sólo que contestó.
        NotificationCompat.Builder publicVersion = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_paynani)
                .setContentTitle(context.getString(R.string.wc_notification_public));
        NotificationCompat.Builder b = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_paynani)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setNumber(unseen.size())
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion.build())
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pending);
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, b.build());
        } catch (SecurityException ignored) {
            // Sin permiso de notificaciones no se avisa.
        }
    }

    private static void createChannel(Context context) {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.wc_notification_channel), NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(context.getString(R.string.wc_notification_channel_desc));
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
