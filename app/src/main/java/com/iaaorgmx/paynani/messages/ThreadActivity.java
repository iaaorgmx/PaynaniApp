package com.iaaorgmx.paynani.messages;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.text.Editable;
import android.text.TextUtils;
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

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

import com.iaaorgmx.paynani.R;
import com.iaaorgmx.paynani.gateway.GatewayPrefs;
import com.iaaorgmx.paynani.gateway.GatewayQueue;
import com.iaaorgmx.paynani.gateway.HumanSms;
import com.iaaorgmx.paynani.gateway.Phone;
import com.iaaorgmx.paynani.gateway.Protocol;
import com.iaaorgmx.paynani.gateway.OrderOrigin;
import com.iaaorgmx.paynani.gateway.db.HumanSend;
import com.iaaorgmx.paynani.gateway.db.OrderMark;
import com.iaaorgmx.paynani.roster.ContactEditorActivity;
import com.iaaorgmx.paynani.roster.RosterLookup;
import com.iaaorgmx.paynani.roster.RosterSource;
import com.iaaorgmx.paynani.roster.RosterSources;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * El hilo de una conversación (#83), como en Google Messages: recibidos a la
 * izquierda, enviados a la derecha, separador de fecha cuando pasa más de una
 * hora y el estado del último enviado.
 *
 * Desde #111 el dueño contesta desde aquí: la barra de abajo manda el SMS con
 * HumanSms y la pasarela se entera con sms.human. Un SMS suyo que no salió se
 * puede reintentar tocando «No se envió». Sin EXTRA_THREAD (un mensaje nuevo), el
 * hilo se busca después del primer envío.
 */
public class ThreadActivity extends AppCompatActivity {

    public static final String EXTRA_THREAD = "thread_id";
    public static final String EXTRA_ADDRESS = "address";

    /** Más de esto entre dos mensajes pone un separador de fecha y hora. */
    private static final long GAP = 60 * 60 * 1000L;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private SmsStore store;
    private long threadId;
    private String address;
    private EditText input;
    private ImageButton send;
    private TextView counter;
    /** Después de mandar, el hilo baja al mensaje nuevo aunque el usuario no estuviera abajo. */
    private boolean scrollToEnd;
    /** Los SMS del dueño que fallaron, por texto: sólo ésos se ofrecen para reintentar. */
    private final Map<String, String> humanFailed = new HashMap<>();
    /**
     * Esos mismos, como burbujas: Android no guarda en el almacén de SMS un envío que
     * falló de una app que no es la de SMS predeterminada, así que sin esto no se verían.
     */
    private final List<SmsStore.Msg> localFailed = new ArrayList<>();
    /** Las órdenes del dueño (PaynaniWeb) y de la API a este número (paynani #489, #491 FR-19). */
    private List<MarkMatch.Mark> marks = new ArrayList<>();
    private boolean dualSim;
    /** Se abrió la app de contactos: al volver se relee el nombre (#134). */
    private boolean contactsOpened;
    /** El número de la conversación en E.164, o null si no es un teléfono. */
    private String e164;
    /** Dónde está el número en los Roster (PRD #137); null mientras se lee o sin pasarela: sin icono. */
    private RosterLookup.Result roster;
    /** Cambia con cada búsqueda: una respuesta vieja no pinta el icono. */
    private int rosterGeneration;
    private ListView list;
    private final ThreadAdapter adapter = new ThreadAdapter();

    /** Lo cargado, del más viejo al más nuevo. */
    private final List<SmsStore.Msg> messages = new ArrayList<>();
    private boolean hasOlder = true;
    private boolean loadingOlder = false;

    /** Llega desde el hilo de la cola (GatewayQueue): se repinta en el principal. */
    private final Runnable humanChanged = () -> mainHandler.post(this::refresh);

    private final ContentObserver observer = new ContentObserver(mainHandler) {
        @Override
        public void onChange(boolean selfChange) {
            refresh();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_thread);
        store = new SmsStore(this);
        threadId = getIntent().getLongExtra(EXTRA_THREAD, -1);
        address = getIntent().getStringExtra(EXTRA_ADDRESS);

        Toolbar toolbar = findViewById(R.id.thread_toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());
        setTitle(address != null ? address : "");
        // Un código corto o un remitente con letras no es un contacto ni va al Roster (#134, FR-3 y FR-14).
        e164 = Phone.toE164(address, Protocol.simCountry(this));
        if (e164 != null) enableTitleTap(toolbar);

        list = findViewById(R.id.thread_list);
        list.setAdapter(adapter);
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int state) {}

            @Override
            public void onScroll(AbsListView view, int first, int visible, int total) {
                if (first == 0 && total > 0 && hasOlder && !loadingOlder) loadOlder();
            }
        });

        input = findViewById(R.id.thread_input);
        send = findViewById(R.id.thread_send);
        counter = findViewById(R.id.thread_counter);
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                updateCounter(s.toString());
            }
        });
        send.setOnClickListener(v -> send());
        GatewayPrefs gatewayPrefs = new GatewayPrefs(this);
        // Sin emparejar (#122) o con una pasarela anterior, paynani no recibe las respuestas.
        boolean warn = !gatewayPrefs.isPaired() || !gatewayPrefs.accepts(HumanSms.TYPE);
        TextView notReceived = findViewById(R.id.thread_not_received);
        notReceived.setText(gatewayPrefs.isPaired() ? R.string.thread_not_received : R.string.thread_not_paired);
        notReceived.setVisibility(warn ? View.VISIBLE : View.GONE);

        executor.execute(() -> {
            if (threadId < 0 && address != null) threadId = store.threadOf(address);
            final Map<String, HumanSend> failed = humanFailed();
            final List<MarkMatch.Mark> found = marks();
            final String title = address != null ? store.title(address) : "";
            final boolean dual = store.dualSim();
            final List<SmsStore.Msg> page = threadId < 0 ? new ArrayList<>()
                    : store.page(threadId, Long.MAX_VALUE, Long.MAX_VALUE);
            mainHandler.post(() -> {
                if (isFinishing()) return;
                setTitle(title);
                dualSim = dual;
                setFailed(failed);
                marks = found;
                messages.clear();
                messages.addAll(page);
                hasOlder = page.size() == SmsStore.PAGE;
                adapter.rebuild();
                list.setSelection(adapter.getCount() - 1);
            });
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (contactsOpened) {
            // Se pudo crear o cambiar el contacto: el título se vuelve a leer (FR-2).
            contactsOpened = false;
            refreshTitle();
        }
        // Al abrir y al volver (de ContactEditorActivity, o con la pasarela de vuelta): FR-6, FR-11, FR-13.
        lookupRoster();
        getContentResolver().registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer);
        // El estado de los SMS del dueño (#111): un fallo no siempre cambia el almacén de SMS.
        HumanSms.addListener(humanChanged);
    }

    @Override
    protected void onPause() {
        super.onPause();
        getContentResolver().unregisterContentObserver(observer);
        HumanSms.removeListener(humanChanged);
    }

    /**
     * El nombre del toolbar se toca como en Google Messages (#134, FR-1 a FR-5): abre la ficha del
     * contacto, o «Crear contacto o agregar a uno existente» con el número si no está (o si no hay
     * permiso de contactos). Toolbar no expone su título: es el TextView con ese texto.
     */
    private void enableTitleTap(Toolbar toolbar) {
        for (int i = 0; i < toolbar.getChildCount(); i++) {
            View child = toolbar.getChildAt(i);
            if (child instanceof TextView && TextUtils.equals(((TextView) child).getText(), toolbar.getTitle())) {
                child.setOnClickListener(v -> openContact());
                android.util.TypedValue ripple = new android.util.TypedValue();
                getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
                child.setBackgroundResource(ripple.resourceId);
                ViewCompat.replaceAccessibilityAction(child, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
                        getString(R.string.thread_open_contact), null);
                return;
            }
        }
    }

    // --- El icono del Roster (PRD #137, FR-6 a FR-14) ----------------------------------------------

    private void lookupRoster() {
        if (e164 == null) return;
        int gen = ++rosterGeneration;
        RosterLookup.run(RosterSources.get(this), e164, Protocol.simCountry(this), new RosterSource.Callback<RosterLookup.Result>() {
            @Override
            public void onResult(RosterLookup.Result r) {
                show(r);
            }

            @Override
            public void onError(RosterSource.Failure f) {
                show(null); // FR-11: sin icono ni aviso; se intenta otra vez al volver
            }

            private void show(RosterLookup.Result r) {
                if (gen != rosterGeneration || isFinishing()) return;
                roster = r;
                invalidateOptionsMenu();
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        getMenuInflater().inflate(R.menu.menu_thread, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(android.view.Menu menu) {
        android.view.MenuItem item = menu.findItem(R.id.thread_roster);
        boolean show = roster != null && roster.actionable();
        item.setVisible(show);
        if (show) {
            boolean in = !roster.matches.isEmpty();
            item.setIcon(in ? R.drawable.ic_roster_manage : R.drawable.ic_roster_add);
            item.setTitle(in ? R.string.thread_roster_edit : R.string.thread_roster_add);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull android.view.MenuItem item) {
        if (item.getItemId() != R.id.thread_roster || roster == null) return super.onOptionsItemSelected(item);
        if (roster.matches.isEmpty()) addToRoster(roster.addable);
        else editInRoster(roster.matches);
        return true;
    }

    /** FR-8: con una cuenta se agrega ahí; con varias, se pregunta. */
    private void addToRoster(List<RosterSource.Account> accounts) {
        if (accounts.isEmpty()) return;
        if (accounts.size() == 1) {
            startNew(accounts.get(0));
            return;
        }
        String[] labels = new String[accounts.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = accountLabel(accounts.get(i));
        new AlertDialog.Builder(this)
                .setTitle(R.string.thread_roster_choose_account)
                .setItems(labels, (d, which) -> startNew(accounts.get(which)))
                .setNegativeButton(R.string.roster_cancel, null)
                .show();
    }

    /** FR-9: el alta con el número en E.164 y, si está en contactos, su nombre y su primer correo. */
    private void startNew(RosterSource.Account account) {
        if (executor.isShutdown()) return;
        executor.execute(() -> {
            final String name = store.contactName(address);
            final String email = store.contactEmail(address);
            mainHandler.post(() -> {
                if (!isFinishing()) startActivity(ContactEditorActivity.forNew(this, account.id, name, email, e164));
            });
        });
    }

    /** FR-10: un miembro se abre directo; varios, en una lista con su cuenta. */
    private void editInRoster(List<RosterLookup.Match> matches) {
        if (matches.size() == 1) {
            open(matches.get(0));
            return;
        }
        String[] labels = new String[matches.size()];
        for (int i = 0; i < labels.length; i++) {
            RosterLookup.Match m = matches.get(i);
            labels[i] = m.member.name + "\n" + accountLabel(m.account);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.thread_roster_choose_member)
                .setItems(labels, (d, which) -> open(matches.get(which)))
                .setNegativeButton(R.string.roster_cancel, null)
                .show();
    }

    private void open(RosterLookup.Match m) {
        // FR-12: solo una cuenta que se leyó llega aquí; si no se pudiera editar, se abre de solo lectura.
        startActivity(m.account.editable() ? ContactEditorActivity.forMember(this, m.account.id, m.member)
                : ContactEditorActivity.forReadOnly(this, m.account.id, m.member));
    }

    private String accountLabel(RosterSource.Account a) {
        return a.primary() ? getString(R.string.roster_account_primary, a.email) : a.email;
    }

    private void refreshTitle() {
        if (executor.isShutdown() || address == null) return;
        store.forgetName(address);
        executor.execute(() -> {
            final String title = store.title(address);
            mainHandler.post(() -> {
                if (!isFinishing()) setTitle(title);
            });
        });
    }

    private void openContact() {
        if (executor.isShutdown()) return;
        executor.execute(() -> {
            final Uri contact = store.contactUri(address);
            mainHandler.post(() -> {
                if (isFinishing()) return;
                Intent intent;
                if (contact != null) {
                    intent = new Intent(Intent.ACTION_VIEW, contact);
                } else {
                    intent = new Intent(Intent.ACTION_INSERT_OR_EDIT)
                            .setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE)
                            .putExtra(ContactsContract.Intents.Insert.PHONE, address)
                            // Contactos de Google regresa aquí al guardar, en lugar de quedarse en la ficha.
                            .putExtra("finishActivityOnSaveCompleted", true);
                }
                try {
                    startActivity(intent);
                    contactsOpened = true;
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(this, R.string.thread_no_contacts_app, Toast.LENGTH_SHORT).show(); // FR-5
                }
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    /** La página anterior, sin mover lo que el usuario está viendo. */
    private void loadOlder() {
        if (messages.isEmpty() || executor.isShutdown()) return;
        loadingOlder = true;
        final long beforeDate = messages.get(0).date;
        final long beforeId = messages.get(0).id;
        executor.execute(() -> {
            final List<SmsStore.Msg> page = store.page(threadId, beforeDate, beforeId);
            mainHandler.post(() -> {
                loadingOlder = false;
                if (isFinishing()) return;
                hasOlder = page.size() == SmsStore.PAGE;
                if (page.isEmpty()) return;
                int oldCount = adapter.getCount();
                View top = list.getChildAt(0);
                int offset = top != null ? top.getTop() : 0;
                messages.addAll(0, page);
                adapter.rebuild();
                list.setSelectionFromTop(adapter.getCount() - oldCount, offset);
            });
        });
    }

    /** Llegó o cambió algo: se relee lo ya cargado y lo nuevo. */
    private void refresh() {
        if (executor.isShutdown()) return;
        final long since = messages.isEmpty() ? 0 : messages.get(0).date;
        executor.execute(() -> {
            // Un mensaje nuevo: el hilo existe hasta que sale el primer SMS.
            if (threadId < 0 && address != null) threadId = store.threadOf(address);
            final Map<String, HumanSend> failed = humanFailed();
            final List<MarkMatch.Mark> found = marks();
            final List<SmsStore.Msg> all = threadId < 0 ? new ArrayList<>() : store.since(threadId, since);
            mainHandler.post(() -> {
                if (isFinishing()) return;
                setFailed(failed);
                marks = found;
                boolean atBottom = list.getLastVisiblePosition() >= adapter.getCount() - 1;
                messages.clear();
                messages.addAll(all);
                adapter.rebuild();
                if (atBottom || scrollToEnd) list.setSelection(adapter.getCount() - 1);
                scrollToEnd = false;
            });
        });
    }

    /** Cuántos SMS saldrán, como los cuenta Android; nada mientras quepa en uno. */
    private void updateCounter(String text) {
        send.setEnabled(HumanSms.textProblem(text) == null && address != null);
        if (text.isEmpty()) {
            counter.setVisibility(View.GONE);
            return;
        }
        int parts = SmsMessage.calculateLength(text, false)[0];
        counter.setVisibility(parts > 1 ? View.VISIBLE : View.GONE);
        counter.setText(getString(R.string.thread_counter, parts));
    }

    private void send() {
        final String text = input.getText().toString();
        if (HumanSms.textProblem(text) != null || address == null) return;
        send.setEnabled(false);
        final Context app = getApplicationContext();
        GatewayQueue.run(() -> {
            String error = null;
            try {
                HumanSms.send(app, address, text);
            } catch (RuntimeException e) {
                error = e.getMessage();
            }
            final String failed = error;
            mainHandler.post(() -> {
                if (isFinishing()) return;
                if (failed != null) {
                    Toast.makeText(this, getString(R.string.thread_send_error, failed), Toast.LENGTH_LONG).show();
                    updateCounter(input.getText().toString());
                    return;
                }
                input.setText("");
                scrollToEnd = true;
                refresh();
            });
        });
    }

    /** Los SMS del dueño a este número que fallaron (y no salieron después). Fuera del hilo principal. */
    private Map<String, HumanSend> humanFailed() {
        Map<String, HumanSend> out = new HashMap<>();
        if (address == null) return out;
        String e164 = Phone.toE164(address, Protocol.simCountry(this));
        for (HumanSend row : GatewayQueue.get(this).humanTo(address, e164 != null ? e164 : address)) {
            if (HumanSms.FAILED.equals(row.status)) out.put(row.text, row);
            else out.remove(row.text); // uno posterior con el mismo texto ya salió
        }
        return out;
    }

    /** Las marcas de este número: «desde PaynaniWeb» o «API: <token>». Fuera del hilo principal. */
    private List<MarkMatch.Mark> marks() {
        List<MarkMatch.Mark> out = new ArrayList<>();
        if (address == null) return out;
        String e164 = Phone.toE164(address, Protocol.simCountry(this));
        if (e164 == null) return out;
        String web = getString(R.string.msg_from_web);
        String api = getString(R.string.msg_from_api);
        for (OrderMark row : GatewayQueue.get(this).marksTo(e164)) {
            String label = OrderOrigin.mark(row.origin, row.token, web, api);
            if (label != null) out.add(new MarkMatch.Mark(label, row.text, row.at));
        }
        return out;
    }

    private void setFailed(Map<String, HumanSend> failed) {
        humanFailed.clear();
        localFailed.clear();
        long n = 0;
        for (HumanSend row : failed.values()) {
            humanFailed.put(row.text, row.local_id);
            SmsStore.Msg m = new SmsStore.Msg();
            m.id = --n; // negativo: no es una fila del almacén de SMS
            m.threadId = threadId;
            m.address = row.to_raw;
            m.body = row.text;
            m.date = row.created_at;
            m.type = Telephony.Sms.MESSAGE_TYPE_FAILED;
            localFailed.add(m);
        }
    }

    private void offerRetry(String localId) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.msg_status_failed)
                .setMessage(R.string.thread_retry_text)
                .setPositiveButton(R.string.thread_retry, (d, w) -> {
                    final Context app = getApplicationContext();
                    GatewayQueue.run(() -> {
                        try {
                            HumanSms.retry(app, localId);
                        } catch (RuntimeException ignored) {
                        }
                        mainHandler.post(this::refresh);
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Filas del hilo: separadores de fecha y mensajes. */
    private class ThreadAdapter extends BaseAdapter {

        private final List<Object> rows = new ArrayList<>();
        private long lastOutgoingId = -1;
        private Map<Long, String> bubbleMarks = new HashMap<>();

        void rebuild() {
            rows.clear();
            lastOutgoingId = -1;
            long prev = 0;
            List<SmsStore.Msg> all = new ArrayList<>(messages);
            for (SmsStore.Msg f : localFailed) {
                boolean stored = false;
                for (SmsStore.Msg m : messages) {
                    if (m.outgoing() && m.failed() && f.body.equals(m.body)) stored = true;
                }
                // Los más viejos que la página cargada salen al cargar la anterior.
                if (!stored && (messages.isEmpty() || !hasOlder || f.date >= messages.get(0).date)) all.add(f);
            }
            java.util.Collections.sort(all, (a, b) -> Long.compare(a.date, b.date));
            List<MarkMatch.Sent> sent = new ArrayList<>();
            for (SmsStore.Msg m : all) {
                if (m.outgoing() && m.id >= 0) sent.add(new MarkMatch.Sent(m.id, m.body, m.date));
            }
            bubbleMarks = MarkMatch.match(sent, marks);
            for (SmsStore.Msg m : all) {
                if (prev == 0 || m.date - prev > GAP) rows.add(Fmt.separator(ThreadActivity.this, m.date));
                rows.add(m);
                prev = m.date;
                if (m.outgoing()) lastOutgoingId = m.id;
            }
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 3;
        }

        @Override
        public int getItemViewType(int position) {
            Object row = rows.get(position);
            if (row instanceof String) return 0;
            return ((SmsStore.Msg) row).outgoing() ? 2 : 1;
        }

        @Override
        public boolean isEnabled(int position) {
            return false;
        }

        @Override
        public View getView(int position, View view, @NonNull ViewGroup parent) {
            int type = getItemViewType(position);
            Context ctx = ThreadActivity.this;
            if (view == null) {
                int layout = type == 0 ? R.layout.thread_separator
                        : type == 1 ? R.layout.thread_item_in : R.layout.thread_item_out;
                view = LayoutInflater.from(ctx).inflate(layout, parent, false);
            }
            if (type == 0) {
                ((TextView) view).setText((String) rows.get(position));
                return view;
            }
            SmsStore.Msg m = (SmsStore.Msg) rows.get(position);
            TextView bubble = view.findViewById(R.id.bubble_text);
            bubble.setText(m.body);
            // Como en PaynaniWeb: lo que mandó el dueño desde PaynaniWeb en verde; lo demás que sale,
            // en el de salida.
            if (m.outgoing()) bubble.setBackgroundResource(
                    getString(R.string.msg_from_web).equals(bubbleMarks.get(m.id)) ? R.drawable.bubble_out_owner
                            : R.drawable.bubble_out);

            TextView meta = view.findViewById(R.id.bubble_meta);
            String status = null;
            boolean failed = m.outgoing() && m.failed();
            if (failed) status = getString(R.string.msg_status_failed);
            else if (m.outgoing() && m.id == lastOutgoingId) {
                status = getString(m.pending() ? R.string.msg_status_sending
                        : m.delivered() ? R.string.msg_status_delivered : R.string.msg_status_sent);
            }
            int slot = dualSim ? store.slot(m.subId) : 0;
            String sim = slot > 0 ? getString(R.string.msg_sim, slot) : null;
            String text = status != null && sim != null ? status + " • " + sim : status != null ? status : sim;
            // Lo pidió el dueño desde PaynaniWeb o la API (FR-19): la marca va primero.
            String mark = m.outgoing() ? bubbleMarks.get(m.id) : null;
            if (mark != null) text = text != null ? mark + " • " + text : mark;
            // Un SMS del dueño que no salió: «No se envió · Reintentar» se puede tocar (#111).
            final String retryId = failed ? humanFailed.get(m.body) : null;
            if (retryId != null) text = getString(R.string.thread_failed_retry);
            meta.setVisibility(text != null ? View.VISIBLE : View.GONE);
            meta.setText(text);
            meta.setTextColor(ContextCompat.getColor(ctx, failed ? R.color.msg_failed : R.color.paynani_gris));
            meta.setOnClickListener(retryId != null ? v -> offerRetry(retryId) : null);
            meta.setClickable(retryId != null);
            meta.setMinHeight(retryId != null ? (int) (48 * getResources().getDisplayMetrics().density) : 0);
            return view;
        }
    }
}
