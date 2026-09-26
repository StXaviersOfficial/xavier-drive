package com.stxaviers.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.util.Locale;

/**
 * Cinematic splash + the FULL in-app updater, all on one interface:
 *
 *   update found   -> update panel slides in (version, real APK size, notes)
 *   Download       -> true progress bar + percent + MB/MB + live speed + ETA
 *   downloaded     -> system Install/Cancel dialog fires automatically;
 *                     "Install Update" stays available below the file info
 *   no install perm-> tapping Install opens the per-app "install unknown
 *                     apps" page; coming back and tapping again installs
 *   installed      -> OS replaces the app -> next launch = new version,
 *                     which lands on the login page
 */
public class SplashActivity extends Activity {

    private static final long MIN_SPLASH_MS = 1900L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private long shownAt = 0L;
    private boolean proceeded = false;

    private Updater updater;
    private BroadcastReceiver installRx;
    private UpdateCheck.UpdateInfo pendingUpdate;
    private File downloadedApk;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        shownAt = System.currentTimeMillis();
        setContentView(R.layout.activity_splash);
        updater = new Updater(this);
        installRx = Installer.register(this, new Installer.Listener() {
            @Override
            public void onPendingUserAction(Intent confirmIntent) {
                try {
                    startActivity(confirmIntent);   // system Update/Cancel dialog
                } catch (Throwable ignored) {}
            }

            @Override
            public void onSuccess() {
                Installer.ToastCompat.show(SplashActivity.this,
                        getString(R.string.update_installed));
            }

            @Override
            public void onFailure(String message) {
                Installer.ToastCompat.show(SplashActivity.this,
                        getString(R.string.install_failed));
            }
        });

        try {
            animateEntrance();
        } catch (Throwable ignored) {}
        Thread t = new Thread(this::runUpdateCheck, "xavierdrive-update-check");
        t.setDaemon(true);
        t.start();
    }

    private void animateEntrance() {
        final View glow = findViewById(R.id.logo_glow);
        final View ring1 = findViewById(R.id.ring1);
        final View ring2 = findViewById(R.id.ring2);
        final ImageView logo = (ImageView) findViewById(R.id.splash_logo);
        final TextView title = (TextView) findViewById(R.id.splash_title);
        final TextView sub = (TextView) findViewById(R.id.splash_sub);

        glow.setAlpha(0f);
        ring1.setAlpha(0f);
        ring2.setAlpha(0f);
        logo.setAlpha(0f);
        logo.setScaleX(0.62f);
        logo.setScaleY(0.62f);
        title.setAlpha(0f);
        title.setTranslationY(30f);
        sub.setAlpha(0f);
        sub.setTranslationY(18f);

        // v1.0.3: the entrance used a 1.9-tension overshoot from 0.25 scale
        // plus a -14° rotation swing — on a real device that read as the logo
        // SHAKING violently. Everything below is deliberately gentler: a soft
        // settle-in, a slow ±5dp breathe, a whisper of glow.
        final OvershootInterpolator over = new OvershootInterpolator(0.45f);
        final DecelerateInterpolator dec = new DecelerateInterpolator();
        final AccelerateDecelerateInterpolator ace =
                new AccelerateDecelerateInterpolator();

        glow.animate().alpha(1f).setDuration(900).setInterpolator(dec).start();
        glow.setScaleX(0.94f);
        glow.setScaleY(0.94f);
        mainHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (glow == null) return;
                glow.animate().scaleX(1.05f).scaleY(1.05f).alpha(0.82f)
                        .setDuration(2600).setInterpolator(ace)
                        .withEndAction(() -> glow.animate().scaleX(0.94f).scaleY(0.94f).alpha(1f)
                                .setDuration(2600).setInterpolator(ace).withEndAction(this).start())
                        .start();
            }
        }, 900);

        ring1.animate().alpha(1f).setDuration(800).setStartDelay(300).setInterpolator(dec).start();
        ring2.animate().alpha(1f).setDuration(800).setStartDelay(500).setInterpolator(dec).start();
        mainHandler.post(new Runnable() {
            float deg1 = 0f, deg2 = 0f;
            @Override public void run() {
                if (ring1 == null || ring2 == null) return;
                deg1 += 0.55f; deg2 -= 0.34f;          // slow, calm drift
                ring1.setRotation(deg1);
                ring2.setRotation(deg2);
                mainHandler.postDelayed(this, 16);
            }
        });

        logo.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(900).setStartDelay(140).setInterpolator(over).start();
        mainHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (logo == null) return;
                logo.animate().translationY(-5f).setDuration(2800).setInterpolator(ace)
                        .withEndAction(() -> logo.animate().translationY(2f).setDuration(2800)
                                .setInterpolator(ace).withEndAction(this).start())
                        .start();
            }
        }, 1100);

        title.animate().alpha(1f).translationY(0f).setDuration(650).setStartDelay(430).setInterpolator(dec).start();
        sub.animate().alpha(1f).translationY(0f).setDuration(650).setStartDelay(580).setInterpolator(dec).start();
    }

    private void runUpdateCheck() {
        UpdateCheck.UpdateInfo info = null;
        try {
            info = UpdateCheck.fetchLatest(this);
        } catch (Throwable ignored) {}
        final UpdateCheck.UpdateInfo update = info;
        final long wait = Math.max(0, MIN_SPLASH_MS - (System.currentTimeMillis() - shownAt));
        mainHandler.postDelayed(() -> proceed(update), wait);
    }

    private void proceed(UpdateCheck.UpdateInfo update) {
        if (proceeded || isFinishing()) return;
        proceeded = true;
        // version straight from the package manager — an installed 1.0.2 is
        // NEVER offered the 1.0.2 update again (the v1.0.2 bug: a stale
        // hardcoded constant made everyone "outdated")
        int installed = UpdateCheck.currentVersionCode(this);
        if (update != null
                && installed > 0
                && update.versionCode > installed
                && !update.apkUrl.trim().isEmpty()) {
            showUpdateUI(update);
        } else {
            goToLogin();
        }
    }

    // ── update panel ────────────────────────────────────────────────────

    private void showUpdateUI(final UpdateCheck.UpdateInfo update) {
        this.pendingUpdate = update;
        View checking = findViewById(R.id.checking_box);
        final View box = findViewById(R.id.update_box);
        checking.setVisibility(View.GONE);
        box.setVisibility(View.VISIBLE);
        box.setAlpha(0f);
        box.setTranslationY(40f);
        box.animate().alpha(1f).translationY(0f).setDuration(500)
                .setInterpolator(new DecelerateInterpolator()).start();

        ((TextView) findViewById(R.id.update_version_size))
                .setText("v" + update.versionName);
        ((TextView) findViewById(R.id.update_notes)).setText(update.notes);

        // real APK size via HEAD (background)
        new Thread(() -> {
            final long size = Updater.probeSize(update.apkUrl);
            if (size > 0) runOnUiThread(() -> {
                TextView vs = (TextView) findViewById(R.id.update_version_size);
                if (vs != null && pendingUpdate != null) {
                    vs.setText("v" + pendingUpdate.versionName + " · "
                            + mb(size) + " MB");
                }
            });
        }, "xd-size-probe").start();

        findViewById(R.id.btn_download).setOnClickListener(v -> startDownload());
        findViewById(R.id.btn_cancel).setOnClickListener(v -> cancelDownload());
        findViewById(R.id.btn_later).setOnClickListener(v -> confirmLater());
        findViewById(R.id.btn_install).setOnClickListener(v -> doInstall());
    }

    private void startDownload() {
        if (pendingUpdate == null) return;
        findViewById(R.id.btn_download).setVisibility(View.GONE);
        findViewById(R.id.update_error).setVisibility(View.GONE);
        findViewById(R.id.progress_box).setVisibility(View.VISIBLE);
        final ProgressBar bar = (ProgressBar) findViewById(R.id.update_progress);
        bar.setIndeterminate(true);      // until content-length arrives
        bar.setProgress(0);

        updater.download(pendingUpdate.apkUrl, new Updater.Callback() {
            @Override
            public void onProgress(long done, long total, float bps) {
                ProgressBar b = (ProgressBar) findViewById(R.id.update_progress);
                TextView pct = (TextView) findViewById(R.id.progress_percent);
                TextView spd = (TextView) findViewById(R.id.progress_speed);
                TextView byt = (TextView) findViewById(R.id.progress_bytes);
                TextView eta = (TextView) findViewById(R.id.progress_eta);
                if (b == null || pct == null) return;
                if (total > 0) {
                    if (b.isIndeterminate()) b.setIndeterminate(false);
                    b.setMax(1000);
                    b.setProgress((int) (done * 1000 / total));
                    pct.setText(String.format(Locale.US, "%d%%",
                            (int) (done * 100 / total)));
                } else {
                    pct.setText(mb(done) + " MB");
                }
                byt.setText(mb(done) + " MB / " + (total > 0 ? mb(total) + " MB" : "?"));
                if (bps > 0) {
                    spd.setText(String.format(Locale.US, "%.1f MB/s", bps / 1048576f));
                    if (total > done && bps > 0) {
                        long s = (total - done) / (long) bps;
                        eta.setText(s < 60 ? ("~" + s + "s left") : ("~" + (s / 60) + "m left"));
                    }
                }
            }

            @Override
            public void onDone(File apk, long totalBytes) {
                downloadedApk = apk;
                findViewById(R.id.progress_box).setVisibility(View.GONE);
                findViewById(R.id.install_box).setVisibility(View.VISIBLE);
                ((TextView) findViewById(R.id.install_status))
                        .setText(getString(R.string.downloaded_ready, mb(totalBytes)));
                findViewById(R.id.perm_hint).setVisibility(
                        Installer.canInstall(SplashActivity.this)
                                ? View.GONE : View.VISIBLE);
                // auto prompt: system Install/Cancel dialog, right here
                doInstall();
            }

            @Override
            public void onError(String message) {
                findViewById(R.id.progress_box).setVisibility(View.GONE);
                findViewById(R.id.btn_download).setVisibility(View.VISIBLE);
                TextView err = (TextView) findViewById(R.id.update_error);
                err.setVisibility(View.VISIBLE);
            }
        });
    }

    private void cancelDownload() {
        updater.cancel();
        findViewById(R.id.progress_box).setVisibility(View.GONE);
        findViewById(R.id.btn_download).setVisibility(View.VISIBLE);
    }

    private void doInstall() {
        if (downloadedApk == null || !downloadedApk.exists()) return;
        if (!Installer.canInstall(this)) {
            // take the user to the "allow from this source" page; they come
            // back and tap Install Update again
            Installer.openPermPage(this);
            return;
        }
        Installer.install(this, downloadedApk);
    }

    private void confirmLater() {
        if (updater != null) updater.cancel();
        goToLogin();
    }

    private void goToLogin() {
        try {
            startActivity(new Intent(this, LoginActivity.class));
        } catch (Throwable ignored) {}
        finish();
        try {
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        } catch (Throwable ignored) {}
    }

    @Override
    public void onBackPressed() {
        // allow exit from splash, but not mid-install-commit
        if (proceeded) {
            super.onBackPressed();
        } else {
            finish();
        }
    }

    private static String mb(long bytes) {
        return String.format(Locale.US, "%.1f", bytes / 1048576f);
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        if (installRx != null) {
            try { unregisterReceiver(installRx); } catch (Throwable ignored) {}
        }
        super.onDestroy();
    }
}
