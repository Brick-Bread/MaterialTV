package app.m3home;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;

import java.io.File;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Installed apps, and the user's favourites (an ordered list of package names). */
final class Apps {

    static final class App {
        final String pkg;
        final String label;
        final ComponentName component;
        final boolean leanback;
        final Drawable icon;   // the sharpest icon the app ships (xxxhdpi when it has one)
        final int tint;        // tonal card colour taken from the icon
        final Bitmap art;      // the user's own tile picture, or null

        App(String pkg, String label, ComponentName component, boolean leanback, Drawable icon, Bitmap art) {
            this.pkg = pkg;
            this.label = label;
            this.component = component;
            this.leanback = leanback;
            this.icon = icon;
            this.art = art;
            this.tint = M3.containerFor(icon);
        }

        Intent launchIntent() {
            return new Intent(Intent.ACTION_MAIN)
                    .addCategory(leanback ? Intent.CATEGORY_LEANBACK_LAUNCHER : Intent.CATEGORY_LAUNCHER)
                    .setComponent(component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        }
    }

    /** Pinned on first run when installed. */
    private static final String[] DEFAULT_FAVS = {
            "org.smarttube.stable",          // SmartTube (current package id)
            "com.teamsmart.videomanager.tv", // SmartTube (older package id)
            "com.tailscale.ipn",             // Tailscale
    };

    private static final String KEY_RECENTS = "recents";
    private static final int MAX_RECENTS = 10;
    private static final String KEY_FAVS = "favourites";
    // Bumped when DEFAULT_FAVS gains an app, so it is added once without dropping user pins.
    private static final String KEY_INIT = "favourites_initialised_v2";

    private final Context context;
    private final SharedPreferences prefs;

    Apps(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences("m3home", Context.MODE_PRIVATE);
    }

    /** Slow (loads icons): call off the main thread. */
    List<App> load() {
        PackageManager pm = context.getPackageManager();
        Map<String, App> byPkg = new LinkedHashMap<>();
        collect(pm, byPkg, Intent.CATEGORY_LEANBACK_LAUNCHER, true);
        collect(pm, byPkg, Intent.CATEGORY_LAUNCHER, false);
        List<App> list = new ArrayList<>(byPkg.values());
        final Collator collator = Collator.getInstance();
        Collections.sort(list, (a, b) -> collator.compare(a.label, b.label));
        return list;
    }

    private void collect(PackageManager pm, Map<String, App> out, String category, boolean leanback) {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo r : pm.queryIntentActivities(query, 0)) {
            ActivityInfo ai = r.activityInfo;
            if (ai == null || ai.packageName.equals(context.getPackageName())) continue;
            if (out.containsKey(ai.packageName)) continue; // TV entry wins over the phone one
            Drawable icon = sharpIcon(pm, ai);
            if (icon == null) {
                try { icon = r.loadIcon(pm); } catch (RuntimeException e) { icon = pm.getDefaultActivityIcon(); }
            }
            CharSequence label = r.loadLabel(pm);
            out.put(ai.packageName, new App(ai.packageName,
                    label == null ? ai.packageName : label.toString(),
                    new ComponentName(ai.packageName, ai.name), leanback, icon, loadArt(ai.packageName)));
        }
    }

    /** The app's icon at the highest density it ships, so it stays crisp when drawn large. */
    private static Drawable sharpIcon(PackageManager pm, ActivityInfo ai) {
        try {
            int id = ai.getIconResource();
            if (id == 0) return null;
            Resources res = pm.getResourcesForApplication(ai.applicationInfo);
            return res.getDrawableForDensity(id, DisplayMetrics.DENSITY_XXXHIGH, null);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- custom tile pictures -------------------------------------------------------------

    private static final String KEY_ART = "tile_art:";

    /** Where you can drop your own tile pictures (any JPG/PNG/WebP, ideally 16:10). */
    static File tilesDir(Context c) {
        File d = c.getExternalFilesDir("tiles");
        if (d != null && !d.exists()) d.mkdirs();
        return d;
    }

    /** Pictures you can choose for a tile: the tiles folder first, then screensaver pictures. */
    List<File> artChoices() {
        List<File> out = new ArrayList<>();
        File dir = tilesDir(context);
        File[] files = dir == null ? null : dir.listFiles();
        if (files != null) {
            List<File> own = new ArrayList<>();
            for (File f : files) if (f.isFile() && ScreensaverView.isImage(f)) own.add(f);
            Collections.sort(own);
            out.addAll(own);
        }
        for (File f : ScreensaverView.listMedia(context)) if (ScreensaverView.isImage(f)) out.add(f);
        return out;
    }

    String artPath(String pkg) {
        return prefs.getString(KEY_ART + pkg, null);
    }

    void setArt(String pkg, File f) {
        if (f == null) prefs.edit().remove(KEY_ART + pkg).apply();
        else prefs.edit().putString(KEY_ART + pkg, f.getAbsolutePath()).apply();
    }

    private Bitmap loadArt(String pkg) {
        String path = artPath(pkg);
        if (path == null) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            if (o.outWidth <= 0) return null;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= 560 && o.outHeight / (sample * 2) >= 350) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            return BitmapFactory.decodeFile(path, o2);
        } catch (OutOfMemoryError | RuntimeException e) {
            return null;
        }
    }

    // ---- stored package lists ----------------------------------------------------------

    private List<String> list(String key) {
        List<String> out = new ArrayList<>();
        for (String p : prefs.getString(key, "").split(",")) {
            if (!p.isEmpty() && !out.contains(p)) out.add(p);
        }
        return out;
    }

    private void putList(String key, List<String> pkgs) {
        StringBuilder sb = new StringBuilder();
        for (String p : pkgs) {
            if (sb.length() > 0) sb.append(',');
            sb.append(p);
        }
        prefs.edit().putString(key, sb.toString()).apply();
    }

    // ---- hidden from All apps ------------------------------------------------------------

    private static final String KEY_HIDDEN = "hidden";

    List<String> hidden() {
        return list(KEY_HIDDEN);
    }

    boolean isHidden(String pkg) {
        return hidden().contains(pkg);
    }

    void setHidden(String pkg, boolean hide) {
        List<String> h = hidden();
        boolean changed = hide ? !h.contains(pkg) && h.add(pkg) : h.remove(pkg);
        if (changed) putList(KEY_HIDDEN, h);
    }

    // ---- recently used (apps opened from MaterialTV, newest first) -------------------------

    List<String> recents() {
        return list(KEY_RECENTS);
    }

    void recordLaunch(String pkg) {
        List<String> r = recents();
        r.remove(pkg);
        r.add(0, pkg);
        while (r.size() > MAX_RECENTS) r.remove(r.size() - 1);
        putList(KEY_RECENTS, r);
    }

    void clearRecents() {
        putList(KEY_RECENTS, new ArrayList<String>());
    }

    void unhideAll() {
        putList(KEY_HIDDEN, new ArrayList<String>());
    }

    void forgetRecent(String pkg) {
        List<String> r = recents();
        if (r.remove(pkg)) putList(KEY_RECENTS, r);
    }

    // ---- favourites -------------------------------------------------------------------

    List<String> favourites() {
        return list(KEY_FAVS);
    }

    private void save(List<String> favs) {
        putList(KEY_FAVS, favs);
    }

    boolean isFavourite(String pkg) {
        return favourites().contains(pkg);
    }

    void toggle(String pkg) {
        List<String> f = favourites();
        if (!f.remove(pkg)) f.add(pkg);
        save(f);
    }

    /** Moves a favourite one step left (-1) or right (+1). */
    void move(String pkg, int delta) {
        List<String> f = favourites();
        int i = f.indexOf(pkg);
        int j = i + delta;
        if (i < 0 || j < 0 || j >= f.size()) return;
        Collections.swap(f, i, j);
        save(f);
    }

    /** First run only: pin a couple of sensible apps if they are installed. */
    void seedDefaults(List<App> installed) {
        if (prefs.getBoolean(KEY_INIT, false)) return;
        List<String> f = favourites();
        for (String d : DEFAULT_FAVS) {
            for (App a : installed) {
                if (a.pkg.equals(d) && !f.contains(d)) f.add(d);
            }
        }
        save(f);
        prefs.edit().putBoolean(KEY_INIT, true).apply();
    }
}
