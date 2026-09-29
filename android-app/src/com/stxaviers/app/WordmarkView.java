package com.stxaviers.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.widget.TextView;

/**
 * The XAVIERDRIVE wordmark (v1.1.2) — the app's name in the home top bar.
 *
 * The owner asked for the title "in a really good animated font". The type
 * is Outfit SemiBold (the calm geometric heading face); the animation is a
 * slow light sweep that travels across the letters every few seconds —
 * visible life without bouncing or glow. Colours resolve through @color
 * refs so light and dark both stay correct, and the sweep pauses whenever
 * the view is detached or the window is hidden.
 *
 * Technique: the base glyphs draw normally, then the same glyphs draw into
 * a layer that is masked (SRC_IN) by a moving gradient band — the band
 * colours only the letters it crosses.
 */
public class WordmarkView extends TextView {

    private static final float CYCLE_MS = 3800f;
    private static final float BAND = 0.42f;   // band width (fraction of width)

    private long start;
    private final Paint bandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public WordmarkView(Context c) { super(c); start = now(); }

    public WordmarkView(Context c, AttributeSet a) { super(c, a); start = now(); }

    public WordmarkView(Context c, AttributeSet a, int s) {
        super(c, a, s); start = now();
    }

    private static long now() { return System.currentTimeMillis(); }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        start = now();
        postInvalidateOnAnimation();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) {
            start = now();
            postInvalidateOnAnimation();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);   // base ink glyphs

        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0 || !isShown()) return;

        // t runs -0.6 .. 1.6 so the band fully enters and exits
        float t = ((now() - start) % CYCLE_MS) / CYCLE_MS * 2.2f - 0.6f;
        if (t < -0.55f || t > 1.55f) {
            postInvalidateOnAnimation();
            return;
        }

        float cx = t * w;
        float half = BAND * w * 0.5f;

        int ink = Fx.color(getContext(), R.color.home_ink);
        int ca = Fx.color(getContext(), R.color.home_brand);
        int cb = Fx.color(getContext(), R.color.accent2);

        bandPaint.setShader(new LinearGradient(
                cx - half, 0f, cx + half, 0f,
                new int[]{ink & 0x00FFFFFF, ca, cb, ink & 0x00FFFFFF},
                new float[]{0f, 0.45f, 0.55f, 1f},
                Shader.TileMode.CLAMP));
        bandPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));

        int save = canvas.saveLayer(0f, 0f, w, h, null);
        if (getLayout() != null) {
            canvas.save();
            canvas.translate(getTotalPaddingLeft(), getTotalPaddingTop());
            getLayout().draw(canvas);          // glyph mask (layer-local)
            canvas.restore();
        }
        canvas.drawRect(cx - half - 4f, 0f, cx + half + 4f, h, bandPaint);
        canvas.restoreToCount(save);

        postInvalidateOnAnimation();
    }
}
