package com.iaaorgmx.paynani;
//PaynaniApp

import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.CompoundButton;

import androidx.fragment.app.Fragment;

import android.telephony.SubscriptionInfo;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.iaaorgmx.paynani.gateway.GatewayClient;
import com.iaaorgmx.paynani.gateway.GatewayPrefs;
import com.iaaorgmx.paynani.gateway.Phone;
import com.iaaorgmx.paynani.gateway.Sims;

import java.util.ArrayList;
import java.util.List;


/**
 * Las opciones de «Channels»: el receptor de SMS y el barrido de la bandeja. Desde #142 son una
 * sección de «Settings» ({@link #embeddedInSettings()}), no una opción del menú, y desde #144
 * son tarjetas de Material 3 como la de la pausa del agente ({@code fragment_channels}).
 *
 * Desde APP-1b (#16) sólo queda el canal SMS, y desde APP-2 (#17) el canal se
 * activa al emparejar con paynani: ya no hay interruptor ni alta de canal en
 * nicebot.co.
 */
public class GatewaysFragment extends Fragment {

    /** Las opciones de Channels como sección de «Settings» (#142), sin su propio desplazamiento. */
    public static GatewaysFragment embeddedInSettings() {
        return new GatewaysFragment();
    }

    AppPrefs prefs;
    MaterialAutoCompleteTextView sp_time_service;

    String[] time_service_values;
    String[] time_service_labels;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Con el tema de «Settings» (Theme.Paynani.M3), como SettingsFragment: la actividad usa el viejo.
        Context themed = new ContextThemeWrapper(requireContext(), R.style.Theme_Paynani_M3);
        View v = inflater.cloneInContext(themed).inflate(R.layout.fragment_channels, container, false);
        prefs = new AppPrefs(getActivity().getApplicationContext());

        time_service_values = getResources().getStringArray(R.array.time_service_values);
        time_service_labels = getResources().getStringArray(R.array.time_service);

        sp_time_service = v.findViewById(R.id.sp_time_service);
        sp_time_service.setSimpleItems(time_service_labels);
        showSavedInterval();
        sp_time_service.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, final int position, long id) {
                if (position == Integer.parseInt(prefs.getTimeServicePos())) return;

                new AlertDialog.Builder(getActivity())
                        .setCancelable(false)
                        .setTitle(R.string.generic_title)
                        .setMessage(R.string.restar_service)
                        .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {

                                prefs.saveTimeServiceValue(time_service_values[position]);
                                prefs.saveTimeServicePos(String.valueOf(position));

                                int flags = PendingIntent.FLAG_CANCEL_CURRENT;
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                                    flags |= PendingIntent.FLAG_IMMUTABLE;
                                }

                                Intent mStartActivity = new Intent(getActivity(), SplashActivity.class);
                                int mPendingIntentId = 123456;
                                PendingIntent mPendingIntent = PendingIntent.getActivity(getActivity(), mPendingIntentId, mStartActivity, flags);
                                AlarmManager mgr = (AlarmManager) getActivity().getSystemService(Context.ALARM_SERVICE);
                                mgr.set(AlarmManager.RTC, System.currentTimeMillis() + 5, mPendingIntent);
                                System.exit(0);
                            }
                        })
                        .setNegativeButton(R.string.btn_cancel, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                showSavedInterval();
                            }
                        })
                        .show();
            }
        });

        bindSwitch((CompoundButton) v.findViewById(R.id.swich_listener), "switch_listener");
        bindSwitch((CompoundButton) v.findViewById(R.id.swich_service), "switch_service");

        addSimSection(v);
        return v;
    }

    /** El intervalo guardado en el selector, sin abrir el aviso de reinicio. */
    private void showSavedInterval() {
        int pos = Integer.parseInt(prefs.getTimeServicePos());
        sp_time_service.setText(time_service_labels[pos], false);
    }

    private void bindSwitch(CompoundButton sw, final String key) {
        sw.setChecked(prefs.getSwitchSetting(key, true));
        sw.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.saveSwitchSetting(key, ((CompoundButton) v).isChecked());
            }
        });
    }

    /** APP-10: elegir la SIM de la pasarela y, si Android no lo sabe, escribir su número. */
    private void addSimSection(View v) {
        final Context ctx = getActivity();
        final GatewayPrefs prefs = new GatewayPrefs(ctx);
        final List<SubscriptionInfo> sims = Sims.active(ctx);

        TextView help = v.findViewById(R.id.channels_sim_help);
        help.setText(sims.isEmpty() ? R.string.sim_none : R.string.sim_help);
        if (sims.isEmpty()) {
            v.findViewById(R.id.channels_sim_layout).setVisibility(View.GONE);
            return;
        }

        List<String> labels = new ArrayList<>();
        final List<Integer> slots = new ArrayList<>();
        labels.add(getString(R.string.sim_all));
        slots.add(-1);
        for (SubscriptionInfo s : sims) {
            String number = Sims.number(ctx, s);
            labels.add(getString(R.string.sim_item, s.getSimSlotIndex() + 1,
                    String.valueOf(s.getCarrierName()),
                    number != null ? number : getString(R.string.sim_unknown_number)));
            slots.add(s.getSimSlotIndex());
        }
        final MaterialAutoCompleteTextView sim = v.findViewById(R.id.channels_sim);
        sim.setSimpleItems(labels.toArray(new String[0]));
        final int[] selected = {Math.max(0, slots.indexOf(prefs.getSimSlot()))};
        sim.setText(labels.get(selected[0]), false);

        final View numberHelp = v.findViewById(R.id.channels_number_help);
        final View numberLayout = v.findViewById(R.id.channels_number_layout);
        final TextInputEditText number = v.findViewById(R.id.channels_number);
        final View save = v.findViewById(R.id.channels_number_save);
        number.setText(prefs.getSimNumber());

        final Runnable showNumber = new Runnable() {
            @Override public void run() {
                // El número a mano sólo hace falta si la SIM que atiende la pasarela no lo trae.
                int slot = slots.get(selected[0]);
                SubscriptionInfo target = null;
                for (SubscriptionInfo s : sims) {
                    if (s.getSimSlotIndex() == slot || (slot < 0 && sims.size() == 1)) target = s;
                }
                boolean need = target != null && Sims.androidNumber(ctx, target) == null;
                int vis = need ? View.VISIBLE : View.GONE;
                numberHelp.setVisibility(vis);
                numberLayout.setVisibility(vis);
                save.setVisibility(vis);
            }
        };
        showNumber.run();

        sim.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View view, int position, long id) {
                selected[0] = position;
                int slot = slots.get(position);
                if (slot != prefs.getSimSlot()) {
                    prefs.setSimSlot(slot);
                    GatewayClient.get(ctx).reconnect();
                    Toast.makeText(ctx, R.string.sim_saved, Toast.LENGTH_SHORT).show();
                }
                showNumber.run();
            }
        });

        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View b) {
                String text = number.getText().toString().trim();
                String e164 = text.isEmpty() ? "" : Phone.toE164(text, Sims.region(ctx, sims.get(0)));
                if (e164 == null) {
                    Toast.makeText(ctx, R.string.sim_number_bad, Toast.LENGTH_LONG).show();
                    return;
                }
                prefs.setSimNumber(e164);
                number.setText(e164);
                GatewayClient.get(ctx).reconnect();
                Toast.makeText(ctx, R.string.sim_saved, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
