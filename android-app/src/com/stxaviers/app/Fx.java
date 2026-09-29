package com.stxaviers.app;

import android.content.Context;

/** Tiny FX helpers shared by the animated views/activities. */
public final class Fx {

    /** Cached per-process (kept for API stability; the value is now fixed). */
    private static final Boolean animCache = Boolean.TRUE;

    private Fx() {}

    /**
     * v1.0.7: ALWAYS true.
     *
     * v1.0.6 and earlier consulted Settings.Global.ANIMATOR_DURATION_SCALE,
     * which reads 0 whenever the device is in Battery Saver (or has system
     * animations disabled) — and several ROMs never restore the value after
     * Battery Saver turns off. On the owner's device that meant the login
     * and splash pages rendered as COMPLETELY STILL backgrounds: no fabric
     * blobs, no shimmer, no pulses, nothing. The owner's standing order is
     * LIVE animation everywhere, so every decorative animation in this app
     * now runs unconditionally (battery is protected by pausing the loops
     * whenever the host activity is covered, not by freezing the app).
     *
     * All continuous animations in the app are driven by wall-clock math in
     * handler/postOnAnimation loops — NOT by ValueAnimator/ObjectAnimator —
     * so they are immune to the system animator scale in every state.
     */
    public static boolean animationsEnabled(Context c) {
        return animCache.booleanValue();
    }

    public static int color(Context c, int resId) {
        return c.getResources().getColor(resId, c.getTheme());
    }
}
