package com.stxaviers.app;

/**
 * Google OAuth configuration for the XavierDrive Android app.
 *
 * Project "stxaviersapp" (Google Cloud). Client set (see credentials.txt):
 *
 *   WEB client     433599682518-viicspv9o6hv9pi5rn54tk5f8shmqd51  — runs the
 *                  site's sign-in AND is the Credential Manager serverClientId
 *                  (GetGoogleIdOption mints ID tokens for this audience).
 *   ANDROID client package com.stxaviers.app + release SHA-1
 *                  9F:24:6E:BA:7E:3A:10:63:2B:4E:34:21:45:7A:46:5C:37:C3:18:C8
 *                  — created in Google Cloud ("StXaviers Android (Release)"),
 *                  verifies the app package with Play Services.
 *
 * Sign-in chain (v1.0.3): Credential Manager native account picker ->
 * ID token -> worker /api/auth/mobile verifies it and mints the SAME
 * session the website uses. If anything in that chain is unavailable
 * (no Play Services, old OS, error), LoginActivity falls back to
 * AuthActivity's hardened WebView flow.
 */
public final class GoogleAuth {

    // ── OAuth clients (project "stxaviersapp") ─────────────────────────
    /** Web-application client — Credential Manager serverClientId. */
    public static final String WEB_CLIENT_ID =
            "433599682518-viicspv9o6hv9pi5rn54tk5f8shmqd51.apps.googleusercontent.com";
    /** Original owner-supplied "installed" client (kept for the record). */
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
    /** Native sign-in: POST {idToken} -> verifies + mints the session. */
    public static final String MOBILE_AUTH_URL = WORKER_URL + "/api/auth/mobile";

    /** Name of the worker's session cookie. */
    public static final String SESSION_COOKIE = "xd_sid";

    private GoogleAuth() {}
}
