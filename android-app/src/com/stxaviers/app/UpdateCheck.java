package com.stxaviers.app;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONObject;

/** Checks the worker's /api/app/version manifest for updates. */
public final class UpdateCheck {

    public static final int CURRENT_VERSION_CODE = 2;
    public static final String CURRENT_VERSION_NAME = "2.0.0";
    private static final String ENDPOINT = "https://stxaviers-auth.quackeditzofficial.workers.dev/api/app/version";
    private static final int TIMEOUT_MS = 6000;

    private UpdateCheck() {}

    public static final class UpdateInfo {
        public final int versionCode;
        public final String versionName;
        public final String apkUrl;
        public final String notes;

        public UpdateInfo(int versionCode, String versionName, String apkUrl, String notes) {
            this.versionCode = versionCode;
            this.versionName = versionName == null ? "" : versionName;
            this.apkUrl = apkUrl == null ? "" : apkUrl;
            this.notes = notes == null ? "" : notes;
        }
    }

    public static UpdateInfo fetchLatest() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(ENDPOINT);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "XavierDrive/" + CURRENT_VERSION_NAME + " (Android)");
            int status = conn.getResponseCode();
            if (status != 200) return null;
            InputStream in = conn.getInputStream();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            return parse(sb.toString());
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Exception ignored) {}
        }
    }

    static UpdateInfo parse(String body) {
        try {
            JSONObject o = new JSONObject(body);
            int code = o.optInt("versionCode", 0);
            String name = o.optString("versionName", "");
            String apkUrl = o.optString("apkUrl", "");
            String notes = o.optString("notes", "");
            if (code > 0 && !name.isEmpty()) return new UpdateInfo(code, name, apkUrl, notes);
        } catch (Exception ignored) {}
        return null;
    }
}
