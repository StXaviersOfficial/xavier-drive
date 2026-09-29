package com.stxaviers.app;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.LinearLayout;

/**
 * Builds a kinetic display-text row: one ShimmerTextView per letter, each
 * with its own rainbow-shimmer phase, popping in with a staggered
 * overshoot entrance. Used for the splash "XAVIERDRIVE" (Orbitron) and
 * the login "XavierDrive" (Bungee) titles.
 */
public final class TypefaceFontHelper {

    public static void addKineticLetters(Context ctx, LinearLayout row, String text,
                                         Typeface tf, float sizeSp, float letterSpacing,
                                         int colorInt, long startDelayMs) {
        if (row == null || text == null) return;
        OvershootInterpolator over = new OvershootInterpolator(0.85f);
        DecelerateInterpolator dec = new DecelerateInterpolator();
        int idx = 0;
        for (char ch : text.toCharArray()) {
            final ShimmerTextView tv = new ShimmerTextView(ctx);
            tv.setText(String.valueOf(ch));
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
            tv.setLetterSpacing(letterSpacing);
            tv.setTypeface(tf);
            tv.setTextColor(colorInt);
            tv.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            tv.setPhase(idx * 0.14f);
            row.addView(tv);

            tv.setAlpha(0f);
            tv.setTranslationY(26f);
            tv.setScaleX(0.6f);
            tv.setScaleY(0.6f);
            tv.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                    .setDuration(620).setStartDelay(startDelayMs + idx * 55)
                    .setInterpolator(idx % 2 == 0 ? over : dec).start();
            idx++;
        }
    }

    private TypefaceFontHelper() {}
}
