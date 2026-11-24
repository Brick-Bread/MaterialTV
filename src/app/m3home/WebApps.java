package app.m3home;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * MaterialTV web apps: full-screen TV apps written in HTML. A web app is a small JSON manifest at
 * an https address (see docs/apps.md). Installing one only stores its checked manifest and icon;
 * the app itself is a web page that WebAppActivity shows full screen.
 *
 * Installed web apps are listed in All apps (and can be favourited) as "webapp:<id>", so the rest
 * of Home treats them like any other app.
 */
final class WebApps {
    private WebApps() {}

    static final int API = 1;
    static final String PREFIX = "webapp:";
    private static final String KEY = "webapps_installed"; // JSON array of checked manifests
    private static final String ICONS = "webapp-icons";
    static final int MAX_INSTALLED = 40;
    private static final int MAX_ICON = 512 * 1024, MAX_ORIGINS = 10;
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,39}");
    static final String[] PERMISSIONS = { "network", "storage", "media" };

    /** An installed web app (a checked manifest). */
    static final class App {
        final String id, name, author, description, version, startUrl, manifestUrl, iconUrl;
        final List<String> origins;      // allowed origins, "https://host[:port]", the manifest's first
        final List<String> permissions;  // a subset of PERMISSIONS

        App(String id, String name, String author, String description, String version, String startUrl,
            String manifestUrl, String iconUrl, List<String> origins, List<String> permissions) {
            this.id = id; this.name = name; this.author = author; this.description = description;
            this.version = version; this.startUrl = startUrl; this.manifestUrl = manifestUrl;
            this.iconUrl = iconUrl; this.origins = origins; this.permissions = permissions;
        }

        String pkg() { return PREFIX + id; }

        boolean may(String permission) { return permissions.contains(permission); }

        /** True when the url is https on one of this app's origins. */
        boolean allows(String url) {
            String o = origin(url);
            return o != null && origins.contains(o);
        }

        JSONObject json() throws JSONException {
            return new JSONObject().put("id", id).put("name", name).put("author", author)
                    .put("description", description).put("version", version).put("start_url", startUrl)
                    .put("manifest_url", manifestUrl).put("icon_url", iconUrl == null ? "" : iconUrl)
                    .put("origins", new JSONArray(origins)).put("permissions", new JSONArray(permissions));
        }

        static App fromJson(JSONObject o) {
            try {
                List<String> origins = strings(o.optJSONArray("origins")), perms = strings(o.optJSONArray("permissions"));
                String id = o.getString("id"), start = o.getString("start_url");
                if (!ID.matcher(id).matches() || origins.isEmpty()) return null;
                String icon = o.optString("icon_url", "");
                App a = new App(id, o.getString("name"), o.optString("author"), o.optString("description"),
                        o.optString("version"), start, o.getString("manifest_url"), icon.isEmpty() ? null : icon,
                        origins, perms);
                return a.allows(start) ? a : null;
            } catch (JSONException e) {
                return null;
            }
        }
    }

    // ---- the installed list ----------------------------------------------------------------

    static List<App> installed(Context c) {
        List<App> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Prefs.of(c).getString(KEY, "[]"));
            for (int i = 0; i < a.length(); i++) {
                App app = App.fromJson(a.optJSONObject(i) == null ? new JSONObject() : a.optJSONObject(i));
                if (app != null) out.add(app);
            }
        } catch (JSONException ignored) {
            // A broken list reads as empty.
        }
        return out;
    }

    static App get(Context c, String id) {
        for (App a : installed(c)) if (a.id.equals(id)) return a;
        return null;
    }

    /** The installed app that came from this manifest address, or null. */
    static App byManifest(Context c, String manifestUrl) {
        for (App a : installed(c)) if (a.manifestUrl.equals(manifestUrl)) return a;
        return null;
    }

    /** "webapp:<id>" -> id, else null. */
    static String idOf(String pkg) {
        return pkg != null && pkg.startsWith(PREFIX) ? pkg.substring(PREFIX.length()) : null;
    }

    private static synchronized void save(Context c, List<App> apps) {
        JSONArray a = new JSONArray();
        try {
            for (App app : apps) a.put(app.json());
        } catch (JSONException e) {
            return;
        }
        Prefs.of(c).edit().putString(KEY, a.toString()).commit();
    }

    /** Removes a web app, its icon and its tile choices. Its web storage stays (see docs/apps.md). */
    static synchronized void uninstall(Context c, String id) {
        List<App> list = installed(c), keep = new ArrayList<>();
        for (App a : list) if (!a.id.equals(id)) keep.add(a);
        save(c, keep);
        iconFile(c, id).delete();
        Apps apps = new Apps(c);
        if (apps.isFavourite(PREFIX + id)) apps.toggle(PREFIX + id);
        apps.forgetRecent(PREFIX + id);
        apps.setHidden(PREFIX + id, false);
    }

    // ---- installing ------------------------------------------------------------------------

    /**
     * Fetches, checks and installs (or updates) the web app at this manifest address. Returns
     * null when installed, else a short reason. Network: call off the main thread.
     */
    static String install(Context c, String manifestUrl) {
        if (!Store.httpsOk(manifestUrl)) return "The manifest must be an https link";
        String body = Store.fetchText(manifestUrl);
        if (body == null) return "Couldn't download the manifest: " + Store.lastProblem(manifestUrl);
        App app;
        try {
            Object parsed = parse(manifestUrl, new JSONObject(body));
            if (parsed instanceof String) return (String) parsed;
            app = (App) parsed;
        } catch (JSONException e) {
            return "The manifest isn't valid JSON";
        }
        synchronized (WebApps.class) {
            List<App> list = installed(c);
            int at = -1;
            for (int i = 0; i < list.size(); i++) {
                App old = list.get(i);
                if (old.id.equals(app.id)) {
                    if (!old.manifestUrl.equals(manifestUrl)) return "Another web app already uses the id \"" + app.id + "\"";
                    at = i;
                    boolean sameIcon = old.iconUrl == null ? app.iconUrl == null : old.iconUrl.equals(app.iconUrl);
                    if (!sameIcon) iconFile(c, app.id).delete(); // the manifest names a new icon
                }
            }
            if (at < 0 && list.size() >= MAX_INSTALLED) return "At most " + MAX_INSTALLED + " web apps";
            if (at >= 0) list.set(at, app); else list.add(app);
            save(c, list);
        }
        fetchIcon(c, app);
        return null;
    }

    /** Re-reads each installed app's manifest (name, icon, start page, origins). Off the main thread. */
    static void refreshAll(Context c) {
        for (App a : installed(c)) install(c, a.manifestUrl); // offline: the old copy stays
    }

    /**
     * Checks a manifest. Returns the App, or a String saying what is wrong. The rules are the ones
     * docs/apps.md documents: api 1, type "webapp", an id, a name, an https start_url on the
     * manifest's own origin or one listed in "origins".
     */
    static Object parse(String manifestUrl, JSONObject m) {
        if (m.optInt("api", 0) != API) return "This web app needs a newer MaterialTV (api " + m.opt("api") + ")";
        if (!"webapp".equals(m.optString("type"))) return "That manifest isn't a MaterialTV web app";
        String id = m.optString("id");
        if (!ID.matcher(id).matches()) return "The manifest's id must be lowercase letters, digits, . _ - (up to 40)";
        String name = clean(m.optString("name"), 40);
        if (name.isEmpty()) return "The manifest needs a name";
        String orientation = m.optString("orientation", "landscape");
        if (!orientation.equals("landscape")) return "Only landscape web apps are supported";

        String home = origin(manifestUrl);
        if (home == null) return "The manifest must be an https link";
        List<String> origins = new ArrayList<>();
        origins.add(home);
        JSONArray extra = m.optJSONArray("origins");
        for (int i = 0; extra != null && i < extra.length() && i < MAX_ORIGINS; i++) {
            String raw = extra.optString(i, "");
            String o = origin(raw);
            if (o == null || !o.equals(raw.replaceAll("/+$", "").toLowerCase(Locale.ROOT))) {
                return "Each origin must look like https://example.com (no path)";
            }
            if (!origins.contains(o)) origins.add(o);
        }
        String start = resolve(manifestUrl, m.optString("start_url", ""));
        if (start == null || !Store.httpsOk(start)) return "start_url must be an https link";
        if (!origins.contains(origin(start))) return "start_url must be on the manifest's origin or listed in origins";

        String icon = m.optString("icon", "");
        icon = icon.isEmpty() ? null : resolve(manifestUrl, icon);
        if (icon != null && !Store.httpsOk(icon)) icon = null;

        List<String> perms = new ArrayList<>();
        JSONArray p = m.optJSONArray("permissions");
        for (int i = 0; p != null && i < p.length(); i++) {
            String s = p.optString(i);
            for (String known : PERMISSIONS) if (known.equals(s) && !perms.contains(s)) perms.add(s);
        }
        if (p == null) { perms.add("network"); perms.add("storage"); } // a sensible default
        return new App(id, name, clean(m.optString("author"), 60), clean(m.optString("description"), 200),
                clean(m.optString("version"), 20), start, manifestUrl, icon, origins, perms);
    }

    // ---- urls --------------------------------------------------------------------------------

    /** "https://Host:443/x" -> "https://host"; non-https (or odd) -> null. */
    static String origin(String url) {
        if (url == null) return null;
        try {
            URI u = new URI(url);
            if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null || u.getRawUserInfo() != null) return null;
            int port = u.getPort();
            return "https://" + u.getHost().toLowerCase(Locale.ROOT) + (port == -1 || port == 443 ? "" : ":" + port);
        } catch (Exception e) {
            return null;
        }
    }

    private static String resolve(String base, String ref) {
        if (ref == null || ref.trim().isEmpty() || ref.length() > Store.MAX_URL) return null;
        try {
            return new URI(base).resolve(ref.trim()).toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String clean(String s, int max) {
        s = s == null ? "" : s.replaceAll("\\p{Cntrl}", " ").trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static List<String> strings(JSONArray a) {
        List<String> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) out.add(a.optString(i));
        return out;
    }

    // ---- icons -------------------------------------------------------------------------------

    private static File iconFile(Context c, String id) { return new File(new File(c.getFilesDir(), ICONS), id + ".img"); }

    private static void fetchIcon(Context c, App a) {
        if (a.iconUrl == null) return;
        File f = iconFile(c, a.id);
        if (f.isFile()) return;
        byte[] b = Store.fetchBytes(a.iconUrl, MAX_ICON);
        if (b == null) return;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(b, 0, b.length, o);
        if (o.outWidth <= 0 || o.outWidth > 2048 || o.outHeight > 2048) return;
        f.getParentFile().mkdirs();
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(b);
        } catch (IOException e) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(f)) tmp.delete();
    }

    /** The app's icon, or its first letter on a steady colour. */
    static Drawable icon(Context c, App a) {
        File f = iconFile(c, a.id);
        if (f.isFile()) {
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getPath(), o);
                int sample = 1;
                while (o.outWidth / (sample * 2) >= 192) sample *= 2;
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = sample;
                Bitmap bm = BitmapFactory.decodeFile(f.getPath(), o2);
                if (bm != null) return new BitmapDrawable(c.getResources(), bm);
            } catch (OutOfMemoryError | RuntimeException ignored) {
                // Fall through to the letter.
            }
        }
        Bitmap bm = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bm);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float hue = (a.id.hashCode() & 0x7FFFFFFF) % 360;
        p.setColor(Color.HSVToColor(new float[] { hue, 0.35f, 0.85f }));
        cv.drawCircle(96, 96, 96, p);
        p.setColor(Color.HSVToColor(new float[] { hue, 0.6f, 0.25f }));
        p.setTextSize(104);
        p.setTypeface(M3.MEDIUM);
        p.setTextAlign(Paint.Align.CENTER);
        String letter = a.name.substring(0, 1).toUpperCase(Locale.ROOT);
        cv.drawText(letter, 96, 96 - (p.descent() + p.ascent()) / 2, p);
        return new BitmapDrawable(c.getResources(), bm);
    }

    // ---- launching -----------------------------------------------------------------------------

    /**
     * Opens a web app full screen. The app and the theme travel in the intent, because
     * WebAppActivity runs in its own process (":webapp"), whose copy of the settings may be stale.
     */
    static Intent launchIntent(Context c, String id) {
        Intent i = new Intent(c, WebAppActivity.class).putExtra(WebAppActivity.EXTRA_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        App a = get(c, id);
        try {
            if (a != null) i.putExtra(WebAppActivity.EXTRA_APP, a.json().toString());
            i.putExtra(WebAppActivity.EXTRA_THEME, theme(c).toString());
        } catch (JSONException ignored) {
            // WebAppActivity falls back to reading the settings itself.
        }
        return i;
    }

    /** MaterialTV's current colour roles as CSS hex ("#RRGGBB"), plus isLight. */
    static JSONObject theme(Context c) throws JSONException {
        M3.init(c);
        return new JSONObject()
                .put("isLight", M3.lightMode)
                .put("background", hex(M3.BACKGROUND))
                .put("surfaceLow", hex(M3.SURFACE_LOW))
                .put("surface", hex(M3.SURFACE))
                .put("surfaceHigh", hex(M3.SURFACE_HIGH))
                .put("surfaceHighest", hex(M3.SURFACE_HIGHEST))
                .put("onSurface", hex(M3.ON_SURFACE))
                .put("onSurfaceVariant", hex(M3.ON_SURFACE_VAR))
                .put("outline", hex(M3.OUTLINE))
                .put("outlineVariant", hex(M3.OUTLINE_VAR))
                .put("primary", hex(M3.PRIMARY))
                .put("onPrimary", hex(M3.ON_PRIMARY))
                .put("primaryContainer", hex(M3.PRIMARY_CONT))
                .put("onPrimaryContainer", hex(M3.ON_PRIMARY_CONT))
                .put("secondaryContainer", hex(M3.SECONDARY_CONT))
                .put("onSecondaryContainer", hex(M3.ON_SECONDARY_CONT))
                .put("tertiaryContainer", hex(M3.TERTIARY_CONT))
                .put("error", hex(M3.ERROR));
    }

    static String hex(int argb) { return String.format(Locale.ROOT, "#%06X", argb & 0xFFFFFF); }
}
