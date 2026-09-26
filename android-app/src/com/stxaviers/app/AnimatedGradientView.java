package com.stxaviers.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * Cinematic animated gradient background for the splash screen.
 * Two hue-shifting diagonal gradients cross-faded over a deep base —
 * pure Canvas paint loop, no assets, cheap (~2 gradients per frame).
 */
public class AnimatedGradientView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] hsv = new float[]{224f, 0.78f, 0.16f}; // deep blue base
    private long start = -1L;
    private static final long CYCLE_MS = 9000L;

    public AnimatedGradientView(Context c) { super(c); init(); }
    public AnimatedGradientView(Context c, AttributeSet a) { super(c, a); init(); }
    public AnimatedGradientView(Context c, AttributeSet a, int s) { super(c, a, s); init(); }

    private void init() {
        setLayerType(LAYER_TYPE_SOFTWARE, null); // smooth gradient banding fix
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (start < 0) start = System.currentTimeMillis();
        float t = ((System.currentTimeMillis() - start) % CYCLE_MS) / (float) CYCLE_MS; // 0..1

        // base deep gradient (static anchor color)
        int c0 = Color.HSVToColor(hsv);

        // two accent hues orbiting the base
        float hue1 = 224f + 26f * (float) Math.sin(t * Math.PI * 2);
        float hue2 = 224f + 40f * (float) Math.cos(t * Math.PI * 2 + 1.2);
        int a1 = Color.HSVToColor(new float[]{(hue1 + 360) % 360, 0.75f, 0.26f});
        int a2 = Color.HSVToColor(new float[]{(hue2 + 360) % 360, 0.62f, 0.30f});

        // main diagonal gradient
        paint.setShader(new LinearGradient(0, 0, w, h,
                new int[]{c0, a1, c0, a2, c0},
                new float[]{0f, 0.22f, 0.5f, 0.78f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);

        // moving light sweep (soft bright band travelling diagonally)
        float sx = (t * 2f - 0.5f) * w;
        int band = Color.HSVToColor(new float[]{226f, 0.35f, 0.34f});
        paint.setShader(new LinearGradient(sx - w * 0.35f, 0, sx + w * 0.35f, h,
                new int[]{0x00000000, (0x2E << 24) | (band & 0xFFFFFF), 0x00000000},
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);

        invalidate(); // continuous animation
    }
}
