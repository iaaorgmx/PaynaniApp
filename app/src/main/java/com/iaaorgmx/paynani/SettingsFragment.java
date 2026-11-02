package com.iaaorgmx.paynani;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.iaaorgmx.paynani.gateway.HumanSms;
import com.iaaorgmx.paynani.whatsapp.WhatsAppEvents;
import com.iaaorgmx.paynani.whatsapp.WhatsAppFragment;
import com.iaaorgmx.paynani.whatsapp.WhatsAppSources;

/**
 * Ajustes (PaynaniApp#111, APP-C4): cuántos minutos el agente no le escribe a un
 * número después de que el dueño le contesta desde «Mensajes». Se guarda en cuanto
 * el valor es válido (0 a 1440); va en cada sms.human como agent_pause_min. Desde #142,
 * debajo van las opciones de Channels ({@link GatewaysFragment}), y desde #171 la tarjeta de
 * WhatsApp ({@link WhatsAppFragment}) si la pasarela anuncia whatsapp.status.
 */
public class SettingsFragment extends Fragment implements WhatsAppEvents.Listener {

    /** Lo que hay que hacer con la tarjeta de WhatsApp (#171). */
    static final int WA_NONE = 0;
    static final int WA_ADD = 1;
    static final int WA_REMOVE = 2;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        LayoutInflater themed = inflater.cloneInContext(new ContextThemeWrapper(requireContext(), R.style.Theme_Paynani_M3));
        View v = themed.inflate(R.layout.fragment_settings, container, false);
        AppPrefs prefs = new AppPrefs(requireContext());
        TextInputLayout layout = v.findViewById(R.id.settings_pause_layout);
        TextInputEditText field = v.findViewById(R.id.settings_pause);
        field.setText(String.valueOf(prefs.getAgentPauseMin()));
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                Integer minutes = parse(s.toString());
                if (minutes == null) {
                    layout.setError(getString(R.string.settings_pause_error, HumanSms.PAUSE_MAX_MIN));
                    return;
                }
                layout.setError(null);
                prefs.setAgentPauseMin(minutes);
            }
        });
        return v;
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // Channels (#142): al rotar, el fragmento hijo ya está restaurado.
        if (getChildFragmentManager().findFragmentById(R.id.settings_channels) == null) {
            getChildFragmentManager().beginTransaction()
                    .replace(R.id.settings_channels, GatewaysFragment.embeddedInSettings())
                    .commit();
        }
        syncWhatsApp();
    }

    @Override
    public void onResume() {
        super.onResume();
        // Un welcome nuevo cambia lo que anuncia la pasarela: se vuelve a decidir con la pantalla
        // abierta (Ximena en #172), y también al volver a ella.
        WhatsAppEvents.add(this);
        syncWhatsApp();
    }

    @Override
    public void onPause() {
        super.onPause();
        WhatsAppEvents.remove(this);
    }

    @Override
    public void onChanged(String what) {
        if (WhatsAppEvents.CONNECTION.equals(what) && isResumed()) syncWhatsApp();
    }

    /**
     * La tarjeta de WhatsApp (#171), la que antes era la opción «WhatsApp» del menú: está si la
     * pasarela anuncia whatsapp.status (FR-8). Se puede llamar las veces que sea; tras rotar se
     * reutiliza el fragmento hijo restaurado.
     */
    private void syncWhatsApp() {
        if (!isAdded() || getChildFragmentManager().isStateSaved()) return;
        Fragment wa = getChildFragmentManager().findFragmentById(R.id.settings_whatsapp);
        switch (whatsAppAction(WhatsAppSources.sectionVisible(requireContext()), wa != null)) {
            case WA_ADD:
                getChildFragmentManager().beginTransaction()
                        .replace(R.id.settings_whatsapp, new WhatsAppFragment())
                        .commit();
                break;
            case WA_REMOVE:
                getChildFragmentManager().beginTransaction().remove(wa).commit();
                break;
            default:
                break;
        }
    }

    /** Agregar, quitar o dejar la tarjeta, según si la pasarela la anuncia y si ya está puesta. */
    static int whatsAppAction(boolean visible, boolean mounted) {
        if (visible && !mounted) return WA_ADD;
        if (!visible && mounted) return WA_REMOVE;
        return WA_NONE;
    }

    /** Los minutos si son un entero de 0 a 1440, o null. */
    static Integer parse(String text) {
        try {
            int n = Integer.parseInt(text.trim());
            return n >= 0 && n <= HumanSms.PAUSE_MAX_MIN ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
