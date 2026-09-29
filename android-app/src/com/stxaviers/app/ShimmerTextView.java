package com.stxaviers.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.widget.TextView;

/**
 * Kinetic gradient text — the login title letters. Each letter is one
 * ShimmerTextView with its own phase offset; a linear rainbow gradient
 * sweeps through the glyph forever (the web login's navLogoGradient,
 * recoded natively). Plain android.widget.TextView — no appcompat needed.
 *
 * v1.0.7: the sweep used to be a ValueAnimator — under Battery Saver
 * (system animator scale 0) it froze solid (or flickered frame-to-frame),
 * which contributed to the "completely still page" report. The sweep is
 * now pure wall-clock math driven by postOnAnimation: nothing the system
 * does to animator scales can stop, freeze, or speed it up.
 */
public class ShimmerTextView extends TextView {

    private static final int[] RAINBOW = {
            0xFF4FF7B0, 0xFF4F8EF7, 0xFFA64FF7, 0xFFF74F7A, 0xFFF7C44F, 0xFF4FF7B0
    };

    /** One full rainbow travel, milliseconds. */
    private static final long PERIOD_MS = 5200L;

    private LinearGradient gradient;
    private final Matrix matrix = new Matrix();
    private float phase = 0f;
    private boolean shimmerOn = true;
    private int lastW = -1;
    private long t0 = -1L;
    private boolean running = false;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running || !shimmerOn) return;
            applyShader(sweepT());
            invalidate();
            postOnAnimation(this);
        }
    };

    public ShimmerTextView(Context c) { super(c); }
    public ShimmerTextView(Context c, AttributeSet a) { super(c, a); }
    public ShimmerTextView(Context c, AttributeSet a, int s) { super(c, a, s); }

    public void setPhase(float p) { this.phase = p; }

    public void setShimmer(boolean on) {
        this.shimmerOn = on;
        if (!on) {
            stopSweep();
            getPaint().setShader(null);
            invalidate();
        } else {
            startSweep();
        }
    }

    private float sweepT() {
        if (t0 < 0L) t0 = SystemClock.elapsedRealtime();
        // progress in [0,1) around the sweep, wall-clock anchored
        return ((SystemClock.elapsedRealtime() - t0) % PERIOD_MS)
                / (float) PERIOD_MS;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (shimmerOn) startSweep();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopSweep();
        super.onDetachedFromWindow();
    }

    private void startSweep() {
        if (running) return;
        running = true;
        postOnAnimation(tick);
    }

    private void stopSweep() {
        running = false;
        removeCallbacks(tick);
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
        if (shimmerOn) {
            if (!running) startSweep();
            applyShader(sweepT());   // stay correct even on a stale frame
        } else {
            getPaint().setShader(null);
        }
        super.onDraw(canvas);
    }
}
