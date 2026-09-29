package com.stxaviers.app;

import android.content.Context;
import android.webkit.CookieManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * ONE shared HTTP client for every Worker call the app makes (v1.1.0).
 *
 * Before this class each screen rolled its own connection code — the exact
 * drift problem SessionProbe solved for /me. Now:
 *
 *   - every request carries the session cookie from the app-wide jar;
 *   - every MUTATING request (POST/PATCH/DELETE) carries the
 *     X-XavierDrive-App: android header — the Worker's CSRF layer accepts
 *     it because a browser can never send a custom header cross-origin
 *     (the CORS preflight denies it), while the native app legitimately
 *     holds the session cookie (see website-docs/02, section 4);
 *   - JSON parsing, error extraction and timeouts are identical everywhere.
 *
 * All methods are BLOCKING and must run on a background thread; use
 * .on(...) to marshal results back to the UI thread.
 */
public final class ApiClient {

    public static final int CONNECT_MS = 10000;
    public static final int READ_MS = 20000;

    /** A parsed response: status + body (JSON when parseable). */
    public static final class Resp {
        public final int code;
        public final String body;
        public final JSONObject json;   // null when body isn't a JSON object
        public final boolean ok;

        Resp(int code, String body) {
            this.code = code;
            this.body = body == null ? "" : body;
            this.ok = code >= 200 && code < 300;
            JSONObject j = null;
            try { j = new JSONObject(this.body); } catch (Throwable ignored) {}
            this.json = j;
        }

        public String error() {
            if (json != null) return json.optString("error", "");
            return body.length() > 200 ? body.substring(0, 200) : body;
        }
    }

    public interface Callback {
        void onResult(Resp r);
    }

    private ApiClient() {}

    // ── cookie + headers ────────────────────────────────────────────────

    public static String cookieHeader() {
        try {
            String c = CookieManager.getInstance()
                    .getCookie(GoogleAuth.WORKER_URL);
            return c == null ? "" : c;
        } catch (Throwable t) {
            return "";
        }
    }

    private static void applyCommon(HttpURLConnection c,
                                    String method, boolean mutating)
            throws java.io.IOException {
        c.setConnectTimeout(CONNECT_MS);
        c.setReadTimeout(READ_MS);
        try {
            c.setRequestMethod(method);
        } catch (java.net.ProtocolException pe) {
            // never happens for the methods we use; keep compile honest
            throw new java.io.IOException("bad method " + method);
        }
        String cookie = cookieHeader();
        if (!cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "XavierDrive/Android");
        if (mutating) {
            // browsers cannot send custom headers cross-origin (preflight
            // denies) — this marks the legitimate native app instead of
            // forging a web Origin.
            c.setRequestProperty("X-XavierDrive-App", "android");
        }
    }

    // ── verbs ───────────────────────────────────────────────────────────

    /** GET (or DELETE) a Worker path like "/drive/files". */
    public static Resp request(String method, String pathAndQuery) {
        HttpURLConnection c = null;
        boolean mutating = !method.equals("GET") && !method.equals("HEAD");
        try {
            c = (HttpURLConnection) new java.net.URL(GoogleAuth.WORKER_URL
                    + pathAndQuery).openConnection();
            applyCommon(c, method, mutating);
            return new Resp(c.getResponseCode(), readAll(c));
        } catch (Throwable t) {
            return networkFail(t);
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignored) {}
        }
    }

    /** POST/PUT/PATCH a JSON body to a Worker path. */
    public static Resp requestJson(String method, String path,
                                   JSONObject body) {
        HttpURLConnection c = null;
        try {
            byte[] bytes = (body == null ? new JSONObject() : body)
                    .toString().getBytes(StandardCharsets.UTF_8);
            c = (HttpURLConnection) new java.net.URL(GoogleAuth.WORKER_URL + path)
                    .openConnection();
            applyCommon(c, method, true);
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(bytes.length);
            c.setRequestProperty("Content-Type", "application/json");
            OutputStream os = c.getOutputStream();
            os.write(bytes);
            os.close();
            return new Resp(c.getResponseCode(), readAll(c));
        } catch (Throwable t) {
            return networkFail(t);
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignored) {}
        }
    }

    /** POST raw bytes with a chosen content type (used for PATCH media). */
    public static Resp requestBytes(String method, String path,
                                    byte[] bytes, String contentType) {
        return requestBytes(method, path, bytes, contentType, null);
    }

    /**
     * v1.1.4: byte upload with a progress listener — the Files upload
     * sheet shows a live percentage bar while the body streams out.
     * The callback runs on the CALLING (background) thread.
     */
    public static Resp requestBytes(String method, String path,
                                    byte[] bytes, String contentType,
                                    final java.util.function.IntConsumer progress) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new java.net.URL(GoogleAuth.WORKER_URL + path)
                    .openConnection();
            applyCommon(c, method, true);
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(bytes.length);
            c.setRequestProperty("Content-Type", contentType);
            OutputStream os = c.getOutputStream();
            if (progress != null) {
                // chunked writes so the listener actually sees movement
                int chunk = 64 * 1024;
                int off = 0;
                while (off < bytes.length) {
                    int n = Math.min(chunk, bytes.length - off);
                    os.write(bytes, off, n);
                    off += n;
                    progress.accept(off * 100 / bytes.length);
                }
                if (bytes.length == 0) progress.accept(100);
            } else {
                os.write(bytes);
            }
            os.close();
            return new Resp(c.getResponseCode(), readAll(c));
        } catch (Throwable t) {
            return networkFail(t);
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignored) {}
        }
    }

    /** GET a binary stream (e.g. /drive/media). Caller must close. */
    public static InputStream openStream(String pathAndQuery,
                                         long[] sizeOut) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new java.net.URL(GoogleAuth.WORKER_URL
                + pathAndQuery).openConnection();
        applyCommon(c, "GET", false);
        int code = c.getResponseCode();
        if (code != 200) {
            c.disconnect();
            throw new IOException("HTTP " + code);
        }
        long len = c.getContentLengthLong();
        if (sizeOut != null && len >= 0) sizeOut[0] = len;
        final HttpURLConnection conn = c;
        return new java.io.FilterInputStream(c.getInputStream()) {
            @Override public void close() throws IOException {
                try { super.close(); } finally { conn.disconnect(); }
            }
        };
    }

    /**
     * POST a JSON body and read the response as raw bytes (binary
     * endpoints like /api/tts). Returns null unless HTTP 200.
     */
    public static byte[] requestBytesReturn(String path, JSONObject body) {
        HttpURLConnection c = null;
        try {
            byte[] bytes = (body == null ? new JSONObject() : body)
                    .toString().getBytes(StandardCharsets.UTF_8);
            c = (HttpURLConnection) new java.net.URL(GoogleAuth.WORKER_URL + path)
                    .openConnection();
            applyCommon(c, "POST", true);
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(bytes.length);
            c.setRequestProperty("Content-Type", "application/json");
            OutputStream os = c.getOutputStream();
            os.write(bytes);
            os.close();
            if (c.getResponseCode() != 200) return null;
            java.io.ByteArrayOutputStream bos =
                    new java.io.ByteArrayOutputStream();
            InputStream in = c.getInputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignored) {}
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /** URL-encode for query strings. */
    public static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    private static String readAll(HttpURLConnection c) {
        try {
            InputStream in = c.getResponseCode() >= 400
                    ? c.getErrorStream() : c.getInputStream();
            if (in == null) return "";
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static Resp networkFail(Throwable t) {
        String m = t.getMessage();
        return new Resp(0, "{\"error\":\"network: "
                + (m == null ? String.valueOf(t) : m) + "\"}");
    }

    // ── async wrapper ───────────────────────────────────────────────────

    /** Run a blocking call on a worker thread, deliver on the UI thread. */
    public static void async(final java.util.concurrent.Callable<Resp> job,
                             final Callback cb) {
        new Thread(() -> {
            Resp r;
            try { r = job.call(); }
            catch (Throwable t) { r = networkFail(t); }
            final Resp fr = r;
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .post(() -> cb.onResult(fr));
        }, "xd-api").start();
    }

    /** Convenience: build a small JSON object from key/value pairs. */
    public static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            try { o.put(String.valueOf(kv[i]), kv[i + 1]); }
            catch (Throwable ignored) {}
        }
        return o;
    }

    /** Convenience: JSONArray from a JSON string, null-safe. */
    public static JSONArray arr(JSONObject o, String key) {
        return o == null ? null : o.optJSONArray(key);
    }
}
