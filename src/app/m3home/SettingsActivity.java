package app.m3home;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;

/**
 * MaterialTV's own settings: a Material 3 side sheet over the current screen. Opened by the Settings
 * button and by holding Home. The sheet holds a stack of pages: a top page with quick toggles and
 * categories (Customise, Screensaver & sleep, Widgets, System). Back pops a page and puts the focus
 * back on the row that opened it; Back on the top page closes the sheet.
 */
public class SettingsActivity extends Activity {
    static final String EXTRA_QUICK = "quick";
    /** Open on this page (over the top page): PLUGINS. */
    static final String EXTRA_PAGE = "page";
    static final String PLUGINS = "plugins";
    /** One plugin's page: PLUGIN_PAGE + plugin id. */
    private static final String PLUGIN_PAGE = "plugin:";

    private static final String TOP = "top", CUSTOMISE = "customise", SCREENSAVER = "screensaver",
            WIDGETS = "widgets", SYSTEM = "system";

    private static final String[] MODES = { "rotate", "clock", "shapes", "slideshow" };
    private static final String[] MODE_NAMES = { "Rotate all", "Clock", "Shapes", "Slideshow" };
    private static final String[] SECONDS = { "10", "15", "20", "30", "60" };
    private static final String[] SECONDS_NAMES = { "10 s", "15 s", "20 s", "30 s", "1 min" };
    private static final String[] MEDIA = { "both", "stills", "live" };
    private static final String[] MEDIA_NAMES = { "Stills and live", "Stills only", "Live only" };

    private static final String[] ROW_IDS = { "favourites", "recents", "apps" };
    private static final String[] ROW_NAMES = { "Favourites", "Recently used", "All apps" };
    private static final String[] ROW_SHORT = { "Favourites", "Recent", "Apps" };
    /** Every row with a Show toggle: the three above plus the app store's Get apps. */
    private static final String[] ALL_ROW_IDS = { "favourites", "recents", "apps", "store" };
    private static final String[] ALL_ROW_NAMES = { "Favourites", "Recently used", "All apps", "Get apps row" };
    private static final int[][] ROW_ORDERS = { { 0, 1, 2 }, { 0, 2, 1 }, { 1, 0, 2 }, { 1, 2, 0 }, { 2, 0, 1 }, { 2, 1, 0 } };
    private static final String[] TILE_SIZES = { "compact", "normal", "large" };
    private static final String[] TILE_NAMES = { "Compact", "Normal", "Large" };
    private static final String[] THEME_MODES = { "dark", "light" };
    private static final String[] THEME_NAMES = { "Dark", "Light" };
    private static final String[] TEXT_SIZES = { "small", "normal", "large" };
    private static final String[] TEXT_NAMES = { "Small", "Normal", "Large" };
    private static final String[] BG_KINDS = { "gradient", "solid", "wallpaper", "slideshow", "custom" };
    private static final String[] BG_NAMES = { "Gradient", "Solid colour", "Wallpaper", "Slideshow", "Your picture" };
    private static final String[] SOLID_NAMES = { "Charcoal", "Midnight", "Forest", "Plum", "Black" };

    private static final int[] SLEEP_MINUTES = { 0, 15, 30, 45, 60, 90, 120 };
    private static final int[] QUICK_SLEEP = { 0, 15, 30, 45, 60, 90 };

    private interface IntGet { int get(); }
    private interface IntSet { void set(int v); }
    private interface BoolGet { boolean get(); }
    private interface BoolSet { void set(boolean v); }

    private ScrollView sheet;
    private GradientDrawable sheetBg;
    private LinearLayout list;
    private View firstRow;
    /** Every focusable row and chip of the current page, in order: focus is saved and restored by index. */
    private final List<View> focusables = new ArrayList<>();
    /** The page stack, current page first, and for each page below it the index of the row that opened the next. */
    private final ArrayDeque<String> pages = new ArrayDeque<>();
    private final ArrayDeque<Integer> openers = new ArrayDeque<>();
    private TextView previewSub;
    private final Map<String, Runnable> themeRows = new HashMap<>();
    private int wallpaperChoices = -1; // how many wallpaper pictures the Customise page offered, or -1
    private int seenRev;
    private float builtScale;
    private boolean resumed, closing;

    private int dp(float v) { return M3.dp(this, v); }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(M3.scaled(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        M3.init(this);
        seenRev = Prefs.lookRev(this);
        builtScale = Prefs.textScale(this);
        FrameLayout root = new FrameLayout(this);

        sheet = new ScrollView(this);
        sheet.setVerticalScrollBarEnabled(false);
        sheet.setClipToPadding(false);
        sheetBg = new GradientDrawable();
        sheetBg.setColor(M3.SURFACE_LOW);
        float r = dp(28);
        sheetBg.setCornerRadii(new float[] { r, r, 0, 0, 0, 0, r, r });
        sheet.setBackground(sheetBg);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(24), dp(36), dp(24), dp(40));
        sheet.addView(list);
        root.addView(sheet, new FrameLayout.LayoutParams(dp(540), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END));
        setContentView(root);

        // After a recreate (the text size changed) come back to the same page and row.
        int focus = 0;
        if (state != null) {
            ArrayList<String> p = state.getStringArrayList("pages");
            ArrayList<Integer> o = state.getIntegerArrayList("openers");
            if (p != null && o != null && !p.isEmpty() && p.size() == o.size() + 1) {
                pages.addAll(p);
                openers.addAll(o);
                focus = state.getInt("focus", 0);
            }
        }
        if (pages.isEmpty()) {
            pages.push(TOP);
            if (PLUGINS.equals(getIntent().getStringExtra(EXTRA_PAGE))) { openers.push(0); pages.push(PLUGINS); }
        }
        render(focus);

        // Wallpaper downloads report here; each theme row (and the slideshow count) redraws itself.
        Wallpapers.setListener(theme -> {
            Runnable redraw = themeRows.get(theme);
            if (redraw != null) redraw.run();
            if (previewSub != null) previewSub.setText(mediaSummary());
            if (wallpaperChoices >= 0 && CUSTOMISE.equals(pages.peek())
                    && Backdrop.wallpaperOptions(this).size() != wallpaperChoices) {
                refresh(); // a download finished: offer its pictures as backgrounds
            }
        });

        if (state == null) {
            sheet.setTranslationX(dp(540));
            sheet.animate().translationX(0).setDuration(280).setInterpolator(new DecelerateInterpolator(2f)).start();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Opened again (Settings button, or holding Home) while already open: back to the top page.
        closing = false;
        sheet.animate().cancel();
        sheet.setTranslationX(0);
        pages.clear();
        openers.clear();
        pages.push(TOP);
        if (PLUGINS.equals(intent.getStringExtra(EXTRA_PAGE))) { openers.push(0); pages.push(PLUGINS); }
        render(0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!resumed) { resumed = true; return; }
        // Back from another screen (Fire TV setup, the phone page...): show what changed there.
        int rev = Prefs.lookRev(this);
        if (rev != seenRev) {
            seenRev = rev;
            if (Prefs.textScale(this) != builtScale) { recreate(); return; }
            M3.init(this);
        }
        refresh();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putStringArrayList("pages", new ArrayList<>(pages));
        out.putIntegerArrayList("openers", new ArrayList<>(openers));
        out.putInt("focus", focusIndex());
    }

    @Override
    protected void onDestroy() {
        Wallpapers.setListener(null); // downloads carry on; only the progress display stops
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (pages.size() > 1) {
            pages.pop();
            render(openers.pop());
            return;
        }
        if (closing) return;
        closing = true;
        sheet.animate().translationX(dp(540)).setDuration(200).withEndAction(() -> {
            if (!closing) return; // opened again meanwhile
            finish();
            overridePendingTransition(0, 0);
        }).start();
    }

    // ---- pages --------------------------------------------------------------------------

    /** Rebuilds the current page and focuses the row at this index (the first row if there's no such row). */
    private void render(int focus) {
        list.removeAllViews();
        focusables.clear();
        firstRow = null;
        previewSub = null;
        themeRows.clear();
        wallpaperChoices = -1;
        sheetBg.setColor(M3.SURFACE_LOW);
        String page = pages.peek();
        if (CUSTOMISE.equals(page)) customisePage();
        else if (SCREENSAVER.equals(page)) screensaverPage();
        else if (WIDGETS.equals(page)) widgetsPage();
        else if (SYSTEM.equals(page)) systemPage();
        else if (PLUGINS.equals(page)) pluginsPage();
        else if (page != null && page.startsWith(PLUGIN_PAGE)) pluginPage(page.substring(PLUGIN_PAGE.length()));
        else topPage();
        View target = focus > 0 && focus < focusables.size() ? focusables.get(focus) : firstRow;
        if (target == firstRow) sheet.scrollTo(0, 0);
        if (target != null) target.requestFocus();
    }

    private void refresh() { render(focusIndex()); }

    private int focusIndex() {
        return Math.max(0, focusables.indexOf(getCurrentFocus()));
    }

    private void push(String page) {
        openers.push(focusIndex());
        pages.push(page);
        render(0);
    }

    /** After a look pref changed: tell Home, re-apply the colours here and, if asked, redraw the sheet in them. */
    private void lookChanged(boolean background, boolean redraw) {
        if (background) Backdrop.changed(this);
        else Prefs.bumpLook(this);
        seenRev = Prefs.lookRev(this);
        M3.init(this);
        if (redraw) refresh();
    }

    private void topPage() {
        title(null, "Settings");
        TextView sub = text("MaterialTV " + version(), 15, M3.ON_SURFACE_VAR, M3.REGULAR);
        sub.setPadding(dp(12), dp(4), 0, dp(16));
        list.addView(sub);

        quickChips();
        space(12);
        if (Secure.stayAwakeOn(this)) stayAwakeRow();
        link("Customise", customiseSummary(), () -> push(CUSTOMISE));
        link("Customise from your phone", "Scan a QR code to change the look, favourites and widgets",
                () -> startActivity(new Intent(this, DeadlinesActivity.class)
                        .putExtra(DeadlinesActivity.EXTRA_CUSTOMISE, true)));
        link("Screensaver & sleep", screensaverSummary(), () -> push(SCREENSAVER));
        link("Widgets", widgetsSummary(), () -> push(WIDGETS));
        link("Plugins", pluginsSummary(), () -> push(PLUGINS));
        link("System", "Brightness, blue light, Fire TV setup, updates, Fire TV settings", () -> push(SYSTEM));
    }

    /** Sleep timer, Blue light, Bedtime and Start screensaver as one row of chips. */
    private void quickChips() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        list.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        chip(bar, "Sleep timer", () -> {
                    int m = sleepMinutes();
                    return m == 0 ? "Off" : minutes(m);
                },
                () -> Prefs.sleepAt(this) != 0,
                () -> {
                    int m = sleepMinutes(), next = 0;
                    for (int q : QUICK_SLEEP) if (q > m) { next = q; break; }
                    setSleep(next); // Off -> 15 -> 30 -> 45 -> 60 -> 90 min -> Off
                });
        chip(bar, "Blue light", () -> Prefs.warm(this) ? "On" : "Off", () -> Prefs.warm(this), () -> {
            Prefs.of(this).edit().putBoolean(Prefs.WARM, !Prefs.warm(this)).apply();
            refreshFilter();
        });
        chip(bar, "Bedtime",
                () -> Prefs.on(this, Prefs.BEDTIME)
                        ? Prefs.hhmm(Prefs.bedStart(this)) + "–" + Prefs.hhmm(Prefs.bedEnd(this)) : "Off",
                () -> Prefs.on(this, Prefs.BEDTIME),
                () -> Prefs.of(this).edit().putBoolean(Prefs.BEDTIME, !Prefs.on(this, Prefs.BEDTIME)).apply());
        chip(bar, "Screensaver", () -> "Start", () -> false,
                () -> startActivity(new Intent(this, ScreensaverActivity.class)));
    }

    private void customisePage() {
        title("Settings", "Customise");

        header("Home screen");
        choice("Row order", orderNames(), this::rowOrderIndex, i -> {
            // The three app rows swap places; Get apps keeps its own slot.
            List<String> order = fullRowOrder();
            int k = 0;
            for (int j = 0; j < order.size(); j++) {
                if (Arrays.asList(ROW_IDS).contains(order.get(j))) order.set(j, ROW_IDS[ROW_ORDERS[i][k++]]);
            }
            saveRows(order, shownRows());
            lookChanged(false, false);
        });
        for (int i = 0; i < ALL_ROW_IDS.length; i++) {
            final String id = ALL_ROW_IDS[i];
            toggle("Show " + ALL_ROW_NAMES[i], null, () -> shownRows().contains(id), v -> {
                List<String> shown = shownRows();
                if (v && !shown.contains(id)) shown.add(id);
                if (!v) shown.remove(id);
                saveRows(fullRowOrder(), shown);
                lookChanged(false, false);
            });
        }
        choice("Tile size", TILE_NAMES, () -> indexOf(TILE_SIZES, Prefs.tileSize(this)), i -> {
            Prefs.of(this).edit().putString(Prefs.TILE_SIZE, TILE_SIZES[i]).apply();
            lookChanged(false, false);
        });
        link("Reorder favourites", "Pick up a favourite on Home and move it with left and right", () -> {
            startActivity(new Intent(this, Home.class).putExtra(Home.EXTRA_REORDER, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
            overridePendingTransition(0, 0);
        });
        link("Clear recently used", null, () -> {
            new Apps(this).clearRecents();
            Toast.makeText(this, "Recently used cleared", Toast.LENGTH_SHORT).show();
        });
        link("Show hidden apps again", null, () -> {
            new Apps(this).unhideAll();
            Toast.makeText(this, "All apps are visible again", Toast.LENGTH_SHORT).show();
        });

        header("Theme");
        choice("Accent colour", Palette.ACCENT_NAMES, () -> indexOf(Palette.ACCENTS, Prefs.accent(this)), i -> {
            Prefs.of(this).edit().putString(Prefs.ACCENT, Palette.ACCENTS[i]).apply();
            lookChanged(false, true);
        }, i -> Palette.seedOf(this, Palette.ACCENTS[i]));
        choice("Light or dark", THEME_NAMES, () -> Prefs.light(this) ? 1 : 0, i -> {
            Prefs.of(this).edit().putString(Prefs.THEME_MODE, THEME_MODES[i]).apply();
            lookChanged(false, true);
        });
        choice("Text size", TEXT_NAMES,
                () -> indexOf(TEXT_SIZES, Prefs.of(this).getString(Prefs.TEXT_SIZE, "normal")), i -> {
            Prefs.of(this).edit().putString(Prefs.TEXT_SIZE, TEXT_SIZES[i]).apply();
            lookChanged(false, false);
            recreate(); // the text size is applied when the activity's context is made
        });

        header("Background");
        final String kind = Prefs.bgKind(this);
        choice("Style", BG_NAMES, () -> indexOf(BG_KINDS, Prefs.bgKind(this)), i -> {
            SharedPreferences.Editor e = Prefs.of(this).edit().putString(Prefs.BG_KIND, BG_KINDS[i]);
            if ("wallpaper".equals(BG_KINDS[i])) {
                // Start on a picture that exists, so Home doesn't fall back to the gradient.
                List<String[]> opts = Backdrop.wallpaperOptions(this);
                if (!opts.isEmpty() && optionIndex(opts, Prefs.of(this).getString(Prefs.BG_WALLPAPER, null)) < 0) {
                    e.putString(Prefs.BG_WALLPAPER, opts.get(0)[0]);
                }
            }
            e.apply();
            lookChanged(true, true); // also shows or hides the rows below
        });
        if ("solid".equals(kind)) {
            final int[] presets = Backdrop.SOLID_PRESETS;
            String[] names = new String[presets.length];
            for (int i = 0; i < names.length; i++) names[i] = i < SOLID_NAMES.length ? SOLID_NAMES[i] : "Colour " + (i + 1);
            choice("Colour", names, () -> {
                int c = Prefs.of(this).getInt(Prefs.BG_COLOR, presets[0]);
                for (int i = 0; i < presets.length; i++) if (presets[i] == c) return i;
                return 0;
            }, i -> {
                Prefs.of(this).edit().putInt(Prefs.BG_COLOR, presets[i]).apply();
                lookChanged(true, followsBackground());
            }, i -> presets[i]);
        } else if ("wallpaper".equals(kind)) {
            final List<String[]> opts = Backdrop.wallpaperOptions(this);
            wallpaperChoices = opts.size();
            if (opts.isEmpty()) {
                link("Download wallpapers", "None yet. Turn on a theme in Screensaver & sleep", () -> push(SCREENSAVER));
            } else {
                String[] names = new String[opts.size()];
                for (int i = 0; i < names.length; i++) names[i] = opts.get(i)[1];
                choice("Picture", names,
                        () -> Math.max(0, optionIndex(opts, Prefs.of(this).getString(Prefs.BG_WALLPAPER, null))), i -> {
                    Prefs.of(this).edit().putString(Prefs.BG_WALLPAPER, opts.get(i)[0]).apply();
                    lookChanged(true, followsBackground());
                });
            }
        } else if ("slideshow".equals(kind)) {
            slideshowRows();
        } else if ("custom".equals(kind) && !new File(getFilesDir(), "background/custom.jpg").exists()) {
            note("No picture yet. Upload one from your phone below.");
        }
        if ("wallpaper".equals(kind) || "custom".equals(kind) || "slideshow".equals(kind)) {
            slider("Dim", 0, 80, 10, () -> Prefs.bgDim(this), v -> {
                Prefs.of(this).edit().putInt(Prefs.BG_DIM, v).apply();
                lookChanged(true, false);
            }, v -> v + "%");
            slider("Blur", 0, 100, 10, () -> Prefs.bgBlur(this), v -> {
                Prefs.of(this).edit().putInt(Prefs.BG_BLUR, v).apply();
                lookChanged(true, false);
            }, v -> v == 0 ? "Off" : v + "%");
        }
        link("Upload a picture from your phone", "Scan a QR code, then choose a photo",
                () -> startActivity(new Intent(this, DeadlinesActivity.class)
                        .putExtra(DeadlinesActivity.EXTRA_CUSTOMISE, true)));
    }

    // ---- background slideshow --- begin -------------------------------------------------
    /** Customise > Background when Style is Slideshow: how often, and which themes' pictures. */
    private void slideshowRows() {
        wallpaperChoices = Backdrop.wallpaperOptions(this).size(); // redraw when a download finishes
        if (wallpaperChoices == 0) {
            link("Download wallpapers", "None yet, so Home shows the gradient. Turn on a theme in Screensaver & sleep",
                    () -> push(SCREENSAVER));
            return;
        }
        final int[] mins = Prefs.BG_SLIDE_CHOICES;
        String[] names = new String[mins.length];
        for (int i = 0; i < mins.length; i++) names[i] = minutes(mins[i]);
        choice("Change every", names, () -> {
            int m = Prefs.bgSlideMinutes(this);
            for (int i = 0; i < mins.length; i++) if (mins[i] == m) return i;
            return 0;
        }, i -> Prefs.of(this).edit().putInt(Prefs.BG_SLIDE_MINUTES, mins[i]).apply()); // Home reads it on resume
        for (final String theme : Prefs.THEMES) {
            int n = Backdrop.slideCount(this, theme);
            String name = theme.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + theme.substring(1);
            toggle(name + " pictures", n == 0 ? "Not downloaded" : n == 1 ? "1 picture" : n + " pictures",
                    () -> Prefs.bgSlideThemes(this).contains(theme), v -> {
                List<String> chosen = Prefs.bgSlideThemes(this);
                if (v && !chosen.contains(theme)) chosen.add(theme);
                if (!v) chosen.remove(theme);
                if (chosen.isEmpty()) {
                    Toast.makeText(this, "Keep at least one theme on", Toast.LENGTH_SHORT).show();
                    return;
                }
                StringBuilder csv = new StringBuilder();
                for (String t : Prefs.THEMES) if (chosen.contains(t)) csv.append(csv.length() == 0 ? "" : ",").append(t);
                boolean all = chosen.size() == Prefs.THEMES.length;
                Prefs.of(this).edit().putString(Prefs.BG_SLIDE_THEMES, all ? "" : csv.toString()).apply();
                lookChanged(true, followsBackground());
            });
        }
    }
    // ---- background slideshow --- end ---------------------------------------------------

    private void screensaverPage() {
        title("Settings", "Screensaver & sleep");

        header("Sleep");
        sleepTimer();
        toggle("Bedtime", "The screensaver turns into a calm sleep scene",
                () -> Prefs.on(this, Prefs.BEDTIME),
                v -> Prefs.of(this).edit().putBoolean(Prefs.BEDTIME, v).apply());
        timeChoice("Bedtime starts", Prefs.BED_START, 23 * 60);
        timeChoice("Bedtime ends", Prefs.BED_END, 7 * 60);
        link("Preview sleep animation", null, () -> startActivity(new Intent(this, ScreensaverActivity.class)
                .putExtra(ScreensaverActivity.EXTRA_SLEEP, true)));

        header("Screensaver");
        if (Secure.stayAwakeOn(this)) stayAwakeRow();
        choice("Style", MODE_NAMES,
                () -> indexOf(MODES, Prefs.screensaverMode(this)),
                i -> Prefs.of(this).edit().putString(Prefs.SS_MODE, MODES[i]).apply());
        choice("Picture time", SECONDS_NAMES,
                () -> indexOf(SECONDS, String.valueOf(Prefs.screensaverSeconds(this))),
                i -> Prefs.of(this).edit().putInt(Prefs.SS_SECONDS, Integer.parseInt(SECONDS[i])).apply());
        choice("Show", MEDIA_NAMES,
                () -> indexOf(MEDIA, Prefs.screensaverMedia(this)),
                i -> Prefs.of(this).edit().putString(Prefs.SS_MEDIA, MEDIA[i]).apply());
        for (String theme : Prefs.THEMES) themeToggle(theme);
        toggle("Clock and widgets", "On the screensaver",
                () -> Prefs.on(this, Prefs.SS_INFO),
                v -> Prefs.of(this).edit().putBoolean(Prefs.SS_INFO, v).apply());
        previewSub = link("Preview screensaver", mediaSummary(),
                () -> startActivity(new Intent(this, ScreensaverActivity.class)));
        toggle("Use as Fire TV screensaver", "Replaces Amazon's screensaver",
                () -> Secure.isScreensaver(this),
                v -> {
                    if (!Secure.setScreensaver(this, v)) {
                        Toast.makeText(this, "Needs a one-time permission: run install.sh", Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void widgetsPage() {
        title("Settings", "Widgets");
        link("Deadlines and countdown", "Scan a QR code to edit them on your phone",
                () -> startActivity(new Intent(this, DeadlinesActivity.class)));
        header("On Home");
        widgetToggle("Weather", Prefs.weatherPlace(this) == null
                ? "Set your town in Deadlines and countdown" : Prefs.weatherPlace(this) + " · °C", Prefs.W_WEATHER);
        widgetToggle("Deadlines", "Your next deadlines with countdowns", Prefs.W_DEADLINES);
        widgetToggle("Countdown", "One big countdown to a date", Prefs.W_COUNTDOWN);
        widgetToggle("Now playing", "What's playing, when an app shares it", Prefs.W_NOWPLAYING);
        widgetToggle("Tailscale", "Online status and tailnet address", Prefs.W_TAILSCALE);
    }

    private void systemPage() {
        title("Settings", "System");

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
        note("Brightness and the filter are a layer MaterialTV draws over the picture. "
                + "Your TV's own picture settings are separate.");

        header("Privacy");
        toggle("Hide IP addresses", "Masks addresses on screen, for screenshots or sharing your screen",
                () -> Prefs.of(this).getBoolean(Prefs.HIDE_IPS, false),
                v -> Prefs.of(this).edit().putBoolean(Prefs.HIDE_IPS, v).apply());

        header("Fire TV setup");
        link("Run setup", "MaterialTV permissions, debloat and privacy, SmartTube, Tailscale",
                () -> startActivity(new Intent(this, SetupActivity.class)));
        link("Undo setup / uninstall", "Put everything back, and optionally remove MaterialTV",
                () -> startActivity(new Intent(this, SetupActivity.class).putExtra(SetupActivity.EXTRA_UNDO, true)));

        header("Updates");
        link("Check for updates", "Version " + Updater.installedVersion(this) + " · from GitHub", () -> {
            Toast.makeText(this, "Checking GitHub…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                String msg = Updater.check(this);
                runOnUiThread(() -> {
                    if (Updater.available(this) == null) {
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                        return;
                    }
                    List<CharSequence> opts = new ArrayList<>();
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
        link("Refresh app store", "The Get apps list and the latest versions", () -> {
            Toast.makeText(this, "Refreshing the app store…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                String msg = Store.refresh(getApplicationContext());
                runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
            }, "m3home-store-refresh").start();
        });

        header("Fire TV settings");
        link("Network", "Wi-Fi and Ethernet", () -> open(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        link("My Fire TV", "About, developer options, restart", () -> open(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)));
        link("Accessibility", null, () -> open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        link("All Fire TV settings", "Display & sounds, apps, remotes and more", () -> open(new Intent()
                .setComponent(new ComponentName("com.amazon.tv.launcher", "com.amazon.tv.launcher.ui.MainSettingsActivity"))));

        String ip = Net.tailscaleIp(this);
        note(ip == null ? "Tailscale is off" : ip.isEmpty() ? "Tailscale is on" : "Tailscale · " + Net.show(this, ip));
    }

    // ---- plugins -----------------------------------------------------------------------------

    private void pluginsPage() {
        title("Settings", "Plugins");
        note("Plugins add Home widgets, rows of tiles, themes and shortcuts. Web plugins are added by link "
                + "from your phone or the Get apps row; Android plugins are apps, and stay off until you switch them on here.");
        List<Plugins.Plugin> all = Plugins.all(this);
        header("Installed");
        if (all.isEmpty()) note("No plugins yet.");
        for (final Plugins.Plugin p : all) {
            boolean on = Plugins.enabled(this, p);
            link(p.name, (on ? "On" : "Off") + " · " + (p.android() ? "Android app · " : "") + p.adds(),
                    () -> push(PLUGIN_PAGE + p.id));
        }
        header("Add and update");
        link("Add a plugin from your phone", "Scan a QR code, then paste the plugin's link under Plugins",
                () -> startActivity(new Intent(this, DeadlinesActivity.class)
                        .putExtra(DeadlinesActivity.EXTRA_CUSTOMISE, true)));
        link("Refresh plugins", "Reload every plugin, and look for new Android plugins", this::refreshPlugins);
        note("Make your own: brick-bread.github.io/MaterialTV/plugins.html");
    }

    private void pluginPage(final String id) {
        final Plugins.Plugin p = Plugins.find(this, id);
        if (p == null) {
            title("Plugins", "Plugin");
            note("This plugin isn't installed any more.");
            return;
        }
        title("Plugins", p.name);
        StringBuilder about = new StringBuilder();
        if (!p.description.isEmpty()) about.append(p.description).append('\n');
        about.append(p.android() ? "Android app " + p.pkg : (p.author.isEmpty() ? "" : "By " + p.author + " · ") + p.url);
        if (!p.version.isEmpty()) about.append(" · version ").append(p.version);
        note(about.toString());

        toggle("On", p.adds(), () -> Plugins.enabled(this, p), v -> {
            Prefs.of(this).edit().putBoolean(Prefs.PLUGIN_ON + p.id, v).commit(); // shows at once; the rest in the background
            final Context app = getApplicationContext();
            new Thread(() -> {
                Plugins.setEnabled(app, p.id, v);
                runOnUiThread(() -> { if (!isDestroyed()) refresh(); });
            }, "m3home-plugin-enable").start();
        });
        boolean on = Plugins.enabled(this, p);
        if (on && !p.rowKeys().isEmpty()) {
            header("Rows on Home");
            for (final String key : p.rowKeys()) {
                String rowTitle = Plugins.rowTitle(this, key);
                toggle(key.endsWith("/" + Plugins.ACTIONS_ROW) ? "Shortcuts row" : rowTitle == null ? key : rowTitle, null,
                        () -> ("," + Prefs.homeRows(this) + ",").contains("," + key + ","),
                        v -> Plugins.showRow(this, key, v));
            }
        }
        if (on && !p.actions.isEmpty()) {
            header("Shortcuts in favourites");
            final Apps apps = new Apps(this);
            for (final Plugins.Action a : p.actions) {
                toggle(a.title, a.type.equals("webhook") ? "Smart home action" : a.type.equals("app") ? "Opens an app" : "Opens a web page",
                        () -> apps.isFavourite(a.favId()), v -> {
                            if (v != apps.isFavourite(a.favId())) apps.toggle(a.favId());
                        });
            }
        }
        if (p.theme != null) {
            header("Theme");
            link("Apply theme", themeSummary(p.theme), () -> {
                Toast.makeText(this, "Applying " + p.name + "…", Toast.LENGTH_SHORT).show();
                final Context app = getApplicationContext();
                new Thread(() -> {
                    String msg = Plugins.applyTheme(app, p);
                    runOnUiThread(() -> {
                        Toast.makeText(app, msg, Toast.LENGTH_LONG).show();
                        if (isDestroyed()) return;
                        seenRev = Prefs.lookRev(this);
                        M3.init(this);
                        if (Prefs.textScale(this) != builtScale) recreate(); else refresh();
                    });
                }, "m3home-plugin-theme").start();
            });
        }
        header("Manage");
        link("Refresh", "Fetch its widgets and rows again now", this::refreshPlugins);
        if (p.android()) {
            link("Uninstall the app", "Android plugins are removed by uninstalling them",
                    () -> open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + p.pkg))));
        } else {
            link("Remove plugin", "Takes off its rows, widgets and shortcuts", () -> {
                List<CharSequence> opts = new ArrayList<>();
                opts.add("Remove " + p.name);
                opts.add("Keep it");
                M3Menu.show(this, "Remove " + p.name + "?", opts, which -> {
                    if (which != 0) return;
                    Plugins.remove(this, p.id);
                    Toast.makeText(this, p.name + " removed", Toast.LENGTH_SHORT).show();
                    onBackPressed();
                });
            });
        }
    }

    private void refreshPlugins() {
        Toast.makeText(this, "Refreshing plugins…", Toast.LENGTH_SHORT).show();
        final Context app = getApplicationContext();
        new Thread(() -> {
            String msg = Plugins.refreshAll(app);
            runOnUiThread(() -> {
                Toast.makeText(app, msg, Toast.LENGTH_LONG).show();
                if (!isDestroyed()) refresh();
            });
        }, "m3home-plugins-refresh").start();
    }

    /** "Accent #1E88E5 · Dark · a picture background". */
    private static String themeSummary(org.json.JSONObject t) {
        List<String> out = new ArrayList<>();
        if (t.has("accent")) out.add("Accent " + t.optString("accent"));
        if (t.has("mode")) out.add(t.optString("mode").equals("light") ? "Light" : "Dark");
        if (t.has("background_url")) out.add("a picture background");
        else if (t.has("background_color")) out.add("background " + t.optString("background_color"));
        if (t.has("tile_size")) out.add(t.optString("tile_size") + " tiles");
        return out.isEmpty() ? "Changes the look" : TextUtils.join(" · ", out);
    }

    /** "2 plugins · 1 on". */
    private String pluginsSummary() {
        List<Plugins.Plugin> all = Plugins.all(this);
        if (all.isEmpty()) return "Add widgets, rows, themes and shortcuts";
        int on = 0;
        for (Plugins.Plugin p : all) if (Plugins.enabled(this, p)) on++;
        return all.size() + (all.size() == 1 ? " plugin · " : " plugins · ") + on + " on";
    }

    // ---- page parts and summaries -------------------------------------------------------

    private void stayAwakeRow() {
        link("Screensaver can't start", "\"Stay awake\" is on, often left by screen mirroring. Select to fix", () -> {
            boolean fixed = Secure.fixStayAwake(this);
            Toast.makeText(this, fixed
                    ? "Fixed. The screensaver can start again"
                    : "Couldn't change it. Run install.sh once to allow this", Toast.LENGTH_LONG).show();
        });
    }

    /** "Purple · Dark · Gradient · Normal tiles". */
    private String customiseSummary() {
        return Palette.ACCENT_NAMES[indexOf(Palette.ACCENTS, Prefs.accent(this))]
                + " · " + (Prefs.light(this) ? "Light" : "Dark")
                + " · " + BG_NAMES[indexOf(BG_KINDS, Prefs.bgKind(this))]
                + " · " + TILE_NAMES[indexOf(TILE_SIZES, Prefs.tileSize(this))] + " tiles";
    }

    /** "Rotate all · Bedtime 23:00–07:00". */
    private String screensaverSummary() {
        String s = MODE_NAMES[indexOf(MODES, Prefs.screensaverMode(this))];
        s += Prefs.on(this, Prefs.BEDTIME)
                ? " · Bedtime " + Prefs.hhmm(Prefs.bedStart(this)) + "–" + Prefs.hhmm(Prefs.bedEnd(this))
                : " · Bedtime off";
        long at = Prefs.sleepAt(this);
        if (at != 0) s += " · Sleeps at " + android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date(at));
        return s;
    }

    /** The widgets that are on, e.g. "Weather, Deadlines, Now playing". */
    private String widgetsSummary() {
        String[] keys = { Prefs.W_WEATHER, Prefs.W_DEADLINES, Prefs.W_COUNTDOWN, Prefs.W_NOWPLAYING, Prefs.W_TAILSCALE };
        String[] names = { "Weather", "Deadlines", "Countdown", "Now playing", "Tailscale" };
        List<String> on = new ArrayList<>();
        for (int i = 0; i < keys.length; i++) if (Prefs.on(this, keys[i])) on.add(names[i]);
        return on.isEmpty() ? "All off" : TextUtils.join(", ", on);
    }

    private boolean followsBackground() { return "background".equals(Prefs.accent(this)); }

    private static int optionIndex(List<String[]> opts, String id) {
        if (id == null) return -1;
        for (int i = 0; i < opts.size(); i++) if (id.equals(opts.get(i)[0])) return i;
        return -1;
    }

    // Home rows: HOME_ROWS lists the shown rows in order. A hidden row is out of the list and comes
    // back at the end when it's shown again.

    private List<String> shownRows() {
        List<String> out = new ArrayList<>();
        for (String s : Prefs.homeRows(this).split(",")) {
            String id = s.trim();
            // Plugin rows ride along, so saving the built-in rows keeps them (Settings > Plugins shows or hides them).
            boolean known = Arrays.asList(ALL_ROW_IDS).contains(id) || id.startsWith(Plugins.ROW_PREFIX);
            if (known && !out.contains(id)) out.add(id);
        }
        return out;
    }

    private List<String> fullRowOrder() {
        List<String> order = shownRows();
        for (String id : ALL_ROW_IDS) if (!order.contains(id)) order.add(id);
        return order;
    }

    private void saveRows(List<String> order, List<String> shown) {
        List<String> out = new ArrayList<>();
        for (String id : order) if (shown.contains(id)) out.add(id);
        Prefs.of(this).edit().putString(Prefs.HOME_ROWS, TextUtils.join(",", out)).apply();
    }

    private int rowOrderIndex() {
        List<String> order = new ArrayList<>(fullRowOrder());
        order.retainAll(Arrays.asList(ROW_IDS)); // Get apps isn't part of the three-row order
        for (int i = 0; i < ROW_ORDERS.length; i++) {
            boolean same = true;
            for (int k = 0; k < 3; k++) if (!ROW_IDS[ROW_ORDERS[i][k]].equals(order.get(k))) same = false;
            if (same) return i;
        }
        return 0;
    }

    private static String[] orderNames() {
        String[] out = new String[ROW_ORDERS.length];
        for (int i = 0; i < out.length; i++) {
            int[] o = ROW_ORDERS[i];
            out[i] = ROW_SHORT[o[0]] + " · " + ROW_SHORT[o[1]] + " · " + ROW_SHORT[o[2]];
        }
        return out;
    }

    /** A time in 30-minute steps; left/right (or Select) changes it. */
    private void timeChoice(String title, final String key, int def) {
        final String[] names = new String[48];
        for (int i = 0; i < 48; i++) names[i] = Prefs.hhmm(i * 30);
        choice(title, names,
                () -> Math.max(0, Math.min(47, Prefs.of(this).getInt(key, def) / 30)),
                i -> Prefs.of(this).edit().putInt(key, i * 30).apply());
    }

    /**
     * A wallpaper theme. On means "show it", and its pictures are downloaded from the wallpapers-v1
     * release when it's switched on and deleted when it's switched off. A theme with nothing
     * downloaded shows as off even though its setting defaults to on, so a fresh install offers the
     * download instead of claiming pictures it doesn't have.
     */
    private void themeToggle(final String theme) {
        final String key = Prefs.SS_THEME + theme;
        LinearLayout r = row(LinearLayout.HORIZONTAL);
        LinearLayout text = labels(Character.toUpperCase(theme.charAt(0)) + theme.substring(1) + " wallpapers", "");
        final TextView sub = (TextView) text.getChildAt(1);
        r.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final SwitchView sw = new SwitchView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(52), dp(32));
        lp.leftMargin = dp(16);
        r.addView(sw, lp);

        final BoolGet isOn = () -> Prefs.of(this).getBoolean(key, true)
                && (Wallpapers.busy(theme) || Wallpapers.has(this, theme));
        final Runnable show = () -> {
            sw.setChecked(isOn.get());
            sub.setText(themeSummary(theme, isOn.get()));
        };
        show.run();
        themeRows.put(theme, show);
        r.setOnClickListener(v -> {
            boolean on = !isOn.get();
            Prefs.of(this).edit().putBoolean(key, on).apply(); // the screensaver reads this, as before
            if (on) Wallpapers.download(this, theme);
            else Wallpapers.remove(this, theme);
            show.run();
            if (previewSub != null) previewSub.setText(mediaSummary());
        });
    }

    /** "Downloading 3 of 11…", "8 pictures · 4 live · 27 MB", "Not downloaded · 30 MB · select to download". */
    private String themeSummary(String theme, boolean on) {
        String status = Wallpapers.status(theme);
        if (status != null) return status;
        List<File> files = Wallpapers.files(this, theme);
        List<Wallpapers.Item> known = Wallpapers.known(this, theme);
        if (files.isEmpty()) {
            long size = 0;
            if (known != null) for (Wallpapers.Item i : known) size += i.size;
            return "Not downloaded" + (size > 0 ? " · " + Wallpapers.mb(size) : "") + " · select to download";
        }
        if (!on) {
            int have = known == null ? 0 : Wallpapers.present(this, known);
            return known != null && have < known.size()
                    ? "Got " + have + " of " + known.size() + " · select to finish downloading"
                    : "Hidden · select to show";
        }
        int videos = 0;
        long bytes = 0;
        for (File f : files) {
            if (ScreensaverView.isVideo(f)) videos++;
            bytes += f.length();
        }
        int images = files.size() - videos;
        return images + (images == 1 ? " picture · " : " pictures · ") + videos + " live · " + Wallpapers.mb(bytes);
    }

    private void widgetToggle(String title, String subtitle, String key) {
        toggle(title, subtitle, () -> Prefs.on(this, key), v -> Prefs.of(this).edit().putBoolean(key, v).apply());
    }

    /** The sleep timer's length in minutes, or 0 when it's off. */
    private int sleepMinutes() {
        return Prefs.sleepAt(this) == 0 ? 0 : Prefs.of(this).getInt(Prefs.SLEEP_MINUTES, 0);
    }

    /** Starts the sleep timer for m minutes from now (0 = off). */
    private void setSleep(int m) {
        Prefs.of(this).edit()
                .putLong(Prefs.SLEEP_AT, m == 0 ? 0 : System.currentTimeMillis() + m * 60_000L)
                .putInt(Prefs.SLEEP_MINUTES, m).apply();
        if (!HomeGuard.refreshSleep() && m != 0) {
            Toast.makeText(this, "Turn on the MaterialTV accessibility service to use this", Toast.LENGTH_LONG).show();
        }
    }

    private static String minutes(int m) {
        return m < 60 ? m + " min" : m % 60 == 0 ? m / 60 + " h" : m / 60 + " h " + m % 60;
    }

    /** Off / 15 min ... 2 h. Left and right change it; the row shows when the stick will sleep. */
    private void sleepTimer() {
        LinearLayout r = row(LinearLayout.HORIZONTAL);
        LinearLayout text = labels("Sleep in", "Off");
        final TextView sub = (TextView) text.getChildAt(1);
        r.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView value = text("", 17, M3.PRIMARY, M3.MEDIUM);
        r.addView(value);

        final int[] idx = { 0 };
        int chosen = sleepMinutes();
        for (int i = 0; i < SLEEP_MINUTES.length; i++) if (SLEEP_MINUTES[i] == chosen) idx[0] = i;
        final Runnable show = () -> {
            int m = SLEEP_MINUTES[idx[0]];
            long when = Prefs.sleepAt(this);
            value.setText("‹  " + (m == 0 ? "Off" : minutes(m)) + "  ›");
            sub.setText(when == 0 ? "Fire TV goes to sleep when the time is up"
                    : "Sleeps at " + android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date(when)));
        };
        final IntSet apply = i -> {
            idx[0] = (i + SLEEP_MINUTES.length) % SLEEP_MINUTES.length;
            setSleep(SLEEP_MINUTES[idx[0]]);
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
            Toast.makeText(this, "Turn on the MaterialTV accessibility service to use this", Toast.LENGTH_LONG).show();
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
        if (media.isEmpty()) return "Slideshow: no pictures yet. Turn on a wallpaper theme to download some";
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

    /** The page title, with the page it belongs to above it in small type (null on the top page). */
    private void title(String parent, String s) {
        if (parent != null) {
            TextView p = text(parent, 15, M3.ON_SURFACE_VAR, M3.REGULAR);
            p.setPadding(dp(12), 0, 0, dp(2));
            list.addView(p);
        }
        TextView t = text(s, 30, M3.ON_SURFACE, M3.REGULAR);
        t.setPadding(dp(12), 0, 0, parent == null ? 0 : dp(4));
        list.addView(t);
    }

    private void space(int dpHeight) {
        list.addView(new View(this), new LinearLayout.LayoutParams(1, dp(dpHeight)));
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

    private void addFocusable(View v) {
        focusables.add(v);
        if (firstRow == null) firstRow = v;
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
        addFocusable(r);
        return r;
    }

    /**
     * A quick-toggle chip: name over its state, filled while on, ringed while focused. Left and right
     * move between chips, Down goes to the list.
     */
    private void chip(LinearLayout bar, String title, Supplier<String> value, BoolGet on, Runnable action) {
        final LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER);
        c.setFocusable(true);
        c.setClickable(true);
        c.setMinimumHeight(dp(72));
        c.setPadding(dp(8), dp(10), dp(8), dp(10));
        final TextView t = text(title, 15, M3.ON_SURFACE, M3.MEDIUM);
        final TextView v = text("", 14, M3.ON_SURFACE_VAR, M3.REGULAR);
        for (TextView tv : new TextView[] { t, v }) {
            tv.setSingleLine(true);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            tv.setGravity(Gravity.CENTER);
            c.addView(tv);
        }
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        c.setBackground(bg);
        final Runnable show = () -> {
            boolean o = on.get(), focused = c.isFocused();
            v.setText(value.get());
            bg.setColor(o ? M3.SECONDARY_CONT : focused ? M3.SURFACE_HIGH : 0x00000000);
            bg.setStroke(focused ? dp(3) : dp(1), focused ? M3.PRIMARY : M3.OUTLINE_VAR);
            t.setTextColor(o ? M3.ON_SECONDARY_CONT : M3.ON_SURFACE);
            v.setTextColor(o ? M3.ON_SECONDARY_CONT : M3.ON_SURFACE_VAR);
        };
        c.setOnFocusChangeListener((x, has) -> show.run());
        c.setOnClickListener(x -> { action.run(); show.run(); });
        show.run();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = lp.rightMargin = dp(4);
        bar.addView(c, lp);
        addFocusable(c);
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

    /** A row that opens something. Returns its subtitle, if it has one, so it can be updated. */
    private TextView link(String title, String subtitle, Runnable action) {
        LinearLayout r = row(LinearLayout.HORIZONTAL);
        LinearLayout text = labels(title, subtitle);
        r.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(text("›", 28, M3.ON_SURFACE_VAR, M3.REGULAR));
        r.setOnClickListener(v -> action.run());
        return subtitle == null ? null : (TextView) text.getChildAt(1);
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
        choice(title, names, get, set, null);
    }

    /** Left/right (or Select) steps through names. With swatch, a colour dot shows each option's colour. */
    private void choice(String title, String[] names, IntGet get, IntSet set, IntUnaryOperator swatch) {
        LinearLayout r = row(LinearLayout.HORIZONTAL);
        r.addView(labels(title, null), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final GradientDrawable dot = swatch == null ? null : M3.circle(swatch.applyAsInt(get.get()));
        if (dot != null) {
            dot.setStroke(dp(1), M3.OUTLINE);
            View d = new View(this);
            d.setBackground(dot);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(20), dp(20));
            lp.rightMargin = dp(10);
            r.addView(d, lp);
        }
        final TextView value = text("‹  " + names[get.get()] + "  ›", 17, M3.PRIMARY, M3.MEDIUM);
        r.addView(value);
        final IntSet go = i -> {
            set.set((i + names.length) % names.length);
            int now = get.get();
            value.setText("‹  " + names[now] + "  ›");
            if (dot != null) dot.setColor(swatch.applyAsInt(now));
        };
        r.setOnClickListener(v -> go.set(get.get() + 1));
        r.setOnKeyListener((v, code, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { go.set(get.get() + 1); return true; }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) { go.set(get.get() - 1); return true; }
            return false;
        });
    }

    private void slider(String title, int min, int max, int stepSize, IntGet get, IntSet set,
                        final IntFunction<String> fmt) {
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
