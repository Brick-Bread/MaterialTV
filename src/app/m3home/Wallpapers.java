package app.m3home;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The screensaver's wallpaper themes, downloaded on demand. The pictures and live wallpapers live
 * as assets of the GitHub release "wallpapers-v1", listed with their size and SHA-256 in its
 * manifest.json. Turning a theme on downloads its files into screensaver/<theme>/; turning it off
 * deletes that folder. One job runs at a time, off the main thread; Settings listens for progress.
 */
final class Wallpapers {
    private Wallpapers() {}

    static final String BASE = "https://github.com/" + Updater.REPO + "/releases/download/wallpapers-v1/";
    private static final String CACHE = "wallpapers-manifest.json"; // last manifest seen, for offline sizes
    private static final long MAX_FILE = 100L * 1024 * 1024;
    private static final long SPARE = 50L * 1024 * 1024;              // leave room for everything else

    /** One file from the manifest. */
    static final class Item {
        final String theme, file, sha256;
        final long size;

        Item(String theme, String file, long size, String sha256) {
            this.theme = theme;
            this.file = file;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    /** Told (on the main thread) whenever a theme's progress or state changes. */
    interface Listener { void changed(String theme); }

    private static final ExecutorService jobs = Executors.newSingleThreadExecutor(r -> new Thread(r, "m3home-wallpapers"));
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Map<String, String> status = new ConcurrentHashMap<>();   // theme -> progress or problem
    private static final Map<String, Integer> generation = new HashMap<>();        // bumped by every request
    private static final Map<String, Boolean> downloading = new ConcurrentHashMap<>();
    private static volatile Listener listener;

    static void setListener(Listener l) { listener = l; }

    static File dir(Context c, String theme) {
        File root = ScreensaverView.mediaDir(c);
        return root == null ? null : new File(root, theme);
    }

    /** The pictures and videos in a theme's folder. */
    static List<File> files(Context c, String theme) {
        List<File> out = new ArrayList<>();
        File d = dir(c, theme);
        File[] all = d == null ? null : d.listFiles();
        if (all != null) for (File f : all) if (f.isFile() && (ScreensaverView.isImage(f) || ScreensaverView.isVideo(f))) out.add(f);
        return out;
    }

    static boolean has(Context c, String theme) { return !files(c, theme).isEmpty(); }

    static boolean busy(String theme) { return downloading.containsKey(theme); }

    /** What's happening with a theme right now ("Downloading 3 of 11…", a problem), or null. */
    static String status(String theme) { return status.get(theme); }

    /** The theme's files in the last manifest we saw, or null if we've never seen one. */
    static List<Item> known(Context c, String theme) {
        List<Item> all = parse(read(new File(c.getFilesDir(), CACHE)));
        if (all == null) return null;
        List<Item> out = new ArrayList<>();
        for (Item i : all) if (i.theme.equals(theme)) out.add(i);
        return out;
    }

    /** How many of the theme's manifest files are already here (by name and size). */
    static int present(Context c, List<Item> items) {
        int n = 0;
        File d = dir(c, items.isEmpty() ? "" : items.get(0).theme);
        for (Item i : items) {
            File f = new File(d, i.file);
            if (f.isFile() && f.length() == i.size) n++;
        }
        return n;
    }

    /** Starts (or queues) downloading a theme. Files already here and correct are kept. */
    static void download(Context c, String theme) {
        final Context app = c.getApplicationContext();
        final int gen = bump(theme);
        downloading.put(theme, true);
        status.put(theme, "Waiting…");
        changed(theme);
        jobs.execute(() -> {
            String problem;
            try {
                problem = fetch(app, theme, gen);
            } catch (Exception e) {
                problem = "Download failed";
            }
            if (!current(theme, gen)) return; // turned off meanwhile; that request cleans up
            downloading.remove(theme);
            settle(app, theme, problem);
            changed(theme);
        });
    }

    /** Stops any download of the theme and deletes its folder. */
    static void remove(Context c, String theme) {
        final Context app = c.getApplicationContext();
        bump(theme); // a running download notices and stops
        downloading.remove(theme);
        status.put(theme, "Removing…");
        changed(theme);
        jobs.execute(() -> {
            deleteDir(dir(app, theme));
            status.remove(theme);
            changed(theme);
        });
    }

    // ---- the work (on the jobs thread) --------------------------------------------------

    /** Downloads what's missing. Returns null when the theme is complete, else a short problem. */
    private static String fetch(Context c, String theme, int gen) {
        if (!online(c)) return "No internet connection";
        status.put(theme, "Getting the list…");
        changed(theme);
        String body = http(BASE + "manifest.json");
        List<Item> all = parse(body);
        if (all == null) return "Couldn't reach GitHub";
        write(new File(c.getFilesDir(), CACHE), body);

        List<Item> items = new ArrayList<>();
        for (Item i : all) if (i.theme.equals(theme)) items.add(i);
        if (items.isEmpty()) return "Nothing to download for this theme yet";

        File d = dir(c, theme);
        if (d == null) return "Storage isn't available";
        d.mkdirs();

        // What's missing, and is there room for it?
        List<Item> todo = new ArrayList<>();
        long need = 0, total = 0;
        for (Item i : items) {
            total += i.size;
            File f = new File(d, i.file);
            if (f.isFile() && f.length() == i.size && i.sha256.equals(sha256(f))) continue;
            todo.add(i);
            need += i.size;
        }
        if (!current(theme, gen)) return null;
        if (need > 0 && free(d) < need + SPARE) return "Not enough space (needs " + mb(need) + ")";

        long done = total - need;
        for (int k = 0; k < todo.size(); k++) {
            Item i = todo.get(k);
            int number = items.size() - todo.size() + k + 1;
            String line = "Downloading " + number + " of " + items.size() + "…";
            File part = new File(d, i.file + ".part"), dest = new File(d, i.file);
            long got = download(BASE + i.file, part, i.size, theme, gen, line, done, total);
            if (!current(theme, gen)) { part.delete(); return null; }
            if (got != i.size || !i.sha256.equals(sha256(part))) {
                part.delete();
                return got < 0 ? (online(c) ? "Download interrupted" : "No internet connection")
                        : "A file arrived damaged";
            }
            dest.delete();
            if (!part.renameTo(dest)) { part.delete(); return "Couldn't save the files"; }
            done += i.size;
        }
        return null;
    }

    /**
     * After a download: a complete theme just shows its files. If it stopped part-way, the theme is
     * switched off (what's here is kept) and the row offers to resume; if nothing arrived, it offers
     * to try again. The pictures you already have are never thrown away by a failed download.
     */
    private static void settle(Context c, String theme, String problem) {
        if (problem == null) { status.remove(theme); return; }
        List<Item> items = known(c, theme);
        boolean some = has(c, theme);
        if (some && items != null && !items.isEmpty() && present(c, items) < items.size()) {
            Prefs.of(c).edit().putBoolean(Prefs.SS_THEME + theme, false).apply();
            status.put(theme, problem + " · got " + present(c, items) + " of " + items.size() + " · select to resume");
        } else if (some) {
            status.remove(theme); // nothing new to fetch right now; keep showing what's here
        } else {
            status.put(theme, problem + " · select to try again");
        }
    }

    /** Streams url into to. Returns the bytes received, or -1 on a network error. */
    private static long download(String url, File to, long expected, String theme, int gen,
                                 String line, long before, long total) {
        HttpURLConnection con = null;
        try {
            con = open(url);
            if (con.getResponseCode() != 200) return -1;
            long got = 0, shown = 0;
            try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(to)) {
                byte[] buf = new byte[65536];
                int n;
                status.put(theme, line + " " + percent(before, total));
                changed(theme);
                while ((n = in.read(buf)) > 0) {
                    if (!current(theme, gen)) return got;
                    got += n;
                    if (got > expected || got > MAX_FILE) return got; // not the file we asked for
                    out.write(buf, 0, n);
                    if (got - shown > 1024 * 1024) {
                        shown = got;
                        status.put(theme, line + " " + percent(before + got, total));
                        changed(theme);
                    }
                }
            }
            return got;
        } catch (Exception e) {
            return -1;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    // ---- helpers ------------------------------------------------------------------------

    private static synchronized int bump(String theme) {
        Integer g = generation.get(theme);
        int next = g == null ? 1 : g + 1;
        generation.put(theme, next);
        return next;
    }

    private static synchronized boolean current(String theme, int gen) {
        Integer g = generation.get(theme);
        return g != null && g == gen;
    }

    private static void changed(String theme) {
        main.post(() -> {
            Listener l = listener;
            if (l != null) l.changed(theme);
        });
    }

    /** The manifest's files, keeping only entries that are safe to save; null if it isn't a manifest. */
    private static List<Item> parse(String body) {
        if (body == null) return null;
        try {
            JSONArray a = new JSONObject(body).getJSONArray("files");
            List<String> themes = Arrays.asList(Prefs.THEMES);
            List<Item> out = new ArrayList<>();
            for (int k = 0; k < a.length(); k++) {
                JSONObject o = a.getJSONObject(k);
                String theme = o.optString("theme"), file = o.optString("file");
                String sha = o.optString("sha256").toLowerCase(Locale.ROOT);
                long size = o.optLong("size");
                if (!themes.contains(theme) || !file.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) continue;
                File f = new File(file);
                if (!ScreensaverView.isImage(f) && !ScreensaverView.isVideo(f)) continue;
                if (!sha.matches("[0-9a-f]{64}") || size <= 0 || size > MAX_FILE) continue;
                out.add(new Item(theme, file, size, sha));
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean online(Context c) {
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return true; // can't tell: just try
        NetworkInfo ni = cm.getActiveNetworkInfo();
        return ni != null && ni.isConnected();
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(10_000);
        con.setReadTimeout(30_000);
        con.setInstanceFollowRedirects(true); // GitHub sends release files on from objects.githubusercontent.com
        con.setRequestProperty("User-Agent", "M3Home-Wallpapers");
        return con;
    }

    private static String http(String url) {
        HttpURLConnection con = null;
        try {
            con = open(url);
            if (con.getResponseCode() != 200) return null;
            try (InputStream in = con.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < 1_000_000) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private static String read(File f) {
        if (!f.isFile()) return null;
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private static void write(File f, String s) {
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(s.getBytes("UTF-8"));
        } catch (Exception ignored) {
            // Only used for offline sizes; fine to miss.
        }
    }

    private static void deleteDir(File d) {
        File[] all = d == null ? null : d.listFiles();
        if (all != null) for (File f : all) {
            if (f.isDirectory()) deleteDir(f);
            else f.delete();
        }
        if (d != null) d.delete();
    }

    private static long free(File d) {
        try {
            return new StatFs(d.getPath()).getAvailableBytes();
        } catch (Exception e) {
            return Long.MAX_VALUE; // can't tell: let the download find out
        }
    }

    private static String percent(long done, long total) {
        return total <= 0 ? "" : "(" + Math.min(100, done * 100 / total) + "%)";
    }

    static String mb(long bytes) {
        return Math.max(1, Math.round(bytes / (1024.0 * 1024))) + " MB";
    }

    private static String sha256(File f) {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
