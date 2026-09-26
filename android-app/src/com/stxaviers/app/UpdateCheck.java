package com.stxaviers.app;

import android.content.Context;
import android.content.pm.PackageInfo;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

import org.json.JSONObject;

/**
 * Checks the worker's /api/app/version manifest for updates.
 *
 * v1.0.3 lesson learned: the installed version is ALWAYS read from the
 * package manager (the same source Android itself uses) — the hardcoded
 * constants here once lagged the manifest by a build and every 1.0.2 user
 * was offered the 1.0.2 update they already had. Never again: there is a
 * single source of truth (AndroidManifest.xml) and this class reads it.
 */
public final class UpdateCheck {

    private static final String ENDPOINT = "https://stxaviers-auth.quackeditzofficial.workers.dev/api/app/version";
    private static final int TIMEOUT_MS = 6000;

    private UpdateCheck() {}

    /** Installed versionCode straight from the OS (0 if unknown). */
    public static int currentVersionCode(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionCode;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Installed versionName straight from the OS ("" if unknown). */
    public static String currentVersionName(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName == null ? "" : pi.versionName;
        } catch (Throwable t) {
            return "";
        }
    }

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

    public static UpdateInfo fetchLatest(Context ctx) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(ENDPOINT);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent",
                    "XavierDrive/" + currentVersionName(ctx) + " (Android)");
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
