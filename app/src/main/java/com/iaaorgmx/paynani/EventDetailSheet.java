package com.iaaorgmx.paynani;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.iaaorgmx.paynani.messages.SmsStore;
import com.iaaorgmx.paynani.messages.ThreadActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * El detalle de un evento del registro (#113), en una hoja de Material3: el título y
 * el ícono de la lista, el texto del SMS como protagonista (seleccionable), De o
 * Para con el nombre del contacto, la fecha, y las acciones «Abrir conversación» y
 * «Copiar». Antes era un AlertDialog con «Mensaje» de título y el texto corrido.
 */
public final class EventDetailSheet {

    private static final ExecutorService LOOKUP = Executors.newSingleThreadExecutor();

    private EventDetailSheet() {
    }

    /** @param who el número como lo muestra la lista, o «número oculto» */
    public static void show(Fragment fragment, LogEvent_Model item, String who) {
        Context ctx = fragment.getContext();
        if (ctx == null) return;
        Context themed = new ContextThemeWrapper(ctx, R.style.Theme_Paynani_M3);
        BottomSheetDialog sheet = new BottomSheetDialog(themed);
        View v = LayoutInflater.from(themed).inflate(R.layout.log_event_detail, new FrameLayout(themed), false);
        sheet.setContentView(v);

        String event = item.getEvent_name();
        int title = EventKind.title(event);
        ((ImageView) v.findViewById(R.id.ev_detail_icon)).setImageResource(EventKind.icon(event));
        ((TextView) v.findViewById(R.id.ev_detail_title)).setText(title != 0 ? ctx.getString(title) : event);
        ((TextView) v.findViewById(R.id.ev_detail_date)).setText(DateUtils.formatDate(item.getdate(),
                ctx.getString(R.string.label_today), ctx.getString(R.string.label_yesterday),
                ctx.getString(R.string.label_format_date)));

        // El texto: del SMS, de la respuesta o del rechazo; el de revocado sale del recurso (#102).
        String text = "gateway_revoked".equals(event) ? ctx.getString(R.string.log_gateway_revoked_detail)
                : item.getMsg() == null ? "" : item.getMsg();
        TextView body = v.findViewById(R.id.ev_detail_text);
        body.setText(text);
        v.findViewById(R.id.ev_detail_text_card).setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);

        String number = item.getphonenumber();
        boolean hasNumber = number != null && !number.isEmpty();
        LinearLayout rows = v.findViewById(R.id.ev_detail_rows);
        TextView person = null;
        // Los avisos de la pasarela no traen número (#65).
        if (!"gateway_rejected".equals(event) && !"gateway_revoked".equals(event)) {
            person = row(themed, rows, EventKind.outgoing(event) ? R.string.lbl_to : R.string.lbl_from, who);
        }

        View open = v.findViewById(R.id.ev_detail_open);
        open.setVisibility(hasNumber && EventKind.sms(event) ? View.VISIBLE : View.GONE);
        open.setOnClickListener(b -> openConversation(fragment, number, sheet));
        String copyText = text.isEmpty() ? (hasNumber ? number : "") : text;
        View copy = v.findViewById(R.id.ev_detail_copy);
        copy.setVisibility(copyText.isEmpty() ? View.GONE : View.VISIBLE);
        copy.setOnClickListener(b -> {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText(ctx.getString(R.string.app_name), copyText));
            Toast.makeText(ctx, R.string.ev_detail_copied, Toast.LENGTH_SHORT).show();
        });
        sheet.show();

        // El nombre del contacto, si lo hay, junto al número; se busca fuera del hilo principal.
        if (hasNumber && person != null) {
            final TextView target = person;
            final Handler main = new Handler(Looper.getMainLooper());
            final Context app = ctx.getApplicationContext();
            LOOKUP.execute(() -> {
                String name;
                try {
                    name = new SmsStore(app).contactName(number);
                } catch (RuntimeException e) {
                    name = null;
                }
                if (name == null || name.isEmpty()) return;
                final String shown = name + "\n" + number;
                main.post(() -> target.setText(shown));
            });
        }
    }

    private static TextView row(Context themed, LinearLayout rows, int label, String value) {
        View row = LayoutInflater.from(themed).inflate(R.layout.status_kv, rows, false);
        ((TextView) row.findViewById(R.id.status_kv_label)).setText(label);
        TextView v = row.findViewById(R.id.status_kv_value);
        v.setText(value);
        rows.addView(row);
        return v;
    }

    /** La conversación de ese número en «Mensajes» (#83), si la hay. */
    private static void openConversation(Fragment fragment, String number, BottomSheetDialog sheet) {
        final Context app = fragment.requireContext().getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        LOOKUP.execute(() -> {
            long thread;
            try {
                thread = new SmsStore(app).threadOf(number);
            } catch (RuntimeException e) {
                thread = -1;
            }
            final long found = thread;
            main.post(() -> {
                if (!fragment.isAdded()) return;
                if (found < 0) {
                    Toast.makeText(app, R.string.ev_detail_no_conversation, Toast.LENGTH_LONG).show();
                    return;
                }
                sheet.dismiss();
                Intent i = new Intent(fragment.requireContext(), ThreadActivity.class);
                i.putExtra(ThreadActivity.EXTRA_THREAD, found);
                i.putExtra(ThreadActivity.EXTRA_ADDRESS, number);
                fragment.startActivity(i);
            });
        });
    }
}
