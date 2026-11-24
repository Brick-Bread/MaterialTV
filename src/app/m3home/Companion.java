package app.m3home;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The companion site: a tiny web server on the stick (port 8765) that your phone opens by scanning
 * the QR code. It only runs while the QR screen (DeadlinesActivity) is open, only answers devices on
 * the home network or the tailnet, and every request must carry the secret key from the QR link, so
 * other devices can't change anything.
 *
 * Endpoints (all need ?k=KEY): GET / (the page), GET /api/data + POST /api/save (deadlines,
 * countdown, weather town), GET/POST /api/config (customisation), POST /api/background (a picture).
 */
final class Companion {
    static final int PORT = 8765;
    static final int MAX_BODY = 64 * 1024;
    static final int MAX_UPLOAD = 8 * 1024 * 1024;
    private static final int MAX_LINE = 8192, MAX_HEADERS = 64;
    private static final int THREADS = 4;
    private static final int TIMEOUT_MS = 10_000, UPLOAD_TIMEOUT_MS = 30_000;
    /** Whole-request limits, so a phone (or anything else) dripping bytes can't hold a thread forever. */
    private static final long REQUEST_MS = 20_000, UPLOAD_REQUEST_MS = 180_000;

    static final String[] ROWS = { "favourites", "recents", "apps", "store" };
    static final String[] TILE_SIZES = { "compact", "normal", "large" };
    static final String[] THEME_MODES = { "dark", "light" };
    static final String[] TEXT_SIZES = { "small", "normal", "large" };
    static final String[] BG_KINDS = { "gradient", "solid", "wallpaper", "slideshow", "custom" };
    static final String[] SS_MODES = { "rotate", "clock", "shapes", "slideshow" };
    static final String[] SS_MEDIA = { "both", "stills", "live" };
    static final int[] SS_SECONDS = { 10, 15, 20, 30, 60 };
    private static final int MAX_FAVOURITES = 60;
    private static final String[][] WIDGETS = {
            { "weather", Prefs.W_WEATHER }, { "deadlines", Prefs.W_DEADLINES }, { "countdown", Prefs.W_COUNTDOWN },
            { "nowplaying", Prefs.W_NOWPLAYING }, { "tailscale", Prefs.W_TAILSCALE } };

    private static final Object WRITE_LOCK = new Object();
    private static volatile String pageTemplate;

    private final Context context;
    private final Runnable onChange;
    private ServerSocket server;
    private ThreadPoolExecutor pool;
    private volatile boolean running;

    Companion(Context context, Runnable onChange) {
        this.context = context.getApplicationContext();
        this.onChange = onChange;
    }

    /** The secret in the QR link. Made once; "New link" in the TV screen replaces it. */
    static String key(Context c) {
        String k = Prefs.of(c).getString("companion_key", null);
        if (k == null) k = newKey(c);
        return k;
    }

    static String newKey(Context c) {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xFF));
        Prefs.of(c).edit().putString("companion_key", sb.toString()).commit();
        return sb.toString();
    }

    boolean start() {
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(PORT));
        } catch (IOException e) {
            return false;
        }
        // At most THREADS requests at once and a short queue; anything beyond that is dropped.
        pool = new ThreadPoolExecutor(THREADS, THREADS, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8),
                r -> new Thread(r, "m3home-companion-req"));
        pool.allowCoreThreadTimeOut(true);
        running = true;
        final ServerSocket ss = server;
        final ThreadPoolExecutor p = pool;
        new Thread(() -> {
            while (running) {
                Socket s;
                try {
                    s = ss.accept();
                } catch (IOException e) {
                    if (!running) return;
                    continue;
                }
                if (!allowedRemote(s.getInetAddress())) {
                    closeQuietly(s); // not on the home network or tailnet: say nothing at all
                    continue;
                }
                try {
                    p.execute(() -> handle(s));
                } catch (RejectedExecutionException e) {
                    closeQuietly(s);
                }
            }
        }, "m3home-companion").start();
        return true;
    }

    void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        if (pool != null) pool.shutdownNow();
    }

    // ---- security helpers (plain Java, no Android) -------------------------------------------

    /** Home network (private ranges), link-local, loopback, or Tailscale's 100.64.0.0/10. */
    static boolean allowedRemote(InetAddress a) {
        if (a == null) return false;
        if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()) return true;
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) return (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64;
        if (a instanceof Inet6Address) {
            if ((b[0] & 0xFE) == 0xFC) return true; // unique local fc00::/7 (Tailscale uses fd7a:115c:a1e0::/48)
            boolean mapped = true; // ::ffff:a.b.c.d
            for (int i = 0; i < 10; i++) if (b[i] != 0) mapped = false;
            if (mapped && (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF) {
                try {
                    return allowedRemote(InetAddress.getByAddress(Arrays.copyOfRange(b, 12, 16)));
                } catch (IOException e) {
                    return false;
                }
            }
        }
        return false;
    }

    /** Compares the key without leaking how much of it matched through timing. */
    static boolean keyMatches(String expected, String given) {
        if (expected == null || given == null) return false;
        byte[] x = expected.getBytes(StandardCharsets.UTF_8), y = given.getBytes(StandardCharsets.UTF_8);
        int diff = x.length ^ y.length;
        for (int i = 0; i < x.length; i++) diff |= x[i] ^ (i < y.length ? y[i] : 0);
        return diff == 0;
    }

    /** A parsed request line and headers. */
    static final class Request {
        String method, path, query = "";
        long contentLength = -1;
        final Map<String, String> headers = new HashMap<>();

        String header(String name) { return headers.get(name); }
    }

    /** Reads the request line and headers; null when malformed, too long or unsupported. */
    static Request readHead(InputStream in) throws IOException {
        String requestLine = line(in);
        if (requestLine == null || requestLine.isEmpty()) return null;
        String[] parts = requestLine.split(" ");
        if (parts.length != 3 || !parts[1].startsWith("/")) return null;
        Request r = new Request();
        r.method = parts[0];
        String target = parts[1];
        int q = target.indexOf('?');
        r.path = q >= 0 ? target.substring(0, q) : target;
        if (q >= 0) r.query = target.substring(q + 1);
        int fragment = r.query.indexOf('#');
        if (fragment >= 0) r.query = r.query.substring(0, fragment);
        for (int n = 0; ; n++) {
            String h = line(in);
            if (h == null || n > MAX_HEADERS) return null;
            if (h.isEmpty()) break;
            int colon = h.indexOf(':');
            if (colon <= 0) return null;
            String name = h.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = h.substring(colon + 1).trim();
            if (name.equals("content-length")) {
                long len;
                try { len = Long.parseLong(value); } catch (NumberFormatException e) { return null; }
                if (len < 0 || (r.contentLength >= 0 && r.contentLength != len)) return null;
                r.contentLength = len;
            }
            if (name.equals("transfer-encoding")) return null; // no chunked bodies
            r.headers.put(name, value);
        }
        return r;
    }

    /** The first value of a query parameter (raw; the key is plain hex). */
    static String param(String query, String name) {
        if (query == null) return null;
        for (String kv : query.split("&")) {
            int e = kv.indexOf('=');
            if (e > 0 && kv.substring(0, e).equals(name)) return kv.substring(e + 1);
        }
        return null;
    }

    /** Reads exactly n bytes, or null if the phone hung up early. */
    static byte[] readBody(InputStream in, int n) throws IOException {
        byte[] body = new byte[n];
        int read = 0;
        while (read < n) {
            int k = in.read(body, read, n - read);
            if (k < 0) return null;
            read += k;
        }
        return body;
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > MAX_LINE) return null;
        }
        if (c == -1) return null; // the phone hung up mid-line
        return b.toString("UTF-8");
    }

    // ---- HTTP -------------------------------------------------------------------------------

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(TIMEOUT_MS);
            Deadline in = new Deadline(new BufferedInputStream(sock.getInputStream()), REQUEST_MS);
            OutputStream out = sock.getOutputStream();
            Request r = readHead(in);
            if (r == null) { send(out, 400, "text/plain; charset=utf-8", "Bad request"); return; }
            if (!keyMatches(key(context), param(r.query, "k"))) {
                send(out, 403, "text/plain; charset=utf-8", "This link has expired. Scan the QR code on the TV again.");
                return;
            }
            String path = r.path, m = r.method;
            if (path.equals("/") || path.equals("/index.html")) {
                if (!m.equals("GET")) { send(out, 405, "text/plain", "Method not allowed"); return; }
                send(out, 200, "text/html; charset=utf-8", page());
            } else if (path.equals("/api/data")) {
                if (!m.equals("GET")) { send(out, 405, "text/plain", "Method not allowed"); return; }
                send(out, 200, "application/json", Deadlines.json(context));
            } else if (path.equals("/api/save")) {
                if (!m.equals("POST")) { send(out, 405, "text/plain", "Method not allowed"); return; }
                String json = jsonBody(in, out, r);
                if (json != null) saveDeadlines(out, json);
            } else if (path.equals("/api/config")) {
                if (m.equals("GET")) {
                    send(out, 200, "application/json", config(context).toString());
                } else if (m.equals("POST")) {
                    String json = jsonBody(in, out, r);
                    if (json == null) return;
                    String msg;
                    try {
                        msg = applyConfig(new JSONObject(json));
                    } catch (JSONException e) {
                        reply(out, 400, false, "That didn't look right.");
                        return;
                    }
                    if (onChange != null) onChange.run();
                    reply(out, 200, true, msg);
                } else {
                    send(out, 405, "text/plain", "Method not allowed");
                }
            } else if (path.equals("/api/background")) {
                if (!m.equals("POST")) { send(out, 405, "text/plain", "Method not allowed"); return; }
                upload(sock, in, out, r);
            } else {
                send(out, 404, "text/plain", "Not found");
            }
        } catch (Exception ignored) {
            // A dropped phone connection is fine.
        }
    }

    /** The JSON body, or null after answering with an error. */
    private static String jsonBody(InputStream in, OutputStream out, Request r) throws IOException {
        if (r.contentLength < 0) { send(out, 411, "text/plain", "Length required"); return null; }
        if (r.contentLength == 0 || r.contentLength > MAX_BODY) { send(out, 413, "text/plain", "Too large"); return null; }
        byte[] body = readBody(in, (int) r.contentLength);
        if (body == null) return null;
        return new String(body, StandardCharsets.UTF_8);
    }

    private void saveDeadlines(OutputStream out, String json) throws IOException, JSONException {
        String err, placeMsg = "";
        synchronized (WRITE_LOCK) {
            err = Deadlines.save(context, json);
            String place = err == null ? new JSONObject(json).optString("place", "").trim() : "";
            if (place.length() > 80) place = place.substring(0, 80);
            if (err == null && !place.isEmpty() && !place.equalsIgnoreCase(Prefs.weatherPlace(context))) {
                String found = Weather.setPlace(context, place);
                placeMsg = found == null ? "Couldn't find that town; weather unchanged." : "Weather now for " + found + ".";
            }
        }
        if (err == null && onChange != null) onChange.run();
        reply(out, err == null ? 200 : 400, err == null, err == null ? ("Saved. " + placeMsg).trim() : err);
    }

    private void upload(Socket sock, Deadline in, OutputStream out, Request r) throws IOException, JSONException {
        String type = r.header("content-type");
        if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("image/")) {
            send(out, 415, "text/plain", "Send a picture");
            return;
        }
        if (r.contentLength < 0) { send(out, 411, "text/plain", "Length required"); return; }
        if (r.contentLength == 0) { reply(out, 400, false, "That picture was empty."); return; }
        if (r.contentLength > MAX_UPLOAD) { reply(out, 413, false, "That picture is too big (8 MB at most)."); return; }
        sock.setSoTimeout(UPLOAD_TIMEOUT_MS);
        in.extend(UPLOAD_REQUEST_MS);
        int length = (int) r.contentLength;
        String err;
        synchronized (WRITE_LOCK) {
            err = Backdrop.saveUpload(context, new Bounded(in, length), length);
            if (err == null && !"custom".equals(Prefs.bgKind(context))) {
                Prefs.of(context).edit().putString(Prefs.BG_KIND, "custom").commit();
                Backdrop.changed(context);
            }
        }
        if (err == null && onChange != null) onChange.run();
        reply(out, err == null ? 200 : 400, err == null, err == null ? "Your picture is now the Home background." : err);
    }

    private static void reply(OutputStream out, int code, boolean ok, String message) throws IOException {
        try {
            send(out, code, "application/json", new JSONObject().put("ok", ok).put("message", message).toString());
        } catch (JSONException e) {
            send(out, 500, "text/plain", "Error");
        }
    }

    private static void send(OutputStream out, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String status;
        switch (code) {
            case 200: status = "OK"; break;
            case 400: status = "Bad Request"; break;
            case 403: status = "Forbidden"; break;
            case 404: status = "Not Found"; break;
            case 405: status = "Method Not Allowed"; break;
            case 411: status = "Length Required"; break;
            case 413: status = "Payload Too Large"; break;
            case 415: status = "Unsupported Media Type"; break;
            default: status = "Error";
        }
        String head = "HTTP/1.1 " + code + " " + status + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + b.length
                + "\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer"
                + "\r\nX-Frame-Options: DENY"
                + "\r\nContent-Security-Policy: default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; "
                + "img-src 'self' data: blob:; connect-src 'self'; form-action 'none'; frame-ancestors 'none'; base-uri 'none'"
                + "\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }

    private static void closeQuietly(Socket s) {
        try { s.close(); } catch (IOException ignored) { }
    }

    /** Fails reads once the request has taken too long overall. */
    static final class Deadline extends FilterInputStream {
        private volatile long end;

        Deadline(InputStream in, long ms) { super(in); extend(ms); }

        void extend(long ms) { end = System.nanoTime() + ms * 1_000_000L; }

        private void check() throws IOException {
            if (System.nanoTime() - end > 0) throw new IOException("request took too long");
        }

        @Override public int read() throws IOException { check(); return super.read(); }
        @Override public int read(byte[] b, int off, int len) throws IOException { check(); return super.read(b, off, len); }
    }

    /** Lets the reader see at most n bytes of the request, and never closes the socket. */
    private static final class Bounded extends FilterInputStream {
        private long left;

        Bounded(InputStream in, long n) { super(in); left = n; }

        @Override public int read() throws IOException {
            if (left <= 0) return -1;
            int c = super.read();
            if (c >= 0) left--;
            return c;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int n = super.read(b, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }

        @Override public long skip(long n) throws IOException {
            long k = super.skip(Math.min(n, left));
            left -= k;
            return k;
        }

        @Override public int available() throws IOException { return (int) Math.min(super.available(), left); }
        @Override public void close() { }
        @Override public boolean markSupported() { return false; }
    }

    // ---- the page -----------------------------------------------------------------------------

    /** assets/companion/index.html with the TV's current colours filled in. */
    private String page() throws IOException {
        String t = pageTemplate;
        if (t == null) {
            try (InputStream in = context.getAssets().open("companion/index.html")) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                t = pageTemplate = b.toString("UTF-8");
            }
        }
        boolean light = Prefs.light(context);
        M3.Scheme s = Palette.scheme(Palette.seedOf(context, Prefs.accent(context)), light);
        StringBuilder css = new StringBuilder(":root{color-scheme:").append(light ? "light" : "dark");
        for (Map.Entry<String, Integer> e : colours(s).entrySet()) css.append(";--").append(e.getKey()).append(':').append(hex(e.getValue()));
        css.append('}');
        return t.replace("/*M3VARS*/", css.toString());
    }

    /** The colour roles the page uses, as CSS variable names. */
    private static Map<String, Integer> colours(M3.Scheme s) {
        Map<String, Integer> m = new java.util.LinkedHashMap<>();
        m.put("bg", s.background); m.put("s1", s.surfaceLow); m.put("s2", s.surfaceHigh); m.put("s3", s.surfaceHighest);
        m.put("p", s.primary); m.put("op", s.onPrimary); m.put("pc", s.primaryCont); m.put("opc", s.onPrimaryCont);
        m.put("sc", s.secondaryCont); m.put("osc", s.onSecondaryCont); m.put("on", s.onSurface); m.put("v", s.onSurfaceVar);
        m.put("o", s.outline); m.put("ov", s.outlineVar); m.put("err", s.error);
        return m;
    }

    static String hex(int argb) {
        return String.format(Locale.ROOT, "#%06X", argb & 0xFFFFFF);
    }

    // ---- GET /api/config ------------------------------------------------------------------------

    static JSONObject config(Context c) throws JSONException {
        SharedPreferences p = Prefs.of(c);
        JSONObject o = new JSONObject();
        o.put("rows", new JSONArray(rows(Prefs.homeRows(c))));
        o.put("tileSize", Prefs.tileSize(c));
        o.put("accent", Prefs.accent(c));
        JSONArray accents = new JSONArray();
        for (int i = 0; i < Palette.ACCENTS.length; i++) {
            accents.put(new JSONObject().put("id", Palette.ACCENTS[i])
                    .put("name", i < Palette.ACCENT_NAMES.length ? Palette.ACCENT_NAMES[i] : Palette.ACCENTS[i])
                    .put("color", hex(Palette.seedOf(c, Palette.ACCENTS[i]))));
        }
        o.put("accents", accents);
        o.put("themeMode", Prefs.light(c) ? "light" : "dark");
        o.put("textSize", p.getString(Prefs.TEXT_SIZE, "normal"));

        JSONObject bg = new JSONObject();
        bg.put("kind", Prefs.bgKind(c));
        bg.put("color", hex(p.getInt(Prefs.BG_COLOR, Backdrop.SOLID_PRESETS[0])));
        bg.put("wallpaper", p.getString(Prefs.BG_WALLPAPER, ""));
        bg.put("dim", Prefs.bgDim(c));
        bg.put("blur", Prefs.bgBlur(c));
        // slideshow: minutes per picture, chosen themes (all = every downloaded theme), pictures per theme
        bg.put("minutes", Prefs.bgSlideMinutes(c));
        bg.put("themes", new JSONArray(Prefs.bgSlideThemes(c)));
        JSONObject slideCounts = new JSONObject();
        for (String t : Prefs.THEMES) slideCounts.put(t, Backdrop.slideCount(c, t));
        bg.put("themeCounts", slideCounts);
        o.put("bg", bg);
        JSONArray presets = new JSONArray();
        for (int col : Backdrop.SOLID_PRESETS) presets.put(hex(col));
        o.put("solidPresets", presets);
        JSONArray walls = new JSONArray();
        for (String[] w : Backdrop.wallpaperOptions(c)) walls.put(new JSONObject().put("id", w[0]).put("label", w[1]));
        o.put("wallpapers", walls);

        Apps apps = new Apps(c);
        o.put("favourites", new JSONArray(apps.favourites()));
        Set<String> hidden = new HashSet<>(apps.hidden());
        JSONArray list = new JSONArray();
        for (String[] a : apps.labels()) {
            list.put(new JSONObject().put("pkg", a[0]).put("label", a[1]).put("hidden", hidden.contains(a[0])));
        }
        o.put("apps", list);

        JSONObject widgets = new JSONObject();
        for (String[] w : WIDGETS) widgets.put(w[0], Prefs.on(c, w[1]));
        o.put("widgets", widgets);

        JSONObject ss = new JSONObject();
        ss.put("mode", Prefs.screensaverMode(c));
        ss.put("seconds", Prefs.screensaverSeconds(c));
        ss.put("media", Prefs.screensaverMedia(c));
        ss.put("info", Prefs.on(c, Prefs.SS_INFO));
        JSONObject themes = new JSONObject(), status = new JSONObject();
        for (String t : Prefs.THEMES) {
            themes.put(t, themeOn(c, t));
            String st = Wallpapers.status(t);
            status.put(t, st != null ? st : Wallpapers.has(c, t) ? "Downloaded" : "Not downloaded");
        }
        ss.put("themes", themes);
        ss.put("themeStatus", status);
        ss.put("bedtime", Prefs.on(c, Prefs.BEDTIME));
        ss.put("bedStart", Prefs.bedStart(c));
        ss.put("bedEnd", Prefs.bedEnd(c));
        o.put("screensaver", ss);

        o.put("store", new JSONObject().put("custom", Store.customJson(c))); // your own apps in Get apps

        JSONObject colors = new JSONObject();
        M3.Scheme s =Palette.scheme(Palette.seedOf(c, Prefs.accent(c)), Prefs.light(c));
        for (Map.Entry<String, Integer> e : colours(s).entrySet()) colors.put(e.getKey(), hex(e.getValue()));
        o.put("colors", colors);
        return o;
    }

    /** Same rule as the Settings screen: wanted and downloaded (or on its way). */
    private static boolean themeOn(Context c, String theme) {
        return Prefs.of(c).getBoolean(Prefs.SS_THEME + theme, true) && (Wallpapers.busy(theme) || Wallpapers.has(c, theme));
    }

    private static List<String> rows(String csv) {
        List<String> out = new ArrayList<>();
        for (String r : csv.split(",")) {
            String id = r.trim();
            if (indexOf(ROWS, id) >= 0 && !out.contains(id)) out.add(id);
        }
        return out;
    }

    // ---- POST /api/config -----------------------------------------------------------------------

    /** Applies the fields present in a partial config; anything invalid is skipped and named. */
    String applyConfig(JSONObject in) throws JSONException {
        List<String> skipped = new ArrayList<>();
        boolean look = false, background = false;
        synchronized (WRITE_LOCK) {
            SharedPreferences p = Prefs.of(context);
            SharedPreferences.Editor e = p.edit();

            if (in.has("rows")) {
                JSONArray a = in.optJSONArray("rows");
                if (a == null || a.length() > ROWS.length) skipped.add("rows");
                else {
                    LinkedHashSet<String> ids = new LinkedHashSet<>();
                    boolean bad = false;
                    for (int i = 0; i < a.length(); i++) {
                        String id = a.optString(i, "");
                        if (indexOf(ROWS, id) < 0) bad = true;
                        else ids.add(id);
                    }
                    if (bad) skipped.add("rows");
                    else { e.putString(Prefs.HOME_ROWS, String.join(",", ids)); look = true; }
                }
            }
            look |= putChoice(in, "tileSize", TILE_SIZES, e, Prefs.TILE_SIZE, skipped);
            look |= putChoice(in, "accent", Palette.ACCENTS, e, Prefs.ACCENT, skipped);
            look |= putChoice(in, "themeMode", THEME_MODES, e, Prefs.THEME_MODE, skipped);
            look |= putChoice(in, "textSize", TEXT_SIZES, e, Prefs.TEXT_SIZE, skipped);

            if (in.has("bg")) {
                JSONObject bg = in.optJSONObject("bg");
                if (bg == null) skipped.add("bg");
                else {
                    background |= putChoice(bg, "kind", BG_KINDS, e, Prefs.BG_KIND, skipped);
                    if (bg.has("color")) {
                        Integer col = colour(bg.opt("color"));
                        if (col == null) skipped.add("bg.color");
                        else { e.putInt(Prefs.BG_COLOR, col); background = true; }
                    }
                    if (bg.has("wallpaper")) {
                        String w = bg.optString("wallpaper", "");
                        boolean known = false;
                        for (String[] o : Backdrop.wallpaperOptions(context)) if (o[0].equals(w)) known = true;
                        if (!known) skipped.add("bg.wallpaper");
                        else { e.putString(Prefs.BG_WALLPAPER, w); background = true; }
                    }
                    background |= putInt(bg, "dim", 0, 80, e, Prefs.BG_DIM, skipped);
                    background |= putInt(bg, "blur", 0, 100, e, Prefs.BG_BLUR, skipped);
                    // slideshow
                    if (bg.has("minutes")) {
                        Object m = bg.opt("minutes");
                        int mi = m instanceof Number ? ((Number) m).intValue() : -1;
                        boolean ok = m instanceof Number && ((Number) m).doubleValue() == mi;
                        boolean known = false;
                        for (int k : Prefs.BG_SLIDE_CHOICES) if (k == mi) known = true;
                        if (!ok || !known) skipped.add("bg.minutes");
                        else e.putInt(Prefs.BG_SLIDE_MINUTES, mi); // read on Home's next resume, no rebuild needed
                    }
                    if (bg.has("themes")) {
                        JSONArray ts = bg.optJSONArray("themes");
                        List<String> chosen = new ArrayList<>();
                        boolean ok = ts != null && ts.length() > 0;
                        for (int i = 0; ok && i < ts.length(); i++) {
                            Object t = ts.opt(i);
                            if (!(t instanceof String) || indexOf(Prefs.THEMES, (String) t) < 0) ok = false;
                            else if (!chosen.contains(t)) chosen.add((String) t);
                        }
                        if (!ok) skipped.add("bg.themes");
                        else {
                            StringBuilder csv = new StringBuilder();
                            for (String t : Prefs.THEMES) if (chosen.contains(t)) csv.append(csv.length() == 0 ? "" : ",").append(t);
                            e.putString(Prefs.BG_SLIDE_THEMES, chosen.size() == Prefs.THEMES.length ? "" : csv.toString());
                            background = true;
                        }
                    }
                }
            }

            if (in.has("widgets")) {
                JSONObject w = in.optJSONObject("widgets");
                if (w == null) skipped.add("widgets");
                else for (String[] k : WIDGETS) putBool(w, k[0], e, k[1], skipped);
            }

            List<String[]> themeChanges = new ArrayList<>();
            if (in.has("screensaver")) {
                JSONObject ss = in.optJSONObject("screensaver");
                if (ss == null) skipped.add("screensaver");
                else {
                    putChoice(ss, "mode", SS_MODES, e, Prefs.SS_MODE, skipped);
                    putChoice(ss, "media", SS_MEDIA, e, Prefs.SS_MEDIA, skipped);
                    if (ss.has("seconds")) {
                        Object v = ss.opt("seconds");
                        int secs = v instanceof Number ? ((Number) v).intValue() : -1;
                        boolean ok = false;
                        for (int s : SS_SECONDS) if (s == secs && ((Number) v).doubleValue() == secs) ok = true;
                        if (ok) e.putInt(Prefs.SS_SECONDS, secs);
                        else skipped.add("screensaver.seconds");
                    }
                    putBool(ss, "info", e, Prefs.SS_INFO, skipped);
                    putBool(ss, "bedtime", e, Prefs.BEDTIME, skipped);
                    putInt(ss, "bedStart", 0, 24 * 60 - 1, e, Prefs.BED_START, skipped);
                    putInt(ss, "bedEnd", 0, 24 * 60 - 1, e, Prefs.BED_END, skipped);
                    if (ss.has("themes")) {
                        JSONObject th = ss.optJSONObject("themes");
                        if (th == null) skipped.add("screensaver.themes");
                        else for (String t : Prefs.THEMES) {
                            if (!th.has(t)) continue;
                            Object v = th.opt(t);
                            if (!(v instanceof Boolean)) { skipped.add("screensaver.themes." + t); continue; }
                            boolean want = (Boolean) v;
                            if (want != themeOn(context, t)) {
                                e.putBoolean(Prefs.SS_THEME + t, want); // the screensaver reads this, as before
                                themeChanges.add(new String[] { t, want ? "on" : "off" });
                            }
                        }
                    }
                }
            }

            if (in.has("favourites") || in.has("hidden")) {
                Apps apps = new Apps(context);
                Set<String> installed = new HashSet<>();
                for (String[] a : apps.labels()) installed.add(a[0]);
                if (in.has("favourites")) {
                    JSONArray a = in.optJSONArray("favourites");
                    if (a == null) skipped.add("favourites");
                    else {
                        LinkedHashSet<String> favs = new LinkedHashSet<>();
                        for (int i = 0; i < a.length() && favs.size() < MAX_FAVOURITES; i++) {
                            String pkg = a.optString(i, "");
                            if (installed.contains(pkg)) favs.add(pkg); // unknown packages are dropped
                        }
                        apps.setFavourites(new ArrayList<>(favs));
                        look = true;
                    }
                }
                if (in.has("hidden")) {
                    JSONArray a = in.optJSONArray("hidden");
                    if (a == null) skipped.add("hidden");
                    else {
                        Set<String> want = new HashSet<>();
                        for (int i = 0; i < a.length(); i++) {
                            String pkg = a.optString(i, "");
                            if (installed.contains(pkg)) want.add(pkg);
                        }
                        for (String pkg : apps.hidden()) if (!want.contains(pkg)) apps.setHidden(pkg, false);
                        for (String pkg : want) apps.setHidden(pkg, true);
                        look = true;
                    }
                }
            }

            if (in.has("store")) {
                JSONObject st = in.optJSONObject("store");
                JSONArray custom = st == null ? null : st.optJSONArray("custom");
                if (custom == null) skipped.add("store");
                else {
                    String err = Store.setCustom(context, custom);
                    if (err != null) skipped.add("your apps (" + err + ")");
                    else look = true; // Home reloads its Get apps row
                }
            }

            e.commit();
            for (String[] t : themeChanges) {
                if (t[1].equals("on")) Wallpapers.download(context, t[0]);
                else Wallpapers.remove(context, t[0]);
            }
            if (background) Backdrop.changed(context); // bumps the look too
            else if (look) Prefs.bumpLook(context);
        }
        if (skipped.isEmpty()) return "Saved.";
        return "Saved, but skipped: " + String.join(", ", skipped) + ".";
    }

    private static boolean putChoice(JSONObject o, String name, String[] allowed, SharedPreferences.Editor e,
                                     String key, List<String> skipped) {
        if (!o.has(name)) return false;
        Object v = o.opt(name);
        if (!(v instanceof String) || indexOf(allowed, (String) v) < 0) { skipped.add(name); return false; }
        e.putString(key, (String) v);
        return true;
    }

    private static boolean putInt(JSONObject o, String name, int min, int max, SharedPreferences.Editor e,
                                  String key, List<String> skipped) {
        if (!o.has(name)) return false;
        Object v = o.opt(name);
        if (!(v instanceof Number)) { skipped.add(name); return false; }
        double d = ((Number) v).doubleValue();
        if (Double.isNaN(d) || d < min || d > max) { skipped.add(name); return false; }
        e.putInt(key, (int) Math.round(d));
        return true;
    }

    private static void putBool(JSONObject o, String name, SharedPreferences.Editor e, String key, List<String> skipped) {
        if (!o.has(name)) return;
        Object v = o.opt(name);
        if (!(v instanceof Boolean)) { skipped.add(name); return; }
        e.putBoolean(key, (Boolean) v);
    }

    /** "#RRGGBB" -> opaque ARGB, else null. */
    static Integer colour(Object v) {
        if (!(v instanceof String)) return null;
        String s = (String) v;
        if (!s.matches("#[0-9a-fA-F]{6}")) return null;
        return 0xFF000000 | Integer.parseInt(s.substring(1), 16);
    }

    private static int indexOf(String[] a, String v) {
        for (int i = 0; i < a.length; i++) if (a[i].equals(v)) return i;
        return -1;
    }
}
