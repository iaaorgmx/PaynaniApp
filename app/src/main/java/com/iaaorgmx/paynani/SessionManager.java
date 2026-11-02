package com.iaaorgmx.paynani;
//PaynaniApp
import android.content.Context;
import android.content.Intent;

import com.iaaorgmx.paynani.gateway.GatewayClient;
import com.iaaorgmx.paynani.gateway.GatewayPrefs;
import com.iaaorgmx.paynani.gateway.PairActivity;

/**
 * Desde APP-2 no hay cuenta ni contraseña: «sesión» quiere decir que el teléfono
 * está emparejado con una pasarela de paynani y su token sigue vigente.
 */
public class SessionManager {

	private final Context _context;
	private final GatewayPrefs prefs;

	public SessionManager(Context context){
		this._context = context.getApplicationContext();
		this.prefs = new GatewayPrefs(this._context);
	}

	/** Emparejado y sin revocar. */
	public boolean isLoggedIn(){
		return prefs.isPaired() && !prefs.isRevoked();
	}

	/** Si no hay emparejamiento vigente, abre la pantalla de emparejar. */
	public void checkLogin(){
		if(!this.isLoggedIn()){
			Intent i = new Intent(_context, PairActivity.class);
			i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
			_context.startActivity(i);
		}
	}

	/** Desempareja: olvida el token y la cola de esta pasarela. */
	public void logoutUser(){
		GatewayClient.get(_context).unpair();
	}

	public String getDeviceId(){
		return prefs.getDeviceId();
	}
}
