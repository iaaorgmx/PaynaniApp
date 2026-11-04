package com.iaaorgmx.paynani.agent;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.iaaorgmx.paynani.R;
import com.iaaorgmx.paynani.webchat.WebchatRules;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * La pantalla «Agente» (#11, Metis[Q56] = A): lo mismo que `/agent` en PaynaniWeb, de arriba abajo
 * (agent_pages.body): el agente y su estado, las cuatro cifras de hoy, «El agente», «Canales», lo
 * que hizo hoy y qué hacer si cambia el estado. Los datos vienen de `agent.get` (paynani #692), y
 * se vuelven a pedir con `agent.changed` o al reconectar.
 */
public class AgentActivity extends AppCompatActivity implements AgentEvents.Listener {

    private LayoutInflater themed;
    private LinearLayout content;
    private int request;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_agent);
        setSupportActionBar(findViewById(R.id.agent_toolbar));
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.agent_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        themed = LayoutInflater.from(new ContextThemeWrapper(this, R.style.Theme_Paynani_M3));
        content = findViewById(R.id.agent_content);
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        AgentEvents.add(this);
        load();
    }

    @Override
    protected void onPause() {
        super.onPause();
        AgentEvents.remove(this);
    }

    @Override
    public void onAgentChanged(String status) {
        load();
    }

    private void load() {
        if (!GatewayAgent.connected(this) || !GatewayAgent.announced(this)) {
            message(getString(R.string.agent_offline), true);
            return;
        }
        int mine = ++request;
        GatewayAgent.get(this, new GatewayAgent.Callback() {
            @Override
            public void onResult(Agent agent) {
                if (mine == request && !isFinishing()) render(agent);
            }

            @Override
            public void onError(String code) {
                if (mine != request || isFinishing()) return;
                message("offline".equals(code) || "timeout".equals(code) ? getString(R.string.agent_offline)
                        : getString(R.string.agent_error, code), true);
            }
        });
    }

    private void message(String text, boolean retry) {
        content.removeAllViews();
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        t.setPadding(0, dp(24), 0, dp(12));
        content.addView(t);
        if (retry) {
            Button b = new Button(this);
            b.setText(R.string.agent_retry);
            b.setOnClickListener(v -> load());
            content.addView(b);
        }
    }

    // --- La página ------------------------------------------------------------------------------

    private void render(Agent a) {
        content.removeAllViews();
        header(a);
        kpis(a);
        LinearLayout agentRows = card(getString(R.string.agent_sec_agent));
        agentRows(agentRows, a);
        LinearLayout channelRows = card(getString(R.string.agent_sec_channels));
        channelRows(channelRows, a);
        LinearLayout activity = card(getString(R.string.agent_sec_activity));
        activityRows(activity, a);
        LinearLayout help = card(getString(R.string.agent_sec_help));
        help(help);
    }

    /** El nombre, «Tu agente de IA · correo · responde en …» y la caja del estado. */
    private void header(Agent a) {
        TextView name = new TextView(this);
        name.setText(a.name);
        name.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall);
        name.setTextColor(color(R.color.paynani_tinta));
        content.addView(name);
        TextView lead = new TextView(this);
        lead.setText(getString(R.string.agent_lead, nz(a.email), nz(a.runtimeLabel)));
        lead.setTextColor(color(R.color.paynani_gris));
        content.addView(lead);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(12));
        int tone = AgentRules.tone(a.status);
        box.setBackground(rounded(bg(tone)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        box.setLayoutParams(lp);
        TextView title = new TextView(this);
        title.setText(getString(R.string.agent_status_dot, statusLabel(a.status)));
        title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        title.setTextColor(fg(tone));
        box.addView(title);
        for (String line : statusLines(a)) {
            TextView t = new TextView(this);
            t.setText(line);
            t.setTextColor(color(R.color.paynani_tinta));
            box.addView(t);
        }
        content.addView(box);
    }

    /** `_status_box` de agent_pages.py. */
    private List<String> statusLines(Agent a) {
        List<String> lines = new ArrayList<>();
        String runtime = nz(a.runtimeLabel);
        switch (a.status) {
            case AgentRules.OFFLINE:
                lines.add("push".equals(a.kind) && "active".equals(a.dispatcher)
                        ? getString(R.string.agent_offline_push, runtime) : getString(R.string.agent_offline_detail));
                break;
            case AgentRules.UNKNOWN:
                lines.add(getString(R.string.agent_unknown_detail));
                break;
            case AgentRules.NO_SESSION:
                lines.add("spool".equals(a.kind) ? getString(R.string.agent_no_session_spool, runtime)
                        : getString(R.string.agent_no_session_detail));
                break;
            default:
                if (a.kind != null && !"watch".equals(a.kind)) {
                    lines.add(getString(R.string.agent_online_other, runtime));
                    if (a.lastAccepted != null) lines.add(getString(R.string.agent_accepted_at, clock(a.lastAccepted)));
                } else {
                    if (a.sessionArmedAt != null) lines.add(getString(R.string.agent_since, clock(a.sessionArmedAt)));
                    if (a.sessionExpiresAt != null) lines.add(getString(R.string.agent_renews, clock(a.sessionExpiresAt)));
                }
        }
        return lines;
    }

    /** Las cuatro cifras de hoy, en dos filas de dos. */
    private void kpis(Agent a) {
        LinearLayout row1 = kpiRow(), row2 = kpiRow();
        kpi(row1, getString(R.string.agent_kpi_handled), String.valueOf(a.replied + a.closed),
                getString(R.string.agent_kpi_handled_sub, String.valueOf(a.replied), String.valueOf(a.closed)));
        kpi(row1, getString(R.string.agent_kpi_waiting), String.valueOf(a.waiting),
                getString(a.waiting > 0 ? R.string.agent_kpi_waiting_sub : R.string.agent_kpi_waiting_none));
        kpi(row2, getString(R.string.agent_kpi_last), a.lastReplyAt != null ? clock(a.lastReplyAt) : "—",
                a.lastReplyAt != null ? getString(R.string.agent_kpi_last_sub, nz(a.lastReplyWho), channelName(a.lastReplyChannel))
                        : getString(R.string.agent_kpi_last_none));
        kpi(row2, getString(R.string.agent_kpi_median),
                a.replyMedianS != null ? AgentRules.duration(a.replyMedianS) : "—",
                getString(a.replyMedianS != null ? R.string.agent_kpi_median_sub : R.string.agent_kpi_median_none));
    }

    private LinearLayout kpiRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        row.setLayoutParams(lp);
        content.addView(row);
        return row;
    }

    private void kpi(LinearLayout row, String label, String value, String sub) {
        View k = themed.inflate(R.layout.agent_kpi, row, false);
        ((TextView) k.findViewById(R.id.agent_kpi_label)).setText(label);
        ((TextView) k.findViewById(R.id.agent_kpi_value)).setText(value);
        ((TextView) k.findViewById(R.id.agent_kpi_sub)).setText(sub);
        row.addView(k);
    }

    /** `_agent_rows`: el harness, la vigilancia o la última entrega, el despachador y la versión. */
    private void agentRows(LinearLayout rows, Agent a) {
        int tone = AgentRules.tone(a.status);
        row(rows, getString(R.string.agent_row_harness), nz(a.runtimeLabel), statusLabel(a.status), tone);
        if (a.kind == null || "watch".equals(a.kind)) {
            if (a.sessionLive) {
                row(rows, getString(R.string.agent_row_watch),
                        a.sessionArmedAt != null ? getString(R.string.agent_row_watch_armed, clock(a.sessionArmedAt)) : "",
                        getString(R.string.agent_pill_armed), AgentRules.TONE_OK);
            } else {
                row(rows, getString(R.string.agent_row_watch), getString(R.string.agent_row_watch_none),
                        getString(R.string.agent_pill_not_armed), AgentRules.TONE_WARN);
            }
        } else {
            row(rows, getString(R.string.agent_row_delivery),
                    a.lastAccepted != null ? getString(R.string.agent_row_delivery_at, clock(a.lastAccepted))
                            : getString(R.string.agent_row_delivery_none), statusLabel(a.status), tone);
        }
        if (a.dispatcher != null) {
            row(rows, getString(R.string.agent_row_dispatcher), getString(R.string.agent_row_dispatcher_detail),
                    unitLabel(a.dispatcher), AgentRules.unitTone(a.dispatcher));
        }
        String installed = a.version == null || a.version.isEmpty() ? "?" : a.version;
        boolean newer = AgentRules.newer(a.latestVersion, a.version);
        row(rows, getString(R.string.agent_row_version), getString(R.string.agent_row_version_detail, installed),
                newer ? getString(R.string.agent_pill_update, a.latestVersion)
                        : a.latestVersion != null && !a.latestVersion.isEmpty() ? getString(R.string.agent_pill_latest) : null,
                newer ? AgentRules.TONE_WARN : AgentRules.TONE_OK);
    }

    /** `_channel_rows`: el correo, la pasarela de SMS, WhatsApp y Telegram si están. */
    private void channelRows(LinearLayout rows, Agent a) {
        row(rows, getString(R.string.agent_ch_email),
                getString(R.string.agent_ch_email_detail, nz(a.email), String.valueOf(a.rosterCount)),
                a.listener == null ? null : unitLabel(a.listener), AgentRules.unitTone(a.listener));
        if (a.phone == null) {
            row(rows, getString(R.string.agent_ch_sms), getString(R.string.agent_no_phone),
                    getString(R.string.agent_state_unpaired), AgentRules.TONE_OFF);
        } else {
            String battery = a.phone.battery != null ? a.phone.battery + " %" : "—";
            row(rows, getString(R.string.agent_ch_sms), getString(R.string.agent_phone_detail,
                    a.phone.model != null ? a.phone.model : "—", a.phone.appVersion != null ? a.phone.appVersion : "—",
                    battery, ago(a.phone.lastSeenAgeS)),
                    getString(a.phone.online ? R.string.agent_state_online : R.string.agent_state_offline),
                    a.phone.online ? AgentRules.TONE_OK : AgentRules.TONE_BAD);
        }
        if (a.whatsapp != null) {
            row(rows, getString(R.string.agent_ch_whatsapp), "", label("agent_wa_" + a.whatsapp, a.whatsapp),
                    AgentRules.whatsappTone(a.whatsapp));
        }
        if (a.telegramState != null) {
            row(rows, getString(R.string.agent_ch_telegram), a.telegramBot != null ? "@" + a.telegramBot : "",
                    label("agent_tg_" + a.telegramState, a.telegramState), AgentRules.telegramTone(a.telegramState));
        }
    }

    /** «Lo que hizo el agente hoy»: quién, qué, el estado y la hora. */
    private void activityRows(LinearLayout rows, Agent a) {
        if (a.activity.isEmpty()) {
            TextView none = new TextView(this);
            none.setText(R.string.agent_activity_none);
            none.setTextColor(color(R.color.paynani_gris));
            none.setPadding(0, dp(8), 0, 0);
            rows.addView(none);
            return;
        }
        for (Agent.Activity act : a.activity) {
            String state = activityState(act);
            row(rows, nz(act.who), activityWhat(act) + (act.at != null ? " · " + clock(act.at) : ""),
                    state, AgentRules.TONE_OFF);
        }
    }

    /** «Si cambia el estado»: qué significa cada estado que no es «Online». */
    private void help(LinearLayout rows) {
        String[] keys = {AgentRules.NO_SESSION, AgentRules.OFFLINE, AgentRules.UNKNOWN};
        int[] texts = {R.string.agent_help_no_session, R.string.agent_help_offline, R.string.agent_help_unknown};
        for (int i = 0; i < keys.length; i++) {
            View r = themed.inflate(R.layout.agent_row, rows, false);
            TextView pill = r.findViewById(R.id.agent_row_pill);
            ((TextView) r.findViewById(R.id.agent_row_name)).setText(getString(texts[i]));
            ((TextView) r.findViewById(R.id.agent_row_name)).setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
            r.findViewById(R.id.agent_row_detail).setVisibility(View.GONE);
            pill(pill, statusLabel(keys[i]), AgentRules.tone(keys[i]));
            // La píldora va arriba del texto, como el <dt> de la web; en columnas, con píldoras de
            // distinto ancho, cada texto empezaba en otro lugar.
            LinearLayout row = (LinearLayout) r;
            row.setOrientation(LinearLayout.VERTICAL);
            row.setGravity(android.view.Gravity.START);
            row.removeView(pill);
            row.addView(pill, 0);
            row.getChildAt(1).setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) pill.getLayoutParams();
            lp.setMarginStart(0);
            lp.bottomMargin = dp(6);
            rows.addView(r);
        }
    }

    // --- Piezas ---------------------------------------------------------------------------------

    private LinearLayout card(String title) {
        View c = themed.inflate(R.layout.agent_card, content, false);
        ((TextView) c.findViewById(R.id.agent_card_title)).setText(title);
        content.addView(c);
        return c.findViewById(R.id.agent_card_body);
    }

    private void row(LinearLayout parent, String name, String detail, String pillText, int tone) {
        View r = themed.inflate(R.layout.agent_row, parent, false);
        ((TextView) r.findViewById(R.id.agent_row_name)).setText(name);
        TextView d = r.findViewById(R.id.agent_row_detail);
        if (detail == null || detail.isEmpty()) d.setVisibility(View.GONE);
        else d.setText(detail);
        TextView pill = r.findViewById(R.id.agent_row_pill);
        if (pillText == null || pillText.isEmpty()) pill.setVisibility(View.GONE);
        else pill(pill, pillText, tone);
        parent.addView(r);
    }

    private void pill(TextView pill, String text, int tone) {
        pill.setText(text);
        pill.setTextColor(fg(tone));
        GradientDrawable g = rounded(bg(tone));
        g.setCornerRadius(dp(999));
        pill.setBackground(g);
    }

    private GradientDrawable rounded(int fill) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(12));
        return g;
    }

    private String statusLabel(String status) {
        switch (AgentRules.status(status)) {
            case AgentRules.ONLINE: return getString(R.string.agent_status_online);
            case AgentRules.NO_SESSION: return getString(R.string.agent_status_no_session);
            case AgentRules.OFFLINE: return getString(R.string.agent_status_offline);
            default: return getString(R.string.agent_status_unknown);
        }
    }

    private String unitLabel(String unit) {
        if ("active".equals(unit)) return getString(R.string.agent_state_active);
        if (unit == null || "unknown".equals(unit)) return getString(R.string.agent_state_unknown);
        return getString(R.string.agent_state_inactive);
    }

    private String channelName(String channel) {
        if (channel == null) return "";
        return label("agent_channel_" + channel, channel);
    }

    /** `_act_what` de dashboard_pages.py. */
    private String activityWhat(Agent.Activity a) {
        String ch = nz(a.channel);
        switch (ch) {
            case "mail": case "sent": return nz(a.what);
            case "sms": return getString(R.string.agent_act_sms);
            case "call": return label("agent_act_" + nz(a.state), nz(a.state));
            case "whatsapp": return getString(R.string.agent_act_whatsapp);
            case "telegram": return getString(R.string.agent_act_telegram);
            case "webchat": return getString(R.string.agent_act_webchat);
            default:
                String w = nz(a.what);
                return label("agent_act_roster_" + ("add".equals(w) || "edit".equals(w) || "delete".equals(w) ? w : "other"), w);
        }
    }

    /** `_act_state` de dashboard_pages.py; null si la web no pone estado. */
    private String activityState(Agent.Activity a) {
        String ch = nz(a.channel), st = nz(a.state);
        boolean closedChat = ("whatsapp".equals(ch) || "telegram".equals(ch) || "webchat".equals(ch))
                && ("handled".equals(st) || "replied".equals(st) || "closed".equals(st));
        if ("mail".equals(ch) || closedChat) return label("agent_mail_" + st, st);
        if ("sent".equals(ch)) return getString(R.string.agent_act_sent);
        if ("sms".equals(ch) && !"received".equals(st) && !"agent".equals(st) && !"human".equals(st)) {
            return label("agent_sms_" + st, st);
        }
        return null;
    }

    /** Las etiquetas por valor, como los catálogos de PaynaniWeb. Un valor que no está queda tal cual. */
    private static final java.util.Map<String, Integer> LABELS = new java.util.HashMap<>();

    static {
        LABELS.put("agent_wa_connected", R.string.agent_wa_connected);
        LABELS.put("agent_wa_connecting", R.string.agent_wa_connecting);
        LABELS.put("agent_wa_unlinked", R.string.agent_wa_unlinked);
        LABELS.put("agent_wa_service_down", R.string.agent_wa_service_down);
        LABELS.put("agent_tg_starting", R.string.agent_tg_starting);
        LABELS.put("agent_tg_running", R.string.agent_tg_running);
        LABELS.put("agent_tg_offline", R.string.agent_tg_offline);
        LABELS.put("agent_tg_conflict", R.string.agent_tg_conflict);
        LABELS.put("agent_tg_token_invalid", R.string.agent_tg_token_invalid);
        LABELS.put("agent_tg_journal_mismatch", R.string.agent_tg_journal_mismatch);
        LABELS.put("agent_tg_stopped", R.string.agent_tg_stopped);
        LABELS.put("agent_tg_service_down", R.string.agent_tg_service_down);
        LABELS.put("agent_mail_observed", R.string.agent_mail_observed);
        LABELS.put("agent_mail_dispatched", R.string.agent_mail_dispatched);
        LABELS.put("agent_mail_presented", R.string.agent_mail_presented);
        LABELS.put("agent_mail_handled", R.string.agent_mail_handled);
        LABELS.put("agent_mail_replied", R.string.agent_mail_replied);
        LABELS.put("agent_mail_closed", R.string.agent_mail_closed);
        LABELS.put("agent_mail_suppressed", R.string.agent_mail_suppressed);
        LABELS.put("agent_sms_queued", R.string.agent_sms_queued);
        LABELS.put("agent_sms_accepted", R.string.agent_sms_accepted);
        LABELS.put("agent_sms_sent", R.string.agent_sms_sent);
        LABELS.put("agent_sms_delivered", R.string.agent_sms_delivered);
        LABELS.put("agent_sms_failed", R.string.agent_sms_failed);
        LABELS.put("agent_sms_rejected", R.string.agent_sms_rejected);
        LABELS.put("agent_sms_expired", R.string.agent_sms_expired);
        LABELS.put("agent_channel_mail", R.string.agent_channel_mail);
        LABELS.put("agent_channel_sms", R.string.agent_channel_sms);
        LABELS.put("agent_channel_whatsapp", R.string.agent_channel_whatsapp);
        LABELS.put("agent_channel_telegram", R.string.agent_channel_telegram);
        LABELS.put("agent_channel_webchat", R.string.agent_channel_webchat);
        LABELS.put("agent_act_call_missed", R.string.agent_act_call_missed);
        LABELS.put("agent_act_call_answered", R.string.agent_act_call_answered);
        LABELS.put("agent_act_roster_add", R.string.agent_act_roster_add);
        LABELS.put("agent_act_roster_edit", R.string.agent_act_roster_edit);
        LABELS.put("agent_act_roster_delete", R.string.agent_act_roster_delete);
        LABELS.put("agent_act_roster_other", R.string.agent_act_roster_other);
    }

    private String label(String key, String fallback) {
        Integer id = LABELS.get(key);
        return id != null ? getString(id) : fallback;
    }

    /** `ago` de dashboard_pages.py. */
    private String ago(Integer seconds) {
        if (seconds == null) return getString(R.string.agent_never);
        int s = Math.max(0, seconds);
        if (s < 60) return getString(R.string.agent_ago_s, s);
        if (s < 3600) return getString(R.string.agent_ago_m, s / 60);
        if (s < 86400) return getString(R.string.agent_ago_h, s / 3600);
        return getString(R.string.agent_ago_d, s / 86400);
    }

    /** `clock` de dashboard_pages.py: «23:01» si es de hoy; si no, con la fecha corta. */
    private String clock(String iso) {
        long ms = WebchatRules.parseIso(iso);
        if (ms <= 0) return "—";
        Date d = new Date(ms);
        String time = DateFormat.getTimeFormat(this).format(d);
        if (DateUtils.isToday(ms)) return time;
        return DateUtils.formatDateTime(this, ms, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH
                | DateUtils.FORMAT_NO_YEAR) + " " + time;
    }

    private int fg(int tone) {
        switch (tone) {
            case AgentRules.TONE_OK: return color(R.color.status_ok);
            case AgentRules.TONE_WARN: return color(R.color.status_warn_text);
            case AgentRules.TONE_BAD: return color(R.color.status_bad);
            default: return color(R.color.status_off);
        }
    }

    private int bg(int tone) {
        switch (tone) {
            case AgentRules.TONE_OK: return color(R.color.status_ok_bg);
            case AgentRules.TONE_WARN: return color(R.color.status_warn_bg);
            case AgentRules.TONE_BAD: return color(R.color.status_bad_bg);
            default: return color(R.color.status_off_bg);
        }
    }

    private int color(int res) {
        return ContextCompat.getColor(this, res);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
