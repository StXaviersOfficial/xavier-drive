package com.stxaviers.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * SETTINGS (v1.1.2) — appearance + app utilities.
 *
 * Appearance: Light / Dark / System default (applies everywhere in
 * XavierDrive the moment it's chosen — every screen re-resolves its
 * colours through Ui.themed()).
 *
 * App: the on-demand update check (the same manifest the splash uses),
 * Open XavierDrive (the web app), and Share XavierDrive. The version
 * line lives here and nowhere else (owner order).
 */
public class SettingsActivity extends XdActivity {

    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView status;
    private boolean checking;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        status = findViewById(R.id.set_update_status);
        findViewById(R.id.set_back).setOnClickListener(v -> finish());

        String vn = UpdateCheck.currentVersionName(this);
        ((TextView) findViewById(R.id.set_version)).setText(
                getString(R.string.home_version_line,
                        vn.isEmpty() ? "1.1.2" : vn));

        bindTheme();
        findViewById(R.id.set_update).setOnClickListener(v -> checkNow());
        findViewById(R.id.set_website).setOnClickListener(v -> {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW,
                        Uri.parse(GoogleAuth.SITE_URL));
                startActivity(i);
            } catch (Throwable t) {
                Ui.toast(this, getString(R.string.no_viewer));
            }
        });
        findViewById(R.id.set_share).setOnClickListener(v -> {
            try {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT,
                        "XavierDrive — St. Xavier's Jr./Sr. School's app: "
                                + GoogleAuth.SITE_URL);
                startActivity(Intent.createChooser(i,
                        getString(R.string.share_app)));
            } catch (Throwable ignored) {}
        });
    }

    // ── theme selector ──────────────────────────────────────────────────

    private void bindTheme() {
        final View rows[] = {findViewById(R.id.set_theme_light),
                findViewById(R.id.set_theme_dark),
                findViewById(R.id.set_theme_system)};
        final ImageView checks[] = {
                (ImageView) findViewById(R.id.set_theme_light_check),
                (ImageView) findViewById(R.id.set_theme_dark_check),
                (ImageView) findViewById(R.id.set_theme_system_check)};
        final String modes[] = {Ui.THEME_LIGHT, Ui.THEME_DARK,
                Ui.THEME_SYSTEM};
        final String current = Ui.themeMode(this);

        for (int i = 0; i < rows.length; i++) {
            final String mode = modes[i];
            checks[i].setVisibility(modes[i].equals(current)
                    ? View.VISIBLE : View.INVISIBLE);
            rows[i].setOnClickListener(v -> {
                if (mode.equals(Ui.themeMode(this))) return;
                Ui.setThemeMode(this, mode);
                for (ImageView c : checks) c.setVisibility(View.INVISIBLE);
                for (int k = 0; k < modes.length; k++) {
                    if (modes[k].equals(mode)) {
                        checks[k].setVisibility(View.VISIBLE);
                    }
                }
                // re-resolve every colour of this screen immediately;
                // the other screens recreate on their next resume
                recreate();
            });
        }
    }

    // ── update check ────────────────────────────────────────────────────

    private void checkNow() {
        if (checking) return;
        checking = true;
        status.setText(R.string.check_update);
        new Thread(() -> {
            ApiClient.Resp r = ApiClient.request("GET",
                    "/api/app/version");
            String msg;
            final int remoteCode = r.ok && r.json != null
                    ? r.json.optInt("versionCode", 0) : -1;
            final String remoteName = r.ok && r.json != null
                    ? r.json.optString("versionName", "") : "";
            final String apkUrl = r.ok && r.json != null
                    ? r.json.optString("apkUrl", "") : "";
            int local = UpdateCheck.currentVersionCode(this);
            if (remoteCode > local && !apkUrl.isEmpty()) {
                msg = getString(R.string.update_found, remoteName);
            } else if (remoteCode >= 0) {
                msg = getString(R.string.up_to_date);
            } else {
                msg = getString(R.string.error_load);
            }
            final String m = msg;
            final boolean offer = remoteCode > local && !apkUrl.isEmpty();
            final String url = apkUrl.startsWith("http") ? apkUrl
                    : GoogleAuth.SITE_URL + "/" + apkUrl;
            h.post(() -> {
                checking = false;
                status.setText(m);
                if (offer) {
                    new AlertDialog.Builder(this,
                            R.style.Theme_XavierDrive_Dialog)
                            .setMessage(getString(
                                    R.string.update_message, remoteName))
                            .setPositiveButton(R.string.download_update,
                                    (d, w) -> {
                                        try {
                                            startActivity(new Intent(
                                                    Intent.ACTION_VIEW,
                                                    Uri.parse(url)));
                                        } catch (Throwable ignored) {}
                                    })
                            .setNegativeButton(R.string.later, null)
                            .show();
                }
            });
        }, "xd-settings-update").start();
    }
}
