package com.iaaorgmx.paynani.webchat;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;

import com.iaaorgmx.paynani.R;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Webchat (paynani PRD #653 FR-8 a FR-10): la conversación del dueño con su agente, la misma de
 * PaynaniWeb. Las burbujas del dueño a la derecha y las del agente a la izquierda con su nombre, un
 * separador por día, las fotos en la burbuja y los archivos como tarjeta. «escribiendo…» y la
 * burbuja del borrador mientras el agente escribe (Q325). Sin conexión no se muestra nada de
 * memoria (FR-10). Lo que se escribe aquí se ve en la web y al revés.
 */
public class WebchatFragment extends Fragment implements WebchatEvents.Listener {

    /** Los tipos que ofrece el selector; la pasarela revisa los bytes (WEBCHAT.md §3). */
    private static final String[] PICK_TYPES = {"image/*", "application/pdf", "text/plain", "text/csv",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/zip"};
    /** Las fotos se decodifican reducidas a este ancho, en píxeles, y se guardan en memoria. */
    private static final int PHOTO_PX = 640;
    private static final LruCache<String, Bitmap> PHOTOS = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    /**
     * El selector de archivos con FLAG_ACTIVITY_NO_USER_ACTION: sin él, Main2Activity recibe
     * onUserLeaveHint y al volver abre «Mensajes» en lugar de Webchat (#125).
     */
    private static final class PickDocuments extends ActivityResultContracts.OpenMultipleDocuments {
        @NonNull
        @Override
        public Intent createIntent(@NonNull Context context, @NonNull String[] input) {
            return super.createIntent(context, input).addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        }
    }

    /** Un archivo elegido que todavía no se manda. */
    private static final class Pending {
        final String name;
        final String mime;
        final byte[] data;
        /** El up_… ya subido: un reintento lo vuelve a usar sin subir otra vez (§13). */
        String upload;

        Pending(String name, String mime, byte[] data) {
            this.name = name;
            this.mime = mime;
            this.data = data;
        }
    }

    private WebchatSource source;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private ListView list;
    private View stateView;
    private View progressView;
    private TextView messageView;
    private View retryView;
    private TextView typingView;
    private View pendingScroll;
    private LinearLayout pendingView;
    private View composeView;
    private EditText input;
    private ImageButton send;
    private final Adapter adapter = new Adapter();

    private List<WebchatSource.Message> messages = new ArrayList<>();
    private String nextBefore;
    private boolean typing;
    private String draft;
    private String agentName = "";
    private final List<Pending> pending = new ArrayList<>();
    private boolean sending;
    private boolean loadingOlder;
    /** La lectura completa y las de lo nuevo: una respuesta de antes de pausar se ignora. */
    private final Gate readGate = new Gate();
    private final Gate sendGate = new Gate();

    private ActivityResultLauncher<String[]> picker;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        picker = registerForActivityResult(new PickDocuments(), this::picked);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        source = WebchatSources.get(requireContext());
        View v = inflater.inflate(R.layout.fragment_webchat, container, false);
        list = v.findViewById(R.id.wc_list);
        stateView = v.findViewById(R.id.wc_state);
        progressView = v.findViewById(R.id.wc_progress);
        messageView = v.findViewById(R.id.wc_message);
        retryView = v.findViewById(R.id.wc_retry);
        retryView.setOnClickListener(x -> load());
        typingView = v.findViewById(R.id.wc_typing);
        pendingScroll = v.findViewById(R.id.wc_pending_scroll);
        pendingView = v.findViewById(R.id.wc_pending);
        composeView = v.findViewById(R.id.wc_compose);
        input = v.findViewById(R.id.wc_input);
        send = v.findViewById(R.id.wc_send);
        v.findViewById(R.id.wc_attach).setOnClickListener(x -> picker.launch(PICK_TYPES));
        send.setOnClickListener(x -> send());
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
        return v;
    }

    @Override
    public void onResume() {
        super.onResume();
        readGate.open();
        sendGate.open();
        WebchatEvents.add(this);
        WebchatNotifier.setScreenVisible(requireContext(), true);
        load();
    }

    @Override
    public void onPause() {
        super.onPause();
        WebchatEvents.remove(this);
        WebchatNotifier.setScreenVisible(requireContext(), false);
        readGate.close();
        sendGate.close();
        sending = false;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    @Override
    public void onChanged(String what, String newDraft) {
        if (!isResumed()) return;
        switch (what) {
            case WebchatEvents.CONNECTION:
                load();
                break;
            case WebchatEvents.DRAFT:
                // FR-6: el borrador viene en el aviso; sin texto, ya no hay borrador.
                draft = newDraft == null || newDraft.isEmpty() ? null : newDraft;
                if (draft != null) typing = true;
                showTyping();
                adapter.notifyDataSetChanged();
                break;
            default:
                refresh();
                break;
        }
    }

    // --- Lectura ------------------------------------------------------------------------------

    private void load() {
        if (!source.available()) {
            offline();
            return;
        }
        int t = readGate.next();
        if (messages.isEmpty()) showState(true, null, false);
        source.thread(null, null, new WebchatSource.Callback<WebchatSource.Thread>() {
            @Override
            public void onResult(WebchatSource.Thread th) {
                if (!readGate.accepts(t) || !isAdded()) return;
                messages = new ArrayList<>(th.messages);
                nextBefore = th.nextBefore;
                apply(th);
                composeView.setVisibility(View.VISIBLE);
                if (messages.isEmpty()) showState(false, getString(R.string.wc_empty), false);
                else stateView.setVisibility(View.GONE);
                adapter.notifyDataSetChanged();
                scrollToEnd();
            }

            @Override
            public void onError(WebchatSource.Failure f) {
                if (!readGate.accepts(t) || !isAdded()) return;
                if ("offline".equals(f.code) || "timeout".equals(f.code)) {
                    offline();
                    return;
                }
                messages = new ArrayList<>();
                adapter.notifyDataSetChanged();
                showState(false, getString(R.string.wc_error_read, f.code), true);
            }
        });
    }

    /** Sólo lo posterior al último mensaje (FR-3, after), para webchat.changed. */
    private void refresh() {
        String after = WebchatRules.lastId(messages);
        if (after == null) {
            load();
            return;
        }
        if (!source.available()) {
            offline();
            return;
        }
        int t = readGate.current();
        source.thread(after, null, new WebchatSource.Callback<WebchatSource.Thread>() {
            @Override
            public void onResult(WebchatSource.Thread th) {
                if (!readGate.accepts(t) || !isAdded()) return;
                messages = new ArrayList<>(WebchatRules.merge(messages, th.messages));
                apply(th);
                stateView.setVisibility(messages.isEmpty() ? View.VISIBLE : View.GONE);
                adapter.notifyDataSetChanged();
                scrollToEnd();
            }

            @Override
            public void onError(WebchatSource.Failure f) {
                if (readGate.accepts(t) && isAdded() && ("offline".equals(f.code) || "timeout".equals(f.code))) {
                    offline();
                }
            }
        });
    }

    /** «Mensajes anteriores»: al llegar arriba de la lista. */
    private void loadOlder() {
        if (loadingOlder || nextBefore == null || !source.available()) return;
        loadingOlder = true;
        int t = readGate.current();
        String before = nextBefore;
        source.thread(null, before, new WebchatSource.Callback<WebchatSource.Thread>() {
            @Override
            public void onResult(WebchatSource.Thread th) {
                loadingOlder = false;
                if (!readGate.accepts(t) || !isAdded()) return;
                int added = th.messages.size();
                messages = new ArrayList<>(WebchatRules.merge(messages, th.messages));
                nextBefore = th.nextBefore;
                adapter.notifyDataSetChanged();
                list.setSelection(added);
            }

            @Override
            public void onError(WebchatSource.Failure f) {
                loadingOlder = false;
            }
        });
    }

    private void apply(WebchatSource.Thread th) {
        typing = th.typing;
        draft = th.draft;
        if (!th.agentName.isEmpty()) agentName = th.agentName;
        showTyping();
        WebchatNotifier.markSeen(requireContext(), WebchatRules.lastId(messages));
    }

    /** FR-10: sin conexión no se muestra la conversación de memoria. */
    private void offline() {
        messages = new ArrayList<>();
        nextBefore = null;
        typing = false;
        draft = null;
        showTyping();
        adapter.notifyDataSetChanged();
        composeView.setVisibility(View.GONE);
        pendingScroll.setVisibility(View.GONE);
        showState(false, getString(source.connected() ? R.string.wc_not_available : R.string.wc_offline), true);
    }

    // --- Escribir (FR-9) --------------------------------------------------------------------

    private void picked(List<Uri> uris) {
        if (uris == null || uris.isEmpty() || !isAdded()) return;
        Context ctx = requireContext().getApplicationContext();
        io.execute(() -> {
            for (Uri uri : uris) {
                String name = displayName(ctx, uri);
                long size = size(ctx, uri);
                String problem = WebchatRules.fileProblem(name, size);
                if (problem != null) {
                    main.post(() -> toast(fileProblemText(problem, name)));
                    continue;
                }
                try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                    byte[] data = readAll(in, WebchatRules.MAX_FILE);
                    if (data == null) {
                        main.post(() -> toast(fileProblemText(WebchatRules.TOO_BIG, name)));
                        continue;
                    }
                    Pending p = new Pending(name, WebchatRules.mime(name), data);
                    main.post(() -> {
                        if (!isAdded()) return;
                        if (pending.size() >= WebchatRules.MAX_FILES) {
                            toast(sendProblemText(WebchatRules.TOO_MANY));
                            return;
                        }
                        pending.add(p);
                        showPending();
                    });
                } catch (Exception e) {
                    main.post(() -> toast(getString(R.string.wc_error_read_file, name)));
                }
            }
        });
    }

    private void showPending() {
        pendingView.removeAllViews();
        for (Pending p : pending) {
            View chip = LayoutInflater.from(requireContext()).inflate(R.layout.webchat_pending, pendingView, false);
            ImageView thumb = chip.findViewById(R.id.wc_pending_thumb);
            if (WebchatRules.isImage(p.name)) {
                Bitmap b = decode(p.data, 96);
                if (b != null) thumb.setImageBitmap(b);
            } else {
                thumb.setImageResource(R.drawable.ic_wc_doc);
            }
            ((TextView) chip.findViewById(R.id.wc_pending_name)).setText(p.name);
            View remove = chip.findViewById(R.id.wc_pending_remove);
            remove.setContentDescription(getString(R.string.wc_remove, p.name));
            remove.setOnClickListener(x -> {
                if (sending) return;
                pending.remove(p);
                showPending();
            });
            pendingView.addView(chip);
        }
        pendingScroll.setVisibility(pending.isEmpty() ? View.GONE : View.VISIBLE);
        updateSend();
    }

    private void updateSend() {
        send.setEnabled(!sending && WebchatRules.sendProblem(OutgoingText.trimEnd(input.getText().toString()),
                sizes()) == null);
    }

    private List<Long> sizes() {
        List<Long> out = new ArrayList<>();
        for (Pending p : pending) out.add((long) p.data.length);
        return out;
    }

    /** Primero se suben los archivos (FR-5), uno por uno, y después va webchat.send (FR-9). */
    private void send() {
        String text = OutgoingText.trimEnd(input.getText().toString());
        String problem = WebchatRules.sendProblem(text, sizes());
        if (problem != null || sending) {
            if (problem != null) toast(sendProblemText(problem));
            return;
        }
        sending = true;
        updateSend();
        int t = sendGate.next();
        List<Pending> files = new ArrayList<>(pending);
        uploadNext(t, files, 0, new ArrayList<>(), text);
    }

    private void uploadNext(int t, List<Pending> files, int i, List<String> refs, String text) {
        if (i == files.size()) {
            source.send(text, refs, new WebchatSource.Callback<WebchatSource.Message>() {
                @Override
                public void onResult(WebchatSource.Message m) {
                    if (!sendGate.accepts(t) || !isAdded()) return;
                    sending = false;
                    input.setText("");
                    pending.clear();
                    showPending();
                    messages = new ArrayList<>(WebchatRules.merge(messages, java.util.Collections.singletonList(m)));
                    stateView.setVisibility(View.GONE);
                    adapter.notifyDataSetChanged();
                    scrollToEnd();
                    WebchatNotifier.markSeen(requireContext(), WebchatRules.lastId(messages));
                }

                @Override
                public void onError(WebchatSource.Failure f) {
                    failed(t, f);
                }
            });
            return;
        }
        Pending p = files.get(i);
        if (p.upload != null) {
            refs.add(p.upload);
            uploadNext(t, files, i + 1, refs, text);
            return;
        }
        source.upload(p.name, p.mime, p.data, new WebchatSource.Callback<String>() {
            @Override
            public void onResult(String upload) {
                if (!sendGate.accepts(t) || !isAdded()) return;
                p.upload = upload;
                refs.add(upload);
                uploadNext(t, files, i + 1, refs, text);
            }

            @Override
            public void onError(WebchatSource.Failure f) {
                failed(t, f);
            }
        });
    }

    /** Lo escrito y los archivos se quedan, para volver a intentar. */
    private void failed(int t, WebchatSource.Failure f) {
        if (!sendGate.accepts(t) || !isAdded()) return;
        sending = false;
        // Un up_… vencido o ya usado: el siguiente intento vuelve a subir los archivos.
        if ("bad_upload".equals(f.code)) for (Pending p : pending) p.upload = null;
        updateSend();
        toast(sendProblemText(f.code));
    }

    // --- Archivos de la conversación ---------------------------------------------------------

    private void showPhoto(ImageView view, WebchatSource.FileRef f) {
        view.setTag(f.ref);
        if (!WebchatRules.validRef(f.ref)) return;
        Bitmap cached = PHOTOS.get(f.ref);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageDrawable(null);
        source.file(f.ref, new WebchatSource.Callback<byte[]>() {
            @Override
            public void onResult(byte[] data) {
                io.execute(() -> {
                    Bitmap b = decode(data, PHOTO_PX);
                    if (b == null) return;
                    PHOTOS.put(f.ref, b);
                    main.post(() -> {
                        if (f.ref.equals(view.getTag())) view.setImageBitmap(b);
                    });
                });
            }

            @Override
            public void onError(WebchatSource.Failure failure) {
                // La foto se queda sin cargar; el nombre sigue en la descripción.
            }
        });
    }

    /** Un archivo se descarga a la caché de la app y se abre con la app que lo sepa mostrar. */
    private void open(WebchatSource.FileRef f) {
        if (!WebchatRules.validRef(f.ref)) {
            // Un ref que no es f_<32 hex> no se pide ni se escribe (Iris en #174).
            toast(getString(R.string.wc_error_download, f.name));
            return;
        }
        Context ctx = requireContext().getApplicationContext();
        toast(getString(R.string.wc_downloading, f.name));
        source.file(f.ref, new WebchatSource.Callback<byte[]>() {
            @Override
            public void onResult(byte[] data) {
                io.execute(() -> {
                    try {
                        File dir = new File(ctx.getCacheDir(), "webchat");
                        if (!dir.isDirectory() && !dir.mkdirs()) throw new Exception("sin carpeta");
                        // El nombre en disco es el ref, nunca el nombre original (WEBCHAT.md §2).
                        File out = new File(dir, f.ref + "." + WebchatRules.safeExtension(f.name));
                        try (FileOutputStream o = new FileOutputStream(out)) {
                            o.write(data);
                        }
                        Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".files", out);
                        String mime = WebchatRules.mime(f.name);
                        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri,
                                mime == null ? "application/octet-stream" : mime)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        main.post(() -> {
                            if (!isAdded()) return;
                            try {
                                // Sin onUserLeaveHint: al volver sigue Webchat, no «Mensajes» (#125).
                                startActivity(Intent.createChooser(view, f.name)
                                        .addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION));
                            } catch (ActivityNotFoundException e) {
                                toast(getString(R.string.wc_no_app, f.name));
                            }
                        });
                    } catch (Exception e) {
                        main.post(() -> toast(getString(R.string.wc_error_download, f.name)));
                    }
                });
            }

            @Override
            public void onError(WebchatSource.Failure failure) {
                if (isAdded()) toast(getString(R.string.wc_error_download, f.name));
            }
        });
    }

    // --- Vista ---------------------------------------------------------------------------------

    private void showTyping() {
        // Con borrador se ve su burbuja; sin borrador, sólo «escribiendo…».
        boolean show = typing && draft == null;
        typingView.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) typingView.setText(getString(R.string.wc_typing, agentLabel()));
    }

    private String agentLabel() {
        return agentName.isEmpty() ? getString(R.string.wc_agent_default) : agentName;
    }

    private void showState(boolean loading, String message, boolean retry) {
        stateView.setVisibility(View.VISIBLE);
        progressView.setVisibility(loading ? View.VISIBLE : View.GONE);
        messageView.setVisibility(message == null ? View.GONE : View.VISIBLE);
        messageView.setText(message);
        retryView.setVisibility(retry ? View.VISIBLE : View.GONE);
    }

    private void scrollToEnd() {
        list.post(() -> list.setSelection(adapter.getCount() - 1));
    }

    private void toast(String text) {
        if (isAdded()) Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show();
    }

    private String fileProblemText(String code, String name) {
        return WebchatRules.TOO_BIG.equals(code) ? getString(R.string.wc_error_too_big_file, name)
                : getString(R.string.wc_error_bad_type, name);
    }

    private String sendProblemText(String code) {
        switch (code == null ? "" : code) {
            case WebchatRules.EMPTY:
                return getString(R.string.wc_error_empty);
            case WebchatRules.TOO_LONG:
                return getString(R.string.wc_error_too_long);
            case WebchatRules.TOO_BIG:
                return getString(R.string.wc_error_too_big);
            case WebchatRules.BAD_TYPE:
                return getString(R.string.wc_error_bad_type_any);
            case WebchatRules.TOO_MANY:
                return getString(R.string.wc_error_too_many, WebchatRules.MAX_FILES);
            case "bad_upload":
                return getString(R.string.wc_error_bad_upload);
            case "rate_limited":
                return getString(R.string.wc_error_rate_limited);
            case "offline":
            case "timeout":
                return getString(R.string.wc_offline);
            case "forbidden":
            case "unavailable":
                // §13: sin webchat.owner, o la instalación sin almacén o sin usuario de PaynaniWeb.
                return getString(R.string.wc_not_available);
            default:
                return getString(R.string.wc_error_send, code);
        }
    }

    /** Las filas: un separador por día, los mensajes y, al final, la burbuja del borrador. */
    private final class Adapter extends BaseAdapter {
        private static final int SEPARATOR = 0;
        private static final int MINE = 1;
        private static final int AGENT = 2;

        /** Cada fila es un separador (Integer: la posición del mensaje), un mensaje o el borrador (String). */
        private List<Object> rows() {
            List<Object> out = new ArrayList<>();
            ZoneId zone = ZoneId.systemDefault();
            for (int i = 0; i < messages.size(); i++) {
                if (WebchatRules.newDay(messages, i, zone)) out.add(i);
                out.add(messages.get(i));
            }
            if (draft != null) out.add(draft);
            return out;
        }

        private List<Object> rows = new ArrayList<>();

        @Override
        public void notifyDataSetChanged() {
            rows = rows();
            super.notifyDataSetChanged();
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
            if (row instanceof Integer) return SEPARATOR;
            if (row instanceof WebchatSource.Message && ((WebchatSource.Message) row).mine()) return MINE;
            return AGENT;
        }

        @Override
        public View getView(int position, View view, ViewGroup parent) {
            Object row = rows.get(position);
            int type = getItemViewType(position);
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (type == SEPARATOR) {
                TextView sep = (TextView) (view != null ? view
                        : inflater.inflate(R.layout.thread_separator, parent, false));
                WebchatSource.Message m = messages.get((Integer) row);
                java.time.LocalDate day = WebchatRules.day(m.at, ZoneId.systemDefault());
                sep.setText(day == null ? "" : day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)));
                return sep;
            }
            if (view == null) {
                view = inflater.inflate(type == MINE ? R.layout.webchat_item_mine : R.layout.webchat_item_agent,
                        parent, false);
            }
            TextView name = view.findViewById(R.id.wc_name);
            LinearLayout files = view.findViewById(R.id.wc_files);
            TextView text = view.findViewById(R.id.wc_text);
            TextView meta = view.findViewById(R.id.wc_meta);
            files.removeAllViews();
            if (row instanceof String) {
                // El borrador (Q325): lo que el agente lleva escrito, todavía sin hora.
                name.setVisibility(View.VISIBLE);
                name.setText(agentLabel());
                text.setVisibility(View.VISIBLE);
                text.setText((String) row);
                meta.setText(getString(R.string.wc_typing, agentLabel()));
                return view;
            }
            WebchatSource.Message m = (WebchatSource.Message) row;
            name.setVisibility(m.mine() ? View.GONE : View.VISIBLE);
            name.setText(agentLabel());
            for (WebchatSource.FileRef f : m.files) files.addView(fileView(inflater, files, f));
            text.setVisibility(m.text.isEmpty() ? View.GONE : View.VISIBLE);
            text.setText(m.text);
            long at = WebchatRules.parseIso(m.at);
            meta.setText(at > 0 ? time(parent.getContext(), at) : "");
            return view;
        }

        private View fileView(LayoutInflater inflater, ViewGroup parent, WebchatSource.FileRef f) {
            if (f.image()) {
                ImageView photo = (ImageView) inflater.inflate(R.layout.webchat_photo, parent, false);
                photo.setContentDescription(f.name);
                showPhoto(photo, f);
                photo.setOnClickListener(x -> open(f));
                return photo;
            }
            View card = inflater.inflate(R.layout.webchat_file, parent, false);
            ((TextView) card.findViewById(R.id.wc_file_name)).setText(f.name);
            ((TextView) card.findViewById(R.id.wc_file_size)).setText(
                    Formatter.formatShortFileSize(parent.getContext(), f.size));
            card.setContentDescription(getString(R.string.wc_download, f.name));
            card.setOnClickListener(x -> open(f));
            return card;
        }
    }

    // --- Utilidades ----------------------------------------------------------------------------

    /** Decodifica reducida a unos maxPx de ancho, o null si no es una imagen. */
    static Bitmap decode(byte[] data, int maxPx) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 1;
        while (bounds.outWidth / (o.inSampleSize * 2) >= maxPx) o.inSampleSize *= 2;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }

    private static String displayName(Context ctx, Uri uri) {
        try (Cursor c = ctx.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (Exception ignored) {
        }
        String last = uri.getLastPathSegment();
        return last == null ? "archivo" : last;
    }

    private static long size(Context ctx, Uri uri) {
        try (Cursor c = ctx.getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Exception ignored) {
        }
        return 0;
    }

    /** Todo el archivo, o null si pasa de max (el tamaño que dijo el sistema puede no ser real). */
    private static byte[] readAll(InputStream in, long max) throws Exception {
        if (in == null) throw new Exception("sin datos");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > max) return null;
        }
        return out.toByteArray();
    }

    /** «5:52 a.m.» (o «17:52» si el teléfono usa 24 horas). */
    static String time(android.content.Context context, long millis) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(millis);
        if (android.text.format.DateFormat.is24HourFormat(context)) {
            return String.format(java.util.Locale.ROOT, "%d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY),
                    c.get(java.util.Calendar.MINUTE));
        }
        int h = c.get(java.util.Calendar.HOUR);
        return String.format(java.util.Locale.ROOT, "%d:%02d ", h == 0 ? 12 : h, c.get(java.util.Calendar.MINUTE))
                + context.getString(c.get(java.util.Calendar.AM_PM) == java.util.Calendar.AM ? R.string.msg_am : R.string.msg_pm);
    }
}
