package com.stxaviers.app;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Starfield: dark theme = twinkling drifting stars with touch parallax +
 * occasional shooting stars. Light theme = slow pastel bokeh bubbles that
 * breathe (a "day sky" reinterpretation of the same canvas).
 */
public class StarfieldView extends View {

    private static final class Star {
        float x, y, r, tw, ts, vx, vy, depth;
        int col;
    }

    private static final class Shoot {
        float x, y, dx, dy, life, maxLife;
    }

    private final List<Star> stars = new ArrayList<>();
    private final List<Shoot> shoots = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random rnd = new Random();
    private long start = -1L, lastT = -1L, nextShootAt = 2500L;
    private boolean dark, animate = true;
    private float px = 0.5f, py = 0.5f;          // parallax target (touch)
    private float pxc = 0.5f, pyc = 0.5f;        // eased current

    public StarfieldView(Context c) { super(c); init(); }
    public StarfieldView(Context c, AttributeSet a) { super(c, a); init(); }
    public StarfieldView(Context c, AttributeSet a, int s) { super(c, a, s); init(); }

    private void init() {
        int mask = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        dark = (mask == Configuration.UI_MODE_NIGHT_YES);
        animate = Fx.animationsEnabled(getContext());
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    private void seed(int w, int h) {
        stars.clear();
        float d = getResources().getDisplayMetrics().density;
        int n = dark ? Math.min(140, w * h / 9000) : Math.min(46, w * h / 26000);
        for (int i = 0; i < n; i++) {
            Star s = new Star();
            s.x = rnd.nextFloat() * w;
            s.y = rnd.nextFloat() * h;
            if (dark) {
                s.r = (rnd.nextFloat() * 1.4f + 0.4f) * d;
                s.tw = rnd.nextFloat() * 6.2832f;
                s.ts = 0.008f + rnd.nextFloat() * 0.02f;
                s.vx = (rnd.nextFloat() - 0.5f) * 0.12f * d;
                s.vy = (rnd.nextFloat() - 0.5f) * 0.12f * d;
                if (rnd.nextFloat() < 0.16f) {
                    int hue = 200 + rnd.nextInt(120);
                    s.col = Color.HSVToColor(new float[]{hue, 0.9f, 0.8f});
                } else {
                    s.col = 0xFFD6E1FF;
                }
            } else {
                s.r = (rnd.nextFloat() * 4f + 3f) * d;   // bokeh
                s.tw = rnd.nextFloat() * 6.2832f;
                s.ts = 0.004f + rnd.nextFloat() * 0.008f;
                s.vx = (rnd.nextFloat() - 0.5f) * 0.05f * d;
                s.vy = (rnd.nextFloat() - 0.5f) * 0.05f * d;
                int hue = rnd.nextBoolean() ? 218 : 46;
                s.col = Color.HSVToColor(new float[]{hue, 0.65f, rnd.nextFloat() * 0.25f + 0.7f});
            }
            s.depth = s.r / (2.2f * d);
            stars.add(s);
        }
    }

    public void setPointer(float rawX, float rawY) {
        // feed parallax from an external view (the ScrollView) in screen coords
        px = Math.max(0f, Math.min(1f, rawX / Math.max(1f, getWidth())));
        py = Math.max(0f, Math.min(1f, rawY / Math.max(1f, getHeight())));
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_MOVE
                || e.getAction() == MotionEvent.ACTION_DOWN) {
            px = e.getX() / Math.max(1f, getWidth());
            py = e.getY() / Math.max(1f, getHeight());
            return true;
        }
        return super.onTouchEvent(e);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        seed(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        long now = System.currentTimeMillis();
        if (start < 0) { start = now; lastT = now; }
        float dt = Math.min(64f, now - lastT) / 16f;   // normalized to 60fps steps
        lastT = now;
        float t = (now - start) / 1000f;

        pxc += (px - pxc) * 0.06f * dt;
        pyc += (py - pyc) * 0.06f * dt;

        for (Star s : stars) {
            if (animate) {
                s.tw += s.ts * dt;
                s.x += s.vx * dt;
                s.y += s.vy * dt;
                if (s.x < -6) s.x = w + 6; else if (s.x > w + 6) s.x = -6;
                if (s.y < -6) s.y = h + 6; else if (s.y > h + 6) s.y = -6;
            }
            float a;
            if (dark) {
                a = 0.35f + 0.65f * Math.abs((float) Math.sin(s.tw));
            } else {
                a = 0.10f + 0.16f * (0.5f + 0.5f * (float) Math.sin(s.tw));
            }
            float ox = (pxc - 0.5f) * 16f * s.depth;
            float oy = (pyc - 0.5f) * 16f * s.depth;
            paint.setShader(null);
            paint.setColor(s.col);
            paint.setAlpha((int) (a * 255f));
            if (dark) {
                canvas.drawCircle(s.x + ox, s.y + oy, s.r, paint);
            } else {
                // bokeh: soft edge via radial gradient
                paint.setShader(new RadialGradientCompat().make(s.x + ox, s.y + oy, s.r * 2.2f, s.col, (int) (a * 255f)));
                canvas.drawCircle(s.x + ox, s.y + oy, s.r * 2.2f, paint);
                paint.setShader(null);
            }
        }

        // shooting stars (both themes; rarer + softer in light)
        if (animate) {
            if (now - start > nextShootAt) {
                Shoot sh = new Shoot();
                sh.maxLife = dark ? 900f : 1100f;
                sh.life = 0f;
                double ang = Math.toRadians(16 + rnd.nextInt(14));
                float sp = (dark ? 14f : 9f) * getResources().getDisplayMetrics().density;
                sh.dx = (float) (Math.cos(ang) * sp);
                sh.dy = (float) (Math.sin(ang) * sp);
                sh.x = rnd.nextFloat() * w * 0.5f - 40f;
                sh.y = rnd.nextFloat() * h * 0.4f;
                shoots.add(sh);
                nextShootAt = now - start + 2600L + rnd.nextInt(3600);
            }
            float d = getResources().getDisplayMetrics().density;
            for (int i = shoots.size() - 1; i >= 0; i--) {
                Shoot sh = shoots.get(i);
                sh.life += dt * 16f;
                sh.x += sh.dx * dt;
                sh.y += sh.dy * dt;
                float p = sh.life / sh.maxLife;
                if (p >= 1f) { shoots.remove(i); continue; }
                float alpha = (float) Math.sin(p * Math.PI);   // fade in+out
                float len = 64f * d;
                int col = dark ? 0xFFFFFFFF : 0xFF2F6BFF;
                paint.setShader(new LinearGradient(
                        sh.x, sh.y, sh.x - sh.dx / (float) Math.hypot(sh.dx, sh.dy) * len,
                        sh.y - sh.dy / (float) Math.hypot(sh.dx, sh.dy) * len,
                        ((int) (alpha * 235f) << 24) | (col & 0xFFFFFF),
                        0x00000000, Shader.TileMode.CLAMP));
                paint.setStrokeWidth(dark ? 2f * d : 1.5f * d);
                paint.setStrokeCap(Paint.Cap.ROUND);
                canvas.drawLine(sh.x, sh.y,
                        sh.x - sh.dx / (float) Math.hypot(sh.dx, sh.dy) * len,
                        sh.y - sh.dy / (float) Math.hypot(sh.dx, sh.dy) * len, paint);
                paint.setShader(null);
            }
        }
        if (animate) invalidate();
    }

    /** tiny shim so the bokeh branch reads cleanly */
    private static final class RadialGradientCompat {
        android.graphics.RadialGradient make(float cx, float cy, float r, int col, int alpha) {
            return new android.graphics.RadialGradient(cx, cy, r,
                    ((alpha) << 24) | (col & 0xFFFFFF), 0x00000000, Shader.TileMode.CLAMP);
        }
    }
}
