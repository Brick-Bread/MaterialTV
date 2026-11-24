package app.m3home;

import android.content.Context;
import android.graphics.drawable.Drawable;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** The Home screen background: gradient, solid colour, a downloaded wallpaper or your own picture. (1.9.0 contract stub; WP2 fills in.) */
final class Backdrop {
    private Backdrop() {}

    static final int[] SOLID_PRESETS = { 0xFF141218, 0xFF1B1F2B, 0xFF10201C, 0xFF2A1A1F, 0xFF000000 };

    /** The background drawable (cached; fine on the UI thread after prepare()). */
    static Drawable get(Context c) { return new Glow(); }
    /** Decodes, blurs and dims off the main thread, then runs done on the main thread. */
    static void prepare(Context c, Runnable done) { if (done != null) done.run(); }
    /** True when the background is a picture (so text over it needs a shadow). */
    static boolean isImage(Context c) { return false; }
    /** Downloaded wallpaper pictures you can use: {id "<theme>/<file>", label}. */
    static List<String[]> wallpaperOptions(Context c) { return new ArrayList<>(); }
    /** Saves an uploaded picture as the background. Returns null when fine, else a message. */
    static String saveUpload(Context c, InputStream in, int length) { return "Not available yet"; }
    /** Call after changing any background pref: drops the cache, refreshes the seed colour, bumps the look. */
    static void changed(Context c) { Prefs.bumpLook(c); }
}
