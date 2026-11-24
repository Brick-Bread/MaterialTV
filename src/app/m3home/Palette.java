package app.m3home;

import android.content.Context;

/**
 * Accent colours and the Material 3 colour scheme made from one seed colour.
 *
 * Tones are worked out in CIE L*C*h: a tone T is the seed's hue and chroma at lightness L* = T,
 * with the chroma reduced until the colour fits in sRGB. No Android classes are used in the
 * colour maths, so it runs the same on a desktop JVM.
 */
final class Palette {
    private Palette() {}

    static final String[] ACCENTS = { "purple", "blue", "teal", "green", "yellow", "orange", "red", "pink", "background", "custom" };
    static final String[] ACCENT_NAMES = { "Purple", "Blue", "Teal", "Green", "Yellow", "Orange", "Red", "Pink", "From background", "Custom" };
    private static final int[] SEEDS = {
            0xFF6750A4, 0xFF0061A4, 0xFF006A6A, 0xFF386A20, 0xFF6D5E0F, 0xFF8B5000, 0xFFB3261E, 0xFF984061,
    };
    static final int PURPLE = 0xFF6750A4;

    /**
     * The seed colour for an accent id ("background" = the colour picked from the Home background,
     * "custom" = any colour, set by a theme plugin).
     */
    static int seedOf(Context c, String accent) {
        if ("custom".equals(accent)) {
            if (c == null) return PURPLE;
            try {
                return 0xFF000000 | Prefs.of(c).getInt(Prefs.ACCENT_SEED, PURPLE);
            } catch (ClassCastException e) {
                return PURPLE;
            }
        }
        if ("background".equals(accent)) {
            if (c == null) return PURPLE;
            try {
                return 0xFF000000 | Prefs.of(c).getInt(Prefs.BG_SEED, PURPLE);
            } catch (ClassCastException e) {
                return PURPLE;
            }
        }
        for (int i = 0; i < SEEDS.length; i++) if (ACCENTS[i].equals(accent)) return SEEDS[i];
        return PURPLE;
    }

    /** The scheme for a seed. Purple + dark is exactly the baseline (1.8) scheme. */
    static M3.Scheme scheme(int seed, boolean light) {
        seed |= 0xFF000000;
        if (seed == PURPLE && !light) return baseline();
        double[] lch = lch(seed);
        double h = lch[2];
        double pc = Math.max(36, Math.min(60, lch[1]));
        M3.Scheme s = new M3.Scheme();
        if (!light) {
            s.primary = tone(h, pc, 80);
            s.onPrimary = tone(h, pc, 20);
            s.primaryCont = tone(h, pc, 30);
            s.onPrimaryCont = tone(h, pc, 90);
            s.secondaryCont = tone(h, 16, 30);
            s.onSecondaryCont = tone(h, 16, 90);
            s.tertiaryCont = tone(h + 60, 24, 30);
            s.background = tone(h, 4, 6);
            s.surfaceLow = tone(h, 4, 10);
            s.surface = tone(h, 4, 12);
            s.surfaceHigh = tone(h, 4, 17);
            s.surfaceHighest = tone(h, 4, 22);
            s.onSurface = tone(h, 4, 90);
            s.onSurfaceVar = tone(h, 8, 80);
            s.outline = tone(h, 8, 60);
            s.outlineVar = tone(h, 8, 30);
            s.error = 0xFFF2B8B5;
            s.tintTop = tone(h, 16, 11);
        } else {
            s.primary = tone(h, pc, 40);
            s.onPrimary = tone(h, pc, 100);
            s.primaryCont = tone(h, pc, 90);
            s.onPrimaryCont = tone(h, pc, 10);
            s.secondaryCont = tone(h, 16, 90);
            s.onSecondaryCont = tone(h, 16, 10);
            s.tertiaryCont = tone(h + 60, 24, 90);
            s.background = tone(h, 4, 98);
            s.surfaceLow = tone(h, 4, 96);
            s.surface = tone(h, 4, 94);
            s.surfaceHigh = tone(h, 4, 92);
            s.surfaceHighest = tone(h, 4, 90);
            s.onSurface = tone(h, 4, 10);
            s.onSurfaceVar = tone(h, 8, 30);
            s.outline = tone(h, 8, 50);
            s.outlineVar = tone(h, 8, 80);
            s.error = 0xFFB3261E;
            s.tintTop = tone(h, 16, 94);
        }
        return s;
    }

    /** The baseline Material 3 dark scheme (purple): MaterialTV's colours up to 1.8. */
    static M3.Scheme baseline() {
        M3.Scheme s = new M3.Scheme();
        s.background = 0xFF141218; s.surfaceLow = 0xFF1D1B20; s.surface = 0xFF211F26; s.surfaceHigh = 0xFF2B2930;
        s.surfaceHighest = 0xFF36343B; s.onSurface = 0xFFE6E0E9; s.onSurfaceVar = 0xFFCAC4D0; s.outline = 0xFF938F99;
        s.outlineVar = 0xFF49454F; s.primary = 0xFFD0BCFF; s.onPrimary = 0xFF381E72; s.primaryCont = 0xFF4F378B;
        s.onPrimaryCont = 0xFFEADDFF; s.secondaryCont = 0xFF4A4458; s.onSecondaryCont = 0xFFE8DEF8;
        s.tertiaryCont = 0xFF633B48; s.error = 0xFFF2B8B5; s.tintTop = 0xFF231C33;
        return s;
    }

    // ---- CIE L*C*h <-> sRGB (D65) ------------------------------------------------------------

    private static final double XN = 0.95047, YN = 1.0, ZN = 1.08883;
    private static final double EPS = 216.0 / 24389.0, KAPPA = 24389.0 / 27.0;

    /** {L*, C*, h in degrees} of an sRGB colour. */
    static double[] lch(int argb) {
        double r = lin((argb >> 16) & 0xFF), g = lin((argb >> 8) & 0xFF), b = lin(argb & 0xFF);
        double x = 0.4124564 * r + 0.3575761 * g + 0.1804375 * b;
        double y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b;
        double z = 0.0193339 * r + 0.1191920 * g + 0.9503041 * b;
        double fx = f(x / XN), fy = f(y / YN), fz = f(z / ZN);
        double l = 116 * fy - 16, a = 500 * (fx - fy), bb = 200 * (fy - fz);
        double h = Math.toDegrees(Math.atan2(bb, a));
        if (h < 0) h += 360;
        return new double[] { l, Math.hypot(a, bb), h };
    }

    /** The colour at lightness tone (0..100) with this hue, and this chroma or as much as sRGB allows. */
    static int tone(double hue, double chroma, double tone) {
        if (tone >= 100) return 0xFFFFFFFF;
        if (tone <= 0) return 0xFF000000;
        double[] rgb = new double[3];
        if (toLinear(tone, chroma, hue, rgb)) return pack(rgb);
        double lo = 0, hi = chroma;
        for (int i = 0; i < 22; i++) {
            double mid = (lo + hi) / 2;
            if (toLinear(tone, mid, hue, rgb)) lo = mid; else hi = mid;
        }
        toLinear(tone, lo, hue, rgb);
        return pack(rgb);
    }

    /** Linear sRGB for L*C*h into out; true when it's inside the sRGB gamut. */
    private static boolean toLinear(double l, double c, double hDeg, double[] out) {
        double h = Math.toRadians(hDeg);
        double a = Math.cos(h) * c, b = Math.sin(h) * c;
        double fy = (l + 16) / 116, fx = fy + a / 500, fz = fy - b / 200;
        double x = finv(fx) * XN, y = finv(fy) * YN, z = finv(fz) * ZN;
        out[0] = 3.2404542 * x - 1.5371385 * y - 0.4985314 * z;
        out[1] = -0.9692660 * x + 1.8760108 * y + 0.0415560 * z;
        out[2] = 0.0556434 * x - 0.2040259 * y + 1.0572252 * z;
        for (double v : out) if (v < -1e-4 || v > 1 + 1e-4) return false;
        return true;
    }

    private static int pack(double[] rgb) {
        return 0xFF000000 | gam(rgb[0]) << 16 | gam(rgb[1]) << 8 | gam(rgb[2]);
    }

    private static double lin(int c8) {
        double c = c8 / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static int gam(double v) {
        v = Math.max(0, Math.min(1, v));
        double c = v <= 0.0031308 ? v * 12.92 : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
        return (int) Math.round(Math.max(0, Math.min(1, c)) * 255);
    }

    private static double f(double t) { return t > EPS ? Math.cbrt(t) : (KAPPA * t + 16) / 116; }

    private static double finv(double f) {
        double f3 = f * f * f;
        return f3 > EPS ? f3 : (116 * f - 16) / KAPPA;
    }
}
