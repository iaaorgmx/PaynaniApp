package com.iaaorgmx.paynani.telegram;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.iaaorgmx.paynani.R;
import com.iaaorgmx.paynani.messages.Fmt;
import com.iaaorgmx.paynani.roster.CatchAllActivity;
import com.iaaorgmx.paynani.roster.RosterSources;
import com.iaaorgmx.paynani.whatsapp.Gate;
import com.iaaorgmx.paynani.whatsapp.PagedList;
import com.iaaorgmx.paynani.whatsapp.WhatsAppSource;

/**
 * «Mensajes de Telegram» (paynani PRD #609 FR-29a, TELEGRAM.md §6): los hilos del bot como
 * «Mensajes de WhatsApp», sin mensaje nuevo: sólo se contesta donde la persona ya escribió. Arriba,
 * un aviso cuando el bot no está funcionando (sin conexión con Telegram, token rechazado…).
 *
 * Sin conexión con paynani no se muestran hilos viejos de memoria.
 */
public class TelegramMessagesFragment extends Fragment implements TelegramEvents.Listener {

    private TelegramSource source;
    private ListView list;
    private View stateView;
    private View progressView;
    private TextView messageView;
    private View retryView;
    private TextView statusView;
    private TextView catchAll;
    private final Adapter adapter = new Adapter();
    /** Los hilos que se muestran; sin conexión se vacía y lo pendiente se ignora. */
    private final PagedList<TelegramSource.ThreadSummary> threads = new PagedList<>(false);
    /** telegram.status: una respuesta de antes de pausar o de otra lectura se ignora. */
    private final Gate statusGate = new Gate();

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        source = TelegramSources.get(requireContext());
        View v = inflater.inflate(R.layout.fragment_telegram_messages, container, false);
        list = v.findViewById(R.id.tg_threads);
        stateView = v.findViewById(R.id.tg_threads_state);
        progressView = v.findViewById(R.id.tg_threads_progress);
        messageView = v.findViewById(R.id.tg_threads_message);
        retryView = v.findViewById(R.id.tg_threads_retry);
        retryView.setOnClickListener(x -> {
            load();
            loadStatus();
        });
        statusView = v.findViewById(R.id.tg_status);
        catchAll = v.findViewById(R.id.tg_catchall);
        catchAll.setOnClickListener(x -> startActivity(
                CatchAllActivity.intent(requireContext(), CatchAllActivity.TELEGRAM)));
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) ->
                startActivity(TelegramThreadActivity.intent(requireContext(), threads.items().get(position).thread)));
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
            }

            @Override
            public void onScroll(AbsListView view, int first, int visible, int total) {
                if (total > 0 && first + visible >= total - 5) loadMore();
            }
        });
        return v;
    }

    @Override
    public void onResume() {
        super.onResume();
        threads.open();
        statusGate.open();
        TelegramEvents.add(this);
        showCatchAll();
        load();
        loadStatus();
    }

    @Override
    public void onPause() {
        super.onPause();
        TelegramEvents.remove(this);
        threads.close();
        statusGate.close();
    }

    @Override
    public void onChanged(String what) {
        if (!isResumed()) return;
        if (TelegramEvents.CONNECTION.equals(what)) showCatchAll();
        if (TelegramEvents.THREADS.equals(what) || TelegramEvents.CONNECTION.equals(what)) load();
        // Si llega un mensaje con el aviso puesto, el servicio ya volvió: la pasarela no siempre
        // manda telegram.changed status cuando el servicio cae o regresa.
        boolean warning = statusView.getVisibility() == View.VISIBLE;
        if (TelegramEvents.STATUS.equals(what) || TelegramEvents.CONNECTION.equals(what)
                || (TelegramEvents.THREADS.equals(what) && warning)) loadStatus();
    }

    /**
     * La entrada al Catch-All de Telegram: con catchall.get y Telegram anunciados. Una pasarela con
     * telegram.threads (TG-5) ya tiene el Catch-All de Telegram (TG-3); la pantalla igual revisa
     * `channels` antes de leer o guardar.
     */
    private void showCatchAll() {
        boolean on = RosterSources.get(requireContext()).catchAllAvailable() && source.available();
        catchAll.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    private void load() {
        if (!source.available()) {
            offline();
            return;
        }
        int t = threads.reload();
        if (threads.items().isEmpty()) showState(true, null, false);
        source.threads(null, new WhatsAppSource.Callback<WhatsAppSource.Page<TelegramSource.ThreadSummary>>() {
            @Override
            public void onResult(WhatsAppSource.Page<TelegramSource.ThreadSummary> page) {
                if (!threads.first(t, page) || !isAdded()) return;
                adapter.notifyDataSetChanged();
                if (threads.items().isEmpty()) showState(false, getString(R.string.tg_threads_empty), false);
                else stateView.setVisibility(View.GONE);
            }

            @Override
            public void onError(WhatsAppSource.Failure f) {
                if (!threads.accepts(t) || !isAdded()) return;
                if ("offline".equals(f.code) || "timeout".equals(f.code)) {
                    offline();
                    return;
                }
                threads.clear();
                adapter.notifyDataSetChanged();
                showState(false, getString(R.string.tg_threads_error, f.code), true);
                loadStatus();   // p. ej. unavailable: el aviso dice que el servicio no está funcionando
            }
        });
    }

    /** El aviso de arriba: sólo cuando el bot no está funcionando. */
    private void loadStatus() {
        if (!source.available()) {
            statusGate.invalidate();
            statusView.setVisibility(View.GONE);
            return;
        }
        int t = statusGate.next();
        source.status(new WhatsAppSource.Callback<TelegramSource.Status>() {
            @Override
            public void onResult(TelegramSource.Status s) {
                if (!statusGate.accepts(t) || !isAdded()) return;
                TelegramRules.State state = TelegramRules.state(s.state);
                statusView.setVisibility(TelegramRules.showsWarning(state) ? View.VISIBLE : View.GONE);
                statusView.setText(statusText(state));
            }

            @Override
            public void onError(WhatsAppSource.Failure f) {
                if (!statusGate.accepts(t) || !isAdded()) return;
                statusView.setVisibility(View.GONE);
            }
        });
    }

    private String statusText(TelegramRules.State state) {
        switch (state) {
            case RECONNECTING:
                return getString(R.string.tg_state_reconnecting);
            case CONFLICT:
                return getString(R.string.tg_state_conflict);
            case TOKEN_INVALID:
                return getString(R.string.tg_state_token_invalid);
            case SERVICE_DOWN:
                return getString(R.string.tg_state_service_down);
            case UNKNOWN:
                return getString(R.string.tg_state_unknown);
            case RUNNING:
            default:
                return "";
        }
    }

    /** Sin conexión no se muestran hilos viejos, ni los que lleguen tarde. */
    private void offline() {
        threads.clear();
        adapter.notifyDataSetChanged();
        statusView.setVisibility(View.GONE);
        // Conectado sin telegram.threads: paynani no tiene Telegram configurado, no es falta de conexión.
        showState(false, getString(source.connected() ? R.string.tg_threads_not_configured
                : R.string.tg_threads_offline), true);
    }

    private void loadMore() {
        if (!source.available()) return;
        String before = threads.nextBefore();
        int t = threads.more();
        if (t < 0) return;
        source.threads(before, new WhatsAppSource.Callback<WhatsAppSource.Page<TelegramSource.ThreadSummary>>() {
            @Override
            public void onResult(WhatsAppSource.Page<TelegramSource.ThreadSummary> page) {
                if (threads.append(t, page) < 0 || !isAdded()) return;
                adapter.notifyDataSetChanged();
            }

            @Override
            public void onError(WhatsAppSource.Failure f) {
                threads.moreFailed(t);
            }
        });
    }

    private void showState(boolean loading, String message, boolean retry) {
        stateView.setVisibility(View.VISIBLE);
        progressView.setVisibility(loading ? View.VISIBLE : View.GONE);
        messageView.setText(message);
        messageView.setVisibility(message == null ? View.GONE : View.VISIBLE);
        retryView.setVisibility(retry ? View.VISIBLE : View.GONE);
    }

    static TelegramRules.Labels labels(Context c) {
        return new TelegramRules.Labels() {
            @Override
            public String hidden() {
                return c.getString(R.string.tg_hidden);
            }

            @Override
            public String media(String kind) {
                switch (kind) {
                    // La lista de TELEGRAM.md §3; cualquier otro valor, «[archivo]».
                    case "image":
                        return c.getString(R.string.tg_media_photo);
                    case "voice":
                        return c.getString(R.string.tg_media_voice);
                    case "audio":
                        return c.getString(R.string.tg_media_audio);
                    case "video":
                        return c.getString(R.string.tg_media_video);
                    case "document":
                        return c.getString(R.string.tg_media_document);
                    case "sticker":
                        return c.getString(R.string.tg_media_sticker);
                    case "location":
                        return c.getString(R.string.tg_media_location);
                    case "contact":
                        return c.getString(R.string.tg_media_contact);
                    default:
                        return c.getString(R.string.tg_media_other);
                }
            }

            @Override
            public String outPrefix() {
                return c.getString(R.string.tg_out_prefix);
            }

            @Override
            public String edited() {
                return c.getString(R.string.tg_edited);
            }
        };
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return threads.items().size();
        }

        @Override
        public Object getItem(int position) {
            return threads.items().get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View view, ViewGroup parent) {
            if (view == null) {
                view = LayoutInflater.from(parent.getContext()).inflate(R.layout.messages_conv_item, parent, false);
            }
            TelegramSource.ThreadSummary t = threads.items().get(position);
            TextView title = view.findViewById(R.id.conv_title);
            TextView date = view.findViewById(R.id.conv_date);
            TextView preview = view.findViewById(R.id.conv_preview);
            String name = t.name != null ? t.name : getString(R.string.tg_unknown_person);
            title.setText(name);
            long at = TelegramRules.parseIso(t.lastAt);
            date.setText(at > 0 ? Fmt.listDate(view.getContext(), at) : "");
            preview.setText(TelegramRules.preview(t, labels(view.getContext())));
            // Sin número: el avatar sale de la inicial del nombre, o el genérico si está oculto.
            Fmt.avatar(view.findViewById(R.id.conv_initial), (ImageView) view.findViewById(R.id.conv_person),
                    t.thread, t.name);
            return view;
        }
    }
}
