package com.iaaorgmx.paynani;

import android.app.Activity;
import android.app.AlertDialog;

/**
 * Emparejar de nuevo: borra el emparejamiento actual y abre la pantalla de emparejar,
 * para cambiar de pasarela sin reinstalar. Lo usan el menú lateral y el botón de Estado.
 */
public final class PairAgain {

    private PairAgain() {}

    public static void confirm(Activity activity) {
        new AlertDialog.Builder(activity)
                .setCancelable(false)
                .setTitle(R.string.generic_title)
                .setMessage(R.string.logout_confirmation_msg)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    SessionManager session = new SessionManager(activity.getApplicationContext());
                    session.logoutUser();
                    session.checkLogin();
                    activity.finish();
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }
}
