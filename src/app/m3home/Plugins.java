package app.m3home;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Plugins: extra Home widgets, Home rows, themes and shortcut tiles. Two kinds (docs/plugins.md):
 *
 * Web plugins are a JSON manifest at an https URL, installed from the phone page or the Get apps
 * row. No plugin code runs on the TV: MaterialTV fetches the manifest and the JSON its widgets and
 * rows point at (https only, size-limited, with timeouts), keeps the last good copy, and shows
 * only sanitised text and downsampled https pictures.
 *
 * Android plugins are separate apps with a ContentProvider that answers the "app.m3home.PLUGIN"
 * intent and names its authority in meta-data "app.m3home.plugin". They're found through the
 * package manager, stay off until switched on in Settings, and are only ever asked for
 * content://authority/widgets, /rows and /rows/ID. Their tiles may open activities (never
 * broadcasts or services) of other apps, through Intent.parseUri with the risky parts stripped.
 */
final class Plugins {
    private Plugins() {}

    static final int API = 1;
    static final String INTENT_ACTION = "app.m3home.PLUGIN";
    static final String META_AUTHORITY = "app.m3home.plugin";
    static final String META_DESCRIPTION = "app.m3home.plugin.description";
    static final String HOST_PERMISSION = "app.m3home.permission.PLUGIN_HOST";
    /** Home row ids: "plugin:" + plugin id + "/" + row id ("actions" = the plugin's shortcut tiles). */
    static final String ROW_PREFIX = "plugin:";
    static final String ACTIONS_ROW = "actions";
    /** A shortcut in the favourites list: "action:" + plugin id + "/" + action id. */
    static final String FAV_PREFIX = "action:";

    static final int MAX_JSON = 256 * 1024, MAX_IMAGE = 1024 * 1024, MAX_BACKGROUND = 8 * 1024 * 1024;
    static final int MAX_PLUGINS = 20, MAX_WIDGETS = 6, MAX_ROWS = 6, MAX_ACTIONS = 30, MAX_ITEMS = 40;
    private static final int MAX_URL = 500, MAX_BODY = 4096, MAX_HEADERS = 5;
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,39}");
    private static final Pattern PKG = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
    private static final Pattern HEADER = Pattern.compile("[A-Za-z0-9-]{1,40}");
    private static final long MINUTE = 60_000L;
    private static final int ANDROID_REFRESH_MIN = 15;
    private static final String CONFIRMED = "plugin_ok:", ROWS_CACHE = "plugin_rows:";

    // ---- the model ------------------------------------------------------------------------------

    /** One installed plugin (web or Android). */
    static final class Plugin {
        final String id, name, author, description, version, icon;
        final String url;                    // web: the manifest link; Android: null
        final String pkg, authority;         // Android: the app and its provider; web: null
        final List<Feed> widgets = new ArrayList<>(), rows = new ArrayList<>();
        final List<Action> actions = new ArrayList<>();
        JSONObject theme;                    // sanitised, or null

        Plugin(String id, String name, String author, String description, String version, String icon,
               String url, String pkg, String authority) {
            this.id = id; this.name = name; this.author = author; this.description = description;
            this.version = version; this.icon = icon; this.url = url; this.pkg = pkg; this.authority = authority;
        }

        boolean android() { return pkg != null; }

        /** "2 widgets · 1 row · a theme · 3 shortcuts". */
        String adds() {
            if (android()) { // it says what it has only when asked, once it's on
                return rows.isEmpty() ? "Widgets and rows from the app"
                        : "Widgets · " + rows.size() + (rows.size() == 1 ? " row" : " rows");
            }
            List<String> out = new ArrayList<>();
            if (!widgets.isEmpty()) out.add(widgets.size() + (widgets.size() == 1 ? " widget" : " widgets"));
            if (!rows.isEmpty()) out.add(rows.size() + (rows.size() == 1 ? " row" : " rows"));
            if (theme != null) out.add("a theme");
            if (!actions.isEmpty()) out.add(actions.size() + (actions.size() == 1 ? " shortcut" : " shortcuts"));
            if (out.isEmpty()) return android() ? "Widgets and rows from the app" : "Nothing yet";
            return android.text.TextUtils.join(" · ", out);
        }

        /** The Home row ids this plugin can add, in order. */
        List<String> rowKeys() {
            List<String> out = new ArrayList<>();
            for (Feed f : rows) out.add(ROW_PREFIX + id + "/" + f.id);
            if (!actions.isEmpty()) out.add(ROW_PREFIX + id + "/" + ACTIONS_ROW);
            return out;
        }

        Action action(String actionId) {
            for (Action a : actions) if (a.id != null && a.id.equals(actionId)) return a;
            return null;
        }
    }

    /** A widget or row and where its data comes from (web plugins: an https JSON link). */
    static final class Feed {
        final String pluginId, id, title, dataUrl;
        final int refreshMinutes;

        Feed(String pluginId, String id, String title, String dataUrl, int refreshMinutes) {
            this.pluginId = pluginId; this.id = id; this.title = title; this.dataUrl = dataUrl;
            this.refreshMinutes = refreshMinutes;
        }
    }

    /** What a tile does: open a web page, open an app (or a deep link), or call a webhook. */
    static final class Action {
        String pluginId, id, title, icon;  // id: only for the manifest's own shortcuts
        String type;                       // url | app | webhook
        String url;                        // url, webhook
        String pkg, uri, intent;           // app: a package, a deep link and/or an intent: URI
        String method = "GET", body, contentType;
        final Map<String, String> headers = new LinkedHashMap<>();

        String favId() { return id == null ? null : FAV_PREFIX + pluginId + "/" + id; }

        /** What runs, for the confirmation and "changed since you allowed it" check. */
        String signature() {
            return method + " " + url + "\n" + headers + "\n" + (body == null ? "" : body);
        }
    }

    /** One widget card or row tile, ready to show. */
    static final class Card {
        final String title, subtitle, image, icon;
        final Action action;

        Card(String title, String subtitle, String image, String icon, Action action) {
            this.title = title; this.subtitle = subtitle; this.image = image; this.icon = icon; this.action = action;
        }
    }

    /** Everything Home needs from the enabled plugins, with pictures decoded. */
    static final class Snapshot {
        final List<Plugin> plugins = new ArrayList<>();
        final List<Card> widgets = new ArrayList<>();
        final Map<String, List<Card>> rows = new HashMap<>();
        final Map<String, Action> favourites = new HashMap<>();

        Plugin plugin(String id) {
            for (Plugin p : plugins) if (p.id.equals(id)) return p;
            return null;
        }
    }

    // ---- state --------------------------------------------------------------------------------

    private static final ExecutorService jobs = Executors.newSingleThreadExecutor(r -> new Thread(r, "m3home-plugins"));
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Map<String, JSONObject> data = new ConcurrentHashMap<>();  // feed key -> {"t":ms,"d":{...}}
    private static final Set<String> busy = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final LruCache<String, Bitmap> pictures = new LruCache<String, Bitmap>(20 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private static final Map<String, Long> failedPictures = new ConcurrentHashMap<>();
    private static volatile List<Plugin> cache;
    private static volatile Runnable listener;
    private static volatile boolean warmed;

    /** Told (on the main thread) when plugin data or the installed list changed. */
    static void setListener(Runnable l) { listener = l; }

    private static void changed() {
        main.post(() -> {
            Runnable l = listener;
            if (l != null) l.run();
        });
    }

    /** Forget the installed list (apps were installed or removed). */
    static void invalidate() { cache = null; }

    // ---- installed plugins ---------------------------------------------------------------------

    /** Every plugin: your web plugins, then the Android ones found on this TV. Cheap; no network. */
    static List<Plugin> all(Context c) {
        List<Plugin> list = cache;
        if (list != null) return list;
        list = new ArrayList<>();
        for (JSONObject o : webList(c)) {
            String id = o.optString("id"), url = o.optString("url");
            Plugin p = parse(read(manifestFile(c, id)), url);
            if (p != null && p.id.equals(id)) list.add(p);
        }
        list.addAll(discover(c));
        cache = list;
        return list;
    }

    static Plugin find(Context c, String id) {
        for (Plugin p : all(c)) if (p.id.equals(id)) return p;
        return null;
    }

    static boolean enabled(Context c, Plugin p) {
        return Prefs.of(c).getBoolean(Prefs.PLUGIN_ON + p.id, false);
    }

    static List<Plugin> enabled(Context c) {
        List<Plugin> out = new ArrayList<>();
        for (Plugin p : all(c)) if (enabled(c, p)) out.add(p);
        return out;
    }

    private static List<JSONObject> webList(Context c) {
        List<JSONObject> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Prefs.of(c).getString(Prefs.PLUGINS, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && ID.matcher(o.optString("id")).matches()) out.add(o);
            }
        } catch (JSONException ignored) {
            // A broken list reads as empty.
        }
        return out;
    }

    private static File dir(Context c, String sub) {
        File d = new File(new File(c.getFilesDir(), "plugins"), sub);
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    private static File manifestFile(Context c, String id) { return new File(dir(c, "manifests"), id + ".json"); }

    private static File dataFile(Context c, String key) {
        return new File(dir(c, "data"), key.replace('/', '~').replace(':', '~') + ".json");
    }

    // ---- web plugins: the manifest ---------------------------------------------------------------

    /** Parses a manifest, keeping only what's complete and safe. Null if it isn't a usable plugin. */
    static Plugin parse(String body, String url) {
        if (body == null) return null;
        try {
            JSONObject m = new JSONObject(body);
            if (m.optInt("api", 0) != API) return null;
            String id = m.optString("id");
            String name = clean(m.optString("name"), 40);
            if (!ID.matcher(id).matches() || name.isEmpty()) return null;
            String icon = icon(m.optString("icon", ""), null);
            Plugin p = new Plugin(id, name, clean(m.optString("author"), 40), clean(m.optString("description"), 300),
                    clean(m.optString("version"), 20), icon, url, null, null);
            feeds(m.optJSONArray("widgets"), p, p.widgets, MAX_WIDGETS);
            feeds(m.optJSONArray("rows"), p, p.rows, MAX_ROWS);
            JSONArray acts = m.optJSONArray("actions");
            Set<String> ids = new LinkedHashSet<>();
            for (int i = 0; acts != null && i < acts.length() && p.actions.size() < MAX_ACTIONS; i++) {
                JSONObject o = acts.optJSONObject(i);
                if (o == null || !ID.matcher(o.optString("id")).matches() || !ids.add(o.optString("id"))) continue;
                Action a = action(o, p);
                if (a == null) continue;
                a.id = o.optString("id");
                a.title = clean(o.optString("title"), 40);
                if (a.title.isEmpty()) continue;
                a.icon = icon(o.optString("icon", ""), null);
                p.actions.add(a);
            }
            p.theme = theme(m.optJSONObject("theme"));
            return p;
        } catch (JSONException e) {
            return null;
        }
    }

    private static void feeds(JSONArray a, Plugin p, List<Feed> out, int max) {
        Set<String> ids = new LinkedHashSet<>();
        for (int i = 0; a != null && i < a.length() && out.size() < max; i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id"), title = clean(o.optString("title"), 40), url = o.optString("data_url");
            if (!ID.matcher(id).matches() || id.equals(ACTIONS_ROW) || !ids.add(id) || !httpsOk(url)) continue;
            int every = Math.max(5, Math.min(24 * 60, o.optInt("refresh_minutes", 30)));
            out.add(new Feed(p.id, id, title.isEmpty() ? p.name : title, url, every));
        }
    }

    /** An action object (from the manifest or a row item), or null if it isn't a safe, complete one. */
    private static Action action(JSONObject o, Plugin p) {
        if (o == null) return null;
        Action a = new Action();
        a.pluginId = p.id;
        a.type = o.optString("type");
        switch (a.type) {
            case "url":
                a.url = o.optString("url");
                return httpsOk(a.url) ? a : null;
            case "app":
                a.pkg = o.optString("package", "");
                a.uri = o.optString("uri", "");
                a.intent = o.optString("intent", "");
                if (a.pkg.isEmpty()) a.pkg = null; else if (!PKG.matcher(a.pkg).matches()) return null;
                if (a.uri.isEmpty()) a.uri = null; else if (a.uri.length() > MAX_URL || !deepLinkOk(a.uri)) return null;
                if (a.intent.isEmpty()) a.intent = null; else if (a.intent.length() > 2000 || !a.intent.startsWith("intent:")) return null;
                return a.pkg != null || a.uri != null || a.intent != null ? a : null;
            case "webhook":
                a.url = o.optString("url");
                a.method = o.optString("method", "GET").toUpperCase(Locale.ROOT);
                if (!httpsOk(a.url) || !(a.method.equals("GET") || a.method.equals("POST"))) return null;
                if (o.has("body")) {
                    Object b = o.opt("body");
                    a.body = b instanceof String ? (String) b : String.valueOf(b); // an object is sent as JSON
                    if (a.body.length() > MAX_BODY) return null;
                }
                a.contentType = o.optString("content_type", "application/json");
                if (!a.contentType.matches("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+(; ?charset=[A-Za-z0-9-]+)?")) return null;
                JSONObject h = o.optJSONObject("headers");
                if (h != null) {
                    Iterator<String> it = h.keys();
                    while (it.hasNext()) {
                        String k = it.next(), v = h.optString(k);
                        if (a.headers.size() >= MAX_HEADERS || !HEADER.matcher(k).matches() || v.length() > 500
                                || v.indexOf('\r') >= 0 || v.indexOf('\n') >= 0
                                || k.equalsIgnoreCase("host") || k.equalsIgnoreCase("content-length")) return null;
                        a.headers.put(k, v);
                    }
                }
                return a;
            default:
                return null;
        }
    }

    /** A theme with only the keys MaterialTV knows, each checked; null if nothing usable. */
    private static JSONObject theme(JSONObject t) throws JSONException {
        if (t == null) return null;
        JSONObject out = new JSONObject();
        String accent = t.optString("accent", "");
        if (accent.matches("#[0-9a-fA-F]{6}")) out.put("accent", accent);
        else for (String a : Palette.ACCENTS) if (a.equals(accent) && !a.equals("custom")) out.put("accent", a);
        String mode = t.optString("mode", "");
        if (mode.equals("dark") || mode.equals("light")) out.put("mode", mode);
        String size = t.optString("tile_size", "");
        if (size.equals("compact") || size.equals("normal") || size.equals("large")) out.put("tile_size", size);
        String bg = t.optString("background_url", "");
        if (httpsOk(bg)) out.put("background_url", bg);
        String col = t.optString("background_color", "");
        if (col.matches("#[0-9a-fA-F]{6}")) out.put("background_color", col);
        if (t.opt("dim") instanceof Number) out.put("dim", Math.max(0, Math.min(80, t.optInt("dim"))));
        if (t.opt("blur") instanceof Number) out.put("blur", Math.max(0, Math.min(100, t.optInt("blur"))));
        return out.length() == 0 ? null : out;
    }

    /** A deep link: an app scheme (netflix://, spotify:...) or https. Never file, content or javascript. */
    private static boolean deepLinkOk(String uri) {
        if (uri.matches(".*\\s.*")) return false;
        int colon = uri.indexOf(':');
        if (colon <= 0) return false;
        String scheme = uri.substring(0, colon).toLowerCase(Locale.ROOT);
        if (!scheme.matches("[a-z][a-z0-9+.-]*")) return false;
        return !(scheme.equals("file") || scheme.equals("content") || scheme.equals("javascript")
                || scheme.equals("intent") || scheme.equals("android-app") || scheme.equals("data")
                || scheme.equals("http"));
    }

    /** An icon: an https picture, a short glyph (an emoji or two letters), or for Android plugins their own URIs. */
    private static String icon(String s, Plugin p) {
        if (s == null || s.isEmpty()) return null;
        if (imageOk(s, p)) return s;
        String g = clean(s, 2);
        return g.isEmpty() || s.contains("://") ? null : g;
    }

    private static boolean imageOk(String s, Plugin p) {
        if (s == null) return false;
        if (httpsOk(s)) return true;
        if (p == null || !p.android()) return false;
        return s.startsWith("content://" + p.authority + "/") || s.startsWith("android.resource://" + p.pkg + "/");
    }

    static boolean isPicture(String icon) { return icon != null && icon.contains("://"); }

    // ---- installing, enabling, removing --------------------------------------------------------

    /** Installs (or updates) a web plugin from its manifest link and switches it on. Off the main thread. */
    static String install(Context c, String url) {
        url = url == null ? "" : url.trim();
        if (!httpsOk(url)) return "Plugin links must start with https://";
        String body;
        try {
            body = new String(fetch(url, MAX_JSON, null, null, null), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "Couldn't load that plugin (" + e.getMessage() + ")";
        }
        Plugin p = parse(body, url);
        if (p == null) return "That isn't a MaterialTV plugin (it needs \"api\": 1, an id and a name)";
        synchronized (Plugins.class) {
            List<JSONObject> list = webList(c);
            boolean update = false;
            JSONArray out = new JSONArray();
            for (JSONObject o : list) {
                if (o.optString("id").equals(p.id)) { update = true; continue; }
                out.put(o);
            }
            if (!update && list.size() >= MAX_PLUGINS) return "That's the most plugins you can add (" + MAX_PLUGINS + ")";
            for (Plugin a : discover(c)) if (a.id.equals(p.id)) return "An app on this TV already uses the id " + p.id;
            try {
                out.put(new JSONObject().put("id", p.id).put("url", url));
            } catch (JSONException e) {
                return "Couldn't save the plugin";
            }
            write(manifestFile(c, p.id), body.getBytes(StandardCharsets.UTF_8));
            Prefs.of(c).edit().putString(Prefs.PLUGINS, out.toString()).putBoolean(Prefs.PLUGIN_ON + p.id, true).commit();
            invalidate();
            syncRows(c);
            Prefs.bumpLook(c);
        }
        refresh(c, p, true);
        changed();
        return (p.name) + " is installed · " + p.adds();
    }

    /** Switches a plugin on or off; its rows are added to (or taken off) Home. Off the main thread for Android plugins. */
    static void setEnabled(Context c, String id, boolean on) {
        Plugin p = find(c, id);
        if (p == null) return;
        Prefs.of(c).edit().putBoolean(Prefs.PLUGIN_ON + id, on).commit();
        if (on && p.android()) {
            androidRows(c, p); // learn its rows before adding them to Home
            invalidate();
        }
        syncRows(c);
        Prefs.bumpLook(c);
        if (on) refresh(c, find(c, id), true);
        changed();
    }

    /** Removes a web plugin and everything it left behind (Android plugins: uninstall the app instead). */
    static void remove(Context c, String id) {
        synchronized (Plugins.class) {
            JSONArray out = new JSONArray();
            for (JSONObject o : webList(c)) if (!o.optString("id").equals(id)) out.put(o);
            SharedPreferences p = Prefs.of(c);
            SharedPreferences.Editor e = p.edit().putString(Prefs.PLUGINS, out.toString()).remove(Prefs.PLUGIN_ON + id);
            for (String k : p.getAll().keySet()) if (k.startsWith(CONFIRMED + id + "/")) e.remove(k);
            e.commit();
            manifestFile(c, id).delete();
            File[] files = dir(c, "data").listFiles();
            if (files != null) for (File f : files) if (f.getName().contains("~" + id + "~")) f.delete();
            for (String k : new ArrayList<>(data.keySet())) if (k.contains(":" + id + "/")) data.remove(k);
            Apps apps = new Apps(c);
            List<String> favs = apps.favourites();
            if (favs.removeIf(f -> f.startsWith(FAV_PREFIX + id + "/"))) apps.setFavourites(favs);
            invalidate();
            syncRows(c);
            Prefs.bumpLook(c);
        }
        changed();
    }

    /**
     * Keeps Home's row list in step: an enabled plugin's rows are appended once (so you can still
     * hide one), and the rows of plugins that are off or gone are taken out.
     */
    static synchronized void syncRows(Context c) {
        Set<String> valid = new LinkedHashSet<>();
        for (Plugin p : enabled(c)) valid.addAll(p.rowKeys());
        SharedPreferences prefs = Prefs.of(c);
        Set<String> seen = new LinkedHashSet<>(csv(prefs.getString(Prefs.PLUGIN_ROWS_SEEN, "")));
        List<String> rows = csv(Prefs.homeRows(c));
        List<String> before = new ArrayList<>(rows);
        rows.removeIf(r -> r.startsWith(ROW_PREFIX) && !valid.contains(r));
        for (String k : valid) {
            if (!seen.contains(k) && !rows.contains(k)) rows.add(k);
            seen.add(k);
        }
        seen.retainAll(valid);
        SharedPreferences.Editor e = prefs.edit().putString(Prefs.PLUGIN_ROWS_SEEN, android.text.TextUtils.join(",", seen));
        if (!rows.equals(before)) e.putString(Prefs.HOME_ROWS, android.text.TextUtils.join(",", rows));
        e.commit();
    }

    /** Shows or hides one plugin row on Home (Settings' per-row switch). */
    static void showRow(Context c, String key, boolean show) {
        List<String> rows = csv(Prefs.homeRows(c));
        if (show == rows.contains(key)) return;
        if (show) rows.add(key); else rows.remove(key);
        Prefs.of(c).edit().putString(Prefs.HOME_ROWS, android.text.TextUtils.join(",", rows)).commit();
        Prefs.bumpLook(c);
    }

    private static List<String> csv(String s) {
        List<String> out = new ArrayList<>();
        for (String x : (s == null ? "" : s).split(",")) {
            String t = x.trim();
            if (!t.isEmpty() && !out.contains(t)) out.add(t);
        }
        return out;
    }

    /** The plugin and row a Home row id stands for, or null. */
    static String[] splitKey(String key, String prefix) {
        if (key == null || !key.startsWith(prefix)) return null;
        String rest = key.substring(prefix.length());
        int slash = rest.lastIndexOf('/');
        if (slash <= 0 || slash == rest.length() - 1) return null;
        return new String[] { rest.substring(0, slash), rest.substring(slash + 1) };
    }

    /** A row's title ("Rooms"), or null when the row isn't (any longer) offered by an enabled plugin. */
    static String rowTitle(Context c, String key) {
        String[] k = splitKey(key, ROW_PREFIX);
        if (k == null) return null;
        Plugin p = find(c, k[0]);
        if (p == null || !enabled(c, p)) return null;
        if (k[1].equals(ACTIONS_ROW)) return p.actions.isEmpty() ? null : p.name;
        for (Feed f : p.rows) if (f.id.equals(k[1])) return f.title;
        return null;
    }

    // ---- Android plugins ----------------------------------------------------------------------

    /** Apps on this TV that offer a plugin provider for us. Package manager only; their providers aren't asked anything. */
    static List<Plugin> discover(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, String> authorities = new LinkedHashMap<>();
        Intent q = new Intent(INTENT_ACTION);
        try {
            for (ResolveInfo r : pm.queryIntentContentProviders(q, PackageManager.GET_META_DATA)) {
                ProviderInfo pi = r.providerInfo;
                if (pi == null) continue;
                String a = pi.metaData == null ? null : pi.metaData.getString(META_AUTHORITY);
                if (!authorities.containsKey(pi.packageName) || a != null) authorities.put(pi.packageName, a);
            }
            for (ResolveInfo r : pm.queryIntentServices(q, 0)) {
                if (r.serviceInfo != null && !authorities.containsKey(r.serviceInfo.packageName)) authorities.put(r.serviceInfo.packageName, null);
            }
        } catch (RuntimeException e) {
            return new ArrayList<>();
        }
        List<Plugin> out = new ArrayList<>();
        for (Map.Entry<String, String> en : authorities.entrySet()) {
            String pkg = en.getKey();
            if (pkg.equals(c.getPackageName()) || out.size() >= MAX_PLUGINS) continue;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA);
                Bundle meta = ai.metaData;
                String auth = en.getValue() != null ? en.getValue() : meta == null ? null : meta.getString(META_AUTHORITY);
                if (auth == null || !auth.matches("[A-Za-z0-9._-]{1,100}")) continue;
                // The authority must be this app's own exported provider, not someone else's.
                ProviderInfo prov = pm.resolveContentProvider(auth, 0);
                if (prov == null || !pkg.equals(prov.packageName) || !prov.exported) continue;
                PackageInfo pi = pm.getPackageInfo(pkg, 0);
                CharSequence label = ai.loadLabel(pm);
                String desc = meta == null ? "" : String.valueOf(meta.get(META_DESCRIPTION) == null ? "" : meta.get(META_DESCRIPTION));
                Plugin p = new Plugin(pkg, clean(label == null ? pkg : label.toString(), 40), "Android app",
                        clean(desc, 300), clean(pi.versionName, 20), null, null, pkg, auth);
                p.widgets.add(new Feed(pkg, "widgets", p.name, null, ANDROID_REFRESH_MIN));
                for (String[] row : cachedAndroidRows(c, pkg)) p.rows.add(new Feed(pkg, row[0], row[1], null, ANDROID_REFRESH_MIN));
                out.add(p);
            } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
                // Gone meanwhile, or not readable: skip it.
            }
        }
        return out;
    }

    private static List<String[]> cachedAndroidRows(Context c, String pkg) {
        List<String[]> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Prefs.of(c).getString(ROWS_CACHE + pkg, "[]"));
            for (int i = 0; i < a.length() && out.size() < MAX_ROWS; i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new String[] { o.getString("id"), o.getString("title") });
            }
        } catch (JSONException ignored) {
            // None known yet.
        }
        return out;
    }

    /** Asks an enabled Android plugin which rows it has (content://authority/rows) and remembers them. */
    private static void androidRows(Context c, Plugin p) {
        List<String[]> rows = new ArrayList<>();
        Cursor cur = null;
        ContentProviderClient cl = null;
        try {
            Uri u = Uri.parse("content://" + p.authority + "/rows");
            cl = c.getContentResolver().acquireUnstableContentProviderClient(u); // a crashing plugin can't take us down
            if (cl == null) return;
            cur = cl.query(u, new String[] { "id", "title" }, null, null, null);
            Set<String> ids = new LinkedHashSet<>();
            while (cur != null && cur.moveToNext() && rows.size() < MAX_ROWS) {
                String id = str(cur, "id"), title = clean(str(cur, "title"), 40);
                if (id == null || !ID.matcher(id).matches() || id.equals(ACTIONS_ROW) || !ids.add(id)) continue;
                rows.add(new String[] { id, title.isEmpty() ? p.name : title });
            }
            JSONArray a = new JSONArray();
            for (String[] r : rows) a.put(new JSONObject().put("id", r[0]).put("title", r[1]));
            Prefs.of(c).edit().putString(ROWS_CACHE + p.pkg, a.toString()).commit();
        } catch (Exception ignored) {
            // The plugin said no (or isn't answering): keep what we knew.
        } finally {
            if (cur != null) try { cur.close(); } catch (RuntimeException ignored) { }
            if (cl != null) cl.close();
        }
    }

    /** content://authority/path as cards (columns title, subtitle, image_uri, intent_uri), in our JSON form. */
    private static JSONObject androidCards(Context c, Plugin p, String path) throws JSONException {
        Cursor cur = null;
        ContentProviderClient cl = null;
        try {
            Uri u = Uri.parse("content://" + p.authority + "/" + path);
            cl = c.getContentResolver().acquireUnstableContentProviderClient(u);
            if (cl == null) return null;
            cur = cl.query(u, new String[] { "title", "subtitle", "image_uri", "intent_uri" }, null, null, null);
            if (cur == null) return null;
            JSONArray items = new JSONArray();
            while (cur.moveToNext() && items.length() < MAX_ITEMS) {
                JSONObject o = new JSONObject().put("title", nz(str(cur, "title"))).put("subtitle", nz(str(cur, "subtitle")))
                        .put("image", nz(str(cur, "image_uri")));
                String intent = str(cur, "intent_uri");
                if (intent != null && !intent.isEmpty()) o.put("action", new JSONObject().put("type", "app").put("intent", intent));
                items.put(o);
            }
            return new JSONObject().put("items", items);
        } catch (RuntimeException | android.os.RemoteException e) {
            return null;
        } finally {
            if (cur != null) try { cur.close(); } catch (RuntimeException ignored) { }
            if (cl != null) cl.close();
        }
    }

    private static String str(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        if (i < 0) return null;
        try { return c.getString(i); } catch (RuntimeException e) { return null; }
    }

    private static String nz(String s) { return s == null ? "" : s; }

    // ---- data: fetching, caching, reading ----------------------------------------------------------

    private static String key(String kind, Feed f) { return kind + ":" + f.pluginId + "/" + f.id; }

    /**
     * Fetches whatever is due, in the background: the enabled plugins' widgets and the given rows
     * (only the ones on screen). Tells the listener when anything new arrived.
     */
    static void refreshDue(Context c, final Set<String> rowKeys) {
        final Context app = c.getApplicationContext();
        if (busy.contains("due")) return;
        busy.add("due");
        jobs.execute(() -> {
            try {
                warm(app);
                boolean any = false;
                for (Plugin p : enabled(app)) {
                    for (Feed f : p.widgets) any |= refreshFeed(app, p, "w", f, false);
                    for (Feed f : p.rows) {
                        if (rowKeys != null && rowKeys.contains(ROW_PREFIX + p.id + "/" + f.id)) any |= refreshFeed(app, p, "r", f, false);
                    }
                }
                if (any) changed();
            } finally {
                busy.remove("due");
            }
        });
    }

    /** Re-reads every manifest and refetches all data now. Returns a status line. Off the main thread. */
    static String refreshAll(Context c) {
        invalidate();
        int ok = 0, failed = 0;
        for (JSONObject o : webList(c)) {
            String id = o.optString("id"), url = o.optString("url");
            try {
                String body = new String(fetch(url, MAX_JSON, null, null, null), StandardCharsets.UTF_8);
                Plugin p = parse(body, url);
                if (p != null && p.id.equals(id)) { write(manifestFile(c, id), body.getBytes(StandardCharsets.UTF_8)); ok++; }
                else failed++;
            } catch (IOException e) {
                failed++;
            }
        }
        invalidate();
        for (Plugin p : enabled(c)) if (p.android()) androidRows(c, p);
        invalidate();
        syncRows(c);
        Prefs.bumpLook(c);
        for (Plugin p : enabled(c)) refresh(c, p, true);
        changed();
        int android = 0;
        for (Plugin p : all(c)) if (p.android()) android++;
        return "Plugins refreshed · " + ok + " web" + (failed > 0 ? " (" + failed + " didn't answer)" : "")
                + " · " + android + " Android";
    }

    /** Everything a plugin shows, now. Off the main thread. */
    private static void refresh(Context c, Plugin p, boolean force) {
        if (p == null) return;
        for (Feed f : p.widgets) refreshFeed(c, p, "w", f, force);
        for (Feed f : p.rows) refreshFeed(c, p, "r", f, force);
    }

    /** Fetches one widget or row if it's due (or forced); true when new data arrived. */
    private static boolean refreshFeed(Context c, Plugin p, String kind, Feed f, boolean force) {
        String k = key(kind, f);
        JSONObject have = cached(c, k);
        long age = System.currentTimeMillis() - (have == null ? 0 : have.optLong("t", 0));
        if (!force && age < f.refreshMinutes * MINUTE && age >= 0) return false;
        if (!busy.add(k)) return false;
        try {
            JSONObject d;
            if (p.android()) {
                d = androidCards(c, p, kind.equals("w") ? "widgets" : "rows/" + f.id);
            } else {
                byte[] b = fetch(f.dataUrl, MAX_JSON, "application/json", null, null);
                d = new JSONObject(new String(b, StandardCharsets.UTF_8));
            }
            if (d == null) throw new IOException("no answer"); // try again in 5 minutes, not every minute
            JSONObject entry = new JSONObject().put("t", System.currentTimeMillis()).put("d", d);
            data.put(k, entry);
            write(dataFile(c, k), entry.toString().getBytes(StandardCharsets.UTF_8));
            // Fetch the pictures now, so Home only decodes from the disk.
            for (Card card : cards(p, kind, d)) {
                if (isPicture(card.image)) picture(c, card.image, 0);
                if (isPicture(card.icon)) picture(c, card.icon, 0);
            }
            return true;
        } catch (IOException | JSONException | RuntimeException e) {
            // Keep the last good copy; try again next time.
            if (have != null) {
                try { have.put("t", System.currentTimeMillis() - (f.refreshMinutes - 5) * MINUTE); } catch (JSONException ignored) { }
            } else {
                try { data.put(k, new JSONObject().put("t", System.currentTimeMillis() - (f.refreshMinutes - 5) * MINUTE)); } catch (JSONException ignored) { }
            }
            return false;
        } finally {
            busy.remove(k);
        }
    }

    /** Loads the saved data of every enabled feed into memory once, off the main thread. */
    private static void warm(Context c) {
        if (warmed) return;
        warmed = true;
        for (Plugin p : enabled(c)) {
            for (Feed f : p.widgets) cached(c, key("w", f));
            for (Feed f : p.rows) cached(c, key("r", f));
        }
    }

    private static JSONObject cached(Context c, String k) {
        JSONObject o = data.get(k);
        if (o != null) return o;
        String s = read(dataFile(c, k));
        if (s == null) return null;
        try {
            o = new JSONObject(s);
            data.put(k, o);
            return o;
        } catch (JSONException e) {
            return null;
        }
    }

    /** The cards in a widget's or row's data: {"icon","text","subtext"} or {"items":[...]}. */
    private static List<Card> cards(Plugin p, String kind, JSONObject d) {
        List<Card> out = new ArrayList<>();
        if (d == null) return out;
        JSONArray items = d.optJSONArray("items");
        int max = kind.equals("w") ? 3 : MAX_ITEMS;
        if (items == null) {
            if (!kind.equals("w")) return out;
            items = new JSONArray().put(d);
        }
        for (int i = 0; i < items.length() && out.size() < max; i++) {
            JSONObject o = items.optJSONObject(i);
            if (o == null) continue;
            String title = clean(o.optString("title", o.optString("text")), 60);
            if (title.isEmpty()) continue;
            String sub = clean(o.optString("subtitle", o.optString("subtext")), 80);
            String image = o.optString("image", "");
            String ic = icon(o.optString("icon", ""), p);
            out.add(new Card(title, sub.isEmpty() ? null : sub, imageOk(image, p) ? image : null, ic,
                    kind.equals("w") ? null : action(o.optJSONObject("action"), p)));
        }
        return out;
    }

    /** A widget's (or row's) cards from memory, or empty. Safe on the main thread (memory only). */
    static List<Card> memoryCards(Context c, Plugin p, String kind, Feed f) {
        JSONObject o = data.get(key(kind, f));
        return o == null ? new ArrayList<>() : cards(p, kind, o.optJSONObject("d"));
    }

    /** Widget cards of the enabled plugins, from memory (the screensaver; at most max). */
    static List<Card> widgetCards(Context c, int max) {
        List<Card> out = new ArrayList<>();
        for (Plugin p : enabled(c)) {
            for (Feed f : p.widgets) {
                for (Card card : memoryCards(c, p, "w", f)) if (out.size() < max) out.add(card);
            }
        }
        return out;
    }

    /** Everything Home shows from plugins, pictures decoded. Off the main thread. */
    static Snapshot snapshot(Context c, List<String> rowKeys) {
        Snapshot s = new Snapshot();
        warm(c);
        int widgetPx = M3.dp(c, 48), tilePx = M3.dp(c, 240);
        for (Plugin p : enabled(c)) {
            s.plugins.add(p);
            for (Feed f : p.widgets) {
                for (Card card : cards(p, "w", dataOf(c, key("w", f)))) {
                    if (s.widgets.size() >= 3) break;
                    s.widgets.add(card);
                    if (isPicture(card.icon)) picture(c, card.icon, widgetPx);
                }
            }
            for (Action a : p.actions) {
                s.favourites.put(a.favId(), a);
                if (isPicture(a.icon)) picture(c, a.icon, tilePx);
            }
        }
        for (String rk : rowKeys) {
            String[] k = splitKey(rk, ROW_PREFIX);
            Plugin p = k == null ? null : s.plugin(k[0]);
            if (p == null) continue;
            List<Card> list = new ArrayList<>();
            if (k[1].equals(ACTIONS_ROW)) {
                for (Action a : p.actions) list.add(new Card(a.title, null, null, a.icon, a));
            } else {
                for (Feed f : p.rows) {
                    if (!f.id.equals(k[1])) continue;
                    list = cards(p, "r", dataOf(c, key("r", f)));
                    for (Card card : list) {
                        if (card.image != null) picture(c, card.image, tilePx);
                        else if (isPicture(card.icon)) picture(c, card.icon, tilePx);
                    }
                }
            }
            s.rows.put(rk, list);
        }
        return s;
    }

    private static JSONObject dataOf(Context c, String k) {
        JSONObject o = cached(c, k);
        return o == null ? null : o.optJSONObject("d");
    }

    // ---- pictures ---------------------------------------------------------------------------------

    /** A decoded picture from memory only (main thread), or null. */
    static Bitmap memoryPicture(String src) {
        if (src == null) return null;
        synchronized (pictures) { return pictures.get(src); }
    }

    /**
     * Decodes a picture (https, cached on disk; or an Android plugin's own content/resource URI),
     * at most about maxPx on its long side; 0 only fills the disk cache. Off the main thread.
     */
    static Bitmap picture(Context c, String src, int maxPx) {
        if (src == null) return null;
        Bitmap have = memoryPicture(src);
        if (have != null) return have;
        Long failedAt = failedPictures.get(src);
        if (failedAt != null && System.currentTimeMillis() - failedAt < 10 * MINUTE) return null;
        failedPictures.put(src, System.currentTimeMillis()); // cleared below when it works
        try {
            byte[] b;
            if (src.startsWith("https://")) {
                File f = new File(dir(c, "pictures"), sha(src) + ".img");
                if (f.isFile()) b = readBytes(f, MAX_IMAGE);
                else {
                    b = fetch(src, MAX_IMAGE, "image/*", null, null);
                    if (!looksLikePicture(b)) return null;
                    write(f, b);
                }
            } else {
                b = localPicture(c, src);
            }
            if (b != null) failedPictures.remove(src);
            if (b == null || maxPx <= 0) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(b, 0, b.length, o);
            if (o.outWidth <= 0 || o.outHeight <= 0 || o.outWidth > 4096 || o.outHeight > 4096) return null;
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxPx) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            if ("image/jpeg".equals(o.outMimeType)) o2.inPreferredConfig = Bitmap.Config.RGB_565; // no alpha: half the memory
            Bitmap bm = BitmapFactory.decodeByteArray(b, 0, b.length, o2);
            int longSide = bm == null ? 0 : Math.max(bm.getWidth(), bm.getHeight());
            if (longSide > maxPx * 5 / 4) { // the last step down, so a row of pictures stays small
                float k = maxPx / (float) longSide;
                Bitmap scaled = Bitmap.createScaledBitmap(bm, Math.max(1, Math.round(bm.getWidth() * k)),
                        Math.max(1, Math.round(bm.getHeight() * k)), true);
                if (scaled != bm) bm.recycle();
                bm = scaled;
            }
            if (bm != null) synchronized (pictures) { pictures.put(src, bm); }
            if (bm != null) failedPictures.remove(src);
            return bm;
        } catch (IOException | OutOfMemoryError | RuntimeException e) {
            return null;
        }
    }

    private static boolean looksLikePicture(byte[] b) {
        if (b == null) return false;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(b, 0, b.length, o);
        return o.outWidth > 0 && o.outHeight > 0 && o.outWidth <= 4096 && o.outHeight <= 4096;
    }

    /** An Android plugin's own picture (content:// from its provider, or android.resource://). */
    private static byte[] localPicture(Context c, String src) throws IOException {
        Uri u = Uri.parse(src);
        String scheme = u.getScheme();
        if (ContentResolver.SCHEME_CONTENT.equals(scheme)) {
            ContentProviderClient cl = c.getContentResolver().acquireUnstableContentProviderClient(u);
            if (cl == null) return null;
            try (android.os.ParcelFileDescriptor fd = cl.openFile(u, "r");
                 InputStream in = fd == null ? null : new FileInputStream(fd.getFileDescriptor())) {
                return in == null ? null : readLimited(in, MAX_IMAGE);
            } catch (android.os.RemoteException | RuntimeException e) {
                return null;
            } finally {
                cl.close();
            }
        }
        if (ContentResolver.SCHEME_ANDROID_RESOURCE.equals(scheme)) {
            try (InputStream in = c.getContentResolver().openInputStream(u)) {
                return in == null ? null : readLimited(in, MAX_IMAGE);
            } catch (RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    /** A steady tile colour per name (a dark tone, so white names read well). */
    static int tint(String seed) {
        float hue = ((seed == null ? 0 : seed.hashCode()) & 0x7FFFFFFF) % 360;
        return Color.HSVToColor(new float[] { hue, 0.42f, 0.30f });
    }

    // ---- running actions ------------------------------------------------------------------------

    /** Does what a tile says. Webhooks ask first, once per action (and again if the action changes). */
    static void run(final Activity a, final Action act) {
        if (act == null) return;
        switch (act.type) {
            case "url":
                if (!launch(a, new Intent(Intent.ACTION_VIEW, Uri.parse(act.url)))) {
                    Toast.makeText(a, "No web browser on this TV opens that link", Toast.LENGTH_SHORT).show();
                }
                return;
            case "app":
                if (!launchApp(a, act)) Toast.makeText(a, "Couldn't open that app", Toast.LENGTH_SHORT).show();
                return;
            case "webhook":
                final String okKey = CONFIRMED + act.pluginId + "/" + sha(act.signature());
                if (Prefs.of(a).getBoolean(okKey, false)) { webhook(a, act); return; }
                Plugin p = find(a, act.pluginId);
                String host;
                try { host = new URI(act.url).getHost(); } catch (Exception e) { host = act.url; }
                List<CharSequence> opts = new ArrayList<>();
                opts.add("Run it");
                opts.add("Cancel");
                M3Menu.show(a, "Run \"" + (act.title == null ? "this shortcut" : act.title) + "\"?",
                        (p == null ? "A plugin" : p.name) + " wants to send a " + act.method + " request to " + host
                                + ". MaterialTV asks once for each shortcut.", opts, which -> {
                    if (which != 0) return;
                    Prefs.of(a).edit().putBoolean(okKey, true).apply();
                    webhook(a, act);
                });
                return;
            default:
        }
    }

    private static void webhook(final Activity a, final Action act) {
        final Context app = a.getApplicationContext();
        jobs.execute(() -> {
            String msg;
            try {
                fetch(act.url, 16 * 1024, null, act.method, act);
                msg = (act.title == null ? "Done" : act.title + ": done");
            } catch (IOException e) {
                msg = (act.title == null ? "Didn't work" : act.title + " didn't work") + " (" + e.getMessage() + ")";
            }
            final String m = msg;
            main.post(() -> Toast.makeText(app, m, Toast.LENGTH_SHORT).show());
        });
    }

    private static boolean launchApp(Activity a, Action act) {
        if (act.intent != null) {
            Intent i = safeIntent(a, act.intent);
            if (i != null && launch(a, i)) return true;
        }
        if (act.uri != null) {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(act.uri));
            if (act.pkg != null) i.setPackage(act.pkg);
            if (launch(a, i)) return true;
        }
        if (act.pkg != null && !act.pkg.equals(a.getPackageName())) {
            PackageManager pm = a.getPackageManager();
            Intent i = pm.getLeanbackLaunchIntentForPackage(act.pkg);
            if (i == null) i = pm.getLaunchIntentForPackage(act.pkg);
            return i != null && launch(a, i);
        }
        return false;
    }

    /**
     * An "intent:" URI from a plugin, made safe to start as an activity: no selector, clip data or
     * permission-granting flags, no file/content data, and never one of MaterialTV's own screens.
     * Null if it can't be used.
     */
    static Intent safeIntent(Context c, String uri) {
        if (uri == null || !uri.startsWith("intent:") || uri.length() > 2000) return null;
        Intent i;
        try {
            i = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME);
        } catch (Exception e) {
            return null;
        }
        i.setSelector(null);
        i.setClipData(null);
        i.setFlags(0);
        Uri d = i.getData();
        if (d != null && d.getScheme() != null) {
            String s = d.getScheme().toLowerCase(Locale.ROOT);
            if (s.equals("file") || s.equals("content") || s.equals("javascript")) return null;
        }
        if (i.getComponent() != null && i.getComponent().getPackageName().equals(c.getPackageName())) return null;
        if (c.getPackageName().equals(i.getPackage())) return null;
        return i;
    }

    /** startActivity only (never a broadcast or a service), and never into MaterialTV itself. */
    private static boolean launch(Context c, Intent i) {
        i.setSelector(null);
        i.setClipData(null);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PackageManager pm = c.getPackageManager();
        List<ResolveInfo> targets = pm.queryIntentActivities(i, 0);
        if (targets.isEmpty()) return false;
        for (ResolveInfo r : targets) {
            if (r.activityInfo == null || r.activityInfo.packageName.equals(c.getPackageName())) return false;
            if (i.getComponent() != null && !r.activityInfo.exported) return false;
        }
        try {
            c.startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    // ---- themes --------------------------------------------------------------------------------------

    /** Applies a theme plugin's look: accent (a named one or any colour), light/dark, background, tile size. Off the main thread. */
    static String applyTheme(Context c, Plugin p) {
        if (p == null || p.theme == null) return "That plugin has no theme";
        JSONObject t = p.theme;
        SharedPreferences.Editor e = Prefs.of(c).edit();
        String accent = t.optString("accent", "");
        if (accent.startsWith("#")) {
            e.putString(Prefs.ACCENT, "custom").putInt(Prefs.ACCENT_SEED, 0xFF000000 | Integer.parseInt(accent.substring(1), 16));
        } else if (!accent.isEmpty()) {
            e.putString(Prefs.ACCENT, accent);
        }
        if (t.has("mode")) e.putString(Prefs.THEME_MODE, t.optString("mode"));
        if (t.has("tile_size")) e.putString(Prefs.TILE_SIZE, t.optString("tile_size"));
        if (t.has("dim")) e.putInt(Prefs.BG_DIM, t.optInt("dim"));
        if (t.has("blur")) e.putInt(Prefs.BG_BLUR, t.optInt("blur"));
        String colour = t.optString("background_color", "");
        if (!colour.isEmpty()) {
            e.putString(Prefs.BG_KIND, "solid").putInt(Prefs.BG_COLOR, 0xFF000000 | Integer.parseInt(colour.substring(1), 16));
        }
        e.commit();
        String url = t.optString("background_url", "");
        if (!url.isEmpty()) {
            try {
                byte[] b = fetch(url, MAX_BACKGROUND, "image/*", null, null);
                String err = Backdrop.saveUpload(c, new ByteArrayInputStream(b), b.length); // sets "custom" and refreshes
                if (err != null) { Backdrop.changed(c); return p.name + " applied, but not its picture: " + err; }
                return p.name + " theme applied";
            } catch (IOException x) {
                Backdrop.changed(c);
                return p.name + " applied, but its picture didn't download";
            }
        }
        Backdrop.changed(c); // also bumps the look
        return p.name + " theme applied";
    }

    // ---- the phone page --------------------------------------------------------------------------

    /** The installed plugins as the phone page shows them. */
    static JSONArray json(Context c) throws JSONException {
        JSONArray out = new JSONArray();
        for (Plugin p : all(c)) {
            JSONObject o = new JSONObject().put("id", p.id).put("name", p.name).put("author", p.author)
                    .put("description", p.description).put("version", p.version).put("adds", p.adds())
                    .put("kind", p.android() ? "android" : "web").put("enabled", enabled(c, p))
                    .put("theme", p.theme != null);
            if (p.url != null) o.put("url", p.url);
            JSONArray acts = new JSONArray();
            for (Action a : p.actions) acts.put(new JSONObject().put("id", a.favId()).put("title", a.title));
            o.put("actions", acts);
            out.put(o);
        }
        return out;
    }

    /** {favourite id: name} for plugin shortcuts that can be favourites (the phone page's favourites list). */
    static JSONObject favLabels(Context c) throws JSONException {
        JSONObject o = new JSONObject();
        for (Plugin p : enabled(c)) for (Action a : p.actions) o.put(a.favId(), a.title + " (" + p.name + ")");
        return o;
    }

    /** True for a shortcut an enabled plugin still offers. */
    static boolean isFavourite(Context c, String favId) {
        String[] k = splitKey(favId, FAV_PREFIX);
        if (k == null) return false;
        Plugin p = find(c, k[0]);
        return p != null && enabled(c, p) && p.action(k[1]) != null;
    }

    // ---- text and network ---------------------------------------------------------------------------

    /** Plain text only: no control or direction-override characters, single line, at most max characters. */
    static String clean(String s, int max) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (int i = 0; i < s.length() && n < max; ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\n' || cp == '\r' || cp == '\t') cp = ' ';
            if (Character.isISOControl(cp) || (cp >= 0x202A && cp <= 0x202E) || (cp >= 0x2066 && cp <= 0x2069)
                    || cp == 0x200E || cp == 0x200F || cp == 0x061C || (cp >= 0xFFF9 && cp <= 0xFFFB)) continue;
            if (cp == ' ' && (b.length() == 0 || b.charAt(b.length() - 1) == ' ')) continue;
            b.appendCodePoint(cp);
            n++;
        }
        return b.toString().trim();
    }

    static boolean httpsOk(String url) {
        if (url == null || url.length() > MAX_URL || !url.startsWith("https://") || url.matches(".*\\s.*")) return false;
        try {
            URI u = new URI(url);
            return u.getHost() != null && !u.getHost().isEmpty() && u.getRawUserInfo() == null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * GET (or a webhook's GET/POST) over https, redirects followed by hand so they stay https
     * (webhooks: not followed), at most max bytes back. Throws with a short reason.
     */
    private static byte[] fetch(String url, int max, String accept, String method, Action hook) throws IOException {
        for (int hops = 0; hops < 5; hops++) {
            if (!httpsOk(url)) throw new IOException("not https");
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            try {
                con.setInstanceFollowRedirects(false);
                con.setConnectTimeout(8000);
                con.setReadTimeout(12_000);
                con.setUseCaches(false);
                con.setRequestProperty("User-Agent", "MaterialTV-Plugins/" + API);
                if (accept != null) con.setRequestProperty("Accept", accept);
                if (hook != null) {
                    con.setRequestMethod(hook.method);
                    for (Map.Entry<String, String> h : hook.headers.entrySet()) con.setRequestProperty(h.getKey(), h.getValue());
                    if (hook.method.equals("POST")) {
                        byte[] body = (hook.body == null ? "" : hook.body).getBytes(StandardCharsets.UTF_8);
                        con.setDoOutput(true);
                        con.setRequestProperty("Content-Type", hook.contentType);
                        con.setFixedLengthStreamingMode(body.length);
                        try (OutputStream out = con.getOutputStream()) { out.write(body); }
                    }
                }
                int code = con.getResponseCode();
                if (hook != null) {
                    if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
                } else if (code >= 300 && code < 400) {
                    String to = con.getHeaderField("Location");
                    if (to == null) throw new IOException("bad redirect");
                    url = new URL(new URL(url), to).toString();
                    continue;
                } else if (code != 200) {
                    throw new IOException("HTTP " + code);
                }
                long len = con.getContentLengthLong();
                if (len > max) throw new IOException("too big");
                try (InputStream in = con.getInputStream()) {
                    byte[] b = readLimited(in, max);
                    if (b == null) throw new IOException("too big");
                    return b;
                }
            } catch (java.net.SocketTimeoutException e) {
                throw new IOException("timed out");
            } catch (javax.net.ssl.SSLException e) {
                throw new IOException("secure connection failed");
            } catch (java.net.UnknownHostException e) {
                throw new IOException("no network or unknown site");
            } finally {
                con.disconnect();
            }
        }
        throw new IOException("too many redirects");
    }

    private static byte[] readLimited(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > max) return null;
        }
        return out.toByteArray();
    }

    private static String sha(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 12; i++) b.append(String.format(Locale.ROOT, "%02x", d[i] & 0xFF));
            return b.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private static String read(File f) {
        try {
            byte[] b = readBytes(f, MAX_JSON * 2);
            return b == null ? null : new String(b, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] readBytes(File f, int max) throws IOException {
        if (!f.isFile()) return null;
        try (InputStream in = new FileInputStream(f)) {
            return readLimited(in, max);
        }
    }

    private static void write(File f, byte[] b) {
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(b);
        } catch (IOException e) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(f)) {
            f.delete();
            if (!tmp.renameTo(f)) tmp.delete();
        }
    }
}
