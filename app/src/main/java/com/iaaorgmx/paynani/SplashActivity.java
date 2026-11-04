package com.iaaorgmx.paynani;
//PaynaniApp

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;

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
			// Emparejada o no, la pantalla principal (#7): sin emparejar, Webchat explica qué es
			// paynani y cómo emparejar, y el menú se puede explorar.
			startActivity(new Intent(getApplicationContext(), Main2Activity.class)
					.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
			finish();
		}, SPLASH_TIME_OUT);
	}
}
