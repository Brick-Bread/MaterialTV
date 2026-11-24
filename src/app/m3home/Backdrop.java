package app.m3home;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Home screen background: gradient, solid colour, a downloaded wallpaper or your own picture.
 *
 * Pictures are decoded small (about 960px wide, RGB_565), blurred on a quarter-size copy and dimmed
 * once, then kept as cache.jpg so the next start is quick. Only one prepared bitmap is held.
 */
final class Backdrop {
    private Backdrop() {}

    static final int[] SOLID_PRESETS = { 0xFF141218, 0xFF1B1F2B, 0xFF10201C, 0xFF2A1A1F, 0xFF000000 };

    private static final int WIDTH = 960;                 // width of the prepared picture
    private static final long MAX_UPLOAD = 8L * 1024 * 1024;
    private static final int MAX_SIDE = 1920;             // uploads are stored at most this big

    private static final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "m3home-backdrop"));
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Object UPLOAD_LOCK = new Object();
    private static Bitmap bitmap;   // the one prepared picture (guarded by Backdrop.class)
    private static String bitmapKey;

    /** The background drawable (cached; fine on the UI thread after prepare()). */
    static Drawable get(Context c) {
        String kind = Prefs.bgKind(c);
        if ("solid".equals(kind)) return new ColorDrawable(0xFF000000 | Prefs.of(c).getInt(Prefs.BG_COLOR, SOLID_PRESETS[0]));
        if (isImageKind(kind)) {
            String key = key(c);
            synchronized (Backdrop.class) {
                if (key != null && key.equals(bitmapKey) && bitmap != null && !bitmap.isRecycled()) {
                    BitmapDrawable d = new BitmapDrawable(c.getResources(), bitmap);
                    d.setFilterBitmap(true);
                    return d;
                }
            }
        }
        return new Glow();
    }

    /** Decodes, blurs and dims off the main thread, then runs done on the main thread. */
    static void prepare(Context c, Runnable done) {
        final Context app = c.getApplicationContext();
        final String key = isImageKind(Prefs.bgKind(app)) ? key(app) : null;
        boolean ready;
        synchronized (Backdrop.class) {
            ready = key == null || key.equals(bitmapKey) && bitmap != null;
        }
        if (ready) {
            if (done != null) main.post(done);
            return;
        }
        final File src = source(app);
        final int dim = Prefs.bgDim(app), blur = Prefs.bgBlur(app);
        final int height = height(app);
        worker.execute(() -> {
            synchronized (Backdrop.class) {
                if (key.equals(bitmapKey) && bitmap != null) { // a queued twin already made it
                    if (done != null) main.post(done);
                    return;
                }
            }
            Bitmap made = null;
            try {
                made = loadCache(app, key);
                if (made == null) {
                    made = render(src, WIDTH, height, dim, blur);
                    if (made != null) saveCache(app, key, made);
                }
            } catch (OutOfMemoryError | RuntimeException e) {
                made = null;
            }
            final Bitmap result = made;
            main.post(() -> {
                if (result != null) {
                    synchronized (Backdrop.class) {
                        // The old picture is only dropped, not recycled: a view may still be drawing it.
                        bitmap = result;
                        bitmapKey = key;
                    }
                }
                if (done != null) done.run();
            });
        });
    }

    /** True when the background is a picture (so text over it needs a shadow). */
    static boolean isImage(Context c) {
        return isImageKind(Prefs.bgKind(c)) && source(c) != null;
    }

    /** Downloaded wallpaper pictures you can use: {id "<theme>/<file>", label}. */
    static List<String[]> wallpaperOptions(Context c) {
        List<String[]> out = new ArrayList<>();
        for (String theme : Prefs.THEMES) {
            List<File> pics = new ArrayList<>();
            for (File f : Wallpapers.files(c, theme)) if (ScreensaverView.isImage(f)) pics.add(f);
            File[] sorted = pics.toArray(new File[0]);
            Arrays.sort(sorted, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            String name = theme.substring(0, 1).toUpperCase(Locale.ROOT) + theme.substring(1);
            int n = 1;
            for (File f : sorted) out.add(new String[] { theme + "/" + f.getName(), name + " " + n++ });
        }
        return out;
    }

    /** Saves an uploaded picture as the background. Returns null when fine, else a message. */
    static String saveUpload(Context c, InputStream in, int length) {
        if (length > MAX_UPLOAD) return "That picture is too big (8 MB at most).";
        synchronized (UPLOAD_LOCK) { // one upload at a time
            File dir = dir(c);
            File tmp = new File(dir, "custom.tmp"), fresh = new File(dir, "custom.new"), dest = new File(dir, "custom.jpg");
            try {
                long total = 0;
                try (OutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[16 * 1024];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        total += n;
                        if (total > MAX_UPLOAD) return "That picture is too big (8 MB at most).";
                        out.write(buf, 0, n);
                    }
                }
                if (total == 0) return "The picture didn't arrive. Try again.";
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(tmp.getPath(), bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return "That isn't a picture MaterialTV can read. Try a JPEG or PNG.";
                Bitmap bm = decodeLongSide(tmp, MAX_SIDE);
                if (bm == null) return "That isn't a picture MaterialTV can read. Try a JPEG or PNG.";
                float s = Math.min(1f, MAX_SIDE / (float) Math.max(bm.getWidth(), bm.getHeight()));
                if (s < 1f) {
                    Bitmap scaled = Bitmap.createScaledBitmap(bm, Math.max(1, Math.round(bm.getWidth() * s)),
                            Math.max(1, Math.round(bm.getHeight() * s)), true);
                    if (scaled != bm) bm.recycle();
                    bm = scaled;
                }
                try (OutputStream out = new FileOutputStream(fresh)) {
                    if (!bm.compress(Bitmap.CompressFormat.JPEG, 90, out)) throw new IOException("encode");
                } finally {
                    bm.recycle();
                }
                if (!fresh.renameTo(dest)) {
                    dest.delete();
                    if (!fresh.renameTo(dest)) return "Couldn't save the picture on the TV.";
                }
            } catch (IOException e) {
                return "Couldn't save the picture on the TV.";
            } catch (OutOfMemoryError e) {
                return "That picture is too big for this TV to open.";
            } finally {
                tmp.delete();
                fresh.delete();
            }
        }
        Prefs.of(c).edit().putString(Prefs.BG_KIND, "custom").apply();
        changed(c);
        return null;
    }

    /** Call after changing any background pref: drops the cache, refreshes the seed colour, bumps the look. */
    static void changed(Context c) {
        synchronized (Backdrop.class) {
            bitmap = null; // dropped, not recycled: Home may still be showing it until it rebuilds
            bitmapKey = null;
        }
        SharedPreferences.Editor e = Prefs.of(c).edit();
        e.putInt(Prefs.BG_SEED, seed(c));
        e.apply();
        Prefs.bumpLook(c);
    }

    // ---- internals ---------------------------------------------------------------------------

    private static boolean isImageKind(String kind) {
        return "wallpaper".equals(kind) || "custom".equals(kind);
    }

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "background");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    /** The picture file for the current background, or null when it isn't a picture or is missing. */
    private static File source(Context c) {
        String kind = Prefs.bgKind(c);
        File f = null;
        if ("custom".equals(kind)) {
            f = new File(new File(c.getFilesDir(), "background"), "custom.jpg");
        } else if ("wallpaper".equals(kind)) {
            String id = Prefs.of(c).getString(Prefs.BG_WALLPAPER, null);
            int slash = id == null ? -1 : id.indexOf('/');
            if (slash > 0) {
                String theme = id.substring(0, slash), name = id.substring(slash + 1);
                if (Arrays.asList(Prefs.THEMES).contains(theme) && !name.isEmpty()
                        && name.indexOf('/') < 0 && name.indexOf('\\') < 0 && !name.startsWith(".")) {
                    File d = Wallpapers.dir(c, theme);
                    if (d != null) f = new File(d, name);
                }
            }
            if (f != null && !ScreensaverView.isImage(f)) f = null;
        }
        return f != null && f.isFile() ? f : null;
    }

    /** What the prepared picture depends on, or null when there's no picture to show. */
    private static String key(Context c) {
        File f = source(c);
        if (f == null) return null;
        return f.getPath() + "|" + f.lastModified() + "|" + f.length() + "|" + Prefs.bgDim(c) + "|" + Prefs.bgBlur(c)
                + "|" + WIDTH + "x" + height(c);
    }

    /** The prepared picture's height: the screen's shape at WIDTH wide. */
    private static int height(Context c) {
        DisplayMetrics m = c.getResources().getDisplayMetrics();
        int w = Math.max(m.widthPixels, m.heightPixels), h = Math.min(m.widthPixels, m.heightPixels);
        if (w <= 0 || h <= 0) return WIDTH * 9 / 16;
        return Math.max(1, Math.round(WIDTH * (float) h / w));
    }

    /** Decodes at least minW x minH (by inSampleSize), RGB_565 unless alpha is wanted. */
    private static Bitmap decode(File f, int minW, int minH, boolean keepAlpha) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= minW && o.outHeight / (sample * 2) >= minH) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        d.inPreferredConfig = keepAlpha ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(f.getPath(), d);
    }

    /** Decodes with the longer side at least longSide (by inSampleSize), RGB_565. */
    private static Bitmap decodeLongSide(File f, int longSide) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int side = Math.max(o.outWidth, o.outHeight), sample = 1;
        while (side / (sample * 2) >= longSide) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        d.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(f.getPath(), d);
    }

    /** The picture cropped to fill w x h, blurred and dimmed. */
    private static Bitmap render(File src, int w, int h, int dim, int blur) {
        if (src == null) return null;
        Bitmap in = decode(src, w, h, false);
        if (in == null) return null;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(out);
        Paint filter = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        int radius = Math.round(Math.max(0, Math.min(100, blur)) * 0.1f); // at quarter size: up to 10px
        if (radius > 0) {
            int sw = Math.max(1, w / 4), sh = Math.max(1, h / 4);
            Bitmap small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
            new Canvas(small).drawBitmap(in, crop(in, sw, sh), new Rect(0, 0, sw, sh), filter);
            in.recycle();
            int[] px = new int[sw * sh];
            small.getPixels(px, 0, sw, 0, 0, sw, sh);
            boxBlur(px, sw, sh, radius);
            small.setPixels(px, 0, sw, 0, 0, sw, sh);
            canvas.drawBitmap(small, null, new Rect(0, 0, w, h), filter);
            small.recycle();
        } else {
            canvas.drawBitmap(in, crop(in, w, h), new Rect(0, 0, w, h), filter);
            in.recycle();
        }
        int a = Math.round(Math.max(0, Math.min(80, dim)) * 2.55f);
        if (a > 0) canvas.drawColor(a << 24);
        return out;
    }

    /** The centre part of bm with the same shape as w x h. */
    private static Rect crop(Bitmap bm, int w, int h) {
        int bw = bm.getWidth(), bh = bm.getHeight();
        if ((long) bw * h > (long) bh * w) { // wider than the screen
            int cw = Math.max(1, (int) ((long) bh * w / h));
            int x = (bw - cw) / 2;
            return new Rect(x, 0, x + cw, bh);
        }
        int ch = Math.max(1, (int) ((long) bw * h / w));
        int y = (bh - ch) / 2;
        return new Rect(0, y, bw, y + ch);
    }

    /** Three box blurs in a row, close to a gaussian. */
    private static void boxBlur(int[] px, int w, int h, int r) {
        int[] tmp = new int[px.length];
        for (int pass = 0; pass < 3; pass++) {
            blurLine(px, tmp, w, h, r, true);
            blurLine(tmp, px, w, h, r, false);
        }
    }

    private static void blurLine(int[] src, int[] dst, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w, len = horizontal ? w : h;
        int step = horizontal ? 1 : w;
        int div = r * 2 + 1;
        for (int line = 0; line < lines; line++) {
            int base = horizontal ? line * w : line;
            int sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = src[base + clamp(i, len) * step];
                sr += (p >> 16) & 0xFF; sg += (p >> 8) & 0xFF; sb += p & 0xFF;
            }
            for (int i = 0; i < len; i++) {
                dst[base + i * step] = 0xFF000000 | (sr / div) << 16 | (sg / div) << 8 | (sb / div);
                int out = src[base + clamp(i - r, len) * step], in = src[base + clamp(i + r + 1, len) * step];
                sr += ((in >> 16) & 0xFF) - ((out >> 16) & 0xFF);
                sg += ((in >> 8) & 0xFF) - ((out >> 8) & 0xFF);
                sb += (in & 0xFF) - (out & 0xFF);
            }
        }
    }

    private static int clamp(int i, int len) { return i < 0 ? 0 : i >= len ? len - 1 : i; }

    private static Bitmap loadCache(Context c, String key) {
        File dir = dir(c), img = new File(dir, "cache.jpg"), k = new File(dir, "cache.key");
        if (!img.isFile() || !k.isFile()) return null;
        try {
            byte[] b = java.nio.file.Files.readAllBytes(k.toPath());
            if (!key.equals(new String(b, StandardCharsets.UTF_8))) return null;
        } catch (IOException e) {
            return null;
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(img.getPath(), o);
    }

    private static void saveCache(Context c, String key, Bitmap bm) {
        File dir = dir(c), img = new File(dir, "cache.jpg"), k = new File(dir, "cache.key");
        k.delete(); // a half-written cache must never match
        try (OutputStream out = new FileOutputStream(img)) {
            if (!bm.compress(Bitmap.CompressFormat.JPEG, 92, out)) return;
        } catch (IOException e) {
            return;
        }
        try (OutputStream out = new FileOutputStream(k)) {
            out.write(key.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            k.delete();
        }
    }

    /** The colour the "From background" accent uses: the background's dominant hue, else purple. */
    private static int seed(Context c) {
        String kind = Prefs.bgKind(c);
        if ("solid".equals(kind)) return hueSeed(new int[] { Prefs.of(c).getInt(Prefs.BG_COLOR, SOLID_PRESETS[0]) }, 0.02);
        File f = isImageKind(kind) ? source(c) : null;
        if (f == null) return Palette.PURPLE;
        try {
            Bitmap bm = decode(f, 64, 64, false);
            if (bm == null) return Palette.PURPLE;
            Bitmap small = Bitmap.createScaledBitmap(bm, 32, 32, true);
            if (small != bm) bm.recycle();
            int[] px = new int[32 * 32];
            small.getPixels(px, 0, 32, 0, 0, 32, 32);
            small.recycle();
            return hueSeed(px, px.length * 0.04);
        } catch (OutOfMemoryError | RuntimeException e) {
            return Palette.PURPLE;
        }
    }

    /** Like M3.containerFor: the saturation-weighted mean hue as a seed colour, or purple for greys. */
    private static int hueSeed(int[] px, double minWeight) {
        float[] hsv = new float[3];
        double x = 0, y = 0, weight = 0;
        for (int p : px) {
            Color.colorToHSV(p, hsv);
            double w = hsv[1] * hsv[2];
            if (w < 0.08) continue;
            double a = Math.toRadians(hsv[0]);
            x += Math.cos(a) * w;
            y += Math.sin(a) * w;
            weight += w;
        }
        if (weight < minWeight || Math.hypot(x, y) < weight * 0.15) return Palette.PURPLE; // grey or no clear hue
        float hue = (float) Math.toDegrees(Math.atan2(y, x));
        if (hue < 0) hue += 360f;
        return Color.HSVToColor(new float[] { hue, 0.62f, 0.62f });
    }
}
