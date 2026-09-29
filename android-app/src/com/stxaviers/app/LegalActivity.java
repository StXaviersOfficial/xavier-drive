package com.stxaviers.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * In-app legal page viewer (v1.0.5). The login note's "Terms of
 * Service" / "Privacy Policy" links open here — a tiny distraction-free
 * WebView over the site's terms.html / privacy.html pages, so the
 * school's legal texts render inside the app instead of a browser.
 */
public class LegalActivity extends XdActivity {

    public static final String EXTRA_URL = "legal_url";
    public static final String EXTRA_TITLE = "legal_title";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_legal);

        String url = getIntent() != null ? getIntent().getStringExtra(EXTRA_URL) : null;
        String title = getIntent() != null ? getIntent().getStringExtra(EXTRA_TITLE) : null;

        android.widget.TextView bar = (android.widget.TextView) findViewById(R.id.legal_title);
        if (bar != null) bar.setText(title == null ? getString(R.string.close) : title);

        findViewById(R.id.legal_close).setOnClickListener(v -> finish());

        WebView web = (WebView) findViewById(R.id.legal_web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(false);          // static pages only
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(url);
                return true;
            }
        });
        if (url != null && url.startsWith("https://")) web.loadUrl(url);
    }

    @Override
    public void onBackPressed() {
        WebView web = (WebView) findViewById(R.id.legal_web);
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void finish() {
        super.finish();
        try {
            overridePendingTransition(R.anim.zoom_in, R.anim.zoom_out);
        } catch (Throwable ignored) {}
    }
}
