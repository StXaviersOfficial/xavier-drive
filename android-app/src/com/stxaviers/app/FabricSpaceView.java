package com.stxaviers.app;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.Xfermode;
import android.os.Build;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * FabricSpaceView — the animated "space fabric" background. v1.0.5.
 *
 * WHY v1.0.4 LOOKED LIKE A FIXED BACKGROUND ON DEVICE: the view forced
 * LAYER_TYPE_SOFTWARE, so every full-screen frame (6 radial gradients +
 * 4 linear ribbons + ~150 stars) was rasterised on the CPU — on a phone
 * that drops to a frame every few seconds, which reads as "plain fixed
 * background". The software layer is GONE (hardware rendering, DITHER
 * flags keep the gradients smooth).
 *
 * Splash mode is now properly LOUD (the owner's "moving mixing colours"
 * order):
 *   API 33+   a full-screen AGSL RuntimeShader — domain-warped fBm
 *             noise driving three hues that rotate through the whole
 *             colour wheel while the warp field itself flows: real
 *             fluid colour mixing, one GPU quad, butter at 60fps.
 *             Falls back to the canvas engine if the shader can't
 *             compile on some driver.
 *   API < 33  the canvas nebula engine, cranked up: blob alphas nearly
 *             doubled, hues rotating through the FULL wheel (not a
 *             ±30° wobble), faster + wider motion, SCREEN blending so
 *             colours mix luminously, brighter stars, more shooting
 *             stars.
 *
 * Login mode is unchanged (calm, theme-aware) — it just inherits the
 * hardware-layer fix, so it finally animates smoothly on-device too.
 *
 * Every position stays a pure function of time — no keyframes, no
 * restarts, no snapping, so it can never jitter or "shake".
 */
public class FabricSpaceView extends View {

    public static final int MODE_LOGIN = 0;
    public static final int MODE_SPLASH = 1;

    // ── tunables ─────────────────────────────────────────────────────────
    private static final int STAR_MAX = 150;

    private static final class Blob {
        float ax, ay;          // anchor (fraction of screen)
        float mrx, mry;        // motion radius (fraction of screen)
        float sx, sy, ss;      // angular speeds (rad/s)
        float px, py, ps;      // phases
        float radF;            // radius as fraction of min(w,h)
        float alpha;
        int color;             // login: fixed; splash: ignored (hue cycling)
        float hue, hueSpeed;   // splash: continuous full-wheel rotation
    }

    private static final class Ribbon {
        float yF;              // vertical anchor (fraction)
        float ampDp;           // wave amplitude dp
        float lenDp;           // wavelength dp
        float speed;           // rad/s
        float phase;
        float thickDp;         // ribbon thickness dp
        float alpha;
        int color;
        float hue, hueSpeed;   // splash cycling
    }

    private static final class Star {
        float x, y, r, tw, ts, vx, vy, depth;
        int col;
    }

    private static final class Shoot {
        float x, y, dx, dy, life, maxLife;
    }

    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint blobPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint ribbonPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint starPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint shootPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint vignettePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint shaderPaint = new Paint();
    private final Path ribbonPath = new Path();
    private static final Xfermode SCREEN =
            new PorterDuffXfermode(PorterDuff.Mode.SCREEN);

    private final Blob[] blobs = new Blob[6];
    private final Ribbon[] ribbons = new Ribbon[4];
    private final List<Star> stars = new ArrayList<>();
    private final List<Shoot> shoots = new ArrayList<>();
    private final Random rnd = new Random();

    private int mode = MODE_LOGIN;
    private boolean dark = true;
    private boolean animate = true;
    /** v1.0.6: host activities pause the 60fps loop while covered (account
     *  picker / legal viewer) — otherwise the full-screen canvas keeps
     * repainting behind the system UI and burns battery. */
    private boolean paused = false;
    private float dp = 2.5f;

    private long start = -1L, lastT = -1L, nextShootAt = 2000L;
    private float px = 0.5f, py = 0.5f;      // parallax target
    private float pxc = 0.5f, pyc = 0.5f;    // eased current

    // AGSL fluid path (splash, API 33+)
    private RuntimeShader fluidShader;
    private boolean shaderOk = false;

    public FabricSpaceView(Context c) { super(c); init(null); }
    public FabricSpaceView(Context c, AttributeSet a) { super(c, a); init(a); }
    public FabricSpaceView(Context c, AttributeSet a, int s) { super(c, a, s); init(a); }

    private void init(AttributeSet attrs) {
        if (attrs != null) {
            TypedArray ta = getContext().obtainStyledAttributes(attrs, R.styleable.FabricSpaceView);
            mode = ta.getInt(R.styleable.FabricSpaceView_fabricMode, MODE_LOGIN);
            ta.recycle();
        }
        int mask = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        // v1.0.9: the update checker is theme-aware now — light mode gets a
        // soft pastel fluid (owner order: light + dark are BOTH first-class).
        // The AGSL dark-cinema shader only runs in night mode; light mode
        // uses the canvas engine with pastel hues + SRC_OVER blending.
        dark = (mask == Configuration.UI_MODE_NIGHT_YES);
        animate = Fx.animationsEnabled(getContext());
        dp = getResources().getDisplayMetrics().density;
        // NOTE: no LAYER_TYPE_SOFTWARE anymore — it forced the whole
        // full-screen canvas onto the CPU and froze the animation on
        // device. Hardware rendering + DITHER flags keep gradients smooth.
        if (mode == MODE_SPLASH && dark && Build.VERSION.SDK_INT >= 33) {
            try {
                fluidShader = new RuntimeShader(FLUID_AGSL);
                fluidShader.setFloatUniform("iSeed", rnd.nextFloat() * 97.0f);
                shaderOk = true;
            } catch (Throwable t) {
                shaderOk = false;   // some driver rejected it — canvas path
            }
        }
        seedPalette();
    }

    /** Build the blob/ribbon palette for the current mode + theme. */
    private void seedPalette() {
        Context c = getContext();
        if (mode == MODE_SPLASH) {
            // always-dark cinematic fluid; hues rotate through the FULL
            // wheel like mixing liquids (v1.0.5: loud, never subtle)
            float[] hues = {222f, 268f, 306f, 192f, 330f, 248f};
            float[][] anchors = {
                    {.16f, .20f}, {.84f, .14f}, {.80f, .86f},
                    {.14f, .88f}, {.50f, .55f}, {.95f, .50f}};
            float[][] motion = {
                    {.14f, .09f}, {.11f, .13f}, {.15f, .08f},
                    {.12f, .11f}, {.19f, .16f}, {.09f, .15f}};
            for (int i = 0; i < blobs.length; i++) {
                Blob b = new Blob();
                b.ax = anchors[i][0];
                b.ay = anchors[i][1];
                b.mrx = motion[i][0];
                b.mry = motion[i][1];
                b.sx = 0.30f + rnd.nextFloat() * 0.30f;
                b.sy = 0.26f + rnd.nextFloat() * 0.26f;
                b.ss = 0.17f + rnd.nextFloat() * 0.14f;
                b.px = rnd.nextFloat() * 6.2832f;
                b.py = rnd.nextFloat() * 6.2832f;
                b.ps = rnd.nextFloat() * 6.2832f;
                b.radF = 0.50f + rnd.nextFloat() * 0.18f;
                b.alpha = 0.30f + rnd.nextFloat() * 0.12f;
                b.hue = hues[i];
                b.hueSpeed = 7f + rnd.nextFloat() * 9f;   // deg/s, full wheel
                b.color = 0xFF4F8EF7;
                blobs[i] = b;
            }
            float[] ry = {.28f, .46f, .64f, .80f};
            for (int i = 0; i < ribbons.length; i++) {
                Ribbon r = new Ribbon();
                r.yF = ry[i];
                r.ampDp = 26f + rnd.nextFloat() * 22f;
                r.lenDp = 460f + rnd.nextFloat() * 220f;
                r.speed = (i % 2 == 0 ? 1f : -1f) * (0.42f + rnd.nextFloat() * 0.26f);
                r.phase = rnd.nextFloat() * 6.2832f;
                r.thickDp = 130f + rnd.nextFloat() * 60f;
                r.alpha = 0.16f + rnd.nextFloat() * 0.06f;
                r.hue = 222f + i * 30f;
                r.hueSpeed = 5f + rnd.nextFloat() * 7f;
                r.color = 0xFF4F8EF7;
                ribbons[i] = r;
            }
        } else {
            // theme-aware login fabric (unchanged — owner is replacing
            // this with a bundled video soon anyway)
            int[] cols = {
                    Fx.color(c, R.color.orb1), Fx.color(c, R.color.orb2),
                    Fx.color(c, R.color.orb3), Fx.color(c, R.color.orb4),
                    Fx.color(c, R.color.orb5)};
            float[][] anchors = {
                    {.18f, .22f}, {.85f, .16f}, {.78f, .86f},
                    {.15f, .88f}, {.50f, .52f}};
            float[][] motion = {
                    {.10f, .06f}, {.07f, .09f}, {.11f, .05f},
                    {.08f, .08f}, {.15f, .11f}};
            float[] alphas = {.17f, .15f, .13f, .15f, .10f};
            for (int i = 0; i < blobs.length - 1; i++) {   // 5 blobs on login
                Blob b = new Blob();
                b.ax = anchors[i][0];
                b.ay = anchors[i][1];
                b.mrx = motion[i][0];
                b.mry = motion[i][1];
                b.sx = 0.09f + rnd.nextFloat() * 0.10f;
                b.sy = 0.08f + rnd.nextFloat() * 0.09f;
                b.ss = 0.08f + rnd.nextFloat() * 0.06f;
                b.px = rnd.nextFloat() * 6.2832f;
                b.py = rnd.nextFloat() * 6.2832f;
                b.ps = rnd.nextFloat() * 6.2832f;
                b.radF = 0.46f + rnd.nextFloat() * 0.14f;
                b.alpha = alphas[i];
                b.color = cols[i];
                blobs[i] = b;
            }
            int[] wcols = {cols[1], cols[0], cols[3], cols[2]};
            float[] ry = {.30f, .47f, .63f, .79f};
            float[] wal = {.10f, .09f, .09f, .08f};
            for (int i = 0; i < ribbons.length; i++) {
                Ribbon r = new Ribbon();
                r.yF = ry[i];
                r.ampDp = 24f + rnd.nextFloat() * 16f;
                r.lenDp = 520f + rnd.nextFloat() * 200f;
                r.speed = (i % 2 == 0 ? 1f : -1f) * (0.16f + rnd.nextFloat() * 0.10f);
                r.phase = rnd.nextFloat() * 6.2832f;
                r.thickDp = 130f + rnd.nextFloat() * 50f;
                r.alpha = wal[i];
                r.color = wcols[i];
                ribbons[i] = r;
            }
        }
    }

    /** Feed parallax from an external view (the ScrollView), screen coords. */
    public void setPointer(float rawX, float rawY) {
        px = Math.max(0f, Math.min(1f, rawX / Math.max(1f, getWidth())));
        py = Math.max(0f, Math.min(1f, rawY / Math.max(1f, getHeight())));
    }

    /** Stop the repaint loop (host activity is covered). Cheap: one last
     *  frame is already on screen; nothing else schedules work. */
    public void pause() { paused = true; }

    /** Restart the repaint loop (host activity is visible again). */
    public void resume() {
        if (paused) {
            paused = false;
            // re-baseline the clocks so dt never jumps when we come back
            lastT = -1L;
            if (animate) invalidate();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN
                || e.getAction() == MotionEvent.ACTION_MOVE) {
            setPointer(e.getRawX(), e.getRawY());
            return true;
        }
        return super.onTouchEvent(e);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        seedStars(w, h);
        // base gradient + vignette are size-dependent — build once here
        // v1.0.9: splash base is theme-aware too (light: paper-white blue)
        int top = mode == MODE_SPLASH
                ? (dark ? 0xFF050813 : 0xFFF7F9FF)
                : (dark ? 0xFF04060F : 0xFFF4F7FF);
        int bot = mode == MODE_SPLASH
                ? (dark ? 0xFF0B1230 : 0xFFE2EAFB)
                : (dark ? 0xFF0B1231 : 0xFFDFE8FB);
        basePaint.setShader(new LinearGradient(0, 0, w * 0.25f, h,
                top, bot, Shader.TileMode.CLAMP));
        if (dark) {
            vignettePaint.setShader(new RadialGradient(
                    w / 2f, h / 2f, (float) Math.hypot(w, h) * 0.62f,
                    0x00000000, mode == MODE_SPLASH ? 0x59000014 : 0x42000010,
                    Shader.TileMode.CLAMP));
        } else {
            vignettePaint.setShader(null);
        }
    }

    private void seedStars(int w, int h) {
        stars.clear();
        int n = Math.min(STAR_MAX, dark ? Math.max(90, w * h / 9000)
                : Math.max(28, w * h / 30000));
        for (int i = 0; i < n; i++) {
            Star s = new Star();
            s.x = rnd.nextFloat() * w;
            s.y = rnd.nextFloat() * h;
            if (dark) {
                s.r = (rnd.nextFloat() * 1.3f + 0.35f) * dp;
                s.tw = rnd.nextFloat() * 6.2832f;
                s.ts = 0.011f + rnd.nextFloat() * 0.026f;
                s.vx = (rnd.nextFloat() - 0.5f) * 0.16f * dp;
                s.vy = (rnd.nextFloat() - 0.5f) * 0.16f * dp;
                if (rnd.nextFloat() < 0.16f) {
                    int hue = 200 + rnd.nextInt(120);
                    s.col = Color.HSVToColor(new float[]{hue, 0.85f, 0.85f});
                } else {
                    s.col = 0xFFD8E2FF;
                }
            } else {
                s.r = (rnd.nextFloat() * 3.6f + 2.6f) * dp;    // bokeh
                s.tw = rnd.nextFloat() * 6.2832f;
                s.ts = 0.004f + rnd.nextFloat() * 0.008f;
                s.vx = (rnd.nextFloat() - 0.5f) * 0.05f * dp;
                s.vy = (rnd.nextFloat() - 0.5f) * 0.05f * dp;
                int hue = rnd.nextBoolean() ? 218 : 44;
                s.col = Color.HSVToColor(new float[]{hue, 0.62f,
                        rnd.nextFloat() * 0.22f + 0.72f});
            }
            s.depth = Math.min(1.6f, s.r / (2.0f * dp));
            stars.add(s);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        long now = System.currentTimeMillis();
        if (start < 0) { start = now; lastT = now; }
        float dt = Math.min(64f, now - lastT) / 16f;
        lastT = now;
        float t = (now - start) / 1000f;

        pxc += (px - pxc) * 0.055f * dt;
        pyc += (py - pyc) * 0.055f * dt;

        // 1 ─ base
        canvas.drawRect(0, 0, w, h, basePaint);

        // 2 ─ the fluid layer (splash only)
        if (mode == MODE_SPLASH) {
            if (shaderOk) {
                drawShaderFluid(canvas, w, h, t);
            } else {
                drawCanvasFluid(canvas, w, h, t);
            }
        } else {
            drawLoginBlobs(canvas, w, h, t);
        }

        // 3 ─ vignette
        if (dark && vignettePaint.getShader() != null) {
            canvas.drawRect(0, 0, w, h, vignettePaint);
        }

        // 4 ─ stars / bokeh
        for (Star s : stars) {
            if (animate) {
                s.tw += s.ts * dt;
                s.x += s.vx * dt;
                s.y += s.vy * dt;
                if (s.x < -8) s.x = w + 8; else if (s.x > w + 8) s.x = -8;
                if (s.y < -8) s.y = h + 8; else if (s.y > h + 8) s.y = -8;
            }
            float a;
            if (dark) {
                a = (mode == MODE_SPLASH ? 0.45f : 0.30f)
                        + 0.55f * Math.abs((float) Math.sin(s.tw));
            } else {
                a = 0.08f + 0.15f * (0.5f + 0.5f * (float) Math.sin(s.tw));
            }
            float ox = (pxc - 0.5f) * 16f * s.depth;
            float oy = (pyc - 0.5f) * 16f * s.depth;
            starPaint.setShader(null);
            if (dark) {
                starPaint.setColor(s.col);
                starPaint.setAlpha((int) (a * 255f));
                canvas.drawCircle(s.x + ox, s.y + oy, s.r, starPaint);
            } else {
                starPaint.setShader(new RadialGradient(s.x + ox, s.y + oy, s.r * 2.4f,
                        ((int) (a * 255f) << 24) | (s.col & 0xFFFFFF),
                        0x00000000, Shader.TileMode.CLAMP));
                canvas.drawCircle(s.x + ox, s.y + oy, s.r * 2.4f, starPaint);
            }
        }

        // 5 ─ shooting stars (more of them on splash)
        if (animate) {
            long interval = mode == MODE_SPLASH ? 1700L : 2800L;
            if (now - start > nextShootAt) {
                int burst = mode == MODE_SPLASH && rnd.nextInt(3) == 0 ? 2 : 1;
                for (int k = 0; k < burst; k++) {
                    Shoot sh = new Shoot();
                    sh.maxLife = dark ? 950f : 1150f;
                    sh.life = 0f;
                    double ang = Math.toRadians(14 + rnd.nextInt(16));
                    float sp = (dark ? 13f : 9f) * dp;
                    sh.dx = (float) (Math.cos(ang) * sp);
                    sh.dy = (float) (Math.sin(ang) * sp);
                    sh.x = rnd.nextFloat() * w * 0.5f - 40f;
                    sh.y = rnd.nextFloat() * h * 0.4f;
                    shoots.add(sh);
                }
                nextShootAt = now - start + interval + rnd.nextInt(2000);
            }
            for (int i = shoots.size() - 1; i >= 0; i--) {
                Shoot sh = shoots.get(i);
                sh.life += dt * 16f;
                sh.x += sh.dx * dt;
                sh.y += sh.dy * dt;
                float p = sh.life / sh.maxLife;
                if (p >= 1f) { shoots.remove(i); continue; }
                float alpha = (float) Math.sin(p * Math.PI);
                float lenD = 60f * dp;
                float norm = (float) Math.hypot(sh.dx, sh.dy);
                float ex = sh.x - sh.dx / norm * lenD;
                float ey = sh.y - sh.dy / norm * lenD;
                int col = dark ? 0xFFFFFFFF : 0xFF2F6BFF;
                shootPaint.setShader(new LinearGradient(sh.x, sh.y, ex, ey,
                        ((int) (alpha * 230f) << 24) | (col & 0xFFFFFF),
                        0x00000000, Shader.TileMode.CLAMP));
                shootPaint.setStrokeWidth((dark ? 2f : 1.5f) * dp);
                shootPaint.setStrokeCap(Paint.Cap.ROUND);
                canvas.drawLine(sh.x, sh.y, ex, ey, shootPaint);
                shootPaint.setShader(null);
            }
        }

        if (animate && !paused) invalidate();
    }

    // ── splash fluid, API 33+: one AGSL shader quad ─────────────────────

    private void drawShaderFluid(Canvas canvas, int w, int h, float t) {
        try {
            fluidShader.setFloatUniform("iResolution", (float) w, (float) h);
            fluidShader.setFloatUniform("iTime", animate ? t : 4.2f);
            shaderPaint.setShader(fluidShader);
            canvas.drawRect(0, 0, w, h, shaderPaint);
        } catch (Throwable t2) {
            shaderOk = false;   // driver fell over at draw time — canvas path
        }
    }

    // ── splash fluid, canvas fallback: loud SCREEN-blended nebula ───────

    private void drawCanvasFluid(Canvas canvas, int w, int h, float t) {
        float minDim = Math.min(w, h);

        // v1.0.9: light splash = pastel hues painted SRC_OVER (SCREEN would
        // wash out to white on a light base); dark splash keeps the loud
        // SCREEN-blended full-wheel mixing.
        blobPaint.setXfermode(dark ? SCREEN : null);
        for (Blob b : blobs) {
            if (b == null) continue;
            float bx = b.ax * w + (float) Math.sin(t * b.sx + b.px) * b.mrx * w
                    + (pxc - 0.5f) * 14f * dp;
            float by = b.ay * h + (float) Math.sin(t * b.sy + b.py) * b.mry * h
                    + (pyc - 0.5f) * 10f * dp;
            float br = b.radF * minDim
                    * (1f + 0.16f * (float) Math.sin(t * b.ss + b.ps));
            // continuous full-wheel rotation = colours that keep mixing
            float hue = (b.hue + t * b.hueSpeed) % 360f;
            float sat = dark ? 0.85f : 0.50f;
            float val = dark ? 0.62f : 0.97f;
            int col = Color.HSVToColor(new float[]{hue, sat, val});
            int a = (int) (b.alpha * (dark ? 255f : 210f));
            blobPaint.setShader(new RadialGradient(bx, by, br,
                    (a << 24) | (col & 0xFFFFFF), 0x00000000, Shader.TileMode.CLAMP));
            canvas.drawCircle(bx, by, br, blobPaint);
        }
        blobPaint.setXfermode(null);

        ribbonPaint.setXfermode(dark ? SCREEN : null);
        for (Ribbon r : ribbons) {
            if (r == null) continue;
            float hue = (r.hue + t * r.hueSpeed) % 360f;
            float rsat = dark ? 0.78f : 0.42f;
            float rval = dark ? 0.64f : 0.99f;
            int col = Color.HSVToColor(new float[]{hue, rsat, rval});
            float yc = r.yF * h + (pyc - 0.5f) * 8f * dp;
            float amp = r.ampDp * dp;
            float len = r.lenDp * dp;
            float half = r.thickDp * dp * 0.5f;
            float soft = 42f * dp;
            float wave = t * r.speed + r.phase;

            ribbonPath.reset();
            ribbonPath.moveTo(-24f, yc - half + amp * (float) Math.sin(wave));
            int steps = 44;
            for (int i = 1; i <= steps; i++) {
                float x = -24f + (w + 48f) * i / steps;
                float k = 6.2832f * x / len;
                float y = yc - half + amp * (float) Math.sin(k + wave);
                ribbonPath.lineTo(x, y);
            }
            for (int i = steps; i >= 0; i--) {
                float x = -24f + (w + 48f) * i / steps;
                float k = 6.2832f * x / len;
                float y = yc + half + amp * 0.82f * (float) Math.sin(k + wave * 0.86f + 1.1f);
                ribbonPath.lineTo(x, y);
            }
            ribbonPath.close();

            int a = (int) (r.alpha * 255f);
            int rgb = col & 0xFFFFFF;
            ribbonPaint.setShader(new LinearGradient(
                    0, yc - half - soft, w * 0.35f, yc + half + soft,
                    new int[]{0x00000000, (a << 24) | rgb, 0x00000000},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawPath(ribbonPath, ribbonPaint);
        }
        ribbonPaint.setXfermode(null);
    }

    // ── login: calm blobs + ribbons (SRC_OVER, theme palette) ───────────

    private void drawLoginBlobs(Canvas canvas, int w, int h, float t) {
        float minDim = Math.min(w, h);
        for (Blob b : blobs) {
            if (b == null) continue;
            float bx = b.ax * w + (float) Math.sin(t * b.sx + b.px) * b.mrx * w
                    + (pxc - 0.5f) * 14f * dp;
            float by = b.ay * h + (float) Math.sin(t * b.sy + b.py) * b.mry * h
                    + (pyc - 0.5f) * 10f * dp;
            float br = b.radF * minDim
                    * (1f + 0.13f * (float) Math.sin(t * b.ss + b.ps));
            int a = (int) (b.alpha * 255f);
            blobPaint.setShader(new RadialGradient(bx, by, br,
                    (a << 24) | (b.color & 0xFFFFFF), 0x00000000, Shader.TileMode.CLAMP));
            canvas.drawCircle(bx, by, br, blobPaint);
        }

        for (Ribbon r : ribbons) {
            if (r == null) continue;
            float yc = r.yF * h + (pyc - 0.5f) * 8f * dp;
            float amp = r.ampDp * dp;
            float len = r.lenDp * dp;
            float half = r.thickDp * dp * 0.5f;
            float soft = 42f * dp;
            float wave = t * r.speed + r.phase;

            ribbonPath.reset();
            ribbonPath.moveTo(-24f, yc - half + amp * (float) Math.sin(wave));
            int steps = 44;
            for (int i = 1; i <= steps; i++) {
                float x = -24f + (w + 48f) * i / steps;
                float k = 6.2832f * x / len;
                float y = yc - half + amp * (float) Math.sin(k + wave);
                ribbonPath.lineTo(x, y);
            }
            for (int i = steps; i >= 0; i--) {
                float x = -24f + (w + 48f) * i / steps;
                float k = 6.2832f * x / len;
                float y = yc + half + amp * 0.82f * (float) Math.sin(k + wave * 0.86f + 1.1f);
                ribbonPath.lineTo(x, y);
            }
            ribbonPath.close();

            int a = (int) (r.alpha * 255f);
            int rgb = r.color & 0xFFFFFF;
            ribbonPaint.setShader(new LinearGradient(
                    0, yc - half - soft, w * 0.35f, yc + half + soft,
                    new int[]{0x00000000, (a << 24) | rgb, 0x00000000},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawPath(ribbonPath, ribbonPaint);
        }
    }

    // ── the AGSL fluid shader (splash, API 33+) ─────────────────────────
    // Domain-warped fBm ("iq clouds" technique): two nested warp fields
    // advect a value-noise density; three hues rotate through the full
    // wheel on independent clocks and mix through that density — a
    // continuously flowing, continuously re-mixing colour fluid. Pure
    // function of (position, time): perfectly smooth, never snaps.
    private static final String FLUID_AGSL =
        "uniform float2 iResolution;\n" +
        "uniform float iTime;\n" +
        "uniform float iSeed;\n" +
        "float hash(float2 p){\n" +
        "  return fract(sin(dot(p, float2(127.1 + iSeed, 311.7))) * 43758.5453123);\n" +
        "}\n" +
        "float vnoise(float2 p){\n" +
        "  float2 i = floor(p);\n" +
        "  float2 f = fract(p);\n" +
        "  f = f * f * (3.0 - 2.0 * f);\n" +
        "  float a = hash(i);\n" +
        "  float b = hash(i + float2(1.0, 0.0));\n" +
        "  float c = hash(i + float2(0.0, 1.0));\n" +
        "  float d = hash(i + float2(1.0, 1.0));\n" +
        "  return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);\n" +
        "}\n" +
        "float fbm(float2 p){\n" +
        "  float v = 0.0;\n" +
        "  float amp = 0.5;\n" +
        "  for (int i = 0; i < 5; i++){\n" +
        "    v += amp * vnoise(p);\n" +
        "    p = p * 2.03 + 13.7;\n" +
        "    amp *= 0.55;\n" +
        "  }\n" +
        "  return v;\n" +
        "}\n" +
        "float3 hsv(float h, float s, float v){\n" +
        "  float3 K3 = float3(1.0, 0.6666666, 0.3333333);\n" +
        "  float3 p = abs(fract(h + K3) * 6.0 - 3.0);\n" +
        "  float3 q = clamp(p - 1.0, 0.0, 1.0);\n" +
        "  return v * (float3(1.0) - s * (float3(1.0) - q));\n" +
        "}\n" +
        "half4 main(float2 fragCoord){\n" +
        "  float2 uv = (fragCoord - 0.5 * iResolution) / iResolution.y;\n" +
        "  float t = iTime * 0.055;\n" +
        "  float2 q = float2(fbm(uv + t * 0.90),\n" +
        "                    fbm(uv + float2(5.2, 1.3) - t * 0.70));\n" +
        "  float2 r = float2(fbm(uv + 3.0 * q + float2(1.7, 9.2) + t * 0.60),\n" +
        "                    fbm(uv + 3.0 * q + float2(8.3, 2.8) - t * 0.45));\n" +
        "  float f = fbm(uv + 3.0 * r);\n" +
        "  float h1 = fract(0.617 + iTime * 0.021);\n" +
        "  float h2 = fract(0.778 + iTime * 0.014);\n" +
        "  float h3 = fract(0.528 + iTime * 0.027);\n" +
        "  float3 c1 = hsv(h1, 0.72, 0.34);\n" +
        "  float3 c2 = hsv(h2, 0.66, 0.30);\n" +
        "  float3 c3 = hsv(h3, 0.80, 0.42);\n" +
        "  float3 col = c1 * 0.95\n" +
        "             + c2 * smoothstep(0.18, 0.78, f) * 1.15\n" +
        "             + c3 * smoothstep(0.30, 0.85, q.x) * 0.95;\n" +
        "  col += 0.05 * float3(fbm(uv * 2.2 - t * 1.7));\n" +
        "  col *= 1.0 - 0.52 * dot(uv, uv);\n" +
        "  return half4(col, 1.0);\n" +
        "}\n";
}
