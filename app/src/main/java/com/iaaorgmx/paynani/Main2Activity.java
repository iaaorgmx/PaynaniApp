package com.iaaorgmx.paynani;
//PaynaniApp

import android.Manifest;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.navigation.NavigationView;
import com.iaaorgmx.paynani.gateway.PairActivity;
import com.iaaorgmx.paynani.webchat.WebchatFragment;
import com.iaaorgmx.paynani.webchat.WebchatSources;

/**
 * La pantalla principal: el menú lateral y la conversación con el agente
 * (PaynaniApp-Agentic#175: esta app sólo hace Webchat). Sin emparejar, Webchat explica qué es
 * paynani y cómo emparejar (#7). Emparejada pero sin Webchat anunciado por la pasarela, abre Estado.
 */
public class Main2Activity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener {

    /** La notificación de Webchat (paynani PRD #653 FR-11) abre la conversación. */
    public static final String EXTRA_OPEN = "open";
    public static final String OPEN_WEBCHAT = "webchat";

    private NavigationView navigationView;
    private DrawerLayout drawer;
    private FragmentManager fragments;
    private SessionManager session;

    // Android 13 o más: el aviso de que el agente contestó necesita el permiso de notificaciones.
    private final ActivityResultLauncher<String> notifications =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {});

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main_new);
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(this));
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        setTitle(getString(R.string.app_name));

        session = new SessionManager(getApplicationContext());
        boolean paired = session.isLoggedIn();

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        drawer = findViewById(R.id.drawer_layout);
        navigationView = findViewById(R.id.nav_view);
        fragments = getSupportFragmentManager();

        navigationView.setNavigationItemSelectedListener(this);
        View header = navigationView.getHeaderView(0);
        ((TextView) header.findViewById(R.id.tx_fullname_client)).setText(R.string.app_name);
        ((TextView) header.findViewById(R.id.tx_email_client)).setText(paired
                ? getString(R.string.nav_device, session.getDeviceId()) : getString(R.string.nav_unpaired));
        // Sin emparejar, «Emparejar de nuevo» es «Emparejar».
        if (!paired) navigationView.getMenu().findItem(R.id.nav_logout).setTitle(R.string.opt_pair);
        updateMenu();

        if (savedInstanceState == null) {
            if (!paired) show(new WelcomeFragment(), R.id.nav_webchat);
            else if (WebchatSources.menuVisible(this)) show(new WebchatFragment(), R.id.nav_webchat);
            else show(new StatusFragment(), R.id.nav_status);
        }

        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(this, drawer, toolbar, R.string.app_name, R.string.app_name) {
            @Override
            public void onDrawerOpened(View drawerView) {
                super.onDrawerOpened(drawerView);
                // La pasarela pudo anunciar Webchat después de abrir la app.
                updateMenu();
            }
        };
        drawer.addDrawerListener(toggle);
        toggle.syncState();

        if (paired && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
        openFromIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        openFromIntent(intent);
    }

    private void openFromIntent(Intent intent) {
        if (intent == null || !OPEN_WEBCHAT.equals(intent.getStringExtra(EXTRA_OPEN))) return;
        intent.removeExtra(EXTRA_OPEN);
        show(new WebchatFragment(), R.id.nav_webchat);
    }

    /**
     * Webchat si la pasarela lo anuncia en el welcome (SMS_GATEWAY.md §13), o sin emparejar, donde
     * explica cómo hacerlo (#7).
     */
    private void updateMenu() {
        navigationView.getMenu().findItem(R.id.nav_section_agent)
                .setVisible(!session.isLoggedIn() || WebchatSources.menuVisible(this));
    }

    private void show(Fragment fragment, int item) {
        fragments.beginTransaction().replace(R.id.containerView, fragment).commit();
        navigationView.setCheckedItem(item);
    }

    @Override
    public boolean onNavigationItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.nav_webchat) {
            show(session.isLoggedIn() ? new WebchatFragment() : new WelcomeFragment(), id);
        } else if (id == R.id.nav_status) {
            show(new StatusFragment(), id);
        } else if (id == R.id.nav_home) {
            show(new AboutFragment(), id);
        } else if (id == R.id.nav_logout) {
            // Sin emparejar no hay nada que borrar: abre la pantalla de emparejar.
            if (session.isLoggedIn()) PairAgain.confirm(this);
            else startActivity(new Intent(this, PairActivity.class));
        }
        drawer.closeDrawer(GravityCompat.START);
        return true;
    }
}
