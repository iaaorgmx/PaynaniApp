package com.iaaorgmx.paynani;
//PaynaniApp
import android.app.Application;

/**
 * Application de la app. Hasta APP-1b (#16) guardaba en memoria las acciones de
 * «responder» de las notificaciones de otras apps; con esos canales retirados
 * ya no guarda nada, pero se conserva porque el manifiesto la declara.
 */
public class GlobalInstance extends Application {
}
