package com.iaaorgmx.paynani.messages;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.provider.Telephony;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import android.view.ContextThemeWrapper;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.iaaorgmx.paynani.R;
import com.iaaorgmx.paynani.roster.CatchAllActivity;
import com.iaaorgmx.paynani.roster.RosterSources;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * «Mensajes» (#83): las conversaciones SMS del teléfono, como la lista de
 * Google Messages. Sólo lectura. Tocar una abre su hilo en ThreadActivity.
 */
public class MessagesFragment extends Fragment {

    private static final String TAG = "PaynaniMessages";
    private static final String PREFS = "messages";
    private static final String CONTACTS_ASKED = "contacts_asked";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private SmsStore store;
    private ConvAdapter adapter;
    private TextView empty;
    private Button retry;

    // Una carga a la vez y a lo más una pendiente (#112): el observador de SMS
    // avisa por cada cambio y antes cada aviso encolaba una lectura completa.
    // Sólo se tocan en el hilo principal.
    private boolean loading;
    private boolean loadAgain;

    // Carga por páginas (#112): el índice ligero de todas las conversaciones y
    // cuántas de ellas ya están en la lista. Cada recarga sube la generación y
    // así se descarta una página pedida antes.
    private List<SmsStore.ConvRef> index = new ArrayList<>();
    private int shown;
    private boolean pageLoading;
    private int generation;
    private TextView footer;
    /** «Catch-All» (paynani PRD #535 FR-A1): sólo si la pasarela anuncia catchall.get. */
    private TextView catchAll;

    private final ContentObserver observer = new ContentObserver(mainHandler) {
        @Override
        public void onChange(boolean selfChange) {
            load();
        }
    };

    // «Nuevo mensaje» (#111): el selector de contactos de Android devuelve un número.
    private final ActivityResultLauncher<Intent> pickPhone =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), this::picked);

    // READ_CONTACTS se pide una sola vez, al abrir «Mensajes» la primera vez (#83).
    // No es un permiso de la pasarela: si se niega, se ven los números.
    private final ActivityResultLauncher<String> contactsLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> load());

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_messages, container, false);
        store = new SmsStore(requireContext());
        empty = root.findViewById(R.id.messages_empty);
        retry = root.findViewById(R.id.messages_retry);
        retry.setOnClickListener(v -> load());
        catchAll = root.findViewById(R.id.messages_catchall);
        catchAll.setOnClickListener(v -> startActivity(CatchAllActivity.intent(requireContext(), CatchAllActivity.SMS)));
        ListView list = root.findViewById(R.id.messages_list);
        adapter = new ConvAdapter(requireContext());
        View footerRow = inflater.inflate(R.layout.messages_footer, list, false);
        footer = footerRow.findViewById(R.id.messages_footer_text);
        list.addFooterView(footerRow, null, false);
        list.setAdapter(adapter);
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
            }

            @Override
            public void onScroll(AbsListView view, int first, int visible, int total) {
                if (total > 0 && first + visible >= total - 5) loadMore();
            }
        });
        list.setOnItemClickListener((parent, view, position, id) -> {
            SmsStore.Conversation conv = adapter.getItem(position);
            if (conv == null) return;
            Intent i = new Intent(requireContext(), ThreadActivity.class);
            i.putExtra(ThreadActivity.EXTRA_THREAD, conv.last.threadId);
            i.putExtra(ThreadActivity.EXTRA_ADDRESS, conv.last.address);
            startActivity(i);
        });

        // «Nuevo mensaje» (#111), con el tema Material3 de Estado sólo para el botón.
        FrameLayout frame = (FrameLayout) list.getParent();
        View fab = inflater.cloneInContext(new ContextThemeWrapper(requireContext(), R.style.Theme_Paynani_M3))
                .inflate(R.layout.messages_new_fab, frame, false);
        fab.setOnClickListener(v -> askNumber());
        frame.addView(fab);

        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!store.canReadContacts() && !prefs.getBoolean(CONTACTS_ASKED, false)) {
            prefs.edit().putBoolean(CONTACTS_ASKED, true).apply();
            contactsLauncher.launch(Manifest.permission.READ_CONTACTS);
        }
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        // El welcome puede cambiar mientras la pantalla está abierta (otra pasarela, una actualización).
        catchAll.setVisibility(RosterSources.get(requireContext()).catchAllAvailable() ? View.VISIBLE : View.GONE);
        requireContext().getContentResolver().registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer);
        load();
    }

    @Override
    public void onPause() {
        super.onPause();
        requireContext().getContentResolver().unregisterContentObserver(observer);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    /**
     * Relee el índice y las páginas que ya se veían, fuera del hilo principal.
     * «Mensajes» nunca queda en blanco (#112): «Cargando…» mientras no hay lista,
     * y si la lectura falla, el error con «Reintentar» (y la excepción en el registro).
     */
    private void load() {
        if (executor.isShutdown()) return;
        if (loading) {
            loadAgain = true;
            return;
        }
        loading = true;
        final int gen = ++generation;
        final int want = Math.max(SmsStore.CONV_PAGE, shown);
        if (adapter.isEmpty()) showState(R.string.msg_loading, false);
        executor.execute(() -> {
            List<SmsStore.ConvRef> idx;
            List<SmsStore.Conversation> convs;
            try {
                long t0 = System.currentTimeMillis();
                idx = store.conversationIndex();
                convs = store.conversations(idx.subList(0, Math.min(want, idx.size())));
                Log.i(TAG, idx.size() + " conversaciones; primera página en "
                        + (System.currentTimeMillis() - t0) + " ms");
            } catch (RuntimeException e) {
                Log.e(TAG, "no se pudieron leer los SMS", e);
                mainHandler.post(() -> {
                    if (isAdded() && adapter.isEmpty()) showState(R.string.msg_load_failed, true);
                    loadDone();
                });
                return;
            }
            // La lista se pinta ya; los nombres se buscan después y la repintan.
            mainHandler.post(() -> {
                if (!isAdded() || gen != generation) return;
                index = idx;
                shown = Math.min(want, idx.size());
                adapter.setNotifyOnChange(false);
                adapter.clear();
                adapter.addAll(convs);
                adapter.notifyDataSetChanged();
                updateFooter();
                if (convs.isEmpty()) {
                    showState(store.canRead() ? R.string.msg_empty : R.string.msg_no_permission, false);
                } else {
                    hideState();
                }
            });
            lookUpNames(convs);
            mainHandler.post(this::loadDone);
        });
    }

    /** La página siguiente del índice, al acercarse al final de la lista. */
    private void loadMore() {
        if (loading || pageLoading || executor.isShutdown() || shown >= index.size()) return;
        pageLoading = true;
        final int gen = generation;
        final List<SmsStore.ConvRef> refs =
                new ArrayList<>(index.subList(shown, Math.min(index.size(), shown + SmsStore.CONV_PAGE)));
        executor.execute(() -> {
            List<SmsStore.Conversation> convs;
            try {
                convs = store.conversations(refs);
            } catch (RuntimeException e) {
                // La lista ya visible se queda; al volver a bajar se reintenta.
                Log.e(TAG, "no se pudo leer una página de conversaciones", e);
                mainHandler.post(() -> pageLoading = false);
                return;
            }
            mainHandler.post(() -> {
                pageLoading = false;
                if (!isAdded() || gen != generation) return;
                adapter.addAll(convs);
                shown += refs.size();
                updateFooter();
            });
            lookUpNames(convs);
        });
    }

    /** Busca en contactos los nombres de una página ya pintada y la repinta. Corre en el executor. */
    private void lookUpNames(List<SmsStore.Conversation> convs) {
        try {
            for (SmsStore.Conversation c : convs) store.contactName(c.last.address);
        } catch (RuntimeException e) {
            Log.w(TAG, "no se pudieron leer los contactos", e);
        }
        mainHandler.post(() -> {
            if (isAdded()) adapter.notifyDataSetChanged();
        });
    }

    private void updateFooter() {
        footer.setVisibility(shown < index.size() ? View.VISIBLE : View.GONE);
    }

    /** Un número tecleado o un contacto, y luego el hilo con ese número (#111). */
    private void askNumber() {
        View body = LayoutInflater.from(requireContext()).inflate(R.layout.messages_new_dialog, null, false);
        EditText number = body.findViewById(R.id.messages_new_number);
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.msg_new)
                .setView(body)
                .setPositiveButton(R.string.msg_new_write, (d, w) -> openThread(number.getText().toString()))
                .setNeutralButton(R.string.msg_new_pick, (d, w) -> {
                    try {
                        pickPhone.launch(new Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI));
                    } catch (android.content.ActivityNotFoundException e) {
                        Log.w(TAG, "sin selector de contactos", e);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void picked(ActivityResult result) {
        if (result.getResultCode() != android.app.Activity.RESULT_OK || result.getData() == null
                || result.getData().getData() == null) return;
        android.net.Uri uri = result.getData().getData();
        // El selector da permiso para leer ese contacto, aunque no haya READ_CONTACTS.
        try (android.database.Cursor c = requireContext().getContentResolver().query(uri,
                new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER}, null, null, null)) {
            if (c != null && c.moveToFirst()) openThread(c.getString(0));
        } catch (RuntimeException e) {
            Log.w(TAG, "no se pudo leer el contacto elegido", e);
        }
    }

    private void openThread(String raw) {
        final String number = raw == null ? "" : raw.trim();
        if (number.isEmpty()) return;
        executor.execute(() -> {
            final long thread = store.threadOf(number);
            mainHandler.post(() -> {
                if (!isAdded()) return;
                Intent i = new Intent(requireContext(), ThreadActivity.class);
                i.putExtra(ThreadActivity.EXTRA_THREAD, thread);
                i.putExtra(ThreadActivity.EXTRA_ADDRESS, number);
                startActivity(i);
            });
        });
    }

    private void loadDone() {
        loading = false;
        if (loadAgain) {
            loadAgain = false;
            load();
        }
    }

    private void showState(int text, boolean canRetry) {
        empty.setText(text);
        empty.setVisibility(View.VISIBLE);
        retry.setVisibility(canRetry ? View.VISIBLE : View.GONE);
    }

    private void hideState() {
        empty.setVisibility(View.GONE);
        retry.setVisibility(View.GONE);
    }

    private class ConvAdapter extends ArrayAdapter<SmsStore.Conversation> {

        ConvAdapter(Context context) {
            super(context, 0, new ArrayList<>());
        }

        @NonNull
        @Override
        public View getView(int position, View view, @NonNull ViewGroup parent) {
            if (view == null) {
                view = LayoutInflater.from(getContext()).inflate(R.layout.messages_conv_item, parent, false);
            }
            SmsStore.Conversation conv = getItem(position);
            SmsStore.Msg m = conv.last;
            // Sólo la caché: buscar en contactos aquí congelaba el hilo principal (#112).
            String name = store.cachedName(m.address);

            TextView title = view.findViewById(R.id.conv_title);
            TextView preview = view.findViewById(R.id.conv_preview);
            TextView date = view.findViewById(R.id.conv_date);
            Fmt.avatar(view.findViewById(R.id.conv_initial), (ImageView) view.findViewById(R.id.conv_person),
                    m.address, name);

            title.setText(name != null ? name : store.displayNumber(m.address));
            String body = m.body.replace('\n', ' ');
            preview.setText(m.outgoing() ? getString(R.string.msg_you_prefix, body) : body);
            date.setText(Fmt.listDate(getContext(), m.date));

            int style = conv.unread ? Typeface.BOLD : Typeface.NORMAL;
            title.setTypeface(null, style);
            preview.setTypeface(null, style);
            date.setTypeface(null, style);
            return view;
        }
    }
}
