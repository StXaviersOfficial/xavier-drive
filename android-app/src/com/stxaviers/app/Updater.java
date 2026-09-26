package com.stxaviers.app;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-app APK downloader with TRUE progress, real byte counts and live speed
 * (rolling window). Replaces the old flow that handed the APK to the browser
 * (where the file went to the generic Downloads folder and nobody ever saw
 * it again). The file lands in the app's own external files dir — always
 * visible to us, no storage permission needed.
 */
public final class Updater {

    public interface Callback {
        void onProgress(long done, long total, float bytesPerSec);
        void onDone(File apk, long totalBytes);
        void onError(String message);
    }

    private static final int CONNECT_MS = 10000, READ_MS = 20000;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final Context ctx;

    public Updater(Context ctx) { this.ctx = ctx; }

    public void cancel() { cancelled.set(true); }

    /** Quick HEAD to learn the real APK size before the download starts. */
    public static long probeSize(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("HEAD");
            if (conn.getResponseCode() / 100 == 2) {
                long len = conn.getContentLength();
                if (len > 0) return len;
            }
        } catch (Throwable ignored) {
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
        }
        return -1L;
    }

    public File targetFile() {
        File dir = ctx.getExternalFilesDir("updates");
        if (dir == null) dir = new File(ctx.getCacheDir(), "updates");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, "xavierdrive-update.apk");
    }

    public void download(final String url, final Callback cb) {
        cancelled.set(false);
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                File out = targetFile();
                //noinspection ResultOfMethodCallIgnored
                if (out.exists()) out.delete();

                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(CONNECT_MS);
                conn.setReadTimeout(READ_MS);
                conn.setRequestProperty("User-Agent",
                        "XavierDrive/" + UpdateCheck.currentVersionName(ctx) + " (Android)");
                conn.setInstanceFollowRedirects(true);

                int status = conn.getResponseCode();
                if (status == HttpURLConnection.HTTP_MOVED_TEMP
                        || status == HttpURLConnection.HTTP_MOVED_PERM
                        || status == HttpURLConnection.HTTP_SEE_OTHER) {
                    String next = conn.getHeaderField("Location");
                    conn.disconnect();
                    conn = (HttpURLConnection) new URL(next).openConnection();
                    conn.setConnectTimeout(CONNECT_MS);
                    conn.setReadTimeout(READ_MS);
                    status = conn.getResponseCode();
                }
                if (status != 200) throw new RuntimeException("HTTP " + status);

                long total = conn.getContentLength();      // real size
                InputStream in = conn.getInputStream();
                OutputStream os = new FileOutputStream(out);

                // rolling speed window (~24 samples x UI cadence)
                final int WIN = 24;
                final long[] stamps = new long[WIN];
                final long[] bytes = new long[WIN];
                int slot = 0;
                long done = 0;
                long lastUi = 0L;

                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancelled.get()) {
                        os.close();
                        in.close();
                        //noinspection ResultOfMethodCallIgnored
                        out.delete();
                        return;
                    }
                    os.write(buf, 0, n);
                    done += n;

                    long now = System.currentTimeMillis();
                    int nextSlot = (slot + 1) % WIN;
                    long oldestStamp = stamps[nextSlot];
                    long oldestBytes = bytes[nextSlot];
                    stamps[slot] = now;
                    bytes[slot] = done;
                    slot = nextSlot;
                    float bps = 0f;
                    if (oldestStamp > 0 && now - oldestStamp > 300) {
                        bps = (done - oldestBytes) / ((now - oldestStamp) / 1000f);
                    }

                    if (now - lastUi > 120) {              // ~8 fps UI refresh
                        lastUi = now;
                        final long d = done, t = total;
                        final float s = bps;
                        postProgress(cb, d, t, s);
                    }
                }
                os.flush();
                os.close();
                in.close();
                postProgress(cb, done, total > 0 ? total : done, 0f);
                postDone(cb, out, total > 0 ? total : done);
            } catch (Throwable e) {
                if (!cancelled.get()) postError(cb, e.getMessage());
            } finally {
                if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
            }
        }, "xd-updater").start();
    }

    private void postProgress(final Callback cb, final long d, final long t, final float s) {
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.post(() -> cb.onProgress(d, t, s));
    }

    private void postDone(final Callback cb, final File f, final long total) {
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.post(() -> cb.onDone(f, total));
    }

    private void postError(final Callback cb, final String m) {
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.post(() -> cb.onError(m));
    }
}
