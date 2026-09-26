package com.stxaviers.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;

/**
 * Glassmorphism card with an animated conic-gradient border (the web login's
 * rotating conic border, recoded natively). Draws:
 *   1. translucent glass fill (theme color login_card)
 *   2. 1dp hairline border
 *   3. rotating sweep-gradient ring (blue -> violet -> pink -> gold -> blue)
 * The 3D tilt is applied by the activity (rotationX/Y on this view).
 */
public class GlowCardView extends FrameLayout {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF rectInner = new RectF();
    private final Matrix matrix = new Matrix();

    private int fillColor = 0xCC151A2E;
    private int lineColor = 0x14FFFFFF;
    private ValueAnimator spin;
    private float angleDeg = 0f;
    private boolean borderOn = true;
    private float radius = 24f;

    public GlowCardView(Context c) { super(c); init(); }
    public GlowCardView(Context c, AttributeSet a) { this(c, a, 0); }
    public GlowCardView(Context c, AttributeSet a, int s) { super(c, a, s); init(); }

    private void init() {
        setWillNotDraw(false);
        float d = getResources().getDisplayMetrics().density;
        radius = 24f * d;
        ring.setStrokeWidth(2.5f * d);
        ring.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1f * d);
        line.setStyle(Paint.Style.STROKE);
        fillColor = Fx.color(getContext(), R.color.login_card);
        lineColor = Fx.color(getContext(), R.color.login_card_border);
    }

    public void setBorderSpin(boolean on) {
        this.borderOn = on;
        if (!on && spin != null) { spin.cancel(); spin = null; }
        if (on && spin == null && Fx.animationsEnabled(getContext())) startSpin();
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (borderOn && Fx.animationsEnabled(getContext())) startSpin();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (spin != null) { spin.cancel(); spin = null; }
        super.onDetachedFromWindow();
    }

    private void startSpin() {
        if (spin != null) return;
        spin = ValueAnimator.ofFloat(0f, 360f);
        spin.setDuration(6000L);
        spin.setInterpolator(new LinearInterpolator());
        spin.setRepeatCount(ValueAnimator.INFINITE);
        spin.addUpdateListener(a -> {
            angleDeg = (Float) a.getAnimatedValue();
            invalidate();
        });
        spin.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float pad = ring.getStrokeWidth() / 2f + 0.5f;
        rect.set(pad, pad, getWidth() - pad, getHeight() - pad);
        rectInner.set(pad + ring.getStrokeWidth(), pad + ring.getStrokeWidth(),
                getWidth() - pad - ring.getStrokeWidth(),
                getHeight() - pad - ring.getStrokeWidth());

        // 1. rotating conic border ring
        if (borderOn && Fx.animationsEnabled(getContext())) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            SweepGradient g = new SweepGradient(cx, cy, new int[]{
                    0xFF4F8EF7, 0xFFA64FF7, 0xFFF74F7A, 0xFFF7C44F, 0xFF4FF7B0, 0xFF4F8EF7
            }, null);
            matrix.reset();
            matrix.postRotate(angleDeg, cx, cy);
            g.setLocalMatrix(matrix);
            ring.setShader(g);
            ring.setAlpha(205);
            canvas.drawRoundRect(rect, radius, radius, ring);
        }

        // 2. glass fill inset behind children
        fill.setColor(fillColor);
        canvas.drawRoundRect(rectInner, radius, radius, fill);

        // 3. hairline
        line.setColor(lineColor);
        canvas.drawRoundRect(rectInner, radius, radius, line);
    }
}
