package app.m3home;

import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

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
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The little app store behind Home's "Get apps" row. The catalog is docs/store.json, served by
 * GitHub Pages; the last copy is kept, and assets/store.json is the fallback. Your own apps (a
 * GitHub repo or an https APK link, added on the phone page) are kept in Prefs.STORE_CUSTOM.
 *
 * Three kinds of source: a GitHub repo's latest release, a fixed https link, or the Amazon
 * Appstore page (for the big proprietary apps). Downloads are checked before installing: the file
 * must be an app, and its package name must be the one the catalog promises (your own apps learn
 * theirs from the first download). Android then asks you to confirm, as with MaterialTV's updates.
 */
final class Store {
    private Store() {}

    static final String CATALOG_URL = "https://brick-bread.github.io/MaterialTV/store.json";
    /**
     * The latest version, download link and size of every catalog app, worked out once a day by a
     * GitHub Action (.github/workflows/store-resolve.yml) and served by GitHub Pages. TVs read this
     * instead of asking api.github.com, which allows only 60 requests an hour per home connection.
     */
    static final String RESOLVED_URL = "https://brick-bread.github.io/MaterialTV/store-resolved.json";
    private static final String CACHE = "store.json", RELEASES = "store-releases.json", ICONS = "store-icons";
    private static final String RESOLVED = "store-resolved.json", GITHUB = "store-github.json", GENERATED = "~generated";
    private static final long GITHUB_TTL_MS = 12 * 3600_000L;
    private static final String LAST_REFRESH = "store_last_refresh", LEARNED = "store_pkg:";
    private static final long DAY_MS = 24 * 3600_000L;
    private static final long MAX_APK = 300L * 1024 * 1024, MAX_TEXT = 1_000_000, MAX_ICON = 512 * 1024;
    /** Screenshots on an app's page: at most 4 per app, each download at most 4 MB, the cache at most 24 MB. */
    static final int MAX_SHOTS = 4;
    private static final long MAX_SHOT = 4L * 1024 * 1024, MAX_SHOT_CACHE = 24L * 1024 * 1024;
    private static final int MAX_NOTES = 1500;
    static final int MAX_CUSTOM = 30, MAX_NAME = 40, MAX_URL = 500;
    static final Pattern REPO = Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9._-]{1,100}");
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,39}");
    private static final Pattern PKG = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");

    /** What the row offers for an app. */
    static final int GET = 0, UPDATE = 1, INSTALLED = 2;

    /** One app in the catalog, or one of yours. */
    static final class Entry {
        final String id, name, description, category, iconUrl, type, repo, assetRegex, url, version, pkg;
        final boolean custom;
        /** For the app's page (StoreDetailActivity): optional catalog extras, see extras(). */
        String longDescription = "", license, website;
        final List<String> screenshots = new ArrayList<>();

        Entry(String id, String name, String pkg, String description, String category, String iconUrl,
              String type, String repo, String assetRegex, String url, String version, boolean custom) {
            this.id = id; this.name = name; this.pkg = pkg; this.description = description;
            this.category = category; this.iconUrl = iconUrl; this.type = type; this.repo = repo;
            this.assetRegex = assetRegex; this.url = url; this.version = version; this.custom = custom;
        }

        boolean appstore() { return "appstore".equals(type); }

        /** A MaterialTV web app: url is its manifest (see WebApps). */
        boolean webapp() { return "webapp".equals(type); }
        /** A MaterialTV plugin ("type": "plugin"); url is its https manifest. See parsePluginEntry. */
        boolean plugin() { return "plugin".equals(type); }

        /** "github.com/owner/name", "videolan.org", "Amazon Appstore". */
        String source() {
            if (appstore()) return "Amazon Appstore";
            if (webapp()) { try { return "web app at " + new URI(url).getHost(); } catch (Exception e) { return "the web"; } }
            if (repo != null) return "github.com/" + repo;
            try { return new URI(url).getHost(); } catch (Exception e) { return "the web"; }
        }
    }

    /** An app as the row shows it right now. */
    static final class Item {
        final Entry entry;
        final int state;
        final String pkg, latest;
        final long size;
        final Bitmap icon;
        final int tint;

        Item(Entry entry, int state, String pkg, String latest, long size, Bitmap icon, int tint) {
            this.entry = entry; this.state = state; this.pkg = pkg; this.latest = latest;
            this.size = size; this.icon = icon; this.tint = tint;
        }
    }

    /** Told on the main thread when an app's progress changes (id), or the whole list (null). */
    interface Listener { void changed(String id); }

    private static final ExecutorService jobs = Executors.newSingleThreadExecutor(r -> new Thread(r, "m3home-store"));
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Map<String, String> status = new ConcurrentHashMap<>();   // id -> "42%", "Confirm on screen"
    private static final Map<String, String> problems = new ConcurrentHashMap<>(); // id -> what went wrong last time
    private static volatile List<Entry> catalog;
    private static volatile JSONObject resolved; // id -> {version, url, size, asset}
    private static volatile Listener listener;
    private static volatile boolean refreshing;

    private static final List<Listener> watchers = new CopyOnWriteArrayList<>(); // open app pages

    static void setListener(Listener l) { listener = l; }

    /** Another listener (an app's page), told as well as Home's; remove it when done. */
    static void addListener(Listener l) { if (l != null && !watchers.contains(l)) watchers.add(l); }

    static void removeListener(Listener l) { watchers.remove(l); }

    /** Tells Home (and open pages) that the list changed: something was installed, removed or added. */
    static void listChanged() { changed(null); }

    /** Progress of an install in hand, or null. */
    static String status(String id) { return status.get(id); }

    /** Why the last install of this app failed, or null. */
    static String problem(String id) { return problems.get(id); }

    private static void changed(String id) {
        main.post(() -> {
            Listener l = listener;
            if (l != null) l.changed(id);
            for (Listener w : watchers) w.changed(id);
        });
    }

    // ---- the lists --------------------------------------------------------------------------

    /** The catalog: the last downloaded copy, else the one shipped in the APK. */
    static List<Entry> catalog(Context c) {
        List<Entry> list = catalog;
        if (list != null) return list;
        list = parse(read(new File(c.getFilesDir(), CACHE)));
        if (list == null || list.isEmpty()) {
            try (InputStream in = c.getAssets().open("store.json")) {
                list = parse(readAll(in));
            } catch (IOException e) {
                list = null;
            }
        }
        if (list == null) list = new ArrayList<>();
        catalog = list;
        return list;
    }

    /** The catalog, then your own apps. */
    static List<Entry> all(Context c) {
        List<Entry> out = new ArrayList<>(catalog(c));
        out.addAll(custom(c));
        return out;
    }

    static Entry find(Context c, String id) {
        for (Entry e : all(c)) if (e.id.equals(id)) return e;
        return null;
    }

    /** Parses store.json, keeping only entries that are complete and safe; null if it isn't one. */
    static List<Entry> parse(String body) {
        if (body == null) return null;
        try {
            JSONArray a = new JSONObject(body).getJSONArray("apps");
            List<Entry> out = new ArrayList<>();
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < a.length() && out.size() < 100; i++) {
                JSONObject o = a.optJSONObject(i);
                Entry plugin = parsePluginEntry(o, ids); // plugin entries: see the plugins section below
                if (plugin != null) { extras(plugin, o); ids.add(plugin.id); out.add(plugin); continue; }
                JSONObject src = o == null ? null : o.optJSONObject("source");
                if (src == null) continue;
                String id = o.optString("id"), name = o.optString("name").trim();
                String pkg = o.optString("package", src.optString("package"));
                String type = src.optString("type");
                if (!ID.matcher(id).matches() || ids.contains(id) || name.isEmpty() || name.length() > MAX_NAME) continue;
                if (type.equals("webapp")) { // web apps have no package: see the web apps section
                    Entry w = webappEntry(id, name, o, src.optString("manifest"), false);
                    if (w != null) { extras(w, o); ids.add(id); out.add(w); }
                    continue;
                }
                if (!PKG.matcher(pkg).matches()) continue;
                String icon = o.optString("icon_url", "");
                if (!icon.isEmpty() && !httpsOk(icon)) icon = "";
                String repo = null, regex = null, url = null, version = null;
                if (type.equals("github")) {
                    repo = src.optString("repo");
                    regex = src.optString("asset_regex", "");
                    if (!REPO.matcher(repo).matches() || regex.length() > 200) continue;
                    try { Pattern.compile(regex); } catch (RuntimeException e) { continue; }
                } else if (type.equals("url")) {
                    url = src.optString("url");
                    version = src.optString("version", "");
                    if (!httpsOk(url)) continue;
                } else if (!type.equals("appstore")) {
                    continue;
                }
                ids.add(id);
                Entry entry = new Entry(id, name, pkg, clip(o.optString("description"), 160), clip(o.optString("category"), 30),
                        icon.isEmpty() ? null : icon, type, repo, regex == null || regex.isEmpty() ? null : regex,
                        url, version == null || version.isEmpty() ? null : version, false);
                extras(entry, o);
                out.add(entry);
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The optional extras an app's page shows: "long_description" (up to 1000 characters),
     * "screenshots" (up to 4 https links), "license" (an SPDX id) and "website" (https).
     */
    private static void extras(Entry e, JSONObject o) {
        e.longDescription = clip(o.optString("long_description"), 1000);
        String lic = clip(o.optString("license"), 40);
        e.license = lic.matches("[A-Za-z0-9.+-]+") ? lic : null;
        String web = o.optString("website", "");
        e.website = httpsOk(web) ? web : null;
        JSONArray shots = o.optJSONArray("screenshots");
        for (int i = 0; shots != null && i < shots.length() && e.screenshots.size() < MAX_SHOTS; i++) {
            String u = shots.optString(i, "");
            if (httpsOk(u) && !e.screenshots.contains(u)) e.screenshots.add(u);
        }
    }

    // ---- your own apps ------------------------------------------------------------------------

    /** Your own apps, from Prefs.STORE_CUSTOM. */
    static List<Entry> custom(Context c) {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Prefs.of(c).getString(Prefs.STORE_CUSTOM, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String id = o.optString("id"), name = o.optString("name"), repo = o.optString("repo", ""), url = o.optString("url", "");
                if (!id.matches("c[0-9a-f]{12}") || name.isEmpty()) continue;
                if (o.has("manifest")) { // a web app's manifest link
                    Entry w = webappEntry(id, name, null, o.optString("manifest"), true);
                    if (w != null) out.add(w);
                    continue;
                }
                boolean gh = !repo.isEmpty();
                out.add(new Entry(id, name, learned(c, id), "Added by you", "Your apps", null,
                        gh ? "github" : "url", gh ? repo : null, null, gh ? null : url, null, true));
            }
        } catch (Exception ignored) {
            // A broken list shows as empty; the phone page can save a new one.
        }
        return out;
    }

    /** Your own apps as the phone page sees them. */
    static JSONArray customJson(Context c) throws org.json.JSONException {
        JSONArray out = new JSONArray();
        for (Entry e : custom(c)) {
            JSONObject o = new JSONObject().put("id", e.id).put("name", e.name);
            if (e.webapp()) o.put("manifest", e.url);
            else if (e.repo != null) o.put("repo", e.repo); else o.put("url", e.url);
            o.put("pkg", e.pkg == null ? "" : e.pkg);
            out.put(o);
        }
        return out;
    }

    /**
     * Replaces your own apps with this list (from the phone page). Returns null when saved, else
     * what was wrong. Each needs a name and either a GitHub repo (owner/name) or an https APK link.
     */
    static String setCustom(Context c, JSONArray a) {
        if (a.length() > MAX_CUSTOM) return "at most " + MAX_CUSTOM + " apps";
        JSONArray out = new JSONArray();
        List<String> keep = new ArrayList<>();
        try {
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) return "an app wasn't readable";
                String name = o.optString("name", "").trim();
                if (name.isEmpty() || name.length() > MAX_NAME || !name.matches("[^\\p{Cntrl}]+")) return "each app needs a name of up to " + MAX_NAME + " letters";
                String repo = o.optString("repo", "").trim(), url = o.optString("url", "").trim();
                if (repo.startsWith("https://github.com/")) repo = repo.substring(19).replaceAll("/+$", "");
                JSONObject n = new JSONObject();
                String manifest = o.optString("manifest", "").trim();
                if (!manifest.isEmpty()) { // a MaterialTV web app
                    if (!repo.isEmpty() || !url.isEmpty()) return "give each app one link";
                    if (!httpsOk(manifest)) return "web app manifests must start with https:// (up to " + MAX_URL + " characters)";
                    n.put("manifest", manifest);
                } else if (!repo.isEmpty() && url.isEmpty()) {
                    if (!REPO.matcher(repo).matches()) return "\"" + repo + "\" isn't a GitHub repo (owner/name)";
                    n.put("repo", repo);
                } else if (repo.isEmpty() && !url.isEmpty()) {
                    if (!httpsOk(url)) return "links must start with https:// (up to " + MAX_URL + " characters)";
                    n.put("url", url);
                } else {
                    return "give each app a GitHub repo or a link, not both";
                }
                String id = o.optString("id", "");
                if (!id.matches("c[0-9a-f]{12}") || keep.contains(id)) id = newId();
                keep.add(id);
                out.put(n.put("id", id).put("name", name));
            }
        } catch (org.json.JSONException e) {
            return "an app wasn't readable";
        }
        android.content.SharedPreferences.Editor ed = Prefs.of(c).edit().putString(Prefs.STORE_CUSTOM, out.toString());
        for (Entry e : custom(c)) if (!keep.contains(e.id)) ed.remove(LEARNED + e.id); // forget removed apps
        ed.commit();
        return null;
    }

    /** Removes one of your own apps (the TV's "Remove from your apps"). */
    static void removeCustom(Context c, String id) {
        try {
            JSONArray a = new JSONArray(Prefs.of(c).getString(Prefs.STORE_CUSTOM, "[]")), out = new JSONArray();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if (!id.equals(o.optString("id"))) out.put(o);
            }
            Prefs.of(c).edit().putString(Prefs.STORE_CUSTOM, out.toString()).remove(LEARNED + id).commit();
        } catch (org.json.JSONException ignored) {
            // Nothing to remove.
        }
        problems.remove(id);
    }

    private static String learned(Context c, String id) { return Prefs.of(c).getString(LEARNED + id, null); }

    private static String newId() {
        byte[] b = new byte[6];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder("c");
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x & 0xFF));
        return sb.toString();
    }

    // ---- what the row shows ------------------------------------------------------------------

    /**
     * The row: catalog apps you haven't got, installed ones with an update, and all of your own.
     * Slow (package manager, icons): call off the main thread.
     */
    static List<Item> items(Context c) {
        PackageManager pm = c.getPackageManager();
        JSONObject rel = releases(c);
        List<Item> out = new ArrayList<>();
        for (Entry e : all(c)) {
            if (e.webapp()) { out.add(webappItem(c, e)); continue; }
            if (e.plugin()) { // plugin entries: see the plugins section below
                Item p = pluginItem(c, e);
                if (p != null) out.add(p);
                continue;
            }
            out.add(appItem(c, pm, rel, e));
        }
        return out;
    }

    /**
     * One app as its page shows it right now (plugins too, installed or not). Null if the id is
     * no longer in the catalog or your apps. Slow: call off the main thread.
     */
    static Item item(Context c, String id) {
        Entry e = find(c, id);
        if (e == null) return null;
        if (e.webapp()) return webappItem(c, e);
        if (e.plugin()) {
            Item p = pluginItem(c, e);
            return p != null ? p : new Item(e, INSTALLED, null, null, -1, icon(c, e.id), Plugins.tint(e.id));
        }
        return appItem(c, c.getPackageManager(), releases(c), e);
    }

    private static Item appItem(Context c, PackageManager pm, JSONObject rel, Entry e) {
        String installed = null;
        if (e.pkg != null) {
            try {
                PackageInfo pi = pm.getPackageInfo(e.pkg, 0);
                installed = pi.versionName == null ? "" : pi.versionName;
            } catch (PackageManager.NameNotFoundException ignored) {
                // Not installed.
            }
        }
        JSONObject r = rel.optJSONObject(e.id);
        String latest = e.version != null ? e.version : r == null ? null : r.optString("version", null);
        long size = r == null ? -1 : r.optLong("size", -1);
        int state;
        if (installed == null) state = GET;
        else if (!e.appstore() && latest != null && newer(latest, installed)) state = UPDATE;
        else if (e.custom) state = INSTALLED;
        else state = INSTALLED; // kept on the row so you can open or uninstall it from here
        Bitmap icon = icon(c, e.id);
        int tint = icon != null ? M3.containerFor(new BitmapDrawable(c.getResources(), icon)) : 0;
        if (icon == null || tint == M3.SURFACE_HIGH) {
            float hue = (e.id.hashCode() & 0x7FFFFFFF) % 360; // a steady colour per app
            tint = Color.HSVToColor(new float[] { hue, 0.42f, 0.30f });
        }
        return new Item(e, state, e.pkg, latest, size, icon, tint);
    }

    // ---- plugins (entries with "type": "plugin"; everything else lives in Plugins.java) ----------

    /**
     * A catalog entry {"id", "name", "type": "plugin", "manifest": "https://…", "description",
     * "category", "icon_url"}, or null when o isn't a plugin entry or isn't a valid one.
     */
    private static Entry parsePluginEntry(JSONObject o, List<String> ids) {
        if (o == null || !"plugin".equals(o.optString("type"))) return null;
        String id = o.optString("id"), name = o.optString("name").trim(), manifest = o.optString("manifest");
        if (!ID.matcher(id).matches() || ids.contains(id) || name.isEmpty() || name.length() > MAX_NAME) return null;
        if (!httpsOk(manifest)) return null;
        String icon = o.optString("icon_url", "");
        return new Entry(id, name, null, clip(o.optString("description"), 160), clip(o.optString("category", "Plugins"), 30),
                httpsOk(icon) ? icon : null, "plugin", null, null, manifest, null, false);
    }

    /** A plugin in the row while it isn't installed yet (installed ones are in Settings > Plugins). */
    private static Item pluginItem(Context c, Entry e) {
        for (Plugins.Plugin p : Plugins.all(c)) if (e.url.equals(p.url)) return null;
        Bitmap icon = icon(c, e.id);
        int tint = icon != null ? M3.containerFor(new BitmapDrawable(c.getResources(), icon)) : 0;
        if (icon == null || tint == M3.SURFACE_HIGH) tint = Plugins.tint(e.id);
        return new Item(e, GET, null, null, -1, icon, tint);
    }

    // ---- end of plugins ----------------------------------------------------------------------

    /** True when version a is newer than b, comparing the leading numbers ("v1.2.3", "32.80s", "2026-09-17"). */
    static boolean newer(String a, String b) {
        List<Long> x = numbers(a), y = numbers(b);
        if (x.isEmpty() || y.isEmpty()) return false;
        for (int i = 0; i < Math.max(x.size(), y.size()); i++) {
            long p = i < x.size() ? x.get(i) : 0, q = i < y.size() ? y.get(i) : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    private static List<Long> numbers(String v) {
        List<Long> out = new ArrayList<>();
        Matcher m = Pattern.compile("^[vV]?([0-9][0-9.\\-]*)").matcher(v == null ? "" : v.trim());
        if (!m.find()) return out;
        for (String part : m.group(1).split("[.\\-]")) {
            if (part.isEmpty() || part.length() > 12) continue;
            out.add(Long.parseLong(part));
        }
        return out;
    }

    // ---- refreshing the catalog and versions ---------------------------------------------------

    /** Once a day, in the background. */
    static void refreshIfDue(Context c) {
        if (refreshing || System.currentTimeMillis() - Prefs.of(c).getLong(LAST_REFRESH, 0) < DAY_MS) return;
        final Context app = c.getApplicationContext();
        new Thread(() -> refresh(app), "m3home-store-refresh").start();
    }

    /** Fetches the catalog, the latest versions and the icons. Returns a short status line. Off the main thread. */
    static String refresh(Context c) {
        if (refreshing) return "Already refreshing…";
        refreshing = true;
        try {
            Prefs.of(c).edit().putLong(LAST_REFRESH, System.currentTimeMillis()).apply();
            String body = http(CATALOG_URL, null);
            List<Entry> fresh = parse(body);
            boolean got = fresh != null && !fresh.isEmpty();
            if (got) {
                write(new File(c.getFilesDir(), CACHE), body.getBytes("UTF-8"));
                catalog = fresh;
            }
            String why = got ? null : lastProblem(CATALOG_URL);
            String rbody = http(RESOLVED_URL, null);
            if (parseResolved(rbody) != null) {
                write(new File(c.getFilesDir(), RESOLVED), rbody.getBytes("UTF-8"));
                resolved = null; // re-pick between this and the bundled copy
            }
            int found = 0, repos = 0;
            String ghWhy = null;
            for (Entry e : all(c)) {
                if (e.repo != null) {
                    repos++;
                    clearProblem();
                    if (resolve(c, e) != null) found++;
                    else if (ghWhy == null && hadProblem()) ghWhy = lastProblem(null);
                }
                fetchIcon(c, e);
            }
            WebApps.refreshAll(c); // installed web apps: re-read their manifests
            changed(null);
            if (!got) return "Couldn't reach the app store: " + why + " Showing the last list.";
            return "App store refreshed · " + catalog(c).size() + " apps"
                    + (repos > 0 && found == 0 ? ". GitHub didn't answer: " + (ghWhy == null ? "no downloads found." : ghWhy) : "");
        } catch (Exception e) {
            return "Couldn't refresh the app store: " + Net.explain(0, e);
        } finally {
            refreshing = false;
        }
    }

    /**
     * The download for an app: {version, url, size}. Catalog apps come from store-resolved.json (no
     * api.github.com call); only a repo missing from it (your own apps, or one added since the
     * last Action run) asks GitHub, at most every 12 hours per repo. Off the main thread.
     */
    private static JSONObject resolve(Context c, Entry e) throws org.json.JSONException {
        if (e.appstore()) return null;
        if (e.repo == null) { // a fixed link: the catalog's version, the resolved file's size if it's the same link
            JSONObject known = e.custom ? null : resolved(c).optJSONObject(e.id);
            long size = known != null && e.url.equals(known.optString("url")) ? known.optLong("size", -1) : -1;
            JSONObject r = new JSONObject().put("version", e.version == null ? "" : e.version).put("url", e.url).put("size", size);
            if (size > 0) remember(c, e.id, r);
            return r;
        }
        JSONObject known = e.custom ? null : resolved(c).optJSONObject(e.id);
        if (known != null && e.repo.equals(known.optString("repo")) && httpsOk(known.optString("url"))) {
            JSONObject r = new JSONObject().put("version", known.optString("version", ""))
                    .put("url", known.optString("url")).put("size", known.optLong("size", -1));
            remember(c, e.id, r);
            return r;
        }
        JSONObject release = githubRelease(c, e.repo);
        if (release == null) return null;
        JSONArray assets = release.optJSONArray("assets");
        Pattern want = e.assetRegex == null ? null : Pattern.compile(e.assetRegex);
        JSONObject best = null;
        int bestScore = -1;
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            String name = a.optString("name");
            if (!name.toLowerCase(Locale.ROOT).endsWith(".apk")) continue;
            if (want != null && !want.matcher(name).find()) continue;
            int s = score(name);
            if (s > bestScore) { bestScore = s; best = a; }
        }
        if (best == null || bestScore < 0) return null;
        String url = best.optString("browser_download_url");
        if (!httpsOk(url)) return null;
        JSONObject r = new JSONObject().put("version", release.optString("tag_name", ""))
                .put("url", url).put("size", best.optLong("size", -1));
        remember(c, e.id, r);
        return r;
    }

    /** Keeps an app's {version, url, size} for the row (store-releases.json). */
    private static void remember(Context c, String id, JSONObject r) throws org.json.JSONException {
        synchronized (Store.class) {
            JSONObject all = releases(c);
            JSONObject old = all.optJSONObject(id);
            if (old != null && old.toString().equals(r.toString())) return;
            all.put(id, r);
            write(new File(c.getFilesDir(), RELEASES), all.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    /** store-resolved.json by app id: the newer of the downloaded copy and the one in the APK. */
    private static JSONObject resolved(Context c) {
        JSONObject r = resolved;
        if (r != null) return r;
        JSONObject cached = parseResolved(read(new File(c.getFilesDir(), RESOLVED))), bundled = null;
        try (InputStream in = c.getAssets().open(RESOLVED)) {
            bundled = parseResolved(readAll(in));
        } catch (IOException ignored) {
            // No bundled copy.
        }
        if (cached == null) r = bundled;
        else if (bundled == null) r = cached;
        else r = bundled.optString(GENERATED).compareTo(cached.optString(GENERATED)) > 0 ? bundled : cached;
        if (r == null) r = new JSONObject();
        resolved = r;
        return r;
    }

    /** {"generated", "apps": [{id, repo?, version, url, size, asset}]} as id -> entry; null if it isn't one. */
    private static JSONObject parseResolved(String body) {
        if (body == null) return null;
        try {
            JSONObject doc = new JSONObject(body);
            JSONArray a = doc.getJSONArray("apps");
            JSONObject out = new JSONObject().put(GENERATED, doc.optString("generated", ""));
            for (int i = 0; i < a.length() && i < 200; i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && ID.matcher(o.optString("id")).matches()) out.put(o.optString("id"), o);
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * A repo's latest release {tag_name, assets: [{name, browser_download_url, size}]}, for repos
     * store-resolved.json doesn't cover. Remembered for 12 hours. After that, github.com's
     * /releases/latest redirect (a web page, not the rate-limited API) gives the tag, and the API
     * is asked only when the tag has changed. Off the main thread.
     */
    private static JSONObject githubRelease(Context c, String repo) {
        File f = new File(c.getFilesDir(), GITHUB);
        JSONObject old = readJson(f).optJSONObject(repo);
        long now = System.currentTimeMillis();
        if (old != null && Math.abs(now - old.optLong("time")) < GITHUB_TTL_MS) return old;
        try {
            JSONObject release;
            String tag = latestTag(repo);
            if (old != null && tag != null && tag.equals(old.optString("tag_name"))) {
                release = old;
            } else {
                String body = http("https://api.github.com/repos/" + repo + "/releases/latest", "application/vnd.github+json");
                if (body == null) return old; // offline or limited: the last answer beats none
                JSONObject full = new JSONObject(body);
                JSONArray assets = new JSONArray(), all = full.optJSONArray("assets");
                for (int i = 0; all != null && i < all.length() && i < 100; i++) {
                    JSONObject a = all.getJSONObject(i);
                    assets.put(new JSONObject().put("name", a.optString("name"))
                            .put("browser_download_url", a.optString("browser_download_url")).put("size", a.optLong("size", -1)));
                }
                release = new JSONObject().put("tag_name", full.optString("tag_name", "")).put("assets", assets)
                        .put("published_at", full.optString("published_at", ""))
                        .put("notes", plainNotes(full.optString("body", "")));
            }
            release.put("time", now);
            synchronized (Store.class) {
                JSONObject cache = readJson(f);
                cache.put(repo, release);
                write(f, cache.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return release;
        } catch (org.json.JSONException x) {
            return old;
        }
    }

    private static JSONObject readJson(File f) {
        try {
            String s = read(f);
            return s == null ? new JSONObject() : new JSONObject(s);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** The latest release's tag, from github.com/<repo>/releases/latest's redirect; null if unknown. No API call. */
    private static String latestTag(String repo) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL("https://github.com/" + repo + "/releases/latest").openConnection();
            con.setInstanceFollowRedirects(false);
            con.setRequestMethod("HEAD");
            con.setConnectTimeout(10_000);
            con.setReadTimeout(15_000);
            con.setRequestProperty("User-Agent", "M3Home-Store");
            int code = con.getResponseCode();
            String to = con.getHeaderField("Location");
            if (code < 300 || code >= 400 || to == null) return null;
            int at = to.lastIndexOf("/releases/tag/");
            return at < 0 ? null : Uri.decode(to.substring(at + "/releases/tag/".length()));
        } catch (Exception x) {
            return null;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    /** Fire TV sticks are 32-bit ARM: prefer armeabi-v7a, then universal; never arm64 or x86. */
    static int score(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("arm64") || n.contains("aarch64") || n.contains("x86") || n.contains("x64")) return -1;
        int s = n.matches(".*(armeabi|armv7|arm32|(^|[^a-z0-9])arm([^a-z0-9]|$)).*") ? 3 : n.contains("universal") ? 2 : 1;
        return n.contains("debug") ? 0 : s;
    }

    private static JSONObject releases(Context c) {
        try {
            String s = read(new File(c.getFilesDir(), RELEASES));
            return s == null ? new JSONObject() : new JSONObject(s);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    // ---- an app's page: its latest release, release notes and screenshots ----------------------

    /** What an app's page knows about its latest release. Any text may be empty, the size -1. */
    static final class Release {
        final String version, published, notes;
        final long size;

        Release(String version, String published, String notes, long size) {
            this.version = version; this.published = published; this.notes = notes; this.size = size;
        }
    }

    /**
     * The latest release as known now, without the network: store-resolved.json for catalog apps
     * (version, size, date and notes), else what was last fetched from GitHub. Off the main thread.
     */
    static Release release(Context c, Entry e) {
        String version = e.version != null ? e.version : "", published = "", notes = "";
        long size = -1;
        JSONObject known = e.custom || e.appstore() ? null : resolved(c).optJSONObject(e.id);
        boolean same = known != null && (e.repo != null ? e.repo.equals(known.optString("repo"))
                : e.url != null && e.url.equals(known.optString("url")));
        if (same) {
            if (version.isEmpty()) version = known.optString("version", "");
            size = known.optLong("size", -1);
            published = known.optString("published_at", "");
            notes = known.optString("notes", "");
        }
        if (e.repo != null && published.isEmpty()) {
            JSONObject gh = readJson(new File(c.getFilesDir(), GITHUB)).optJSONObject(e.repo);
            if (gh != null) {
                if (version.isEmpty()) version = gh.optString("tag_name", "");
                published = gh.optString("published_at", "");
                notes = gh.optString("notes", "");
            }
        }
        JSONObject r = releases(c).optJSONObject(e.id);
        if (r != null) {
            if (version.isEmpty()) version = r.optString("version", "");
            if (size <= 0) size = r.optLong("size", -1);
        }
        return new Release(clip(version, 40), clip(published, 40), clip(notes, MAX_NOTES), size);
    }

    /**
     * Like release(), but first asks GitHub about repos store-resolved.json doesn't cover (your
     * own apps, mostly), at most every 12 hours per repo. Off the main thread.
     */
    static Release fetchRelease(Context c, Entry e) {
        if (e.repo != null) {
            try {
                resolve(c, e);
            } catch (Exception ignored) {
                // Show what's known.
            }
        }
        return release(c, e);
    }

    /** GitHub release notes (markdown) as short plain text: the same rules as resolve_store.py's plain(). */
    static String plainNotes(String md) {
        if (md == null || md.isEmpty()) return "";
        String t = md.replace("\r\n", "\n").replace('\r', '\n');
        if (t.length() > 20_000) t = t.substring(0, 20_000);
        t = t.replaceAll("(?s)<!--.*?-->", "")
                .replaceAll("(?s)```.*?```", "")                          // code blocks
                .replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", "")              // images
                .replaceAll("(?i)<img[^>]*>", "")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("<[^>]+>", "")                                // other html tags
                .replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1")           // links -> their text
                .replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "")                // headings
                .replaceAll("(?m)^\\s{0,3}>\\s?", "")                     // quotes
                .replaceAll("(?m)^\\s*(-{3,}|\\*{3,}|_{3,})\\s*$", "")    // rules
                .replaceAll("(?m)^\\s*\\|.*\\|\\s*$", "")                 // tables
                .replaceAll("(?m)^\\s*[-*+]\\s+", "\u2022 ")              // bullets
                .replaceAll("(\\*\\*|__|~~|`)", "")                       // emphasis, code
                .replaceAll("[ \\t]+\n", "\n")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
        if (t.length() > MAX_NOTES) {
            String cut = t.substring(0, MAX_NOTES - 1);
            int sp = Math.max(cut.lastIndexOf(' '), cut.lastIndexOf('\n'));
            t = (sp > MAX_NOTES * 0.7 ? cut.substring(0, sp) : cut).trim() + "\u2026";
        }
        return t;
    }

    /**
     * A screenshot from an app's catalog entry, at most maxWidth pixels wide (RGB_565), or null.
     * https only; each download is at most 4 MB and is kept in the cache (24 MB in all, the
     * oldest dropped first). Off the main thread; load one at a time.
     */
    static Bitmap screenshot(Context c, String url, int maxWidth) {
        if (!httpsOk(url)) return null;
        File dir = new File(c.getCacheDir(), "store-shots");
        File f = new File(dir, Integer.toHexString(url.hashCode()) + "-" + Integer.toHexString(url.length()) + ".img");
        if (!f.isFile()) {
            byte[] b = bytes(url, MAX_SHOT);
            if (b == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(b, 0, b.length, o);
            if (o.outWidth <= 0 || o.outHeight <= 0 || o.outWidth > 8192 || o.outHeight > 8192) return null; // not a sensible picture
            dir.mkdirs();
            trimShots(dir, b.length);
            write(f, b);
            if (!f.isFile()) return decodeShot(b, o.outWidth, maxWidth);
        }
        f.setLastModified(System.currentTimeMillis());
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            if (o.outWidth <= 0) { f.delete(); return null; }
            return scaleTo(BitmapFactory.decodeFile(f.getPath(), shotOptions(o.outWidth, maxWidth)), maxWidth);
        } catch (OutOfMemoryError | RuntimeException e) {
            return null;
        }
    }

    private static Bitmap decodeShot(byte[] b, int width, int maxWidth) {
        try {
            return scaleTo(BitmapFactory.decodeByteArray(b, 0, b.length, shotOptions(width, maxWidth)), maxWidth);
        } catch (OutOfMemoryError | RuntimeException e) {
            return null;
        }
    }

    private static BitmapFactory.Options shotOptions(int width, int maxWidth) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        int sample = 1;
        while (width / (sample * 2) >= maxWidth) sample *= 2;
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return o;
    }

    private static Bitmap scaleTo(Bitmap b, int maxWidth) {
        if (b == null || b.getWidth() <= maxWidth) return b;
        int h = Math.max(1, Math.round(b.getHeight() * (maxWidth / (float) b.getWidth())));
        Bitmap s = Bitmap.createScaledBitmap(b, maxWidth, h, true);
        if (s != b) b.recycle();
        return s;
    }

    /** Makes room in the screenshot cache: the least recently shown go first. */
    private static synchronized void trimShots(File dir, long incoming) {
        File[] files = dir.listFiles();
        if (files == null) return;
        long total = incoming;
        for (File f : files) total += f.length();
        if (total <= MAX_SHOT_CACHE) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : files) {
            if (total <= MAX_SHOT_CACHE) break;
            total -= f.length();
            f.delete();
        }
    }

    // ---- icons --------------------------------------------------------------------------------

    private static File iconFile(Context c, String id) { return new File(new File(c.getFilesDir(), ICONS), id + ".img"); }

    private static void fetchIcon(Context c, Entry e) {
        if (e.iconUrl == null) return;
        if (hasBundledIcon(c, e.id)) return; // bundled store icon: nothing to download
        File f = iconFile(c, e.id);
        if (f.isFile()) return;
        byte[] b = bytes(e.iconUrl, MAX_ICON);
        if (b == null) return;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(b, 0, b.length, o);
        if (o.outWidth <= 0 || o.outWidth > 2048 || o.outHeight > 2048) return; // not a sensible picture
        f.getParentFile().mkdirs();
        write(f, b);
    }

    // ---- bundled store icons (begin) ----
    /** The icon shipped in the APK at assets/store-icons/<id>.png, or null if there isn't one. */
    private static Bitmap bundledIcon(Context c, String id) {
        if (id == null || !id.matches("[A-Za-z0-9._-]+")) return null;
        try (InputStream in = c.getAssets().open(ICONS + "/" + id + ".png")) {
            return BitmapFactory.decodeStream(in);
        } catch (IOException | OutOfMemoryError | RuntimeException e) {
            return null;
        }
    }

    private static boolean hasBundledIcon(Context c, String id) {
        if (id == null || !id.matches("[A-Za-z0-9._-]+")) return false;
        try (InputStream in = c.getAssets().open(ICONS + "/" + id + ".png")) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }
    // ---- bundled store icons (end) ----

    /** An app's icon: the bundled one, else the downloaded one; null if neither. Off the main thread. */
    static Bitmap icon(Context c, String id) {
        Bitmap bundled = bundledIcon(c, id); // bundled store icon first
        if (bundled != null) return bundled;
        File f = iconFile(c, id);
        if (!f.isFile()) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= 192) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            return BitmapFactory.decodeFile(f.getPath(), o2);
        } catch (OutOfMemoryError | RuntimeException e) {
            return null;
        }
    }

    // ---- opening and installing -----------------------------------------------------------------

    /** Opens an installed app. False if it can't be opened. */
    static boolean open(Context c, String pkg) {
        if (pkg == null) return false;
        if (WebApps.idOf(pkg) != null) return openWebapp(c, WebApps.idOf(pkg));
        PackageManager pm = c.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        try {
            c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    /** The Amazon Appstore page for a package (the Fire TV store app, else the web page). */
    static boolean openAppstore(Context c, String pkg) {
        for (String u : new String[] { "amzn://apps/android?p=" + pkg, "https://www.amazon.com/gp/mas/dl/android?p=" + pkg }) {
            try {
                c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (ActivityNotFoundException | SecurityException ignored) {
                // Try the next way.
            }
        }
        return false;
    }

    static boolean busy(String id) { return status.containsKey(id); }

    /** Downloads, checks and hands the app to Android's installer, in the background. Progress goes to the listener. */
    static void install(Context c, Entry e) {
        if (e.webapp()) { installWebapp(c, e); return; }
        if (e.appstore() || busy(e.id)) return;
        final Context app = c.getApplicationContext();
        problems.remove(e.id);
        status.put(e.id, "Starting…");
        changed(e.id);
        jobs.execute(() -> {
            String problem;
            try {
                problem = doInstall(app, e);
            } catch (Exception x) {
                problem = "Install failed";
            }
            if (problem != null) {
                status.remove(e.id);
                problems.put(e.id, problem);
            }
            changed(e.id);
        });
    }

    private static String doInstall(Context c, Entry e) throws Exception {
        clearProblem();
        JSONObject r = resolve(c, e);
        if (r == null) return hadProblem() ? "Couldn't reach GitHub: " + lastProblem(null) : "Couldn't find a download for this Fire TV";
        File dir = new File(c.getCacheDir(), "store");
        dir.mkdirs();
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete(); // one download at a time; nothing to keep
        File apk = new File(dir, e.id + ".apk");
        try {
            status.put(e.id, "0%");
            changed(e.id);
            if (!download(r.getString("url"), apk, e.id)) return "The download failed" + (hadProblem() ? ": " + lastProblem(r.getString("url")) : "");
            status.put(e.id, "Checking…");
            changed(e.id);
            PackageInfo info = c.getPackageManager().getPackageArchiveInfo(apk.getPath(), 0);
            if (info == null || info.packageName == null) return "That download isn't an app";
            String want = e.custom ? learned(c, e.id) : e.pkg;
            if (info.packageName.equals(c.getPackageName())) return "That's MaterialTV itself. Update it from Settings";
            if (want != null && !want.equals(info.packageName)) return "The download is a different app (" + info.packageName + ")";
            if (e.custom && want == null) Prefs.of(c).edit().putString(LEARNED + e.id, info.packageName).commit();

            PackageInstaller pi = c.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(info.packageName);
            params.setSize(apk.length());
            int id = pi.createSession(params);
            try (PackageInstaller.Session s = pi.openSession(id)) {
                try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("app.apk", 0, apk.length())) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    s.fsync(out);
                }
                Intent cb = new Intent(c, Result.class).putExtra("id", e.id).putExtra("name", e.name);
                PendingIntent pending = PendingIntent.getBroadcast(c, id, cb, PendingIntent.FLAG_UPDATE_CURRENT);
                status.put(e.id, "Confirm on screen");
                changed(e.id);
                s.commit(pending.getIntentSender());
            }
            return null;
        } finally {
            apk.delete(); // the installer has its own copy
        }
    }

    /** Android's answer: open its confirmation screen, or report how it went. */
    public static class Result extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            String id = i.getStringExtra("id"), name = i.getStringExtra("name");
            if (id == null) return;
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            }
            status.remove(id);
            if (st == PackageInstaller.STATUS_SUCCESS) {
                problems.remove(id);
                Toast.makeText(c, name + " is installed", Toast.LENGTH_SHORT).show();
            } else {
                String msg = st == PackageInstaller.STATUS_FAILURE_ABORTED ? "Cancelled"
                        : "Not installed" + (i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) == null ? ""
                        : ": " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
                problems.put(id, msg);
                Toast.makeText(c, name + ": " + msg, Toast.LENGTH_LONG).show();
            }
            changed(id);
        }
    }

    // ---- web apps (MaterialTV web apps; the apps themselves live in WebApps) ------------------------
    // A store.json entry {"source": {"type": "webapp", "manifest": "https://…/manifest.json"}}, or one
    // of your own apps with a "manifest" link. Installed ones stay in the row, as Installed, with Open.

    /** A web app entry; null unless the manifest link is https. */
    private static Entry webappEntry(String id, String name, JSONObject o, String manifest, boolean custom) {
        if (!httpsOk(manifest)) return null;
        String icon = o == null ? "" : o.optString("icon_url", "");
        if (!icon.isEmpty() && !httpsOk(icon)) icon = "";
        return new Entry(id, name, null, o == null ? "Added by you" : clip(o.optString("description"), 160),
                o == null ? "Your apps" : clip(o.optString("category"), 30), icon.isEmpty() ? null : icon,
                "webapp", null, null, manifest, null, custom);
    }

    /** How the row shows a web app: Get, or Installed (opens it). */
    private static Item webappItem(Context c, Entry e) {
        WebApps.App app = WebApps.byManifest(c, e.url);
        Bitmap icon = icon(c, e.id);
        if (icon == null && app != null) {
            android.graphics.drawable.Drawable d = WebApps.icon(c, app);
            if (d instanceof BitmapDrawable) icon = ((BitmapDrawable) d).getBitmap();
        }
        int tint = icon != null ? M3.containerFor(new BitmapDrawable(c.getResources(), icon)) : 0;
        if (icon == null || tint == M3.SURFACE_HIGH) {
            float hue = (e.id.hashCode() & 0x7FFFFFFF) % 360;
            tint = Color.HSVToColor(new float[] { hue, 0.42f, 0.30f });
        }
        return new Item(e, app == null ? GET : INSTALLED, app == null ? null : app.pkg(),
                app == null ? null : app.version, -1, icon, tint);
    }

    /** Fetches and checks the manifest, then adds the app to All apps. No APK, no Android prompt. */
    private static void installWebapp(Context c, Entry e) {
        if (busy(e.id)) return;
        final Context app = c.getApplicationContext();
        problems.remove(e.id);
        status.put(e.id, "Installing…");
        changed(e.id);
        jobs.execute(() -> {
            String problem;
            try {
                problem = WebApps.install(app, e.url);
            } catch (Exception x) {
                problem = "Install failed";
            }
            status.remove(e.id);
            final String p = problem;
            if (p != null) problems.put(e.id, p); else problems.remove(e.id);
            main.post(() -> Toast.makeText(app, p == null ? e.name + " is installed" : e.name + ": " + p,
                    p == null ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show());
            changed(e.id);
            changed(null); // reload the row and All apps
        });
    }

    private static boolean openWebapp(Context c, String id) {
        if (WebApps.get(c, id) == null) return false;
        try {
            c.startActivity(WebApps.launchIntent(c, id));
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    /** https-only text and bytes for WebApps (manifests, icons). Off the main thread. */
    static String fetchText(String url) { return http(url, "application/json"); }

    static byte[] fetchBytes(String url, long max) { return bytes(url, max); }

    // ---- network (https only, redirects followed by hand so they can't drop to http) ------------

    static boolean httpsOk(String url) {
        if (url == null || url.length() > MAX_URL || !url.startsWith("https://") || url.matches(".*\\s.*")) return false;
        try {
            URI u = new URI(url);
            return u.getHost() != null && !u.getHost().isEmpty() && u.getRawUserInfo() == null;
        } catch (Exception e) {
            return false;
        }
    }

    // This thread's last HTTP status and exception, so a failure can say why (Net.explain).
    private static final ThreadLocal<Integer> lastCode = new ThreadLocal<>();
    private static final ThreadLocal<Throwable> lastError = new ThreadLocal<>();

    private static void clearProblem() { lastCode.remove(); lastError.remove(); }

    private static boolean hadProblem() {
        Integer code = lastCode.get();
        return lastError.get() != null || (code != null && code != 200);
    }

    /** Why this thread's last request failed, in plain English; url names the site (null: GitHub). */
    static String lastProblem(String url) {
        Integer code = lastCode.get();
        return Net.explain(code == null ? 0 : code, lastError.get(), url);
    }

    private static void failed(Exception e) {
        if (!(e instanceof IOException && String.valueOf(e.getMessage()).startsWith("HTTP "))) lastError.set(e);
    }

    private static HttpURLConnection connect(String url, String accept) throws IOException {
        clearProblem();
        for (int hops = 0; hops < 6; hops++) {
            if (!url.startsWith("https://")) throw new IOException("not https");
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            con.setInstanceFollowRedirects(false);
            con.setConnectTimeout(10_000);
            con.setReadTimeout(30_000);
            con.setRequestProperty("User-Agent", "M3Home-Store");
            if (accept != null) con.setRequestProperty("Accept", accept);
            int code = con.getResponseCode();
            lastCode.set(code);
            if (code >= 300 && code < 400) {
                String to = con.getHeaderField("Location");
                con.disconnect();
                if (to == null) throw new IOException("redirect without a location");
                url = new URL(new URL(url), to).toString();
                continue;
            }
            if (code != 200) { con.disconnect(); throw new IOException("HTTP " + code); }
            return con;
        }
        throw new IOException("too many redirects");
    }

    private static String http(String url, String accept) {
        byte[] b = bytesWith(url, accept, MAX_TEXT);
        try {
            return b == null ? null : new String(b, "UTF-8");
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] bytes(String url, long max) { return bytesWith(url, null, max); }

    private static byte[] bytesWith(String url, String accept, long max) {
        HttpURLConnection con = null;
        try {
            con = connect(url, accept);
            try (InputStream in = con.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > max) return null;
                }
                return out.toByteArray();
            }
        } catch (Exception e) {
            failed(e);
            return null;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    /** Streams the APK to a file, reporting "42%" as it goes. */
    private static boolean download(String url, File to, String id) {
        HttpURLConnection con = null;
        try {
            con = connect(url, null);
            long total = con.getContentLengthLong(), got = 0, shown = 0;
            if (total > MAX_APK) return false;
            try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(to)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    got += n;
                    if (got > MAX_APK) return false;
                    out.write(buf, 0, n);
                    if (got - shown > 512 * 1024) {
                        shown = got;
                        status.put(id, total > 0 ? Math.min(99, got * 100 / total) + "%" : Wallpapers.mb(got));
                        changed(id);
                    }
                }
            }
            return total <= 0 || got == total;
        } catch (Exception e) {
            failed(e);
            return false;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    // ---- files ------------------------------------------------------------------------------

    private static String clip(String s, int max) {
        s = s == null ? "" : s.trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String read(File f) {
        if (!f.isFile()) return null;
        try (InputStream in = new FileInputStream(f)) {
            return readAll(in);
        } catch (IOException e) {
            return null;
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }

    private static void write(File f, byte[] b) {
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(b);
        } catch (IOException e) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(f)) tmp.delete();
    }
}
