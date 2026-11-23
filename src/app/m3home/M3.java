package app.m3home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;

/** Material 3 dark colour roles (baseline purple) and small drawing helpers. */
final class M3 {
    private M3() {}

    static final int BACKGROUND        = 0xFF141218;
    static final int SURFACE_LOW       = 0xFF1D1B20;
    static final int SURFACE           = 0xFF211F26;
    static final int SURFACE_HIGH      = 0xFF2B2930;
    static final int SURFACE_HIGHEST   = 0xFF36343B;
    static final int ON_SURFACE        = 0xFFE6E0E9;
    static final int ON_SURFACE_VAR    = 0xFFCAC4D0;
    static final int OUTLINE           = 0xFF938F99;
    static final int OUTLINE_VAR       = 0xFF49454F;
    static final int PRIMARY           = 0xFFD0BCFF;
    static final int ON_PRIMARY        = 0xFF381E72;
    static final int PRIMARY_CONT      = 0xFF4F378B;
    static final int ON_PRIMARY_CONT   = 0xFFEADDFF;
    static final int SECONDARY_CONT    = 0xFF4A4458;
    static final int ON_SECONDARY_CONT = 0xFFE8DEF8;
    static final int ERROR             = 0xFFF2B8B5;
    /** A faint primary tint for the top of the screen, like M3's tonal surfaces. */
    static final int TINT_TOP          = 0xFF231C33;

    // Google Sans Flex (SIL OFL 1.1, assets/fonts). System sans-serif until init() has run.
    static Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    static Typeface MEDIUM  = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    static Typeface LIGHT   = Typeface.create("sans-serif-light", Typeface.NORMAL);
    private static boolean fontsLoaded;

    /** Loads the bundled variable font at three weights. Safe to call more than once. */
    static synchronized void init(Context c) {
        if (fontsLoaded) return;
        fontsLoaded = true;
        Typeface regular = flex(c, 400), medium = flex(c, 560), light = flex(c, 300);
        if (regular != null) REGULAR = regular;
        if (medium != null) MEDIUM = medium;
        if (light != null) LIGHT = light;
    }

    private static Typeface flex(Context c, int weight) {
        try {
            // ROND gives Google Sans Flex its softer, Material 3 Expressive corners.
            return new Typeface.Builder(c.getApplicationContext().getAssets(), "fonts/GoogleSansFlex.ttf")
                    .setFontVariationSettings("'wght' " + weight + ", 'ROND' 50")
                    .build();
        } catch (RuntimeException e) {
            return null;
        }
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable rounded(int color, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        return d;
    }

    static GradientDrawable outlined(int fill, int stroke, int strokePx, float radiusPx) {
        GradientDrawable d = rounded(fill, radiusPx);
        d.setStroke(strokePx, stroke);
        return d;
    }

    /**
     * A dark tonal container colour taken from an icon, like M3's colour extraction:
     * the icon's dominant hue at a fixed low tone, so every card stays calm and readable.
     */
    static int containerFor(Drawable icon) {
        if (icon == null) return SURFACE_HIGH;
        try {
            Drawable d = icon.getConstantState() != null ? icon.getConstantState().newDrawable().mutate() : icon;
            Bitmap bm = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, 24, 24);
            d.draw(new Canvas(bm));
            float[] hsv = new float[3];
            double x = 0, y = 0, weight = 0;
            for (int py = 0; py < 24; py++) {
                for (int px = 0; px < 24; px++) {
                    int c = bm.getPixel(px, py);
                    if (Color.alpha(c) < 128) continue;
                    Color.colorToHSV(c, hsv);
                    double w = hsv[1] * hsv[2];
                    if (w < 0.08) continue; // greys and near-blacks say nothing about the brand colour
                    double a = Math.toRadians(hsv[0]);
                    x += Math.cos(a) * w;
                    y += Math.sin(a) * w;
                    weight += w;
                }
            }
            bm.recycle();
            if (weight < 1.0) return SURFACE_HIGH;
            float hue = (float) Math.toDegrees(Math.atan2(y, x));
            if (hue < 0) hue += 360f;
            return Color.HSVToColor(new float[] { hue, 0.42f, 0.30f });
        } catch (RuntimeException e) {
            return SURFACE_HIGH;
        }
    }

    /** The same hue, lighter (>1) or darker (<1). */
    static int tone(int color, float factor) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] = Math.max(0f, Math.min(1f, hsv[2] * factor));
        return Color.HSVToColor(Color.alpha(color), hsv);
    }

    static GradientDrawable circle(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }
}
