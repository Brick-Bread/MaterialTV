package app.m3home;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** The background: M3 dark surface with two soft tonal glows (primary and tertiary). */
final class Glow extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float w = b.width(), h = b.height();
        c.drawColor(M3.BACKGROUND);
        blob(c, w * 0.08f, h * 0.02f, w * 0.62f, 0x8C4F378B); // primary container, top left
        blob(c, w * 0.98f, h * 1.02f, w * 0.55f, 0x66633B48); // tertiary container, bottom right
        blob(c, w * 0.78f, -h * 0.15f, w * 0.30f, 0x40381E72); // a little depth top right
    }

    private void blob(Canvas c, float x, float y, float r, int color) {
        paint.setShader(new RadialGradient(x, y, r, color, color & 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, r, paint);
    }

    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(ColorFilter cf) { }
    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
}
