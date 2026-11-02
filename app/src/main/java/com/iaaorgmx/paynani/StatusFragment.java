package com.iaaorgmx.paynani;
//PaynaniApp
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import com.iaaorgmx.paynani.gateway.DeviceStatus;
import com.iaaorgmx.paynani.gateway.GatewayClient;
import com.iaaorgmx.paynani.gateway.GatewayPrefs;
import com.iaaorgmx.paynani.gateway.GatewayQueue;
import com.iaaorgmx.paynani.gateway.RcsWatcher;
import com.iaaorgmx.paynani.gateway.Unseen;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pantalla de estado (APP-8, PROD-4): a qué pasarela está emparejado el teléfono,
 * si está conectado, qué falta por llegar a paynani, cuántos SMS salieron en la
 * última hora y si los permisos y la batería están en orden. Se refresca sola
 * cada 2 segundos mientras está a la vista.
 *
 * Desde #113, en tarjetas de Material3: arriba un resumen («Todo funciona» o lo que
 * hay que revisar) y luego Conexión, Mensajes de hoy, Teléfono y Acerca de.
 */
public class StatusFragment extends Fragment {

    /** Debajo de esta carga, sin cargar, el resumen pide revisar la batería. */
    private static final int LOW_BATTERY = 20;
    private static final String GOOGLE_MESSAGES = "com.google.android.apps.messaging";
    // Cuánto tiene que llevar cortada la conexión para ofrecer emparejar de nuevo
    // (#61). En debug, 1 minuto para poder probarlo; en release, 10.
    private static final long PAIR_AGAIN_AFTER_MS = (BuildConfig.DEBUG ? 1 : 10) * 60_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LayoutInflater themed;
    private View root;
    private LinearLayout connRows;
    private LinearLayout msgRows;
    private LinearLayout phoneRows;
    private LinearLayout aboutRows;
    private View fix;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            load();
            handler.postDelayed(this, 2000);
        }
    };

    /**
     * Lee de la cola fuera del hilo principal (#105) y luego pinta:
     * {sms.status en cola, todo lo que hay en cola, enviados en la última hora}.
     */
    private void load() {
        Context ctx = getContext();
        if (ctx == null) return;
        GatewayQueue queue = GatewayQueue.get(ctx);
        GatewayQueue.run(() -> {
            int[] counts = {queue.count("sms.status"), queue.count(null),
                    queue.sentLastHour(System.currentTimeMillis())};
            handler.post(() -> {
                if (isResumed()) render(counts);
            });
        });
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        // Solo esta pantalla usa Material3 por ahora (#113).
        themed = inflater.cloneInContext(new ContextThemeWrapper(requireContext(), R.style.Theme_Paynani_M3));
        View v = themed.inflate(R.layout.fragment_status, container, false);
        root = v;
        connRows = v.findViewById(R.id.status_conn_rows);
        msgRows = v.findViewById(R.id.status_msg_rows);
        phoneRows = v.findViewById(R.id.status_phone_rows);
        aboutRows = v.findViewById(R.id.status_about_rows);
        fix = v.findViewById(R.id.status_fix);
        // Sin datos todavía, las tarjetas saldrían vacías: se muestran con la primera lectura.
        v.findViewById(R.id.status_content).setVisibility(View.INVISIBLE);
        fix.setOnClickListener(b -> startActivity(new Intent(getActivity(), PermissionActivity.class)));
        return v;
    }

    @Override public void onResume() {
        super.onResume();
        handler.post(refresh);
    }

    @Override public void onPause() {
        super.onPause();
        handler.removeCallbacks(refresh);
    }

    private void render(int[] counts) {
        Context ctx = getContext();
        if (ctx == null || root == null) return;
        connRows.removeAllViews();
        msgRows.removeAllViews();
        phoneRows.removeAllViews();
        aboutRows.removeAllViews();
        GatewayPrefs prefs = new GatewayPrefs(ctx);
        GatewayClient client = GatewayClient.get(ctx);
        // Lo que el resumen pide revisar, con el nombre corto de cada cosa.
        List<String> problems = new ArrayList<>();

        // Conexión
        Chip chip = root.findViewById(R.id.status_conn_chip);
        long since = client.connectedSince();
        String connectedText = null;
        boolean paired = prefs.isPaired();
        String host = Uri.parse(prefs.getWsUrl()).getHost();
        if (paired) kv(connRows, R.string.status_gateway, host != null ? host : "");
        if (!paired) {
            // Sin emparejar (#122): no es una falla, todavía no hay pasarela.
            chip(chip, R.string.status_chip_unpaired, false);
            kv(connRows, 0, getString(R.string.status_unpaired_conn));
        } else if (prefs.isRevoked()) {
            chip(chip, R.string.status_chip_revoked, false);
            kv(connRows, 0, getString(R.string.status_revoked));
            problems.add(getString(R.string.status_connection));
            pairAgainButton();
        } else if (since > 0) {
            chip(chip, R.string.status_chip_online, true);
            connectedText = getString(R.string.status_connected_since, time(ctx, since));
            kv(connRows, 0, connectedText);
        } else {
            chip(chip, R.string.status_chip_offline, false);
            // Desde cuándo está cortada (hallazgo 3b de QA-4). Si la app murió con la
            // conexión abierta no hay hora de corte: queda la de la última conexión.
            long cut = prefs.getDisconnectedAt();
            long last = prefs.getLastConnected();
            String text;
            if (cut > 0) text = getString(R.string.status_disconnected_since, time(ctx, cut));
            else if (last > 0) text = getString(R.string.status_last_connected, time(ctx, last));
            else text = getString(R.string.status_never_connected);
            kv(connRows, 0, text);
            problems.add(getString(R.string.status_connection));
            long down = cut > 0 ? cut : last;
            if (down > 0 && System.currentTimeMillis() - down >= PAIR_AGAIN_AFTER_MS) pairAgainButton();
        }

        // Mensajes de hoy
        int[] today = prefs.deliveredToday();
        int statuses = counts[0];
        int inbound = counts[1] - statuses;
        setText(R.id.status_tile_sms_num, String.valueOf(today[0]));
        setText(R.id.status_tile_calls_num, String.valueOf(today[1]));
        setText(R.id.status_tile_queue_num, String.valueOf(inbound + statuses));
        long lastAt = prefs.getLastDeliveredAt();
        if (lastAt > 0) {
            String who = prefs.getLastDeliveredFrom().isEmpty() ? getString(R.string.status_hidden) : prefs.getLastDeliveredFrom();
            int fmt = "sms.in".equals(prefs.getLastDeliveredType()) ? R.string.status_last_sms : R.string.status_last_call;
            kv(msgRows, R.string.status_last, getString(fmt, time(ctx, lastAt), who));
        } else {
            kv(msgRows, R.string.status_last, getString(R.string.status_last_none));
        }
        kv(msgRows, R.string.status_queue, getString(R.string.status_queue_value,
                getResources().getQuantityString(R.plurals.status_queue_inbound, inbound, inbound),
                getResources().getQuantityString(R.plurals.status_queue_statuses, statuses, statuses)));
        kv(msgRows, R.string.status_sent_hour, getString(R.string.status_sent_hour_value,
                counts[2], prefs.getMaxOutPerHour()));

        // Teléfono: la carga, la que lee ahora; los avisos los decide la pasarela (#109).
        DeviceStatus.Reading battery = DeviceStatus.read(ctx, System.currentTimeMillis());
        TextView pct = root.findViewById(R.id.status_battery_pct);
        TextView state = root.findViewById(R.id.status_battery_state);
        LinearProgressIndicator bar = root.findViewById(R.id.status_battery_bar);
        TextView note = root.findViewById(R.id.status_battery_note);
        if (battery == null) {
            pct.setText(R.string.status_device_battery);
            state.setText(R.string.status_device_battery_unknown);
            bar.setVisibility(View.GONE);
        } else {
            boolean low = !battery.charging && battery.pct < LOW_BATTERY;
            pct.setText(getString(R.string.status_battery_pct, battery.pct));
            state.setText(battery.charging ? R.string.status_battery_charging : R.string.status_battery_discharging);
            state.setTextColor(color(low ? R.color.status_warn_text : R.color.paynani_gris));
            bar.setVisibility(View.VISIBLE);
            bar.setProgressCompat(battery.pct, false);
            bar.setIndicatorColor(color(low ? R.color.status_warn : R.color.status_ok));
            if (low) problems.add(getString(R.string.status_battery_pct, battery.pct));
        }
        boolean needsUpdate = battery != null && prefs.isPaired() && !prefs.accepts(DeviceStatus.TYPE);
        note.setText(R.string.status_device_battery_update);
        note.setVisibility(needsUpdate ? View.VISIBLE : View.GONE);

        List<String> missing = PermissionActivity.missing(ctx);
        if (missing.isEmpty()) {
            check(R.string.status_permissions, getString(R.string.status_permissions_ok), true);
        } else {
            Set<String> names = new LinkedHashSet<>();
            for (String p : missing) names.add(getString(PermissionActivity.label(p)));
            check(R.string.status_permissions, getString(R.string.status_permissions_missing,
                    android.text.TextUtils.join(", ", new ArrayList<>(names))), false)
                    .setOnClickListener(b -> startActivity(new Intent(getActivity(), PermissionActivity.class)));
            problems.add(getString(R.string.status_permissions));
        }
        boolean optimized = PermissionActivity.batteryOptimized(ctx);
        check(R.string.status_battery, getString(optimized ? R.string.status_battery_on : R.string.status_battery_ok),
                !optimized);
        if (optimized) problems.add(getString(R.string.status_battery));
        // El ahorro de energía del fabricante (DuraSpeed, MIUI…) cierra la app aunque
        // la batería esté bien (#84, #86). Sólo si el teléfono tiene esa pantalla.
        BackgroundScreens.Screen background = BackgroundScreens.find(ctx);
        if (background != null) {
            boolean allowed = BackgroundScreens.confirmed(ctx);
            View bg = check(R.string.status_background, allowed ? getString(R.string.status_background_ok)
                    : getString(R.string.status_background_pending, background.vendor), allowed);
            if (!allowed) {
                bg.setOnClickListener(b -> BackgroundScreens.showGuide(ctx, background, this::load));
                problems.add(getString(R.string.status_background));
            }
        }
        // Lo que llega por RCS no pasa por SMS y la app no lo ve (#46, #54).
        boolean rcsOff = PermissionActivity.rcsOffConfirmed(ctx);
        View rcs = check(R.string.status_rcs, getString(rcsOff ? R.string.status_rcs_ok : R.string.status_rcs_pending),
                rcsOff);
        if (!rcsOff) {
            rcs.setOnClickListener(b -> showRcsGuide());
            problems.add(getString(R.string.status_rcs));
        }
        // A4: si se puede detectar un RCS que no llegó como SMS (#55).
        if (!RcsWatcher.enabled(ctx)) {
            check(R.string.status_rcs_detector, getString(R.string.status_rcs_detector_off), false)
                    .setOnClickListener(b -> showDetectorGuide());
            problems.add(getString(R.string.status_rcs_detector));
        } else if (prefs.isPaired() && !prefs.accepts(Unseen.TYPE)) {
            // Sin emparejar (#122) no hay pasarela que actualizar.
            check(R.string.status_rcs_detector, getString(R.string.status_rcs_detector_update), false);
            problems.add(getString(R.string.status_rcs_detector));
        } else {
            check(R.string.status_rcs_detector, getString(R.string.status_rcs_detector_on), true);
        }

        // Acerca de: el teléfono en paynani y las versiones (#63).
        kv(aboutRows, R.string.status_device_id, prefs.getDeviceId());
        kv(aboutRows, R.string.status_app_version, getString(R.string.status_app_version_value,
                BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE));
        String server = prefs.getServerVersion();
        if (!prefs.isPaired()) {
            kv(aboutRows, R.string.status_server_version, getString(R.string.status_server_version_unpaired));
        } else if (server.isEmpty()) {
            kv(aboutRows, R.string.status_server_version, getString(R.string.status_server_version_unknown));
        } else {
            kv(aboutRows, R.string.status_server_version, server);
        }

        summary(problems, connectedText, paired);
        root.findViewById(R.id.status_content).setVisibility(View.VISIBLE);
        fix.setVisibility(missing.isEmpty() && !optimized ? View.GONE : View.VISIBLE);
    }

    /** La tarjeta de arriba: «Todo funciona», cuántas cosas hay que revisar y cuáles, o «Sin emparejar». */
    private void summary(List<String> problems, String connectedText, boolean paired) {
        View pair = root.findViewById(R.id.status_summary_pair);
        pair.setVisibility(paired ? View.GONE : View.VISIBLE);
        pair.setOnClickListener(paired ? null : v -> startActivity(
                new Intent(requireContext(), com.iaaorgmx.paynani.gateway.PairActivity.class)));
        if (!paired) {
            MaterialCardView card = root.findViewById(R.id.status_summary);
            card.setCardBackgroundColor(color(R.color.status_warn_bg));
            ((ImageView) root.findViewById(R.id.status_summary_icon)).setImageResource(R.drawable.ic_status_warn);
            setText(R.id.status_summary_title, getString(R.string.status_unpaired_title));
            setText(R.id.status_summary_sub, getString(R.string.status_unpaired_sub));
            TextView detail = root.findViewById(R.id.status_summary_detail);
            // Lo demás que falte (permisos, RCS…) se sigue diciendo.
            detail.setText(android.text.TextUtils.join(" · ", problems));
            detail.setTextColor(color(R.color.status_warn_text));
            detail.setVisibility(problems.isEmpty() ? View.GONE : View.VISIBLE);
            return;
        }
        boolean ok = problems.isEmpty();
        MaterialCardView card = root.findViewById(R.id.status_summary);
        card.setCardBackgroundColor(color(ok ? R.color.status_ok_bg : R.color.status_warn_bg));
        ((ImageView) root.findViewById(R.id.status_summary_icon))
                .setImageResource(ok ? R.drawable.ic_status_ok : R.drawable.ic_status_warn);
        setText(R.id.status_summary_title, getString(ok ? R.string.status_summary_ok : R.string.status_summary_attention));
        setText(R.id.status_summary_sub, ok ? getString(R.string.status_summary_ok_sub)
                : getResources().getQuantityString(R.plurals.status_summary_count, problems.size(), problems.size()));
        TextView detail = root.findViewById(R.id.status_summary_detail);
        String text = ok ? connectedText : android.text.TextUtils.join(" · ", problems);
        detail.setText(text);
        detail.setTextColor(color(ok ? R.color.status_ok : R.color.status_warn_text));
        detail.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** El atajo a «Emparejar de nuevo» debajo de la Conexión (#61). */
    private void pairAgainButton() {
        Button b = (Button) themed.inflate(R.layout.status_pair_again, connRows, false);
        b.setOnClickListener(v -> {
            if (getActivity() != null) PairAgain.confirm(getActivity());
        });
        connRows.addView(b);
    }

    /** Los pasos para apagar RCS, con atajo a Google Messages y la confirmación. */
    private void showRcsGuide() {
        Context ctx = getContext();
        if (ctx == null) return;
        AlertDialog.Builder b = new AlertDialog.Builder(ctx)
                .setTitle(R.string.rcs_guide_title)
                .setMessage(R.string.rcs_guide_steps)
                .setPositiveButton(R.string.rcs_guide_done, (d, w) -> {
                    PermissionActivity.setRcsOffConfirmed(ctx, true);
                    load();
                })
                .setNegativeButton(R.string.rcs_guide_later, null);
        Intent messages = ctx.getPackageManager().getLaunchIntentForPackage(GOOGLE_MESSAGES);
        if (messages != null) {
            b.setNeutralButton(R.string.rcs_guide_open, (d, w) -> startActivity(messages));
        }
        b.show();
    }

    /**
     * Lleva al acceso a las notificaciones. Desde Android 13 un APK instalado a mano
     * no aparece ahí hasta permitir los ajustes restringidos, así que antes se explica.
     */
    private void showDetectorGuide() {
        Context ctx = getContext();
        if (ctx == null) return;
        int message = Build.VERSION.SDK_INT >= 33 ? R.string.rcs_detector_guide_restricted : R.string.rcs_detector_guide;
        new AlertDialog.Builder(ctx)
                .setTitle(R.string.rcs_detector_guide_title)
                .setMessage(message)
                .setPositiveButton(R.string.rcs_detector_guide_open, (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                    } catch (Exception e) {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + ctx.getPackageName())));
                    }
                })
                .setNegativeButton(R.string.rcs_guide_later, null)
                .show();
    }

    private static String time(Context ctx, long ms) {
        Date d = new Date(ms);
        return DateFormat.getMediumDateFormat(ctx).format(d) + " " + DateFormat.getTimeFormat(ctx).format(d);
    }

    /** Una fila de etiqueta y valor; sin etiqueta (0) solo el valor. */
    private void kv(LinearLayout parent, int label, String value) {
        View row = themed.inflate(R.layout.status_kv, parent, false);
        TextView l = row.findViewById(R.id.status_kv_label);
        if (label == 0) l.setVisibility(View.GONE);
        else l.setText(label);
        ((TextView) row.findViewById(R.id.status_kv_value)).setText(value);
        parent.addView(row);
    }

    /** Una revisión del teléfono con su ícono; la devuelve por si hay que hacerla tocable. */
    private View check(int label, String value, boolean ok) {
        View row = themed.inflate(R.layout.status_check, phoneRows, false);
        ((ImageView) row.findViewById(R.id.status_check_icon))
                .setImageResource(ok ? R.drawable.ic_status_ok : R.drawable.ic_status_warn);
        ((TextView) row.findViewById(R.id.status_check_label)).setText(label);
        TextView v = row.findViewById(R.id.status_check_value);
        v.setText(value);
        v.setTextColor(color(ok ? R.color.status_ok : R.color.status_warn_text));
        // El ícono no se lee: el estado va en palabras.
        row.setContentDescription(getString(label) + ". " + value + ". "
                + getString(ok ? R.string.status_check_ok : R.string.status_check_attention));
        phoneRows.addView(row);
        return row;
    }

    private void chip(Chip chip, int text, boolean ok) {
        chip.setText(text);
        chip.setChipIconResource(ok ? R.drawable.ic_status_ok : R.drawable.ic_status_warn);
        chip.setChipIconVisible(true);
        chip.setChipBackgroundColor(android.content.res.ColorStateList.valueOf(
                color(ok ? R.color.status_ok_bg : R.color.status_warn_bg)));
        chip.setTextColor(color(ok ? R.color.status_ok : R.color.status_warn_text));
    }

    private void setText(int id, String text) {
        ((TextView) root.findViewById(id)).setText(text);
    }

    private int color(int res) {
        return ContextCompat.getColor(requireContext(), res);
    }
}
