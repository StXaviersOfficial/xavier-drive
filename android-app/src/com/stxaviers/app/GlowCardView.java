package com.stxaviers.app;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/**
 * Glassmorphism card, cranked up. Draws:
 *   1. rotating conic-gradient border (blue -> violet -> pink -> gold -> mint)
 *   2. translucent glass fill + hairline
 *   3. an endless diagonal SHINE band sweeping across the glass
 *
 * v1.0.6: shineMaxFraction — the shine band can be confined to the TOP part
 * of the card. On the login card the Google button lives in the lower half:
 * the sweep flashing over the official G is exactly what the owner read as
 * "the G is too bright, not normal colours", so the login card confines the
 * shine to the welcome-text area and it NEVER touches the button.
 *
 * Optional XML attrs (see attrs.xml):
 *   cardFill / cardBorder  — force a palette (the splash update panel is
 *                            always-dark, so it forces dark glass colors)
 *   cardRadius             — corner radius
 * The 3D tilt is applied by the host activity (rotationX/Y on this view).
 */
public class GlowCardView extends FrameLayout {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint shine = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final RectF rect = new RectF();
    private final RectF rectInner = new RectF();
    private final Matrix matrix = new Matrix();

    private int fillColor = 0xCC151A2E;
    private int lineColor = 0x14FFFFFF;
    /** v1.0.7: wall-clock spin — the old ValueAnimator froze under
     *  Battery Saver's animator scale 0 (still border ring, still shine). */
    private boolean spinning = false;
    private long spinT0 = 0L;
    private float angleDeg = 0f;
    private boolean borderOn = true;
    private boolean shineOn = true;
    private float radius = 24f;
    /** 1f = shine sweeps the full card; <1f = only the top fraction. */
    private float shineMaxFraction = 1f;

    private final Runnable spinTick = new Runnable() {
        @Override
        public void run() {
            if (!spinning) return;
            angleDeg = ((SystemClock.elapsedRealtime() - spinT0) % 6000L)
                    * 360f / 6000f;
            invalidate();
            postOnAnimation(this);
        }
    };

    public GlowCardView(Context c) { super(c); init(null); }
    public GlowCardView(Context c, AttributeSet a) { this(c, a, 0); }
    public GlowCardView(Context c, AttributeSet a, int s) { super(c, a, s); init(a); }

    private void init(AttributeSet attrs) {
        setWillNotDraw(false);
        float d = getResources().getDisplayMetrics().density;
        radius = 26f * d;
        ring.setStrokeWidth(2.5f * d);
        ring.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1f * d);
        line.setStyle(Paint.Style.STROKE);
        fillColor = Fx.color(getContext(), R.color.login_card);
        lineColor = Fx.color(getContext(), R.color.login_card_border);

        if (attrs != null) {
            TypedArray ta = getContext().obtainStyledAttributes(attrs, R.styleable.GlowCardView);
            fillColor = ta.getColor(R.styleable.GlowCardView_cardFill, fillColor);
            lineColor = ta.getColor(R.styleable.GlowCardView_cardBorder, lineColor);
            radius = ta.getDimension(R.styleable.GlowCardView_cardRadius, radius);
            ta.recycle();
        }
    }

    public void setBorderSpin(boolean on) {
        this.borderOn = on;
        if (!on) stopSpin();
        else startSpin();
        invalidate();
    }

    /**
     * Confine the shine sweep to the top fraction of the card (1f = whole
     * card). The login card uses ~0.45f: the band plays over the welcome
     * text and can never wash out the Google button / its G below.
     */
    public void setShineMaxFraction(float f) {
        this.shineMaxFraction = Math.max(0f, Math.min(1f, f));
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (borderOn) startSpin();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopSpin();
        super.onDetachedFromWindow();
    }

    private void startSpin() {
        if (spinning) return;
        spinning = true;
        spinT0 = SystemClock.elapsedRealtime()
                - (long) (angleDeg / 360f * 6000f);   // resume where it left
        postOnAnimation(spinTick);
    }

    private void stopSpin() {
        spinning = false;
        removeCallbacks(spinTick);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float pad = ring.getStrokeWidth() / 2f + 0.5f;
        rect.set(pad, pad, getWidth() - pad, getHeight() - pad);
        rectInner.set(pad + ring.getStrokeWidth(), pad + ring.getStrokeWidth(),
                getWidth() - pad - ring.getStrokeWidth(),
                getHeight() - pad - ring.getStrokeWidth());
        boolean anim = Fx.animationsEnabled(getContext());

        // 1. rotating conic border ring
        if (borderOn && anim) {
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

        // 4. diagonal shine band sweeping across the glass forever — but
        //    never into the region below shineMaxFraction (login: the button)
        if (shineOn && anim && getWidth() > 4) {
            float w = getWidth(), hgt = getHeight();
            float bandW = w * 0.34f;
            // one sweep every spin: angle 0..360 -> x from -bandW*2 to w+bandW*2
            float x = -bandW * 2f + (angleDeg / 360f) * (w + bandW * 4f);
            float a = Math.max(0f, 1f - Math.abs(x - w / 2f) / (w * 0.85f)); // soft in/out
            if (a > 0.02f) {
                int alpha = (int) (46f * a);
                shine.setShader(new LinearGradient(
                        x, 0, x + bandW, hgt,
                        0x00FFFFFF, (alpha << 24) | 0x00DCEAFF, Shader.TileMode.CLAMP));
                canvas.save();
                canvas.clipRect(rectInner);
                if (shineMaxFraction < 1f) {
                    canvas.clipRect(0f, 0f, getWidth(),
                            getHeight() * shineMaxFraction);
                }
                canvas.drawRoundRect(rectInner, radius, radius, shine);
                canvas.restore();
            }
        }
    }
}
