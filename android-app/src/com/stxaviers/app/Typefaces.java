package com.stxaviers.app;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;

import java.util.HashMap;

/**
 * Cached loader for the app's typefaces (v2.0.0).
 *
 * TWO families, both instanced by scripts/v2fonts/make_fonts.py from the
 * Google Fonts variable TTFs (OFL) with true lowercase verified:
 *
 *   Outfit  (res/font/outfit_*)     — headings, titles, the school name.
 *                                     Geometric but calm — "better than the
 *                                     basic default, still normal looking".
 *   Inter   (res/font/inter_*)      — body, labels, lists, menus.
 *
 * The legacy display fonts (splash / login brand moments) stay in
 * assets/fonts and load exactly as before.
 *
 * res/font resources apply automatically to XML android:fontFamily on
 * API 26+; on API 24-25 these helpers fall back to the platform default
 * (graceful, no crash).
 */
public final class Typefaces {

    // display faces (assets — splash/login only)
    public static final String ORBITRON_XB = "OrbitronExtraBold.ttf";
    public static final String ORBITRON_MED = "OrbitronMedium.ttf";
    public static final String AUDIOWIDE = "Audiowide.ttf";
    public static final String RIGHTEOUS = "Righteous.ttf";
    public static final String MICHROMA = "Michroma.ttf";
    public static final String MONOTON = "Monoton-Regular.ttf";
    public static final String RUBIK_PUDDLES = "RubikPuddles-Regular.ttf";
    public static final String VT323 = "VT323-Regular.ttf";
    public static final String SHRIKHAND = "Shrikhand-Regular.ttf";
    public static final String RUBIK_GLITCH = "RubikGlitch-Regular.ttf";
    public static final String POPPINS = "Poppins-SemiBold.ttf";

    private static final HashMap<String, Typeface> CACHE = new HashMap<>();

    public static Typeface get(Context c, String name) {
        Typeface t = CACHE.get(name);
        if (t == null) {
            try {
                t = Typeface.createFromAsset(c.getAssets(), "fonts/" + name);
            } catch (Throwable ignored) {
            }
            if (t == null) t = Typeface.DEFAULT;
            CACHE.put(name, t);
        }
        return t;
    }

    // ── v2.0.0 text faces (res/font — cached per resource id) ───────────

    /** Outfit SemiBold — headings, screen titles, the school name. */
    public static Typeface outfitSemi(Context c) {
        return res(c, R.font.outfit_semibold);
    }

    /** Outfit Medium — subtitles and emphasized labels. */
    public static Typeface outfitMedium(Context c) {
        return res(c, R.font.outfit_medium);
    }

    /** Inter Regular — body text. */
    public static Typeface interRegular(Context c) {
        return res(c, R.font.inter_regular);
    }

    /** Inter Medium — list labels, chips, nav captions. */
    public static Typeface interMedium(Context c) {
        return res(c, R.font.inter_medium);
    }

    private static Typeface res(Context c, int id) {
        String key = "@res/" + id;
        Typeface t = CACHE.get(key);
        if (t == null) {
            t = Typeface.DEFAULT;
            if (Build.VERSION.SDK_INT >= 26) {
                try {
                    t = c.getResources().getFont(id);
                } catch (Throwable ignored) {}
            }
            CACHE.put(key, t);
        }
        return t;
    }

    private Typefaces() {}
}
