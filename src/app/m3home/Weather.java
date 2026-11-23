package app.m3home;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;

/**
 * Weather from Open-Meteo (free, no account or key). Only the chosen place's coordinates are sent.
 * The last answer is cached, so the screensaver never waits on the network.
 */
final class Weather {
    private Weather() {}

    private static final String CACHE = "wx_cache", CACHE_TIME = "wx_cache_time";
    private static final long MAX_AGE_MS = 30 * 60_000;

    static final class Now {
        int temp, high, low, rainChance, code;
        boolean day;
    }

    /** Fetches a fresh forecast in the background if the cached one is older than 30 minutes. */
    static void refreshIfStale(final Context c, final Runnable done) {
        if (Prefs.weatherPlace(c) == null) return; // no town chosen yet
        long age = System.currentTimeMillis() - Prefs.of(c).getLong(CACHE_TIME, 0);
        if (age < MAX_AGE_MS) return;
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            String url = String.format(Locale.ROOT,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                            + "&current=temperature_2m,weather_code,is_day"
                            + "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                            + "&timezone=auto&forecast_days=1",
                    Prefs.weatherLat(app), Prefs.weatherLon(app));
            String body = get(url);
            if (body != null && body.contains("\"current\"")) {
                Prefs.of(app).edit().putString(CACHE, body).putLong(CACHE_TIME, System.currentTimeMillis()).apply();
                if (done != null) done.run();
            }
        }, "m3home-weather").start();
    }

    /** The cached forecast, or null if there isn't one yet (or it's over 6 hours old). */
    static Now current(Context c) {
        try {
            if (System.currentTimeMillis() - Prefs.of(c).getLong(CACHE_TIME, 0) > 6 * 3600_000L) return null;
            JSONObject o = new JSONObject(Prefs.of(c).getString(CACHE, ""));
            JSONObject cur = o.getJSONObject("current");
            JSONObject daily = o.getJSONObject("daily");
            Now n = new Now();
            n.temp = (int) Math.round(cur.getDouble("temperature_2m"));
            n.code = cur.getInt("weather_code");
            n.day = cur.optInt("is_day", 1) == 1;
            n.high = (int) Math.round(first(daily.getJSONArray("temperature_2m_max")));
            n.low = (int) Math.round(first(daily.getJSONArray("temperature_2m_min")));
            n.rainChance = (int) Math.round(first(daily.optJSONArray("precipitation_probability_max")));
            return n;
        } catch (Exception e) {
            return null;
        }
    }

    /** Looks a town up with Open-Meteo's geocoder and stores it. Returns its display name or null. */
    static String setPlace(Context c, String town) {
        try {
            String body = get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&format=json&name="
                    + URLEncoder.encode(town.trim(), "UTF-8"));
            JSONObject hit = new JSONObject(body).getJSONArray("results").getJSONObject(0);
            String name = hit.getString("name");
            Prefs.of(c).edit()
                    .putString(Prefs.WX_PLACE, name)
                    .putString(Prefs.WX_LAT, String.valueOf(hit.getDouble("latitude")))
                    .putString(Prefs.WX_LON, String.valueOf(hit.getDouble("longitude")))
                    .putLong(CACHE_TIME, 0) // force a refresh for the new place
                    .apply();
            return name;
        } catch (Exception e) {
            return null;
        }
    }

    private static double first(JSONArray a) {
        return a == null || a.length() == 0 ? 0 : a.optDouble(0, 0);
    }

    static String describe(int code) {
        if (code == 0) return "Clear";
        if (code <= 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code == 85 || code == 86) return "Snow showers";
        if (code >= 95) return "Thunderstorm";
        return "Weather";
    }

    static int icon(int code, boolean day) {
        if (code == 0 || code == 1) return day ? R.drawable.ic_wx_sun : R.drawable.ic_wx_moon;
        if (code == 45 || code == 48) return R.drawable.ic_wx_fog;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return R.drawable.ic_wx_snow;
        if (code >= 95) return R.drawable.ic_wx_storm;
        if (code >= 51) return R.drawable.ic_wx_rain;
        return R.drawable.ic_wx_cloud;
    }

    private static String get(String url) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(8000);
            con.setReadTimeout(8000);
            con.setRequestProperty("User-Agent", "M3Home/1.5 (Fire TV launcher)");
            if (con.getResponseCode() != 200) return null;
            try (InputStream in = con.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < 512_000) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (con != null) con.disconnect();
        }
    }
}
