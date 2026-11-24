package app.m3home;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An app's full-screen page in Get apps, for everything that doesn't install through the Amazon
 * Appstore: GitHub and link apps, web apps, plugins and your own apps. Left: the icon, name,
 * where it comes from, chips, and Install / Update / Open (with progress while it installs) and
 * the other actions. Right, scrolling: the description, what's new, screenshots (Select shows
 * them full screen), what a plugin adds or what a web app may do, and the details. Back closes.
 * The page follows installs and uninstalls as they happen.
 */
public class StoreDetailActivity extends Activity {
    /** The Store.Entry id to show. */
    static final String EXTRA_ID = "id";

    private static final int THUMB_PX = 640, VIEWER_PX = 1280;
    private static final Pattern PERCENT = Pattern.compile("(\\d{1,3})%");

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor(r -> new Thread(r, "m3home-store-page"));
    private final ExecutorService shots = Executors.newSingleThreadExecutor(r -> new Thread(r, "m3home-store-shots"));

    private String id;
    private Store.Entry entry;
    private Store.Item item;
    private Store.Release release;
    private Plugins.Plugin plugin;      // installed plugin, or the manifest's preview
    private boolean pluginInstalled;
    private WebApps.App webapp;         // installed web app, or the manifest's preview
    private String previewProblem;      // why the plugin / web app manifest couldn't be read
    private boolean previewed, fetchedRelease;
    private String installedVersion;
    private int minSdk = -1;
    private String pluginBusy;          // "Adding…" while a plugin job runs
    private boolean wasBusy;

    private List<String> shotUrls = new ArrayList<>();
    private FrameLayout root;
    private LinearLayout leftPane, rightPane, aboutBox, extraBox, detailsBox;
    private Flow actions;
    private ScrollView rightScroll;
    private TextView primary, status;
    private Bar bar;
    private Flow chips;
    private View lastLeft, lastRight;
    private final List<View> thumbViews = new ArrayList<>();
    private final List<Bitmap> thumbs = new ArrayList<>();
    private FrameLayout viewer;
    private ImageView viewerImage;
    private TextView viewerCaption;
    private Bitmap viewerBitmap;
    private int viewerIndex = -1;
    private int builtRev, builtPrimary, builtBackground;
    private float builtScale;
    private boolean firstRender = true;

    private final Store.Listener storeListener = changed -> {
        if (isFinishing() || isDestroyed()) return;
        if (changed != null && !changed.equals(id)) return;
        boolean busy = Store.status(id) != null;
        showProgress();
        if (changed == null || (wasBusy && !busy)) load(false); // finished, failed, or the list changed
        wasBusy = busy;
    };

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            Uri data = i.getData();
            String pkg = data == null ? null : data.getSchemeSpecificPart();
            if (pkg == null || item == null || pkg.equals(item.pkg) || pkg.equals(entry.pkg) || entry.custom) load(false);
        }
    };

    private int dp(float v) { return M3.dp(this, v); }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(M3.scaled(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        M3.init(this);
        id = getIntent().getStringExtra(EXTRA_ID);
        entry = id == null ? null : Store.find(this, id);
        if (entry == null || entry.appstore()) {
            finish();
            return;
        }
        wasBusy = Store.status(id) != null;
        shotUrls = new ArrayList<>(entry.screenshots); // fixed for this page, even if the catalog refreshes
        build();
        Store.addListener(storeListener);
        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pf.addDataScheme("package");
        registerReceiver(packages, pf);
        load(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (entry == null) return;
        if (lookMoved()) return;
        if (!firstRender) load(false); // back from Android's installer or uninstaller, or the app itself
    }

    @Override
    protected void onDestroy() {
        Store.removeListener(storeListener);
        try { unregisterReceiver(packages); } catch (IllegalArgumentException ignored) { }
        work.shutdownNow();
        shots.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (viewer != null) { closeViewer(); return; }
        super.onBackPressed();
    }

    /** True (and the page redrawn) when the colours or the text size changed since it was built. */
    private boolean lookMoved() {
        if (Prefs.lookRev(this) == builtRev) return false;
        if (Prefs.textScale(this) != builtScale) { recreate(); return true; }
        M3.init(this);
        builtRev = Prefs.lookRev(this);
        if (M3.PRIMARY == builtPrimary && M3.BACKGROUND == builtBackground) return false;
        build();
        render();
        return true;
    }

    // ---- data ---------------------------------------------------------------------------

    /** Reads the app's state (and, the first time, its release and manifest) off the main thread, then renders. */
    private void load(final boolean first) {
        final Context app = getApplicationContext();
        final boolean fetch = !fetchedRelease, preview = !previewed;
        fetchedRelease = true;
        try {
            work.execute(() -> {
                Store.Entry e = Store.find(app, id);
                if (e == null) { ui.post(this::finish); return; } // removed meanwhile
                Store.Item it = Store.item(app, id);
                Store.Release r = Store.release(app, e);
                String ver = null;
                int min = -1;
                String pkg = it == null ? null : it.pkg;
                if (pkg != null && WebApps.idOf(pkg) == null) {
                    try {
                        PackageInfo pi = app.getPackageManager().getPackageInfo(pkg, 0);
                        ver = pi.versionName;
                        ApplicationInfo ai = pi.applicationInfo;
                        if (ai != null) min = ai.minSdkVersion;
                    } catch (PackageManager.NameNotFoundException ignored) {
                        // Not installed.
                    }
                }
                Plugins.Plugin p = null;
                boolean pIn = false;
                if (e.plugin()) {
                    for (Plugins.Plugin x : Plugins.all(app)) if (e.url.equals(x.url)) { p = x; pIn = true; }
                }
                WebApps.App w = e.webapp() ? WebApps.byManifest(app, e.url) : null;
                final Store.Entry fe = e;
                final Store.Item fit = it;
                final Store.Release fr = r;
                final String fver = ver;
                final int fmin = min;
                final Plugins.Plugin fp = p;
                final boolean fpIn = pIn;
                final WebApps.App fw = w;
                ui.post(() -> {
                    if (isDestroyed()) return;
                    entry = fe;
                    item = fit;
                    release = fr;
                    installedVersion = fver;
                    minSdk = fmin;
                    pluginInstalled = fpIn;
                    if (fp != null || fpIn) plugin = fp;
                    if (fw != null) webapp = fw;
                    render();
                });
                if (preview) preview(app, e, p, w);
                if (fetch && e.repo != null) {
                    Store.Release fresh = Store.fetchRelease(app, e);
                    ui.post(() -> {
                        if (isDestroyed()) return;
                        release = fresh;
                        render();
                    });
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Closing.
        }
    }

    /** A plugin's or web app's manifest, read from the web when it isn't installed (what it adds, what it may do). */
    private void preview(Context app, Store.Entry e, Plugins.Plugin installedPlugin, WebApps.App installedApp) {
        previewed = true;
        if (!(e.plugin() || e.webapp()) || installedPlugin != null || installedApp != null) return;
        String body = Store.fetchText(e.url);
        Plugins.Plugin p = null;
        WebApps.App w = null;
        String problem = null;
        if (body == null) {
            problem = "Couldn't read its manifest: " + Store.lastProblem(e.url);
        } else if (e.plugin()) {
            p = Plugins.parse(body, e.url);
            if (p == null) problem = "Its manifest isn't a MaterialTV plugin";
        } else {
            try {
                Object o = WebApps.parse(e.url, new JSONObject(body));
                if (o instanceof WebApps.App) w = (WebApps.App) o; else problem = String.valueOf(o);
            } catch (Exception x) {
                problem = "Its manifest isn't readable";
            }
        }
        final Plugins.Plugin fp = p;
        final WebApps.App fw = w;
        final String fproblem = problem;
        ui.post(() -> {
            if (isDestroyed()) return;
            if (plugin == null) plugin = fp;
            if (webapp == null) webapp = fw;
            previewProblem = fproblem;
            render();
        });
    }

    private boolean installed() { return item != null && item.state != Store.GET; }

    // ---- the frame ----------------------------------------------------------------------

    private void build() {
        builtRev = Prefs.lookRev(this);
        builtScale = Prefs.textScale(this);
        builtPrimary = M3.PRIMARY;
        builtBackground = M3.BACKGROUND;
        thumbViews.clear();
        for (Bitmap b : thumbs) if (b != null) b.recycle();
        thumbs.clear();
        viewer = null;

        root = new FrameLayout(this);
        root.setBackground(new Glow());
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setPadding(dp(48), dp(36), dp(40), 0);
        root.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Left: who it is and what you can do.
        ScrollView leftScroll = new ScrollView(this);
        leftScroll.setVerticalScrollBarEnabled(false);
        leftScroll.setClipToPadding(false);
        leftScroll.setClipChildren(false);
        leftPane = new LinearLayout(this);
        leftPane.setOrientation(LinearLayout.VERTICAL);
        leftPane.setClipChildren(false);
        leftPane.setClipToPadding(false);
        leftPane.setPadding(dp(8), dp(4), dp(8), dp(36));
        leftScroll.addView(leftPane);
        row.addView(leftScroll, new LinearLayout.LayoutParams(dp(356), ViewGroup.LayoutParams.MATCH_PARENT));

        // Right: everything to read.
        rightScroll = new ScrollView(this);
        rightScroll.setVerticalScrollBarEnabled(false);
        rightScroll.setClipToPadding(false);
        rightScroll.setFadingEdgeLength(dp(28));
        rightScroll.setVerticalFadingEdgeEnabled(true);
        rightPane = new LinearLayout(this);
        rightPane.setOrientation(LinearLayout.VERTICAL);
        rightPane.setPadding(0, dp(4), dp(8), dp(40));
        rightScroll.addView(rightPane);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        rl.leftMargin = dp(36);
        row.addView(rightScroll, rl);
        setContentView(root);

        buildLeft();
        aboutBox = column();
        rightPane.addView(aboutBox);
        if (!shotUrls.isEmpty()) rightPane.addView(screenshotStrip());
        extraBox = column();
        rightPane.addView(extraBox);
        detailsBox = column();
        rightPane.addView(detailsBox);

        root.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus, now) -> {
            if (now == null) return;
            if (inside(now, rightPane)) lastRight = now;
            else if (inside(now, leftPane)) lastLeft = now;
        });
    }

    private LinearLayout column() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        return c;
    }

    private void buildLeft() {
        // The icon: a big rounded square on the app's colour.
        FrameLayout well = new FrameLayout(this);
        final float radius = dp(30);
        well.setBackground(M3.rounded(wellColor(), radius));
        well.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius); }
        });
        well.setClipToOutline(true);
        well.setTag("icon");
        leftPane.addView(well, new LinearLayout.LayoutParams(dp(104), dp(104)));
        fillIcon(well);

        TextView name = text(entry.name, 32, M3.ON_SURFACE, M3.REGULAR);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(0, dp(14), 0, 0);
        leftPane.addView(name);

        TextView from = text(sourceLine(), 15, M3.ON_SURFACE_VAR, M3.REGULAR);
        from.setSingleLine(true);
        from.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        from.setTag("from");
        from.setPadding(0, dp(4), 0, dp(14));
        leftPane.addView(from);

        chips = new Flow(this, dp(8), dp(8));
        leftPane.addView(chips, matchWrap(0));

        primary = button("", true);
        primary.setTag("primary");
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        pl.topMargin = dp(20);
        leftPane.addView(primary, pl);

        bar = new Bar(this);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        bl.topMargin = dp(16);
        bl.leftMargin = bl.rightMargin = dp(4);
        leftPane.addView(bar, bl);
        status = text("", 15, M3.ON_SURFACE_VAR, M3.REGULAR);
        status.setPadding(dp(4), dp(8), dp(4), 0);
        leftPane.addView(status);

        actions = new Flow(this, dp(12), dp(12)); // the other actions, side by side where they fit
        actions.setClipChildren(false);
        actions.setClipToPadding(false);
        leftPane.addView(actions, matchWrap(dp(16)));
    }

    /** Behind a picture icon: the colour taken from it (as on Home). Behind a letter: the primary container. */
    private int wellColor() {
        return item != null && item.icon != null ? item.tint : M3.PRIMARY_CONT;
    }

    private void fillIcon(FrameLayout well) {
        well.removeAllViews();
        Bitmap icon = item != null ? item.icon : null;
        if (icon != null) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageBitmap(icon);
            well.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return;
        }
        String glyph = plugin != null && plugin.icon != null && !Plugins.isPicture(plugin.icon) ? plugin.icon
                : new String(Character.toChars(entry.name.codePointAt(0))).toUpperCase(Locale.ROOT);
        TextView t = text(glyph, 52, M3.ON_PRIMARY_CONT, M3.MEDIUM);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        well.addView(t, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** "github.com/owner/repo", "videolan.org", "By MaterialTV · brick-bread.github.io". */
    private String sourceLine() {
        String host = entry.source();
        if (entry.webapp() || entry.plugin()) {
            try { host = new java.net.URI(entry.url).getHost(); } catch (Exception ignored) { }
            String author = plugin != null ? plugin.author : webapp != null ? webapp.author : "";
            return (author == null || author.isEmpty() ? "" : "By " + author + " · ") + host;
        }
        return host;
    }

    // ---- rendering ----------------------------------------------------------------------

    private void render() {
        if (root == null) return;
        String focusTag = getCurrentFocus() == null ? null : tagOf(getCurrentFocus());
        View well = leftPane.findViewWithTag("icon");
        if (well instanceof FrameLayout) {
            ((GradientDrawable) well.getBackground()).setColor(wellColor());
            fillIcon((FrameLayout) well);
        }
        TextView from = (TextView) leftPane.findViewWithTag("from");
        if (from != null) from.setText(sourceLine());
        renderChips();
        renderActions();
        showProgress();
        renderAbout();
        renderExtra();
        renderDetails();

        if (firstRender) primary.requestFocus();
        else restoreFocus(focusTag);
        firstRender = false;
    }

    /** Puts the focus back on the view with this tag after a redraw (the primary button if it's gone). */
    private void restoreFocus(String focusTag) {
        if (focusTag == null || viewer != null) return;
        View target = root.findViewWithTag(focusTag);
        if (target != null && target.isFocusable() && target.isShown()) {
            if (!target.isFocused()) target.requestFocus();
        } else {
            primary.requestFocus();
        }
    }

    private String tagOf(View v) {
        Object t = v.getTag();
        return t instanceof String ? (String) t : null;
    }

    private void renderChips() {
        chips.removeAllViews();
        if (entry.category != null && !entry.category.isEmpty()) chip(entry.category, false);
        if (entry.plugin()) chip("Plugin", false);
        else if (entry.webapp()) chip("Web app", false);
        else if (entry.custom) chip("Added by you", false);
        else if (entry.license != null) chip("Open source", false);
        String ver = versionShown();
        if (ver != null) chip(shortVersion(ver), false);
        long size = release != null && release.size > 0 ? release.size : item != null ? item.size : -1;
        if (size > 0 && !entry.webapp() && !entry.plugin()) chip(Wallpapers.mb(size), false);
        if (item != null && item.state == Store.UPDATE) chip("Update available", true);
        else if (installed()) chip(entry.plugin() ? (plugin != null && Plugins.enabled(this, plugin) ? "Added · On" : "Added · Off") : "Installed", true);
    }

    /** "v1.104.7" for "1.104.7-tfb3b9b296-g226f029a3": short enough for a chip or a button. */
    private static String shortVersion(String v) {
        int dash = v.indexOf('-');
        if (v.length() > 14 && dash > 0) v = v.substring(0, dash);
        return v.matches("[0-9].*") ? "v" + v : v;
    }

    /** The version to show: the latest release, else the web app's or plugin's own. */
    private String versionShown() {
        if (release != null && !release.version.isEmpty()) return release.version;
        if (item != null && item.latest != null && !item.latest.isEmpty()) return item.latest;
        if (webapp != null && webapp.version != null && !webapp.version.isEmpty()) return webapp.version;
        if (plugin != null && plugin.version != null && !plugin.version.isEmpty()) return plugin.version;
        return null;
    }

    private void chip(String label, boolean accent) {
        TextView c = text(label, 14, accent ? M3.ON_PRIMARY_CONT : M3.ON_SURFACE_VAR, M3.MEDIUM);
        c.setSingleLine(true);
        c.setEllipsize(TextUtils.TruncateAt.END);
        c.setMaxWidth(dp(320));
        c.setPadding(dp(14), dp(6), dp(14), dp(7));
        c.setBackground(accent ? M3.rounded(M3.PRIMARY_CONT, dp(10))
                : M3.outlined(M3.alpha(M3.SURFACE_LOW, 0xB0), M3.OUTLINE_VAR, dp(1), dp(10)));
        chips.addView(c);
    }

    /** The primary button's label and job, then the other actions under it. */
    private void renderActions() {
        actions.removeAllViews();
        boolean busy = Store.status(id) != null || pluginBusy != null;
        boolean on = installed();
        final String pkg = item == null ? null : item.pkg;

        if (entry.plugin()) {
            renderPluginActions(busy);
            return;
        }
        if (busy) {
            setPrimary(item != null && item.state == Store.UPDATE ? "Updating…" : "Installing…",
                    () -> Toast.makeText(this, entry.name + " is installing", Toast.LENGTH_SHORT).show());
        } else if (item != null && item.state == Store.UPDATE) {
            String to = versionShown();
            setPrimary("Update" + (to == null ? "" : " to " + shortVersion(to)), () -> Store.install(this, entry));
        } else if (on) {
            setPrimary("Open", () -> {
                if (!Store.open(this, pkg)) Toast.makeText(this, "Couldn't open " + entry.name, Toast.LENGTH_SHORT).show();
            });
        } else {
            setPrimary("Install", () -> Store.install(this, entry));
        }
        if (on && item.state == Store.UPDATE) {
            action("Open", "open", () -> {
                if (!Store.open(this, pkg)) Toast.makeText(this, "Couldn't open " + entry.name, Toast.LENGTH_SHORT).show();
            });
        }
        if (on && !busy) {
            action("Uninstall", "uninstall", () -> {
                if (entry.webapp()) uninstallWebapp(WebApps.idOf(pkg));
                else uninstall(pkg);
            });
        }
        if (entry.custom) {
            action("Remove from your apps", "remove", () -> {
                Store.removeCustom(this, entry.id);
                Store.listChanged();
                Toast.makeText(this, entry.name + " removed from your apps", Toast.LENGTH_SHORT).show();
                finish();
            });
        }
    }

    private void renderPluginActions(boolean busy) {
        final Plugins.Plugin p = plugin;
        if (busy) {
            setPrimary(pluginBusy != null ? pluginBusy : "Working…", () -> { });
            return;
        }
        if (!pluginInstalled || p == null) {
            setPrimary("Add plugin", () -> pluginJob("Adding…", app -> Plugins.install(app, entry.url)));
            return;
        }
        final boolean enabled = Plugins.enabled(this, p);
        setPrimary(enabled ? "Turn off" : "Turn on", () -> pluginJob(enabled ? "Turning off…" : "Turning on…", app -> {
            Plugins.setEnabled(app, p.id, !enabled);
            return p.name + (enabled ? " is off" : " is on");
        }));
        if (p.theme != null) {
            action("Apply theme", "theme", () -> pluginJob("Applying theme…", app -> Plugins.applyTheme(app, p)));
        }
        action("Plugin settings", "settings", () -> startActivity(new Intent(this, SettingsActivity.class)
                .putExtra(SettingsActivity.EXTRA_PAGE, SettingsActivity.PLUGINS)));
        if (!p.android()) {
            action("Remove plugin", "remove", () -> {
                List<CharSequence> opts = new ArrayList<>();
                opts.add("Remove " + p.name);
                opts.add("Keep it");
                M3Menu.show(this, "Remove " + p.name + "?", "Takes off its rows, widgets and shortcuts.", opts, which -> {
                    if (which != 0) return;
                    Plugins.remove(this, p.id);
                    Toast.makeText(this, p.name + " removed", Toast.LENGTH_SHORT).show();
                    pluginInstalled = false;
                    Store.listChanged();
                    load(false);
                });
            });
        }
    }

    private interface Job { String run(Context app); }

    /** Runs a plugin job in the background with progress on the page, then shows its message and reloads. */
    private void pluginJob(String label, final Job job) {
        if (pluginBusy != null) return;
        pluginBusy = label;
        renderActions();
        showProgress();
        final Context app = getApplicationContext();
        try {
            work.execute(() -> {
                String msg;
                try {
                    msg = job.run(app);
                } catch (Exception e) {
                    msg = "That didn't work";
                }
                final String m = msg;
                ui.post(() -> {
                    Toast.makeText(app, m, Toast.LENGTH_LONG).show();
                    if (isDestroyed()) return;
                    pluginBusy = null;
                    if (lookMoved()) { load(false); return; }
                    load(false);
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            pluginBusy = null;
        }
    }

    private void setPrimary(String label, Runnable run) {
        primary.setText(label);
        primary.setOnClickListener(v -> run.run());
    }

    private void action(String label, String tag, Runnable run) {
        TextView b = button(label, false);
        b.setTag("action:" + tag);
        b.setOnClickListener(v -> run.run());
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setPadding(dp(20), 0, dp(20), 0);
        b.setMinWidth(dp(150));
        actions.addView(b, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));
    }

    /** The progress bar and status line: install progress, a plugin job, or the last problem. */
    private void showProgress() {
        if (bar == null) return;
        String busy = Store.status(id);
        if (busy == null) busy = pluginBusy;
        String problem = Store.problem(id);
        if (busy != null) {
            Matcher m = PERCENT.matcher(busy);
            bar.set(m.find() ? Math.min(100, Integer.parseInt(m.group(1))) / 100f : -1f);
            bar.setVisibility(View.VISIBLE);
            status.setTextColor(M3.ON_SURFACE_VAR);
            boolean downloading = m.find(0) || busy.endsWith("MB") || busy.endsWith("KB") || busy.endsWith("GB");
            status.setText(busy.equals("Confirm on screen") ? "Confirm on screen to finish installing"
                    : downloading ? "Downloading · " + busy : busy);
            status.setVisibility(View.VISIBLE);
        } else {
            bar.setVisibility(View.GONE);
            if (problem != null) {
                boolean cancelled = "Cancelled".equals(problem);
                status.setTextColor(cancelled ? M3.ON_SURFACE_VAR : M3.ERROR);
                status.setText(cancelled ? "Last try was cancelled" : "Last try: " + problem);
                status.setVisibility(View.VISIBLE);
            } else if (previewProblem != null && !installed()) {
                status.setTextColor(M3.ERROR);
                status.setText(previewProblem);
                status.setVisibility(View.VISIBLE);
            } else {
                status.setVisibility(View.GONE);
            }
        }
        // An install started or ended: swap the buttons (Install -> Installing… -> Open), keeping the focus.
        if (primary.getText().length() > 0 && (busy != null) != primary.getText().toString().endsWith("…")) {
            String focusTag = getCurrentFocus() == null ? null : tagOf(getCurrentFocus());
            renderActions();
            restoreFocus(focusTag);
        }
    }

    // ---- the right side -----------------------------------------------------------------

    private void renderAbout() {
        aboutBox.removeAllViews();
        LinearLayout card = card(aboutBox, "About");
        String lede = entry.description == null ? "" : entry.description;
        if (entry.custom && !entry.webapp()) lede = "Added by you from " + entry.source() + ".";
        String body = entry.longDescription;
        if (body.isEmpty() && plugin != null && !plugin.description.isEmpty()) body = plugin.description;
        if (body.isEmpty() && webapp != null && webapp.description != null && !webapp.description.isEmpty()) body = webapp.description;
        if (!lede.isEmpty()) {
            TextView l = text(lede, 20, M3.ON_SURFACE, M3.MEDIUM);
            l.setLineSpacing(dp(2), 1f);
            readableBlock(card, l, "about:lede");
        }
        if (!body.isEmpty() && !body.equals(lede)) readable(card, body, "about");
        if (entry.custom && body.isEmpty()) {
            readable(card, "MaterialTV downloads it straight from " + (entry.repo != null ? "the repo's latest GitHub release" : "the link you gave")
                    + " and checks it's an app before Android asks you to confirm.", "about:custom");
        }

        if (release != null && !release.notes.isEmpty()) {
            StringBuilder head = new StringBuilder("What's new");
            LinearLayout notes = card(aboutBox, head.toString());
            String when = date(release.published);
            String ver = release.version.isEmpty() ? null : release.version;
            if (ver != null || when != null) {
                TextView meta = text((ver == null ? "" : ver) + (ver != null && when != null ? " · " : "") + (when == null ? "" : "Released " + when),
                        15, M3.ON_SURFACE_VAR, M3.MEDIUM);
                meta.setPadding(dp(14), 0, dp(14), dp(4));
                notes.addView(meta);
            }
            readable(notes, release.notes, "notes");
        }
    }

    private void renderExtra() {
        extraBox.removeAllViews();
        if (entry.plugin()) {
            LinearLayout card = card(extraBox, "What it adds");
            if (plugin == null) {
                readable(card, previewProblem != null ? previewProblem : "Reading the plugin…", "adds:wait");
                return;
            }
            List<String> lines = new ArrayList<>();
            if (plugin.android()) lines.add("Widgets and rows from the app " + plugin.pkg);
            for (Plugins.Feed f : plugin.widgets) lines.add("• Widget: " + f.title + " (updates every " + minutes(f.refreshMinutes) + ")");
            for (Plugins.Feed f : plugin.rows) lines.add("• Row on Home: " + f.title + " (updates every " + minutes(f.refreshMinutes) + ")");
            if (plugin.theme != null) lines.add("• A theme: " + themeSummary(plugin.theme) + ". Applying it is up to you.");
            int hooks = 0;
            for (Plugins.Action a : plugin.actions) {
                String kind = a.type.equals("webhook") ? "calls " + host(a.url) : a.type.equals("app") ? "opens an app" : "opens " + host(a.url);
                lines.add("• Shortcut: " + a.title + " (" + kind + ")");
                if (a.type.equals("webhook")) hooks++;
            }
            if (lines.isEmpty()) lines.add("Nothing yet");
            readable(card, TextUtils.join("\n", lines), "adds");
            String safety = "Plugins add data, not code: nothing from a plugin runs on your TV.";
            if (hooks > 0) safety += " Shortcuts that call a web address (webhooks) ask you before they run the first time, and again if they change.";
            TextView s = text(safety, 15, M3.ON_SURFACE_VAR, M3.REGULAR);
            s.setLineSpacing(dp(2), 1f);
            readableBlock(card, s, "adds:safety");
        } else if (entry.webapp()) {
            LinearLayout card = card(extraBox, "What it may do");
            if (webapp == null) {
                readable(card, previewProblem != null ? previewProblem : "Reading the web app…", "perm:wait");
                return;
            }
            List<String> lines = new ArrayList<>();
            lines.add(webapp.may("network") ? "• Load from any https address (APIs, pictures, video)"
                    : "• Load only from its own sites, listed below");
            lines.add(webapp.may("storage") ? "• Keep its data on this TV" : "• Keeps nothing on this TV between visits");
            lines.add(webapp.may("media") ? "• Start audio and video by itself" : "• Play audio and video only when you press Select");
            lines.add("• Never your camera, microphone, location, notifications or files");
            readable(card, TextUtils.join("\n", lines), "perm");
            TextView h = text("Its sites", 15, M3.PRIMARY, M3.MEDIUM);
            h.setPadding(dp(14), dp(14), dp(14), 0);
            card.addView(h);
            readable(card, TextUtils.join("\n", webapp.origins), "origins");
        }
    }

    private void renderDetails() {
        detailsBox.removeAllViews();
        LinearLayout card = card(detailsBox, "Details");
        LinearLayout rows = column();
        String pkg = item != null && item.pkg != null && WebApps.idOf(item.pkg) == null ? item.pkg : entry.pkg;
        if (pkg != null) detail(rows, "Package", pkg);
        if (entry.plugin() && plugin != null) detail(rows, "Plugin id", plugin.id);
        if (entry.webapp() && webapp != null) detail(rows, "Web app id", webapp.id);
        if (entry.repo != null) detail(rows, "Source", "https://github.com/" + entry.repo);
        else if (entry.webapp() || entry.plugin()) detail(rows, "Manifest", entry.url);
        else if (entry.url != null) detail(rows, "Download", entry.url);
        if (entry.website != null) detail(rows, "Website", entry.website);
        if (entry.license != null) detail(rows, "Licence", entry.license);
        String ver = versionShown();
        if (ver != null) detail(rows, "Latest version", ver);
        if (installedVersion != null && !installedVersion.isEmpty()) detail(rows, "Installed version", installedVersion);
        else if (entry.webapp() && webapp != null && installed() && !webapp.version.isEmpty()) detail(rows, "Installed version", webapp.version);
        if (minSdk > 0) detail(rows, "Needs", android(minSdk));
        String when = release == null ? null : date(release.published);
        if (when != null) detail(rows, "Last updated", when);
        long size = release != null && release.size > 0 ? release.size : item != null ? item.size : -1;
        if (size > 0) detail(rows, "Download size", Wallpapers.mb(size));
        if (!entry.plugin() && !entry.webapp()) {
            detail(rows, "Installs", "Downloaded by MaterialTV, checked, then Android asks you to confirm");
        }
        readableBlock(card, rows, "details");
    }

    private void detail(LinearLayout rows, String label, String value) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(5), 0, dp(5));
        TextView l = text(label, 15, M3.ON_SURFACE_VAR, M3.REGULAR);
        r.addView(l, new LinearLayout.LayoutParams(dp(150), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView v = text(value, 16, M3.ON_SURFACE, M3.REGULAR);
        v.setMaxLines(2);
        v.setEllipsize(TextUtils.TruncateAt.END);
        r.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        rows.addView(r);
    }

    /** A rounded section with a title; returns the box to add to. */
    private LinearLayout card(LinearLayout parent, String title) {
        LinearLayout c = column();
        c.setBackground(M3.rounded(M3.alpha(M3.SURFACE_LOW, 0xCC), dp(28)));
        c.setPadding(dp(14), dp(20), dp(14), dp(14));
        TextView h = text(title, 15, M3.PRIMARY, M3.MEDIUM);
        h.setPadding(dp(14), 0, dp(14), dp(6));
        c.addView(h);
        LinearLayout.LayoutParams lp = matchWrap(parent.getChildCount() == 0 && parent == aboutBox ? 0 : dp(16));
        parent.addView(c, lp);
        return c;
    }

    /** Text you can scroll through with the remote: each chunk takes focus in turn. */
    private void readable(LinearLayout into, String text, String tag) {
        List<String> parts = chunks(text);
        for (int i = 0; i < parts.size(); i++) {
            TextView t = text(parts.get(i), 17, M3.ON_SURFACE, M3.REGULAR);
            t.setLineSpacing(dp(3), 1f);
            readableBlock(into, t, tag + ":" + i);
        }
    }

    private void readableBlock(LinearLayout into, View v, String tag) {
        v.setFocusable(true);
        v.setTag(tag);
        final GradientDrawable bg = M3.rounded(0x00000000, dp(18));
        v.setBackground(bg);
        v.setPadding(dp(14), dp(8), dp(14), dp(8));
        v.setOnFocusChangeListener((x, has) -> bg.setColor(has ? M3.alpha(M3.SURFACE_HIGHEST, 0xE6) : 0x00000000));
        into.addView(v, matchWrap(dp(2)));
    }

    /** Splits text into blocks of a few lines each, at paragraph, line or sentence ends. */
    private static List<String> chunks(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String line : text.split("\n")) {
            List<String> pieces = new ArrayList<>();
            if (line.length() > 360) {
                StringBuilder s = new StringBuilder();
                for (String sentence : line.split("(?<=[.!?])\\s+")) {
                    if (s.length() > 0 && s.length() + sentence.length() > 300) { pieces.add(s.toString()); s.setLength(0); }
                    if (s.length() > 0) s.append(' ');
                    s.append(sentence);
                }
                if (s.length() > 0) pieces.add(s.toString());
            } else {
                pieces.add(line);
            }
            for (String piece : pieces) {
                boolean blank = piece.trim().isEmpty();
                if (cur.length() > 0 && (cur.length() + piece.length() > 320 || (blank && cur.length() > 160))) {
                    out.add(cur.toString().trim());
                    cur.setLength(0);
                    if (blank) continue;
                }
                if (cur.length() > 0) cur.append('\n');
                cur.append(piece);
            }
        }
        if (cur.toString().trim().length() > 0) out.add(cur.toString().trim());
        return out;
    }

    // ---- screenshots --------------------------------------------------------------------

    private View screenshotStrip() {
        LinearLayout card = column();
        card.setBackground(M3.rounded(M3.alpha(M3.SURFACE_LOW, 0xCC), dp(28)));
        card.setPadding(0, dp(20), 0, dp(18));
        TextView h = text("Screenshots", 15, M3.PRIMARY, M3.MEDIUM);
        h.setPadding(dp(28), 0, dp(28), dp(4));
        card.addView(h);

        HorizontalScrollView strip = new HorizontalScrollView(this);
        strip.setHorizontalScrollBarEnabled(false);
        strip.setClipToPadding(false);
        strip.setPadding(dp(28), dp(10), dp(28), dp(10));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        strip.addView(row);
        card.addView(strip);

        for (int i = 0; i < shotUrls.size(); i++) {
            final int index = i;
            final FrameLayout t = new FrameLayout(this);
            t.setFocusable(true);
            t.setClickable(true);
            t.setTag("shot:" + i);
            final float r = dp(18);
            t.setBackground(M3.rounded(M3.SURFACE_HIGH, r));
            t.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); }
            });
            t.setClipToOutline(true);
            final GradientDrawable ring = M3.outlined(0x00000000, 0x00000000, dp(3), r);
            t.setForeground(ring);
            TextView wait = text("Loading…", 14, M3.ON_SURFACE_VAR, M3.REGULAR);
            wait.setGravity(Gravity.CENTER);
            t.addView(wait, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            t.setOnFocusChangeListener((v, has) -> {
                ring.setStroke(dp(3), has ? M3.ON_SURFACE : 0x00000000);
                v.animate().scaleX(has ? 1.05f : 1f).scaleY(has ? 1.05f : 1f).setDuration(150).start();
            });
            t.setOnClickListener(v -> openViewer(index));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(120), dp(200));
            if (i > 0) lp.leftMargin = dp(16);
            row.addView(t, lp);
            thumbViews.add(t);
            thumbs.add(null);
        }
        LinearLayout.LayoutParams lp = matchWrap(dp(16));
        card.setLayoutParams(lp);
        loadThumbs();
        return card;
    }

    /** Downloads the thumbnails one at a time, in order. */
    private void loadThumbs() {
        final Context app = getApplicationContext();
        final List<String> urls = new ArrayList<>(shotUrls);
        final List<View> views = new ArrayList<>(thumbViews);
        for (int i = 0; i < urls.size(); i++) {
            final int index = i;
            try {
                shots.execute(() -> {
                    final Bitmap b = Store.screenshot(app, urls.get(index), THUMB_PX);
                    ui.post(() -> showThumb(views, index, b));
                });
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                return;
            }
        }
    }

    private void showThumb(List<View> views, int index, Bitmap b) {
        if (isDestroyed() || views != null && (index >= thumbViews.size() || thumbViews.get(index) != views.get(index))) {
            if (b != null) b.recycle(); // the page was rebuilt meanwhile
            return;
        }
        FrameLayout t = (FrameLayout) thumbViews.get(index);
        t.removeAllViews();
        if (b == null) {
            TextView none = text("Couldn't load", 14, M3.ON_SURFACE_VAR, M3.REGULAR);
            none.setGravity(Gravity.CENTER);
            t.addView(none, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return;
        }
        thumbs.set(index, b);
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setImageBitmap(b);
        t.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ViewGroup.LayoutParams lp = t.getLayoutParams();
        int h = dp(200);
        lp.width = Math.max(dp(90), Math.min(dp(400), Math.round(h * b.getWidth() / (float) Math.max(1, b.getHeight()))));
        t.setLayoutParams(lp);
        if (viewer != null && viewerIndex == index && viewerBitmap == null) viewerImage.setImageBitmap(b);
    }

    private void openViewer(int index) {
        if (shotUrls.isEmpty()) return;
        if (viewer == null) {
            viewer = new FrameLayout(this);
            viewer.setBackgroundColor(M3.alpha(M3.BACKGROUND, 0xF5));
            viewer.setClickable(true);
            viewer.setFocusable(true);
            viewerImage = new ImageView(this);
            viewerImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
            FrameLayout.LayoutParams il = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            il.setMargins(dp(48), dp(32), dp(48), dp(72));
            viewer.addView(viewerImage, il);
            viewerCaption = text("", 16, M3.ON_SURFACE_VAR, M3.MEDIUM);
            viewerCaption.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            cl.bottomMargin = dp(26);
            viewer.addView(viewerCaption, cl);
            root.addView(viewer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            viewer.setAlpha(0f);
            viewer.animate().alpha(1f).setDuration(180).start();
            viewer.requestFocus();
        }
        showInViewer(index);
    }

    private void showInViewer(final int index) {
        viewerIndex = index;
        int n = shotUrls.size();
        viewerCaption.setText(entry.name + " · " + (index + 1) + " of " + n
                + (n > 1 ? "   ·   Left and right for more" : "") + "   ·   Back to close");
        viewerImage.setImageBitmap(index < thumbs.size() ? thumbs.get(index) : null);
        dropViewerBitmap();
        final String url = shotUrls.get(index);
        final Context app = getApplicationContext();
        try {
            shots.execute(() -> {
                final Bitmap b = Store.screenshot(app, url, VIEWER_PX);
                ui.post(() -> {
                    if (viewer == null || viewerIndex != index || isDestroyed()) {
                        if (b != null) b.recycle();
                        return;
                    }
                    if (b == null) {
                        if (index >= thumbs.size() || thumbs.get(index) == null) viewerCaption.setText("Couldn't load this picture · Back to close");
                        return;
                    }
                    viewerImage.setImageBitmap(b);
                    dropViewerBitmap();
                    viewerBitmap = b;
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Closing.
        }
    }

    private void dropViewerBitmap() {
        Bitmap old = viewerBitmap;
        viewerBitmap = null;
        if (old != null && !old.isRecycled()) ui.post(old::recycle); // after the view has let go of it
    }

    private void closeViewer() {
        final FrameLayout v = viewer;
        final int index = viewerIndex;
        viewer = null;
        viewerIndex = -1;
        viewerImage.setImageDrawable(null);
        dropViewerBitmap();
        v.animate().alpha(0f).setDuration(150).withEndAction(() -> root.removeView(v)).start();
        if (index >= 0 && index < thumbViews.size()) thumbViews.get(index).requestFocus();
    }

    // ---- keys ---------------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        int code = ev.getKeyCode();
        if (viewer != null) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) return super.dispatchKeyEvent(ev);
            if (ev.getAction() == KeyEvent.ACTION_DOWN) {
                int n = shotUrls.size();
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT && n > 1) showInViewer((viewerIndex + 1) % n);
                else if (code == KeyEvent.KEYCODE_DPAD_LEFT && n > 1) showInViewer((viewerIndex + n - 1) % n);
                else if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) closeViewer();
            }
            return true; // the viewer has the keys
        }
        View f = getCurrentFocus();
        if (ev.getAction() == KeyEvent.ACTION_DOWN && f != null) {
            boolean inLeft = inside(f, leftPane), inRight = inside(f, rightPane);
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT && inLeft) { // over to the reading side, where you left it
                View beside = f.focusSearch(View.FOCUS_RIGHT);
                if (beside != null && inside(beside, leftPane)) return super.dispatchKeyEvent(ev); // the next button along
                View to = lastRight != null && lastRight.isShown() && onScreen(lastRight) ? lastRight : firstOnScreen(rightPane);
                if (to != null) to.requestFocus();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT && inRight) {
                View next = f.focusSearch(View.FOCUS_LEFT);
                if (next == null || !inside(next, rightPane)) { // back to the buttons
                    (lastLeft != null && lastLeft.isShown() ? lastLeft : primary).requestFocus();
                    return true;
                }
            }
            if ((code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) && (inLeft || inRight)) {
                View next = f.focusSearch(code == KeyEvent.KEYCODE_DPAD_UP ? View.FOCUS_UP : View.FOCUS_DOWN);
                if (next != null && !inside(next, inLeft ? leftPane : rightPane)) {
                    // Up and down stay on their side; at the ends, just show the rest of it.
                    ScrollView sv = inRight ? rightScroll : (ScrollView) leftPane.getParent();
                    sv.smoothScrollBy(0, code == KeyEvent.KEYCODE_DPAD_UP ? -dp(160) : dp(160));
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(ev);
    }

    /** The first focusable view of a pane that's mostly on screen. */
    private View firstOnScreen(ViewGroup pane) {
        ArrayList<View> all = pane.getFocusables(View.FOCUS_FORWARD);
        for (View v : all) if (v.isShown() && onScreen(v)) return v;
        return all.isEmpty() ? null : all.get(0);
    }

    private boolean onScreen(View v) {
        android.graphics.Rect r = new android.graphics.Rect();
        return v.getGlobalVisibleRect(r) && r.height() > v.getHeight() / 3;
    }

    private static boolean inside(View v, View parent) {
        for (View x = v; x != null; x = x.getParent() instanceof View ? (View) x.getParent() : null) if (x == parent) return true;
        return false;
    }

    // ---- uninstalling -------------------------------------------------------------------

    private void uninstall(String pkg) {
        if (pkg == null) return;
        try {
            startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Couldn't uninstall " + entry.name + ". Try App info", Toast.LENGTH_SHORT).show();
        }
    }

    private void uninstallWebapp(final String webId) {
        if (webId == null) return;
        List<CharSequence> opts = new ArrayList<>();
        opts.add("Uninstall " + entry.name);
        opts.add("Cancel");
        M3Menu.show(this, "Uninstall " + entry.name + "?", "It leaves All apps and Favourites. You can install it again from here.",
                opts, which -> {
                    if (which != 0) return;
                    WebApps.uninstall(this, webId);
                    Toast.makeText(this, entry.name + " uninstalled", Toast.LENGTH_SHORT).show();
                    Store.listChanged(); // Home reloads its rows
                    load(false);
                });
    }

    // ---- small helpers ------------------------------------------------------------------

    private static String host(String url) {
        try {
            String h = new java.net.URI(url).getHost();
            return h == null ? "a web address" : h;
        } catch (Exception e) {
            return "a web address";
        }
    }

    private static String minutes(int m) {
        if (m % 1440 == 0) return m == 1440 ? "day" : (m / 1440) + " days";
        if (m % 60 == 0) return m == 60 ? "hour" : (m / 60) + " hours";
        return m + " minutes";
    }

    private static String themeSummary(JSONObject t) {
        List<String> out = new ArrayList<>();
        if (t.has("accent")) out.add("accent " + t.optString("accent"));
        if (t.has("mode")) out.add(t.optString("mode").equals("light") ? "light" : "dark");
        if (t.has("background_url")) out.add("a picture background");
        else if (t.has("background_color")) out.add("background " + t.optString("background_color"));
        if (t.has("tile_size")) out.add(t.optString("tile_size") + " tiles");
        return out.isEmpty() ? "changes the look" : TextUtils.join(", ", out);
    }

    /** "12 Nov 2026" from an ISO time, or null. */
    private static String date(String iso) {
        if (iso == null || iso.isEmpty()) return null;
        try {
            return DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
                    .format(Instant.parse(iso).atZone(ZoneId.systemDefault()));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static final String[] ANDROID = { "5.0", "5.1", "6", "7.0", "7.1", "8.0", "8.1", "9", "10", "11", "12", "12L", "13", "14", "15", "16" };

    /** "Android 9 or newer (API 28)". */
    private static String android(int api) {
        String name = api >= 21 && api - 21 < ANDROID.length ? ANDROID[api - 21] : null;
        return (name == null ? "API " + api : "Android " + name + " or newer (API " + api + ")");
    }

    private LinearLayout.LayoutParams matchWrap(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        return lp;
    }

    private TextView text(String s, float sp, int color, android.graphics.Typeface face) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(face);
        t.setGravity(Gravity.START);
        return t;
    }

    /** A pill button: filled (primary) or tonal; focus draws a ring and grows it a little. */
    private TextView button(String label, boolean filled) {
        final TextView b = text(label, 18, filled ? M3.ON_PRIMARY : M3.ON_SECONDARY_CONT, M3.MEDIUM);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        b.setPadding(dp(24), 0, dp(24), 0);
        b.setFocusable(true);
        b.setClickable(true);
        final GradientDrawable bg = M3.rounded(filled ? M3.PRIMARY : M3.SECONDARY_CONT, dp(28));
        b.setBackground(bg);
        b.setOnFocusChangeListener((v, has) -> {
            bg.setStroke(has ? dp(3) : 0, M3.ON_SURFACE);
            v.animate().scaleX(has ? 1.04f : 1f).scaleY(has ? 1.04f : 1f).setDuration(150).start();
        });
        return b;
    }

    // ---- views --------------------------------------------------------------------------

    /** Lays its children left to right, wrapping to a new line when the width runs out (the chips). */
    static final class Flow extends ViewGroup {
        private final int hGap, vGap;

        Flow(Context c, int hGap, int vGap) {
            super(c);
            this.hGap = hGap;
            this.vGap = vGap;
        }

        @Override
        protected void onMeasure(int ws, int hs) {
            int max = MeasureSpec.getMode(ws) == MeasureSpec.UNSPECIFIED ? Integer.MAX_VALUE : MeasureSpec.getSize(ws);
            int x = 0, y = 0, rowH = 0, widest = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View ch = getChildAt(i);
                if (ch.getVisibility() == GONE) continue;
                ViewGroup.LayoutParams lp = ch.getLayoutParams();
                int wSpec = lp != null && lp.width > 0 ? MeasureSpec.makeMeasureSpec(lp.width, MeasureSpec.EXACTLY)
                        : max == Integer.MAX_VALUE ? MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                        : MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST);
                int hSpec = lp != null && lp.height > 0 ? MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY)
                        : MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
                ch.measure(wSpec, hSpec);
                int w = ch.getMeasuredWidth(), h = ch.getMeasuredHeight();
                if (x > 0 && x + w > max) { x = 0; y += rowH + vGap; rowH = 0; }
                x += w + hGap;
                widest = Math.max(widest, x - hGap);
                rowH = Math.max(rowH, h);
            }
            int width = MeasureSpec.getMode(ws) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(ws) : Math.min(widest, max);
            setMeasuredDimension(width, y + rowH);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int max = r - l, x = 0, y = 0, rowH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View ch = getChildAt(i);
                if (ch.getVisibility() == GONE) continue;
                int w = ch.getMeasuredWidth(), h = ch.getMeasuredHeight();
                if (x > 0 && x + w > max) { x = 0; y += rowH + vGap; rowH = 0; }
                ch.layout(x, y, x + w, y + h);
                x += w + hGap;
                rowH = Math.max(rowH, h);
            }
        }
    }

    /** An M3 linear progress indicator: determinate (0..1) with a gap and a stop dot, or indeterminate (-1). */
    static final class Bar extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float value = -1f;
        private final long start = SystemClock.uptimeMillis();

        Bar(Context c) { super(c); }

        void set(float v) {
            value = v;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = h / 2f, gap = h;
            if (w <= 0) return;
            if (value >= 0f) {
                float end = w * Math.max(0f, Math.min(1f, value));
                if (end > 0f) {
                    p.setColor(M3.PRIMARY);
                    rect.set(0, 0, Math.max(h, end), h);
                    c.drawRoundRect(rect, r, r, p);
                }
                float from = end > 0f ? Math.max(h, end) + gap : 0f;
                if (from < w - h) {
                    p.setColor(M3.SURFACE_HIGHEST);
                    rect.set(from, 0, w, h);
                    c.drawRoundRect(rect, r, r, p);
                    p.setColor(M3.PRIMARY);
                    c.drawCircle(w - r, r, r * 0.6f, p); // the stop dot
                }
            } else {
                p.setColor(M3.SURFACE_HIGHEST);
                rect.set(0, 0, w, h);
                c.drawRoundRect(rect, r, r, p);
                float t = ((SystemClock.uptimeMillis() - start) % 1500) / 1500f;
                float len = w * 0.35f, a = (t * 1.35f - 0.35f) * w;
                float from = Math.max(0f, a), to = Math.min(w, a + len);
                if (to - from > 1f) {
                    p.setColor(M3.PRIMARY);
                    rect.set(from, 0, to, h);
                    c.drawRoundRect(rect, r, r, p);
                }
                if (isShown()) postInvalidateOnAnimation();
            }
        }
    }
}
