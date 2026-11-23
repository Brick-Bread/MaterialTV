package app.m3home;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Deadlines and the one big countdown, edited from the companion site. Stored as JSON in prefs. */
final class Deadlines {
    private Deadlines() {}

    private static final String KEY = "deadlines_json";
    private static final int MAX_ITEMS = 40, MAX_TITLE = 60;

    static final class Item {
        final String title;
        final LocalDate date;

        Item(String title, LocalDate date) { this.title = title; this.date = date; }

        long daysLeft() { return ChronoUnit.DAYS.between(LocalDate.now(), date); }

        String when() {
            long d = daysLeft();
            if (d == 0) return "today";
            if (d == 1) return "tomorrow";
            if (d == -1) return "yesterday";
            return d > 0 ? "in " + d + " days" : -d + " days ago";
        }
    }

    /** Upcoming deadlines (today or later), soonest first. */
    static List<Item> upcoming(Context c) {
        List<Item> out = new ArrayList<>();
        for (Item i : all(c)) if (i.daysLeft() >= 0) out.add(i);
        return out;
    }

    static List<Item> all(Context c) {
        List<Item> out = new ArrayList<>();
        try {
            JSONArray a = root(c).optJSONArray("deadlines");
            for (int i = 0; a != null && i < a.length(); i++) {
                Item it = parse(a.optJSONObject(i));
                if (it != null) out.add(it);
            }
        } catch (JSONException ignored) { }
        Collections.sort(out, (x, y) -> x.date.compareTo(y.date));
        return out;
    }

    static Item countdown(Context c) {
        try {
            return parse(root(c).optJSONObject("countdown"));
        } catch (JSONException e) {
            return null;
        }
    }

    /** What the companion site loads. */
    static String json(Context c) {
        try {
            JSONObject o = root(c);
            o.put("place", Prefs.weatherPlace(c));
            return o.toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    /** Validates and stores what the companion site sends. Returns null when OK, else a message. */
    static String save(Context c, String body) {
        try {
            JSONObject in = new JSONObject(body);
            JSONArray clean = new JSONArray();
            JSONArray list = in.optJSONArray("deadlines");
            for (int i = 0; list != null && i < list.length() && clean.length() < MAX_ITEMS; i++) {
                JSONObject d = list.optJSONObject(i);
                if (parse(d) != null) clean.put(normal(d));
            }
            JSONObject out = new JSONObject().put("deadlines", clean);
            JSONObject cd = in.optJSONObject("countdown");
            if (parse(cd) != null) out.put("countdown", normal(cd));
            Prefs.of(c).edit().putString(KEY, out.toString()).apply();
            return null;
        } catch (JSONException e) {
            return "That didn't look right. Check the dates.";
        }
    }

    private static JSONObject root(Context c) throws JSONException {
        return new JSONObject(Prefs.of(c).getString(KEY, "{\"deadlines\":[]}"));
    }

    private static JSONObject normal(JSONObject d) throws JSONException {
        String t = d.optString("title", "").trim();
        if (t.length() > MAX_TITLE) t = t.substring(0, MAX_TITLE);
        return new JSONObject().put("title", t).put("date", d.optString("date", "").trim());
    }

    private static Item parse(JSONObject d) {
        if (d == null) return null;
        String t = d.optString("title", "").trim();
        if (t.isEmpty()) return null;
        try {
            LocalDate date = LocalDate.parse(d.optString("date", "").trim());
            return new Item(t.length() > MAX_TITLE ? t.substring(0, MAX_TITLE) : t, date);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
