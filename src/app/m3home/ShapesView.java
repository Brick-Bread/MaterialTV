package app.m3home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Material 3 Expressive shapes (circle, squircle, flower, scalloped "cookie", pill, soft star)
 * drifting, turning and gently morphing in the purple/rose palette.
 */
final class ShapesView extends View {
    private static final int[] COLORS = {
            0xFFD0BCFF, 0xFF4F378B, 0xFFEFB8C8, 0xFF633B48, 0xFFCCC2DC, 0xFF4A4458, 0xFFB69DF8, 0xFF7D5260,
    };
    private static final int COUNT = 9;

    private static final class Shape {
        int type, color, alpha;
        float x, y, vx, vy, r, rot, vr, phase, vp;
        android.graphics.RadialGradient halo; // soft glow, centred on the shape's origin
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final Random rnd = new Random();
    private final List<Shape> shapes = new ArrayList<>();
    private long last;
    private boolean running;

    ShapesView(Context c) { super(c); }

    void start() {
        running = true;
        last = System.nanoTime();
        postInvalidateOnAnimation();
    }

    void stop() { running = false; }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (!shapes.isEmpty() || w == 0) return;
        float d = getResources().getDisplayMetrics().density;
        for (int i = 0; i < COUNT; i++) {
            Shape s = new Shape();
            s.type = i % 6;
            s.color = COLORS[rnd.nextInt(COLORS.length)];
            s.alpha = 120 + rnd.nextInt(110);
            s.r = h * (0.07f + rnd.nextFloat() * 0.13f);
            s.x = rnd.nextFloat() * w;
            s.y = rnd.nextFloat() * h;
            float speed = (10 + rnd.nextFloat() * 18) * d;
            double a = rnd.nextDouble() * Math.PI * 2;
            s.vx = (float) Math.cos(a) * speed;
            s.vy = (float) Math.sin(a) * speed;
            s.rot = rnd.nextFloat() * 360;
            s.vr = (rnd.nextBoolean() ? 1 : -1) * (3 + rnd.nextFloat() * 9);
            s.phase = rnd.nextFloat() * 6.28f;
            s.vp = 0.25f + rnd.nextFloat() * 0.45f;
            s.halo = new android.graphics.RadialGradient(0, 0, s.r * 1.9f,
                    (s.color & 0x00FFFFFF) | 0x38000000, s.color & 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP);
            shapes.add(s);
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = System.nanoTime();
        float dt = Math.min((now - last) / 1e9f, 0.1f);
        last = now;
        float w = getWidth(), h = getHeight();
        for (Shape s : shapes) {
            s.x += s.vx * dt;
            s.y += s.vy * dt;
            if ((s.x < -s.r * 0.4f && s.vx < 0) || (s.x > w + s.r * 0.4f && s.vx > 0)) s.vx = -s.vx;
            if ((s.y < -s.r * 0.4f && s.vy < 0) || (s.y > h + s.r * 0.4f && s.vy > 0)) s.vy = -s.vy;
            s.rot += s.vr * dt;
            s.phase += s.vp * dt;
            draw(c, s);
        }
        if (running) postInvalidateOnAnimation();
    }

    private void draw(Canvas c, Shape s) {
        float breathe = 1f + 0.06f * (float) Math.sin(s.phase);
        float r = s.r * breathe;
        c.save();
        c.translate(s.x, s.y);
        paint.setShader(s.halo);
        paint.setAlpha(255);
        c.drawCircle(0, 0, s.r * 1.9f, paint);
        paint.setShader(null);
        paint.setColor(s.color);
        paint.setAlpha(s.alpha);
        c.rotate(s.rot);
        switch (s.type) {
            case 0: // circle
                c.drawCircle(0, 0, r, paint);
                break;
            case 1: { // squircle whose corners breathe
                float corner = r * (0.38f + 0.14f * (float) Math.sin(s.phase * 0.8f));
                rect.set(-r, -r, r, r);
                c.drawRoundRect(rect, corner, corner, paint);
                break;
            }
            case 2: // six-petal flower
                polar(c, r, 6, 0.16f + 0.07f * (float) Math.sin(s.phase));
                break;
            case 3: // scalloped cookie
                polar(c, r, 9, 0.06f + 0.03f * (float) Math.sin(s.phase * 1.3f));
                break;
            case 4: // pill
                rect.set(-r * 1.3f, -r * 0.55f, r * 1.3f, r * 0.55f);
                c.drawRoundRect(rect, r * 0.55f, r * 0.55f, paint);
                break;
            default: // soft four-point star
                polar(c, r, 4, 0.30f + 0.08f * (float) Math.sin(s.phase));
                break;
        }
        c.restore();
    }

    /** r(θ) = R(1 + amp·cos kθ): petals, scallops and soft stars from one formula. */
    private void polar(Canvas c, float radius, int k, float amp) {
        path.reset();
        final int steps = 120;
        for (int i = 0; i <= steps; i++) {
            double t = Math.PI * 2 * i / steps;
            float rr = radius * (1f + amp * (float) Math.cos(k * t)) / (1f + amp);
            float px = rr * (float) Math.cos(t), py = rr * (float) Math.sin(t);
            if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
        }
        path.close();
        c.drawPath(path, paint);
    }
}
