package com.stxaviers.app;

import android.webkit.CookieManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Exchanges a native Credential-Manager ID token for the school session.
 *
 * POST /api/auth/mobile {idToken} → the worker verifies the token with
 * Google (audience = the school's web client, issuer, expiry, verified
 * email), creates the exact same KV session the website's /callback flow
 * creates, and returns the raw Set-Cookie string. We drop it into the
 * app-wide cookie jar so the portal WebView boots signed-in.
 */
public final class MobileSession {

    public interface Callback {
        void onSuccess(String name);
        void onNetworkError(String message);
    }

    private MobileSession() {}

    public static void exchange(final String idToken, final Callback cb) {
        new Thread(() -> {
            String name = null;
            String error = null;
            HttpURLConnection conn = null;
            try {
                byte[] body = ("{\"idToken\":" + quote(idToken) + "}")
                        .getBytes(StandardCharsets.UTF_8);
                conn = (HttpURLConnection)
                        new URL(GoogleAuth.MOBILE_AUTH_URL).openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(12000);
                conn.setReadTimeout(12000);
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(body.length);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("User-Agent", "XavierDrive/Android");
                OutputStream os = conn.getOutputStream();
                os.write(body);
                os.close();

                int code = conn.getResponseCode();
                BufferedReader r = new BufferedReader(new InputStreamReader(
                        code >= 200 && code < 300 ? conn.getInputStream()
                                : conn.getErrorStream(), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                r.close();

                if (code == 200) {
                    JSONObject o = new JSONObject(sb.toString());
                    if (o.optBoolean("ok")) {
                        JSONObject user = o.optJSONObject("user");
                        if (user != null) {
                            name = user.optString("name", "");
                        }
                        String cookie = o.optString("cookie", "");
                        if (cookie.contains(GoogleAuth.SESSION_COOKIE + "=")) {
                            CookieManager cm = CookieManager.getInstance();
                            cm.setAcceptCookie(true);
                            cm.setCookie(GoogleAuth.WORKER_URL, cookie);
                            cm.flush();
                        } else {
                            error = "no session cookie in reply";
                        }
                    } else {
                        error = o.optString("error", "rejected");
                    }
                } else {
                    JSONObject o = new JSONObject(sb.toString());
                    error = o.optString("error", ("HTTP " + code));
                }
            } catch (Throwable t) {
                error = t.getMessage() == null ? String.valueOf(t) : t.getMessage();
            } finally {
                if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
            }

            final String okName = name;
            final String err = error;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (err == null) cb.onSuccess(okName);
                else cb.onNetworkError(err);
            });
        }, "xd-mobile-auth").start();
    }

    private static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n");  break;
                case '\r': b.append("\\r");  break;
                case '\t': b.append("\\t");  break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }
}
