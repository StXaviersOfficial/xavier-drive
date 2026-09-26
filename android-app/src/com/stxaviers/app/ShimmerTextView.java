package com.stxaviers.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.animation.LinearInterpolator;
import android.widget.TextView;

/**
 * Kinetic gradient text — the login title letters. Each letter is one
 * ShimmerTextView with its own phase offset; a linear rainbow gradient
 * sweeps through the glyph forever (the web login's navLogoGradient,
 * recoded natively). Plain android.widget.TextView — no appcompat needed.
 */
public class ShimmerTextView extends TextView {

    private static final int[] RAINBOW = {
            0xFF4FF7B0, 0xFF4F8EF7, 0xFFA64FF7, 0xFFF74F7A, 0xFFF7C44F, 0xFF4FF7B0
    };

    private ValueAnimator sweep;
    private LinearGradient gradient;
    private final Matrix matrix = new Matrix();
    private float phase = 0f;
    private boolean shimmerOn = true;
    private int lastW = -1;

    public ShimmerTextView(Context c) { super(c); }
    public ShimmerTextView(Context c, AttributeSet a) { super(c, a); }
    public ShimmerTextView(Context c, AttributeSet a, int s) { super(c, a, s); }

    public void setPhase(float p) { this.phase = p; }

    public void setShimmer(boolean on) {
        this.shimmerOn = on;
        if (!on && sweep != null) {
            sweep.cancel();
            sweep = null;
            getPaint().setShader(null);
            invalidate();
        } else if (on && sweep == null) {
            startSweep();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (shimmerOn && Fx.animationsEnabled(getContext())) startSweep();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (sweep != null) { sweep.cancel(); sweep = null; }
        super.onDetachedFromWindow();
    }

    private void startSweep() {
        if (sweep != null) return;
        sweep = ValueAnimator.ofFloat(0f, 1f);
        sweep.setDuration(5200L);
        sweep.setInterpolator(new LinearInterpolator());
        sweep.setRepeatCount(ValueAnimator.INFINITE);
        sweep.addUpdateListener(a -> {
            applyShader((Float) a.getAnimatedValue());
            invalidate();
        });
        sweep.start();
    }

    private void applyShader(float t) {
        int w = Math.max(1, getWidth());
        if (w != lastW || gradient == null) {
            gradient = new LinearGradient(0, 0, w * 2f, 0, RAINBOW, null,
                    Shader.TileMode.CLAMP);
            lastW = w;
        }
        matrix.reset();
        // travel 3 widths per cycle, offset by this letter's phase
        matrix.setTranslate(-w * 2f + (t * 3f * w) + phase * w, 0f);
        gradient.setLocalMatrix(matrix);
        getPaint().setShader(gradient);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (shimmerOn && Fx.animationsEnabled(getContext())) {
            if (sweep == null) startSweep();
        } else {
            getPaint().setShader(null);
        }
        super.onDraw(canvas);
    }
}
