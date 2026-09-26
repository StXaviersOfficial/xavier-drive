package com.stxaviers.app;

import android.content.Context;
import android.provider.Settings;

/** Tiny FX helpers shared by the animated views/activities. */
public final class Fx {

    /** Cached per-process — Settings.Global hits the content resolver, and
     *  ShimmerTextView was consulting it on every frame draw. */
    private static Boolean animCache;

    private Fx() {}

    /** True when the user has system animations enabled (reduced-motion aware). */
    public static boolean animationsEnabled(Context c) {
        if (animCache == null) {
            try {
                float s = Settings.Global.getFloat(
                        c.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
                animCache = (s != 0f);
            } catch (Throwable t) {
                animCache = Boolean.TRUE;
            }
        }
        return animCache;
    }

    public static int color(Context c, int resId) {
        return c.getResources().getColor(resId, c.getTheme());
    }
}
