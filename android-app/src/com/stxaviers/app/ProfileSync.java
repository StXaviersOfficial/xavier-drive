package com.stxaviers.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import java.io.ByteArrayOutputStream;

/**
 * Profile identity sync (v1.1.2).
 *
 * The website stores each account's display name and photo in Firebase
 * through the Worker (/api/user/profile, website-docs/02). This helper
 * pulls that truth into the app so the avatar and name shown everywhere
 * match the website exactly — and pushing an edit from the app updates
 * the website too (same endpoint, same storage).
 *
 * The decoded avatar bitmap is cached statically (decoded once per
 * process); the raw data URL lives in XDState.photo, in-memory only.
 */
public final class ProfileSync {

    private static Bitmap sPhoto;
    private static final Handler H = new Handler(Looper.getMainLooper());

    private ProfileSync() {}

    /** The decoded profile photo, or null when the account has none. */
    public static Bitmap photo() { return sPhoto; }

    /** Drop the cached photo (sign-out / account switch). */
    public static void clear() { sPhoto = null; }

    public interface Done { void run(boolean changed); }

    /**
     * Fetch name + photo from the school server and merge into XDState.
     * Runs on a background thread; the callback lands on the UI thread.
     */
    public static void fetch(final Context c, final Done done) {
        new Thread(() -> {
            boolean changed = false;
            try {
                ApiClient.Resp r = ApiClient.request("GET",
                        "/api/user/profile");
                if (r.ok && r.json != null) {
                    XDState st = XDState.get(c);
                    String name = r.json.optString("name", "");
                    String photo = r.json.optString("photo", "");
                    if (!name.isEmpty() && !name.equals(st.name)) {
                        st.name = name;
                        st.save(c);
                        changed = true;
                    }
                    if (!photo.isEmpty()) {
                        if (!photo.equals(st.photo)) {
                            st.photo = photo;
                            changed = true;
                        }
                        Bitmap bmp = decode(photo);
                        if (bmp != null) sPhoto = bmp;
                    } else if (st.photo != null && !st.photo.isEmpty()) {
                        st.photo = "";
                        sPhoto = null;
                        changed = true;
                    }
                }
            } catch (Throwable ignored) {}
            final boolean ch = changed;
            H.post(() -> {
                if (done != null) done.run(ch);
            });
        }, "xd-profile-sync").start();
    }

    /**
     * Push a new name and/or photo. `bmp` may be null (name-only edit).
     * Returns true on success; runs on the CALLING thread (background).
     */
    public static boolean push(Context c, String name, Bitmap bmp) {
        try {
            org.json.JSONObject body = new org.json.JSONObject();
            if (name != null && name.trim().length() >= 2) {
                body.put("name", name.trim());
            }
            if (bmp != null) {
                body.put("photo", encode(bmp));
            }
            ApiClient.Resp r = ApiClient.requestJson("POST",
                    "/api/user/profile", body);
            if (r.ok) {
                XDState st = XDState.get(c);
                if (name != null && name.trim().length() >= 2) {
                    st.name = name.trim();
                }
                if (bmp != null) {
                    st.photo = encode(bmp);
                    sPhoto = bmp;
                }
                st.save(c);
                return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Downscale any picked image to a sane avatar size (≤ 512px). */
    public static Bitmap normalize(Bitmap in) {
        if (in == null) return null;
        int w = in.getWidth(), h = in.getHeight();
        int max = Math.max(w, h);
        if (max <= 512) return in;
        float scale = 512f / max;
        return Bitmap.createScaledBitmap(in,
                Math.max(1, Math.round(w * scale)),
                Math.max(1, Math.round(h * scale)), true);
    }

    /** data:image/...;base64,xxxx -> Bitmap (null on any trouble). */
    public static Bitmap decode(String dataUrl) {
        try {
            if (dataUrl == null) return null;
            int comma = dataUrl.indexOf(',');
            if (comma < 0) return null;
            byte[] bytes = Base64.decode(dataUrl.substring(comma + 1),
                    Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Bitmap -> a compact JPEG data URL the server accepts (< 2 MB). */
    public static String encode(Bitmap bmp) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 86, bos);
        return "data:image/jpeg;base64," + Base64.encodeToString(
                bos.toByteArray(), Base64.NO_WRAP);
    }
}
