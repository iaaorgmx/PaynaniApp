package com.iaaorgmx.paynani;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

import com.iaaorgmx.paynani.gateway.DeviceStatus;
import com.iaaorgmx.paynani.gateway.GatewayClient;
import com.iaaorgmx.paynani.gateway.GatewayPrefs;
import com.iaaorgmx.paynani.gateway.Inbound;
import com.iaaorgmx.paynani.gateway.GatewayQueue;
import com.iaaorgmx.paynani.gateway.RcsWatcher;
import com.iaaorgmx.paynani.webchat.WebchatNotifier;

import java.util.Timer;
import java.util.TimerTask;

/**
 * Servicio en primer plano que mantiene viva la conexión con la pasarela (APP-3)
 * y corre el barrido de respaldo de la bandeja (APP-4). La notificación dice si
 * el teléfono está conectado a paynani.
 */
public class SMSService extends Service implements GatewayClient.StateListener {

    AppPrefs prefs;
    PowerManager.WakeLock mWakeLock;
    PowerManager pm;
    private Timer timer;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    /** Conectar o desconectar la corriente manda la batería en el momento (#109). */
    private final BroadcastReceiver power = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            L.e("Service", "corriente: " + intent.getAction());
            Context app = context.getApplicationContext();
            main.postDelayed(() -> GatewayQueue.run(() -> DeviceStatus.report(app, true)), DeviceStatus.POWER_DELAY_MS);
        }
    };
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "PaynaniApp Keep Alive Service";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (mWakeLock != null && !mWakeLock.isHeld()) {
            mWakeLock.acquire();
        }

        if (!new GatewayPrefs(getApplicationContext()).isPaired()) {
            // Sin emparejamiento no hay a dónde conectarse: no se queda un servicio vacío.
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return Service.START_NOT_STICKY;
        }

        prefs = new AppPrefs(getApplicationContext());
        GatewayClient client = GatewayClient.get(getApplicationContext());
        client.setStateListener(this);
        client.start();

        if (timer == null) {
            String timeValue = prefs.getTimeServiceValue();
            long min = (timeValue != null && !timeValue.isEmpty()) ? Long.parseLong(timeValue) : 1;
            long intervalo = (min * 60000);
            L.e("Service", "onStartCommand-> Intervalo: " + intervalo);

            timer = new Timer();
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    if (prefs.getSwitchSetting("switch_service", true)) {
                        int n = Inbound.sweep(getApplicationContext());
                        if (n > 0) L.e("Service", "barrido: " + n + " SMS encolados");
                        // Las notificaciones de Google Messages vencidas, por si Android
                        // reinició el detector antes de los 60 s (#55).
                        RcsWatcher.check(getApplicationContext());
                    }
                    // La batería, aunque el reenvío esté pausado: el teléfono sigue conectado (#109).
                    DeviceStatus.report(getApplicationContext(), false);
                }
            }, 0, intervalo);
        }

        return Service.START_STICKY;
    }

    @Override
    public void onGatewayState(GatewayClient.State state) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(state));
    }

    private Notification buildNotification(GatewayClient.State state) {
        Intent notificationIntent = new Intent(this, SplashActivity.class);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingFlags);

        int text;
        switch (state) {
            case CONNECTED: text = R.string.gateway_state_connected; break;
            case REVOKED: text = R.string.gateway_state_revoked; break;
            case UNPAIRED: text = R.string.gateway_state_unpaired; break;
            default: text = R.string.gateway_state_disconnected; break;
        }
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(text))
                .setSmallIcon(R.drawable.ic_stat_paynani)
                .setContentIntent(pendingIntent)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createNotificationChannel() {
        NotificationChannel serviceChannel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
        );
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(serviceChannel);
        }
    }

    @SuppressLint("InvalidWakeLockTag")
    @Override
    public void onCreate() {
        super.onCreate();

        pm = (PowerManager) getSystemService(POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PaynaniApp:SystemService");

        createNotificationChannel();
        // Webchat (paynani PRD #653 FR-11): avisa cuando el agente contesta y la pantalla no está abierta.
        WebchatNotifier.start(getApplicationContext());
        IntentFilter powerFilter = new IntentFilter(Intent.ACTION_POWER_CONNECTED);
        powerFilter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        registerReceiver(power, powerFilter);
        Notification notification = buildNotification(GatewayClient.get(getApplicationContext()).state());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (timer != null) timer.cancel();
        unregisterReceiver(power);
        main.removeCallbacksAndMessages(null);
        GatewayClient.get(getApplicationContext()).setStateListener(null);
        WebchatNotifier.stop();
        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
        }
        L.e("OnDestroyService", "Ondestroy");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
