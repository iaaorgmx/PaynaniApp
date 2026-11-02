package com.iaaorgmx.paynani.whatsapp;

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

import java.util.ArrayList;
import java.util.List;

/**
 * «WhatsApp messages» (paynani PRD #550 FR-25): los hilos de WhatsApp como «Messages» muestra los
 * de SMS, sin botón de mensaje nuevo (Q189=A): sólo se contesta donde el contacto ya escribió. De
 * un número fuera del roster sólo se ve el número y la hora (FR-16a).
 *
 * Sin conexión no se muestran hilos viejos de memoria (§12): «Sin conexión con paynani».
 */
public class WhatsAppMessagesFragment extends Fragment implements WhatsAppEvents.Listener {

    private WhatsAppSource source;
    private ListView list;
    private View stateView;
    private View progressView;
    private TextView messageView;
    private View retryView;
    private TextView catchAll;
    private final Adapter adapter = new Adapter();
    /** Los hilos que se muestran; sin conexión se vacía y lo pendiente se ignora. */
    private final PagedList<WhatsAppSource.ThreadSummary> threads = new PagedList<>(false);

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        source = WhatsAppSources.get(requireContext());
        View v = inflater.inflate(R.layout.fragment_whatsapp_messages, container, false);
        list = v.findViewById(R.id.wa_threads);
        stateView = v.findViewById(R.id.wa_threads_state);
        progressView = v.findViewById(R.id.wa_threads_progress);
        messageView = v.findViewById(R.id.wa_threads_message);
        retryView = v.findViewById(R.id.wa_threads_retry);
        retryView.setOnClickListener(x -> load());
        catchAll = v.findViewById(R.id.wa_catchall);
        catchAll.setOnClickListener(x -> startActivity(
                CatchAllActivity.intent(requireContext(), CatchAllActivity.WHATSAPP)));
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) ->
                startActivity(WhatsAppThreadActivity.intent(requireContext(), threads.items().get(position).number)));
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
        catchAll.setVisibility(RosterSources.get(requireContext()).catchAllAvailable() ? View.VISIBLE : View.GONE);
        WhatsAppEvents.add(this);
        load();
    }

    @Override
    public void onPause() {
        super.onPause();
        WhatsAppEvents.remove(this);
        threads.close();
    }

    @Override
    public void onChanged(String what) {
        if ((WhatsAppEvents.THREADS.equals(what) || WhatsAppEvents.CONNECTION.equals(what)) && isResumed()) load();
    }

    private void load() {
        if (!source.available()) {
            offline();
            return;
        }
        int t = threads.reload();
        if (threads.items().isEmpty()) showState(true, null, false);
        source.threads(null, new WhatsAppSource.Callback<WhatsAppSource.Page<WhatsAppSource.ThreadSummary>>() {
            @Override
            public void onResult(WhatsAppSource.Page<WhatsAppSource.ThreadSummary> page) {
                if (!threads.first(t, page) || !isAdded()) return;
                adapter.notifyDataSetChanged();
                if (threads.items().isEmpty()) showState(false, getString(R.string.wa_threads_empty), false);
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
                showState(false, getString(R.string.wa_threads_error, f.code), true);
            }
        });
    }

    /** Sin conexión no se muestran hilos viejos (§12), ni los que lleguen tarde. */
    private void offline() {
        threads.clear();
        adapter.notifyDataSetChanged();
        showState(false, getString(R.string.wa_threads_offline), true);
    }

    private void loadMore() {
        if (!source.available()) return;
        String before = threads.nextBefore();
        int t = threads.more();
        if (t < 0) return;
        source.threads(before, new WhatsAppSource.Callback<WhatsAppSource.Page<WhatsAppSource.ThreadSummary>>() {
            @Override
            public void onResult(WhatsAppSource.Page<WhatsAppSource.ThreadSummary> page) {
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

    static WhatsAppRules.Labels labels(android.content.Context c) {
        return new WhatsAppRules.Labels() {
            @Override
            public String hidden() {
                return c.getString(R.string.wa_hidden);
            }

            @Override
            public String media(String kind) {
                switch (kind) {
                    case "image":
                        return c.getString(R.string.wa_media_image);
                    case "audio":
                        return c.getString(R.string.wa_media_audio);
                    case "video":
                        return c.getString(R.string.wa_media_video);
                    case "document":
                        return c.getString(R.string.wa_media_document);
                    case "sticker":
                        return c.getString(R.string.wa_media_sticker);
                    default:
                        return c.getString(R.string.wa_media_other);
                }
            }

            @Override
            public String outPrefix() {
                return c.getString(R.string.wa_out_prefix);
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
            WhatsAppSource.ThreadSummary t = threads.items().get(position);
            TextView title = view.findViewById(R.id.conv_title);
            TextView date = view.findViewById(R.id.conv_date);
            TextView preview = view.findViewById(R.id.conv_preview);
            title.setText(t.name != null ? t.name : t.number);
            long at = WhatsAppRules.parseIso(t.lastAt);
            date.setText(at > 0 ? Fmt.listDate(view.getContext(), at) : "");
            preview.setText(WhatsAppRules.preview(t, labels(view.getContext())));
            Fmt.avatar(view.findViewById(R.id.conv_initial), (ImageView) view.findViewById(R.id.conv_person),
                    t.number, t.name);
            return view;
        }
    }
}
