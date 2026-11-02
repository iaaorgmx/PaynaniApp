package com.iaaorgmx.paynani;
//PaynaniApp

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
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

import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.iaaorgmx.paynani.gateway.GatewayClient;
import com.iaaorgmx.paynani.gateway.GatewayPrefs;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Pantalla de estado: a qué pasarela está emparejado el teléfono, si está conectado,
 * si las notificaciones están activas y las versiones. Se refresca sola cada 2 segundos
 * mientras está a la vista.
 *
 * En tarjetas de Material3: arriba un resumen («Todo funciona» o lo que hay que revisar)
 * y luego Conexión, Teléfono y Acerca de.
 */
public class StatusFragment extends Fragment {

    // Cuánto tiene que llevar cortada la conexión para ofrecer emparejar de nuevo.
    // En debug, 1 minuto para poder probarlo; en release, 10.
    private static final long PAIR_AGAIN_AFTER_MS = (BuildConfig.DEBUG ? 1 : 10) * 60_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LayoutInflater themed;
    private View root;
    private LinearLayout connRows;
    private LinearLayout phoneRows;
    private LinearLayout aboutRows;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (isResumed()) render();
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        themed = inflater.cloneInContext(new ContextThemeWrapper(requireContext(), R.style.Theme_Paynani_M3));
        View v = themed.inflate(R.layout.fragment_status, container, false);
        root = v;
        connRows = v.findViewById(R.id.status_conn_rows);
        phoneRows = v.findViewById(R.id.status_phone_rows);
        aboutRows = v.findViewById(R.id.status_about_rows);
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

    private void render() {
        Context ctx = getContext();
        if (ctx == null || root == null) return;
        connRows.removeAllViews();
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

        // Teléfono: sin notificaciones no llega el aviso de que el agente contestó.
        boolean notify = NotificationManagerCompat.from(ctx).areNotificationsEnabled();
        View n = check(R.string.status_notifications,
                getString(notify ? R.string.status_notifications_ok : R.string.status_notifications_off), notify);
        if (!notify) {
            n.setOnClickListener(b -> startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.getPackageName())));
            problems.add(getString(R.string.status_notifications));
        }

        // Acerca de: el teléfono en paynani y las versiones.
        kv(aboutRows, R.string.status_device_id, prefs.getDeviceId());
        kv(aboutRows, R.string.status_app_version, getString(R.string.status_app_version_value,
                BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE));
        String server = prefs.getServerVersion();
        if (!paired) {
            kv(aboutRows, R.string.status_server_version, getString(R.string.status_server_version_unpaired));
        } else if (server.isEmpty()) {
            kv(aboutRows, R.string.status_server_version, getString(R.string.status_server_version_unknown));
        } else {
            kv(aboutRows, R.string.status_server_version, server);
        }

        summary(problems, connectedText, paired);
        root.findViewById(R.id.status_content).setVisibility(View.VISIBLE);
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
