package com.stxaviers.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * The real Google sign-in for the app.
 *
 * LoginActivity's Google button opens this screen, which runs the school's
 * production Google flow (auth worker -> accounts.google.com -> back) inside
 * a hardened in-app browser:
 *
 *   - Chrome UA (Google blocks OAuth on WebView user-agents)
 *   - DOM storage + 3rd-party cookies (the worker lives on another origin)
 *   - every http(s) hop stays INSIDE the view — the redirect chain that the
 *     external-browser handoff used to break never leaves the app
 *
 * When the worker's callback lands on the site origin with a fresh xd_sid
 * cookie, the flow is intercepted (the site itself never loads here), the
 * session is verified against /me, a themed success state plays, and the
 * portal (MainActivity) opens — never a blank page.
 */
public class AuthActivity extends Activity {

    private final Handler h = new Handler(Looper.getMainLooper());

    private WebView web;
    private View webCard;
    private View bar;
    private TextView status;
    private View errorPanel;
    private TextView errorText;
    private View successPanel;
    private TextView successName;
    private ImageView successLogo;

    private boolean done = false;        // terminal state reached
    private boolean loading = false;     // a page is loading right now
    private int reloads = 0;             // watchdog auto-reloads used
    private String lastStarted = "";

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (done || !loading) return;
            if (reloads < 2) {
                reloads++;
                status.setText(R.string.auth_status_connecting);
                web.reload();
                h.postDelayed(this, 25000L);
            } else {
                showError(getString(R.string.auth_timeout));
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_auth);

        web = (WebView) findViewById(R.id.auth_web);
        webCard = findViewById(R.id.auth_web_card);
        bar = findViewById(R.id.auth_bar);
        status = (TextView) findViewById(R.id.auth_status);
        errorPanel = findViewById(R.id.auth_error);
        errorText = (TextView) findViewById(R.id.auth_error_msg);
        successPanel = findViewById(R.id.auth_success);
        successName = (TextView) findViewById(R.id.auth_success_name);
        successLogo = (ImageView) findViewById(R.id.auth_success_logo);

        Button retry = (Button) findViewById(R.id.auth_retry);
        retry.setOnClickListener(v -> restart());
        Button back = (Button) findViewById(R.id.auth_back);
        back.setOnClickListener(v -> finish());

        configureWebView();
        startFlow();
    }

    private void startFlow() {
        done = false;
        reloads = 0;
        errorPanel.setVisibility(View.GONE);
        successPanel.setVisibility(View.GONE);
        webCard.setVisibility(View.VISIBLE);
        webCard.setAlpha(1f); // undo the success-fade in case of a retry
        web.setVisibility(View.VISIBLE);
        status.setText(R.string.auth_status_connecting);
        web.loadUrl(GoogleAuth.LOGIN_URL);
        armWatchdog();
    }

    private void restart() {
        startFlow();
    }

    private void armWatchdog() {
        h.removeCallbacks(watchdog);
        h.postDelayed(watchdog, 25000L);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setTextZoom(100);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setUserAgentString(MainActivity.CHROME_UA);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setBackgroundColor(0x00000000);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (done) return true;
                android.net.Uri uri = request.getUrl();
                String scheme = uri.getScheme() == null ? "" : uri.getScheme();
                if (!scheme.equals("http") && !scheme.equals("https")) {
                    return true; // swallow mailto:/market: etc. — stay in the flow
                }
                String host = uri.getHost() == null ? "" : uri.getHost();
                // The worker's OAuth callback 302s to the site origin once the
                // session cookie is set — that is our "login complete" signal.
                if (host.equals("stxaviers.pages.dev")) {
                    onFrontendReached(uri.toString());
                    return true; // never actually load the site here
                }
                return false; // accounts.google.com / worker / gstatic stay in-view
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                lastStarted = url;
                if (!done) {
                    loading = true;
                    bar.setVisibility(View.VISIBLE);
                    if (url.contains("accounts.google.com")) {
                        status.setText(R.string.auth_status_wait);
                    }
                    armWatchdog();
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                loading = false;
                if (!done) bar.setVisibility(View.INVISIBLE);
            }

            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request.isForMainFrame() && !done) {
                    showError(getString(R.string.auth_offline));
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (!done) {
                    bar.setAlpha(newProgress >= 100 ? 0f : 1f);
                }
            }
        });
    }

    // ── login completed: the worker bounced us back to the site origin ──
    private void onFrontendReached(String url) {
        if (done) return;
        done = true;
        h.removeCallbacks(watchdog);
        loading = false;
        bar.setVisibility(View.INVISIBLE);

        if (url.contains("auth=error")) {
            showError(getString(R.string.auth_cancelled));
            return;
        }

        webCard.animate().alpha(0f).setDuration(250).start();
        status.setText(R.string.auth_status_verify);

        // The xd_sid cookie is written by the 302 we just intercepted — it
        // can land in the jar a beat late, so poll briefly before giving up.
        h.postDelayed(new Runnable() {
            int tries = 0;

            @Override
            public void run() {
                String cookies = CookieManager.getInstance()
                        .getCookie(GoogleAuth.WORKER_URL);
                if (cookies != null && cookies.contains(GoogleAuth.SESSION_COOKIE + "=")) {
                    verifySession();
                } else if (tries++ < 15) {
                    h.postDelayed(this, 200L);
                } else {
                    showError(getString(R.string.auth_verify_failed));
                }
            }
        }, 250L);
    }

    // ── native /me check: is the session real? who signed in? ───────────
    private void verifySession() {
        new Thread(() -> {
            String name = null;
            boolean ok = false;
            try {
                HttpURLConnection c = (HttpURLConnection)
                        new URL(GoogleAuth.ME_URL).openConnection();
                c.setRequestMethod("GET");
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                String cookies = CookieManager.getInstance()
                        .getCookie(GoogleAuth.WORKER_URL);
                if (cookies != null) c.setRequestProperty("Cookie", cookies);
                c.setRequestProperty("Accept", "application/json");
                int code = c.getResponseCode();
                if (code == 200) {
                    BufferedReader r = new BufferedReader(
                            new InputStreamReader(c.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                    r.close();
                    JSONObject o = new JSONObject(sb.toString());
                    JSONObject user = o.optJSONObject("user");
                    if (user != null && user.optString("email", "").length() > 3) {
                        ok = true;
                        name = user.optString("name", "");
                        if (name == null || name.trim().isEmpty()) {
                            name = user.optString("email", "");
                        }
                    }
                }
            } catch (Throwable ignored) {
            }

            final boolean finalOk = ok;
            final String finalName = name;
            runOnUiThread(() -> {
                if (finalOk) showSuccess(finalName);
                else showError(getString(R.string.auth_verify_failed));
            });
        }).start();
    }

    // ── themed success state, then straight into the portal ─────────────
    private void showSuccess(String name) {
        done = true;
        webCard.setVisibility(View.GONE);
        errorPanel.setVisibility(View.GONE);
        successPanel.setVisibility(View.VISIBLE);

        String who = (name == null || name.trim().isEmpty())
                ? "" : name.trim();
        successName.setText(who.isEmpty()
                ? getString(R.string.auth_signed_in)
                : getString(R.string.auth_welcome, who));

        if (Fx.animationsEnabled(this)) {
            OvershootInterpolator over = new OvershootInterpolator(1.9f);
            DecelerateInterpolator dec = new DecelerateInterpolator();
            successLogo.setAlpha(0f);
            successLogo.setScaleX(0.3f);
            successLogo.setScaleY(0.3f);
            successLogo.setRotation(-14f);
            successLogo.animate().alpha(1f).scaleX(1f).scaleY(1f).rotation(0f)
                    .setDuration(700).setInterpolator(over).start();
            successName.setAlpha(0f);
            successName.setTranslationY(18f);
            successName.animate().alpha(1f).translationY(0f)
                    .setDuration(550).setStartDelay(220).setInterpolator(dec).start();
            // gentle float, mirroring the login page logo
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    successLogo.animate().translationY(-9f).setDuration(1500)
                            .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                            .withEndAction(() -> successLogo.animate().translationY(5f)
                                    .setDuration(1500)
                                    .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                                    .withEndAction(this).start())
                            .start();
                }
            }, 800);
        }

        h.postDelayed(this::goMain, 1150L);
    }

    private void showError(String msg) {
        done = true;
        h.removeCallbacks(watchdog);
        loading = false;
        bar.setVisibility(View.INVISIBLE);
        webCard.setVisibility(View.GONE);
        successPanel.setVisibility(View.GONE);
        errorText.setText(msg);
        errorPanel.setVisibility(View.VISIBLE);

        if (Fx.animationsEnabled(this)) {
            errorPanel.setAlpha(0f);
            errorPanel.setTranslationY(26f);
            errorPanel.animate().alpha(1f).translationY(0f)
                    .setDuration(450)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
    }

    private void goMain() {
        try {
            setResult(RESULT_OK); // tell LoginActivity to step aside
            startActivity(new Intent(this, MainActivity.class));
        } catch (Throwable ignored) {
        }
        finish();
        try {
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        try {
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
