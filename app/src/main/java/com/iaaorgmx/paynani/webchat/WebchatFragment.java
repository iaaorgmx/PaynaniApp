package com.iaaorgmx.paynani.webchat;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.util.LruCache;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat;
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
    private final SafeIo io = new SafeIo();
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
    private View replyBar;
    private TextView replyName;
    private TextView replyText;
    private final Adapter adapter = new Adapter();

    /** El mensaje que se va a citar (#745 FR-11), o null. */
    private WebchatSource.Message replyingTo;
    /** El mensaje resaltado un momento al tocar su cita (FR-12), o null. */
    private String highlightId;
    private PopupWindow menu;

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
        replyBar = v.findViewById(R.id.wc_reply_bar);
        replyName = v.findViewById(R.id.wc_reply_name);
        replyText = v.findViewById(R.id.wc_reply_text);
        v.findViewById(R.id.wc_reply_close).setOnClickListener(x -> clearReply());
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
        dismissMenu();
        WebchatEvents.remove(this);
        WebchatNotifier.setScreenVisible(requireContext(), false);
        readGate.close();
        sendGate.close();
        sending = false;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        io.shutdown();
        // Lo que el hilo de fondo dejó para la pantalla ya no tiene a quién mostrarse (#5).
        main.removeCallbacksAndMessages(null);
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
            case WebchatEvents.REACTIONS:
                reloadReactions();
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

    /**
     * #745 FR-5: una reacción cambió, quizá en un mensaje viejo, y refresh() sólo trae lo posterior.
     * Se vuelve a pedir la última página y se reemplazan por id los mensajes que ya se ven, sin
     * mover la lista. Una reacción en un mensaje anterior a esa página se ve al volver a abrir.
     */
    private void reloadReactions() {
        if (messages.isEmpty() || !source.available()) return;
        int t = readGate.current();
        source.thread(null, null, new WebchatSource.Callback<WebchatSource.Thread>() {
            @Override
            public void onResult(WebchatSource.Thread th) {
                if (!readGate.accepts(t) || !isAdded()) return;
                messages = new ArrayList<>(WebchatRules.merge(messages, th.messages));
                adapter.notifyDataSetChanged();
            }

            @Override
            public void onError(WebchatSource.Failure f) {
                // El siguiente aviso o la siguiente lectura las trae.
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
        dismissMenu();
        clearReply();
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
        io.run(() -> {
            for (Uri uri : uris) {
                String name = displayName(ctx, uri);
                long size = size(ctx, uri);
                String problem = WebchatRules.fileProblem(name, size);
                if (problem != null) {
                    later(() -> toast(fileProblemText(problem, name)));
                    continue;
                }
                try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                    byte[] data = readAll(in, WebchatRules.MAX_FILE);
                    if (data == null) {
                        later(() -> toast(fileProblemText(WebchatRules.TOO_BIG, name)));
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
                    later(() -> toast(getString(R.string.wc_error_read_file, name)));
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
            // FR-11: la cita va con el mensaje; si se quitó mientras subían los archivos, ya no va.
            String quoted = replyingTo == null ? null : replyingTo.id;
            source.send(text, refs, quoted, new WebchatSource.Callback<WebchatSource.Message>() {
                @Override
                public void onResult(WebchatSource.Message m) {
                    if (!sendGate.accepts(t) || !isAdded()) return;
                    sending = false;
                    input.setText("");
                    pending.clear();
                    showPending();
                    clearReply();
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

    // --- Citas y reacciones (paynani #745) ---------------------------------------------------

    /** FR-11: la barra de cita arriba del cuadro de texto; el mensaje sale con reply_to. */
    private void startReply(WebchatSource.Message m) {
        if (!source.canReply() || !isAdded()) return;
        replyingTo = m;
        String name = m.mine() ? getString(R.string.wc_you) : agentLabel();
        String line = WebchatRules.excerpt(m);
        replyName.setText(name);
        replyText.setText(line);
        replyBar.setContentDescription(getString(R.string.wc_replying_to, name, line));
        replyBar.setVisibility(View.VISIBLE);
        input.requestFocus();
        InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
    }

    private void clearReply() {
        replyingTo = null;
        if (replyBar != null) replyBar.setVisibility(View.GONE);
    }

    /**
     * FR-8: el menú de un mensaje, arriba de la burbuja si cabe y si no abajo, alineado con ella.
     * La fila de reacciones sólo si este paynani reacciona, «Reply» sólo si cita (FR-14) y «Copy»
     * sólo si hay texto (FR-13). Sin nada que ofrecer no se abre.
     */
    @android.annotation.SuppressLint("InflateParams")   // el contenido de un PopupWindow no tiene padre
    private void openMenu(View bubble, WebchatSource.Message m) {
        if (!isAdded()) return;
        dismissMenu();
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        View content = inflater.inflate(R.layout.webchat_menu, null, false);
        LinearLayout row = content.findViewById(R.id.wc_menu_reactions);
        boolean react = source.canReact();
        if (react) {
            String mine = m.myReaction();
            for (String e : WebchatRules.QUICK_REACTIONS) {
                row.addView(emojiButton(row, e, e.equals(mine), () -> react(m, WebchatRules.reactionChoice(mine, e))));
            }
            TextView more = emojiButton(row, "+", false, () -> askEmoji(m));
            more.setContentDescription(getString(R.string.wc_more_reactions));
            more.setTextColor(getResources().getColor(R.color.paynani_gris, null));
            row.addView(more);
        } else {
            row.setVisibility(View.GONE);
        }
        View reply = content.findViewById(R.id.wc_menu_reply);
        boolean canReply = source.canReply();
        reply.setVisibility(canReply ? View.VISIBLE : View.GONE);
        reply.setOnClickListener(x -> {
            dismissMenu();
            startReply(m);
        });
        View copy = content.findViewById(R.id.wc_menu_copy);
        boolean canCopy = !m.text.isEmpty();
        copy.setVisibility(canCopy ? View.VISIBLE : View.GONE);
        copy.setOnClickListener(x -> {
            dismissMenu();
            copy(m);
        });
        if (!canReply && !canCopy) content.findViewById(R.id.wc_menu_actions).setVisibility(View.GONE);
        if (!react && !canReply && !canCopy) return;

        PopupWindow pw = new PopupWindow(content, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pw.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        pw.setOutsideTouchable(true);
        content.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int w = content.getMeasuredWidth();
        int h = content.getMeasuredHeight();
        int[] at = new int[2];
        bubble.getLocationInWindow(at);
        int[] listAt = new int[2];
        list.getLocationInWindow(listAt);
        int margin = dp(8);
        int screenW = list.getRootView().getWidth();
        int x = m.mine() ? at[0] + bubble.getWidth() - w : at[0];
        x = Math.max(margin, Math.min(x, screenW - w - margin));
        // Arriba de la burbuja si cabe dentro de la lista; si no, debajo.
        int y = at[1] - h - margin >= listAt[1] ? at[1] - h - margin : at[1] + bubble.getHeight() + margin;
        pw.showAtLocation(list, Gravity.NO_GRAVITY, x, y);
        menu = pw;
    }

    private void dismissMenu() {
        if (menu != null) menu.dismiss();
        menu = null;
    }

    /** Un emoji de la fila del menú (FR-9); el del dueño, resaltado. */
    private TextView emojiButton(ViewGroup parent, String emoji, boolean selected, Runnable pick) {
        TextView b = new TextView(parent.getContext());
        b.setText(emoji);
        // Los 8 (7 y el «+») caben a lo ancho en un teléfono de 360 dp.
        b.setTextSize(22);
        b.setGravity(Gravity.CENTER);
        b.setMinWidth(dp(40));
        b.setMinimumWidth(dp(40));
        b.setMinHeight(dp(44));
        b.setPadding(0, 0, 0, 0);
        if (selected) {
            b.setBackgroundResource(R.drawable.wc_reaction_mine_bg);
        } else {
            android.util.TypedValue tv = new android.util.TypedValue();
            parent.getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
            b.setBackgroundResource(tv.resourceId);
        }
        b.setContentDescription(getString(R.string.wc_react_with, emoji));
        b.setSelected(selected);
        b.setOnClickListener(x -> {
            dismissMenu();
            pick.run();
        });
        return b;
    }

    /** El «+» (FR-9, Q133 = A): un campo para un emoji, con el teclado de emojis del teléfono. */
    private void askEmoji(WebchatSource.Message m) {
        if (!isAdded()) return;
        EditText field = new EditText(requireContext());
        field.setHint(R.string.wc_emoji_hint);
        field.setSingleLine(true);
        field.setGravity(Gravity.CENTER);
        field.setTextSize(28);
        LinearLayout box = new LinearLayout(requireContext());
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.wc_emoji_title)
                .setView(box)
                // El click se pone en onShow: así un texto que no es emoji deja el diálogo abierto.
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> {
            field.requestFocus();
            InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
            // Metis en PaynaniApp#21: con algo que no es un solo emoji, el error va en el campo y
            // el diálogo sigue abierto para corregirlo.
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x -> {
                String e = field.getText().toString().trim();
                if (!WebchatRules.singleEmoji(e)) {
                    field.setError(getString(R.string.wc_error_bad_emoji));
                    return;
                }
                dialog.dismiss();
                react(m, WebchatRules.reactionChoice(m.myReaction(), e));
            });
        });
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        dialog.show();
    }

    /** FR-9, FR-10: webchat.react; la burbuja se vuelve a dibujar con lo que respondió paynani. */
    private void react(WebchatSource.Message m, String emoji) {
        if (!source.canReact()) return;
        int t = readGate.current();
        source.react(m.id, emoji, new WebchatSource.Callback<List<WebchatSource.Reaction>>() {
            @Override
            public void onResult(List<WebchatSource.Reaction> reactions) {
                if (!readGate.accepts(t) || !isAdded()) return;
                int i = WebchatRules.indexOf(messages, m.id);
                if (i < 0) return;
                messages = new ArrayList<>(messages);
                messages.set(i, messages.get(i).withReactions(reactions));
                adapter.notifyDataSetChanged();
            }

            @Override
            public void onError(WebchatSource.Failure f) {
                if (!readGate.accepts(t) || !isAdded()) return;
                switch (f.code) {
                    case "bad_emoji":
                        toast(getString(R.string.wc_error_bad_emoji));
                        break;
                    case "offline":
                    case "timeout":
                        toast(getString(R.string.wc_offline));
                        break;
                    case "rate_limited":
                        toast(getString(R.string.wc_error_rate_limited));
                        break;
                    default:
                        toast(getString(R.string.wc_error_react, f.code));
                        break;
                }
            }
        });
    }

    /** FR-13: el texto al portapapeles. Desde Android 13 el sistema ya dice que se copió. */
    private void copy(WebchatSource.Message m) {
        ClipboardManager cm = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.wc_notification_channel), m.text));
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(requireContext(), R.string.wc_copied, Toast.LENGTH_SHORT).show();
        }
    }

    /** FR-12: tocar una cita lleva al original si está cargado y lo resalta un momento. */
    private void jumpTo(String ref) {
        if (WebchatRules.indexOf(messages, ref) < 0) {
            toast(getString(R.string.wc_quote_not_loaded));
            return;
        }
        int row = adapter.rowOf(ref);
        if (row < 0) return;
        list.setSelection(Math.max(0, row - 1));
        highlightId = ref;
        adapter.notifyDataSetChanged();
        main.postDelayed(() -> {
            if (!ref.equals(highlightId) || !isAdded()) return;
            highlightId = null;
            adapter.notifyDataSetChanged();
        }, 1500);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * FR-11: deslizar una burbuja a la derecha abre la cita, como en WhatsApp. Va en la burbuja y en
     * lo que tiene adentro (el texto con enlaces y los archivos atrapan el toque); hasta que el
     * dedo se mueve de lado no toma nada, así que el toque largo y los enlaces siguen igual.
     */
    private final class SwipeToReply implements View.OnTouchListener {
        private final View bubble;
        private final WebchatSource.Message message;
        private final int slop;
        private float downX;
        private float downY;
        private boolean swiping;

        SwipeToReply(View bubble, WebchatSource.Message message) {
            this.bubble = bubble;
            this.message = message;
            this.slop = ViewConfiguration.get(bubble.getContext()).getScaledTouchSlop();
        }

        // No toma toques: devuelve false hasta que el dedo va de lado, así que un toque sigue
        // siendo un click normal de la vista (con performClick) y TalkBack no cambia.
        @android.annotation.SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    swiping = false;
                    return false;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - downX;
                    float dy = e.getRawY() - downY;
                    if (!swiping && dx > slop && dx > Math.abs(dy) * 2) {
                        swiping = true;
                        v.cancelLongPress();
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (!swiping) return false;
                    bubble.setTranslationX(Math.min(Math.max(0, dx), dp(96)));
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (!swiping) return false;
                    swiping = false;
                    boolean far = bubble.getTranslationX() >= dp(64);
                    bubble.animate().translationX(0).setDuration(150).start();
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                    if (far && e.getActionMasked() == MotionEvent.ACTION_UP) startReply(message);
                    return true;
                }
                default:
                    return swiping;
            }
        }
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
                io.run(() -> {
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
                io.run(() -> {
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
                        later(() -> toast(getString(R.string.wc_error_download, f.name)));
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

    /**
     * Algo para la pantalla desde el hilo de fondo. Si el fragmento ya se fue, no corre: getString y
     * requireContext lanzan IllegalStateException sin él (#5).
     */
    private void later(Runnable ui) {
        main.post(() -> {
            if (isAdded()) ui.run();
        });
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
    @android.annotation.SuppressLint("ClickableViewAccessibility")   // SwipeToReply no toma toques (ver ahí)
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

        /** La fila del mensaje id, o -1 (FR-12). */
        int rowOf(String id) {
            for (int i = 0; i < rows.size(); i++) {
                Object row = rows.get(i);
                if (row instanceof WebchatSource.Message && ((WebchatSource.Message) row).id.equals(id)) return i;
            }
            return -1;
        }

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
            View bubble = view.findViewById(R.id.wc_bubble);
            files.removeAllViews();
            if (row instanceof String) {
                // El borrador (Q325): lo que el agente lleva escrito, todavía sin hora. Sin menú,
                // cita ni reacciones (FR-8): todavía no es un mensaje.
                unbind(view, bubble, text);
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
            View.OnLongClickListener longPress = x -> {
                openMenu(bubble, m);
                return true;
            };
            View.OnTouchListener swipe = source.canReply() ? new SwipeToReply(bubble, m) : null;
            for (WebchatSource.FileRef f : m.files) files.addView(fileView(inflater, files, f, longPress, swipe));
            text.setVisibility(m.text.isEmpty() ? View.GONE : View.VISIBLE);
            text.setText(m.text);
            long at = WebchatRules.parseIso(m.at);
            meta.setText(at > 0 ? time(parent.getContext(), at) : "");
            bindQuote(view, m, longPress, swipe);
            bindReactions(view, m);
            // FR-8: tocar y mantener la burbuja (o su texto) abre el menú; FR-11: deslizar cita.
            bubble.setOnLongClickListener(longPress);
            text.setOnLongClickListener(longPress);
            bubble.setOnTouchListener(swipe);
            text.setOnTouchListener(swipe);
            bubble.setTranslationX(0);
            // FR-15: la acción de TalkBack «Opciones del mensaje», también abre el menú.
            ViewCompat.replaceAccessibilityAction(bubble, AccessibilityActionCompat.ACTION_LONG_CLICK,
                    getString(R.string.wc_message_options), (v, args) -> {
                        openMenu(bubble, m);
                        return true;
                    });
            // FR-12: el original, resaltado un momento.
            view.setBackgroundColor(m.id.equals(highlightId) ? 0x1f8f2d2b : Color.TRANSPARENT);
            return view;
        }

        /** Una fila reciclada que ahora es el borrador: sin nada de un mensaje. */
        private void unbind(View view, View bubble, TextView text) {
            view.findViewById(R.id.wc_quote).setVisibility(View.GONE);
            LinearLayout reactions = view.findViewById(R.id.wc_reactions);
            reactions.removeAllViews();
            reactions.setVisibility(View.GONE);
            bubble.setOnLongClickListener(null);
            bubble.setLongClickable(false);
            text.setOnLongClickListener(null);
            text.setLongClickable(false);
            bubble.setOnTouchListener(null);
            text.setOnTouchListener(null);
            bubble.setTranslationX(0);
            ViewCompat.replaceAccessibilityAction(bubble, AccessibilityActionCompat.ACTION_LONG_CLICK, null, null);
            view.setBackgroundColor(Color.TRANSPARENT);
        }

        /** FR-12: la cita dentro de la respuesta; una missing dice «Mensaje no disponible» y no lleva a nada. */
        private void bindQuote(View view, WebchatSource.Message m, View.OnLongClickListener longPress,
                               View.OnTouchListener swipe) {
            View quote = view.findViewById(R.id.wc_quote);
            WebchatSource.ReplyTo r = m.replyTo;
            if (r == null) {
                quote.setVisibility(View.GONE);
                return;
            }
            quote.setVisibility(View.VISIBLE);
            TextView qName = view.findViewById(R.id.wc_quote_name);
            TextView qText = view.findViewById(R.id.wc_quote_text);
            String line = r.missing ? getString(R.string.wc_quote_missing) : r.excerpt;
            qName.setVisibility(r.missing || r.name.isEmpty() ? View.GONE : View.VISIBLE);
            qName.setText(r.name);
            qText.setText(line);
            quote.setContentDescription(getString(R.string.wc_quote_desc, r.name, line));
            if (r.missing) {
                quote.setOnClickListener(null);
                quote.setClickable(false);
            } else {
                quote.setOnClickListener(x -> jumpTo(r.ref));
            }
            quote.setOnLongClickListener(longPress);
            quote.setOnTouchListener(swipe);
        }

        /**
         * FR-10: cada emoji con su número si son más de uno; la del dueño resaltada, y tocarla la
         * quita. TalkBack lee «👍, 2, incluida la tuya» (FR-15).
         */
        private void bindReactions(View view, WebchatSource.Message m) {
            LinearLayout box = view.findViewById(R.id.wc_reactions);
            box.removeAllViews();
            box.setVisibility(m.reactions.isEmpty() ? View.GONE : View.VISIBLE);
            LayoutInflater inflater = LayoutInflater.from(box.getContext());
            for (WebchatSource.Reaction r : m.reactions) {
                TextView chip = (TextView) inflater.inflate(R.layout.webchat_reaction, box, false);
                chip.setText(r.count > 1 ? r.emoji + " " + r.count : r.emoji);
                chip.setContentDescription(getString(r.mine ? R.string.wc_reaction_desc_mine
                        : R.string.wc_reaction_desc, r.emoji, r.count));
                if (r.mine) {
                    chip.setBackgroundResource(R.drawable.wc_reaction_mine_bg);
                    if (source.canReact()) {
                        chip.setOnClickListener(x -> react(m, ""));
                        ViewCompat.replaceAccessibilityAction(chip, AccessibilityActionCompat.ACTION_CLICK,
                                getString(R.string.wc_reaction_remove), null);
                    }
                }
                box.addView(chip);
            }
        }

        private View fileView(LayoutInflater inflater, ViewGroup parent, WebchatSource.FileRef f,
                              View.OnLongClickListener longPress, View.OnTouchListener swipe) {
            View out;
            if (f.image()) {
                ImageView photo = (ImageView) inflater.inflate(R.layout.webchat_photo, parent, false);
                photo.setContentDescription(f.name);
                showPhoto(photo, f);
                photo.setOnClickListener(x -> open(f));
                out = photo;
            } else {
                View card = inflater.inflate(R.layout.webchat_file, parent, false);
                ((TextView) card.findViewById(R.id.wc_file_name)).setText(f.name);
                ((TextView) card.findViewById(R.id.wc_file_size)).setText(
                        Formatter.formatShortFileSize(parent.getContext(), f.size));
                card.setContentDescription(getString(R.string.wc_download, f.name));
                card.setOnClickListener(x -> open(f));
                out = card;
            }
            // FR-8, FR-11: sobre una foto o un archivo, el toque largo y el deslizar también valen.
            out.setOnLongClickListener(longPress);
            out.setOnTouchListener(swipe);
            return out;
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
