package app.m3home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

import java.util.Random;

/**
 * The bedtime scene: a very dark night sky, softly twinkling stars, a slow crescent moon and the odd
 * shooting star. Drawn at about 20 frames a second, which is plenty for something this slow and
 * keeps the stick cool overnight.
 */
final class SleepView extends View {
    private static final int STARS = 150;
    private static final long FRAME_MS = 50;
    private static final float MOON_CROSS_SECONDS = 30 * 60; // the moon takes 30 minutes to cross

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random rnd = new Random();
    private final float[] sx = new float[STARS], sy = new float[STARS], sr = new float[STARS],
            sPhase = new float[STARS], sSpeed = new float[STARS];
    private Shader sky, moonGlow;
    private final Path moon = new Path();
    private float moonR;
    private long startNs;
    private boolean running;

    // shooting star
    private float shootX, shootY, shootDx, shootDy, shootT = -1;
    private long nextShootMs;

    SleepView(Context c) { super(c); }

    void start() {
        running = true;
        startNs = System.nanoTime();
        nextShootMs = System.currentTimeMillis() + 20_000 + rnd.nextInt(30_000);
        invalidate();
    }

    void stop() { running = false; }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (w == 0 || h == 0) return;
        sky = new LinearGradient(0, 0, 0, h, new int[] { 0xFF0B0918, 0xFF08070F, 0xFF050409 }, null, Shader.TileMode.CLAMP);
        for (int i = 0; i < STARS; i++) {
            sx[i] = rnd.nextFloat() * w;
            sy[i] = (float) Math.pow(rnd.nextFloat(), 1.4) * h * 0.9f; // more stars higher up
            sr[i] = 0.6f + rnd.nextFloat() * (rnd.nextInt(12) == 0 ? 2.2f : 1.2f);
            sPhase[i] = rnd.nextFloat() * 6.28f;
            sSpeed[i] = 0.3f + rnd.nextFloat() * 1.2f;
        }
        moonR = h * 0.055f;
        // A crescent: the moon's disc minus an offset disc.
        Path full = new Path();
        full.addCircle(0, 0, moonR, Path.Direction.CW);
        Path bite = new Path();
        bite.addCircle(moonR * 0.45f, -moonR * 0.18f, moonR * 0.92f, Path.Direction.CW);
        moon.reset();
        moon.op(full, bite, Path.Op.DIFFERENCE);
        moonGlow = new RadialGradient(0, 0, moonR * 4.5f, 0x30B9A6F0, 0x00B9A6F0, Shader.TileMode.CLAMP);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (sky == null) return;
        float w = getWidth(), h = getHeight();
        float t = (System.nanoTime() - startNs) / 1e9f;

        paint.setShader(sky);
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(null);

        // Stars twinkle by fading in and out at their own pace.
        for (int i = 0; i < STARS; i++) {
            float tw = 0.5f + 0.5f * (float) Math.sin(sPhase[i] + t * sSpeed[i]);
            paint.setColor(0xFFE8E0FF);
            paint.setAlpha((int) (40 + 150 * tw));
            c.drawCircle(sx[i], sy[i], sr[i], paint);
        }

        // The moon drifts slowly along a shallow arc and starts over.
        float p = (t % MOON_CROSS_SECONDS) / MOON_CROSS_SECONDS;
        float mx = w * (0.12f + 0.76f * p);
        float my = h * (0.30f - 0.14f * (float) Math.sin(Math.PI * p));
        c.save();
        c.translate(mx, my);
        paint.setShader(moonGlow);
        paint.setAlpha(255);
        c.drawCircle(0, 0, moonR * 4.5f, paint);
        paint.setShader(null);
        paint.setColor(0xFFEDE6FF);
        paint.setAlpha(225);
        c.rotate(-20);
        c.drawPath(moon, paint);
        c.restore();

        drawShootingStar(c, w, h);

        if (running) postInvalidateDelayed(FRAME_MS);
    }

    private void drawShootingStar(Canvas c, float w, float h) {
        long now = System.currentTimeMillis();
        if (shootT < 0 && now >= nextShootMs) {
            shootT = 0;
            shootX = w * (0.2f + rnd.nextFloat() * 0.6f);
            shootY = h * (0.05f + rnd.nextFloat() * 0.25f);
            float len = w * 0.25f;
            shootDx = len * (rnd.nextBoolean() ? 1 : -1);
            shootDy = len * 0.35f;
        }
        if (shootT < 0) return;
        shootT += FRAME_MS / 1000f / 1.1f; // about 1.1 s across
        if (shootT >= 1f) {
            shootT = -1;
            nextShootMs = now + 35_000 + rnd.nextInt(60_000);
            return;
        }
        float hx = shootX + shootDx * shootT, hy = shootY + shootDy * shootT;
        float tail = 0.18f;
        float tx = hx - shootDx * tail, ty = hy - shootDy * tail;
        float fade = (float) Math.sin(Math.PI * shootT);
        paint.setShader(new LinearGradient(tx, ty, hx, hy, 0x00FFFFFF, (int) (200 * fade) << 24 | 0xFFFFFF, Shader.TileMode.CLAMP));
        paint.setStrokeWidth(getResources().getDisplayMetrics().density * 2f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(tx, ty, hx, hy, paint);
        paint.setShader(null);
    }
}
