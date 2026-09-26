package com.stxaviers.app;

import android.app.Activity;
import android.app.AlertDialog;
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

/**
 * The native login page — the web's cinematic login recoded in pure Android,
 * then cranked up. FX stack:
 *
 *   AuroraView          4 drifting screen-blended orbs (theme aware)
 *   StarfieldView       twinkling stars / bokeh + shooting stars + parallax
 *   logo                overshoot pop + endless float + pulsing glow +
 *                       counter-rotating rings + 2 orbiting electron dots
 *   title               per-letter stagger (translateY + scale + blurless)
 *                       with sweeping rainbow gradient (ShimmerTextView)
 *   chips               staggered slide-up + infinite float w/ phase offsets
 *   GlowCardView        glass card + rotating conic border + 3D tilt
 *                       (touch-follow, spring return, idle sway)
 *   google button       magnetic pull + shine sweep + ripple + press scale
 *
 * Auto-themes: every color is a @color ref — values/ = light,
 * values-night/ = dark; the system picks at inflate time.
 */
public class LoginActivity extends Activity {

    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean fx = true;

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
            findViewById(R.id.continue_link).setOnClickListener(v -> goMain());
        } catch (Throwable ignored) {}
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
            LinearLayout box = findViewById(r == 0 ? R.id.title_row1 : R.id.title_row2);
            for (char ch : rows[r].toCharArray()) {
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
        wrap.setOnTouchListener((v, e) -> {
            if (!fx) return false;
            switch (e.getAction()) {
                case MotionEvent.ACTION_MOVE:
                case MotionEvent.ACTION_DOWN: {
                    float cx = card.getX() + card.getWidth() / 2f;
                    float cy = card.getY() + card.getHeight() / 2f;
                    // card center in scroll coords (scrollY offset matters)
                    int[] loc = new int[2];
                    card.getLocationOnScreen(loc);
                    cx = loc[0] + card.getWidth() / 2f;
                    cy = loc[1] + card.getHeight() / 2f;
                    float dx = (e.getRawX() - cx) / (card.getWidth() / 2f);
                    float dy = (e.getRawY() - cy) / (card.getHeight() / 2f);
                    dx = Math.max(-1f, Math.min(1f, dx));
                    dy = Math.max(-1f, Math.min(1f, dy));
                    card.animate().cancel();
                    card.setRotationY(dx * 9f);
                    card.setRotationX(-dy * 9f);
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    card.animate().rotationY(0f).rotationX(0f)
                            .setDuration(450)
                            .setInterpolator(new OvershootInterpolator(2f))
                            .start();
                    break;
            }
            return false; // let the scroll happen too
        });
    }

    // ── google button: magnetic + shine + press ─────────────────────────
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
                        float dy = (e.getRawY() - (loc[1] + btn.getHeight() / 2f))
                                / btn.getHeight();
                        dx = Math.max(-0.5f, Math.min(0.5f, dx));
                        dy = Math.max(-0.5f, Math.min(0.5f, dy));
                        btn.animate().translationX(dx * 28f).translationY(dy * 20f)
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
        btn.setOnClickListener(v -> showComingSoon());

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

    private void showComingSoon() {
        new AlertDialog.Builder(this, R.style.Theme_XavierDrive_Dialog)
                .setTitle(R.string.login_coming_title)
                .setMessage(R.string.login_coming_msg)
                .setPositiveButton(R.string.continue_to_app,
                        (d, w) -> goMain())
                .setNegativeButton(R.string.close, null)
                .show();
    }

    // ── entrance choreography ───────────────────────────────────────────
    private void entrance() {
        final View glow = findViewById(R.id.login_glow);
        final View ring1 = findViewById(R.id.login_ring1);
        final View ring2 = findViewById(R.id.login_ring2);
        final ImageView logo = findViewById(R.id.login_logo);
        final View sub = findViewById(R.id.login_sub);
        final View chips = findViewById(R.id.chips_row);
        final View card = findViewById(R.id.login_card);
        final View cont = findViewById(R.id.continue_link);
        final View hint = findViewById(R.id.login_hint);
        final View welcome = findViewById(R.id.login_welcome);
        final View note = findViewById(R.id.login_note);

        DecelerateInterpolator dec = new DecelerateInterpolator();
        OvershootInterpolator over = new OvershootInterpolator(1.9f);

        glow.setAlpha(0f);
        ring1.setAlpha(0f);
        ring2.setAlpha(0f);
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
        cont.setAlpha(0f);
        hint.setAlpha(0f);
        welcome.setAlpha(0f);
        welcome.setTranslationY(12f);
        note.setAlpha(0f);

        glow.animate().alpha(1f).setDuration(700).setInterpolator(dec).start();
        logo.animate().alpha(1f).scaleX(1f).scaleY(1f).rotation(0f)
                .setDuration(850).setStartDelay(120).setInterpolator(over).start();
        ring1.animate().alpha(1f).setDuration(600).setStartDelay(250)
                .setInterpolator(dec).start();
        ring2.animate().alpha(1f).setDuration(600).setStartDelay(450)
                .setInterpolator(dec).start();
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
        cont.animate().alpha(1f).setDuration(600).setStartDelay(1450).start();
    }

    // ── infinite loops: rings, orbit dots, glow pulse, logo float, chips ─
    private void loops() {
        final View glow = findViewById(R.id.login_glow);
        final View ring1 = findViewById(R.id.login_ring1);
        final View ring2 = findViewById(R.id.login_ring2);
        final View logo = findViewById(R.id.login_logo);
        final View dot1 = findViewById(R.id.orbit_dot1);
        final View dot2 = findViewById(R.id.orbit_dot2);
        final View chips = findViewById(R.id.chips_row);
        final View card = findViewById(R.id.login_card);

        AccelerateDecelerateInterpolator ace =
                new AccelerateDecelerateInterpolator();

        // rings counter-rotate (frame-stepped like the splash)
        h.post(new Runnable() {
            float a1 = 0f, a2 = 0f;
            @Override
            public void run() {
                if (ring1 == null || ring2 == null) return;
                a1 += 1.5f;
                a2 -= 0.9f;
                ring1.setRotation(a1);
                ring2.setRotation(a2);
                h.postDelayed(this, 16);
            }
        });

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

    private void goMain() {
        try {
            startActivity(new Intent(this, MainActivity.class));
        } catch (Throwable ignored) {}
        finish();
        try {
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        } catch (Throwable ignored) {}
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
