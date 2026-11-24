package app.m3home;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

/**
 * M3 Home's own settings: a Material 3 side sheet over the current screen. Opened by the Settings
 * button and by holding Home. Its last section redirects into Fire TV's own settings pages.
 */
public class SettingsActivity extends Activity {
    static final String EXTRA_QUICK = "quick";

    private static final String[] MODES = { "rotate", "clock", "shapes", "slideshow" };
    private static final String[] MODE_NAMES = { "Rotate all", "Clock", "Shapes", "Slideshow" };

    private interface IntGet { int get(); }
    private interface IntSet { void set(int v); }
    private interface BoolGet { boolean get(); }
    private interface BoolSet { void set(boolean v); }

    private ScrollView sheet;
    private LinearLayout list;
    private View firstRow;

    private int dp(float v) { return M3.dp(this, v); }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        M3.init(this);
        FrameLayout root = new FrameLayout(this);

        sheet = new ScrollView(this);
        sheet.setVerticalScrollBarEnabled(false);
        sheet.setClipToPadding(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(M3.SURFACE_LOW);
        float r = dp(28);
        bg.setCornerRadii(new float[] { r, r, 0, 0, 0, 0, r, r });
        sheet.setBackground(bg);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(24), dp(36), dp(24), dp(40));
        sheet.addView(list);
        root.addView(sheet, new FrameLayout.LayoutParams(dp(540), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END));
        setContentView(root);

        build();

        sheet.setTranslationX(dp(540));
        sheet.animate().translationX(0).setDuration(280).setInterpolator(new DecelerateInterpolator(2f)).start();
        if (firstRow != null) firstRow.requestFocus();
    }

    @Override
    public void onBackPressed() {
        sheet.animate().translationX(dp(540)).setDuration(200).withEndAction(() -> {
            finish();
            overridePendingTransition(0, 0);
        }).start();
    }

    // ---- content ------------------------------------------------------------------------

    private void build() {
        TextView title = text("Settings", 30, M3.ON_SURFACE, M3.REGULAR);
        title.setPadding(dp(12), 0, 0, 0);
        list.addView(title);
        TextView sub = text("M3 Home " + version(), 15, M3.ON_SURFACE_VAR, M3.REGULAR);
        sub.setPadding(dp(12), dp(4), 0, dp(8));
        list.addView(sub);

        header("Sleep timer");
        sleepTimer();
        toggle("Bedtime", "The screensaver turns into a calm sleep scene",
                () -> Prefs.on(this, Prefs.BEDTIME),
                v -> Prefs.of(this).edit().putBoolean(Prefs.BEDTIME, v).apply());
        timeChoice("Bedtime starts", Prefs.BED_START, 23 * 60);
        timeChoice("Bedtime ends", Prefs.BED_END, 7 * 60);
        link("Preview sleep animation", null, () -> startActivity(new Intent(this, ScreensaverActivity.class)
                .putExtra(ScreensaverActivity.EXTRA_SLEEP, true)));

        header("Display");
        slider("Brightness", 20, 100, 5,
                () -> Prefs.brightness(this),
                v -> { Prefs.of(this).edit().putInt(Prefs.BRIGHTNESS, v).apply(); refreshFilter(); },
                v -> v + "%");
        toggle("Blue light filter", "Warms the picture to cut blue light",
                () -> Prefs.warm(this),
                v -> { Prefs.of(this).edit().putBoolean(Prefs.WARM, v).apply(); refreshFilter(); });
        slider("Filter strength", 10, 100, 10,
                () -> Prefs.warmStrength(this),
                v -> { Prefs.of(this).edit().putInt(Prefs.WARM_STRENGTH, v).apply(); refreshFilter(); },
                v -> v + "%");
        note("Brightness and the filter are a layer M3 Home draws over the picture. "
                + "Your TV's own picture settings are separate.");

        header("Screensaver");
        if (Secure.stayAwakeOn(this)) {
            link("Screensaver can't start", "\"Stay awake\" is on, often left by screen mirroring. Select to fix",
                    () -> Toast.makeText(this, Secure.fixStayAwake(this)
                            ? "Fixed. The screensaver can start again"
                            : "Couldn't change it. Run install.sh once to allow this", Toast.LENGTH_LONG).show());
        }
        choice("Style", MODE_NAMES,
                () -> indexOf(MODES, Prefs.screensaverMode(this)),
                i -> Prefs.of(this).edit().putString(Prefs.SS_MODE, MODES[i]).apply());
        choice("Picture time", SECONDS_NAMES,
                () -> indexOf(SECONDS, String.valueOf(Prefs.screensaverSeconds(this))),
                i -> Prefs.of(this).edit().putInt(Prefs.SS_SECONDS, Integer.parseInt(SECONDS[i])).apply());
        choice("Show", MEDIA_NAMES,
                () -> indexOf(MEDIA, Prefs.screensaverMedia(this)),
                i -> Prefs.of(this).edit().putString(Prefs.SS_MEDIA, MEDIA[i]).apply());
        for (final String theme : Prefs.THEMES) {
            final String key = Prefs.SS_THEME + theme;
            toggle(Character.toUpperCase(theme.charAt(0)) + theme.substring(1) + " wallpapers", null,
                    () -> Prefs.of(this).getBoolean(key, true),
                    v -> Prefs.of(this).edit().putBoolean(key, v).apply());
        }
        toggle("Clock and widgets", "On the screensaver",
                () -> Prefs.on(this, Prefs.SS_INFO),
                v -> Prefs.of(this).edit().putBoolean(Prefs.SS_INFO, v).apply());
        link("Preview screensaver", mediaSummary(),
                () -> startActivity(new Intent(this, ScreensaverActivity.class)));
        toggle("Use as Fire TV screensaver", "Replaces Amazon's screensaver",
                () -> Secure.isScreensaver(this),
                v -> {
                    if (!Secure.setScreensaver(this, v)) {
                        Toast.makeText(this, "Needs a one-time permission: run install.sh", Toast.LENGTH_LONG).show();
                    }
                });

        header("Widgets");
        link("Deadlines and countdown", "Scan a QR code to edit them on your phone",
                () -> startActivity(new Intent(this, DeadlinesActivity.class)));
        widgetToggle("Weather", Prefs.weatherPlace(this) == null
                ? "Set your town in Deadlines and countdown" : Prefs.weatherPlace(this) + " · °C", Prefs.W_WEATHER);
        widgetToggle("Deadlines", "Your next deadlines with countdowns", Prefs.W_DEADLINES);
        widgetToggle("Countdown", "One big countdown to a date", Prefs.W_COUNTDOWN);
        widgetToggle("Now playing", "What's playing, when an app shares it", Prefs.W_NOWPLAYING);
        widgetToggle("Tailscale", "Online status and tailnet address", Prefs.W_TAILSCALE);

        header("Home screen");
        link("Clear recently used", null, () -> {
            new Apps(this).clearRecents();
            Toast.makeText(this, "Recently used cleared", Toast.LENGTH_SHORT).show();
        });
        link("Show hidden apps again", null, () -> {
            new Apps(this).unhideAll();
            Toast.makeText(this, "All apps are visible again", Toast.LENGTH_SHORT).show();
        });

        header("Updates");
        final String[] status = { "Version " + Updater.installedVersion(this) + " · from GitHub" };
        link("Check for updates", status[0], () -> {
            Toast.makeText(this, "Checking GitHub…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                String msg = Updater.check(this);
                runOnUiThread(() -> {
                    if (Updater.available(this) == null) {
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                        return;
                    }
                    List<CharSequence> opts = new java.util.ArrayList<>();
                    opts.add("Install now");
                    opts.add("Later");
                    M3Menu.show(this, msg, opts, which -> {
                        if (which != 0) return;
                        new Thread(() -> Updater.install(this, m -> runOnUiThread(() ->
                                Toast.makeText(this, m, Toast.LENGTH_LONG).show()))).start();
                    });
                });
            }).start();
        });
        toggle("Check automatically", "Once a day; you choose when to install",
                () -> Prefs.on(this, Updater.AUTO),
                v -> Prefs.of(this).edit().putBoolean(Updater.AUTO, v).apply());

        header("Fire TV settings");
        link("Network", "Wi-Fi and Ethernet", () -> open(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        link("My Fire TV", "About, developer options, restart", () -> open(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)));
        link("Accessibility", null, () -> open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        link("All Fire TV settings", "Display & sounds, apps, remotes and more", () -> open(new Intent()
                .setComponent(new ComponentName("com.amazon.tv.launcher", "com.amazon.tv.launcher.ui.MainSettingsActivity"))));

        String ip = Net.tailscaleIp(this);
        note(ip == null ? "Tailscale is off" : ip.isEmpty() ? "Tailscale is on" : "Tailscale · " + ip);
    }

    private static final String[] SECONDS = { "10", "15", "20", "30", "60" };
    private static final String[] SECONDS_NAMES = { "10 s", "15 s", "20 s", "30 s", "1 min" };
    private static final String[] MEDIA = { "both", "stills", "live" };
    private static final String[] MEDIA_NAMES = { "Stills and live", "Stills only", "Live only" };

    /** A time in 30-minute steps; left/right (or Select) changes it. */
    private void timeChoice(String title, final String key, int def) {
        final String[] names = new String[48];
        for (int i = 0; i < 48; i++) names[i] = Prefs.hhmm(i * 30);
        choice(title, names,
                () -> Math.max(0, Math.min(47, Prefs.of(this).getInt(key, def) / 30)),
                i -> Prefs.of(this).edit().putInt(key, i * 30).apply());
    }

    private void widgetToggle(String title, String subtitle, String key) {
        toggle(title, subtitle, () -> Prefs.on(this, key), v -> Prefs.of(this).edit().putBoolean(key, v).apply());
    }

    private static final int[] SLEEP_MINUTES = { 0, 15, 30, 45, 60, 90, 120 };

    /** Off / 15 min ... 2 h. Left and right change it; the row shows when the stick will sleep. */
    private void sleepTimer() {
        LinearLayout r = row(LinearLayout.HORIZONTAL);
        LinearLayout text = labels("Sleep in", "Off");
        final TextView sub = (TextView) text.getChildAt(1);
        r.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView value = text("", 17, M3.PRIMARY, M3.MEDIUM);
        r.addView(value);

        long at = Prefs.sleepAt(this);
        final int[] idx = { 0 };
        if (at != 0) {
            int chosen = Prefs.of(this).getInt(Prefs.SLEEP_MINUTES, 0);
            for (int i = 0; i < SLEEP_MINUTES.length; i++) if (SLEEP_MINUTES[i] == chosen) idx[0] = i;
        }
        final Runnable show = () -> {
            int m = SLEEP_MINUTES[idx[0]];
            long when = Prefs.sleepAt(this);
            value.setText("‹  " + (m == 0 ? "Off" : m < 60 ? m + " min" : m % 60 == 0 ? m / 60 + " h" : m / 60 + " h " + m % 60) + "  ›");
            sub.setText(when == 0 ? "Fire TV goes to sleep when the time is up"
                    : "Sleeps at " + android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date(when)));
        };
        final IntSet apply = i -> {
            idx[0] = (i + SLEEP_MINUTES.length) % SLEEP_MINUTES.length;
            int m = SLEEP_MINUTES[idx[0]];
            Prefs.of(this).edit()
                    .putLong(Prefs.SLEEP_AT, m == 0 ? 0 : System.currentTimeMillis() + m * 60_000L)
                    .putInt(Prefs.SLEEP_MINUTES, m).apply();
            if (!HomeGuard.refreshSleep() && m != 0) {
                Toast.makeText(this, "Turn on the M3 Home accessibility service to use this", Toast.LENGTH_LONG).show();
            }
            show.run();
        };
        show.run();
        r.setOnClickListener(v -> apply.set(idx[0] + 1));
        r.setOnKeyListener((v, code, ev) -> {
            if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            if (ev.getAction() == KeyEvent.ACTION_DOWN) apply.set(idx[0] + (code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1));
            return true;
        });
    }

    private void refreshFilter() {
        if (!HomeGuard.refreshFilter()) {
            Toast.makeText(this, "Turn on the M3 Home accessibility service to use this", Toast.LENGTH_LONG).show();
        }
    }

    private void open(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "That page isn't available on this Fire TV", Toast.LENGTH_SHORT).show();
        }
    }

    private String mediaSummary() {
        List<File> media = ScreensaverView.chosenMedia(this);
        int videos = 0;
        for (File f : media) if (ScreensaverView.isVideo(f)) videos++;
        int images = media.size() - videos;
        if (media.isEmpty()) return "Slideshow: no pictures added yet";
        return "Slideshow: " + images + (images == 1 ? " picture, " : " pictures, ")
                + videos + (videos == 1 ? " live wallpaper" : " live wallpapers");
    }

    private String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private static int indexOf(String[] a, String v) {
        for (int i = 0; i < a.length; i++) if (a[i].equals(v)) return i;
        return 0;
    }

    // ---- building blocks ----------------------------------------------------------------

    private TextView text(String s, float sp, int color, android.graphics.Typeface face) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(face);
        return t;
    }

    private void header(String s) {
        TextView t = text(s, 15, M3.PRIMARY, M3.MEDIUM);
        t.setPadding(dp(12), dp(26), 0, dp(8));
        list.addView(t);
    }

    private void note(String s) {
        TextView t = text(s, 14, M3.ON_SURFACE_VAR, M3.REGULAR);
        t.setPadding(dp(12), dp(6), dp(12), dp(4));
        t.setAlpha(0.8f);
        list.addView(t);
    }

    /** A focusable M3 list row with a tonal highlight when focused. */
    private LinearLayout row(int orientation) {
        final LinearLayout r = new LinearLayout(this);
        r.setOrientation(orientation);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setFocusable(true);
        r.setClickable(true);
        r.setMinimumHeight(dp(64));
        r.setPadding(dp(16), dp(12), dp(16), dp(12));
        final GradientDrawable bg = M3.rounded(0x00000000, dp(20));
        r.setBackground(bg);
        r.setOnFocusChangeListener((v, has) -> bg.setColor(has ? M3.SECONDARY_CONT : 0x00000000));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(2);
        list.addView(r, lp);
        if (firstRow == null) firstRow = r;
        return r;
    }

    private LinearLayout labels(String title, String subtitle) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.addView(text(title, 18, M3.ON_SURFACE, M3.REGULAR));
        if (subtitle != null) {
            TextView s = text(subtitle, 14, M3.ON_SURFACE_VAR, M3.REGULAR);
            s.setPadding(0, dp(2), 0, 0);
            l.addView(s);
        }
        return l;
    }

    private void link(String title, String subtitle, Runnable action) {
        LinearLayout r = row(LinearLayout.HORIZONTAL);
        r.addView(labels(title, subtitle), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(text("›", 28, M3.ON_SURFACE_VAR, M3.REGULAR));
        r.setOnClickListener(v -> action.run());
    }

    private void toggle(String title, String subtitle, BoolGet get, BoolSet set) {
        LinearLayout r = row(LinearLayout.HORIZONTAL);
        r.addView(labels(title, subtitle), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final SwitchView sw = new SwitchView(this);
        sw.setChecked(get.get());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(52), dp(32));
        lp.leftMargin = dp(16);
        r.addView(sw, lp);
        r.setOnClickListener(v -> {
            set.set(!get.get());
            sw.setChecked(get.get());
        });
    }

    private void choice(String title, String[] names, IntGet get, IntSet set) {
        LinearLayout r = row(LinearLayout.HORIZONTAL);
        r.addView(labels(title, null), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView value = text("‹  " + names[get.get()] + "  ›", 17, M3.PRIMARY, M3.MEDIUM);
        r.addView(value);
        final Runnable[] step = new Runnable[2];
        step[0] = () -> { set.set((get.get() + 1) % names.length); value.setText("‹  " + names[get.get()] + "  ›"); };
        step[1] = () -> { set.set((get.get() + names.length - 1) % names.length); value.setText("‹  " + names[get.get()] + "  ›"); };
        r.setOnClickListener(v -> step[0].run());
        r.setOnKeyListener((v, code, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { step[0].run(); return true; }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) { step[1].run(); return true; }
            return false;
        });
    }

    private void slider(String title, int min, int max, int stepSize, IntGet get, IntSet set,
                        final java.util.function.IntFunction<String> fmt) {
        LinearLayout r = row(LinearLayout.VERTICAL);
        r.setGravity(Gravity.NO_GRAVITY);
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.addView(text(title, 18, M3.ON_SURFACE, M3.REGULAR),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView value = text(fmt.apply(get.get()), 17, M3.PRIMARY, M3.MEDIUM);
        top.addView(value);
        r.addView(top);
        final SliderView bar = new SliderView(this, min, max);
        bar.setValue(get.get());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28));
        lp.topMargin = dp(10);
        r.addView(bar, lp);
        r.setOnKeyListener((v, code, ev) -> {
            if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            if (ev.getAction() == KeyEvent.ACTION_DOWN) {
                int n = get.get() + (code == KeyEvent.KEYCODE_DPAD_RIGHT ? stepSize : -stepSize);
                n = Math.max(min, Math.min(max, n));
                set.set(n);
                bar.setValue(n);
                value.setText(fmt.apply(n));
            }
            return true;
        });
    }

    // ---- Material 3 controls drawn by hand ----------------------------------------------

    /** M3 switch: 52x32 track; filled primary with a large thumb when on, outlined when off. */
    private static final class SwitchView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private boolean on;

        SwitchView(Context c) { super(c); }

        void setChecked(boolean on) { this.on = on; invalidate(); }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = h / 2f, d = getResources().getDisplayMetrics().density;
            rect.set(0, 0, w, h);
            p.setStyle(Paint.Style.FILL);
            p.setColor(on ? M3.PRIMARY : M3.SURFACE_HIGHEST);
            c.drawRoundRect(rect, r, r, p);
            if (!on) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2 * d);
                p.setColor(M3.OUTLINE);
                rect.inset(d, d);
                c.drawRoundRect(rect, r, r, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(r, r, 8 * d, p);
            } else {
                p.setColor(M3.ON_PRIMARY);
                c.drawCircle(w - r, r, 12 * d, p);
            }
        }
    }

    /** M3 Expressive slider: thick rounded track, a gap, and a tall handle bar. */
    private static final class SliderView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final int min, max;
        private int value;

        SliderView(Context c, int min, int max) {
            super(c);
            this.min = min;
            this.max = max;
        }

        void setValue(int v) { value = v; invalidate(); }

        @Override
        protected void onDraw(Canvas c) {
            float d = getResources().getDisplayMetrics().density;
            float w = getWidth(), h = getHeight(), track = 12 * d, gap = 6 * d, handle = 4 * d;
            float frac = (value - min) / (float) (max - min);
            float x = handle / 2 + frac * (w - handle);
            float top = (h - track) / 2, bottom = top + track, r = track / 2;
            p.setColor(M3.PRIMARY);
            if (x - gap > 0) { rect.set(0, top, Math.max(x - gap, track), bottom); c.drawRoundRect(rect, r, r, p); }
            p.setColor(M3.SECONDARY_CONT);
            if (x + gap < w) { rect.set(Math.min(x + gap, w - track), top, w, bottom); c.drawRoundRect(rect, r, r, p); }
            p.setColor(M3.PRIMARY);
            rect.set(x - handle / 2, 0, x + handle / 2, h);
            c.drawRoundRect(rect, handle / 2, handle / 2, p);
        }
    }
}
