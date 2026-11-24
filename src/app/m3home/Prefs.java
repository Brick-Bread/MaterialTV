package app.m3home;

import android.content.Context;
import android.content.SharedPreferences;

/** MaterialTV's own settings (display filter, screensaver). Same file as the favourites. */
final class Prefs {
    private Prefs() {}

    static final String BRIGHTNESS = "brightness";        // 20..100 (%); 100 = no dimming
    static final String WARM = "blue_light_filter";       // on/off
    static final String WARM_STRENGTH = "blue_light_strength"; // 10..100 (%)
    static final String SS_MODE = "screensaver_mode";     // rotate | clock | shapes | slideshow
    static final String PREV_SCREENSAVER = "previous_screensaver";
    static final String HIDE_IPS = "hide_ips";            // mask addresses on screen
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

    // ---- 1.9.0 customisation (shared contract: every package reads these, only Prefs defines them) ----
    // Home layout
    static final String HOME_ROWS = "home_rows";       // CSV in display order; a missing id = hidden. ids: favourites,recents,apps,store
    static final String TILE_SIZE = "tile_size";       // compact | normal | large
    // Theme
    static final String ACCENT = "accent";             // purple|blue|teal|green|yellow|orange|red|pink|background|custom
    static final String THEME_MODE = "theme_mode";     // dark | light
    static final String TEXT_SIZE = "text_size";       // small | normal | large
    // Home background
    static final String BG_KIND = "bg_kind";           // gradient | solid | wallpaper | custom
    static final String BG_COLOR = "bg_color";         // int ARGB
    static final String BG_WALLPAPER = "bg_wallpaper"; // "<theme>/<filename>" in the screensaver folder
    static final String BG_DIM = "bg_dim";             // 0..80 (%)
    static final String BG_BLUR = "bg_blur";           // 0..100
    static final String BG_SEED = "bg_seed";           // int colour picked from the background (for accent=background)
    static final String LOOK_REV = "look_rev";         // bumped on any look/layout change; screens recreate when it moves

    // App store (1.10.0)
    static final String STORE_CUSTOM = "store_custom"; // JSON array of your own apps: {id, name, repo | url}
    private static final String STORE_ROW_ADDED = "store_row_added"; // the "Get apps" row was added once

    // Plugins (see Plugins.java and docs/plugins.md)
    static final String PLUGINS = "plugins";                   // JSON array of your web plugins: {id, url}
    static final String PLUGIN_ON = "plugin_on:";              // + plugin id -> on/off (web: on when added; Android: off until switched on)
    static final String PLUGIN_ROWS_SEEN = "plugin_rows_seen"; // CSV of plugin row ids already added to Home once
    static final String ACCENT_SEED = "accent_seed";           // int colour for ACCENT "custom" (set by a theme plugin)

    /**
     * The shown rows. Ids: favourites,recents,apps,store, and plugin rows "plugin:<plugin>/<row>".
     * 1.10.0 adds "store" once to a saved list.
     */
    static String homeRows(Context c) {
        SharedPreferences p = of(c);
        if (!p.getBoolean(STORE_ROW_ADDED, false)) {
            SharedPreferences.Editor e = p.edit().putBoolean(STORE_ROW_ADDED, true);
            String saved = p.getString(HOME_ROWS, null);
            if (saved != null && !saved.trim().isEmpty() && !("," + saved + ",").contains(",store,")) {
                e.putString(HOME_ROWS, saved + ",store");
            }
            e.commit();
        }
        return p.getString(HOME_ROWS, "favourites,recents,apps,store");
    }
    static String tileSize(Context c) { return of(c).getString(TILE_SIZE, "normal"); }
    static String accent(Context c) { return of(c).getString(ACCENT, "purple"); }
    static boolean light(Context c) { return "light".equals(of(c).getString(THEME_MODE, "dark")); }
    static float textScale(Context c) {
        String s = of(c).getString(TEXT_SIZE, "normal");
        return s.equals("small") ? 0.9f : s.equals("large") ? 1.15f : 1f;
    }
    static String bgKind(Context c) { return of(c).getString(BG_KIND, "gradient"); }
    static int bgDim(Context c) { return of(c).getInt(BG_DIM, 40); }
    static int bgBlur(Context c) { return of(c).getInt(BG_BLUR, 30); }
    static int lookRev(Context c) { return of(c).getInt(LOOK_REV, 0); }
    static void bumpLook(Context c) { of(c).edit().putInt(LOOK_REV, lookRev(c) + 1).apply(); }

    static int brightness(Context c) { return of(c).getInt(BRIGHTNESS, 100); }
    static boolean warm(Context c) { return of(c).getBoolean(WARM, false); }
    static int warmStrength(Context c) { return of(c).getInt(WARM_STRENGTH, 40); }
    static String screensaverMode(Context c) { return of(c).getString(SS_MODE, "rotate"); }
}
