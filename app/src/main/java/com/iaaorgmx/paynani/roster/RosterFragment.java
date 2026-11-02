package com.iaaorgmx.paynani.roster;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.iaaorgmx.paynani.R;

import java.util.ArrayList;
import java.util.List;

/**
 * «Roster» (paynani #409, R-1 a R-5): el roster de cada cuenta de la instalación, con alta,
 * edición y baja. Todo pasa por la pasarela (SMS_GATEWAY.md §11) y nada se muestra como guardado
 * hasta que ella confirma. Sin conexión se ve la última lista y no se edita.
 */
public class RosterFragment extends Fragment {

    private RosterSource source;
    private LayoutInflater themed;

    private MaterialAutoCompleteTextView accountView;
    private TextView offlineView;
    private TextView emptyView;
    private MaterialButton retryView;
    private CircularProgressIndicator progressView;
    private ExtendedFloatingActionButton addView;
    private View defaultRulesView;
    private final MemberAdapter adapter = new MemberAdapter();

    private List<RosterSource.Account> accounts = new ArrayList<>();
    private RosterSource.Account account;
    private RosterSource.Roster roster;
    /** Cambia con cada carga: una respuesta de una carga anterior se ignora. */
    private int generation;

    /** El editor de un contacto (#133): al guardar o borrar, regresa con el texto que se avisa aquí. */
    private final ActivityResultLauncher<Intent> editor = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), r -> {
                if (r.getResultCode() == android.app.Activity.RESULT_OK && r.getData() != null) {
                    String message = r.getData().getStringExtra(ContactEditorActivity.EXTRA_MESSAGE);
                    if (message != null) snack(message);
                }
            });

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        source = RosterSources.get(requireContext());
        themed = inflater.cloneInContext(new ContextThemeWrapper(requireContext(), R.style.Theme_Paynani_M3));
        View v = themed.inflate(R.layout.fragment_roster, container, false);
        accountView = v.findViewById(R.id.roster_account);
        offlineView = v.findViewById(R.id.roster_offline);
        emptyView = v.findViewById(R.id.roster_empty);
        retryView = v.findViewById(R.id.roster_retry);
        progressView = v.findViewById(R.id.roster_progress);
        addView = v.findViewById(R.id.roster_add);
        defaultRulesView = v.findViewById(R.id.roster_default_rules);
        defaultRulesView.setOnClickListener(x -> startActivity(RulesEditorActivity.forDefault(requireContext())));
        RecyclerView list = v.findViewById(R.id.roster_list);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(adapter);
        retryView.setOnClickListener(x -> loadAccounts());
        addView.setOnClickListener(x -> edit(null));
        accountView.setOnItemClickListener((parent, view, position, id) -> {
            account = accounts.get(position);
            loadRoster();
        });
        return v;
    }

    @Override
    public void onResume() {
        super.onResume();
        loadAccounts();
    }

    // --- Lectura ------------------------------------------------------------------------------

    private void loadAccounts() {
        int gen = ++generation;
        showLoading();
        source.accounts(new RosterSource.Callback<List<RosterSource.Account>>() {
            @Override
            public void onResult(List<RosterSource.Account> result) {
                if (gen != generation || !isAdded()) return;
                accounts = result;
                List<String> labels = new ArrayList<>();
                RosterSource.Account keep = null;
                for (RosterSource.Account a : result) {
                    labels.add(label(a));
                    if (account != null && TextUtils.equals(a.id, account.id)) keep = a;
                }
                account = keep != null ? keep : (result.isEmpty() ? null : result.get(0));
                accountView.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, labels));
                if (account != null) accountView.setText(label(account), false);
                loadRoster();
            }

            @Override
            public void onError(RosterSource.Failure f) {
                if (gen != generation || !isAdded()) return;
                showLoadError(f);
            }
        });
    }

    private void loadRoster() {
        loadRoster(null);
    }

    /** after corre con la lista nueva ya en pantalla. */
    private void loadRoster(Runnable after) {
        if (account == null) return;
        int gen = ++generation;
        showLoading();
        source.list(account.id, new RosterSource.Callback<RosterSource.Roster>() {
            @Override
            public void onResult(RosterSource.Roster result) {
                if (gen != generation || !isAdded()) return;
                show(result);
                if (after != null) after.run();
            }

            @Override
            public void onError(RosterSource.Failure f) {
                if (gen != generation || !isAdded()) return;
                showLoadError(f);
            }
        });
    }

    private String label(RosterSource.Account a) {
        String s = a.primary() ? getString(R.string.roster_account_primary, a.email) : a.email;
        return a.editable() ? s : getString(R.string.roster_account_unreadable, s);
    }

    private boolean canEdit() {
        return source.available() && account != null && account.editable() && roster != null;
    }

    private void showLoading() {
        progressView.setVisibility(View.VISIBLE);
        emptyView.setVisibility(View.GONE);
        retryView.setVisibility(View.GONE);
        updateEditable();
    }

    private void show(RosterSource.Roster r) {
        roster = r;
        progressView.setVisibility(View.GONE);
        retryView.setVisibility(View.GONE);
        emptyView.setText(R.string.roster_empty);
        emptyView.setVisibility(r.members.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.set(r.members);
        updateEditable();
    }

    private void showLoadError(RosterSource.Failure f) {
        progressView.setVisibility(View.GONE);
        // Sin pasarela, el aviso de arriba ya lo explica: abajo, sin el código (paynani #418).
        emptyView.setText("offline".equals(f.code) || "timeout".equals(f.code)
                ? getString(R.string.roster_error_load_offline) : getString(R.string.roster_error_load, f.code));
        emptyView.setVisibility(View.VISIBLE);
        retryView.setVisibility(View.VISIBLE);
        updateEditable();
    }

    private void updateEditable() {
        offlineView.setVisibility(source.available() ? View.GONE : View.VISIBLE);
        defaultRulesView.setVisibility(source.available() && source.rulesAvailable() ? View.VISIBLE : View.GONE);
        if (canEdit()) addView.show();
        else addView.hide();
    }

    // --- Alta, edición y baja (R-3 a R-5) -----------------------------------------------------

    /** La pantalla de alta (current null) o edición (#133); al volver, onResume lee la lista otra vez. */
    private void edit(RosterMember current) {
        if (!canEdit()) {
            updateEditable();
            return;
        }
        editor.launch(current == null ? ContactEditorActivity.forNew(requireContext(), account.id)
                : ContactEditorActivity.forMember(requireContext(), account.id, current));
    }

    private void snack(String text) {
        View v = getView();
        if (v != null) Snackbar.make(v, text, Snackbar.LENGTH_LONG).show();
    }

    // --- Lista ----------------------------------------------------------------------------------

    private final class MemberAdapter extends RecyclerView.Adapter<MemberAdapter.Holder> {
        private List<RosterMember> items = new ArrayList<>();

        void set(List<RosterMember> members) {
            items = new ArrayList<>(members);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(themed.inflate(R.layout.roster_member, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            RosterMember m = items.get(position);
            h.name.setText(m.name);
            h.owner.setVisibility(m.owner ? View.VISIBLE : View.GONE);
            h.type.setText(RosterMember.AI_AGENT.equals(m.type) ? R.string.roster_type_agent : R.string.roster_type_human);
            List<String> channels = new ArrayList<>();
            if (!m.email.isEmpty()) channels.add(m.email);
            channels.addAll(m.phones);
            // Telegram también es un canal (paynani #628): el @usuario, o «Telegram» si la celda es un id,
            // que la app no ve.
            if (!m.telegramUsername.isEmpty()) channels.add(getString(R.string.roster_card_telegram, m.telegramUsername));
            else if (m.hasTelegram) channels.add(getString(R.string.roster_card_telegram_id));
            h.channels.setText(channels.isEmpty() ? getString(R.string.roster_no_channel) : TextUtils.join(" · ", channels));
            h.itemView.setOnClickListener(x -> edit(m));
            boolean rules = source.available() && source.rulesAvailable();
            h.rulesRow.setVisibility(rules ? View.VISIBLE : View.GONE);
            if (rules) {
                h.rules.setText(!m.hasRules ? R.string.rules_member_none
                        : m.rulesEnabled ? R.string.rules_member_on : R.string.rules_member_off);
                h.rulesOpen.setOnClickListener(x -> startActivity(
                        RulesEditorActivity.forMember(requireContext(), account.id, m, roster.etag)));
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final TextView name;
            final TextView owner;
            final TextView type;
            final TextView channels;
            final View rulesRow;
            final TextView rules;
            final View rulesOpen;

            Holder(View v) {
                super(v);
                rulesRow = v.findViewById(R.id.roster_member_rules_row);
                rules = v.findViewById(R.id.roster_member_rules);
                rulesOpen = v.findViewById(R.id.roster_member_rules_open);
                name = v.findViewById(R.id.roster_member_name);
                owner = v.findViewById(R.id.roster_member_owner);
                type = v.findViewById(R.id.roster_member_type);
                channels = v.findViewById(R.id.roster_member_channels);
            }
        }
    }
}
