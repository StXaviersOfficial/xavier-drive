package com.stxaviers.app;

/**
 * Google OAuth configuration for the XavierDrive Android app.
 *
 * Source: owner-provided credentials for the Google Cloud project
 * "stxaviersapp" (also embedded verbatim at res/raw/credentials.txt).
 *
 * NOTE ON THE FLOW (2026-09): this client is an ANDROID-type OAuth client.
 * Google policy (2025) blocks custom-URI-scheme and loopback redirects for
 * Android clients, and Credential Manager (the supported native path) needs
 * the AndroidX stack this Gradle-free build deliberately avoids. The live
 * login therefore runs the school's production Google flow (same accounts,
 * same school roles) through the auth worker inside the app's own hardened
 * WebView — see AuthActivity. These constants stay configured here so the
 * switch to Credential Manager is a drop-in when the app moves to Gradle.
 */
public final class GoogleAuth {

    // ── OAuth client (project "stxaviersapp") ──────────────────────────
    public static final String CLIENT_ID =
            "433599682518-pkeb26l3lrk9jbe5lft6g2g0jl6833b4.apps.googleusercontent.com";
    public static final String PROJECT_ID = "stxaviersapp";
    public static final String AUTH_URI = "https://accounts.google.com/o/oauth2/auth";
    public static final String TOKEN_URI = "https://oauth2.googleapis.com/token";
    public static final String CERT_URL = "https://www.googleapis.com/oauth2/v1/certs";

    // ── Production endpoints ───────────────────────────────────────────
    public static final String WORKER_URL =
            "https://stxaviers-auth.quackeditzofficial.workers.dev";
    public static final String SITE_URL = "https://stxaviers.pages.dev";

    /** Worker entry point that starts the Google sign-in redirect chain. */
    public static final String LOGIN_URL = WORKER_URL + "/login";
    /** Session check — returns {user:{email,name,picture},role,...}. */
    public static final String ME_URL = WORKER_URL + "/me";

    /** Name of the worker's session cookie. */
    public static final String SESSION_COOKIE = "xd_sid";

    private GoogleAuth() {}
}
