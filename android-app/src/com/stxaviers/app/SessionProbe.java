package com.stxaviers.app;

import android.content.Context;
import android.webkit.CookieManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * ONE shared session probe (v1.0.10). Before this, three copies of the
 * same cookie -> /me check lived in SplashActivity, LoginActivity and
 * HomeActivity — three places to drift out of sync. Now they all ask
 * this class, which classifies the outcome the same way every time:
 *
 *   ALIVE    /me returned 200 with a real user  -> the session is live
 *   DEAD     no cookie at all, or /me said 401/403 -> the session is gone
 *   UNKNOWN  network error while a cookie EXISTS -> offline-but-signed-in;
 *            callers decide (splash: optimistic -> Home; login: stay;
 *            home: ignore — the standing offline-friendly doctrine)
 *
 * The role field is only filled when the server sent one (teacher /
 * student / admin); it powers the home role chip.
 */
public final class SessionProbe {

    public static final int UNKNOWN = 0;
    public static final int ALIVE = 1;
    public static final int DEAD = 2;

    public static final class Result {
        public final int state;
        public final String name;   // "" when unknown
        public final String email;  // "" when unknown
        public final String role;   // "" when unknown
        public final boolean isAdmin;
        public final boolean isDeveloper;
        /** True when a session cookie exists — i.e. the user signed in
         *  before, even if we couldn't verify it right now. */
        public final boolean cookiePresent;

        Result(int state, String name, String email, String role,
               boolean isAdmin, boolean isDeveloper,
               boolean cookiePresent) {
            this.state = state;
            this.name = name == null ? "" : name;
            this.email = email == null ? "" : email;
            this.role = role == null ? "" : role;
            this.isAdmin = isAdmin;
            this.isDeveloper = isDeveloper;
            this.cookiePresent = cookiePresent;
        }

        public boolean alive() { return state == ALIVE; }

        /** A user worth showing (the worker always sends an email). */
        public boolean hasUser() { return email != null && email.length() > 3; }
    }

    public interface Callback {
        void onResult(Result r);
    }

    private SessionProbe() {}

    /** Async flavour — safe to call from the UI thread. */
    public static void check(final Context ctx, final Callback cb) {
        new Thread(() -> {
            final Result r = checkSync(ctx);
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .post(() -> cb.onResult(r));
        }, "xd-session-probe").start();
    }

    /** Blocking flavour — for orchestrators that already own a thread. */
    public static Result checkSync(Context ctx) {
        HttpURLConnection c = null;
        boolean hasCookie = false;
        try {
            String cookies = CookieManager.getInstance()
                    .getCookie(GoogleAuth.WORKER_URL);
            hasCookie = cookies != null
                    && cookies.contains(GoogleAuth.SESSION_COOKIE + "=");
            if (!hasCookie) {
                return new Result(DEAD, "", "", "", false, false, false);
            }
            c = (HttpURLConnection) new URL(GoogleAuth.ME_URL).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            c.setRequestProperty("Cookie", cookies);
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            if (code == 401 || code == 403) {
                return new Result(DEAD, "", "", "", false, false, true);
            }
            if (code != 200) {
                // 5xx / gateway hiccup with a cookie on board — NOT proof
                // the session died; treat as unknown so callers stay kind.
                return new Result(UNKNOWN, "", "", "", false, false, true);
            }
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            JSONObject o = new JSONObject(sb.toString());
            JSONObject user = o.optJSONObject("user");
            String name = "", email = "";
            if (user != null) {
                name = user.optString("name", "");
                email = user.optString("email", "");
            }
            String role = o.optString("role", "");
            boolean admin = o.optBoolean("isAdmin", false);
            boolean dev = o.optBoolean("isDeveloper", false);
            if (email.length() > 3) {
                return new Result(ALIVE, name, email, role, admin, dev,
                        true);
            }
            // 200 but no recognizable user — don't trust it either way
            return new Result(UNKNOWN, "", "", "", false, false, true);
        } catch (Throwable t) {
            // offline / flaky. A cookie on board means the user signed in
            // before — callers that can afford optimism (splash/home)
            // stay signed in.
            return new Result(UNKNOWN, "", "", "", false, false, hasCookie);
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignored) {}
        }
    }
}
