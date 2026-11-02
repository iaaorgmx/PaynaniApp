package com.iaaorgmx.paynani;
//PaynaniApp

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;

import com.iaaorgmx.paynani.gateway.PairActivity;

public class SplashActivity extends Activity {

	private static final int SPLASH_TIME_OUT = 1500;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		// La app ya está abierta: el ícono o la notificación sólo la traen al frente, sin
		// Splash ni otra Main2Activity encima de la que hay.
		if (!isTaskRoot()) {
			finish();
			return;
		}
		setContentView(R.layout.activity_splash);
		new Handler().postDelayed(() -> {
			// Sin emparejamiento vigente, a emparejar; si no, a la conversación con el agente.
			Class<?> next = new SessionManager(getApplicationContext()).isLoggedIn()
					? Main2Activity.class : PairActivity.class;
			startActivity(new Intent(getApplicationContext(), next).setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
			finish();
		}, SPLASH_TIME_OUT);
	}
}
