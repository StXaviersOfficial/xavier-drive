package com.stxaviers.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Cinematic splash: animated gradient bg (AnimatedGradientView), sparkle logo
 * overshoot-entrance + endless float + pulsing glow, counter-rotating rings,
 * title/sub slide-up — while the update check runs on a background thread.
 */
public class SplashActivity extends Activity {

    private static final long MIN_SPLASH_MS = 1900L;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private long shownAt = 0L;
    private boolean proceeded = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        shownAt = System.currentTimeMillis();
        setContentView(R.layout.activity_splash);
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

        // initial states
        glow.setAlpha(0f);
        ring1.setAlpha(0f);
        ring2.setAlpha(0f);
        logo.setAlpha(0f);
        logo.setScaleX(0.25f);
        logo.setScaleY(0.25f);
        logo.setRotation(-14f);
        title.setAlpha(0f);
        title.setTranslationY(30f);
        sub.setAlpha(0f);
        sub.setTranslationY(18f);

        final OvershootInterpolator over = new OvershootInterpolator(1.9f);
        final DecelerateInterpolator dec = new DecelerateInterpolator();
        final AccelerateDecelerateInterpolator ace = new AccelerateDecelerateInterpolator();

        // 1) glow breathes in
        glow.animate().alpha(1f).setDuration(700).setInterpolator(dec).start();
        glow.setScaleX(0.7f);
        glow.setScaleY(0.7f);
        // endless glow pulse
        mainHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (glow == null) return;
                glow.animate().scaleX(1.12f).scaleY(1.12f).alpha(0.65f)
                        .setDuration(1400).setInterpolator(ace)
                        .withEndAction(() -> glow.animate().scaleX(0.86f).scaleY(0.86f).alpha(1f)
                                .setDuration(1400).setInterpolator(ace).withEndAction(this).start())
                        .start();
            }
        }, 650);

        // 2) rings fade in, then counter-rotate forever
        ring1.animate().alpha(1f).setDuration(600).setStartDelay(250).setInterpolator(dec).start();
        ring2.animate().alpha(1f).setDuration(600).setStartDelay(450).setInterpolator(dec).start();
        mainHandler.post(new Runnable() {
            long deg1 = 0, deg2 = 0;
            @Override public void run() {
                if (ring1 == null || ring2 == null) return;
                deg1 += 1.6f; deg2 -= 1.0f;
                ring1.setRotation(deg1);
                ring2.setRotation(deg2);
                mainHandler.postDelayed(this, 16);
            }
        });

        // 3) logo overshoot pop
        logo.animate().alpha(1f).scaleX(1f).scaleY(1f).rotation(0f)
                .setDuration(850).setStartDelay(120).setInterpolator(over).start();
        // then endless gentle float
        mainHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (logo == null) return;
                logo.animate().translationY(-12f).setDuration(1700).setInterpolator(ace)
                        .withEndAction(() -> logo.animate().translationY(4f).setDuration(1700)
                                .setInterpolator(ace).withEndAction(this).start())
                        .start();
            }
        }, 1000);

        // 4) title + sub slide up
        title.animate().alpha(1f).translationY(0f).setDuration(650).setStartDelay(430).setInterpolator(dec).start();
        sub.animate().alpha(1f).translationY(0f).setDuration(650).setStartDelay(580).setInterpolator(dec).start();
    }

    private void runUpdateCheck() {
        UpdateCheck.UpdateInfo info = null;
        try {
            info = UpdateCheck.fetchLatest();
        } catch (Throwable ignored) {}
        final UpdateCheck.UpdateInfo update = info;
        final long wait = Math.max(0, MIN_SPLASH_MS - (System.currentTimeMillis() - shownAt));
        mainHandler.postDelayed(() -> proceed(update), wait);
    }

    private void proceed(UpdateCheck.UpdateInfo update) {
        if (proceeded) return;
        proceeded = true;
        if (update != null && update.versionCode > UpdateCheck.CURRENT_VERSION_CODE && !update.apkUrl.trim().isEmpty()) {
            showUpdateDialog(update);
        } else {
            goToMain();
        }
    }

    private void showUpdateDialog(final UpdateCheck.UpdateInfo update) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.update_available)
                .setMessage(getString(R.string.update_message, update.versionName))
                .setPositiveButton(R.string.download, (dialog, which) -> {
                    openInBrowser(update.apkUrl);
                    goToMain();
                })
                .setNegativeButton(R.string.not_now, (dialog, which) -> goToMain())
                .setOnCancelListener(dialog -> goToMain())
                .show();
    }

    private void goToMain() {
        try {
            startActivity(new Intent(this, MainActivity.class));
        } catch (Throwable ignored) {}
        finish();
        try {
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        } catch (Throwable ignored) {}
    }

    private void openInBrowser(String url) {
        try {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Throwable ignored) {}
        } catch (Throwable e) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show();
        }
    }
}
