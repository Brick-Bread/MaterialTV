package app.m3home;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import java.util.HashMap;
import java.util.Map;

/**
 * Turns any app icon into a tidy round one for the tiles: empty edges are trimmed, a full-colour
 * square icon fills the whole circle, a wide logo (like a banner) sits on a circle of its own
 * background colour, and a see-through logo is centred at a steady size.
 */
final class RoundIcon {
    private RoundIcon() {}

    private static final int WORK = 192;                     // size the icon is studied at
    private static final Map<String, Bitmap> cache = new HashMap<>();

    /** The round icon for key (e.g. a package name), size px across; null if it can't be drawn. */
    static synchronized Bitmap of(String key, Drawable d, int size) {
        String k = key + "@" + size;
        Bitmap b = cache.get(k);
        if (b == null && d != null) {
            try {
                b = make(d, size);
            } catch (RuntimeException | OutOfMemoryError e) {
                b = null;
            }
            if (b != null) cache.put(k, b);
        }
        return b;
    }

    static synchronized void clear() { cache.clear(); }

    private static Bitmap make(Drawable d, int size) {
        int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
        if (iw <= 0 || ih <= 0) { iw = ih = WORK; }
        float s = (float) WORK / Math.max(iw, ih);
        int w = Math.max(1, Math.round(iw * s)), h = Math.max(1, Math.round(ih * s));
        Bitmap src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas sc = new Canvas(src);
        Rect old = new Rect(d.getBounds());
        d.setBounds(0, 0, w, h);
        d.draw(sc);
        d.setBounds(old);

        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        // Trim see-through edges.
        int l = w, t = h, r = -1, btm = -1;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            if ((px[y * w + x] >>> 24) > 24) {
                if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > btm) btm = y;
            }
        }
        if (r < 0) return null; // nothing to draw
        Rect box = new Rect(l, t, r + 1, btm + 1);
        int bw = box.width(), bh = box.height();

        // Does the art have its own solid background? Check just inside its four corners.
        int in = Math.max(1, Math.min(bw, bh) / 24);
        int[] corners = {
                px[(t + in) * w + (l + in)], px[(t + in) * w + (r - in)],
                px[(btm - in) * w + (l + in)], px[(btm - in) * w + (r - in)] };
        boolean solid = true;
        for (int c : corners) if ((c >>> 24) < 220) solid = false;

        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas oc = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        RectF dst;
        if (solid) {
            int edge = edgeColour(px, w, box);
            float aspect = (float) bw / bh;
            // Paint the circle in the art's own edge colour, then the art on top.
            Bitmap layer = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas lc = new Canvas(layer);
            lc.drawColor(edge | 0xFF000000);
            if (aspect > 0.8f && aspect < 1.25f) {
                // A square icon: fill the circle (only background is lost at the corners).
                float sc2 = Math.max((float) size / bw, (float) size / bh);
                float dw = bw * sc2, dh = bh * sc2;
                dst = new RectF((size - dw) / 2f, (size - dh) / 2f, (size + dw) / 2f, (size + dh) / 2f);
            } else {
                // A wide or tall logo: fit it inside the circle so nothing is cut off.
                float fit = size * 0.84f;
                float sc2 = Math.min(fit / bw, fit / bh);
                float dw = bw * sc2, dh = bh * sc2;
                dst = new RectF((size - dw) / 2f, (size - dh) / 2f, (size + dw) / 2f, (size + dh) / 2f);
            }
            lc.drawBitmap(src, box, dst, p);
            p.setShader(new BitmapShader(layer, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            oc.drawCircle(size / 2f, size / 2f, size / 2f, p);
            layer.recycle();
        } else {
            // A see-through logo: centre it at a steady size over the tile's soft disc.
            float fit = size * 0.66f;
            float sc2 = Math.min(fit / bw, fit / bh);
            float dw = bw * sc2, dh = bh * sc2;
            dst = new RectF((size - dw) / 2f, (size - dh) / 2f, (size + dw) / 2f, (size + dh) / 2f);
            oc.drawBitmap(src, box, dst, p);
        }
        src.recycle();
        return out;
    }

    /** The average colour along the art's outer edge. */
    private static int edgeColour(int[] px, int w, Rect b) {
        long rs = 0, gs = 0, bs = 0, n = 0;
        int step = Math.max(1, Math.min(b.width(), b.height()) / 32);
        for (int x = b.left; x < b.right; x += step) {
            for (int y : new int[] { b.top + 1, b.bottom - 2 }) {
                int c = px[y * w + x]; rs += Color.red(c); gs += Color.green(c); bs += Color.blue(c); n++;
            }
        }
        for (int y = b.top; y < b.bottom; y += step) {
            for (int x : new int[] { b.left + 1, b.right - 2 }) {
                int c = px[y * w + x]; rs += Color.red(c); gs += Color.green(c); bs += Color.blue(c); n++;
            }
        }
        if (n == 0) return Color.DKGRAY;
        return Color.rgb((int) (rs / n), (int) (gs / n), (int) (bs / n));
    }
}
