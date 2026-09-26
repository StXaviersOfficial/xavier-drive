package com.stxaviers.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.Toast;

/**
 * XavierDrive main shell: the full school portal (stxaviers.pages.dev)
 * running inside a properly-configured WebView. Reached after a successful
 * Google sign-in (AuthActivity) — the worker session cookie is already in
 * the app-wide cookie jar, so the site boots straight into the app screen.
 *
 * Blank-page defense (owner-reported bug): a load watchdog re-arms on every
 * page start — if the page stalls (progress < 100 for 25s) it silently
 * reloads (max twice) before falling back to the offline panel; and after
 * every page finish a JS probe checks that the document actually rendered
 * (body children + title), self-healing the "loaded but white" case.
 */
public class MainActivity extends Activity {

    private static final String SITE_URL = "https://stxaviers.pages.dev/";
    // Google blocks OAuth on WebView user-agents ("; wv" marker) — use a
    // Chrome-mobile UA so the school Google sign-in works inside the app.
    public static final String CHROME_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";
    private static final int FILE_CHOOSER_CODE = 1001;

    private WebView web;
    private View offlineOverlay;
    private ValueCallback<Uri[]> filePathCallback;
    private long lastBackAt = 0L;

    // blank-page watchdog state
    private final android.os.Handler watchdogHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean pageLoading = false;
    private boolean probing = false;
    private int stalledReloads = 0;
    private int blankReloads = 0;

    private final Runnable loadWatchdog = new Runnable() {
        @Override
        public void run() {
            if (pageLoading && web != null) {
                if (stalledReloads < 2) {
                    stalledReloads++;
                    web.reload();
                    watchdogHandler.postDelayed(this, 25000L);
                } else {
                    offlineOverlay.setVisibility(View.VISIBLE);
                }
            }
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        web = (WebView) findViewById(R.id.web);
        offlineOverlay = findViewById(R.id.offline);
        Button retry = (Button) findViewById(R.id.retry_btn);
        retry.setOnClickListener(v -> {
            offlineOverlay.setVisibility(View.GONE);
            stalledReloads = 0;
            blankReloads = 0;
            web.reload();
        });

        configureWebView();
        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
            if (web.getUrl() == null) web.loadUrl(SITE_URL);
        } else {
            web.loadUrl(SITE_URL);
        }
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
        s.setDisplayZoomControls(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setTextZoom(100);                       // ignore system font scale
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setSupportMultipleWindows(false);       // window.open stays in-app (OAuth safe)
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setUserAgentString(CHROME_UA);

        // The portal talks to the auth worker on another domain — its session
        // cookie MUST be accepted or login loops forever.
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        // theme-colored web background — a white flash must never read as "blank"
        try {
            web.setBackgroundColor(Fx.color(this, R.color.bg_deep));
        } catch (Throwable ignored) {}
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme() == null ? "" : uri.getScheme();
                // Non-web intents (mailto:, tel:, whatsapp:, intent:) -> external apps.
                // ALL http(s) urls stay in the WebView — that keeps the Google OAuth
                // redirect chain (site -> worker -> accounts.google -> callback) intact.
                if (!scheme.equals("http") && !scheme.equals("https")) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    } catch (Throwable ignored) {}
                    return true;
                }
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                pageLoading = true;
                watchdogHandler.removeCallbacks(loadWatchdog);
                watchdogHandler.postDelayed(loadWatchdog, 25000L);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                pageLoading = false;
                watchdogHandler.removeCallbacks(loadWatchdog);
                offlineOverlay.setVisibility(View.GONE);
                // did the page actually render? (loaded-but-white self-heal)
                // only for the portal itself — OAuth hop pages are allowed to be bare
                if (url != null && url.startsWith("https://stxaviers.pages.dev")) {
                    watchdogHandler.postDelayed(() -> probeRendered(), 900L);
                }
            }

            /** JS probe: body children + title length — a real render has both. */
            private void probeRendered() {
                if (probing || pageLoading || web == null) return;
                probing = true;
                try {
                    web.evaluateJavascript(
                            "(function(){var b=document.body;return b?(b.childElementCount+'|'+(document.title||'').length):'0|0'})()",
                            value -> {
                                probing = false;
                                if (value == null) return;
                                String v = value.replace("\"", "");
                                String[] parts = v.split("\\|");
                                int children = 0, titleLen = 0;
                                try {
                                    children = Integer.parseInt(parts[0]);
                                    titleLen = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
                                } catch (NumberFormatException ignored) {}
                                if (children == 0 && titleLen == 0 && web != null) {
                                    if (blankReloads < 1) {
                                        blankReloads++;
                                        web.reload();
                                    } else {
                                        offlineOverlay.setVisibility(View.VISIBLE);
                                    }
                                }
                            });
                } catch (Throwable t) {
                    probing = false;
                }
            }

            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                // only fatal if the MAIN FRAME failed; favicons/css 404s must not
                // trigger the offline screen
                if (request.isForMainFrame()) {
                    if (view.getTitle() == null || view.getTitle().isEmpty()) {
                        offlineOverlay.setVisibility(View.VISIBLE);
                    }
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                // handled per-resource by WebView itself; ignore
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            // File uploads (chat attachments): <input type="file">
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                try {
                    Intent intent = params.createIntent();
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivityForResult(Intent.createChooser(intent, "Select file"), FILE_CHOOSER_CODE);
                } catch (Throwable e) {
                    filePathCallback = null;
                    Toast.makeText(MainActivity.this, "Cannot open file picker", Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }
        });

        // Downloads: PDFs, charts, generated files, APK updates
        web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimetype, long contentLength) {
                try {
                    DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                    if (mimetype != null && !mimetype.isEmpty()) req.setMimeType(mimetype);
                    String cookies = CookieManager.getInstance().getCookie(url);
                    if (cookies != null) req.addRequestHeader("cookie", cookies);
                    req.addRequestHeader("User-Agent", userAgent);
                    req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    String name = URLUtil.guessFileName(url, contentDisposition, mimetype);
                    req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                    DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                    dm.enqueue(req);
                    Toast.makeText(MainActivity.this, "Downloading " + name + "…", Toast.LENGTH_SHORT).show();
                } catch (Throwable e) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    } catch (Throwable ignored) {}
                }
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_CODE) {
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                results = new Uri[]{ data.getData() };
            }
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackAt < 2200L) {
            super.onBackPressed();
        } else {
            lastBackAt = now;
            Toast.makeText(this, R.string.exit_hint, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (web != null) web.saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onDestroy() {
        watchdogHandler.removeCallbacksAndMessages(null);
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
