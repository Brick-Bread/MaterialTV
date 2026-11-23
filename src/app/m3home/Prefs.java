package app.m3home;

import android.content.Context;
import android.content.SharedPreferences;

/** M3 Home's own settings (display filter, screensaver). Same file as the favourites. */
final class Prefs {
    private Prefs() {}

    static final String BRIGHTNESS = "brightness";        // 20..100 (%); 100 = no dimming
    static final String WARM = "blue_light_filter";       // on/off
    static final String WARM_STRENGTH = "blue_light_strength"; // 10..100 (%)
    static final String SS_MODE = "screensaver_mode";     // rotate | clock | shapes | slideshow
    static final String PREV_SCREENSAVER = "previous_screensaver";
    static final String SLEEP_AT = "sleep_at";            // wall-clock ms; 0 = timer off
    static final String SLEEP_MINUTES = "sleep_minutes";  // the length last chosen

    // Screensaver
    static final String SS_SECONDS = "ss_seconds";        // how long each picture shows
    static final String SS_MEDIA = "ss_media";            // both | stills | live
    static final String SS_THEME = "ss_theme_";           // + abstract|nature|space|city -> on/off
    static final String SS_INFO = "ss_info";              // clock + widgets card on/off
    static final String[] THEMES = { "abstract", "nature", "space", "city" };

    // Widgets
    static final String W_WEATHER = "w_weather", W_DEADLINES = "w_deadlines", W_COUNTDOWN = "w_countdown",
            W_NOWPLAYING = "w_nowplaying", W_TAILSCALE = "w_tailscale";
    static final String WX_PLACE = "wx_place", WX_LAT = "wx_lat", WX_LON = "wx_lon";

    static boolean on(Context c, String key) { return of(c).getBoolean(key, true); }
    static int screensaverSeconds(Context c) { return of(c).getInt(SS_SECONDS, 15); }
    static String screensaverMedia(Context c) { return of(c).getString(SS_MEDIA, "both"); }
    /** The weather town, or null until it's set on the companion page (no built-in default). */
    static String weatherPlace(Context c) { return of(c).getString(WX_PLACE, null); }
    static double weatherLat(Context c) { return Double.parseDouble(of(c).getString(WX_LAT, "0")); }
    static double weatherLon(Context c) { return Double.parseDouble(of(c).getString(WX_LON, "0")); }

    // Bedtime: the screensaver becomes the sleep animation between these times (minutes after midnight).
    static final String BEDTIME = "bedtime", BED_START = "bedtime_start", BED_END = "bedtime_end";

    static int bedStart(Context c) { return of(c).getInt(BED_START, 23 * 60); }
    static int bedEnd(Context c) { return of(c).getInt(BED_END, 7 * 60); }

    static boolean isBedtime(Context c) {
        if (!on(c, BEDTIME)) return false;
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int now = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE);
        int s = bedStart(c), e = bedEnd(c);
        return s == e ? false : s < e ? now >= s && now < e : now >= s || now < e; // spans midnight
    }

    static String hhmm(int minutes) {
        return String.format(java.util.Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
    }

    static long sleepAt(Context c) {
        long at = of(c).getLong(SLEEP_AT, 0);
        return at > System.currentTimeMillis() ? at : 0;
    }

    static SharedPreferences of(Context c) {
        return c.getApplicationContext().getSharedPreferences("m3home", Context.MODE_PRIVATE);
    }

    static int brightness(Context c) { return of(c).getInt(BRIGHTNESS, 100); }
    static boolean warm(Context c) { return of(c).getBoolean(WARM, false); }
    static int warmStrength(Context c) { return of(c).getInt(WARM_STRENGTH, 40); }
    static String screensaverMode(Context c) { return of(c).getString(SS_MODE, "rotate"); }
}
