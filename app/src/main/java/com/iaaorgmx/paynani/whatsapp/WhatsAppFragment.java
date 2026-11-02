package com.iaaorgmx.paynani.whatsapp;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.iaaorgmx.paynani.R;
import com.iaaorgmx.paynani.gateway.GatewayPrefs;
import com.iaaorgmx.paynani.gateway.Phone;

/**
 * «WhatsApp» (paynani PRD #550 FR-8): el estado de la cuenta y, sólo con whatsapp.admin, pedir el
 * código de vinculación, cancelarlo y desvincular (FR-6a). Nunca el QR: el teléfono no puede
 * escanear su propia pantalla.
 *
 * El código es una credencial de un solo uso: sólo vive en esta pantalla mientras se ve. Se borra
 * al vencer, al salir de la pantalla y al cambiar el estado, y nunca se escribe en el log.
 */
public class WhatsAppFragment extends Fragment implements WhatsAppEvents.Listener {

    private WhatsAppSource source;
    private final Handler main = new Handler(Looper.getMainLooper());

    private TextView offlineView;
    private ImageView statusIcon;
    private TextView statusView;
    private TextView detailView;
    private LinearProgressIndicator progressView;
    private TextView noAdminView;
    private MaterialButton unlinkView;
    private View linkCard;
    private TextInputLayout numberLayout;
    private TextInputEditText numberView;
    private View codeBox;
    private TextView codeView;
    private TextView countdownView;
    private MaterialButton requestView;
    private MaterialButton cancelView;

    private WhatsAppSource.Status status;
    /** El código que se muestra ahora. Sólo en memoria y sólo mientras la pantalla se ve (FR-6a). */
    private final CodeSlot slot = new CodeSlot();
    /** whatsapp.status: una respuesta de una lectura anterior, o de antes de pausar, se ignora. */
    private final Gate statusGate = new Gate();
    /** whatsapp.link_cancel y whatsapp.unlink. */
    private final Gate actionGate = new Gate();
    private boolean actionBusy;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!isAdded()) return;
            WhatsAppSource.LinkCode code = slot.code();
            if (code != null) {
                long left = remaining(code.expiresInS, code.receivedElapsedMs, code.expiresAt);
                if (left <= 0) {
                    clearCode();
                    snack(getString(R.string.wa_expired));
                    load();
                    return;
                }
                countdownView.setText(getString(R.string.wa_countdown, WhatsAppRules.countdown(left)));
            } else if (status != null && WhatsAppRules.state(status.state) == WhatsAppRules.State.LINKING) {
                showDetail();
            }
            main.postDelayed(this, 1000);
        }
    };

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        source = WhatsAppSources.get(requireContext());
        LayoutInflater themed = inflater.cloneInContext(
                new ContextThemeWrapper(requireContext(), R.style.Theme_Paynani_M3));
        View v = themed.inflate(R.layout.fragment_whatsapp, container, false);
        offlineView = v.findViewById(R.id.wa_offline);
        statusIcon = v.findViewById(R.id.wa_status_icon);
        statusView = v.findViewById(R.id.wa_status);
        detailView = v.findViewById(R.id.wa_status_detail);
        progressView = v.findViewById(R.id.wa_progress);
        noAdminView = v.findViewById(R.id.wa_no_admin);
        unlinkView = v.findViewById(R.id.wa_unlink);
        linkCard = v.findViewById(R.id.wa_link_card);
        numberLayout = v.findViewById(R.id.wa_number_layout);
        numberView = v.findViewById(R.id.wa_number);
        codeBox = v.findViewById(R.id.wa_code_box);
        codeView = v.findViewById(R.id.wa_code);
        countdownView = v.findViewById(R.id.wa_countdown);
        requestView = v.findViewById(R.id.wa_request);
        cancelView = v.findViewById(R.id.wa_cancel);
        requestView.setOnClickListener(x -> requestCode());
        cancelView.setOnClickListener(x -> cancelLink());
        unlinkView.setOnClickListener(x -> confirmUnlink());
        v.findViewById(R.id.wa_copy).setOnClickListener(x -> copyCode());
        return v;
    }

    @Override
    public void onResume() {
        super.onResume();
        slot.open();
        statusGate.open();
        actionGate.open();
        WhatsAppEvents.add(this);
        load();
        main.post(tick);
    }

    @Override
    public void onPause() {
        super.onPause();
        WhatsAppEvents.remove(this);
        main.removeCallbacks(tick);
        // Al salir de la pantalla el código se olvida (FR-6a), y una respuesta de whatsapp.link
        // que llegue después se descarta: al volver se pide otro.
        slot.close();
        statusGate.close();
        actionGate.close();
        actionBusy = false;
        clearCode();
        updateBusy();
    }

    @Override
    public void onChanged(String what) {
        if ((WhatsAppEvents.STATUS.equals(what) || WhatsAppEvents.CONNECTION.equals(what)) && isResumed()) load();
    }

    // --- Lectura ----------------------------------------------------------------------------------

    private void load() {
        if (!source.available()) {
            statusGate.invalidate();
            status = null;
            clearCode();
            progressView.setVisibility(View.GONE);
            offlineView.setVisibility(View.VISIBLE);
            show();
            return;
        }
        offlineView.setVisibility(View.GONE);
        int gen = statusGate.next();
        progressView.setVisibility(View.VISIBLE);
        source.status(new WhatsAppSource.Callback<WhatsAppSource.Status>() {
            @Override
            public void onResult(WhatsAppSource.Status result) {
                if (!statusGate.accepts(gen) || !isAdded()) return;
                updateBusy();
                status = result;
                // Ya vinculada, o la vinculación la tomó otro: el código de aquí ya no sirve.
                if (slot.code() != null && WhatsAppRules.state(result.state) != WhatsAppRules.State.LINKING) {
                    clearCode();
                }
                show();
            }

            @Override
            public void onError(WhatsAppSource.Failure f) {
                if (!statusGate.accepts(gen) || !isAdded()) return;
                updateBusy();
                status = null;
                clearCode();
                offlineView.setVisibility(View.VISIBLE);
                show();
            }
        });
    }

    private void show() {
        WhatsAppRules.State state = status == null ? null : WhatsAppRules.state(status.state);
        WhatsAppSource.LinkCode code = slot.code();
        if (state == null) {
            statusIcon.setImageResource(R.drawable.ic_status_warn);
            statusView.setText(R.string.wa_offline);
            detailView.setVisibility(View.GONE);
            noAdminView.setVisibility(View.GONE);
            unlinkView.setVisibility(View.GONE);
            linkCard.setVisibility(View.GONE);
            return;
        }
        statusIcon.setImageResource(state == WhatsAppRules.State.CONNECTED ? R.drawable.ic_status_ok
                : R.drawable.ic_status_warn);
        statusView.setText(stateLabel(state));
        showDetail();

        boolean admin = source.admin();
        WhatsAppRules.Actions actions = WhatsAppRules.actions(state, admin);
        noAdminView.setVisibility(!admin && state != WhatsAppRules.State.SERVICE_DOWN ? View.VISIBLE : View.GONE);
        unlinkView.setVisibility(actions.unlink ? View.VISIBLE : View.GONE);
        linkCard.setVisibility(actions.requestCode || code != null ? View.VISIBLE : View.GONE);
        numberLayout.setVisibility(actions.requestCode && status.linkNumber == null ? View.VISIBLE : View.GONE);
        requestView.setVisibility(actions.requestCode ? View.VISIBLE : View.GONE);
        requestView.setText(code != null || state == WhatsAppRules.State.LINKING ? R.string.wa_request_again
                : R.string.wa_request);
        cancelView.setVisibility(actions.cancelLink ? View.VISIBLE : View.GONE);
        codeBox.setVisibility(code != null ? View.VISIBLE : View.GONE);
        if (code != null) codeView.setText(code.code);
        enable(!busy());
    }

    private void showDetail() {
        if (status == null) return;
        WhatsAppRules.State state = WhatsAppRules.state(status.state);
        String detail = null;
        if (state == WhatsAppRules.State.LINKING && status.linkingBy != null) {
            long left = remaining(status.linkingExpiresInS, status.receivedElapsedMs, status.linkingExpiresAt);
            detail = getString(R.string.wa_detail_linking, byLabel(status.linkingBy), WhatsAppRules.countdown(left));
        } else if (status.me != null && (state == WhatsAppRules.State.CONNECTED
                || state == WhatsAppRules.State.RECONNECTING)) {
            detail = getString(R.string.wa_detail_me, status.me);
        }
        detailView.setText(detail);
        detailView.setVisibility(detail == null ? View.GONE : View.VISIBLE);
    }

    // --- Vincular ---------------------------------------------------------------------------------

    private void requestCode() {
        if (status == null || busy()) return;
        String number = null;
        if (status.linkNumber == null) {
            String typed = numberView.getText() == null ? "" : numberView.getText().toString().trim();
            number = Phone.toE164(typed, null);
            if (TextUtils.isEmpty(number) || !number.startsWith("+")) {
                numberLayout.setError(getString(R.string.wa_error_number));
                return;
            }
            numberLayout.setError(null);
        }
        clearCode();
        int t = slot.request();
        updateBusy();
        source.link(number, new WhatsAppSource.Callback<WhatsAppSource.LinkCode>() {
            @Override
            public void onResult(WhatsAppSource.LinkCode result) {
                // Después de salir de la pantalla no se guarda (FR-6a).
                if (!slot.deliver(t, result) || !isAdded()) return;
                updateBusy();
                load();
            }

            @Override
            public void onError(WhatsAppSource.Failure f) {
                if (!slot.fail(t) || !isAdded()) return;
                updateBusy();
                if ("invalid".equals(f.code) && "number".equals(f.field)) {
                    numberLayout.setError(getString(R.string.wa_error_number));
                } else {
                    snack(errorText(f));
                }
                load();
            }
        });
    }

    private void cancelLink() {
        if (busy()) return;
        int t = startAction();
        source.linkCancel(new WhatsAppSource.Callback<Void>() {
            @Override
            public void onResult(Void result) {
                if (!endAction(t)) return;
                clearCode();
                snack(getString(R.string.wa_cancelled));
                load();
            }

            @Override
            public void onError(WhatsAppSource.Failure f) {
                if (!endAction(t)) return;
                snack(errorText(f));
                load();
            }
        });
    }

    private void confirmUnlink() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.wa_unlink_title)
                .setMessage(R.string.wa_unlink_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.wa_unlink, (d, w) -> unlink())
                .show();
    }

    private void unlink() {
        if (busy()) return;
        int t = startAction();
        source.unlink(new WhatsAppSource.Callback<Boolean>() {
            @Override
            public void onResult(Boolean remote) {
                if (!endAction(t)) return;
                longSnack(getString(Boolean.TRUE.equals(remote) ? R.string.wa_unlinked : R.string.wa_unlinked_manual));
                load();
            }

            @Override
            public void onError(WhatsAppSource.Failure f) {
                if (!endAction(t)) return;
                snack(errorText(f));
                load();
            }
        });
    }

    private void copyCode() {
        WhatsAppSource.LinkCode code = slot.code();
        if (code == null) return;
        ClipboardManager cm = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText(getString(R.string.wa_title), code.code);
        // Que el teclado y la vista previa del portapapeles no lo muestren ni lo sugieran.
        PersistableBundle extras = new PersistableBundle();
        extras.putBoolean(Build.VERSION.SDK_INT >= 33 ? ClipDescription.EXTRA_IS_SENSITIVE
                : "android.content.extra.IS_SENSITIVE", true);
        clip.getDescription().setExtras(extras);
        cm.setPrimaryClip(clip);
        if (Build.VERSION.SDK_INT < 33) snack(getString(R.string.wa_copied));
    }

    private void clearCode() {
        slot.clear();
        if (codeView != null) codeView.setText(null);
        if (codeBox != null) codeBox.setVisibility(View.GONE);
    }

    // --- Ayudas -----------------------------------------------------------------------------------

    /** Con expires_in_s, sin depender de relojes; si la pasarela no lo manda, con expires_at. */
    private long remaining(long expiresInS, long receivedElapsedMs, String expiresAt) {
        long left = WhatsAppRules.remainingSeconds(expiresInS, receivedElapsedMs, SystemClock.elapsedRealtime());
        if (left >= 0) return left;
        return WhatsAppRules.remainingSeconds(expiresAt, System.currentTimeMillis(),
                new GatewayPrefs(requireContext()).getClockOffsetMs());
    }

    private boolean busy() {
        return slot.busy() || actionBusy;
    }

    private void updateBusy() {
        boolean b = busy();
        if (progressView != null) progressView.setVisibility(b ? View.VISIBLE : View.GONE);
        if (requestView != null) enable(!b);
    }

    private int startAction() {
        actionBusy = true;
        updateBusy();
        return actionGate.next();
    }

    /** false si la respuesta ya no le toca a la pantalla. */
    private boolean endAction(int t) {
        if (!actionGate.accepts(t) || !isAdded()) return false;
        actionBusy = false;
        updateBusy();
        return true;
    }

    private void enable(boolean on) {
        requestView.setEnabled(on);
        cancelView.setEnabled(on);
        unlinkView.setEnabled(on);
    }

    private int stateLabel(WhatsAppRules.State state) {
        switch (state) {
            case CONNECTED:
                return R.string.wa_state_connected;
            case RECONNECTING:
                return R.string.wa_state_reconnecting;
            case LINKING:
                return R.string.wa_state_linking;
            case SERVICE_DOWN:
                return R.string.wa_state_service_down;
            case NOT_LINKED:
            default:
                return R.string.wa_state_not_linked;
        }
    }

    private String byLabel(String by) {
        if ("web".equals(by)) return getString(R.string.wa_by_web);
        if ("terminal".equals(by)) return getString(R.string.wa_by_terminal);
        return getString(R.string.wa_by_phone);
    }

    private String errorText(WhatsAppSource.Failure f) {
        switch (f.code) {
            case "forbidden":
                return getString(R.string.wa_error_forbidden);
            case "already_linked":
                return getString(R.string.wa_error_already_linked);
            case "conflict":
                return getString(R.string.wa_error_conflict);
            case "unavailable":
                return getString(R.string.wa_error_unavailable);
            case "offline":
            case "timeout":
                return getString(R.string.wa_error_offline);
            default:
                return getString(R.string.wa_error_generic, f.code);
        }
    }

    private void snack(String text) {
        View v = getView();
        if (v != null) Snackbar.make(v, text, Snackbar.LENGTH_LONG).show();
    }

    /**
     * Para avisos de dos frases, como el de desvincular (paynani #578): hasta 5 líneas,
     * 8 s y un botón OK, para que se alcance a leer que hay que revisar el teléfono.
     */
    private void longSnack(String text) {
        View v = getView();
        if (v == null) return;
        Snackbar bar = Snackbar.make(v, text, 8000);
        TextView label = bar.getView().findViewById(com.google.android.material.R.id.snackbar_text);
        if (label != null) label.setMaxLines(5);
        bar.setAction(R.string.ok, x -> bar.dismiss()).show();
    }
}
