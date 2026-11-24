package app.m3home;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;

/**
 * "Set up your Fire TV" (first run, or Settings) and its opposite, "Undo setup", as a guided,
 * remote-friendly wizard: Welcome › Connect to ADB › Choose › Setting up › Done. MaterialTV connects
 * to the TV's own ADB and does the chosen steps, showing each one as it goes.
 */
public class SetupActivity extends Activity {
    static final String EXTRA_UNDO = "undo";

    private static final int WELCOME = 0, ADB = 1, CHOOSE = 2, RUN = 3, DONE = 4, PAGES = 5;
    private static final String[] SETUP_STEPS = { "Welcome", "Connect", "Choose", "Set up", "Done" };
    private static final String[] UNDO_STEPS = { "Undo setup", "Connect", "Choose", "Undo", "Done" };
    private static final String TAILSCALE = "com.tailscale.ipn";

    private final Setup.Choices choices = new Setup.Choices();
    private final Setup.UndoChoices undoChoices = new Setup.UndoChoices();
    private boolean undo;

    // Page machinery
    private FrameLayout pages;
    private View current, focusFirst;
    private int page = -1;
    private LinearLayout dots;
    private TextView stepLabel;

    // ADB page state (survives leaving and coming back to the page)
    private Adb adb;
    private boolean connecting, approving;
    private String adbError, adbNote;
    private LinearLayout adbStatus;
    private TextView adbButton;

    // Run page state
    private boolean running, runFailed;
    private String runError;
    private final List<String> results = new ArrayList<>();
    private TextView runStep;
    private View runDot;
    private ObjectAnimator runPulse;
    private LinearLayout runLog, runButtons;
    private ScrollView runScroll;

    private int dp(float v) { return M3.dp(this, v); }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        M3.init(this);
        undo = getIntent().getBooleanExtra(EXTRA_UNDO, false);
        Prefs.of(this).edit().putBoolean("setup_offered", true).apply();

        FrameLayout root = new FrameLayout(this);
        root.setBackground(new Glow());

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(120), dp(36), dp(120), 0);
        dots = new LinearLayout(this);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < PAGES; i++) {
            View d = new View(this);
            d.setBackground(M3.rounded(M3.OUTLINE_VAR, dp(5)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(10), dp(10));
            lp.rightMargin = dp(8);
            dots.addView(d, lp);
        }
        header.addView(dots);
        stepLabel = text("", 15, M3.ON_SURFACE_VAR, M3.MEDIUM);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.leftMargin = dp(10);
        header.addView(stepLabel, sl);
        header.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        tvAddress = text("", 17, M3.ON_SURFACE_VAR, M3.MEDIUM);
        header.addView(tvAddress);
        root.addView(header, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        pages = new FrameLayout(this);
        pages.setClipChildren(false);
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        pl.topMargin = dp(84);
        root.addView(pages, pl);
        setContentView(root);

        show(WELCOME, true);
    }

    @Override
    public void onBackPressed() {
        switch (page) {
            case WELCOME: finish(); break;
            case ADB: show(WELCOME, false); break;
            case CHOOSE: show(ADB, false); break;
            case RUN:
                if (running) return; // don't walk away from a half-done setup
                if (runFailed) show(ADB, false);
                else show(DONE, true);
                break;
            default: finish();
        }
    }

    @Override
    protected void onDestroy() {
        if (runPulse != null) runPulse.cancel();
        watcher.removeCallbacksAndMessages(null);
        if (adb != null) { adb.close(); adb = null; } // while running, the run thread owns (and closes) it
        super.onDestroy();
    }

    // ---- page switching -----------------------------------------------------------------------

    private void show(int target, boolean forward) {
        final View old = current;
        page = target;
        focusFirst = null;
        View v;
        switch (target) {
            case WELCOME: v = undo ? buildUndoIntro() : buildWelcome(); break;
            case ADB: v = buildAdb(); break;
            case CHOOSE: v = buildChoose(); break;
            case RUN: v = buildRun(); break;
            default: v = buildDone();
        }
        pages.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        current = v;

        float dx = dp(72) * (forward ? 1 : -1);
        v.setAlpha(0f);
        v.setTranslationX(dx);
        v.animate().alpha(1f).translationX(0f).setDuration(260).setStartDelay(60)
                .setInterpolator(new DecelerateInterpolator(2f)).start();
        if (old != null) {
            if (old instanceof ViewGroup) ((ViewGroup) old).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            old.animate().alpha(0f).translationX(-dx * 0.6f).setDuration(180).setStartDelay(0)
                    .setInterpolator(new AccelerateInterpolator()).withEndAction(() -> pages.removeView(old)).start();
        }
        if (focusFirst != null) focusFirst.requestFocus();
        updateHeader();
    }

    private TextView tvAddress;

    private void updateHeader() {
        String ip = Qr.lanIp(this);
        tvAddress.setText(ip == null ? "This TV isn't on a network" : "This TV  ·  " + Net.show(this, ip));
        String[] names = undo ? UNDO_STEPS : SETUP_STEPS;
        stepLabel.setText("Step " + (page + 1) + " of " + PAGES + "  ·  " + names[page]);
        for (int i = 0; i < PAGES; i++) {
            final View d = dots.getChildAt(i);
            ((GradientDrawable) d.getBackground()).setColor(i <= page ? M3.PRIMARY : M3.OUTLINE_VAR);
            final int want = dp(i == page ? 28 : 10);
            final int from = d.getLayoutParams().width;
            if (from == want) continue;
            ValueAnimator a = ValueAnimator.ofInt(from, want);
            a.setDuration(260);
            a.setInterpolator(new DecelerateInterpolator(2f));
            a.addUpdateListener(an -> {
                d.getLayoutParams().width = (Integer) an.getAnimatedValue();
                d.requestLayout();
            });
            a.start();
        }
    }

    // ---- page 1: welcome / undo intro -----------------------------------------------------------

    private View buildWelcome() {
        LinearLayout col = new LinearLayout(this);
        ScrollView sv = singleColumn(col);
        col.addView(text("Welcome to MaterialTV", 40, M3.ON_SURFACE, M3.REGULAR));
        TextView body = body("A calm, Material You home screen for your Fire TV: your apps, big and simple, with no ads. "
                + "This short setup can also tidy up the Fire TV for you. You choose every step, and you can undo it all later from Settings.");
        body.setPadding(0, dp(10), 0, dp(20));
        col.addView(body);

        LinearLayout card = card();
        featureRow(card,
                "Clean up Fire TV", "dumbtv's debloat switches off Amazon ads and tracking",
                "Privacy settings", "Stops usage data and ad tracking where it can");
        featureRow(card,
                "SmartTube and Tailscale", "Optional: ad-free YouTube and your private network",
                "Undo anytime", "Settings › Undo setup puts everything back");
        col.addView(card, matchWrap(0));

        TextView start = pill("Get started", true);
        start.setOnClickListener(v -> show(ADB, true));
        TextView skip = pill("Skip for now", false);
        skip.setOnClickListener(v -> finish());
        col.addView(buttonRow(start, skip), matchWrap(dp(28)));
        focusFirst = start;
        return sv;
    }

    private View buildUndoIntro() {
        LinearLayout col = new LinearLayout(this);
        ScrollView sv = singleColumn(col);
        col.addView(text("Undo setup", 40, M3.ON_SURFACE, M3.REGULAR));
        TextView body = body("This puts back what MaterialTV's setup changed on your Fire TV. You'll pick exactly what to undo on the next steps.");
        body.setPadding(0, dp(10), 0, dp(20));
        col.addView(body);

        LinearLayout card = card();
        featureRow(card,
                "Amazon's apps back on", "Everything the setup switched off",
                "Settings restored", "Privacy settings and background limits put back");
        featureRow(card,
                "Optional removals", "SmartTube and Tailscale",
                "MaterialTV too, if you like", "Gives the Home button back to Amazon");
        if (!Setup.hasLedger(this)) {
            TextView note = text("No change log from an earlier setup was found, so MaterialTV will use dumbtv's standard restore lists.",
                    15, M3.ON_SURFACE_VAR, M3.REGULAR);
            note.setPadding(dp(4), dp(12), 0, 0);
            card.addView(note);
        }
        col.addView(card, matchWrap(0));

        TextView go = pill("Continue", true);
        go.setOnClickListener(v -> show(ADB, true));
        TextView later = pill("Not now", false);
        later.setOnClickListener(v -> finish());
        col.addView(buttonRow(go, later), matchWrap(dp(28)));
        focusFirst = go;
        return sv;
    }

    private void featureRow(LinearLayout card, String t1, String s1, String t2, String s2) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(feature(t1, s1), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(24);
        row.addView(feature(t2, s2), lp);
        card.addView(row, matchWrap(card.getChildCount() == 0 ? 0 : dp(14)));
    }

    private View feature(String title, String subtitle) {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.HORIZONTAL);
        View dot = new View(this);
        dot.setBackground(M3.circle(M3.PRIMARY));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(10), dp(10));
        dl.topMargin = dp(9);
        dl.rightMargin = dp(14);
        f.addView(dot, dl);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.addView(text(title, 19, M3.ON_SURFACE, M3.MEDIUM));
        TextView s = text(subtitle, 15, M3.ON_SURFACE_VAR, M3.REGULAR);
        s.setPadding(0, dp(2), 0, 0);
        l.addView(s);
        f.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return f;
    }

    // ---- page 2: ADB debugging --------------------------------------------------------------------

    // Phone / computer setup: Amazon blocks apps from using the TV's own ADB on current Fire OS,
    // so the TV shows a QR code and a command, and watches the change log for the result.
    private static final String SITE = "https://brick-bread.github.io/MaterialTV/setup.html";
    private final String pairCode = makeCode();
    private long ledgerAtStart = -1;
    private boolean ledgerExistedAtStart;
    private final android.os.Handler watcher = new android.os.Handler(android.os.Looper.getMainLooper());

    private static String makeCode() {
        String abc = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        java.security.SecureRandom r = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) sb.append(abc.charAt(r.nextInt(abc.length())));
        return sb.toString();
    }

    private View buildAdb() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        View v = singleColumn(col);
        String ip = Qr.lanIp(this);
        String shown = ip == null ? "TV-IP" : Net.show(this, ip);

        col.addView(text(undo ? "Undo from your phone or computer" : "Set up from your phone or computer", 34, M3.ON_SURFACE, M3.REGULAR));
        TextView b = body("Amazon doesn't let apps change these Fire TV settings by themselves, so your phone or computer does it over Wi-Fi. This screen moves on by itself when it's done.");
        b.setPadding(0, dp(8), 0, dp(20));
        col.addView(b);

        // QR  |  This TV + pairing code  |  how it works
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout qrCard = new FrameLayout(this);
        qrCard.setBackground(M3.rounded(0xFFFFFFFF, dp(24)));
        qrCard.setPadding(dp(14), dp(14), dp(14), dp(14));
        android.widget.ImageView qr = new android.widget.ImageView(this);
        if (ip != null) qr.setImageBitmap(Qr.make(SITE + "?tv=" + ip + "&c=" + pairCode + (undo ? "&undo=1" : ""), dp(196)));
        qrCard.addView(qr, new FrameLayout.LayoutParams(dp(196), dp(196)));
        row.addView(qrCard, new LinearLayout.LayoutParams(dp(224), dp(224)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(32), 0, dp(32), 0);
        info.addView(text("This TV", 16, M3.PRIMARY, M3.MEDIUM));
        TextView ipText = text(ip == null ? "Not on a network" : Net.show(this, ip), 46, M3.ON_SURFACE, M3.REGULAR);
        ipText.setSingleLine(true);
        info.addView(ipText);
        TextView pc = text("Pairing code", 16, M3.PRIMARY, M3.MEDIUM);
        pc.setPadding(0, dp(14), 0, 0);
        info.addView(pc);
        TextView code = text(pairCode, 40, M3.ON_SURFACE, M3.MEDIUM);
        code.setSingleLine(true);
        code.setLetterSpacing(0.14f);
        info.addView(code);
        row.addView(info, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout steps = card();
        steps.addView(text("How it works", 16, M3.PRIMARY, M3.MEDIUM));
        numbered(steps, 1, "ADB debugging on: Settings › My Fire TV › Developer options.");
        numbered(steps, 2, "Android phone: scan the code with the MaterialTV app or the camera.");
        numbered(steps, 3, "Computer or iPhone owner: run the command below on a computer.");
        numbered(steps, 4, "On the TV, tick Always allow and press OK.");
        col.addView(row, matchWrap(0));

        TextView open = pill("Open Fire TV settings", false);
        open.setOnClickListener(x -> openFireTvSettings());
        adbButton = pill("Try on this TV", false);
        adbButton.setOnClickListener(x -> onAdbButton());
        col.addView(buttonRow(open, adbButton), matchWrap(dp(22)));

        adbStatus = new LinearLayout(this);
        adbStatus.setOrientation(LinearLayout.VERTICAL);
        col.addView(adbStatus, matchWrap(dp(16)));
        col.addView(steps, matchWrap(dp(16)));

        LinearLayout cmd = card();
        cmd.addView(text("On a computer", 16, M3.PRIMARY, M3.MEDIUM));
        TextView c1 = text("Windows:  $env:M3_TV=\"" + shown + "\"" + (undo ? "; $env:M3_UNDO=\"1\"" : "")
                + "; irm https://raw.githubusercontent.com/Brick-Bread/MaterialTV/main/scripts/setup.ps1 | iex",
                16, M3.ON_SURFACE, Typeface.MONOSPACE);
        c1.setPadding(0, dp(8), 0, dp(6));
        cmd.addView(c1);
        TextView c2 = text("Mac/Linux:  curl -fsSL https://raw.githubusercontent.com/Brick-Bread/MaterialTV/main/scripts/setup.sh | bash -s -- --tv "
                + shown + (undo ? " --undo" : ""), 16, M3.ON_SURFACE, Typeface.MONOSPACE);
        cmd.addView(c2);
        col.addView(cmd, matchWrap(dp(16)));
        refreshAdb();

        java.io.File f = Setup.ledgerFile(this);
        ledgerExistedAtStart = f.exists();
        ledgerAtStart = f.exists() ? f.lastModified() : -1;
        watcher.removeCallbacksAndMessages(null);
        watcher.postDelayed(watch, 3000);

        focusFirst = open;
        return v;
    }

    /** Notices when a phone or computer has finished, by watching the change log on this TV. */
    private final Runnable watch = new Runnable() {
        @Override public void run() {
            if (page != ADB || isDestroyed()) return;
            java.io.File f = Setup.ledgerFile(SetupActivity.this);
            boolean finished = undo ? (ledgerExistedAtStart && !f.exists())
                    : (f.exists() && f.lastModified() != ledgerAtStart && f.length() > 2);
            if (finished) {
                results.clear();
                summariseLedger(f);
                Prefs.of(SetupActivity.this).edit().putBoolean("setup_done", !undo).apply();
                Secure.ensureGuard(SetupActivity.this);
                show(DONE, true);
                return;
            }
            watcher.postDelayed(this, 3000);
        }
    };

    /** A few plain lines for the Done page from the change log a phone or computer wrote. */
    private void summariseLedger(java.io.File f) {
        if (undo) { results.add("Everything you chose was put back."); return; }
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            byte[] buf = new byte[(int) Math.min(f.length(), 512 * 1024)];
            int n = in.read(buf);
            in.close();
            org.json.JSONObject o = new org.json.JSONObject(new String(buf, 0, Math.max(n, 0), "UTF-8"));
            if (o.optBoolean("permissions")) results.add("MaterialTV permissions are set up.");
            org.json.JSONArray off = o.optJSONArray("disabled");
            if (off != null && off.length() > 0) results.add("Switched off " + off.length() + " Amazon apps.");
            org.json.JSONObject st = o.optJSONObject("settings_before");
            if (st != null && st.length() > 0) results.add("Applied " + st.length() + " privacy settings.");
            org.json.JSONArray inst = o.optJSONArray("installed");
            for (int i = 0; inst != null && i < inst.length(); i++) {
                String p = inst.optString(i);
                results.add(p.contains("tailscale") ? "Installed Tailscale." : p.contains("smarttube") ? "Installed SmartTube." : "Installed " + p + ".");
            }
            if (o.has("vpn_before")) choices.tailscale = true;
        } catch (Exception ignored) {
            results.add("Setup finished.");
        }
    }

    private void numbered(LinearLayout card, int n, String s) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        TextView num = text(String.valueOf(n), 15, M3.ON_PRIMARY_CONT, M3.MEDIUM);
        num.setGravity(Gravity.CENTER);
        num.setBackground(M3.circle(M3.PRIMARY_CONT));
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(dp(28), dp(28));
        nl.rightMargin = dp(14);
        r.addView(num, nl);
        TextView t = text(s, 18, M3.ON_SURFACE, M3.REGULAR);
        t.setLineSpacing(dp(2), 1f);
        r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(r, matchWrap(dp(12)));
    }

    private void onAdbButton() {
        if (connecting) return;
        if (adb != null) { show(CHOOSE, true); return; }
        connect();
    }

    private void connect() {
        connecting = true;
        approving = false;
        adbError = null;
        adbNote = null;
        refreshAdb();
        new Thread(() -> {
            try {
                final Adb a = Adb.connect(this, () -> runOnUiThread(() -> {
                    approving = true;
                    if (!isDestroyed() && page == ADB) refreshAdb();
                }));
                runOnUiThread(() -> {
                    connecting = false;
                    approving = false;
                    if (isDestroyed()) { a.close(); return; }
                    if (adb != null) adb.close();
                    adb = a;
                    if (page != ADB) return;
                    refreshAdb();
                    adbButton.postDelayed(() -> { if (page == ADB && adb != null && !isDestroyed()) show(CHOOSE, true); }, 900);
                });
            } catch (ConnectException e) {
                adbFailed("ADB debugging looks off — turn it on and press Connect again.");
            } catch (IOException e) {
                adbFailed(Adb.looksBlocked(e)
                        ? "This Fire TV doesn't let apps do it themselves. Use your phone or computer (left)."
                        : "Couldn't connect: " + e.getMessage());
            } catch (RuntimeException e) {
                adbFailed("Couldn't connect: " + e);
            }
        }, "m3home-setup-connect").start();
    }

    private void adbFailed(final String message) {
        runOnUiThread(() -> {
            connecting = false;
            approving = false;
            adbError = message;
            if (!isDestroyed() && page == ADB) {
                refreshAdb();
                adbButton.requestFocus();
            }
        });
    }

    /** Redraws the ADB page's status card and button from the connection state. */
    private void refreshAdb() {
        if (adbStatus == null) return;
        adbStatus.removeAllViews();
        adbStatus.setVisibility(View.VISIBLE);
        if (adb != null) {
            adbButton.setText("Next");
            statusCard(M3.SECONDARY_CONT, "✓  Connected", "MaterialTV can now set up your Fire TV.", M3.PRIMARY, false);
        } else if (connecting && approving) {
            adbButton.setText("Waiting…");
            statusCard(M3.PRIMARY_CONT, "On your TV",
                    "Tick “Always allow from this computer” and press OK.", M3.ON_PRIMARY_CONT, true);
        } else if (connecting) {
            adbButton.setText("Connecting…");
            statusCard(0xCC1D1B20, "Connecting to your TV…", null, M3.ON_SURFACE, true);
        } else if (adbError != null) {
            adbButton.setText("Try on this TV");
            statusCard(0xCC1D1B20, adbError, null, M3.ERROR, false);
        } else if (adbNote != null) {
            adbButton.setText("Try on this TV");
            statusCard(0xCC1D1B20, adbNote, null, M3.ON_SURFACE_VAR, false);
        } else {
            adbButton.setText("Try on this TV");
            statusCard(0xCC1D1B20, "Waiting for your phone or computer…", null, M3.ON_SURFACE_VAR, true);
        }
    }

    private void statusCard(int bg, String title, String body, int titleColor, boolean pulse) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(M3.rounded(bg, dp(24)));
        c.setPadding(dp(24), dp(18), dp(24), dp(18));
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        if (pulse) {
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(12), dp(12));
            dl.rightMargin = dp(14);
            top.addView(pulsingDot(titleColor), dl);
        }
        top.addView(text(title, body != null ? 22 : 19, titleColor, M3.MEDIUM),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        c.addView(top);
        if (body != null) {
            TextView b = text(body, 19, titleColor, M3.REGULAR);
            b.setPadding(0, dp(6), 0, 0);
            c.addView(b);
        }
        adbStatus.addView(c, matchWrap(0));
        c.setAlpha(0f);
        c.setScaleX(0.96f);
        c.setScaleY(0.96f);
        c.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(new DecelerateInterpolator(2f)).start();
    }

    private void openFireTvSettings() {
        Intent i = new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException e2) {
                adbNote = "Couldn't open Settings from here. Use the Settings icon on the Fire TV home screen.";
                refreshAdb();
            }
        }
    }

    // ---- page 3: choose -----------------------------------------------------------------------------

    private View buildChoose() {
        LinearLayout[] cols = new LinearLayout[2];
        View v = twoColumns(cols);
        LinearLayout left = cols[0], right = cols[1];

        left.addView(text(undo ? "Choose what to undo" : "Choose what to set up", 36, M3.ON_SURFACE, M3.REGULAR));
        TextView b = body(undo
                ? "Everything is on except removing MaterialTV itself."
                : "The recommended steps are already on. Everything can be undone later from MaterialTV settings.");
        b.setPadding(0, dp(10), 0, 0);
        left.addView(b);

        TextView go = pill(undo ? "Undo" : "Set up", true);
        go.setOnClickListener(x -> onGo());
        TextView back = pill("Back", false);
        back.setOnClickListener(x -> show(ADB, false));
        left.addView(go, pillParams(dp(28)));
        left.addView(back, pillParams(dp(14)));

        LinearLayout card = card();
        card.setPadding(dp(8), dp(8), dp(8), dp(8));
        if (undo) {
            toggle(card, "Undo debloat and privacy", "Switch Amazon's apps back on and restore settings",
                    () -> undoChoices.debloat, x -> undoChoices.debloat = x);
            toggle(card, "Remove SmartTube", "And switch the Amazon YouTube app back on",
                    () -> undoChoices.smartTube, x -> undoChoices.smartTube = x);
            toggle(card, "Remove Tailscale", "Stops the always-on VPN and uninstalls it",
                    () -> undoChoices.tailscale, x -> undoChoices.tailscale = x);
            toggle(card, "Uninstall MaterialTV too", "Removes this app and gives the Home button back to Amazon",
                    () -> undoChoices.m3home, x -> undoChoices.m3home = x);
        } else {
            toggle(card, "MaterialTV permissions", "Home button, hold Home for settings, screensaver, now playing, updates",
                    () -> choices.permissions, x -> choices.permissions = x);
            toggle(card, "Debloat and privacy", "dumbtv's cleanup: switch off Amazon ads and tracking, limit what can't be switched off, privacy settings",
                    () -> choices.debloat, x -> choices.debloat = x);
            toggle(card, "Keep Alexa and the voice remote", "Turn off to switch Alexa and the microphone app off too",
                    () -> choices.keepAlexa, x -> choices.keepAlexa = x);
            toggle(card, "SmartTube instead of YouTube", "Ad-free YouTube app, downloaded from its GitHub release and checksum-checked",
                    () -> choices.smartTube, x -> choices.smartTube = x);
            toggle(card, "Tailscale", "Official app from pkgs.tailscale.com, set as the always-on VPN; you sign in on the TV",
                    () -> choices.tailscale, x -> choices.tailscale = x);
        }
        right.addView(card, matchWrap(0));

        focusFirst = go;
        return v;
    }

    private void onGo() {
        if (adb == null) { show(ADB, false); return; }
        if (undo) {
            List<CharSequence> o = new ArrayList<>();
            o.add(undoChoices.m3home ? "Yes, undo and uninstall MaterialTV" : "Yes, undo it");
            o.add("Cancel");
            M3Menu.show(this, "Undo the setup?", o, which -> { if (which == 0 && page == CHOOSE) startRun(); });
        } else {
            startRun();
        }
    }

    // ---- page 4: running ------------------------------------------------------------------------------

    private void startRun() {
        if (running || adb == null) return;
        final Adb a = adb;
        adb = null; // the run thread owns it now and closes it when done
        running = true;
        runFailed = false;
        runError = null;
        results.clear();
        show(RUN, true);
        new Thread(() -> {
            String err = null;
            try {
                if (undo) Setup.undo(this, a, undoChoices, this::say);
                else Setup.run(this, a, choices, this::say);
            } catch (IOException e) {
                err = e.getMessage() != null ? e.getMessage() : e.toString();
            } catch (RuntimeException e) {
                err = e.toString();
            } finally {
                a.close();
            }
            final String error = err;
            runOnUiThread(() -> { if (!isDestroyed()) runFinished(error); });
        }, "m3home-setup").start();
    }

    private View buildRun() {
        LinearLayout[] cols = new LinearLayout[2];
        View v = twoColumns(cols);
        LinearLayout left = cols[0], right = cols[1];

        left.addView(text(undo ? "Undoing setup" : "Setting up", 36, M3.ON_SURFACE, M3.REGULAR));
        LinearLayout now = new LinearLayout(this);
        now.setOrientation(LinearLayout.HORIZONTAL);
        now.setGravity(Gravity.CENTER_VERTICAL);
        runDot = new View(this);
        runDot.setBackground(M3.circle(M3.PRIMARY));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(14), dp(14));
        dl.rightMargin = dp(14);
        now.addView(runDot, dl);
        runPulse = ObjectAnimator.ofFloat(runDot, "alpha", 1f, 0.2f);
        runPulse.setDuration(650);
        runPulse.setRepeatMode(ValueAnimator.REVERSE);
        runPulse.setRepeatCount(ValueAnimator.INFINITE);
        runPulse.start();
        runStep = text(running ? "Starting…" : (runFailed ? "Stopped" : "Finished"), 20, M3.PRIMARY, M3.MEDIUM);
        now.addView(runStep, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        left.addView(now, matchWrap(dp(18)));
        TextView b = body("Keep the TV on. Downloads can take a few minutes.");
        b.setPadding(0, dp(12), 0, 0);
        left.addView(b);
        runButtons = new LinearLayout(this);
        runButtons.setOrientation(LinearLayout.VERTICAL);
        runButtons.setClipChildren(false);
        left.addView(runButtons, matchWrap(dp(14)));

        // The log fills the right column; it's focusable so the remote can scroll it.
        runLog = right;
        runScroll = (ScrollView) right.getParent();
        runScroll.setBackground(M3.rounded(0xCC1D1B20, dp(24)));
        runScroll.setPadding(dp(24), dp(10), dp(24), dp(18));
        runScroll.setClipToPadding(true);
        runScroll.setFocusable(true);
        runScroll.setFocusableInTouchMode(false);
        focusFirst = runScroll;
        return v;
    }

    private void say(final String s) {
        runOnUiThread(() -> {
            if (isDestroyed()) return;
            boolean detail = s.startsWith("  ");
            if (detail && !s.contains("Downloading")) results.add(s.trim());
            if (!detail && runStep != null) runStep.setText(s);
            if (runLog == null) return;
            TextView t = text(detail ? s.trim() : s, detail ? 17 : 18, detail ? M3.ON_SURFACE_VAR : M3.ON_SURFACE,
                    detail ? M3.REGULAR : M3.MEDIUM);
            t.setPadding(detail ? dp(16) : 0, dp(detail ? 2 : 10), 0, 0);
            runLog.addView(t);
            t.setAlpha(0f);
            t.setTranslationY(dp(8));
            t.animate().alpha(1f).translationY(0f).setDuration(180).start();
            runScroll.post(() -> runScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void runFinished(String error) {
        running = false;
        if (runPulse != null) runPulse.cancel();
        watcher.removeCallbacksAndMessages(null);
        if (runDot != null) runDot.setAlpha(1f);
        if (error == null) {
            Prefs.of(this).edit().putBoolean("setup_done", !undo).apply();
            runStep.setText(undo ? "Undo finished" : "Setup finished");
            runStep.postDelayed(() -> { if (page == RUN && !isDestroyed()) show(DONE, true); }, 1000);
            return;
        }
        runFailed = true;
        runError = error;
        ((GradientDrawable) runDot.getBackground()).setColor(M3.ERROR);
        runStep.setText("Something went wrong");
        runStep.setTextColor(M3.ERROR);
        TextView t = text("Stopped: " + error, 18, M3.ERROR, M3.MEDIUM);
        t.setPadding(0, dp(12), 0, 0);
        runLog.addView(t);
        runScroll.post(() -> runScroll.fullScroll(View.FOCUS_DOWN));
        TextView again = pill("Try again", true);
        again.setOnClickListener(x -> show(ADB, false));
        TextView close = pill("Close", false);
        close.setOnClickListener(x -> finish());
        runButtons.addView(again, pillParams(dp(14)));
        runButtons.addView(close, pillParams(dp(14)));
        adbNote = (undo ? "Undo stopped: " : "Setup stopped: ") + error + ". Press Connect to try again.";
        again.requestFocus();
    }

    // ---- page 5: done ---------------------------------------------------------------------------------

    private View buildDone() {
        LinearLayout col = new LinearLayout(this);
        ScrollView sv = singleColumn(col);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView check = text("✓", 30, M3.ON_PRIMARY_CONT, M3.MEDIUM);
        check.setGravity(Gravity.CENTER);
        check.setBackground(M3.circle(M3.PRIMARY_CONT));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(dp(64), dp(64));
        cl.rightMargin = dp(24);
        top.addView(check, cl);
        check.setScaleX(0.4f);
        check.setScaleY(0.4f);
        check.animate().scaleX(1f).scaleY(1f).setStartDelay(180).setDuration(420).setInterpolator(new OvershootInterpolator(2.5f)).start();
        top.addView(text(undo ? "Setup undone" : "You're all set", 40, M3.ON_SURFACE, M3.REGULAR));
        col.addView(top);

        String summary;
        if (undo && undoChoices.m3home) summary = "MaterialTV is uninstalling itself and Amazon's home screen is coming back. Thanks for trying it!";
        else if (undo) summary = "Your Fire TV is back the way it was. You can run setup again any time from MaterialTV settings.";
        else if (choices.tailscale) summary = "Your Fire TV is ready. Open Tailscale once to sign in, and the always-on VPN takes it from there.";
        else summary = "Your Fire TV is ready. You can change or undo any of this later from MaterialTV settings.";
        TextView b = body(summary);
        b.setPadding(0, dp(18), 0, dp(18));
        col.addView(b);

        if (!results.isEmpty()) {
            LinearLayout card = card();
            card.addView(text("What changed", 16, M3.PRIMARY, M3.MEDIUM));
            int n = 0;
            for (String r : results) {
                if (++n > 8) break;
                TextView t = text("•  " + r, 17, M3.ON_SURFACE, M3.REGULAR);
                t.setPadding(0, dp(6), 0, 0);
                card.addView(t);
            }
            col.addView(card, matchWrap(0));
        }

        TextView home = pill(undo ? "Close" : "Go to home", true);
        home.setOnClickListener(x -> finish());
        LinearLayout row;
        if (!undo && choices.tailscale) {
            TextView ts = pill("Open Tailscale to sign in", false);
            ts.setOnClickListener(x -> openTailscale());
            row = buttonRow(home, ts);
        } else {
            row = buttonRow(home);
        }
        col.addView(row, matchWrap(dp(28)));
        focusFirst = home;
        return sv;
    }

    private void openTailscale() {
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(TAILSCALE);
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(TAILSCALE);
        if (i == null) return;
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException ignored) { }
    }

    // ---- building blocks ----------------------------------------------------------------------------

    private interface BoolGet { boolean get(); }
    private interface BoolSet { void set(boolean v); }

    private TextView text(String s, float sp, int color, Typeface face) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(face);
        return t;
    }

    private TextView body(String s) {
        TextView t = text(s, 19, M3.ON_SURFACE_VAR, M3.REGULAR);
        t.setLineSpacing(dp(4), 1f);
        return t;
    }

    private LinearLayout.LayoutParams matchWrap(int topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMargin;
        return lp;
    }

    private LinearLayout.LayoutParams pillParams(int topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56));
        lp.topMargin = topMargin;
        lp.leftMargin = dp(4);
        return lp;
    }

    /** A full-width page: one scrolling column. */
    private ScrollView singleColumn(LinearLayout col) {
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipChildren(false);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        col.setClipToPadding(false);
        col.setPadding(dp(120), dp(8), dp(120), dp(40));
        sv.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return sv;
    }

    /** Text and buttons on the left, content on the right; each side scrolls on its own. */
    private View twoColumns(LinearLayout[] out) {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.HORIZONTAL);
        page.setClipChildren(false);
        page.setClipToPadding(false);
        page.setPadding(dp(120), dp(8), dp(120), dp(40));
        for (int i = 0; i < 2; i++) {
            ScrollView sv = new ScrollView(this);
            sv.setVerticalScrollBarEnabled(false);
            sv.setClipChildren(false);
            sv.setClipToPadding(false);
            sv.setPadding(dp(4), dp(4), dp(4), dp(4));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setClipChildren(false);
            col.setClipToPadding(false);
            sv.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, i == 0 ? 5f : 6f);
            if (i == 1) lp.leftMargin = dp(40);
            page.addView(sv, lp);
            out[i] = col;
        }
        return page;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(M3.rounded(0xCC1D1B20, dp(24)));
        c.setPadding(dp(24), dp(20), dp(24), dp(20));
        return c;
    }

    private LinearLayout buttonRow(TextView... buttons) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(dp(8), dp(6), dp(8), dp(6));
        for (int i = 0; i < buttons.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56));
            if (i > 0) lp.leftMargin = dp(16);
            row.addView(buttons[i], lp);
        }
        return row;
    }

    private void toggle(LinearLayout card, String title, String subtitle, BoolGet get, BoolSet set) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setFocusable(true);
        r.setClickable(true);
        r.setPadding(dp(18), dp(14), dp(18), dp(14));
        final GradientDrawable bg = M3.rounded(0x00000000, dp(20));
        r.setBackground(bg);
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title, 19, M3.ON_SURFACE, M3.REGULAR));
        TextView s = text(subtitle, 15, M3.ON_SURFACE_VAR, M3.REGULAR);
        s.setPadding(0, dp(2), 0, 0);
        labels.addView(s);
        r.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final SwitchView sw = new SwitchView(this);
        sw.setChecked(get.get());
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(dp(52), dp(32));
        sl.leftMargin = dp(16);
        r.addView(sw, sl);
        r.setOnFocusChangeListener((v, has) -> bg.setColor(has ? M3.SECONDARY_CONT : 0x00000000));
        r.setOnClickListener(v -> {
            set.set(!get.get());
            sw.setChecked(get.get());
        });
        LinearLayout.LayoutParams lp = matchWrap(card.getChildCount() == 0 ? 0 : dp(2));
        card.addView(r, lp);
    }

    private View pulsingDot(int color) {
        final View d = new View(this);
        d.setBackground(M3.circle(color));
        final ObjectAnimator a = ObjectAnimator.ofFloat(d, "alpha", 1f, 0.2f);
        a.setDuration(650);
        a.setRepeatMode(ValueAnimator.REVERSE);
        a.setRepeatCount(ValueAnimator.INFINITE);
        d.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { a.start(); }
            @Override public void onViewDetachedFromWindow(View v) { a.cancel(); }
        });
        return d;
    }

    private TextView pill(String label, boolean primary) {
        final TextView b = text(label, 18, primary ? M3.ON_PRIMARY : M3.ON_SECONDARY_CONT, M3.MEDIUM);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(32), 0, dp(32), 0);
        b.setFocusable(true);
        b.setClickable(true);
        final int rest = primary ? M3.PRIMARY : M3.SECONDARY_CONT;
        final GradientDrawable bg = M3.rounded(rest, dp(28));
        b.setBackground(bg);
        b.setOnFocusChangeListener((v, has) -> {
            bg.setStroke(has ? dp(3) : 0, M3.ON_SURFACE);
            v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(150).start();
        });
        return b;
    }

    /** M3 switch, the same look as in MaterialTV settings. */
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
}
