package com.iaaorgmx.paynani;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.View;
import android.widget.Toast;

/**
 * El prompt de ejemplo para pedirle el QR al agente (#7): «Copiar» lo pone en el portapapeles
 * para pegarlo en el chat con el agente. Lo usan Webchat sin emparejar y la pantalla de emparejar.
 */
public final class PairPrompt {

    private PairPrompt() {}

    /** `root` contiene el layout pair_prompt. */
    public static void bind(View root) {
        root.findViewById(R.id.pair_prompt_copy).setOnClickListener(v -> copy(v.getContext()));
    }

    static void copy(Context context) {
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.pair_prompt_copy),
                context.getString(R.string.pair_prompt)));
        // Desde Android 13 el sistema ya avisa que se copió; antes, el aviso es de la app.
        if (android.os.Build.VERSION.SDK_INT < 33) {
            Toast.makeText(context, R.string.pair_prompt_copied, Toast.LENGTH_SHORT).show();
        }
    }
}
