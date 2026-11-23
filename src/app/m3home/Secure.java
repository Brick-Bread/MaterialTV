package app.m3home;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;

/**
 * System settings M3 Home may change once install.sh has granted WRITE_SECURE_SETTINGS
 * (adb shell pm grant app.m3home android.permission.WRITE_SECURE_SETTINGS).
 * Without the grant every method here is a harmless no-op that reports false.
 */
final class Secure {
    private Secure() {}

    static final String GUARD = "app.m3home/app.m3home.HomeGuard";
    static final String DREAM = "app.m3home/app.m3home.Screensaver";

    static boolean canWrite(Context c) {
        return c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED;
    }

    /** Android can drop the Home-button service after an update; put it back if we're allowed. */
    static void ensureGuard(Context c) {
        if (!canWrite(c)) return;
        ContentResolver cr = c.getContentResolver();
        try {
            String cur = Settings.Secure.getString(cr, "enabled_accessibility_services");
            if (cur == null || !cur.contains(GUARD)) {
                Settings.Secure.putString(cr, "enabled_accessibility_services",
                        cur == null || cur.isEmpty() ? GUARD : cur + ":" + GUARD);
                Settings.Secure.putInt(cr, "accessibility_enabled", 1);
            }
        } catch (SecurityException ignored) { }
    }

    static boolean isScreensaver(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), "screensaver_components");
        return s != null && s.contains(DREAM);
    }

    /** Makes M3 Home the Fire TV screensaver, or puts the previous one back. */
    static boolean setScreensaver(Context c, boolean on) {
        if (!canWrite(c)) return false;
        ContentResolver cr = c.getContentResolver();
        try {
            String cur = Settings.Secure.getString(cr, "screensaver_components");
            if (on) {
                if (cur != null && !cur.contains(DREAM)) {
                    Prefs.of(c).edit().putString(Prefs.PREV_SCREENSAVER, cur).apply();
                }
                Settings.Secure.putString(cr, "screensaver_components", DREAM);
                Settings.Secure.putInt(cr, "screensaver_enabled", 1);
            } else {
                String prev = Prefs.of(c).getString(Prefs.PREV_SCREENSAVER, null);
                if (prev != null && !prev.isEmpty()) Settings.Secure.putString(cr, "screensaver_components", prev);
                else Settings.Secure.putString(cr, "screensaver_components", "");
            }
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }
}
