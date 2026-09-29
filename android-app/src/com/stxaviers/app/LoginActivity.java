package com.stxaviers.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.SpannableString;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * LOGIN v1.0.5 — the typography pass the owner asked for.
 *
 * Fonts (all VERIFIED to have genuine lowercase glyphs — Bungee and
 * Bebas Neue were thrown out because their lowercase is just scaled
 * capitals, which made v1.0.4 read as ALL CAPS):
 *   XavierDrive                  Shrikhand     fat retro display,
 *                                              per-letter kinetic +
 *                                              rainbow shimmer + bob
 *   St. Xavier's Jr./Sr. School  Poppins       NORMAL font, NEVER
 *                                              animated (owner rule)
 *   Welcome back                 Rubik Glitch  crazyass glitch font,
 *                                              animated to death:
 *                                              wave + spin + breathe +
 *                                              random glitch bursts
 *   Continue with Google         system font   written normally, not
 *                                              animated (owner rule);
 *                                              official-colour G at a
 *                                              normal 20dp size
 *   terms note                   system sans   Terms/Privacy open the
 *                                              in-app legal viewer
 *
 * Google sign-in chain (v1.0.5): Credential Manager -> LEGACY account
 * picker (the classic chooser with every device account — fixes the
 * GetCredentialProviderConfigurationException dead end) -> Play
 * Services rescue dialog -> WebView flow.
 */
public class LoginActivity extends XdActivity {

    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean fx = true;
    private boolean leaving = false;
    private boolean signingIn = false;       // v1.0.6: double-tap guard
    private int nativeRescues = 0;          // PS-update dialog, max 1
    private final List<ShimmerTextView> welcomeLetters = new ArrayList<>();
    private final List<ShimmerTextView> titleLetters = new ArrayList<>();
    private final Random rnd = new Random();

    // v1.0.6: the three 60fps handler loops, tracked so they can be paused
    // when the activity is covered (account picker / legal viewer open on
    // top) — the loops and the full-screen fabric used to keep burning CPU
    // behind the system UI for as long as those overlays sat there.
    // v1.0.7: glow breathing + logo drift moved into the same wall-clock
    // regime (ambienceLoop) — the old ViewPropertyAnimator withEndAction
    // chains snapped end-to-end every frame under Battery Saver's animator
    // scale 0, which read as a flickering glow / shaking logo.
    private Runnable welcomeLoop, titleLoop, swayLoop, ambienceLoop;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        fx = Fx.animationsEnabled(this);
        try {
            applyFonts();
            buildTitle();
            buildWelcome();
            wireCard();
            wireGoogleButton();
            entrance();
            if (fx) loops();
        } catch (Throwable ignored) {}
        checkExistingSession();
    }

    // ── typography ──────────────────────────────────────────────────────

    private void applyFonts() {
        // school line — Poppins, normal font, static, mixed case
        TextView sub = (TextView) findViewById(R.id.login_sub);
        sub.setTypeface(Typefaces.get(this, Typefaces.POPPINS));
        // Google button — the owner said "write it normally": system
        // font, and the G at a proper 20dp with its OFFICIAL colours
        Button gbtn = (Button) findViewById(R.id.gbtn);
        gbtn.setTypeface(Typeface.DEFAULT_BOLD);
        android.graphics.drawable.Drawable g =
                getResources().getDrawable(R.drawable.ic_google);
        int sz = (int) (20f * getResources().getDisplayMetrics().density);
        if (g != null) {
            g.setBounds(0, 0, sz, sz);
            gbtn.setCompoundDrawables(g, null, null, null);
        }
        wireTermsLinks();
    }

    /** "Terms of Service" / "Privacy Policy" -> in-app legal pages. */
    private void wireTermsLinks() {
        TextView note = (TextView) findViewById(R.id.login_note);
        String full = getString(R.string.login_note);
        String terms = "Terms of Service";
        String priv = "Privacy Policy";
        SpannableString ss = new SpannableString(full);
        int linkColor = Fx.color(this, R.color.continue_ink);
        int t0 = full.indexOf(terms);
        if (t0 >= 0) attachLink(ss, t0, t0 + terms.length(), linkColor,
                GoogleAuth.SITE_URL + "/terms.html", "Terms of Service");
        int p0 = full.indexOf(priv);
        if (p0 >= 0) attachLink(ss, p0, p0 + priv.length(), linkColor,
                GoogleAuth.SITE_URL + "/privacy.html", "Privacy Policy");
        note.setText(ss);
        note.setMovementMethod(LinkMovementMethod.getInstance());
        note.setHighlightColor(Color.TRANSPARENT);
    }

    private void attachLink(SpannableString ss, int start, int end,
                            final int color, final String url, final String title) {
        ss.setSpan(new ClickableSpan() {
            @Override
            public void onClick(View widget) {
                try {
                    Intent i = new Intent(LoginActivity.this, LegalActivity.class);
                    i.putExtra(LegalActivity.EXTRA_URL, url);
                    i.putExtra(LegalActivity.EXTRA_TITLE, title);
                    startActivity(i);
                    overridePendingTransition(R.anim.zoom_in, R.anim.zoom_out);
                } catch (Throwable ignored) {}
            }

            @Override
            public void updateDrawState(android.text.TextPaint ds) {
                ds.setColor(color);
                ds.setUnderlineText(true);
            }
        }, start, end, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** XavierDrive — Shrikhand letters, kinetic entrance + shimmer. */
    private void buildTitle() {
        LinearLayout row = (LinearLayout) findViewById(R.id.title_row1);
        if (row == null) return;
        TypefaceFontHelper.addKineticLetters(this, row,
                getString(R.string.login_title_1),
                Typefaces.get(this, Typefaces.SHRIKHAND), 33f, 0.0f,
                Fx.color(this, R.color.login_ink), 240L);
        // Shrikhand is wide — shrink the row if it can't fit the screen
        titleLetters.clear();
        for (int i = 0; i < row.getChildCount(); i++) {
            View c = row.getChildAt(i);
            if (c instanceof ShimmerTextView) titleLetters.add((ShimmerTextView) c);
        }
        row.post(() -> {
            try {
                int pad = (int) (26f * getResources().getDisplayMetrics().density);
                int avail = getResources().getDisplayMetrics().widthPixels - pad * 2;
                int total = 0;
                for (int i = 0; i < row.getChildCount(); i++) {
                    total += row.getChildAt(i).getWidth();
                }
                if (total > avail && total > 0) {
                    float f = avail / (float) total;
                    for (ShimmerTextView tv : titleLetters) {
                        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 33f * f);
                    }
                }
            } catch (Throwable ignored) {}
        });
    }

    /**
     * "Welcome back" — Rubik Glitch, one letter per ShimmerTextView.
     * Entrance: letters crash in from alternating sides with spin and
     * overshoot. Afterwards the loop in loops() animates them FOREVER:
     * wave + rotation + breathing scale + random glitch bursts (the
     * "animate the shi out of it" directive).
     */
    private void buildWelcome() {
        LinearLayout row = (LinearLayout) findViewById(R.id.welcome_row);
        if (row == null) return;
        welcomeLetters.clear();
        String text = getString(R.string.login_welcome);
        int idx = 0;
        for (char ch : text.toCharArray()) {
            final ShimmerTextView tv = new ShimmerTextView(this);
            tv.setText(String.valueOf(ch));
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 27f);
            tv.setTypeface(Typefaces.get(this, Typefaces.RUBIK_GLITCH));
            tv.setTextColor(Fx.color(this, R.color.login_ink));
            tv.setLayoutParams(new LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
            tv.setPhase(idx * 0.22f);
            row.addView(tv);
            welcomeLetters.add(tv);

            boolean left = (idx % 2 == 0);
            tv.setAlpha(0f);
            tv.setTranslationX(left ? -46f : 46f);
            tv.setRotation(left ? -24f : 24f);
            tv.setScaleX(0.4f);
            tv.setScaleY(0.4f);
            tv.animate().alpha(1f).translationX(0f).rotation(0f)
                    .scaleX(1f).scaleY(1f)
                    .setDuration(680).setStartDelay(1000L + idx * 55)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(1.45f))
                    .start();
            idx++;
        }
    }

    // ── card: 3D tilt + fabric parallax ────────────────────────────────
    private void wireCard() {
        final View card = findViewById(R.id.login_card);
        final View wrap = findViewById(R.id.login_scroll);
        final FabricSpaceView fabric = (FabricSpaceView) findViewById(R.id.login_fabric);
        // v1.0.6: the glass shine stays in the welcome-text area — the band
        // used to sweep across the Google button too, which read as the G
        // "flashing / too bright" (official colours must never be washed out)
        if (card instanceof GlowCardView) {
            ((GlowCardView) card).setShineMaxFraction(0.45f);
        }
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
                    card.setRotationY(dx * 8f);
                    card.setRotationX(-dy * 8f);
                    if (fabric != null) fabric.setPointer(e.getRawX(), e.getRawY());
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    card.animate().rotationY(0f).rotationX(0f)
                            .setDuration(450)
                            .setInterpolator(new android.view.animation.OvershootInterpolator(2f))
                            .start();
                    if (fabric != null) fabric.setPointer(
                            getResources().getDisplayMetrics().widthPixels / 2f,
                            getResources().getDisplayMetrics().heightPixels / 2f);
                    break;
            }
            return false; // let the scroll happen too
        });
    }

    // ── google button: PLAIN (owner: "write it normally, no need to
    //    animate it") — normal font, official G colours at a normal
    //    size, no shine sweep (the sweep is what made the G flash
    //    "too bright, not normal colours"), just a standard press
    //    press-down feel ─────────────────────────────────────────────
    private void wireGoogleButton() {
        final Button btn = findViewById(R.id.gbtn);
        btn.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        btn.animate().scaleX(0.97f).scaleY(0.97f)
                                .setDuration(90).start();
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        btn.animate().scaleX(1f).scaleY(1f)
                                .setDuration(180)
                                .setInterpolator(
                                        new android.view.animation.OvershootInterpolator(1.6f))
                                .start();
                        break;
                }
                return false; // keep click handling
            }
        });
        btn.setOnClickListener(v -> goAuth());
    }

    // ── returning users: live session cookie -> skip the login ──────────
    // v1.0.10: delegated to the shared SessionProbe (one truth for the
    // cookie -> /me check). The login page only skips when the probe
    // DEFINITELY verified the session (ALIVE); offline stays on this
    // page where the user can still see what's happening.
    private void checkExistingSession() {
        SessionProbe.check(this, r -> {
            if (r.alive() && r.hasUser() && !leaving && !isFinishing()) {
                leaving = true;
                h.postDelayed(() -> goHome(r.name, r.email), 350L);
            }
        });
    }

    /** v1.0.9: sign-in lands on the NATIVE home screen (identity carried
     *  in extras; Home's own soft probe backfills role etc.). */
    private void goHome(String name, String email) {
        if (isFinishing()) return;
        try {
            Intent i = new Intent(this, HomeActivity.class);
            if (name != null && !name.trim().isEmpty()) {
                i.putExtra(HomeActivity.EXTRA_NAME, name);
            }
            if (email != null && !email.trim().isEmpty()) {
                i.putExtra(HomeActivity.EXTRA_EMAIL, email);
            }
            startActivity(i);
        } catch (Throwable ignored) {
        }
        finish();
        try {
            overridePendingTransition(R.anim.warp_in, R.anim.warp_out);
        } catch (Throwable ignored) {}
    }

    // ── entrance choreography ───────────────────────────────────────────
    private void entrance() {
        final View glow = findViewById(R.id.login_glow);
        final ImageView logo = (ImageView) findViewById(R.id.login_logo);
        final View sub = findViewById(R.id.login_sub);
        final View card = findViewById(R.id.login_card);
        final View note = findViewById(R.id.login_note);
        final View gwrap = findViewById(R.id.gbtn_wrap);

        android.view.animation.DecelerateInterpolator dec =
                new android.view.animation.DecelerateInterpolator();

        glow.setAlpha(0f);
        logo.setAlpha(0f);
        logo.setScaleX(0.6f);
        logo.setScaleY(0.6f);
        sub.setAlpha(0f);
        sub.setTranslationY(16f);
        card.setAlpha(0f);
        card.setTranslationY(46f);
        card.setScaleX(0.94f);
        card.setScaleY(0.94f);
        gwrap.setAlpha(0f);
        gwrap.setTranslationY(20f);
        note.setAlpha(0f);

        glow.animate().alpha(0.9f).setDuration(700).setInterpolator(dec).start();
        logo.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(750).setStartDelay(120)
                .setInterpolator(new android.view.animation.OvershootInterpolator(0.9f))
                .start();
        sub.animate().alpha(1f).translationY(0f).setDuration(650)
                .setStartDelay(760).setInterpolator(dec).start();
        card.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setDuration(800).setStartDelay(880)
                .setInterpolator(new android.view.animation.OvershootInterpolator(0.7f))
                .start();
        gwrap.animate().alpha(1f).translationY(0f).setDuration(600)
                .setStartDelay(1500).setInterpolator(dec).start();
        note.animate().alpha(1f).setDuration(500).setStartDelay(1750).start();
    }

    // ── infinite loops: glitch-wave welcome, bobbing title, glow, logo ──
    private void loops() {
        final View glow = findViewById(R.id.login_glow);
        final ImageView logo = (ImageView) findViewById(R.id.login_logo);
        final View card = findViewById(R.id.login_card);

        // "Welcome back" — animated to death: endless wave + spin +
        // breathing + RANDOM GLITCH BURSTS every ~2.4s (300ms of
        // per-letter horizontal tearing + flicker). Runs forever.
        welcomeLoop = new Runnable() {
            long t0 = System.currentTimeMillis();
            long nextBurst = 2200L;
            long burstEnd = -1L;

            @Override
            public void run() {
                if (welcomeLetters.isEmpty()) return;
                long now = System.currentTimeMillis();
                float t = (now - t0) / 1000f;
                float dp = getResources().getDisplayMetrics().density;

                if (now >= nextBurst && burstEnd < 0) {
                    burstEnd = now + 300L + rnd.nextInt(220);
                    nextBurst = now + 1900L + rnd.nextInt(1600);
                }
                boolean bursting = burstEnd > 0 && now < burstEnd;
                if (bursting && rnd.nextInt(6) == 0) {
                    // occasionally flip a letter's alpha for a frame —
                    // hard digital flicker inside the burst
                    ShimmerTextView tv = welcomeLetters.get(
                            rnd.nextInt(welcomeLetters.size()));
                    tv.setAlpha(rnd.nextBoolean() ? 0.25f : 1f);
                } else if (!bursting) {
                    burstEnd = -1L;
                }

                for (int i = 0; i < welcomeLetters.size(); i++) {
                    ShimmerTextView tv = welcomeLetters.get(i);
                    float ph = i * 0.55f;
                    float waveY = (float) Math.sin(t * 2.6f + ph) * 7f * dp;
                    float rot = (float) Math.sin(t * 1.9f + ph) * 7f;
                    float scl = 1f + 0.06f * (float) Math.sin(t * 3.2f + ph);
                    if (bursting) {
                        // glitch tear: letters jump sideways + over-rotate
                        waveY += (rnd.nextFloat() - 0.5f) * 9f * dp;
                        rot += (rnd.nextFloat() - 0.5f) * 26f;
                        scl = 1f + (rnd.nextFloat() - 0.5f) * 0.16f;
                        tv.setTranslationX((rnd.nextFloat() - 0.5f) * 10f * dp);
                    } else {
                        tv.setTranslationX(0f);
                        if (tv.getAlpha() < 1f) tv.setAlpha(1f);
                    }
                    tv.setTranslationY(waveY);
                    tv.setRotation(rot);
                    tv.setScaleX(scl);
                    tv.setScaleY(scl);
                }
                h.postDelayed(this, 16);
            }
        };
        h.postDelayed(welcomeLoop, 2500);

        // XavierDrive (Shrikhand) — letters bob on a slow individual wave
        // (the rainbow shimmer itself comes from ShimmerTextView)
        titleLoop = new Runnable() {
            long t0 = System.currentTimeMillis();
            @Override
            public void run() {
                if (titleLetters.isEmpty()) return;
                float t = (System.currentTimeMillis() - t0) / 1000f;
                float dp = getResources().getDisplayMetrics().density;
                for (int i = 0; i < titleLetters.size(); i++) {
                    titleLetters.get(i).setTranslationY(
                            (float) Math.sin(t * 1.8f + i * 0.45f) * 3.2f * dp);
                }
                h.postDelayed(this, 16);
            }
        };
        h.postDelayed(titleLoop, 1600);

        // glow + logo: v1.0.7 wall-clock ambience (alpha-only breathing for
        // the halo, whisper-slow vertical drift for the emblem — pure
        // functions of time, so no animator scale can ever make them
        // flicker, jump, or "shake")
        ambienceLoop = new Runnable() {
            long t0 = System.currentTimeMillis();
            @Override
            public void run() {
                float t = (System.currentTimeMillis() - t0) / 1000f;
                if (glow != null) {
                    // 0.55 .. 0.95 over 1.6s each way (unchanged pacing)
                    float g = 0.55f + 0.40f
                            * (0.5f + 0.5f * (float) Math.sin(t * (float) Math.PI / 1.6f));
                    glow.setAlpha(g);
                }
                if (logo != null) {
                    // -5dp .. +3dp glide, 3.4s each direction
                    float amp = 4f;
                    float l = -1f + amp * (float) Math.sin(t * (float) Math.PI / 3.4f);
                    logo.setTranslationY(l * dp1());
                }
                h.postDelayed(this, 32);
            }
        };
        h.postDelayed(ambienceLoop, 700);

        // idle card sway (paused while the user is tilting it)
        swayLoop = new Runnable() {
            long t0 = System.currentTimeMillis();
            @Override
            public void run() {
                if (card == null) return;
                if (!card.isPressed()) {
                    float t = (System.currentTimeMillis() - t0) / 1000f;
                    if (Math.abs(card.getRotationY()) < 0.5f
                            && Math.abs(card.getRotationX()) < 0.5f) {
                        card.setRotationY((float) Math.sin(t * 0.55) * 2.4f);
                        card.setRotationX((float) Math.cos(t * 0.45) * 1.7f);
                    }
                }
                h.postDelayed(this, 32);
            }
        };
        h.post(swayLoop);
    }

    /** dp factor for the ambience loop (density can't change mid-activity). */
    private float dp1() {
        return getResources().getDisplayMetrics().density;
    }

    /** v1.0.6: stop every 60fps loop + the full-screen fabric while another
     *  activity (account picker, legal viewer) covers this one. */
    private void stopLoginLoops() {
        if (welcomeLoop != null) h.removeCallbacks(welcomeLoop);
        if (titleLoop != null) h.removeCallbacks(titleLoop);
        if (swayLoop != null) h.removeCallbacks(swayLoop);
        if (ambienceLoop != null) h.removeCallbacks(ambienceLoop);
        FabricSpaceView fabric = (FabricSpaceView) findViewById(R.id.login_fabric);
        if (fabric != null) fabric.pause();
    }

    // ── the real Google sign-in ─────────────────────────────────────────
    // Chain (v1.0.5): Credential Manager -> LEGACY account picker (every
    // device account, classic chooser) -> Play Services rescue -> WebView.
    private void goAuth() {
        // v1.0.6: one sign-in at a time — a double-tap used to open two
        // account pickers back-to-back (the second replaced the first and
        // confused the result routing)
        if (signingIn) return;
        signingIn = true;
        NativeGoogleSignIn.fetch(this, new NativeGoogleSignIn.Callback() {
            @Override
            public void onToken(String idToken, String displayName) {
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
                            h.postDelayed(() -> goHome(name, null), 250L);
                        });
                    }

                    @Override
                    public void onNetworkError(String message) {
                        runOnUiThread(() -> {
                            signingIn = false;   // let them try again
                            android.widget.Toast.makeText(
                                    LoginActivity.this,
                                    R.string.mobile_server_unreachable,
                                    android.widget.Toast.LENGTH_LONG).show();
                        });
                    }
                });
            }

            @Override
            public void onCancelled() {
                // user closed the account picker — quietly back to the page
                signingIn = false;
            }

            @Override
            public void onUnavailable(String reason) {
                // BOTH native paths failed. If Play Services itself is
                // outdated (the usual cause of the v1.0.4 credential
                // error), offer the system update dialog once, then
                // retry; otherwise the WebView flow takes over.
                runOnUiThread(() -> {
                    if (nativeRescues < 1) {
                        int code;
                        try {
                            code = GoogleApiAvailability.getInstance()
                                    .isGooglePlayServicesAvailable(LoginActivity.this);
                        } catch (Throwable t) {
                            code = ConnectionResult.SUCCESS; // unknown -> skip rescue
                        }
                        if (code != ConnectionResult.SUCCESS) {
                            nativeRescues++;
                            try {
                                GoogleApiAvailability.getInstance()
                                        .makeGooglePlayServicesAvailable(LoginActivity.this)
                                        .addOnCompleteListener(t -> {
                                            // Play Services updated (or dismissed)
                                            // — one more native attempt
                                            signingIn = false;   // allow the retry
                                            goAuth();
                                        });
                                return;
                            } catch (Throwable ignored) {}
                        }
                    }
                    try {
                        // full reason -> logcat (for us), short friendly line
                        // -> the user (v1.0.7: the old toast printed raw
                        // exception chains like "java.lang.NoClassDefFoundError"
                        // which read as the app breaking)
                        android.util.Log.w("XavierDrive",
                                "native sign-in unavailable, web fallback: " + reason);
                        android.widget.Toast.makeText(LoginActivity.this,
                                R.string.auth_web_fallback,
                                android.widget.Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignored) {}
                    openWebViewAuth();
                });
            }
        });
    }

    private void openWebViewAuth() {
        // the native attempt is over — if the WebView flow is abandoned the
        // onActivityResult(7001) path resets the flag, and if it can't even
        // start the user must be able to tap again
        signingIn = false;
        try {
            startActivityForResult(new Intent(this, AuthActivity.class), 7001);
        } catch (Throwable ignored) {}
        try {
            overridePendingTransition(R.anim.zoom_in, R.anim.zoom_out);
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // the LEGACY account picker result (device accounts chooser)
        if (NativeGoogleSignIn.onActivityResult(requestCode, resultCode, data)) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        // AuthActivity already launched HomeActivity on success — just get
        // out of the way so Back from Home exits the app cleanly.
        if (requestCode == 7001) {
            if (resultCode == RESULT_OK) {
                finish();
            } else {
                signingIn = false;   // WebView sign-in abandoned — allow retry
            }
            try {
                overridePendingTransition(R.anim.zoom_in, R.anim.zoom_out);
            } catch (Throwable ignored) {}
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // v1.0.6: the account picker / legal viewer now sits on top of us —
        // stop the 60fps letter loops and the full-screen fabric repaint
        // (battery + no jank fighting the system UI)
        stopLoginLoops();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // restart the loops only if we're still a live login page
        if (fx && !leaving && !isFinishing()) {
            FabricSpaceView fabric = (FabricSpaceView) findViewById(R.id.login_fabric);
            if (fabric != null) fabric.resume();
            if (welcomeLoop != null || titleLoop != null || swayLoop != null
                    || ambienceLoop != null) {
                loops();
            }
        }
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        try {
            overridePendingTransition(R.anim.zoom_in, R.anim.zoom_out);
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
