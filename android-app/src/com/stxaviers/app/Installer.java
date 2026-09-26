package com.stxaviers.app;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Native APK installer using the framework PackageInstaller (no AndroidX
 * needed). commit() fires our receiver with STATUS_PENDING_USER_ACTION +
 * the system's own Install/Cancel confirm dialog; when the "install unknown
 * apps" permission is missing, that same system flow lands the user on the
 * per-app permission page — exactly the owner's spec.
 */
public final class Installer {

    public interface Listener {
        void onPendingUserAction(Intent confirmIntent);   // system dialog next
        void onSuccess();
        void onFailure(String message);
    }

    public static final String ACTION_STATUS =
            "com.stxaviers.app.UPDATE_STATUS";

    private Installer() {}

    /** Registers the status receiver; returns it so the caller can unregister. */
    public static BroadcastReceiver register(final Activity act, final Listener l) {
        BroadcastReceiver rx = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                        Integer.MIN_VALUE);
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm != null) l.onPendingUserAction(confirm);
                } else if (status == PackageInstaller.STATUS_SUCCESS) {
                    l.onSuccess();
                } else {
                    String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                    l.onFailure(msg);
                }
            }
        };
        if (Build.VERSION.SDK_INT >= 33) {
            act.registerReceiver(rx, new IntentFilter(ACTION_STATUS),
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            act.registerReceiver(rx, new IntentFilter(ACTION_STATUS));
        }
        return rx;
    }

    /** True when this app may request package installs (API 26+ check). */
    public static boolean canInstall(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            return c.getPackageManager().canRequestPackageInstalls();
        }
        return true;    // pre-O relies on the global "unknown sources" toggle
    }

    /** Opens the per-app "install unknown apps" permission page (API 26+). */
    public static void openPermPage(Activity a) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                a.startActivity(new Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + a.getPackageName())));
            } else {
                a.startActivity(new Intent(
                        android.provider.Settings.ACTION_SECURITY_SETTINGS));
            }
        } catch (Throwable ignored) {}
    }

    /** Streams the downloaded APK into a PackageInstaller session. */
    public static void install(Activity act, File apk) {
        try {
            PackageInstaller installer = act.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(
                            PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            int sessionId = installer.createSession(params);
            PackageInstaller.Session session = installer.openSession(sessionId);

            OutputStream out = session.openWrite("xd-update", 0, apk.length());
            InputStream in = new FileInputStream(apk);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            out.flush();
            session.fsync(out);
            out.close();

            Intent done = new Intent(ACTION_STATUS)
                    .setPackage(act.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(act, sessionId, done, flags);
            session.commit(pi.getIntentSender());
            session.close();
        } catch (Throwable e) {
            ToastCompat.show(act, "Install error: " + e.getMessage());
        }
    }

    /** Tiny toast shim (kept separate so LoginActivity stays lean). */
    static final class ToastCompat {
        static void show(Context c, String m) {
            try {
                android.widget.Toast.makeText(c, m,
                        android.widget.Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {}
        }
    }
}
