package com.iaaorgmx.paynani;
//PaynaniApp

import android.os.Bundle;
import android.os.Handler;
import android.app.Activity;
import android.content.Intent;
import android.preference.PreferenceManager;
import android.view.Menu;


public class SplashActivity extends Activity {
	
	private static int SPLASH_TIME_OUT = 2500;
	SessionManager session;
	Intent i;
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		// La app ya está abierta (#125): el ícono o la notificación del servicio sólo
		// la traen al frente, sin Splash ni otra Main2Activity encima de la que hay.
		if (!isTaskRoot()) {
			finish();
			return;
		}
		setContentView(R.layout.activity_splash);

		session = new SessionManager(getApplicationContext());


		
		new Handler().postDelayed(new Runnable() {
			 
            /*
             * Showing splash screen with a timer. This will be useful when you
             * want to show case your app logo / company
             */
 
            @Override
            public void run() {
                // This method will be executed once the timer is over
                // Start your app main activity

				if (!PermissionActivity.missing(getApplicationContext()).isEmpty()
						|| PreferenceManager.getDefaultSharedPreferences(getApplicationContext())
						.getBoolean("request_permissions", true)) {
					i = new Intent(getApplicationContext(), PermissionActivity.class);
				} else {
					// Emparejada o no, la pantalla principal (#122): sin emparejar, el menú
					// ofrece «Emparejar» y se puede explorar la app antes.
					i = new Intent(getApplicationContext(), Main2Activity.class);
				}
				i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

                startActivity(i);
 
                // close this activity
                finish();
            }
        }, SPLASH_TIME_OUT);
    }
	

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.splash, menu);
		return true;
	}

}
