package com.iaaorgmx.paynani.telegram;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

import com.iaaorgmx.paynani.R;
import com.iaaorgmx.paynani.messages.Fmt;
import com.iaaorgmx.paynani.messages.OutgoingText;
import com.iaaorgmx.paynani.whatsapp.Gate;
import com.iaaorgmx.paynani.whatsapp.PagedList;
import com.iaaorgmx.paynani.whatsapp.WhatsAppSource;

/**
 * Un hilo de Telegram (paynani PRD #609 FR-29a, TELEGRAM.md §6): los mensajes y, sólo si la
 * pasarela lo permite (can_reply y telegram.send), el cuadro para contestar. No hay forma de
 * empezar un hilo nuevo: sólo se contesta donde la persona ya le escribió al bot (FR-25). De alguien
 * fuera del roster sin el Catch-All de Telegram sólo se ve la hora.
 */
public class TelegramThreadActivity extends AppCompatActivity implements TelegramEvents.Listener {

    static final String EXTRA_THREAD = "thread";

    private TelegramSource source;
    private String thread;
    private ListView list;
    private EditText input;
    private ImageButton send;
    private TextView counter;
    private View replyBar;
    private TextView cannotReply;
    private final Adapter adapter = new Adapter();
    /** Del más viejo al más reciente, como se muestran; sin conexión se vacía. */
    private final PagedList<TelegramSource.Message> messages = new PagedList<>(true);
    /** telegram.send: la respuesta después de pausar se ignora. */
    private final Gate sendGate = new Gate();
    private boolean sending;

    /**
     * Con FLAG_ACTIVITY_NO_USER_ACTION: sin él, Main2Activity recibe onUserLeaveHint y al volver
     * abre «Mensajes» en lugar de «Mensajes de Telegram» (como el editor del roster, #417).
     */
    public static Intent intent(Context context, String thread) {
        return new Intent(context, TelegramThreadActivity.class).putExtra(EXTRA_THREAD, thread)
                .addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_telegram_thread);
        source = TelegramSources.get(this);
        thread = getIntent().getStringExtra(EXTRA_THREAD);

        Toolbar toolbar = findViewById(R.id.tg_thread_toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());
        // El id del hilo es opaco (t_…): el título espera al nombre.
        setTitle("");

        list = findViewById(R.id.tg_thread_list);
        list.setAdapter(adapter);
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int state) {
            }

            @Override
            public void onScroll(AbsListView view, int first, int visible, int total) {
                if (first == 0 && total > 0) loadOlder();
            }
        });
        input = findViewById(R.id.tg_thread_input);
        input.setHint(R.string.tg_input_hint);
        send = findViewById(R.id.tg_thread_send);
        send.setContentDescription(getString(R.string.tg_send));
        counter = findViewById(R.id.tg_thread_counter);
        replyBar = findViewById(R.id.tg_thread_reply);
        cannotReply = findViewById(R.id.tg_thread_cannot_reply);
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateSend();
            }
        });
        send.setOnClickListener(v -> send());
        showReply(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        messages.open();
        sendGate.open();
        TelegramEvents.add(this);
        load();
    }

    @Override
    protected void onPause() {
        super.onPause();
        TelegramEvents.remove(this);
        messages.close();
        sendGate.close();
        sending = false;
    }

    @Override
    public void onChanged(String what) {
        if ((TelegramEvents.THREADS.equals(what) || TelegramEvents.CONNECTION.equals(what)) && !isFinishing()) {
            load();
        }
    }

    private void load() {
        if (thread == null) return;
        if (!source.available()) {
            offline();
            return;
        }
        int t = messages.reload();
        source.thread(thread, null, new WhatsAppSource.Callback<TelegramSource.Thread>() {
            @Override
            public void onResult(TelegramSource.Thread th) {
                if (!messages.first(t, th.messages) || isFinishing()) return;
                setTitle(th.name != null ? th.name : getString(R.string.tg_unknown_person));
                adapter.notifyDataSetChanged();
                list.setSelection(adapter.getCount() - 1);
                showReply(TelegramRules.replyVisible(th.canReply, source.canSend()));
                if (!th.inRoster) cannotReply.setText(R.string.tg_cannot_reply_roster);
            }

            @Override
            public void onError(WhatsAppSource.Failure f) {
                if (!messages.accepts(t) || isFinishing()) return;
                if ("offline".equals(f.code) || "timeout".equals(f.code)) {
                    offline();
                    return;
                }
                showReply(false);
                Toast.makeText(TelegramThreadActivity.this, errorText(f), Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** Sin conexión no se muestran mensajes viejos (§12), ni los que lleguen tarde. */
    private void offline() {
        messages.clear();
        adapter.notifyDataSetChanged();
        replyBar.setVisibility(View.GONE);
        cannotReply.setText(R.string.tg_threads_offline);
        cannotReply.setVisibility(View.VISIBLE);
    }

    private void loadOlder() {
        if (!source.available()) return;
        String before = messages.nextBefore();
        int t = messages.more();
        if (t < 0) return;
        source.thread(thread, before, new WhatsAppSource.Callback<TelegramSource.Thread>() {
            @Override
            public void onResult(TelegramSource.Thread th) {
                int added = messages.append(t, th.messages);
                if (added < 0 || isFinishing()) return;
                adapter.notifyDataSetChanged();
                list.setSelection(added);
            }

            @Override
            public void onError(WhatsAppSource.Failure f) {
                messages.moreFailed(t);
            }
        });
    }

    private void showReply(boolean canReply) {
        replyBar.setVisibility(canReply ? View.VISIBLE : View.GONE);
        cannotReply.setText(R.string.tg_cannot_reply);
        cannotReply.setVisibility(canReply ? View.GONE : View.VISIBLE);
        updateSend();
    }

    private void updateSend() {
        String text = input.getText().toString();
        send.setEnabled(!sending && TelegramRules.sendable(text));
        int len = OutgoingText.trimEnd(text).length();
        counter.setVisibility(len > TelegramRules.MAX_TEXT - 200 ? View.VISIBLE : View.GONE);
        counter.setText(String.valueOf(TelegramRules.MAX_TEXT - len));
    }

    private void send() {
        // Sin los espacios ni saltos de línea del final (paynani #612); si no queda nada, no se manda.
        String text = OutgoingText.trimEnd(input.getText().toString());
        if (!TelegramRules.sendable(text) || sending) return;
        sending = true;
        updateSend();
        int t = sendGate.next();
        source.send(thread, text, new WhatsAppSource.Callback<TelegramSource.Message>() {
            @Override
            public void onResult(TelegramSource.Message m) {
                if (!sendGate.accepts(t) || isFinishing()) return;
                sending = false;
                input.setText("");
                // El estado del envío se ve al volver a leer el hilo (TELEGRAM.md §4).
                load();
            }

            @Override
            public void onError(WhatsAppSource.Failure f) {
                if (!sendGate.accepts(t) || isFinishing()) return;
                sending = false;
                updateSend();
                Toast.makeText(TelegramThreadActivity.this, errorText(f), Toast.LENGTH_LONG).show();
                // not_started o not_allowed: la pasarela ya no deja contestar aquí.
                if ("not_started".equals(f.code) || "not_allowed".equals(f.code)) showReply(false);
            }
        });
    }

    private String errorText(WhatsAppSource.Failure f) {
        switch (f.code) {
            case "not_allowed":
                return getString(R.string.tg_error_not_allowed);
            case "not_started":
                return getString(R.string.tg_error_not_started);
            case "rate_limited":
                return getString(R.string.tg_error_rate_limited, f.retryAfterS);
            case "unavailable":
                return getString(R.string.tg_error_unavailable);
            case "offline":
            case "timeout":
                return getString(R.string.tg_error_offline);
            default:
                return getString(R.string.tg_error_generic, f.code);
        }
    }

    private String meta(TelegramSource.Message m) {
        long at = TelegramRules.parseIso(m.at);
        String time = at > 0 ? Fmt.time(this, at) : "";
        if (!m.out()) return time;
        if ("sending".equals(m.status)) return getString(R.string.tg_sending);
        if ("failed".equals(m.status)) return getString(R.string.tg_failed);
        return time;
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return messages.items().size();
        }

        @Override
        public Object getItem(int position) {
            return messages.items().get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return messages.items().get(position).out() ? 1 : 0;
        }

        @Override
        public View getView(int position, View view, ViewGroup parent) {
            TelegramSource.Message m = messages.items().get(position);
            if (view == null) {
                view = LayoutInflater.from(parent.getContext()).inflate(
                        m.out() ? R.layout.thread_item_out : R.layout.thread_item_in, parent, false);
            }
            TextView bubble = view.findViewById(R.id.bubble_text);
            bubble.setText(TelegramRules.bubble(m, TelegramMessagesFragment.labels(parent.getContext())));
            // Como en PaynaniWeb: lo del dueño (desde el teléfono o la web) en verde; lo del agente
            // y la API, en el de salida.
            if (m.out()) bubble.setBackgroundResource(TelegramRules.byOwner(m.by) ? R.drawable.bubble_out_owner
                    : R.drawable.bubble_out);
            TextView meta = view.findViewById(R.id.bubble_meta);
            String text = meta(m);
            meta.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
            meta.setText(text);
            meta.setTextColor(ContextCompat.getColor(parent.getContext(),
                    m.out() && "failed".equals(m.status) ? R.color.msg_failed : R.color.paynani_gris));
            return view;
        }
    }
}
