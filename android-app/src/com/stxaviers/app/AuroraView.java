package com.stxaviers.app;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * Aurora background: four huge radial-gradient orbs drifting forever on
 * Lissajous paths. Dark theme -> additive screen blend (neon glow over
 * night sky). Light theme -> soft pastel washes at low alpha.
 */
public class AuroraView extends View {

    private static final class Orb {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float cx, cy, r;            // base center (fraction of view) + radius px
        float ax, ay;               // drift amplitudes (fraction)
        float sx, sy, pX, pY;       // speeds + phases
        int col;
    }

    private final Orb[] orbs = new Orb[4];
    private long start = -1L;
    private boolean dark;
    private boolean animate = true;

    public AuroraView(Context c) { super(c); init(); }
    public AuroraView(Context c, AttributeSet a) { super(c, a); init(); }
    public AuroraView(Context c, AttributeSet a, int s) { super(c, a, s); init(); }

    private void init() {
        int mask = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        dark = (mask == Configuration.UI_MODE_NIGHT_YES);
        animate = Fx.animationsEnabled(getContext());
        setLayerType(LAYER_TYPE_SOFTWARE, null); // banding fix + screen blend
        int[] cols = {
                0xFF4f8ef7, 0xFFa64ff7, 0xFFF74f7a, 0xFF22d3ee
        };
        if (!dark) cols = new int[]{
                0xFF7db4ff, 0xFFc9a6f7, 0xFFffa6bd, 0xFF7de9ff
        };
        float[][] geom = {          // cx, cy, ax, ay, r(frac)
                {0.12f, 0.10f, 0.10f, 0.08f, 0.62f},
                {0.88f, 0.16f, 0.09f, 0.10f, 0.54f},
                {0.20f, 0.92f, 0.08f, 0.10f, 0.58f},
                {0.80f, 0.88f, 0.10f, 0.07f, 0.50f},
        };
        float[][] speed = {
                {0.043f, 0.031f, 0.0f, 1.1f},
                {0.037f, 0.046f, 2.1f, 0.4f},
                {0.029f, 0.041f, 4.2f, 3.0f},
                {0.051f, 0.024f, 1.0f, 5.1f},
        };
        for (int i = 0; i < 4; i++) {
            Orb o = new Orb();
            o.cx = geom[i][0]; o.cy = geom[i][1];
            o.ax = geom[i][2]; o.ay = geom[i][3];
            o.r = geom[i][4];
            o.sx = speed[i][0]; o.sy = speed[i][1];
            o.pX = speed[i][2]; o.pY = speed[i][3];
            o.col = cols[i];
            if (dark) o.paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
            orbs[i] = o;
        }
        if (dark) {
            // night base wash
            setBackgroundColor(0xFF070a18);
        } else {
            setBackgroundColor(0xFFEEF2FB);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (start < 0) start = System.currentTimeMillis();
        float t = (System.currentTimeMillis() - start) / 1000f;

        float min = Math.min(w, h);
        for (int i = 0; i < orbs.length; i++) {
            Orb o = orbs[i];
            float x = (o.cx + o.ax * (float) Math.sin(t * o.sx * 2f * Math.PI + o.pX)) * w;
            float y = (o.cy + o.ay * (float) Math.cos(t * o.sy * 2f * Math.PI + o.pY)) * h;
            float r = o.r * min * (1f + 0.08f * (float) Math.sin(t * 0.35f * 2f * Math.PI + i));
            int alpha = dark ? 0xB3 : 0x5C;
            int c = (alpha << 24) | (o.col & 0xFFFFFF);
            o.paint.setShader(new RadialGradient(x, y, r,
                    c, (o.col & 0xFFFFFF), Shader.TileMode.CLAMP));
            canvas.drawCircle(x, y, r, o.paint);
        }
        if (animate) invalidate();
    }
}
