package app.m3home;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** The background: the M3 background colour with soft tonal glows (primary and tertiary containers). */
final class Glow extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final M3.Scheme fixed;
    private final Shader[] shaders = new Shader[3];
    private final float[] blobs = new float[9]; // x, y, r for each glow
    private int shadedFor; // background colour the shaders were made for

    /** Follows the current colour roles. */
    Glow() { this(null); }

    /** Always uses this scheme (the screensaver passes M3.dark()). */
    Glow(M3.Scheme scheme) { fixed = scheme; }

    @Override
    protected void onBoundsChange(Rect b) {
        shaders[0] = null;
    }

    @Override
    public void draw(Canvas c) {
        int bg = fixed != null ? fixed.background : M3.BACKGROUND;
        if (shaders[0] == null || shadedFor != bg) build(bg);
        c.drawColor(bg);
        for (int i = 0; i < 3; i++) {
            paint.setShader(shaders[i]);
            c.drawCircle(blobs[i * 3], blobs[i * 3 + 1], blobs[i * 3 + 2], paint);
        }
    }

    private void build(int bg) {
        Rect b = getBounds();
        float w = b.width(), h = b.height();
        int primaryCont = fixed != null ? fixed.primaryCont : M3.PRIMARY_CONT;
        int tertiaryCont = fixed != null ? fixed.tertiaryCont : M3.TERTIARY_CONT;
        int onPrimary = fixed != null ? fixed.onPrimary : M3.ON_PRIMARY;
        blob(0, w * 0.08f, h * 0.02f, w * 0.62f, M3.alpha(primaryCont, 0x8C));  // top left
        blob(1, w * 0.98f, h * 1.02f, w * 0.55f, M3.alpha(tertiaryCont, 0x66)); // bottom right
        blob(2, w * 0.78f, -h * 0.15f, w * 0.30f, M3.alpha(onPrimary, 0x40));   // a little depth top right
        shadedFor = bg;
    }

    private void blob(int i, float x, float y, float r, int color) {
        blobs[i * 3] = x; blobs[i * 3 + 1] = y; blobs[i * 3 + 2] = Math.max(1f, r);
        shaders[i] = new RadialGradient(x, y, Math.max(1f, r), color, color & 0x00FFFFFF, Shader.TileMode.CLAMP);
    }

    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(ColorFilter cf) { }
    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
}
