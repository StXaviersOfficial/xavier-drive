package com.stxaviers.app;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.BounceInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * v1.0.4 UPDATE CHECKER — live, loud, and rock-stable where it matters.
 *
 * THE SHAKE IS DEAD. The v1.0.3 logo still drifted on a ±5dp float while
 * two orbit rings spun around it at 35% speed — together that read as the
 * logo trembling. Now:
 *   - the logo gets ONE gentle settle-in (alpha + scale, no rotation,
 *     no overshoot) and is then NEVER moved again — no float, no rings,
 *     no orbit dots. The halo behind it breathes ALPHA ONLY.
 *   - all the "live" energy moved into the background: the new
 *     FabricSpaceView (hue-cycling fluid nebula + silk ribbons + stars)
 *     and the typography itself.
 *
 * Typography (all different, all display fonts, all with GENUINE
 * lowercase glyphs — verified):
 *   XavierDrive          Monoton      (neon-tube, per-letter rainbow)
 *   checking for updates Rubik Puddles (melting paint)
 *   version footer       VT323        (pixel terminal)
 *   update panel         Righteous title / Audiowide meta + buttons /
 *                      Michroma percent / Poppins later
 *
 * The screen shows ONLY: logo, XAVIERDRIVE, the check pulse, the update
 * panel when there is one, and the version at the very bottom. The school
 * line is gone from this screen (it lives on the login page now).
 */
public class SplashActivity extends XdActivity {

    private static final long MIN_SPLASH_MS = 2100L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private long shownAt = 0L;
    private boolean proceeded = false;

    private Updater updater;
    private BroadcastReceiver installRx;
    private UpdateCheck.UpdateInfo pendingUpdate;
    private File downloadedApk;
    private SessionProbe.Result session;   // filled while the splash shows
    private boolean userChoseDownload = false;   // race guard (see below)

    // v1.0.7: the pulse dots / labels / glow used to be infinite
    // ObjectAnimators — under Battery Saver (system animator scale 0)
    // they froze or flickered, which is how the splash read as a still
    // page. One wall-clock tick now drives every one of them as a pure
    // function of elapsed time; nothing the system does to animator
    // scales can stop it.
    private Runnable pulseTick;
    private long pulseT0 = 0L;
    private final List<ShimmerTextView> titleLetters = new ArrayList<>();
    private boolean labelSwapped = false;

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
            applyFonts();
            buildTitle();
            animateEntrance();
            startPulse();
        } catch (Throwable ignored) {}
        setVersionFooter();
        Thread t = new Thread(this::runUpdateCheck, "xavierdrive-update-check");
        t.setDaemon(true);
        t.start();
    }

    // ── typography ──────────────────────────────────────────────────────

    private void applyFonts() {
        ((TextView) findViewById(R.id.checking_label))
                .setTypeface(Typefaces.get(this, Typefaces.RUBIK_PUDDLES));
        ((TextView) findViewById(R.id.update_title))
                .setTypeface(Typefaces.get(this, Typefaces.RIGHTEOUS));
        ((TextView) findViewById(R.id.update_version_size))
                .setTypeface(Typefaces.get(this, Typefaces.AUDIOWIDE));
        ((TextView) findViewById(R.id.update_notes))
                .setTypeface(Typefaces.get(this, Typefaces.ORBITRON_MED));
        ((Button) findViewById(R.id.btn_download))
                .setTypeface(Typefaces.get(this, Typefaces.AUDIOWIDE));
        ((Button) findViewById(R.id.btn_install))
                .setTypeface(Typefaces.get(this, Typefaces.AUDIOWIDE));
        ((TextView) findViewById(R.id.progress_percent))
                .setTypeface(Typefaces.get(this, Typefaces.MICHROMA));
        ((TextView) findViewById(R.id.progress_speed))
                .setTypeface(Typefaces.get(this, Typefaces.AUDIOWIDE));
        ((TextView) findViewById(R.id.progress_bytes))
                .setTypeface(Typefaces.get(this, Typefaces.AUDIOWIDE));
        ((TextView) findViewById(R.id.progress_eta))
                .setTypeface(Typefaces.get(this, Typefaces.AUDIOWIDE));
        ((TextView) findViewById(R.id.btn_cancel))
                .setTypeface(Typefaces.get(this, Typefaces.MICHROMA));
        ((TextView) findViewById(R.id.btn_later))
                .setTypeface(Typefaces.get(this, Typefaces.POPPINS));
        ((TextView) findViewById(R.id.install_status))
                .setTypeface(Typefaces.get(this, Typefaces.AUDIOWIDE));
        ((TextView) findViewById(R.id.version_label))
                .setTypeface(Typefaces.get(this, Typefaces.VT323));
    }

    private void setVersionFooter() {
        TextView v = (TextView) findViewById(R.id.version_label);
        String name = UpdateCheck.currentVersionName(this);
        if (v != null) v.setText(name.isEmpty() ? "v1.1.2" : ("v" + name));
        // the soft alpha pulse is driven by the wall-clock tick below
    }

    /** XavierDrive — one Monoton (neon tube) letter per ShimmerTextView. */
    private void buildTitle() {
        LinearLayout row = (LinearLayout) findViewById(R.id.title_row);
        if (row == null) return;
        String text = getString(R.string.app_name);   // "XavierDrive"
        TypefaceFontHelper.addKineticLetters(this, row, text,
                Typefaces.get(this, Typefaces.MONOTON), 24f, 0.06f,
                Fx.color(this, R.color.splash_title), 320L);
        // collect for the fit pass
        titleLetters.clear();
        for (int i = 0; i < row.getChildCount(); i++) {
            View c = row.getChildAt(i);
            if (c instanceof ShimmerTextView) titleLetters.add((ShimmerTextView) c);
        }
        fitTitleToWidth(row, 24f);
    }

    /** If the Orbitron row is wider than the screen, shrink every letter. */
    private void fitTitleToWidth(final LinearLayout row, final float baseSp) {
        row.post(() -> {
            try {
                int pad = (int) (26f * getResources().getDisplayMetrics().density);
                int avail = getResources().getDisplayMetrics().widthPixels - pad * 2;
                int total = 0;
                for (int i = 0; i < row.getChildCount(); i++) total += row.getChildAt(i).getWidth();
                if (total > avail && total > 0) {
                    float f = avail / (float) total;
                    for (ShimmerTextView tv : titleLetters) {
                        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseSp * f);
                    }
                }
            } catch (Throwable ignored) {}
        });
    }

    // ── choreography ────────────────────────────────────────────────────

    private void animateEntrance() {
        final View glow = findViewById(R.id.logo_glow);
        final ImageView logo = (ImageView) findViewById(R.id.splash_logo);

        DecelerateInterpolator dec = new DecelerateInterpolator();

        // halo: fade in via the one-shot entrance; the forever-breathing
        // ALPHA pulse moved into the wall-clock tick (see startPulse) —
        // scale is never touched (a scale here is what made v1.0.2/v1.0.3
        // feel like the whole emblem was throbbing)
        glow.setAlpha(0f);
        glow.animate().alpha(0.9f).setDuration(900).setInterpolator(dec).start();

        // logo: ONE settle-in. alpha + scale only, no rotation, no overshoot,
        // and after these 700ms NOTHING touches its transform ever again.
        logo.setAlpha(0f);
        logo.setScaleX(0.88f);
        logo.setScaleY(0.88f);
        logo.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(700).setStartDelay(120).setInterpolator(dec).start();

        // title letters: staggered pop (TypefaceFontHelper already set the
        // per-letter entrance animations when it built the row)
    }

    /** The checking pulse + every forever-animation on this screen,
     *  driven by one wall-clock tick: three dots breathing in sequence,
     *  the label + version footer alpha pulses, and the halo breathing.
     *  Pure functions of elapsed time — immune to animator scales.
     *
     *  v1.0.10: after ~1.05s the label politely admits what else is
     *  happening ("checking your session") — the two checks run in
     *  parallel and both must finish before the app moves anywhere. */
    private void startPulse() {
        if (pulseTick != null) return;
        final int[] dotIds = {R.id.pulse_dot1, R.id.pulse_dot2, R.id.pulse_dot3};
        final View[] dots = new View[dotIds.length];
        for (int i = 0; i < dotIds.length; i++) {
            dots[i] = findViewById(dotIds[i]);
            if (dots[i] != null) dots[i].setAlpha(0.25f);
        }
        final TextView label = (TextView) findViewById(R.id.checking_label);
        final TextView footer = (TextView) findViewById(R.id.version_label);
        final View glow = findViewById(R.id.logo_glow);
        pulseT0 = System.currentTimeMillis();
        pulseTick = new Runnable() {
            @Override
            public void run() {
                float t = (System.currentTimeMillis() - pulseT0) / 1000f;
                for (int i = 0; i < dots.length; i++) {
                    View d = dots[i];
                    if (d == null) continue;
                    // 950ms cycle, 190ms stagger — same pacing as the old
                    // ObjectAnimator chain, now as cosines
                    float p = ((t - i * 0.19f) / 0.95f) % 1f;
                    if (p < 0f) p += 1f;
                    d.setAlpha(0.625f - 0.375f * (float) Math.cos(2 * Math.PI * p));
                    float s = 0.975f - 0.275f * (float) Math.cos(2 * Math.PI * p);
                    d.setScaleX(s);
                    d.setScaleY(s);
                }
                if (label != null) {
                    label.setAlpha(0.775f - 0.225f
                            * (float) Math.cos(2 * Math.PI * t / 1.9f));
                    // one honest hand-off: updates first, then the session
                    if (!labelSwapped && t > 1.05f) {
                        labelSwapped = true;
                        label.animate().alpha(0f).setDuration(180)
                                .withEndAction(() -> {
                                    try {
                                        label.setText(R.string.checking_session);
                                        label.animate().alpha(1f)
                                                .setDuration(260).start();
                                    } catch (Throwable ignored) {}
                                }).start();
                    }
                }
                if (footer != null) {
                    footer.setAlpha(0.775f - 0.225f
                            * (float) Math.cos(2 * Math.PI * t / 2.6f));
                }
                // halo breathes 0.5..0.9 once the entrance fade is done
                if (glow != null && t > 1.0f) {
                    float g = 0.7f + 0.2f
                            * (float) Math.cos(2 * Math.PI * (t - 0.9f) / 3.4f);
                    glow.setAlpha(g);
                }
                mainHandler.postDelayed(this, 32);
            }
        };
        mainHandler.post(pulseTick);
    }

    private void stopPulse() {
        if (pulseTick != null) {
            mainHandler.removeCallbacks(pulseTick);
            pulseTick = null;
        }
    }

    // ── the double gate: updates AND session, in parallel ──────────────

    /**
     * v1.0.10 — the website's waiting screen, natively. While the fabric
     * plays, BOTH the update manifest and the saved login session are
     * checked at the same time; only when both are done (and the minimum
     * splash moment has passed) does the app move:
     *
     *   update needed  -> the update panel (Later still routes by session)
     *   no update     -> straight to Home when signed in, Login otherwise
     *
     * A returning user therefore NEVER sees the login page again.
     */
    private void runUpdateCheck() {
        final AtomicReference<SessionProbe.Result> sessionRef =
                new AtomicReference<>(
                        new SessionProbe.Result(SessionProbe.UNKNOWN, "", "", "",
                                false, false, false));
        Thread sessionThread = new Thread(
                () -> sessionRef.set(SessionProbe.checkSync(this)),
                "xd-splash-session");
        sessionThread.setDaemon(true);
        sessionThread.start();

        UpdateCheck.UpdateInfo info = null;
        try {
            info = UpdateCheck.fetchLatest(this);
        } catch (Throwable ignored) {}

        try {
            sessionThread.join(9000L);   // probe self-times-out at 6s+6s
        } catch (Throwable ignored) {}

        final UpdateCheck.UpdateInfo update = info;
        final SessionProbe.Result sess = sessionRef.get();
        final long wait = Math.max(0, MIN_SPLASH_MS - (System.currentTimeMillis() - shownAt));
        mainHandler.postDelayed(() -> proceed(update, sess), wait);
    }

    private void proceed(UpdateCheck.UpdateInfo update, SessionProbe.Result sess) {
        if (proceeded || isFinishing()) return;
        proceeded = true;
        this.session = sess;
        // version straight from the package manager — an installed build is
        // NEVER offered its own version as an "update" (the v1.0.2 bug)
        int installed = UpdateCheck.currentVersionCode(this);
        if (update != null
                && installed > 0
                && update.versionCode > installed
                && !update.apkUrl.trim().isEmpty()) {
            showUpdateUI(update);
        } else {
            routeBySession();
        }
    }

    /** Verified-alive, or signed-in-before but offline -> Home; else Login. */
    private void routeBySession() {
        SessionProbe.Result s = session;
        if (s != null
                && ((s.alive() && s.hasUser())
                    || (s.state == SessionProbe.UNKNOWN && s.cookiePresent))) {
            goHome(s.name, s.email);
        } else {
            goToLogin();
        }
    }

    private void goHome(String name, String email) {
        try {
            Intent i = new Intent(this, HomeActivity.class);
            if (name != null && !name.trim().isEmpty()) {
                i.putExtra(HomeActivity.EXTRA_NAME, name);
            }
            if (email != null && !email.trim().isEmpty()) {
                i.putExtra(HomeActivity.EXTRA_EMAIL, email);
            }
            startActivity(i);
        } catch (Throwable ignored) {}
        finish();
        try {
            overridePendingTransition(R.anim.warp_in, R.anim.warp_out);
        } catch (Throwable ignored) {}
    }

    // ── update panel ────────────────────────────────────────────────────

    private void showUpdateUI(final UpdateCheck.UpdateInfo update) {
        this.pendingUpdate = update;
        final View checking = findViewById(R.id.checking_box);
        final View box = findViewById(R.id.update_box);

        // the pulse collapses into nothing…
        stopPulse();
        if (checking != null) {
            checking.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f)
                    .setDuration(260).setInterpolator(new AccelerateInterpolator())
                    .withEndAction(() -> {
                        try { checking.setVisibility(View.GONE); } catch (Throwable ignored) {}
                    }).start();
        }

        // …and the glass panel warps in with a satisfying overshoot
        box.setVisibility(View.VISIBLE);
        box.setAlpha(0f);
        box.setTranslationY(54f);
        box.setScaleX(0.92f);
        box.setScaleY(0.92f);
        box.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setDuration(560).setStartDelay(200)
                .setInterpolator(new OvershootInterpolator(0.65f)).start();

        TextView title = (TextView) findViewById(R.id.update_title);
        TextView meta = (TextView) findViewById(R.id.update_version_size);
        TextView notes = (TextView) findViewById(R.id.update_notes);
        title.setAlpha(0f); title.setTranslationY(16f);
        meta.setAlpha(0f); meta.setTranslationY(12f);
        notes.setAlpha(0f);
        title.animate().alpha(1f).translationY(0f).setDuration(450)
                .setStartDelay(420).setInterpolator(new DecelerateInterpolator()).start();
        meta.animate().alpha(1f).translationY(0f).setDuration(450)
                .setStartDelay(520).setInterpolator(new DecelerateInterpolator()).start();
        notes.animate().alpha(1f).setDuration(450)
                .setStartDelay(620).setInterpolator(new DecelerateInterpolator()).start();

        meta.setText("v" + update.versionName);
        notes.setText(update.notes);

        // real APK size via HEAD (background) — AND the v1.0.10 rule: if
        // this exact version is already on disk (downloaded before, the
        // install was skipped or glitched), skip straight to Install.
        final Button dl = (Button) findViewById(R.id.btn_download);
        final LinearLayout installBox = (LinearLayout) findViewById(R.id.install_box);
        final TextView installStatus = (TextView) findViewById(R.id.install_status);
        new Thread(() -> {
            final long size = Updater.probeSize(update.apkUrl);
            final File cached = updater.fileFor(update.versionCode);
            boolean reusable = false;
            if (cached.exists() && cached.length() > 524288L) {  // >0.5 MB
                reusable = (size <= 0) || (cached.length() == size);
                if (!reusable) {
                    //noinspection ResultOfMethodCallIgnored
                    cached.delete();     // partial/corrupt — start clean
                }
            }
            updater.cleanStale(cached);
            final boolean ready = reusable;
            runOnUiThread(() -> {
                if (isFinishing() || installBox == null) return;
                if (size > 0 && meta != null && pendingUpdate != null) {
                    meta.setText("v" + pendingUpdate.versionName + " · "
                            + mb(size) + " MB");
                }
                // Race guard: if the user already tapped Download while
                // this probe was running, their download owns the UI now —
                // never yank the buttons out from under a live download.
                if (ready && !userChoseDownload) {
                    downloadedApk = cached;
                    dl.setVisibility(View.GONE);
                    installBox.setVisibility(View.VISIBLE);
                    if (installStatus != null) {
                        installStatus.setText(getString(
                                R.string.update_ready_file, mb(cached.length())));
                    }
                    findViewById(R.id.perm_hint).setVisibility(
                            Installer.canInstall(SplashActivity.this)
                                    ? View.GONE : View.VISIBLE);
                    // no auto-prompt on a resumed download — the user
                    // already saw the system dialog once; they tap Install
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
        userChoseDownload = true;   // the reuse probe must back off now
        findViewById(R.id.btn_download).setVisibility(View.GONE);
        findViewById(R.id.update_error).setVisibility(View.GONE);
        findViewById(R.id.progress_box).setVisibility(View.VISIBLE);
        final ProgressBar bar = (ProgressBar) findViewById(R.id.update_progress);
        bar.setIndeterminate(true);      // until content-length arrives
        bar.setProgress(0);

        updater.download(pendingUpdate.apkUrl, pendingUpdate.versionCode, new Updater.Callback() {
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
                // v1.0.6: show the actual reason ("HTTP 500", "connection
                // timed out"…) — a generic failure line gave the owner
                // nothing to diagnose with
                String extra = message == null ? "" : message.trim();
                if (extra.length() > 80) extra = extra.substring(0, 80);
                err.setText(getString(R.string.download_failed)
                        + (extra.isEmpty() ? "" : "\n(" + extra + ")"));
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
            Installer.openPermPage(this);
            return;
        }
        Installer.install(this, downloadedApk);
    }

    private void confirmLater() {
        if (updater != null) updater.cancel();
        routeBySession();   // v1.0.10: "Later" respects a saved login too
    }

    private void goToLogin() {
        try {
            startActivity(new Intent(this, LoginActivity.class));
        } catch (Throwable ignored) {}
        finish();
        try {
            overridePendingTransition(R.anim.warp_in, R.anim.warp_out);
        } catch (Throwable ignored) {}
    }

    @Override
    public void onBackPressed() {
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
    protected void onPause() {
        super.onPause();
        // v1.0.6: the system install/permission dialog sits on top of us
        // while the APK installs — stop the full-screen fabric repaint
        FabricSpaceView fabric = (FabricSpaceView) findViewById(R.id.splash_fabric);
        if (fabric != null) fabric.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        FabricSpaceView fabric = (FabricSpaceView) findViewById(R.id.splash_fabric);
        if (fabric != null) fabric.resume();
    }

    @Override
    protected void onDestroy() {
        stopPulse();
        mainHandler.removeCallbacksAndMessages(null);
        if (installRx != null) {
            try { unregisterReceiver(installRx); } catch (Throwable ignored) {}
        }
        super.onDestroy();
    }
}
