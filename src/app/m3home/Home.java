package app.m3home;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

/**
 * The home screen, Material 3 Expressive: greeting and clock, a Tailscale chip and Settings button,
 * then Favourites, Recently used, an All apps grid and Get apps (the little app store), in the order
 * (and tile size) set in Settings.
 */
public class Home extends Activity {
    /** Opens Home with the first favourite picked up, ready to move. (1.9.0 contract) */
    static final String EXTRA_REORDER = "reorder";

    // Tile widths at the "normal" size; compact is x0.8, large x1.2.
    private static final int FAV_TILE_DP = 232;
    private static final int RECENT_TILE_DP = 196;
    private static final int GAP_DP = 20;
    private static final int SIDE_PADDING_DP = 48;
    private static final float TILE_RATIO = 0.625f; // 16:10 cards
    private static final int SHOWN_RECENTS = 8;
    private static final long TAILSCALE_POLL_MS = 5000;
    /** Tile names stay white on every theme: the cards are always a dark tone of the app's colour. */
    private static final int TILE_LABEL = 0xE6FFFFFF;

    private static final int ROW_FAVS = 0, ROW_RECENTS = 1, ROW_GRID = 2, ROW_STORE = 3;
    /** Plugin rows are numbered from here, in the order of pluginRowKeys. */
    private static final int ROW_PLUGIN = 100;
    private static final long PLUGIN_POLL_MS = 60_000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Apps apps;
    private List<Apps.App> installed = new ArrayList<>();

    // Layout from Prefs, fixed for this activity instance (a change bumps the look rev -> recreate).
    private List<Integer> order = new ArrayList<>();
    private int gridCols = 5, gridTileDp = 156, favTileDp = FAV_TILE_DP, recentTileDp = RECENT_TILE_DP;
    private int builtRev;
    private boolean imageBg;

    private FrameLayout root;
    private ScrollView scroll;
    private LinearLayout column;
    private static final String ADD_TAG = "m3home:add-favourite";
    private static final String LIFT_TAG = "m3home:lift";
    private static final String STORE_TAG = "m3home:store:", STORE_ADD_TAG = "m3home:store-add";

    // The "Get apps" row (Store): the apps it offers, each tile's badge, and where focus was.
    private List<Store.Item> storeItems = new ArrayList<>();
    private final java.util.Map<String, Object[]> storeBadges = new java.util.HashMap<>();
    private LinearLayout storeSection, storeRow;
    private int storeFocus;

    // Plugins: their rows ("plugin:<plugin>/<row>" ids, numbered from ROW_PLUGIN), header widgets and shortcuts.
    private final List<String> pluginRowKeys = new ArrayList<>();
    private final List<LinearLayout> pluginSections = new ArrayList<>(), pluginRows = new ArrayList<>();
    private Plugins.Snapshot plugins = new Plugins.Snapshot();
    private LinearLayout pluginChips;
    private int pluginFocus;
    private static final String PLUGIN_TAG = "m3home:plugin:";

    private final Runnable pollPlugins = new Runnable() {
        @Override public void run() {
            Plugins.refreshDue(Home.this, new java.util.HashSet<>(pluginRowKeys));
            ui.postDelayed(this, PLUGIN_POLL_MS);
        }
    };

    // Move mode: a favourite picked up and carried with Left/Right.
    private String movingPkg;
    private boolean pendingReorder;
    private int moveKeyDown = -1;
    private TextView favHint;

    private TextView greeting, clock, date, tsText, allCount;
    private LinearLayout hiddenButton;
    private TextView hiddenButtonText;
    private ImageView tsIcon;
    private LinearLayout tsChip;
    private LinearLayout favSection, recentSection, allSection;
    private LinearLayout favRow, recentRow;
    private GridLayout grid;

    private boolean resumed, dirty = true, loading, recentsChanged;
    private String focusPkg;
    private int focusRow = ROW_FAVS;

    private final Runnable pollTailscale = new Runnable() {
        @Override public void run() {
            updateTailscale();
            ui.postDelayed(this, TAILSCALE_POLL_MS);
        }
    };

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateClock(); }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            Plugins.invalidate(); // an Android plugin may have come or gone
            dirty = true;
            if (resumed) load();
        }
    };

    // ---- lifecycle --------------------------------------------------------------------

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(M3.scaled(base)); // the user's text size
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // On a phone, MaterialTV is the setup remote for a Fire TV, not a launcher.
        if (!getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)) {
            startActivity(new Intent(this, PhoneSetupActivity.class));
            finish();
            return;
        }
        M3.init(this);
        Secure.ensureGuard(this); // an update can switch the Home-button service off
        if (!Prefs.of(this).getBoolean("setup_offered", false)) {
            startActivity(new Intent(this, SetupActivity.class)); // first run: offer the setup once
        }
        apps = new Apps(this);
        builtRev = Prefs.lookRev(this);
        readLayout();
        pendingReorder = getIntent().getBooleanExtra(EXTRA_REORDER, false)
                || (state != null && state.getBoolean(EXTRA_REORDER, false));
        getIntent().removeExtra(EXTRA_REORDER); // only once, not again on a later recreate
        setContentView(buildScreen());

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pf.addDataScheme("package");
        registerReceiver(packageReceiver, pf);

        // App store progress redraws one badge; a refreshed catalog reloads the row.
        Store.setListener(id -> {
            if (id != null) { showBadge(id); return; }
            dirty = true;
            if (resumed) load();
        });
        // New plugin data (or a plugin installed from the Get apps row) reloads the plugin rows.
        Plugins.setListener(() -> {
            if (Prefs.lookRev(this) != builtRev) { if (resumed) recreate(); return; }
            dirty = true;
            if (resumed) load();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Prefs.lookRev(this) != builtRev) { // colours, text size, layout or background changed
            recreate();
            return;
        }
        resumed = true;
        IntentFilter tf = new IntentFilter();
        tf.addAction(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeReceiver, tf);
        updateClock();
        if (Prefs.on(this, Prefs.W_WEATHER)) Weather.refreshIfStale(this, null); // warm for the screensaver
        refreshUpdateCard();
        Updater.checkIfDue(this, () -> ui.post(this::refreshUpdateCard));
        if (order.contains(ROW_STORE)) Store.refreshIfDue(this);
        ui.removeCallbacks(pollTailscale);
        ui.post(pollTailscale);
        scheduleSlide(); // background slideshow: next picture when due (soon if overdue)
        ui.removeCallbacks(pollPlugins);
        ui.post(pollPlugins); // refreshes plugin widgets and the rows on screen while Home shows
        if (dirty) {
            load();
        } else if (!loading) {
            render(); // recents, pins or hidden apps may have changed while we were away
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        endMove();
        try { unregisterReceiver(timeReceiver); } catch (IllegalArgumentException ignored) { }
        ui.removeCallbacks(pollTailscale);
        ui.removeCallbacks(slideTick);
        ui.removeCallbacks(pollPlugins);
    }

    // ---- background slideshow --- begin -------------------------------------------------
    private static final int SLIDE_FADE_MS = 1500;

    private final Runnable slideTick = () -> Backdrop.nextSlide(this, changed -> {
        if (isDestroyed() || root == null) return;
        if (changed) crossfadeBackground();
        if (resumed) scheduleSlide();
    });

    private void scheduleSlide() {
        ui.removeCallbacks(slideTick);
        if (Backdrop.isSlideshow(this)) ui.postDelayed(slideTick, Backdrop.slideDelay(this));
    }

    /** Fades from the background showing now to Backdrop's new picture, then lets the old one go. */
    private void crossfadeBackground() {
        android.graphics.drawable.Drawable next = Backdrop.get(this), cur = root.getBackground();
        if (cur instanceof android.graphics.drawable.TransitionDrawable) {
            cur = ((android.graphics.drawable.TransitionDrawable) cur).getDrawable(1); // a fade still running
        }
        if (cur == null) { root.setBackground(next); return; }
        final android.graphics.drawable.TransitionDrawable fade =
                new android.graphics.drawable.TransitionDrawable(new android.graphics.drawable.Drawable[] { cur, next });
        root.setBackground(fade);
        fade.startTransition(SLIDE_FADE_MS);
        ui.postDelayed(() -> { if (root != null && root.getBackground() == fade) root.setBackground(next); }, SLIDE_FADE_MS + 100);
    }
    // ---- background slideshow --- end ---------------------------------------------------

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (pendingReorder) out.putBoolean(EXTRA_REORDER, true); // survives a look-change recreate
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(slideTick);
        try { unregisterReceiver(packageReceiver); } catch (IllegalArgumentException ignored) { }
        Store.setListener(null); // installs carry on; only the badges stop
        Plugins.setListener(null);
        super.onDestroy();
    }

    /** Home pressed while already home: back to the top and the first favourite. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        endMove();
        if (intent != null && intent.getBooleanExtra(EXTRA_REORDER, false)) {
            // Settings' "Reorder favourites": pick up the first favourite. onResume renders and
            // starts it; if we're already showing, start now.
            pendingReorder = true;
            if (resumed && !loading && !dirty) startPendingReorder();
            return;
        }
        goTop();
    }

    /** While a favourite is picked up: Left/Right carry it, Select/Back/Up/Down put it down. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (movingPkg == null && atRowEnd(e)) return true; // stop at the end of a row instead of jumping elsewhere
        if (movingPkg == null) return super.dispatchKeyEvent(e);
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) shiftMove(code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : +1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_MENU:
                // Drop on release, and only for a press that began in move mode, so the click
                // that opened move mode (or a long press) can't drop it straight away.
                if (down) { if (e.getRepeatCount() == 0) moveKeyDown = code; }
                else if (moveKeyDown == code) endMove();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down && e.getRepeatCount() == 0) endMove();
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    @Override
    public void onBackPressed() {
        goTop(); // a launcher has nowhere to go back to
    }

    private void goTop() {
        if (scroll != null) scroll.smoothScrollTo(0, 0);
        focusPkg = null;
        focusFirstTile();
    }

    // ---- layout -----------------------------------------------------------------------

    private int dp(float v) { return M3.dp(this, v); }

    /** Row order and tile size from Prefs. */
    private void readLayout() {
        order = new ArrayList<>();
        pluginRowKeys.clear();
        for (String id : Prefs.homeRows(this).split(",")) {
            String s = id.trim();
            int r = s.equals("favourites") ? ROW_FAVS : s.equals("recents") ? ROW_RECENTS
                    : s.equals("apps") ? ROW_GRID : s.equals("store") ? ROW_STORE : -1;
            if (r < 0 && !pluginRowKeys.contains(s) && Plugins.rowTitle(this, s) != null) {
                pluginRowKeys.add(s); // a row from an enabled plugin
                r = ROW_PLUGIN + pluginRowKeys.size() - 1;
            }
            if (r >= 0 && !order.contains(r)) order.add(r);
        }
        if (order.isEmpty()) order.add(ROW_GRID); // never an empty Home: the apps grid shows anyway

        String size = Prefs.tileSize(this);
        boolean compact = "compact".equals(size), large = "large".equals(size);
        float k = compact ? 0.8f : large ? 1.2f : 1f;
        favTileDp = Math.round(FAV_TILE_DP * k);
        recentTileDp = Math.round(RECENT_TILE_DP * k);
        gridCols = compact ? 6 : large ? 4 : 5;
        int screenDp = getResources().getConfiguration().screenWidthDp;
        if (screenDp <= 0) screenDp = 960; // Fire TV at 1080p
        gridTileDp = Math.max(96, (screenDp - 2 * SIDE_PADDING_DP - (gridCols - 1) * GAP_DP) / gridCols);
    }

    private View buildScreen() {
        root = new FrameLayout(this);
        root.setBackground(new Glow()); // until the chosen background is decoded
        imageBg = Backdrop.isImage(this);
        Backdrop.prepare(this, () -> {
            if (!isDestroyed() && root != null) root.setBackground(Backdrop.get(this));
        });

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);

        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(SIDE_PADDING_DP), dp(32), dp(SIDE_PADDING_DP), dp(56));
        column.setClipChildren(false);
        column.setClipToPadding(false);

        column.addView(buildHeader());
        column.addView(buildUpdateCard());

        // Favourites (the header's second text is the move-mode hint)
        TextView[] hint = new TextView[1];
        favSection = section("Favourites", hint);
        favHint = hint[0];
        favHint.setTextColor(M3.PRIMARY);
        favHint.setVisibility(View.GONE);
        favRow = row();
        favSection.addView(rowScroller(favRow));

        // Recently used
        recentSection = section("Recently used", null);
        recentRow = row();
        recentSection.addView(rowScroller(recentRow));

        // All apps
        TextView[] count = new TextView[1];
        allSection = section("All apps", count);
        allCount = count[0];
        grid = new GridLayout(this);
        grid.setColumnCount(gridCols);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        grid.setPadding(0, dp(14), 0, 0);
        allSection.addView(grid);
        hiddenButton = pillButton(R.drawable.ic_visibility_off, "Hidden apps");
        hiddenButtonText = (TextView) hiddenButton.getChildAt(1);
        hiddenButton.setOnClickListener(v -> showHiddenApps());
        LinearLayout.LayoutParams hbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        hbLp.topMargin = dp(8);
        allSection.addView(hiddenButton, hbLp);

        // Get apps
        storeSection = section("Get apps", null);
        storeRow = row();
        storeSection.addView(rowScroller(storeRow));

        // Plugin rows: the row's name, with the plugin's name beside it.
        pluginSections.clear();
        pluginRows.clear();
        for (String key : pluginRowKeys) {
            TextView[] by = new TextView[1];
            String title = Plugins.rowTitle(this, key);
            LinearLayout sec = section(title, by);
            String[] k = Plugins.splitKey(key, Plugins.ROW_PREFIX);
            Plugins.Plugin p = k == null ? null : Plugins.find(this, k[0]);
            if (p != null && !p.name.equals(title)) by[0].setText(p.name);
            LinearLayout r = row();
            sec.addView(rowScroller(r));
            sec.setVisibility(View.GONE); // until it has something to show
            pluginSections.add(sec);
            pluginRows.add(r);
        }

        for (int r : order) column.addView(sectionView(r)); // hidden rows are never added

        scroll.addView(column);
        root.addView(scroll);
        return root;
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.BOTTOM);
        header.setClipChildren(false);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        greeting = new TextView(this);
        style(greeting, 18, M3.PRIMARY, M3.MEDIUM);
        shadow(greeting);
        greeting.setLetterSpacing(0.02f);
        left.addView(greeting);

        LinearLayout timeLine = new LinearLayout(this);
        timeLine.setOrientation(LinearLayout.HORIZONTAL);
        timeLine.setBaselineAligned(true);
        clock = new TextView(this);
        style(clock, 60, M3.ON_SURFACE, M3.REGULAR);
        shadow(clock);
        clock.setIncludeFontPadding(false);
        clock.setLetterSpacing(-0.02f);
        date = new TextView(this);
        style(date, 20, M3.ON_SURFACE_VAR, M3.REGULAR);
        shadow(date);
        date.setPadding(dp(18), 0, 0, 0);
        timeLine.addView(clock);
        timeLine.addView(date);
        LinearLayout.LayoutParams tlLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlLp.topMargin = dp(2);
        left.addView(timeLine, tlLp);
        header.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Plugin widgets: small status chips (not focusable), like the Tailscale one.
        pluginChips = new LinearLayout(this);
        pluginChips.setOrientation(LinearLayout.HORIZONTAL);
        pluginChips.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams pcLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        pcLp.bottomMargin = dp(8);
        header.addView(pluginChips, pcLp);

        // Tailscale status chip (status only, not focusable).
        tsChip = new LinearLayout(this);
        tsChip.setOrientation(LinearLayout.HORIZONTAL);
        tsChip.setGravity(Gravity.CENTER_VERTICAL);
        tsChip.setPadding(dp(14), 0, dp(18), 0);
        tsIcon = new ImageView(this);
        tsIcon.setImageResource(R.drawable.ic_tailnet);
        tsChip.addView(tsIcon, new LinearLayout.LayoutParams(dp(18), dp(18)));
        tsText = new TextView(this);
        style(tsText, 15, M3.ON_SURFACE_VAR, M3.MEDIUM);
        tsText.setPadding(dp(10), 0, 0, 0);
        tsText.setText("Tailscale");
        tsChip.addView(tsText);
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        chipLp.rightMargin = dp(14);
        chipLp.bottomMargin = dp(8);
        header.addView(tsChip, chipLp);

        // Settings: M3 filled tonal button with icon; filled primary when focused, gear turns.
        final LinearLayout settings = pillButton(R.drawable.ic_settings, "Settings");
        final View gear = settings.getChildAt(0);
        final View.OnFocusChangeListener base = settings.getOnFocusChangeListener();
        settings.setOnFocusChangeListener((v, has) -> {
            base.onFocusChange(v, has);
            gear.animate().rotation(has ? 60f : 0f).setDuration(320).start();
            if (has) scroll.smoothScrollTo(0, 0);
        });
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        LinearLayout.LayoutParams setLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(52));
        setLp.bottomMargin = dp(4);
        header.addView(settings, setLp);
        return header;
    }

    private LinearLayout updateCard;
    private TextView updateText;
    private boolean updating;

    /** "MaterialTV 1.7 is available · Install": shown only when GitHub has a newer release. */
    private View buildUpdateCard() {
        updateCard = new LinearLayout(this);
        updateCard.setOrientation(LinearLayout.HORIZONTAL);
        updateCard.setGravity(Gravity.CENTER_VERTICAL);
        updateCard.setBackground(M3.rounded(M3.PRIMARY_CONT, dp(28)));
        updateCard.setPadding(dp(28), dp(14), dp(14), dp(14));
        updateText = new TextView(this);
        style(updateText, 18, M3.ON_PRIMARY_CONT, M3.MEDIUM);
        updateCard.addView(updateText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout install = pillButton(R.drawable.ic_add, "Install");
        ((ImageView) install.getChildAt(0)).setRotation(45f); // reads as a download arrow-ish mark
        install.getChildAt(0).setVisibility(View.GONE);
        install.setOnClickListener(v -> installUpdate());
        updateCard.addView(install, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(24);
        updateCard.setLayoutParams(lp);
        updateCard.setVisibility(View.GONE);
        return updateCard;
    }

    private void refreshUpdateCard() {
        String v = Updater.available(this);
        updateCard.setVisibility(v == null ? View.GONE : View.VISIBLE);
        if (v != null && !updating) updateText.setText("MaterialTV " + v + " is available");
    }

    private void installUpdate() {
        if (updating) return;
        updating = true;
        updateText.setText("Downloading the update…");
        new Thread(() -> Updater.install(this, msg -> ui.post(() -> {
            updating = false;
            updateText.setText(msg);
        })), "m3home-update").start();
    }

    /** M3 filled tonal button (icon + text) that turns filled primary and springs on focus. */
    private LinearLayout pillButton(int iconRes, String text) {
        final LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setFocusable(true);
        b.setClickable(true);
        b.setPadding(dp(18), 0, dp(24), 0);
        final GradientDrawable bg = M3.rounded(M3.SECONDARY_CONT, dp(26));
        b.setBackground(bg);
        final ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setImageTintList(ColorStateList.valueOf(M3.ON_SECONDARY_CONT));
        b.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));
        final TextView t = new TextView(this);
        t.setText(text);
        style(t, 16, M3.ON_SECONDARY_CONT, M3.MEDIUM);
        t.setPadding(dp(10), 0, 0, 0);
        b.addView(t);
        b.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? M3.PRIMARY : M3.SECONDARY_CONT);
            int fg = has ? M3.ON_PRIMARY : M3.ON_SECONDARY_CONT;
            icon.setImageTintList(ColorStateList.valueOf(fg));
            t.setTextColor(fg);
            pop(b, has, 1.08f);
        });
        return b;
    }

    /** A section: title (plus an optional count) above its content. */
    private LinearLayout section(String title, TextView[] countOut) {
        LinearLayout s = new LinearLayout(this);
        s.setOrientation(LinearLayout.VERTICAL);
        s.setClipChildren(false);
        s.setClipToPadding(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(30);
        s.setLayoutParams(lp);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setBaselineAligned(true);
        TextView t = new TextView(this);
        t.setText(title);
        style(t, 22, M3.ON_SURFACE, M3.MEDIUM);
        shadow(t);
        head.addView(t);
        if (countOut != null) {
            TextView n = new TextView(this);
            style(n, 16, M3.ON_SURFACE_VAR, M3.REGULAR);
            shadow(n);
            n.setPadding(dp(12), 0, 0, 0);
            head.addView(n);
            countOut[0] = n;
        }
        s.addView(head);
        return s;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setClipChildren(false);
        r.setClipToPadding(false);
        r.setPadding(0, dp(16), dp(32), dp(4));
        return r;
    }

    private HorizontalScrollView rowScroller(LinearLayout row) {
        HorizontalScrollView h = new HorizontalScrollView(this);
        h.setHorizontalScrollBarEnabled(false);
        h.setOverScrollMode(View.OVER_SCROLL_NEVER);
        h.setClipChildren(false);
        h.setClipToPadding(false);
        h.addView(row);
        return h;
    }

    private void style(TextView t, float sp, int color, Typeface face) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(face);
    }

    /** Over a picture background, a soft halo in the background tone keeps titles readable. */
    private void shadow(TextView t) {
        if (imageBg) t.setShadowLayer(dp(3), 0, dp(1), M3.lightMode ? 0x66FFFFFF : 0x99000000); // small, so it never shows as a box
    }

    /** M3 Expressive focus: a springy scale up, a calm settle back. */
    private static void pop(View v, boolean in, float scale) {
        v.animate().cancel();
        v.animate()
                .scaleX(in ? scale : 1f).scaleY(in ? scale : 1f)
                .setInterpolator(in ? new OvershootInterpolator(1.6f) : new DecelerateInterpolator())
                .setDuration(in ? 280 : 160)
                .start();
    }

    /**
     * One app card: a rounded 16:9 surface with the banner, or the icon on a colour taken from it,
     * with the label beneath. On focus the corners morph rounder and the card springs up.
     */
    private View tile(final Apps.App app, int widthDp, final int row) {
        View icon = null;
        if (app.art == null) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setImageDrawable(app.icon);
            icon = iv;
        }
        final LinearLayout tile = cardTile(app.pkg, app.label, app.tint, icon, app.art, widthDp, row);
        tile.setOnClickListener(v -> launch(app));
        tile.setOnLongClickListener(v -> { showOptions(app, row); return true; });
        tile.setOnKeyListener((v, code, ev) -> {
            if (code == KeyEvent.KEYCODE_MENU) {
                if (ev.getAction() == KeyEvent.ACTION_UP) showOptions(app, row);
                return true;
            }
            return false;
        });
        return tile;
    }

    /**
     * The card every tile shares: a tonal gradient from the app's colour, its icon (or your own
     * picture) and its name inside. tag identifies it for focus restore.
     */
    private LinearLayout cardTile(final String tag, String name, int tint, View icon, android.graphics.Bitmap art,
                                  int widthDp, final int row) {
        final LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setClipChildren(false);
        tile.setTag(tag);

        final int w = dp(widthDp), h = Math.round(w * TILE_RATIO);
        final float rest = dp(20), focused = dp(32);
        // Every tile is drawn the same M3 way: a tonal gradient from the app's own colour,
        // its sharpest icon, and its name inside the card. Your own picture replaces it all.
        final GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] { M3.tone(tint, 1.35f), tint, M3.tone(tint, 0.72f) });
        bg.setCornerRadius(rest);
        final GradientDrawable ring = M3.outlined(Color.TRANSPARENT, M3.PRIMARY, dp(3), rest);

        final FrameLayout card = new FrameLayout(this);
        card.setBackground(bg);
        card.setClipToOutline(true);
        card.setTag(ring); // move mode thickens it
        float nameSp = widthDp >= 200 ? 17 : 14;
        if (art != null) {
            ImageView pic = new ImageView(this);
            pic.setScaleType(ImageView.ScaleType.CENTER_CROP);
            pic.setImageBitmap(art);
            card.addView(pic, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            View scrim = new View(this);
            scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[] { Color.TRANSPARENT, Color.TRANSPARENT, 0xC0000000 })); // name scrim on the picture
            card.addView(scrim, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else if (icon != null) {
            // A soft lighter disc behind the icon, so dark and light icons both sit well.
            View plate = new View(this);
            plate.setBackground(M3.circle(0x1FFFFFFF));
            int plateSize = Math.round(h * 0.56f);
            FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(plateSize, plateSize,
                    Gravity.CENTER_HORIZONTAL | Gravity.TOP);
            plp.topMargin = Math.round(h * 0.10f);
            card.addView(plate, plp);
            int size = Math.round(h * 0.40f);
            if (icon instanceof TextView) ((TextView) icon).setTextSize(TypedValue.COMPLEX_UNIT_PX, size * 0.7f);
            FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(size, size, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
            ilp.topMargin = Math.round(h * 0.10f) + (plateSize - size) / 2;
            card.addView(icon, ilp);
        }
        final TextView label = new TextView(this);
        label.setText(name);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        label.setMarqueeRepeatLimit(-1);
        label.setHorizontalFadingEdgeEnabled(true);
        label.setGravity(Gravity.CENTER);
        label.setPadding(dp(10), 0, dp(10), Math.round(h * 0.07f));
        style(label, nameSp, TILE_LABEL, M3.MEDIUM);
        card.addView(label, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        tile.addView(card, new LinearLayout.LayoutParams(w, h));

        final ValueAnimator[] morph = new ValueAnimator[1];
        tile.setOnFocusChangeListener((v, has) -> {
            card.setForeground(has ? ring : null);
            if (morph[0] != null) morph[0].cancel();
            ValueAnimator a = ValueAnimator.ofFloat(bg.getCornerRadius(), has ? focused : rest);
            a.setDuration(240);
            a.setInterpolator(new DecelerateInterpolator());
            a.addUpdateListener(x -> {
                float r = (float) x.getAnimatedValue();
                bg.setCornerRadius(r);
                ring.setCornerRadius(r);
                card.invalidateOutline();
            });
            a.start();
            morph[0] = a;
            pop(card, has, 1.1f);
            label.setTextColor(has ? Color.WHITE : TILE_LABEL);
            label.setSelected(has); // scrolls a long name while the tile is highlighted
            if (has) focused(tile, tag, row);
        });
        return tile;
    }

    /** Bookkeeping when a tile takes focus: where to come back to, and keeping it on screen. */
    private void focused(View tile, String tag, int row) {
        if (movingPkg != null && !movingPkg.equals(tag)) endMove(); // focus left it (touch etc.)
        focusPkg = tag;
        focusRow = row;
        if (row == ROW_STORE) storeFocus = storeRow.indexOfChild(tile);
        if (row >= ROW_PLUGIN) pluginFocus = rowView(row).indexOfChild(tile);
        keepVisible(tile, row);
    }

    /** The last card in Favourites: a dashed outline with a "+" that opens a list of apps to pin. */
    private View addFavouriteTile() {
        View t = dashedTile("Add favourite", ADD_TAG, favTileDp, ROW_FAVS);
        t.setOnClickListener(v -> showAddFavourite());
        return t;
    }

    /** A dashed card with a "+" bubble: Add favourite, and the app store's Add an app. */
    private View dashedTile(String text, final String tag, int widthDp, final int row) {
        final LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setClipChildren(false);
        tile.setTag(tag);

        final int w = dp(widthDp);
        final float rest = dp(18), focused = dp(30);
        final int idle = M3.alpha(M3.SURFACE, 0x55), lit = M3.alpha(M3.PRIMARY_CONT, 0x80);
        final GradientDrawable bg = new GradientDrawable();
        bg.setColor(idle);
        bg.setCornerRadius(rest);
        bg.setStroke(dp(2), M3.OUTLINE_VAR, dp(10), dp(8));

        final FrameLayout card = new FrameLayout(this);
        card.setBackground(bg);
        card.setClipToOutline(true);
        FrameLayout plusBubble = new FrameLayout(this);
        plusBubble.setBackground(M3.circle(M3.PRIMARY_CONT));
        ImageView plus = new ImageView(this);
        plus.setImageResource(R.drawable.ic_add);
        plus.setImageTintList(ColorStateList.valueOf(M3.ON_PRIMARY_CONT));
        int h = Math.round(w * TILE_RATIO);
        int bubble = Math.round(h * 0.41f); // 60dp at the normal size
        plusBubble.addView(plus, new FrameLayout.LayoutParams(bubble / 2, bubble / 2, Gravity.CENTER));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(bubble, bubble, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        blp.topMargin = Math.round(h * 0.18f);
        card.addView(plusBubble, blp);
        final TextView label = new TextView(this);
        label.setText(text);
        label.setGravity(Gravity.CENTER);
        label.setPadding(dp(10), 0, dp(10), Math.round(h * 0.07f));
        style(label, widthDp >= 200 ? 17 : 14, M3.ON_SURFACE_VAR, M3.MEDIUM);
        card.addView(label, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        tile.addView(card, new LinearLayout.LayoutParams(w, h));

        final ValueAnimator[] morph = new ValueAnimator[1];
        tile.setOnFocusChangeListener((v, has) -> {
            if (has) bg.setStroke(dp(3), M3.PRIMARY);
            else bg.setStroke(dp(2), M3.OUTLINE_VAR, dp(10), dp(8));
            bg.setColor(has ? lit : idle);
            if (morph[0] != null) morph[0].cancel();
            ValueAnimator a = ValueAnimator.ofFloat(bg.getCornerRadius(), has ? focused : rest);
            a.setDuration(240);
            a.addUpdateListener(x -> {
                bg.setCornerRadius((float) x.getAnimatedValue());
                card.invalidateOutline();
            });
            a.start();
            morph[0] = a;
            pop(card, has, 1.1f);
            label.setTextColor(has ? M3.ON_SURFACE : M3.ON_SURFACE_VAR);
            if (has) focused(tile, tag, row);
        });
        return tile;
    }

    // ---- the app store row ----------------------------------------------------------------

    /** A "Get apps" card: the app's icon or first letter, with a badge (Get, Update, 42%…). */
    private View storeTile(final Store.Item item) {
        final Store.Entry e = item.entry;
        View icon;
        if (item.icon != null) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setImageBitmap(item.icon);
            icon = iv;
        } else {
            TextView letter = new TextView(this);
            letter.setText(e.name.substring(0, 1).toUpperCase(java.util.Locale.ROOT));
            letter.setGravity(Gravity.CENTER);
            letter.setIncludeFontPadding(false);
            letter.setTextColor(TILE_LABEL);
            letter.setTypeface(M3.MEDIUM);
            icon = letter;
        }
        LinearLayout tile = cardTile(STORE_TAG + e.id, e.name, item.tint, icon, null, recentTileDp, ROW_STORE);
        FrameLayout card = (FrameLayout) tile.getChildAt(0);
        if (item.icon != null) {
            // Store icons are full-bleed circles: grow the picture to fill the whole circular icon
            // well (the plate cardTile adds just before it) and clip it round, instead of drawing it
            // small inside the well. Normal app tiles are untouched.
            View plate = card.getChildAt(card.indexOfChild(icon) - 1);
            FrameLayout.LayoutParams plp = (FrameLayout.LayoutParams) plate.getLayoutParams();
            FrameLayout.LayoutParams ilp = (FrameLayout.LayoutParams) icon.getLayoutParams();
            ilp.width = ilp.height = plp.width;
            ilp.topMargin = plp.topMargin;
            icon.setLayoutParams(ilp);
            ((ImageView) icon).setScaleType(ImageView.ScaleType.CENTER_CROP);
            icon.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override public void getOutline(View v, android.graphics.Outline o) {
                    o.setOval(0, 0, v.getWidth(), v.getHeight());
                }
            });
            icon.setClipToOutline(true);
        }
        TextView badge = new TextView(this);
        style(badge, 13, M3.ON_PRIMARY, M3.MEDIUM);
        badge.setPadding(dp(10), dp(3), dp(10), dp(4));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        blp.topMargin = blp.rightMargin = dp(8);
        card.addView(badge, blp);
        storeBadges.put(e.id, new Object[] { badge, item });
        showBadge(e.id);
        tile.setOnClickListener(v -> showStoreDetail(item));
        return tile;
    }

    /** The badge for an app: its install progress, a problem, or what Select will do. */
    private void showBadge(String id) {
        Object[] b = storeBadges.get(id);
        if (b == null) return;
        TextView badge = (TextView) b[0];
        Store.Item item = (Store.Item) b[1];
        String busy = Store.status(id);
        String text;
        int fill = M3.PRIMARY, ink = M3.ON_PRIMARY;
        if (busy != null) text = busy;
        else if (Store.problem(id) != null && !"Cancelled".equals(Store.problem(id))) { text = "Failed"; fill = M3.ERROR; ink = M3.BACKGROUND; }
        else if (item.state == Store.UPDATE) text = "Update";
        else if (item.state == Store.INSTALLED) { text = "Installed"; fill = M3.SECONDARY_CONT; ink = M3.ON_SECONDARY_CONT; }
        else text = "Get";
        badge.setText(text);
        badge.setTextColor(ink);
        badge.setBackground(M3.rounded(fill, dp(12)));
    }

    /** The last card in Get apps: how to add your own apps from the phone. */
    private View addAppTile() {
        View t = dashedTile("Add an app", STORE_ADD_TAG, recentTileDp, ROW_STORE);
        t.setOnClickListener(v -> {
            List<CharSequence> opts = new ArrayList<>();
            opts.add("Show the QR code for your phone");
            opts.add("Refresh the app store");
            M3Menu.show(this, "Add an app", "On your phone, open the MaterialTV page and go to Your apps. "
                    + "Add a GitHub repo (owner/name), an https link to an APK, or a MaterialTV web app's manifest link. It shows up here.", opts, which -> {
                if (which == 0) {
                    startActivity(new Intent(this, DeadlinesActivity.class).putExtra(DeadlinesActivity.EXTRA_CUSTOMISE, true));
                } else {
                    Toast.makeText(this, "Refreshing the app store…", Toast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        String msg = Store.refresh(getApplicationContext());
                        ui.post(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
                    }, "m3home-store-refresh").start();
                }
            });
        });
        return t;
    }

    /** The detail sheet: what the app is, where it comes from, and Install / Update / Open / Remove. */
    private void showStoreDetail(final Store.Item item) {
        final Store.Entry e = item.entry;
        if (e.plugin()) { showPluginOffer(e); return; }
        StringBuilder about = new StringBuilder();
        if (!e.description.isEmpty()) about.append(e.description).append('\n');
        if (e.appstore()) about.append("Installs via the Fire TV app store (no direct download from the maker)");
        else about.append("From ").append(e.source());
        if (item.latest != null && !item.latest.isEmpty()) about.append(" · ").append(item.latest);
        if (item.size > 0) about.append(" · ").append(Wallpapers.mb(item.size));
        String busy = Store.status(e.id), problem = Store.problem(e.id);
        if (busy != null) about.append("\nInstalling: ").append(busy);
        else if (problem != null) about.append("\nLast try: ").append(problem);

        final List<CharSequence> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        boolean installed = item.state != Store.GET;
        if (e.appstore() && !installed) {
            labels.add("Install");
            actions.add(() -> {
                if (!Store.openAppstore(this, e.pkg)) Toast.makeText(this, "The Appstore isn't available", Toast.LENGTH_SHORT).show();
            });
        } else if (busy == null && item.state != Store.INSTALLED) {
            labels.add(item.state == Store.UPDATE ? "Update" + (item.latest == null ? "" : " to " + item.latest) : "Install");
            actions.add(() -> {
                storeFocus = storeRow.indexOfChild(storeRow.findViewWithTag(STORE_TAG + e.id));
                Store.install(this, e);
            });
        }
        if (installed) {
            labels.add("Open");
            actions.add(() -> {
                if (!Store.open(this, item.pkg)) Toast.makeText(this, "Couldn't open " + e.name, Toast.LENGTH_SHORT).show();
            });
        }
        if (installed) { // web apps: MaterialTV removes them itself; Android apps: Android asks
            labels.add("Uninstall");
            actions.add(() -> { if (e.webapp()) uninstallWebapp(WebApps.idOf(item.pkg), e.name); else uninstall(item.pkg, e.name); });
        }
        if (e.custom) {
            labels.add("Remove from your apps");
            actions.add(() -> {
                Store.removeCustom(this, e.id);
                dirty = true;
                load();
            });
        }
        if (labels.isEmpty()) {
            Toast.makeText(this, e.name + ": " + busy, Toast.LENGTH_SHORT).show();
            return;
        }
        M3Menu.show(this, e.name, about.toString(), labels, which -> actions.get(which).run());
    }
    // ---- plugins ----------------------------------------------------------------------------

    /** A Get apps plugin: what it is, and Install. Installed plugins live in Settings > Plugins. */
    private void showPluginOffer(final Store.Entry e) {
        String about = (e.description.isEmpty() ? "" : e.description + "\n")
                + "A MaterialTV plugin from " + e.source() + ". It can add widgets, rows, a theme or shortcuts; "
                + "it runs no code on your TV. Manage it in Settings > Plugins.";
        List<CharSequence> labels = new ArrayList<>();
        labels.add("Install plugin");
        M3Menu.show(this, e.name, about, labels, which -> {
            Toast.makeText(this, "Installing " + e.name + "…", Toast.LENGTH_SHORT).show();
            final Context app = getApplicationContext();
            new Thread(() -> {
                String msg = Plugins.install(app, e.url);
                ui.post(() -> Toast.makeText(app, msg, Toast.LENGTH_LONG).show());
            }, "m3home-plugin-install").start();
        });
    }

    /**
     * A plugin tile: a row item's picture, or its icon (a picture or an emoji) on a colour from its
     * name. Select does what it says; Menu (or a long press) offers favourites.
     */
    private View pluginTile(final String tag, final Plugins.Card card, int widthDp, final int row) {
        android.graphics.Bitmap art = Plugins.memoryPicture(card.image);
        View icon = null;
        if (art == null) {
            android.graphics.Bitmap pic = Plugins.isPicture(card.icon) ? Plugins.memoryPicture(card.icon) : null;
            if (pic != null) {
                ImageView iv = new ImageView(this);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iv.setImageBitmap(pic);
                icon = iv;
            } else {
                TextView glyph = new TextView(this);
                glyph.setText(card.icon != null && !Plugins.isPicture(card.icon) ? card.icon
                        : new String(Character.toChars(card.title.codePointAt(0))).toUpperCase(java.util.Locale.ROOT));
                glyph.setGravity(Gravity.CENTER);
                glyph.setIncludeFontPadding(false);
                glyph.setTextColor(TILE_LABEL);
                glyph.setTypeface(M3.MEDIUM);
                icon = glyph;
            }
        }
        LinearLayout tile = cardTile(tag, card.title, Plugins.tint(card.title), icon, art, widthDp, row);
        tile.setOnClickListener(v -> {
            if (card.action != null) Plugins.run(this, card.action);
            else showPluginOptions(tag, card, row);
        });
        tile.setOnLongClickListener(v -> { showPluginOptions(tag, card, row); return true; });
        tile.setOnKeyListener((v, code, ev) -> {
            if (code == KeyEvent.KEYCODE_MENU) {
                if (ev.getAction() == KeyEvent.ACTION_UP) showPluginOptions(tag, card, row);
                return true;
            }
            return false;
        });
        return tile;
    }

    /** Open, and Add to / Remove from favourites for a plugin's own shortcuts. */
    private void showPluginOptions(final String tag, final Plugins.Card card, final int row) {
        final List<CharSequence> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        final Plugins.Action act = card.action;
        if (act != null) {
            labels.add(act.type.equals("webhook") ? "Run" : "Open");
            actions.add(() -> Plugins.run(this, act));
        }
        final String favId = act == null ? null : act.favId();
        if (favId != null) {
            final boolean fav = apps.isFavourite(favId);
            labels.add(fav ? "Remove from favourites" : "Add to favourites");
            actions.add(() -> {
                apps.toggle(favId);
                focusPkg = fav ? null : favId;
                focusRow = fav ? row : ROW_FAVS;
                render();
                if (!fav && !order.contains(ROW_FAVS)) {
                    Toast.makeText(this, "Added. Favourites are hidden; turn them on in Settings.", Toast.LENGTH_LONG).show();
                }
            });
            if (fav && row == ROW_FAVS && favTileCount() > 1) {
                labels.add("Move");
                actions.add(() -> startMove(tag));
            }
        }
        labels.add("Plugin settings");
        actions.add(() -> startActivity(new Intent(this, SettingsActivity.class)
                .putExtra(SettingsActivity.EXTRA_PAGE, SettingsActivity.PLUGINS)));
        M3Menu.show(this, card.title, card.subtitle, labels, which -> actions.get(which).run());
    }

    /** The enabled plugins' widgets as status chips in the header (at most three). */
    private void renderPluginWidgets() {
        pluginChips.removeAllViews();
        for (Plugins.Card w : plugins.widgets) {
            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(dp(14), 0, dp(18), 0);
            chip.setBackground(M3.rounded(M3.SECONDARY_CONT, dp(22)));
            android.graphics.Bitmap pic = Plugins.isPicture(w.icon) ? Plugins.memoryPicture(w.icon) : null;
            if (pic != null) {
                ImageView iv = new ImageView(this);
                iv.setImageBitmap(pic);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(22), dp(22));
                ilp.rightMargin = dp(10);
                chip.addView(iv, ilp);
            } else if (w.icon != null && !Plugins.isPicture(w.icon)) {
                TextView g = new TextView(this);
                g.setText(w.icon);
                style(g, 16, M3.ON_SECONDARY_CONT, M3.MEDIUM);
                g.setPadding(0, 0, dp(8), 0);
                chip.addView(g);
            }
            TextView t = new TextView(this);
            t.setText(w.subtitle == null ? w.title : w.title + " · " + w.subtitle);
            style(t, 15, M3.ON_SECONDARY_CONT, M3.MEDIUM);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setMaxWidth(dp(280));
            chip.addView(t);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
            lp.rightMargin = dp(14);
            pluginChips.addView(chip, lp);
        }
    }

    /** Scrolls so a focused row shows with its title, or a grid tile sits comfortably on screen. */
    private void keepVisible(final View tile, final int row) {
        ui.post(() -> {
            boolean first = row == firstVisibleRow(); // the top row also shows the header
            int sectionTop = first ? 0 : Math.max(0, topIn(sectionView(row)) - dp(24));
            if (row != ROW_GRID) { scroll.smoothScrollTo(0, sectionTop); return; }
            int top = topIn(tile), height = tile.getHeight();
            int view = scroll.getHeight(), at = scroll.getScrollY(), margin = dp(72);
            int target = at;
            if (top - margin < at) target = top - margin;
            else if (top + height + margin > at + view) target = top + height + margin - view;
            if (grid.indexOfChild(tile) < gridCols) target = sectionTop;
            if (target != at) scroll.smoothScrollTo(0, Math.max(0, target));
        });
    }

    /** The first row on screen (Recently used hides itself while empty). */
    private int firstVisibleRow() {
        for (int r : order) if (sectionView(r).getVisibility() == View.VISIBLE) return r;
        return ROW_GRID;
    }

    /** A view's top within the scrolling column. */
    private int topIn(View v) {
        int y = 0;
        while (v != null && v != column) {
            y += v.getTop();
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return y;
    }

    // ---- data -------------------------------------------------------------------------

    private void load() {
        if (loading) return;
        loading = true;
        dirty = false;
        new Thread(() -> {
            final List<Apps.App> list = apps.load();
            final List<Store.Item> store = order.contains(ROW_STORE) ? Store.items(this) : new ArrayList<>();
            final Plugins.Snapshot plug = Plugins.snapshot(this, new ArrayList<>(pluginRowKeys));
            ui.post(() -> {
                loading = false;
                installed = list;
                storeItems = store;
                plugins = plug;
                apps.seedDefaults(list);
                render();
                if (dirty && resumed) load();
            });
        }, "m3home-load").start();
    }

    private Apps.App find(String pkg) {
        for (Apps.App a : installed) if (a.pkg.equals(pkg)) return a;
        return null;
    }

    private void render() {
        String moving = movingPkg;
        endMove(); // the tiles are rebuilt; picked up again below
        String keepPkg = moving != null ? moving : focusPkg;
        int keepRow = moving != null ? ROW_FAVS : focusRow;

        favRow.removeAllViews();
        if (order.contains(ROW_FAVS)) {
            for (String pkg : apps.favourites()) {
                if (pkg.startsWith(Plugins.FAV_PREFIX)) { // a plugin shortcut
                    Plugins.Action act = plugins.favourites.get(pkg);
                    if (act != null) favRow.addView(pluginTile(pkg, new Plugins.Card(act.title, null, null, act.icon, act),
                            favTileDp, ROW_FAVS), rowLp());
                    continue;
                }
                Apps.App a = find(pkg);
                if (a != null) favRow.addView(tile(a, favTileDp, ROW_FAVS), rowLp());
            }
            favRow.addView(addFavouriteTile(), rowLp());
        }

        recentRow.removeAllViews();
        if (order.contains(ROW_RECENTS)) {
            for (String pkg : apps.recents()) {
                if (recentRow.getChildCount() >= SHOWN_RECENTS) break;
                Apps.App a = find(pkg);
                if (a != null) recentRow.addView(tile(a, recentTileDp, ROW_RECENTS), rowLp());
            }
        }
        recentSection.setVisibility(recentRow.getChildCount() == 0 ? View.GONE : View.VISIBLE);

        grid.removeAllViews();
        if (order.contains(ROW_GRID)) {
            List<String> hiddenPkgs = apps.hidden();
            int shown = 0, hiddenCount = 0;
            for (Apps.App app : installed) {
                if (hiddenPkgs.contains(app.pkg)) { hiddenCount++; continue; }
                View t = tile(app, gridTileDp, ROW_GRID);
                GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
                lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                boolean lastColumn = shown % gridCols == gridCols - 1;
                lp.setMargins(0, 0, lastColumn ? 0 : dp(GAP_DP), dp(22));
                grid.addView(t, lp);
                shown++;
            }
            allCount.setText(shown + (shown == 1 ? " app" : " apps"));
            hiddenButton.setVisibility(hiddenCount == 0 ? View.GONE : View.VISIBLE);
            hiddenButtonText.setText("Hidden apps · " + hiddenCount);
        }

        storeRow.removeAllViews();
        storeBadges.clear();
        if (order.contains(ROW_STORE)) {
            for (Store.Item it : storeItems) storeRow.addView(storeTile(it), rowLp());
            storeRow.addView(addAppTile(), rowLp());
        }

        for (int i = 0; i < pluginRowKeys.size(); i++) {
            String key = pluginRowKeys.get(i);
            LinearLayout r = pluginRows.get(i);
            r.removeAllViews();
            List<Plugins.Card> cards = plugins.rows.get(key);
            if (cards != null) {
                for (int j = 0; j < cards.size(); j++) {
                    r.addView(pluginTile(PLUGIN_TAG + key + "#" + j, cards.get(j), recentTileDp, ROW_PLUGIN + i), rowLp());
                }
            }
            pluginSections.get(i).setVisibility(r.getChildCount() == 0 ? View.GONE : View.VISIBLE);
        }
        renderPluginWidgets();

        View target = null;
        if (keepPkg != null) {
            if (order.contains(keepRow)) target = rowView(keepRow).findViewWithTag(keepPkg);
            for (int r : order) {
                if (target == null) target = rowView(r).findViewWithTag(keepPkg);
            }
            // An app store tile went away (installed): stay in the row, on its neighbour.
            if (target == null && keepRow == ROW_STORE && order.contains(ROW_STORE) && storeRow.getChildCount() > 0) {
                target = storeRow.getChildAt(Math.max(0, Math.min(storeFocus, storeRow.getChildCount() - 1)));
            }
            // A plugin row's items changed: stay in the row, at the same place.
            if (target == null && keepRow >= ROW_PLUGIN && order.contains(keepRow)) {
                ViewGroup r = rowView(keepRow);
                if (r.getChildCount() > 0) target = r.getChildAt(Math.max(0, Math.min(pluginFocus, r.getChildCount() - 1)));
            }
        }
        if (target != null) target.requestFocus(); else focusFirstTile();
        if (moving != null && favRow.findViewWithTag(moving) != null) startMove(moving);
        startPendingReorder();
    }

    private ViewGroup rowView(int row) {
        if (row >= ROW_PLUGIN) return pluginRows.get(row - ROW_PLUGIN);
        return row == ROW_FAVS ? favRow : row == ROW_RECENTS ? recentRow : row == ROW_STORE ? storeRow : grid;
    }

    private LinearLayout sectionView(int row) {
        if (row >= ROW_PLUGIN) return pluginSections.get(row - ROW_PLUGIN);
        return row == ROW_FAVS ? favSection : row == ROW_RECENTS ? recentSection : row == ROW_STORE ? storeSection : allSection;
    }

    private LinearLayout.LayoutParams rowLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(GAP_DP + 4);
        return lp;
    }

    /** The first tile of the first row shown, in the user's row order. */
    private void focusFirstTile() {
        if (favRow == null) return;
        for (int r : order) {
            ViewGroup v = rowView(r);
            if (sectionView(r).getVisibility() == View.VISIBLE && v.getChildCount() > 0) {
                v.getChildAt(0).requestFocus();
                return;
            }
        }
    }

    /** Favourite tiles on screen (not counting the "Add favourite" card). */
    private int favTileCount() {
        int n = 0;
        for (int i = 0; i < favRow.getChildCount(); i++) {
            if (!ADD_TAG.equals(favRow.getChildAt(i).getTag())) n++;
        }
        return n;
    }

    // ---- move mode --------------------------------------------------------------------

    /** Settings' "Reorder favourites" asked for move mode: pick up the first favourite. */
    private void startPendingReorder() {
        if (!pendingReorder) return;
        pendingReorder = false;
        getIntent().removeExtra(EXTRA_REORDER);
        if (!order.contains(ROW_FAVS)) {
            Toast.makeText(this, "Favourites are hidden. Turn them on in Settings first.", Toast.LENGTH_LONG).show();
            return;
        }
        if (favTileCount() == 0) {
            Toast.makeText(this, "No favourites yet. Add one with the + card.", Toast.LENGTH_LONG).show();
            View add = favRow.findViewWithTag(ADD_TAG);
            if (add != null) add.requestFocus();
            return;
        }
        startMove((String) favRow.getChildAt(0).getTag());
    }

    /** Lifts a favourite so Left/Right carry it along the row. */
    private void startMove(String pkg) {
        View tile = favRow.findViewWithTag(pkg);
        if (tile == null) return;
        if (movingPkg != null && !movingPkg.equals(pkg)) endMove();
        tile.requestFocus();
        movingPkg = pkg; // after requestFocus, so its focus listener doesn't see another tile moving
        moveKeyDown = -1;
        setLifted(tile, true);
        favHint.setText(favTileCount() > 1 ? "Moving · ‹ › to move · Select to drop" : "Add another favourite to move it");
        favHint.setVisibility(View.VISIBLE);
        keepVisible(tile, ROW_FAVS);
    }

    /** Puts the lifted favourite down where it is (the order is already saved). */
    private void endMove() {
        if (movingPkg == null) return;
        View tile = favRow == null ? null : favRow.findViewWithTag(movingPkg);
        movingPkg = null;
        moveKeyDown = -1;
        if (tile != null) setLifted(tile, false);
        if (favHint != null) favHint.setVisibility(View.GONE);
    }

    /** Swaps the lifted favourite with its neighbour, live: only the neighbour's view moves, so focus stays. */
    private void shiftMove(int dir) {
        final View tile = favRow.findViewWithTag(movingPkg);
        if (tile == null) { endMove(); return; }
        int i = favRow.indexOfChild(tile), j = i + dir;
        if (j < 0 || j >= favRow.getChildCount()) return;
        View other = favRow.getChildAt(j);
        Object tag = other.getTag();
        if (!(tag instanceof String) || ADD_TAG.equals(tag)) return; // the + card stays last
        apps.moveBeside(movingPkg, (String) tag, dir > 0);
        favRow.removeView(other);
        favRow.addView(other, i, rowLp());
        setLifted(tile, true); // refresh which arrows show
        ui.post(() -> {
            // Keep the carried tile (plus a peek at its neighbour) in view as the row scrolls.
            Rect r = new Rect(-dp(64), 0, tile.getWidth() + dp(64), tile.getHeight());
            tile.requestRectangleOnScreen(r, false);
        });
    }

    /** Move mode look: raised, a thicker ring, and ‹ › bubbles on the sides it can still go. */
    private void setLifted(View tile, boolean on) {
        if (!(tile instanceof ViewGroup) || ((ViewGroup) tile).getChildCount() == 0) return;
        View c = ((ViewGroup) tile).getChildAt(0);
        if (!(c instanceof FrameLayout)) return;
        FrameLayout card = (FrameLayout) c;
        View old = card.findViewWithTag(LIFT_TAG);
        if (old != null) card.removeView(old);
        if (card.getTag() instanceof GradientDrawable) {
            ((GradientDrawable) card.getTag()).setStroke(dp(on ? 5 : 3), M3.PRIMARY);
        }
        tile.animate().translationY(on ? -dp(12) : 0f).setDuration(200)
                .setInterpolator(new DecelerateInterpolator()).start();
        if (!on) return;
        int i = favRow.indexOfChild(tile);
        boolean canLeft = i > 0;
        boolean canRight = i + 1 < favRow.getChildCount() && !ADD_TAG.equals(favRow.getChildAt(i + 1).getTag());
        FrameLayout lift = new FrameLayout(this);
        lift.setTag(LIFT_TAG);
        lift.addView(arrow("‹", canLeft), arrowLp(Gravity.START));
        lift.addView(arrow("›", canRight), arrowLp(Gravity.END));
        card.addView(lift, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private TextView arrow(String glyph, boolean visible) {
        TextView t = new TextView(this);
        t.setText(glyph);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(30)); // fixed px: a symbol sized to its bubble
        t.setTextColor(M3.ON_PRIMARY);
        t.setTypeface(M3.MEDIUM);
        t.setPadding(0, 0, 0, dp(3));
        t.setBackground(M3.circle(M3.PRIMARY));
        t.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        return t;
    }

    private FrameLayout.LayoutParams arrowLp(int side) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(36), dp(36), side | Gravity.CENTER_VERTICAL);
        lp.leftMargin = lp.rightMargin = dp(8);
        return lp;
    }

    // ---- actions ----------------------------------------------------------------------

    private void launch(Apps.App app) {
        try {
            // Web apps (Apps.App.webId) open in WebAppActivity with their manifest and the theme.
            startActivity(app.webId != null ? WebApps.launchIntent(this, app.webId) : app.launchIntent());
            apps.recordLaunch(app.pkg);
            recentsChanged = true;
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Couldn't open " + app.label, Toast.LENGTH_SHORT).show();
        }
    }

    private void openSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Settings isn't available", Toast.LENGTH_SHORT).show();
        }
    }

    private void showOptions(final Apps.App app, final int row) {
        final boolean fav = apps.isFavourite(app.pkg);
        final List<CharSequence> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();

        labels.add(fav ? "Remove from favourites" : "Add to favourites");
        actions.add(() -> {
            apps.toggle(app.pkg);
            focusPkg = app.pkg;
            focusRow = fav ? row : ROW_FAVS;
            if (fav && row == ROW_FAVS) focusRow = ROW_GRID;
            render();
        });
        if (fav && row == ROW_FAVS && favTileCount() > 1) {
            labels.add("Move");
            actions.add(() -> startMove(app.pkg));
        }
        if (row == ROW_RECENTS) {
            labels.add("Remove from recently used");
            actions.add(() -> { apps.forgetRecent(app.pkg); focusPkg = null; render(); });
        }
        if (row == ROW_GRID) {
            labels.add("Hide from All apps");
            actions.add(() -> {
                apps.setHidden(app.pkg, true);
                focusPkg = neighbourInGrid(app.pkg);
                focusRow = ROW_GRID;
                render();
                Toast.makeText(this, app.label + " hidden. Find it under Hidden apps.", Toast.LENGTH_SHORT).show();
            });
        }
        labels.add("Change tile picture");
        actions.add(() -> pickTileArt(app));
        if (app.webId != null) { // a web app has no Android app info: offer Uninstall instead
            labels.add("Uninstall");
            actions.add(() -> uninstallWebapp(app.webId, app.label));
            M3Menu.show(this, app.label, labels, which -> actions.get(which).run());
            return;
        }
        if (!app.pkg.equals(getPackageName())) {
            labels.add("Uninstall");
            actions.add(() -> uninstall(app.pkg, app.label));
        }
        labels.add("App info");
        actions.add(() -> {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + app.pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, "App info isn't available", Toast.LENGTH_SHORT).show();
            }
        });

        M3Menu.show(this, app.label, labels, which -> actions.get(which).run());
    }

    /** True for Left on a row's first tile or Right on its last: the remote should just stop there. */
    private boolean atRowEnd(KeyEvent e) {
        int code = e.getKeyCode();
        if (e.getAction() != KeyEvent.ACTION_DOWN
                || (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT)) return false;
        View f = getCurrentFocus();
        if (f == null || !(f.getParent() instanceof LinearLayout)) return false;
        LinearLayout row = (LinearLayout) f.getParent();
        if (row.getOrientation() != LinearLayout.HORIZONTAL || !(row.getParent() instanceof android.widget.HorizontalScrollView)) return false;
        int i = row.indexOfChild(f);
        return code == KeyEvent.KEYCODE_DPAD_RIGHT ? i == row.getChildCount() - 1 : i == 0;
    }

    /** Asks Android to uninstall an app (it shows its own "Do you want to uninstall?" confirm). */
    private void uninstall(String pkg, CharSequence label) {
        try {
            startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Couldn't uninstall " + label + ". Try App info", Toast.LENGTH_SHORT).show();
        }
    }

    /** Removes a MaterialTV web app (after asking) and reloads the rows. */
    private void uninstallWebapp(final String id, final String name) {
        if (id == null) return;
        List<CharSequence> opts = new ArrayList<>();
        opts.add("Uninstall " + name);
        opts.add("Cancel");
        M3Menu.show(this, "Uninstall " + name + "?", "It leaves All apps and Favourites. You can install it again from Get apps.",
                opts, which -> {
                    if (which != 0) return;
                    WebApps.uninstall(this, id);
                    Toast.makeText(this, name + " uninstalled", Toast.LENGTH_SHORT).show();
                    focusPkg = null;
                    dirty = true;
                    load();
                });
    }

    /** The grid tile after (or else before) pkg, so focus stays put when pkg is hidden. */
    private String neighbourInGrid(String pkg) {
        for (int i = 0; i < grid.getChildCount(); i++) {
            if (pkg.equals(grid.getChildAt(i).getTag())) {
                int j = i + 1 < grid.getChildCount() ? i + 1 : i - 1;
                return j >= 0 ? (String) grid.getChildAt(j).getTag() : null;
            }
        }
        return null;
    }

    /** Lets you swap an app's tile for one of your own pictures (or go back to the default). */
    private void pickTileArt(final Apps.App app) {
        final List<java.io.File> pics = apps.artChoices();
        final boolean custom = apps.artPath(app.pkg) != null;
        List<CharSequence> names = new ArrayList<>();
        if (custom) names.add("Use the default tile");
        for (java.io.File f : pics) names.add(prettyName(f));
        if (names.isEmpty()) {
            Toast.makeText(this, "No pictures yet. Put images in the stick's MaterialTV \"tiles\" folder.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        M3Menu.show(this, "Tile picture for " + app.label, names, which -> {
            int i = custom ? which - 1 : which;
            apps.setArt(app.pkg, i < 0 ? null : pics.get(i));
            focusPkg = app.pkg;
            dirty = true;
            load(); // reload so the new picture is decoded off the main thread
        });
    }

    /** "i16-webb-cosmic-cliffs.jpg" -> "Webb cosmic cliffs". */
    private static String prettyName(java.io.File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        n = n.replaceFirst("^[a-z]\\d{2}-", "").replace('-', ' ').replace('_', ' ').trim();
        return n.isEmpty() ? f.getName() : Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    private void showAddFavourite() {
        final List<Apps.App> candidates = new ArrayList<>();
        for (Apps.App a : installed) if (!apps.isFavourite(a.pkg)) candidates.add(a);
        if (candidates.isEmpty()) {
            Toast.makeText(this, "Every app is already a favourite", Toast.LENGTH_SHORT).show();
            return;
        }
        List<CharSequence> names = new ArrayList<>();
        for (Apps.App a : candidates) names.add(a.label);
        M3Menu.show(this, "Add a favourite", names, which -> {
            String pkg = candidates.get(which).pkg;
            apps.toggle(pkg);
            focusPkg = pkg;
            focusRow = ROW_FAVS;
            render();
        });
    }

    private void showHiddenApps() {
        final List<Apps.App> hiddenApps = new ArrayList<>();
        for (Apps.App a : installed) if (apps.isHidden(a.pkg)) hiddenApps.add(a);
        if (hiddenApps.isEmpty()) return;
        List<CharSequence> names = new ArrayList<>();
        for (Apps.App a : hiddenApps) names.add(a.label);
        M3Menu.show(this, "Hidden apps: pick one to show", names, which -> {
            Apps.App a = hiddenApps.get(which);
            apps.setHidden(a.pkg, false);
            focusPkg = a.pkg;
            focusRow = ROW_GRID;
            render();
        });
    }

    // ---- live bits --------------------------------------------------------------------

    private void updateClock() {
        Date now = new Date();
        clock.setText(DateFormat.format(DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm", now));
        date.setText(DateFormat.format("EEEE, d MMMM", now));
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        greeting.setText(hour >= 5 && hour < 12 ? "Good morning"
                : hour >= 12 && hour < 17 ? "Good afternoon"
                : hour >= 17 && hour < 22 ? "Good evening" : "Good night");
    }

    private void updateTailscale() {
        String ip = Net.tailscaleIp(this);
        boolean up = ip != null;
        tsChip.setBackground(up
                ? M3.rounded(M3.SECONDARY_CONT, dp(22))
                : M3.outlined(M3.alpha(M3.SURFACE, 0x66), M3.OUTLINE_VAR, dp(1), dp(22)));
        tsIcon.setImageTintList(ColorStateList.valueOf(up ? M3.PRIMARY : M3.ERROR));
        tsText.setTextColor(up ? M3.ON_SECONDARY_CONT : M3.ON_SURFACE_VAR);
        if (!up) tsText.setText("Tailscale off");
        else if (ip.isEmpty()) tsText.setText("Tailscale on");
        else tsText.setText("Tailscale · " + Net.show(this, ip));
    }
}
