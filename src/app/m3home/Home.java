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
 * The home screen, Material 3 Expressive (dark, baseline purple): greeting and clock, a Tailscale
 * chip and Settings button, then Favourites, Recently used and an All apps grid.
 */
public class Home extends Activity {
    /** Opens Home with the first favourite picked up, ready to move. (1.9.0 contract) */
    static final String EXTRA_REORDER = "reorder";

    private static final int GRID_COLUMNS = 5;
    private static final int GRID_TILE_DP = 156;
    private static final int FAV_TILE_DP = 232;
    private static final int RECENT_TILE_DP = 196;
    private static final int GAP_DP = 20;
    private static final float TILE_RATIO = 0.625f; // 16:10 cards
    private static final int SHOWN_RECENTS = 8;
    private static final long TAILSCALE_POLL_MS = 5000;

    private static final int ROW_FAVS = 0, ROW_RECENTS = 1, ROW_GRID = 2;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Apps apps;
    private List<Apps.App> installed = new ArrayList<>();

    private ScrollView scroll;
    private LinearLayout column;
    private static final String ADD_TAG = "m3home:add-favourite";

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
            dirty = true;
            if (resumed) load();
        }
    };

    // ---- lifecycle --------------------------------------------------------------------

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
        setContentView(buildScreen());

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pf.addDataScheme("package");
        registerReceiver(packageReceiver, pf);
    }

    @Override
    protected void onResume() {
        super.onResume();
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
        ui.removeCallbacks(pollTailscale);
        ui.post(pollTailscale);
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
        try { unregisterReceiver(timeReceiver); } catch (IllegalArgumentException ignored) { }
        ui.removeCallbacks(pollTailscale);
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(packageReceiver); } catch (IllegalArgumentException ignored) { }
        super.onDestroy();
    }

    /** Home pressed while already home: back to the top and the first favourite. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        goTop();
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

    private View buildScreen() {
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new Glow());

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);

        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(48), dp(32), dp(48), dp(56));
        column.setClipChildren(false);
        column.setClipToPadding(false);

        column.addView(buildHeader());
        column.addView(buildUpdateCard());

        // Favourites
        favSection = section("Favourites", null);
        favRow = row();
        favSection.addView(rowScroller(favRow));
        column.addView(favSection);

        // Recently used
        recentSection = section("Recently used", null);
        recentRow = row();
        recentSection.addView(rowScroller(recentRow));
        column.addView(recentSection);

        // All apps
        TextView[] count = new TextView[1];
        allSection = section("All apps", count);
        allCount = count[0];
        grid = new GridLayout(this);
        grid.setColumnCount(GRID_COLUMNS);
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
        column.addView(allSection);

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
        greeting.setLetterSpacing(0.02f);
        left.addView(greeting);

        LinearLayout timeLine = new LinearLayout(this);
        timeLine.setOrientation(LinearLayout.HORIZONTAL);
        timeLine.setBaselineAligned(true);
        clock = new TextView(this);
        style(clock, 60, M3.ON_SURFACE, M3.REGULAR);
        clock.setIncludeFontPadding(false);
        clock.setLetterSpacing(-0.02f);
        date = new TextView(this);
        style(date, 20, M3.ON_SURFACE_VAR, M3.REGULAR);
        date.setPadding(dp(18), 0, 0, 0);
        timeLine.addView(clock);
        timeLine.addView(date);
        LinearLayout.LayoutParams tlLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlLp.topMargin = dp(2);
        left.addView(timeLine, tlLp);
        header.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

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
        head.addView(t);
        if (countOut != null) {
            TextView n = new TextView(this);
            style(n, 16, M3.ON_SURFACE_VAR, M3.REGULAR);
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
        final LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setClipChildren(false);
        tile.setTag(app.pkg);

        final int w = dp(widthDp), h = Math.round(w * TILE_RATIO);
        final float rest = dp(20), focused = dp(32);
        // Every tile is drawn the same M3 way: a tonal gradient from the app's own colour,
        // its sharpest icon, and its name inside the card. Your own picture replaces it all.
        final GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] { M3.tone(app.tint, 1.35f), app.tint, M3.tone(app.tint, 0.72f) });
        bg.setCornerRadius(rest);
        final GradientDrawable ring = M3.outlined(0x00000000, M3.PRIMARY, dp(3), rest);

        final FrameLayout card = new FrameLayout(this);
        card.setBackground(bg);
        card.setClipToOutline(true);
        float nameSp = widthDp >= 200 ? 17 : 14;
        if (app.art != null) {
            ImageView art = new ImageView(this);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.setImageBitmap(app.art);
            card.addView(art, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            View scrim = new View(this);
            scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[] { 0x00000000, 0x00000000, 0xC0000000 }));
            card.addView(scrim, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            // A soft lighter disc behind the icon, so dark and light icons both sit well.
            View plate = new View(this);
            plate.setBackground(M3.circle(0x1FFFFFFF));
            int plateSize = Math.round(h * 0.56f);
            FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(plateSize, plateSize,
                    Gravity.CENTER_HORIZONTAL | Gravity.TOP);
            plp.topMargin = Math.round(h * 0.10f);
            card.addView(plate, plp);
            ImageView icon = new ImageView(this);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setImageDrawable(app.icon);
            int size = Math.round(h * 0.40f);
            FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(size, size, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
            ilp.topMargin = Math.round(h * 0.10f) + (plateSize - size) / 2;
            card.addView(icon, ilp);
        }
        final TextView label = new TextView(this);
        label.setText(app.label);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        label.setMarqueeRepeatLimit(-1);
        label.setHorizontalFadingEdgeEnabled(true);
        label.setGravity(Gravity.CENTER);
        label.setPadding(dp(10), 0, dp(10), Math.round(h * 0.07f));
        style(label, nameSp, 0xE6FFFFFF, M3.MEDIUM);
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
            label.setTextColor(has ? 0xFFFFFFFF : 0xE6FFFFFF);
            label.setSelected(has); // scrolls a long name while the tile is highlighted
            if (has) {
                focusPkg = app.pkg;
                focusRow = row;
                keepVisible(tile, row);
            }
        });
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

    /** The last card in Favourites: a dashed outline with a "+" that opens a list of apps to pin. */
    private View addFavouriteTile() {
        final LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setClipChildren(false);
        tile.setTag(ADD_TAG);

        final int w = dp(FAV_TILE_DP);
        final float rest = dp(18), focused = dp(30);
        final GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x55211F26);
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
        plusBubble.addView(plus, new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER));
        int h = Math.round(w * TILE_RATIO);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(60), dp(60), Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        blp.topMargin = Math.round(h * 0.18f);
        card.addView(plusBubble, blp);
        final TextView label = new TextView(this);
        label.setText("Add favourite");
        label.setGravity(Gravity.CENTER);
        label.setPadding(dp(10), 0, dp(10), Math.round(h * 0.07f));
        style(label, 17, M3.ON_SURFACE_VAR, M3.MEDIUM);
        card.addView(label, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        tile.addView(card, new LinearLayout.LayoutParams(w, h));

        final ValueAnimator[] morph = new ValueAnimator[1];
        tile.setOnFocusChangeListener((v, has) -> {
            if (has) bg.setStroke(dp(3), M3.PRIMARY);
            else bg.setStroke(dp(2), M3.OUTLINE_VAR, dp(10), dp(8));
            bg.setColor(has ? 0x804F378B : 0x55211F26);
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
            if (has) {
                focusPkg = ADD_TAG;
                focusRow = ROW_FAVS;
                keepVisible(tile, ROW_FAVS);
            }
        });
        tile.setOnClickListener(v -> showAddFavourite());
        return tile;
    }

    /** Scrolls so a focused row shows with its title, or a grid tile sits comfortably on screen. */
    private void keepVisible(final View tile, final int row) {
        ui.post(() -> {
            if (row == ROW_FAVS) { scroll.smoothScrollTo(0, 0); return; }
            if (row == ROW_RECENTS) { scroll.smoothScrollTo(0, Math.max(0, topIn(recentSection) - dp(24))); return; }
            int top = topIn(tile), height = tile.getHeight();
            int view = scroll.getHeight(), at = scroll.getScrollY(), margin = dp(72);
            int target = at;
            if (top - margin < at) target = top - margin;
            else if (top + height + margin > at + view) target = top + height + margin - view;
            if (grid.indexOfChild(tile) < GRID_COLUMNS) target = Math.max(0, topIn(allSection) - dp(24));
            if (target != at) scroll.smoothScrollTo(0, Math.max(0, target));
        });
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
            ui.post(() -> {
                loading = false;
                installed = list;
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
        String keepPkg = focusPkg;
        int keepRow = focusRow;

        favRow.removeAllViews();
        for (String pkg : apps.favourites()) {
            Apps.App a = find(pkg);
            if (a != null) favRow.addView(tile(a, FAV_TILE_DP, ROW_FAVS), rowLp());
        }
        favRow.addView(addFavouriteTile(), rowLp());

        recentRow.removeAllViews();
        for (String pkg : apps.recents()) {
            if (recentRow.getChildCount() >= SHOWN_RECENTS) break;
            Apps.App a = find(pkg);
            if (a != null) recentRow.addView(tile(a, RECENT_TILE_DP, ROW_RECENTS), rowLp());
        }
        recentSection.setVisibility(recentRow.getChildCount() == 0 ? View.GONE : View.VISIBLE);

        grid.removeAllViews();
        List<String> hiddenPkgs = apps.hidden();
        int shown = 0, hiddenCount = 0;
        for (Apps.App app : installed) {
            if (hiddenPkgs.contains(app.pkg)) { hiddenCount++; continue; }
            View t = tile(app, GRID_TILE_DP, ROW_GRID);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            boolean lastColumn = shown % GRID_COLUMNS == GRID_COLUMNS - 1;
            lp.setMargins(0, 0, lastColumn ? 0 : dp(GAP_DP), dp(22));
            grid.addView(t, lp);
            shown++;
        }
        allCount.setText(shown + (shown == 1 ? " app" : " apps"));
        hiddenButton.setVisibility(hiddenCount == 0 ? View.GONE : View.VISIBLE);
        hiddenButtonText.setText("Hidden apps · " + hiddenCount);

        View target = null;
        if (keepPkg != null) {
            target = rowView(keepRow).findViewWithTag(keepPkg);
            for (int r = ROW_FAVS; target == null && r <= ROW_GRID; r++) {
                target = rowView(r).findViewWithTag(keepPkg);
            }
        }
        if (target != null) target.requestFocus(); else focusFirstTile();
    }

    private ViewGroup rowView(int row) {
        return row == ROW_FAVS ? favRow : row == ROW_RECENTS ? recentRow : grid;
    }

    private LinearLayout.LayoutParams rowLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(GAP_DP + 4);
        return lp;
    }

    private void focusFirstTile() {
        if (favRow != null && favRow.getChildCount() > 0) favRow.getChildAt(0).requestFocus();
        else if (recentRow != null && recentRow.getChildCount() > 0) recentRow.getChildAt(0).requestFocus();
        else if (grid != null && grid.getChildCount() > 0) grid.getChildAt(0).requestFocus();
    }

    // ---- actions ----------------------------------------------------------------------

    private void launch(Apps.App app) {
        try {
            startActivity(app.launchIntent());
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
        if (fav && row == ROW_FAVS) {
            labels.add("Move left");
            actions.add(() -> { apps.move(app.pkg, -1); focusPkg = app.pkg; focusRow = ROW_FAVS; render(); });
            labels.add("Move right");
            actions.add(() -> { apps.move(app.pkg, +1); focusPkg = app.pkg; focusRow = ROW_FAVS; render(); });
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
                : M3.outlined(0x66211F26, M3.OUTLINE_VAR, dp(1), dp(22)));
        tsIcon.setImageTintList(ColorStateList.valueOf(up ? M3.PRIMARY : M3.ERROR));
        tsText.setTextColor(up ? M3.ON_SECONDARY_CONT : M3.ON_SURFACE_VAR);
        if (!up) tsText.setText("Tailscale off");
        else if (ip.isEmpty()) tsText.setText("Tailscale on");
        else tsText.setText("Tailscale · " + Net.show(this, ip));
    }
}
