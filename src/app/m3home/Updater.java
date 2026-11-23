package app.m3home;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

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
import java.util.Locale;

/**
 * Updates from the project's public GitHub Releases. Once a day it asks GitHub for the latest
 * release; if it's newer, Home shows an "Update available" card. Installing downloads the APK,
 * checks its SHA-256 against the release's .sha256 file, and hands it to Android's installer,
 * which asks you to confirm. Android only accepts it if it's signed with the same key as this app.
 */
final class Updater {
    private Updater() {}

    static final String REPO = "Brick-Bread/m3-firestick-launcher";
    private static final String LATEST = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final long DAY_MS = 24 * 3600_000L;
    static final String AUTO = "update_auto";
    private static final String LAST_CHECK = "update_last_check", NEW_VERSION = "update_version",
            APK_URL = "update_apk", SHA_URL = "update_sha";

    interface Done { void on(String message); }

    static String installedVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    /** The newer version waiting to be installed, or null. */
    static String available(Context c) {
        String v = Prefs.of(c).getString(NEW_VERSION, null);
        return v != null && newer(v, installedVersion(c)) ? v : null;
    }

    /** Daily background check (when automatic checks are on). */
    static void checkIfDue(Context c, Runnable foundNew) {
        if (!Prefs.on(c, AUTO)) return;
        if (System.currentTimeMillis() - Prefs.of(c).getLong(LAST_CHECK, 0) < DAY_MS) return;
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            if (check(app) != null && available(app) != null && foundNew != null) foundNew.run();
        }, "m3home-update-check").start();
    }

    /** Asks GitHub now. Returns a short status line. Call off the main thread. */
    static String check(Context c) {
        String body = http(LATEST);
        Prefs.of(c).edit().putLong(LAST_CHECK, System.currentTimeMillis()).apply();
        if (body == null) return "Couldn't reach GitHub. Try again later.";
        try {
            JSONObject rel = new JSONObject(body);
            String version = rel.getString("tag_name").replaceFirst("^[vV]", "");
            String apk = null, sha = null;
            JSONArray assets = rel.optJSONArray("assets");
            for (int i = 0; assets != null && i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                String name = a.optString("name");
                if (name.equals("m3home.apk")) apk = a.optString("browser_download_url");
                if (name.equals("m3home.apk.sha256")) sha = a.optString("browser_download_url");
            }
            if (apk == null || sha == null) return "The latest release has no installable APK.";
            Prefs.of(c).edit().putString(NEW_VERSION, version).putString(APK_URL, apk).putString(SHA_URL, sha).apply();
            return newer(version, installedVersion(c)) ? "Version " + version + " is available" : "You're up to date (" + installedVersion(c) + ")";
        } catch (Exception e) {
            return "GitHub sent something unexpected. Try again later.";
        }
    }

    /** Downloads, verifies and opens Android's install prompt. Call off the main thread. */
    static void install(Context c, Done done) {
        String apkUrl = Prefs.of(c).getString(APK_URL, null), shaUrl = Prefs.of(c).getString(SHA_URL, null);
        if (apkUrl == null || shaUrl == null) { done.on("Check for updates first."); return; }
        String shaFile = http(shaUrl);
        String expected = shaFile == null ? null : shaFile.trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
        if (expected == null || expected.length() != 64) { done.on("Couldn't get the update's checksum."); return; }

        File dir = new File(c.getCacheDir(), "update");
        dir.mkdirs();
        File apk = new File(dir, "m3home.apk");
        if (!download(apkUrl, apk)) { done.on("The download failed. Try again later."); return; }
        if (!expected.equals(sha256(apk))) {
            apk.delete();
            done.on("The download didn't match its checksum, so it wasn't installed.");
            return;
        }
        try {
            PackageInstaller pi = c.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(c.getPackageName());
            int id = pi.createSession(params);
            try (PackageInstaller.Session s = pi.openSession(id)) {
                try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("m3home.apk", 0, apk.length())) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    s.fsync(out);
                }
                Intent cb = new Intent(c, Result.class);
                PendingIntent pending = PendingIntent.getBroadcast(c, id, cb, PendingIntent.FLAG_UPDATE_CURRENT);
                s.commit(pending.getIntentSender());
            }
            done.on("Confirm the update on screen.");
        } catch (Exception e) {
            done.on("Android refused the update: " + e.getMessage());
        }
    }

    /** Android's answer: open its confirmation screen, or report the result. */
    public static class Result extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                android.widget.Toast.makeText(c, "Update not installed" + (msg == null ? "" : ": " + msg),
                        android.widget.Toast.LENGTH_LONG).show();
            }
        }
    }

    /** After an update: turn the Home-button service back on and return to M3 Home. */
    public static class Replaced extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            Secure.ensureGuard(c);
            c.startActivity(new Intent(c, Home.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    // ---- helpers ------------------------------------------------------------------------

    static boolean newer(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    private static int num(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9]", "")); } catch (NumberFormatException e) { return 0; }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(10_000);
        con.setReadTimeout(30_000);
        con.setInstanceFollowRedirects(true);
        con.setRequestProperty("User-Agent", "M3Home-Updater");
        con.setRequestProperty("Accept", "application/vnd.github+json");
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

    private static boolean download(String url, File to) {
        HttpURLConnection con = null;
        try {
            con = open(url);
            if (con.getResponseCode() != 200) return false;
            try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(to)) {
                byte[] buf = new byte[65536];
                int n;
                long total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > 64L * 1024 * 1024) return false; // nothing this app ships is that big
                    out.write(buf, 0, n);
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (con != null) con.disconnect();
        }
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
