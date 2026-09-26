package com.stxaviers.app;

import android.content.Context;
import android.provider.Settings;

/** Tiny FX helpers shared by the animated views/activities. */
public final class Fx {

    private Fx() {}

    /** True when the user has system animations enabled (reduced-motion aware). */
    public static boolean animationsEnabled(Context c) {
        try {
            float s = Settings.Global.getFloat(
                    c.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
            return s != 0f;
        } catch (Throwable t) {
            return true;
        }
    }

    public static int color(Context c, int resId) {
        return c.getResources().getColor(resId, c.getTheme());
    }
}
