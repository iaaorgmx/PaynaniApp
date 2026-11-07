package com.iaaorgmx.paynani;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * «Development team» (#17): los humanos y agentes de IA que hacen paynani, como «Acerca de › Equipo»
 * en PaynaniWeb y PaynaniWebMT. Los datos son una copia de `scripts/paynani_lib/web/team.json` de
 * paynani y sus avatares, en `assets/team/`. Como en la web (AboutPages::team): sólo los miembros
 * con tipo y rol conocidos y un avatar que existe, en orden alfabético por nombre.
 */
public class TeamActivity extends AppCompatActivity {

    private static final String DIR = "team";
    private static final List<String> ROLES = Arrays.asList("maintainer", "committer", "contributor");
    private static final Pattern AVATAR = Pattern.compile("^[a-z0-9-]{1,39}\\.(png|jpg)$");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_team);
        setSupportActionBar(findViewById(R.id.team_toolbar));
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.about_team);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        LayoutInflater themed = LayoutInflater.from(new ContextThemeWrapper(this, R.style.Theme_Paynani_M3));
        LinearLayout list = findViewById(R.id.team_list);
        for (JSONObject m : members()) {
            list.addView(row(themed, list, m));
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private List<JSONObject> members() {
        List<JSONObject> out = new ArrayList<>();
        try (InputStream in = getAssets().open(DIR + "/team.json")) {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            for (int n; (n = in.read(chunk)) > 0; ) buf.write(chunk, 0, n);
            JSONArray all = new JSONObject(buf.toString(StandardCharsets.UTF_8.name())).optJSONArray("members");
            List<String> files = Arrays.asList(getAssets().list(DIR));
            for (int i = 0; all != null && i < all.length(); i++) {
                JSONObject m = all.optJSONObject(i);
                if (m == null) continue;
                String type = m.optString("type"), avatar = m.optString("avatar");
                if ((type.equals("human") || type.equals("agent")) && ROLES.contains(m.optString("role"))
                        && AVATAR.matcher(avatar).matches() && files.contains(avatar)) {
                    out.add(m);
                }
            }
        } catch (Exception e) {
            L.e("TeamActivity", "team.json: " + e);
        }
        Collections.sort(out, (a, b) -> name(a).toLowerCase(Locale.ROOT).compareTo(name(b).toLowerCase(Locale.ROOT)));
        return out;
    }

    private static String name(JSONObject m) {
        String n = m.optString("name");
        return n.isEmpty() ? m.optString("login") : n;
    }

    private View row(LayoutInflater themed, LinearLayout parent, JSONObject m) {
        View row = themed.inflate(R.layout.item_team_member, parent, false);
        final String name = name(m), login = m.optString("login");
        try (InputStream in = getAssets().open(DIR + "/" + m.optString("avatar"))) {
            ((ImageView) row.findViewById(R.id.team_avatar)).setImageBitmap(BitmapFactory.decodeStream(in));
        } catch (Exception e) {
            L.e("TeamActivity", "avatar " + login + ": " + e);
        }
        ((TextView) row.findViewById(R.id.team_name)).setText(name);
        ((TextView) row.findViewById(R.id.team_login)).setText("@" + login);
        ((TextView) row.findViewById(R.id.team_detail)).setText(detail(m));
        row.setContentDescription(name + ". " + detail(m) + ". " + getString(R.string.about_team_profile, name));
        row.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/" + Uri.encode(login))));
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, R.string.about_no_browser, Toast.LENGTH_SHORT).show();
            }
        });
        return row;
    }

    /** «AI agent · Claude Code · Maintainer (paynani core) · paynani, PaynaniWeb, PaynaniApp». */
    private String detail(JSONObject m) {
        List<String> parts = new ArrayList<>();
        boolean agent = m.optString("type").equals("agent");
        parts.add(getString(agent ? R.string.about_team_type_agent : R.string.about_team_type_human));
        if (agent && !m.optString("harness").isEmpty()) parts.add(m.optString("harness"));
        String role = text("about_team_role_" + m.optString("role"));
        String scope = text("about_team_scope_" + m.optString("role_scope"));
        parts.add(scope.isEmpty() ? role : role + " (" + scope + ")");
        List<String> projects = new ArrayList<>();
        JSONArray p = m.optJSONArray("projects");
        for (int i = 0; p != null && i < p.length(); i++) {
            String t = text("about_team_project_" + p.optString(i));
            if (!t.isEmpty()) projects.add(t);
        }
        if (!projects.isEmpty()) parts.add(android.text.TextUtils.join(", ", projects));
        return android.text.TextUtils.join(" · ", parts);
    }

    /**
     * El texto de un valor de team.json; vacío si no se conoce. Con un mapa y no con
     * getIdentifier(): shrinkResources quitaría los textos que sólo se piden por nombre.
     */
    private String text(String key) {
        Integer id = TEXTS.get(key);
        return id == null ? "" : getString(id);
    }

    private static final java.util.Map<String, Integer> TEXTS = new java.util.HashMap<>();
    static {
        TEXTS.put("about_team_role_maintainer", R.string.about_team_role_maintainer);
        TEXTS.put("about_team_role_committer", R.string.about_team_role_committer);
        TEXTS.put("about_team_role_contributor", R.string.about_team_role_contributor);
        TEXTS.put("about_team_scope_app", R.string.about_team_scope_app);
        TEXTS.put("about_team_scope_core", R.string.about_team_scope_core);
        TEXTS.put("about_team_scope_devops", R.string.about_team_scope_devops);
        TEXTS.put("about_team_scope_lead", R.string.about_team_scope_lead);
        TEXTS.put("about_team_scope_review", R.string.about_team_scope_review);
        TEXTS.put("about_team_scope_web", R.string.about_team_scope_web);
        TEXTS.put("about_team_project_paynani", R.string.about_team_project_paynani);
        TEXTS.put("about_team_project_paynaniweb", R.string.about_team_project_paynaniweb);
        TEXTS.put("about_team_project_paynaniapp", R.string.about_team_project_paynaniapp);
    }
}
