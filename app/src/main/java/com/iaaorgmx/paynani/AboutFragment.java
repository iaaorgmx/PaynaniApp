package com.iaaorgmx.paynani;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;

import com.google.android.material.chip.Chip;

/**
 * «About» (#146): el logotipo y la versión (se copia al tocarla), y tres grupos de renglones
 * como los de las apps de código abierto: Proyecto, Ayuda y Créditos.
 */
public class AboutFragment extends Fragment {

    private static final String URL_WEB = "https://iaa.org.mx";
    private static final String URL_SOURCE = "https://github.com/iaaorgmx/PaynaniApp";

    private LayoutInflater rows;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Con el tema de Settings y Estado (Theme.Paynani.M3); la actividad usa el viejo.
        rows = inflater.cloneInContext(new ContextThemeWrapper(requireContext(), R.style.Theme_Paynani_M3));
        View v = rows.inflate(R.layout.fragment_about, container, false);

        // La misma versión que Estado (#79): nombre y versionCode.
        String version = getString(R.string.status_app_version_value,
                BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE);
        final String shown = BuildConfig.DEBUG ? version + " - Development Version." : version;
        Chip chip = v.findViewById(R.id.about_version);
        chip.setText(getString(R.string.about_version_value, shown));
        chip.setContentDescription(getString(R.string.about_version_value, shown) + ". "
                + getString(R.string.about_version_copy));
        chip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View b) {
                ClipboardManager cm = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("Paynani", "Paynani " + shown));
                // Desde Android 13 el sistema ya avisa que se copió.
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(requireContext(), R.string.about_version_copied, Toast.LENGTH_SHORT).show();
                }
            }
        });

        LinearLayout project = v.findViewById(R.id.about_project);
        addRow(project, R.drawable.ic_about_web, R.string.about_web, getString(R.string.about_web_detail), URL_WEB);
        addRow(project, R.drawable.ic_about_code, R.string.about_source, getString(R.string.about_source_detail), URL_SOURCE);
        addRow(project, R.drawable.ic_about_license, R.string.about_license, getString(R.string.about_license_detail),
                URL_SOURCE + "/blob/main/LICENSE");

        LinearLayout help = v.findViewById(R.id.about_help);
        addRow(help, R.drawable.ic_about_bug, R.string.about_report, getString(R.string.about_report_detail),
                URL_SOURCE + "/issues/new?body=" + Uri.encode("\n\n---\nPaynani " + shown
                        + "\nAndroid " + android.os.Build.VERSION.RELEASE + " · " + android.os.Build.MODEL));
        addRow(help, R.drawable.ic_about_news, R.string.about_news, getString(R.string.about_news_detail),
                URL_SOURCE + "/releases");

        LinearLayout credits = v.findViewById(R.id.about_credits);
        addRow(credits, R.drawable.ic_about_libraries, R.string.about_libraries, getString(R.string.about_libraries_detail), null);
        addRow(credits, R.drawable.ic_about_font, R.string.about_logo_font, getString(R.string.about_logo_font_detail), null);
        addRow(credits, R.drawable.ic_about_icons, R.string.about_icons, getString(R.string.about_icons_detail), null);
        // Como en PaynaniWeb (#17): el último de Créditos abre la lista del equipo, dentro de la app.
        View team = addRow(credits, R.drawable.ic_about_team, R.string.about_team, getString(R.string.about_team_detail), null);
        team.setBackgroundResource(outValue(android.R.attr.selectableItemBackground));
        team.setOnClickListener(b -> startActivity(new Intent(requireContext(), TeamActivity.class)));
        return v;
    }

    /** Un renglón con ícono, título y detalle; si trae url, la abre en el navegador. */
    private View addRow(LinearLayout parent, @DrawableRes int icon, @StringRes int title, String detail, final String url) {
        View row = rows.inflate(R.layout.item_about_row, parent, false);
        ((ImageView) row.findViewById(R.id.about_row_icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.about_row_title)).setText(title);
        ((TextView) row.findViewById(R.id.about_row_detail)).setText(detail);
        if (url != null) {
            row.findViewById(R.id.about_row_end).setVisibility(View.VISIBLE);
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View b) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    } catch (ActivityNotFoundException e) {
                        Toast.makeText(requireContext(), R.string.about_no_browser, Toast.LENGTH_SHORT).show();
                    }
                }
            });
        } else {
            row.setBackground(null);
            row.setFocusable(true);
        }
        // Se lee como uno: título y detalle.
        row.setContentDescription(getString(title) + ". " + detail);
        parent.addView(row);
        return row;
    }

    /** El drawable de un atributo del tema (el fondo con el efecto al tocar). */
    private int outValue(int attr) {
        android.util.TypedValue tv = new android.util.TypedValue();
        requireContext().getTheme().resolveAttribute(attr, tv, true);
        return tv.resourceId;
    }
}
