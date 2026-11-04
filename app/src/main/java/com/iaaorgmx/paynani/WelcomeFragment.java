package com.iaaorgmx.paynani;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.fragment.app.Fragment;

import com.iaaorgmx.paynani.gateway.PairActivity;

/**
 * Webchat sin emparejar (#7): en lugar de la conversación, qué es paynani, la guía para instalarlo,
 * el prompt de ejemplo para pedirle el QR al agente y «Emparejar».
 */
public class WelcomeFragment extends Fragment {

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_welcome, container, false);
        PairPrompt.bind(v);
        v.findViewById(R.id.welcome_guide).setOnClickListener(b -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.welcome_guide_url))));
            } catch (ActivityNotFoundException e) {
                android.widget.Toast.makeText(requireContext(), R.string.about_no_browser,
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        v.findViewById(R.id.welcome_pair).setOnClickListener(b ->
                startActivity(new Intent(requireContext(), PairActivity.class)));
        return v;
    }
}
