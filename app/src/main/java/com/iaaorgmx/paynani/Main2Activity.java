package com.iaaorgmx.paynani;
//PaynaniApp
import com.iaaorgmx.paynani.roster.RosterFragment;
import com.iaaorgmx.paynani.roster.RosterSources;
import android.*;
import android.Manifest;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import android.util.Log;
import android.view.KeyEvent;
import android.view.View;

import android.view.Menu;
import android.view.MenuItem;
import android.webkit.WebView;
import android.widget.TextView;
import android.widget.Toast;


import com.google.android.material.navigation.NavigationView;
import com.iaaorgmx.paynani.messages.MessagesFragment;
import com.iaaorgmx.paynani.telegram.TelegramMessagesFragment;
import com.iaaorgmx.paynani.telegram.TelegramSources;
import com.iaaorgmx.paynani.webchat.WebchatFragment;
import com.iaaorgmx.paynani.webchat.WebchatSources;
import com.iaaorgmx.paynani.whatsapp.WhatsAppMessagesFragment;
import com.iaaorgmx.paynani.whatsapp.WhatsAppSources;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Calendar;


import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

public class Main2Activity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener {

    NavigationView navigationView;
    DrawerLayout drawer;
    FragmentManager mFragmentManager;
    FragmentTransaction mFragmentTransaction;
    boolean ishome = true;

    TextView tx_fullname_client,tx_email_client;
    AppPrefs prefs;
    SessionManager session;
    String googlePushID;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main_new);

        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(this));

        prefs = new AppPrefs(this);
        session = new SessionManager(getApplicationContext());

        if(prefs.isFirstLaunch()){
            prefs.saveSwitchSetting("switch_listener",true);
            prefs.saveSwitchSetting("switch_service",true);
            prefs.saveTimeServiceValue("1");
            prefs.saveFirstLaunchTime(String.valueOf(System.currentTimeMillis()));
            prefs.saveFirstLaunch("no");
        }

        L.e("Fechainstalacion","-> " + prefs.getFirstLaunchTime());

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        setTitle(getString(R.string.app_name));

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // Sin emparejamiento vigente (nunca emparejado, desemparejado o revocado) la
        // app abre igual (#122): el menú ofrece «Emparejar» y no arranca SMSService.
        boolean paired = session.isLoggedIn();

        if (!PermissionActivity.missing(this).isEmpty()) {
            // Sin los permisos la pasarela no recibe ni envía. Se avisa; no se desempareja.
            new AlertDialog.Builder(this)
                    .setTitle(R.string.generic_title)
                    .setMessage(R.string.permissions_missing)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            startActivity(new Intent(Main2Activity.this, PermissionActivity.class));
                        }
                    })
                    .setNegativeButton(R.string.btn_cancel, null)
                    .show();
        }

        if (paired) startForegroundService(new Intent(this, SMSService.class));

        // Si a la app la cerraron a la fuerza, el permiso de segundo plano del
        // fabricante puede no estar o haberse perdido: se vuelve a pedir (#86).
        if (BackgroundScreens.forceStoppedSinceLastCheck(this)) {
            BackgroundScreens.Screen background = BackgroundScreens.find(this);
            if (background != null) {
                BackgroundScreens.setConfirmed(this, false);
                BackgroundScreens.showGuide(this, background, null);
            }
        }

        drawer = (DrawerLayout) findViewById(R.id.drawer_layout);
        navigationView = (NavigationView) findViewById(R.id.nav_view);

        mFragmentManager = getSupportFragmentManager();
        mFragmentTransaction = mFragmentManager.beginTransaction();
        mFragmentTransaction.replace(R.id.containerView, new StatusFragment()).commit();

        navigationView.setNavigationItemSelectedListener(this);
        navigationView.setCheckedItem(R.id.nav_status); // #142: la pantalla de inicio, marcada en el menú
        View header = navigationView.getHeaderView(0);

        tx_fullname_client = (TextView)header.findViewById(R.id.tx_fullname_client);
        tx_email_client = (TextView)header.findViewById(R.id.tx_email_client);

        tx_fullname_client.setText(R.string.app_name);
        tx_email_client.setText(paired ? getString(R.string.nav_device, session.getDeviceId())
                : getString(R.string.nav_unpaired));
        // Sin emparejar, «Emparejar de nuevo» es «Emparejar» (#122).
        if (!paired) navigationView.getMenu().findItem(R.id.nav_logout).setTitle(R.string.opt_pair);
        updateMenu();

        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(this, drawer, toolbar, R.string.app_name, R.string.app_name) {
            @Override
            public void onDrawerOpened(View drawerView) {
                super.onDrawerOpened(drawerView);
                // La pasarela pudo anunciar roster.list (paynani #417) o WhatsApp (PRD #550) después
                // de abrir la app.
                updateMenu();
            }
        };
        drawer.setDrawerListener(toggle);
        toggle.syncState();
        openFromIntent(getIntent());
    }

    /** La notificación de Webchat (paynani PRD #653 FR-11) abre la conversación. */
    public static final String EXTRA_OPEN = "open";
    public static final String OPEN_WEBCHAT = "webchat";

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        openFromIntent(intent);
    }

    private void openFromIntent(Intent intent) {
        if (intent == null || !OPEN_WEBCHAT.equals(intent.getStringExtra(EXTRA_OPEN))) return;
        intent.removeExtra(EXTRA_OPEN);
        // No es volver con Inicio o Recientes: no se reemplaza por «Mensajes» (#125).
        leftByUser = false;
        openMessagesOnResume = false;
        ishome = false;
        mFragmentManager.beginTransaction().replace(R.id.containerView, new WebchatFragment()).commit();
        navigationView.setCheckedItem(R.id.nav_webchat);
    }

    /** Las opciones que dependen de lo que anuncia la pasarela en el welcome. */
    private void updateMenu() {
        navigationView.getMenu().findItem(R.id.nav_roster).setVisible(RosterSources.menuVisible(this));
        navigationView.getMenu().findItem(R.id.nav_whatsapp_messages).setVisible(WhatsAppSources.messagesVisible(this));
        navigationView.getMenu().findItem(R.id.nav_telegram_messages).setVisible(TelegramSources.messagesVisible(this));
        navigationView.getMenu().findItem(R.id.nav_section_agent).setVisible(WebchatSources.menuVisible(this));
    }

    /** El usuario salió de la app con Inicio o Recientes (#125); al volver se abre «Mensajes». */
    private boolean leftByUser;
    private boolean openMessagesOnResume;

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        leftByUser = true;
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        // Volver de una pantalla propia (un hilo, permisos) no cuenta: ésas no llaman
        // onUserLeaveHint en esta actividad.
        openMessagesOnResume = leftByUser;
        leftByUser = false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (openMessagesOnResume) {
            openMessagesOnResume = false;
            if (!(mFragmentManager.findFragmentById(R.id.containerView) instanceof MessagesFragment)) {
                ishome = false;
                mFragmentManager.beginTransaction().replace(R.id.containerView, new MessagesFragment()).commit();
            }
            navigationView.setCheckedItem(R.id.nav_messages);
        }
    }

    @Override
    public boolean onNavigationItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.nav_home) {
            ishome = true;
            FragmentTransaction fragmentTransaction = mFragmentManager.beginTransaction();
            fragmentTransaction.replace(R.id.containerView,new AboutFragment()).commit();
        }else if(id == R.id.nav_status){
            ishome = false;
            FragmentTransaction fragmentTransaction = mFragmentManager.beginTransaction();
            fragmentTransaction.replace(R.id.containerView,new StatusFragment()).commit();
        }else if(id == R.id.nav_logout){
            // «Emparejar de nuevo» (#61): el mismo diálogo que el botón de Estado.
            // Sin emparejar no hay nada que borrar: abre la pantalla de emparejar (#122).
            if (session.isLoggedIn()) PairAgain.confirm(this);
            else startActivity(new Intent(this, com.iaaorgmx.paynani.gateway.PairActivity.class));
        }else if(id == R.id.nav_messages){
            ishome = false;
            FragmentTransaction fragmentTransaction = mFragmentManager.beginTransaction();
            fragmentTransaction.replace(R.id.containerView,new MessagesFragment()).commit();
        }else if(id == R.id.nav_settings){
            ishome = false;
            FragmentTransaction fragmentTransaction = mFragmentManager.beginTransaction();
            fragmentTransaction.replace(R.id.containerView,new SettingsFragment()).commit();
        }else if(id == R.id.nav_roster){
            ishome = false;
            FragmentTransaction fragmentTransaction = mFragmentManager.beginTransaction();
            fragmentTransaction.replace(R.id.containerView,new RosterFragment()).commit();
        }else if(id == R.id.nav_whatsapp_messages){
            ishome = false;
            FragmentTransaction fragmentTransaction = mFragmentManager.beginTransaction();
            fragmentTransaction.replace(R.id.containerView,new WhatsAppMessagesFragment()).commit();
        }else if(id == R.id.nav_webchat){
            ishome = false;
            FragmentTransaction fragmentTransaction = mFragmentManager.beginTransaction();
            fragmentTransaction.replace(R.id.containerView,new WebchatFragment()).commit();
        }else if(id == R.id.nav_telegram_messages){
            ishome = false;
            FragmentTransaction fragmentTransaction = mFragmentManager.beginTransaction();
            fragmentTransaction.replace(R.id.containerView,new TelegramMessagesFragment()).commit();
        }else if(id == R.id.nav_log){
            ishome = false;
            FragmentTransaction fragmentTransaction = mFragmentManager.beginTransaction();
            fragmentTransaction.replace(R.id.containerView,new EventLogFragment()).commit();
        }

        DrawerLayout drawer = (DrawerLayout) findViewById(R.id.drawer_layout);
        drawer.closeDrawer(GravityCompat.START);
        return true;
    }
}