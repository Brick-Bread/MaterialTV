package app.m3home;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Fire TV setup and its uninstaller, run on the TV through Adb. Every change is written to a
 * change log first (which apps it switched off, each setting's old value, what it installed), so
 * Undo can put back exactly what was there. The debloat lists come from dumbtv (MIT, assets/setup).
 */
final class Setup {
    private Setup() {}

    interface Log { void line(String s); }

    static final class Choices {
        boolean permissions = true, debloat = true, keepAlexa = true, smartTube, tailscale;
    }

    static final class UndoChoices {
        boolean debloat = true, smartTube = true, tailscale = true, m3home;
    }

    /**
     * The change log lives on the TV (in MaterialTV's own folder), so setup and undo can be run from
     * the TV, a phone or a computer script and they all see the same record. Same format everywhere.
     */
    static final String LEDGER_DIR = "/sdcard/Android/data/app.m3home/files/setup";
    static final String LEDGER_PATH = LEDGER_DIR + "/ledger.json";
    private static final String SELF = "app.m3home";
    private static final String GUARD = Secure.GUARD, DREAM = Secure.DREAM;
    private static final String LISTENER = "app.m3home/app.m3home.MediaListener";
    private static final String TAILSCALE = "com.tailscale.ipn", YOUTUBE = "com.amazon.firetv.youtube";
    private static final String[] SMARTTUBE = { "org.smarttube.stable", "com.teamsmart.videomanager.tv" };
    // Pinned like dumbtv: this exact build, checked against its SHA-256 before installing.
    private static final String SMARTTUBE_URL =
            "https://github.com/yuliskov/SmartTube/releases/download/32.56s/SmartTube_stable_32.56_universal.apk";
    private static final String SMARTTUBE_SHA = "4a35b90940612e048e4e9cb9e10379f7db1e84f30375994129f10a571ee04ce5";

    /** On the TV itself: is there a change log from an earlier setup? */
    static boolean hasLedger(Context c) {
        return ledgerFile(c).length() > 2;
    }

    static File ledgerFile(Context c) {
        File d = c.getExternalFilesDir("setup");
        return new File(d == null ? c.getFilesDir() : d, "ledger.json");
    }

    // ---- setup ------------------------------------------------------------------------------

    static void run(Context c, Adb adb, Choices ch, Log log) throws IOException {
        JSONObject led = ledger(adb);
        try {
            stayAwake(adb, led, log);
            if (ch.permissions) permissions(c, adb, led, log);
            if (ch.debloat) debloat(c, adb, led, ch.keepAlexa, log);
            if (ch.smartTube) smartTube(c, adb, led, log);
            if (ch.tailscale) tailscale(c, adb, led, log);
            log.line("Setup finished.");
        } finally {
            save(adb, led);
        }
    }

    private static void permissions(Context c, Adb adb, JSONObject led, Log log) throws IOException {
        log.line("MaterialTV permissions…");
        String a11y = clean(adb.shell("settings get secure enabled_accessibility_services"));
        remember(led, "a11y_before", a11y);
        if (!a11y.contains(GUARD)) {
            adb.shell("settings put secure enabled_accessibility_services " + (a11y.isEmpty() ? GUARD : a11y + ":" + GUARD));
        }
        adb.shell("settings put secure accessibility_enabled 1");
        adb.shell("pm grant " + SELF + " android.permission.WRITE_SECURE_SETTINGS");
        adb.shell("cmd notification allow_listener " + LISTENER);
        adb.shell("appops set " + SELF + " REQUEST_INSTALL_PACKAGES allow");
        adb.shell("dumpsys deviceidle whitelist +" + SELF);
        String ss = clean(adb.shell("settings get secure screensaver_components"));
        remember(led, "screensaver_before", ss);
        adb.shell("settings put secure screensaver_components " + DREAM);
        adb.shell("settings put secure screensaver_enabled 1");
        put(led, "permissions", true);
        log.line("  Home button, screensaver, now playing and updates are set up.");
    }

    private static void debloat(Context c, Adb adb, JSONObject led, boolean keepAlexa, Log log) throws IOException {
        log.line("Debloat and privacy (dumbtv lists)…");
        Set<String> installed = packages(adb, "pm list packages"), disabled = packages(adb, "pm list packages -d");
        Set<String> never = new HashSet<>(list(c, "never-disable.txt"));
        List<String> targets = new ArrayList<>(list(c, "packages.txt"));
        if (!keepAlexa) targets.addAll(list(c, "alexa.txt"));
        JSONArray off = arr(led, "disabled");
        int count = 0;
        for (String p : targets) {
            if (never.contains(p) || !installed.contains(p) || disabled.contains(p)) continue;
            String r = adb.shell("pm disable-user --user 0 " + p);
            if (r.contains("disabled")) { off.put(p); count++; }
        }
        log.line("  Switched off " + count + " Amazon ad and tracking apps.");

        String bg = clean(adb.shell("cmd netpolicy get restrict-background"));
        remember(led, "data_saver_before", bg.contains("enabled") ? "true" : "false");
        adb.shell("cmd netpolicy set restrict-background true");
        JSONArray uids = arr(led, "blacklisted_uids"), quiet = arr(led, "background_off");
        for (String p : list(c, "protected.txt")) {
            if (!installed.contains(p)) continue;
            adb.shell("appops set " + p + " RUN_IN_BACKGROUND ignore; appops set " + p + " RUN_ANY_IN_BACKGROUND ignore; am force-stop " + p);
            quiet.put(p);
            String uid = uidOf(adb, p);
            if (uid != null && Integer.parseInt(uid) >= 10000) {
                adb.shell("cmd netpolicy add restrict-background-blacklist " + uid);
                uids.put(uid);
            }
        }
        log.line("  Limited " + quiet.length() + " apps Amazon won't let you switch off.");

        JSONObject before = obj(led, "settings_before");
        int n = 0;
        for (String line : list(c, "settings.txt")) {
            String[] f = line.split("\\s+");
            if (f.length < 3) continue;
            String key = f[0] + " " + f[1];
            if (!before.has(key)) remember(before, key, clean(adb.shell("settings get " + key)));
            adb.shell("settings put " + key + " " + f[2]);
            n++;
        }
        log.line("  Applied " + n + " privacy settings.");
    }

    private static void smartTube(Context c, Adb adb, JSONObject led, Log log) throws IOException {
        log.line("SmartTube…");
        Set<String> installed = packages(adb, "pm list packages");
        boolean had = false;
        for (String p : SMARTTUBE) had |= installed.contains(p);
        if (!had) {
            File apk = download(c, SMARTTUBE_URL, "smarttube.apk", log);
            if (!SMARTTUBE_SHA.equals(sha256(apk))) { apk.delete(); throw new IOException("SmartTube download didn't match its checksum"); }
            String r = adb.shell("pm install -r '" + apk.getAbsolutePath() + "'");
            apk.delete();
            if (!r.contains("Success")) throw new IOException("SmartTube didn't install: " + r);
            arr(led, "installed").put(SMARTTUBE[0]);
            log.line("  Installed SmartTube.");
        } else {
            log.line("  SmartTube was already installed.");
        }
        if (installed.contains(YOUTUBE) && !packages(adb, "pm list packages -d").contains(YOUTUBE)) {
            adb.shell("pm disable-user --user 0 " + YOUTUBE);
            arr(led, "disabled").put(YOUTUBE);
            log.line("  Switched off the Amazon YouTube app.");
        }
    }

    private static void tailscale(Context c, Adb adb, JSONObject led, Log log) throws IOException {
        log.line("Tailscale…");
        if (!packages(adb, "pm list packages").contains(TAILSCALE)) {
            String page = text("https://pkgs.tailscale.com/stable/");
            Matcher m = Pattern.compile("tailscale-android-universal-[0-9.]+\\.apk").matcher(page == null ? "" : page);
            if (!m.find()) throw new IOException("couldn't find Tailscale's Android download");
            String url = "https://pkgs.tailscale.com/stable/" + m.group();
            String sha = text(url + ".sha256");
            File apk = download(c, url, "tailscale.apk", log);
            String want = sha == null ? "" : sha.trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
            if (want.length() != 64 || !want.equals(sha256(apk))) { apk.delete(); throw new IOException("Tailscale download didn't match its checksum"); }
            String r = adb.shell("pm install -r '" + apk.getAbsolutePath() + "'");
            apk.delete();
            if (!r.contains("Success")) throw new IOException("Tailscale didn't install: " + r);
            arr(led, "installed").put(TAILSCALE);
            log.line("  Installed " + m.group().replace(".apk", "") + ".");
        } else {
            log.line("  Tailscale was already installed.");
        }
        remember(led, "vpn_before", clean(adb.shell("settings get secure always_on_vpn_app")));
        adb.shell("appops set " + TAILSCALE + " ACTIVATE_VPN allow; settings put secure always_on_vpn_app " + TAILSCALE
                + "; dumpsys deviceidle whitelist +" + TAILSCALE);
        log.line("  Tailscale is the always-on VPN. Open Tailscale once to sign in.");
    }

    // ---- uninstall ----------------------------------------------------------------------------

    /** Puts things back. Uses the change log when there is one, otherwise dumbtv's restore lists. */
    static void undo(Context c, Adb adb, UndoChoices ch, Log log) throws IOException {
        JSONObject led = ledger(adb);
        boolean logged = led.length() > 0;
        if (ch.debloat) {
            log.line("Undoing debloat and privacy…");
            Set<String> disabled = packages(adb, "pm list packages -d");
            List<String> toEnable = new ArrayList<>();
            JSONArray off = arr(led, "disabled");
            for (int i = 0; i < off.length(); i++) toEnable.add(off.optString(i));
            if (!logged) {
                toEnable.addAll(list(c, "packages.txt"));
                toEnable.addAll(list(c, "alexa.txt"));
            }
            int n = 0;
            for (String p : toEnable) {
                if (p.equals(YOUTUBE) && !ch.smartTube) continue;
                if (disabled.contains(p)) { adb.shell("pm enable --user 0 " + p); n++; }
            }
            log.line("  Switched " + n + " apps back on.");
            for (String p : list(c, "protected.txt")) {
                adb.shell("appops set " + p + " RUN_IN_BACKGROUND allow; appops set " + p + " RUN_ANY_IN_BACKGROUND allow");
                String uid = uidOf(adb, p);
                if (uid != null) adb.shell("cmd netpolicy remove restrict-background-blacklist " + uid);
            }
            adb.shell("cmd netpolicy set restrict-background " + ("true".equals(led.optString("data_saver_before")) ? "true" : "false"));
            JSONObject before = led.optJSONObject("settings_before");
            int s = 0;
            if (before != null && before.length() > 0) {
                for (java.util.Iterator<String> it = before.keys(); it.hasNext(); ) {
                    String key = it.next();
                    restore(adb, key, before.optString(key, ""));
                    s++;
                }
            } else {
                for (String line : list(c, "settings.restore.txt")) {
                    String[] f = line.split("\\s+");
                    if (f.length >= 3) { adb.shell("settings put " + f[0] + " " + f[1] + " " + f[2]); s++; }
                }
            }
            log.line("  Restored " + s + " settings and background limits.");
        }
        if (ch.smartTube) {
            for (String p : SMARTTUBE) {
                if (packages(adb, "pm list packages").contains(p) && (!logged || contains(arr(led, "installed"), p))) {
                    adb.shell("pm uninstall " + p);
                    log.line("Removed SmartTube.");
                }
            }
            adb.shell("pm enable --user 0 " + YOUTUBE);
        }
        if (ch.tailscale) {
            restore(adb, "secure always_on_vpn_app", led.optString("vpn_before", ""));
            if (packages(adb, "pm list packages").contains(TAILSCALE) && (!logged || contains(arr(led, "installed"), TAILSCALE))) {
                log.line("Removed Tailscale's always-on VPN; uninstalling Tailscale…");
                adb.shell("pm uninstall " + TAILSCALE);
            } else {
                log.line("Tailscale is no longer the always-on VPN.");
            }
        }
        if (ch.m3home) {
            log.line("Removing MaterialTV and its permissions…");
            String ss = led.optString("screensaver_before", "");
            if (ss.isEmpty() || ss.contains(SELF)) ss = clean(adb.shell("settings get secure screensaver_default_component"));
            restore(adb, "secure screensaver_components", ss);
            String a11y = clean(adb.shell("settings get secure enabled_accessibility_services"));
            String rest = a11y.replace(":" + GUARD, "").replace(GUARD + ":", "").replace(GUARD, "");
            restore(adb, "secure enabled_accessibility_services", rest);
            if (rest.isEmpty()) adb.shell("settings put secure accessibility_enabled 0");
            adb.shell("cmd notification disallow_listener " + LISTENER + "; dumpsys deviceidle whitelist -" + SELF);

            log.line("Uninstalling MaterialTV. Amazon's home screen comes back. Goodbye!");
            adb.shell("pm uninstall " + SELF); // the last thing it does: this ends the app
            return;
        }
        if (led.has("stay_on_before")) adb.shell("settings put global stay_on_while_plugged_in " + led.optString("stay_on_before", "0"));
        adb.shell("rm -f " + LEDGER_PATH);
        log.line("Undo finished.");
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static void restore(Adb adb, String nsKey, String value) throws IOException {
        if (value == null || value.isEmpty() || value.equals("null")) adb.shell("settings delete " + nsKey);
        else adb.shell("settings put " + nsKey + " '" + value.replace("'", "") + "'");
    }

    private static String uidOf(Adb adb, String pkg) throws IOException {
        Matcher m = Pattern.compile("(userId|appId)=(\\d+)").matcher(adb.shell("dumpsys package " + pkg + " | grep -m1 -E 'userId=|appId='"));
        return m.find() ? m.group(2) : null;
    }

    private static Set<String> packages(Adb adb, String cmd) throws IOException {
        Set<String> out = new HashSet<>();
        for (String l : adb.shell(cmd).split("\n")) {
            l = l.trim();
            if (l.startsWith("package:")) out.add(l.substring(8));
        }
        return out;
    }

    /** A dumbtv list from assets/setup: one package (or setting) per line, # comments ignored. */
    static List<String> list(Context c, String name) {
        List<String> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getAssets().open("setup/" + name), "UTF-8"))) {
            String l;
            while ((l = r.readLine()) != null) {
                int hash = l.indexOf('#');
                if (hash >= 0) l = l.substring(0, hash);
                l = l.trim();
                if (l.isEmpty()) continue;
                out.add(name.startsWith("settings") ? l : l.split("\\s+")[0]);
            }
        } catch (IOException ignored) { }
        return out;
    }

    private static String clean(String s) {
        s = s == null ? "" : s.trim();
        return s.equals("null") ? "" : s;
    }

    /** Reads the change log from the TV (empty if there isn't one). */
    private static JSONObject ledger(Adb adb) throws IOException {
        String s = adb.shell("cat " + LEDGER_PATH + " 2>/dev/null");
        try {
            return s.trim().startsWith("{") ? new JSONObject(s.trim()) : new JSONObject();
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** Writes the change log to the TV. Base64 keeps the shell from touching the JSON. */
    private static void save(Adb adb, JSONObject led) throws IOException {
        String b64 = android.util.Base64.encodeToString(led.toString().getBytes("UTF-8"), android.util.Base64.NO_WRAP);
        adb.shell("mkdir -p " + LEDGER_DIR + " && echo " + b64 + " | base64 -d > " + LEDGER_PATH);
    }

    /** "Stay awake while plugged in" stops the screensaver ever starting; switch it off (and remember). */
    private static void stayAwake(Adb adb, JSONObject led, Log log) throws IOException {
        String v = clean(adb.shell("settings get global stay_on_while_plugged_in"));
        if (!v.isEmpty() && !v.equals("0")) {
            remember(led, "stay_on_before", v);
            adb.shell("settings put global stay_on_while_plugged_in 0");
            log.line("  Turned off “stay awake” so the screensaver can start.");
        }
    }

    /** Keeps the first "before" value only, so re-running setup can't overwrite the original. */
    private static void remember(JSONObject o, String key, String value) {
        if (!o.has(key)) put(o, key, value == null ? "" : value);
    }

    private static void put(JSONObject o, String k, Object v) {
        try { o.put(k, v); } catch (Exception ignored) { }
    }

    private static JSONArray arr(JSONObject o, String k) {
        JSONArray a = o.optJSONArray(k);
        if (a == null) { a = new JSONArray(); put(o, k, a); }
        return a;
    }

    private static JSONObject obj(JSONObject o, String k) {
        JSONObject x = o.optJSONObject(k);
        if (x == null) { x = new JSONObject(); put(o, k, x); }
        return x;
    }

    private static boolean contains(JSONArray a, String v) {
        for (int i = 0; i < a.length(); i++) if (v.equals(a.optString(i))) return true;
        return false;
    }

    private static String text(String url) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(10_000);
            con.setReadTimeout(20_000);
            con.setRequestProperty("User-Agent", "M3Home-Setup");
            if (con.getResponseCode() != 200) return null;
            try (InputStream in = con.getInputStream()) {
                java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0 && o.size() < 2_000_000) o.write(b, 0, n);
                return o.toString("UTF-8");
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    /** Downloads into the app's own folder, which adb's shell can read for `pm install`. */
    private static File download(Context c, String url, String name, Log log) throws IOException {
        File dir = c.getExternalFilesDir("setup");
        if (dir == null) throw new IOException("no storage for the download");
        dir.mkdirs();
        File f = new File(dir, name);
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(10_000);
        con.setReadTimeout(60_000);
        con.setInstanceFollowRedirects(true);
        con.setRequestProperty("User-Agent", "M3Home-Setup");
        try {
            if (con.getResponseCode() != 200) throw new IOException("download failed (" + con.getResponseCode() + ")");
            long total = con.getContentLengthLong(), got = 0, lastShown = 0;
            try (InputStream in = con.getInputStream(); OutputStream o = new FileOutputStream(f)) {
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) > 0) {
                    o.write(b, 0, n);
                    got += n;
                    if (got > 200L * 1024 * 1024) throw new IOException("download too large");
                    if (total > 0 && got - lastShown > total / 4) {
                        lastShown = got;
                        log.line("  Downloading… " + (got * 100 / total) + "%");
                    }
                }
            }
        } finally {
            con.disconnect();
        }
        return f;
    }

    private static String sha256(File f) {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) md.update(b, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest()) sb.append(String.format("%02x", x & 0xFF));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
