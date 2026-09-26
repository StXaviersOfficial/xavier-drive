package com.stxaviers.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.webkit.CookieManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * The native login page — the web's cinematic login recoded in pure Android,
 * then cranked up. FX stack:
 *
 *   AuroraView          4 drifting screen-blended orbs (theme aware)
 *   StarfieldView       twinkling stars / bokeh + shooting stars + parallax
 *   logo                overshoot pop + endless float + pulsing glow +
 *                       2 orbiting electron dots
 *   title               per-letter stagger (translateY + scale)
 *                       with sweeping rainbow gradient (ShimmerTextView)
 *   chips               staggered slide-up + infinite float w/ phase offsets
 *   GlowCardView        glass card + rotating conic border + 3D tilt
 *                       (touch-follow, spring return, idle sway)
 *   google button       magnetic pull + shine sweep + ripple + press scale
 *                       -> opens AuthActivity (real Google sign-in)
 *
 * Auto-themes: every color is a @color ref — values/ = light,
 * values-night/ = dark; the system picks at inflate time.
 *
 * Returning users skip straight to the portal: if a live worker session
 * cookie exists (7-day validity), /me is checked in the background and the
 * app continues into MainActivity without showing the login friction.
 */
public class LoginActivity extends Activity {

    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean fx = true;
    private boolean leaving = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        fx = Fx.animationsEnabled(this);
        try {
            buildTitle();
            wireCard();
            wireGoogleButton();
            entrance();
            if (fx) loops();
        } catch (Throwable ignored) {}
        checkExistingSession();
    }

    // ── kinetic title: one ShimmerTextView per letter ───────────────────
    private void buildTitle() {
        String[] rows = {
                getString(R.string.login_title_1),
                getString(R.string.login_title_2)
        };
        int idx = 0;
        OvershootInterpolator over = new OvershootInterpolator(1.28f);
        for (int r = 0; r < rows.length; r++) {
            String row = rows[r] == null ? "" : rows[r];
            if (row.isEmpty()) continue; // single-word brand: row 2 unused
            LinearLayout box = findViewById(r == 0 ? R.id.title_row1 : R.id.title_row2);
            for (char ch : row.toCharArray()) {
                final ShimmerTextView tv = new ShimmerTextView(this);
                tv.setText(String.valueOf(ch));
                tv.setTextSize(32);
                tv.setLetterSpacing(0.02f);
                tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                tv.setLayoutParams(lp);
                tv.setPhase(idx * 0.14f);
                box.addView(tv);
                final int delay = 250 + idx * 55;
                tv.setAlpha(0f);
                tv.setTranslationY(26f);
                tv.setScaleX(0.6f);
                tv.setScaleY(0.6f);
                tv.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                        .setDuration(650).setStartDelay(delay)
                        .setInterpolator(over).start();
                idx++;
            }
        }
    }

    // ── card: 3D tilt + idle sway ───────────────────────────────────────
    private void wireCard() {
        final View card = findViewById(R.id.login_card);
        final View wrap = findViewById(R.id.login_scroll);
        final StarfieldView stars = (StarfieldView) findViewById(R.id.login_stars);
        wrap.setOnTouchListener((v, e) -> {
            if (!fx) return false;
            switch (e.getAction()) {
                case MotionEvent.ACTION_MOVE:
                case MotionEvent.ACTION_DOWN: {
                    int[] loc = new int[2];
                    card.getLocationOnScreen(loc);
                    float cx = loc[0] + card.getWidth() / 2f;
                    float cy = loc[1] + card.getHeight() / 2f;
                    float dx = (e.getRawX() - cx) / (card.getWidth() / 2f);
                    float dy = (e.getRawY() - cy) / (card.getHeight() / 2f);
                    dx = Math.max(-1f, Math.min(1f, dx));
                    dy = Math.max(-1f, Math.min(1f, dy));
                    card.animate().cancel();
                    card.setRotationY(dx * 9f);
                    card.setRotationX(-dy * 9f);
                    // star parallax follows the same finger
                    stars.setPointer(e.getRawX(), e.getRawY());
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    card.animate().rotationY(0f).rotationX(0f)
                            .setDuration(450)
                            .setInterpolator(new OvershootInterpolator(2f))
                            .start();
                    stars.setPointer(
                            getResources().getDisplayMetrics().widthPixels / 2f,
                            getResources().getDisplayMetrics().heightPixels / 2f);
                    break;
            }
            return false; // let the scroll happen too
        });
    }

    // ── google button: magnetic + shine + REAL sign-in ──────────────────
    private void wireGoogleButton() {
        final Button btn = findViewById(R.id.gbtn);
        final View shine = findViewById(R.id.gbtn_shine);
        btn.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_MOVE:
                    case MotionEvent.ACTION_DOWN: {
                        if (!fx) break;
                        int[] loc = new int[2];
                        btn.getLocationOnScreen(loc);
                        float dx = (e.getRawX() - (loc[0] + btn.getWidth() / 2f))
                                / btn.getWidth();
                        dx = Math.max(-0.5f, Math.min(0.5f, dx));
                        // horizontal-only magnet: the wrap clips vertically,
                        // and sideways pull reads just as magnetic
                        btn.animate().translationX(dx * 26f)
                                .scaleX(1.03f).scaleY(1.03f)
                                .setDuration(90).start();
                        break;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        btn.animate().translationX(0f).translationY(0f)
                                .scaleX(1f).scaleY(1f)
                                .setDuration(260)
                                .setInterpolator(new OvershootInterpolator(2.2f))
                                .start();
                        break;
                }
                return false; // keep click handling
            }
        });
        btn.setOnClickListener(v -> goAuth());

        // shine sweep loop
        if (fx && shine != null) {
            shine.post(() -> {
                final int parentW = ((View) shine.getParent()).getWidth();
                final Runnable sweep = new Runnable() {
                    @Override
                    public void run() {
                        if (shine == null) return;
                        shine.setX(-parentW * 0.8f);
                        shine.setAlpha(1f);
                        shine.animate().x(parentW * 1.3f)
                                .setDuration(900)
                                .setStartDelay(2500)
                                .setInterpolator(new AccelerateDecelerateInterpolator())
                                .withEndAction(this)
                                .start();
                    }
                };
                sweep.run();
            });
        }
    }

    // ── returning users: live session cookie -> skip the login ──────────
    private void checkExistingSession() {
        new Thread(() -> {
            boolean ok = false;
            try {
                String cookies = CookieManager.getInstance()
                        .getCookie(GoogleAuth.WORKER_URL);
                if (cookies != null
                        && cookies.contains(GoogleAuth.SESSION_COOKIE + "=")) {
                    HttpURLConnection c = (HttpURLConnection)
                            new URL(GoogleAuth.ME_URL).openConnection();
                    c.setRequestMethod("GET");
                    c.setConnectTimeout(6000);
                    c.setReadTimeout(6000);
                    c.setRequestProperty("Cookie", cookies);
                    ok = (c.getResponseCode() == 200);
                }
            } catch (Throwable ignored) {
            }
            if (ok) {
                runOnUiThread(() -> {
                    if (!leaving && !isFinishing()) {
                        leaving = true;
                        h.postDelayed(this::goMainSilent, 350L);
                    }
                });
            }
        }).start();
    }

    private void goMainSilent() {
        if (isFinishing()) return;
        try {
            startActivity(new Intent(this, MainActivity.class));
        } catch (Throwable ignored) {
        }
        finish();
        try {
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        } catch (Throwable ignored) {}
    }

    // ── entrance choreography ───────────────────────────────────────────
    private void entrance() {
        final View glow = findViewById(R.id.login_glow);
        final ImageView logo = findViewById(R.id.login_logo);
        final View sub = findViewById(R.id.login_sub);
        final View chips = findViewById(R.id.chips_row);
        final View card = findViewById(R.id.login_card);
        final View hint = findViewById(R.id.login_hint);
        final View welcome = findViewById(R.id.login_welcome);
        final View note = findViewById(R.id.login_note);

        DecelerateInterpolator dec = new DecelerateInterpolator();
        OvershootInterpolator over = new OvershootInterpolator(1.9f);

        glow.setAlpha(0f);
        logo.setAlpha(0f);
        logo.setScaleX(0.25f);
        logo.setScaleY(0.25f);
        logo.setRotation(-16f);
        sub.setAlpha(0f);
        sub.setTranslationY(18f);
        chips.setAlpha(0f);
        chips.setTranslationY(26f);
        card.setAlpha(0f);
        card.setTranslationY(40f);
        card.setScaleX(0.94f);
        hint.setAlpha(0f);
        welcome.setAlpha(0f);
        welcome.setTranslationY(12f);
        note.setAlpha(0f);

        glow.animate().alpha(1f).setDuration(700).setInterpolator(dec).start();
        logo.animate().alpha(1f).scaleX(1f).scaleY(1f).rotation(0f)
                .setDuration(850).setStartDelay(120).setInterpolator(over).start();
        sub.animate().alpha(1f).translationY(0f).setDuration(650)
                .setStartDelay(560).setInterpolator(dec).start();
        chips.animate().alpha(1f).translationY(0f).setDuration(700)
                .setStartDelay(700).setInterpolator(dec).start();
        card.animate().alpha(1f).translationY(0f).scaleX(1f).setDuration(800)
                .setStartDelay(850).setInterpolator(dec).start();
        welcome.animate().alpha(1f).translationY(0f).setDuration(500)
                .setStartDelay(1000).setInterpolator(dec).start();
        hint.animate().alpha(1f).setDuration(500).setStartDelay(1120).start();
        note.animate().alpha(1f).setDuration(500).setStartDelay(1300).start();
    }

    // ── infinite loops: orbit dots, glow pulse, logo float, chips ───────
    private void loops() {
        final View glow = findViewById(R.id.login_glow);
        final View logo = findViewById(R.id.login_logo);
        final View dot1 = findViewById(R.id.orbit_dot1);
        final View dot2 = findViewById(R.id.orbit_dot2);
        final View chips = findViewById(R.id.chips_row);
        final View card = findViewById(R.id.login_card);

        AccelerateDecelerateInterpolator ace =
                new AccelerateDecelerateInterpolator();

        // electron dots orbiting the logo
        h.post(new Runnable() {
            float a = 0f, b = 2.4f;
            @Override
            public void run() {
                if (dot1 == null || dot2 == null || logo == null) return;
                float r1 = 96f * getResources().getDisplayMetrics().density;
                float r2 = 110f * getResources().getDisplayMetrics().density;
                dot1.setTranslationX((float) Math.cos(a) * r1);
                dot1.setTranslationY((float) Math.sin(a) * r1 * 0.34f);
                dot1.setAlpha(0.55f + 0.45f * (float) Math.sin(a));
                dot2.setTranslationX((float) Math.cos(b) * r2);
                dot2.setTranslationY((float) Math.sin(b) * r2 * 0.34f);
                dot2.setAlpha(0.5f + 0.5f * (float) Math.sin(b + 1.6f));
                a += 0.055f;
                b -= 0.042f;
                h.postDelayed(this, 16);
            }
        });

        // glow pulse
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (glow == null) return;
                glow.animate().scaleX(1.12f).scaleY(1.12f).alpha(0.65f)
                        .setDuration(1400).setInterpolator(ace)
                        .withEndAction(() -> glow.animate().scaleX(0.86f).scaleY(0.86f)
                                .alpha(1f).setDuration(1400).setInterpolator(ace)
                                .withEndAction(this).start())
                        .start();
            }
        }, 650);

        // logo float
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (logo == null) return;
                logo.animate().translationY(-12f).rotation(1.5f)
                        .setDuration(1700).setInterpolator(ace)
                        .withEndAction(() -> logo.animate().translationY(4f).rotation(-1.5f)
                                .setDuration(1700).setInterpolator(ace)
                                .withEndAction(this).start())
                        .start();
            }
        }, 1000);

        // chips float (phase via different durations — organic drift)
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (chips == null) return;
                chips.animate().translationY(-7f).setDuration(2300)
                        .setInterpolator(ace)
                        .withEndAction(() -> chips.animate().translationY(0f)
                                .setDuration(2300).setInterpolator(ace)
                                .withEndAction(this).start())
                        .start();
            }
        }, 1800);

        // idle card sway (paused while user is tilting)
        h.post(new Runnable() {
            long t0 = System.currentTimeMillis();
            @Override
            public void run() {
                if (card == null) return;
                if (!card.isPressed() && !card.hasTransientState()) {
                    float t = (System.currentTimeMillis() - t0) / 1000f;
                    if (Math.abs(card.getRotationY()) < 0.5f
                            && Math.abs(card.getRotationX()) < 0.5f) {
                        card.setRotationY((float) Math.sin(t * 0.6) * 2.6f);
                        card.setRotationX((float) Math.cos(t * 0.5) * 1.8f);
                    }
                }
                h.postDelayed(this, 32);
            }
        });
    }

    // ── the real Google sign-in (native first, WebView fallback) ────────
    private void goAuth() {
        // 1) Native Credential Manager: instant device-account picker —
        //    no "connecting to Google" wait, saved accounts right there.
        NativeGoogleSignIn.fetch(this, new NativeGoogleSignIn.Callback() {
            @Override
            public void onToken(String idToken, String displayName) {
                // 2) trade the ID token for the school session
                MobileSession.exchange(idToken, new MobileSession.Callback() {
                    @Override
                    public void onSuccess(String name) {
                        runOnUiThread(() -> {
                            if (isFinishing()) return;
                            android.widget.Toast.makeText(LoginActivity.this,
                                    (name == null || name.trim().isEmpty())
                                            ? getString(R.string.auth_signed_in)
                                            : getString(R.string.auth_welcome, name.trim()),
                                    android.widget.Toast.LENGTH_SHORT).show();
                            leaving = true;
                            h.postDelayed(LoginActivity.this::goMainSilent, 250L);
                        });
                    }

                    @Override
                    public void onNetworkError(String message) {
                        // Token was fine, server unreachable — the WebView flow
                        // would hit the same network; stay and let them retry.
                        runOnUiThread(() -> android.widget.Toast.makeText(
                                LoginActivity.this,
                                R.string.mobile_server_unreachable,
                                android.widget.Toast.LENGTH_LONG).show());
                    }
                });
            }

            @Override
            public void onCancelled() {
                // user closed the account picker — quietly back to the page
            }

            @Override
            public void onUnavailable(String reason) {
                // 3) no Play Services / ancient firmware — the hardened
                //    WebView flow still signs in exactly like the website.
                runOnUiThread(LoginActivity.this::openWebViewAuth);
            }
        });
    }

    private void openWebViewAuth() {
        try {
            startActivityForResult(new Intent(this, AuthActivity.class), 7001);
        } catch (Throwable ignored) {}
        try {
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // AuthActivity already launched MainActivity on success — just get
        // out of the way so Back from the portal exits the app cleanly.
        if (requestCode == 7001 && resultCode == RESULT_OK) {
            finish();
            try {
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        try {
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
