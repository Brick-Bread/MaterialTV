package app.m3home;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.net.ConnectException;
import java.util.regex.Pattern;

/**
 * Phone mode: the same MaterialTV APK on an Android phone is a setup remote for a Fire TV. Amazon blocks
 * apps on the TV from using its own ADB, but a phone on the same Wi-Fi can, so the phone runs the
 * same Setup steps and writes the same change log onto the TV. Opened from the TV's QR code
 * (m3home://setup?tv=…&c=…[&undo=1]) or from the app icon.
 */
public class PhoneSetupActivity extends Activity {
    private static final Pattern IPV4 = Pattern.compile("^(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$");
    private static final Pattern CODE = Pattern.compile("^[A-Z0-9]{4,8}$");

    private final Setup.Choices choices = new Setup.Choices();
    private final Setup.UndoChoices undoChoices = new Setup.UndoChoices();
    private boolean undo, busy;
    private EditText tv;
    private LinearLayout column, log;
    private ScrollView scroll;
    private TextView go, title, where, tvBadge;
    private LinearLayout scanBox;
    private QrScanView scanner;
    private static final int CAMERA_REQUEST = 7;

    private int dp(float v) { return M3.dp(this, v); }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        M3.init(this);
        String ip = null, code = null;
        Uri u = getIntent() == null ? null : getIntent().getData();
        if (u != null) {
            String t = u.getQueryParameter("tv"), c = u.getQueryParameter("c");
            if (t != null && IPV4.matcher(t).matches()) ip = t;
            if (c != null && CODE.matcher(c).matches()) code = c;
            undo = "1".equals(u.getQueryParameter("undo"));
        }

        scroll = new ScrollView(this);
        scroll.setBackground(new Glow());
        scroll.setFillViewport(true);
        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(20), dp(36), dp(20), dp(40));
        scroll.addView(column);
        setContentView(scroll);

        column.addView(text("MaterialTV", 15, M3.PRIMARY, M3.MEDIUM));
        title = text(undo ? "Undo your Fire TV setup" : "Set up your Fire TV", 30, M3.ON_SURFACE, M3.REGULAR);
        column.addView(title);
        TextView intro = text("Your phone does the setup over Wi-Fi, because Amazon doesn't let apps on the TV do it themselves. Keep the phone on the same Wi-Fi as the TV (or both on Tailscale).",
                16, M3.ON_SURFACE_VAR, M3.REGULAR);
        intro.setPadding(0, dp(8), 0, dp(16));
        column.addView(intro);
        checkForUpdate();

        LinearLayout tvCard = card();
        tvCard.addView(text("Fire TV address", 14, M3.PRIMARY, M3.MEDIUM));
        tv = new EditText(this);
        tv.setText(ip == null ? "" : ip);
        tv.setHint("e.g. 192.168.1.50");
        tv.setInputType(InputType.TYPE_CLASS_PHONE);
        tv.setImeOptions(EditorInfo.IME_ACTION_DONE);
        tv.setTextColor(M3.ON_SURFACE);
        tv.setHintTextColor(M3.OUTLINE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        tv.setTypeface(M3.REGULAR);
        tv.setBackground(M3.outlined(0x00000000, M3.OUTLINE, dp(1), dp(12)));
        tv.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams tl = matchWrap();
        tl.topMargin = dp(8);
        tvCard.addView(tv, tl);
        where = text(code != null
                ? "Check the TV shows the pairing code " + code + "."
                : "Find it on the TV in MaterialTV's setup screen, or Settings › My Fire TV › About › Network.",
                14, M3.ON_SURFACE_VAR, M3.REGULAR);
        where.setPadding(0, dp(8), 0, 0);
        tvCard.addView(where);

        tvBadge = text("", 20, M3.ON_PRIMARY_CONT, M3.MEDIUM);
        tvBadge.setBackground(M3.rounded(M3.PRIMARY_CONT, dp(20)));
        tvBadge.setPadding(dp(16), dp(10), dp(16), dp(10));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.bottomMargin = dp(12);
        column.addView(tvBadge, bl);
        showTv(ip);

        TextView scan = new TextView(this);
        scan.setText("Scan the TV's code");
        scan.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        scan.setTypeface(M3.MEDIUM);
        scan.setTextColor(M3.ON_SECONDARY_CONT);
        scan.setGravity(Gravity.CENTER);
        scan.setBackground(M3.rounded(M3.SECONDARY_CONT, dp(26)));
        scan.setOnClickListener(v -> openScanner());
        LinearLayout.LayoutParams scl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        scl.bottomMargin = dp(12);
        column.addView(scan, scl);

        scanBox = new LinearLayout(this);
        scanBox.setOrientation(LinearLayout.VERTICAL);
        scanBox.setVisibility(View.GONE);
        scanner = new QrScanView(this);
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        frame.setBackground(M3.rounded(0xFF000000, dp(24)));
        frame.setClipToOutline(true);
        frame.addView(scanner);
        scanBox.addView(frame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)));
        TextView hint = text("Point the camera at the QR code on the TV. It connects by itself.", 14, M3.ON_SURFACE_VAR, M3.REGULAR);
        hint.setPadding(0, dp(8), 0, dp(12));
        scanBox.addView(hint);
        column.addView(scanBox, matchWrap());

        LinearLayout opts = card();
        opts.addView(text(undo ? "What to undo" : "What to set up", 14, M3.PRIMARY, M3.MEDIUM));
        if (undo) {
            toggle(opts, "Undo debloat and privacy", "Switch Amazon's apps back on, restore settings", true, x -> undoChoices.debloat = x);
            toggle(opts, "Remove SmartTube", "And switch the Amazon YouTube app back on", true, x -> undoChoices.smartTube = x);
            toggle(opts, "Remove Tailscale", "Stops the always-on VPN and uninstalls it", true, x -> undoChoices.tailscale = x);
            toggle(opts, "Uninstall MaterialTV too", "Gives the Home button back to Amazon", false, x -> undoChoices.m3home = x);
        } else {
            toggle(opts, "MaterialTV permissions", "Home button, hold Home for settings, screensaver, now playing, updates", true, x -> choices.permissions = x);
            toggle(opts, "Debloat and privacy", "dumbtv's cleanup of Amazon ads, tracking and settings", true, x -> choices.debloat = x);
            toggle(opts, "Keep Alexa and the voice remote", "Turn off to switch Alexa off too", true, x -> choices.keepAlexa = x);
            toggle(opts, "SmartTube instead of YouTube", "Downloaded from its GitHub release, checksum-checked", false, x -> choices.smartTube = x);
            toggle(opts, "Tailscale", "Official app, set as the always-on VPN", false, x -> choices.tailscale = x);
        }

        go = new TextView(this);
        go.setText(undo ? "Undo now" : "Set up");
        go.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        go.setTypeface(M3.MEDIUM);
        go.setTextColor(M3.ON_PRIMARY);
        go.setGravity(Gravity.CENTER);
        go.setBackground(M3.rounded(M3.PRIMARY, dp(28)));
        go.setOnClickListener(v -> start());
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        gl.topMargin = dp(18);
        column.addView(go, gl);

        log = new LinearLayout(this);
        log.setOrientation(LinearLayout.VERTICAL);
        log.setPadding(0, dp(16), 0, 0);
        column.addView(log);

        if (ip == null) openScanner(); // no TV yet: go straight to scanning
    }

    private void showTv(String ip) {
        tvBadge.setText(ip == null ? "No TV yet. Scan the code on the TV" : "TV  \u00B7  " + ip);
        tvBadge.setBackground(M3.rounded(ip == null ? M3.SURFACE_HIGH : M3.PRIMARY_CONT, dp(20)));
        tvBadge.setTextColor(ip == null ? M3.ON_SURFACE_VAR : M3.ON_PRIMARY_CONT);
    }

    // ---- QR scanning ------------------------------------------------------------------------

    private void openScanner() {
        if (busy) return;
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { android.Manifest.permission.CAMERA }, CAMERA_REQUEST);
            return;
        }
        scanBox.setVisibility(View.VISIBLE);
        scanner.stop();
        scanner.start(this::scanned);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != CAMERA_REQUEST) return;
        if (results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) openScanner();
        else say("Without the camera, type the TV's address instead (it's shown on the TV).", M3.ON_SURFACE_VAR);
    }

    /** Accepts the TV's code (setup.html?tv=…&c=… or m3home://setup?…), fills in the address and waits for "Set up". */
    private void scanned(String text) {
        Uri u;
        try { u = Uri.parse(text.trim()); } catch (RuntimeException e) { u = null; }
        String t = u == null ? null : u.getQueryParameter("tv");
        String c = u == null ? null : u.getQueryParameter("c");
        boolean looksOurs = u != null && ("m3home".equals(u.getScheme())
                || (u.getPath() != null && u.getPath().endsWith("setup.html")));
        if (!looksOurs || t == null || !IPV4.matcher(t).matches()) {
            say("That isn't an MaterialTV setup code. Scan the code on the TV's setup screen.", M3.ERROR);
            scanner.stop();
            scanner.start(this::scanned);
            return;
        }
        scanner.stop();
        scanBox.setVisibility(View.GONE);
        boolean wantUndo = "1".equals(u.getQueryParameter("undo"));
        if (wantUndo != undo) {
            // The TV is on the other flow (setup vs undo): reopen in that mode.
            startActivity(new Intent(this, PhoneSetupActivity.class).setData(Uri.parse("m3home://setup?tv=" + t
                    + (c != null && CODE.matcher(c).matches() ? "&c=" + c : "") + (wantUndo ? "&undo=1" : ""))));
            finish();
            return;
        }
        tv.setText(t);
        showTv(t);
        if (c != null && CODE.matcher(c).matches()) where.setText("The TV shows the pairing code " + c + ".");
        say("Found the TV at " + t + ". Choose what to " + (undo ? "undo" : "set up") + " below, then press "
                + (undo ? "Undo now" : "Set up") + ".", M3.PRIMARY);
        // Don't start by itself: the options must be the user's choice. Bring them into view instead.
        scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, go.getTop() - scroll.getHeight() / 2)));
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (scanner != null) scanner.stop();
        if (scanBox != null) scanBox.setVisibility(View.GONE);
    }

    private void start() {
        if (busy) return;
        final String host = tv.getText().toString().trim();
        if (!IPV4.matcher(host).matches()) {
            say("Enter the TV's address, like 192.168.1.50.", M3.ERROR);
            return;
        }
        busy = true;
        showTv(host);
        go.setText(undo ? "Undoing…" : "Setting up…");
        log.removeAllViews();
        new Thread(() -> {
            Adb adb = null;
            try {
                say("Connecting to the TV at " + host + "…", M3.ON_SURFACE);
                adb = Adb.connectTo(this, host, () -> say("On the TV: tick “Always allow” and press OK on “Allow USB debugging?”", M3.PRIMARY));
                say("Connected to the TV at " + host + ".", M3.PRIMARY);
                if (undo) Setup.undo(this, adb, undoChoices, s -> say(s, M3.ON_SURFACE));
                else Setup.run(this, adb, choices, s -> say(s, M3.ON_SURFACE));
                say(undo ? "All done. The TV is back the way it was." : "All done. Press Home on the TV remote.", M3.PRIMARY);
                runOnUiThread(() -> { busy = false; go.setText("Done"); go.setOnClickListener(v -> finish()); });
            } catch (ConnectException e) {
                fail("Can't reach the TV. Check the address, that ADB debugging is on (Settings › My Fire TV › Developer options), and that the phone is on the same Wi-Fi.");
            } catch (IOException e) {
                fail("Stopped: " + e.getMessage());
            } catch (RuntimeException e) {
                fail("Stopped: " + e); // never crash; say what went wrong
            } finally {
                if (adb != null) adb.close();
            }
        }, "m3home-phone-setup").start();
    }

    private void fail(String msg) {
        say(msg, M3.ERROR);
        runOnUiThread(() -> { busy = false; go.setText("Try again"); });
    }

    private void say(final String s, final int color) {
        runOnUiThread(() -> {
            TextView t = text(s, 15, s.startsWith("  ") ? M3.ON_SURFACE_VAR : color, M3.REGULAR);
            t.setPadding(0, dp(4), 0, 0);
            log.addView(t);
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    // ---- building blocks --------------------------------------------------------------------

    private interface BoolSet { void set(boolean v); }

    // ---- updates from GitHub (same releases as the TV) ----------------------------------

    private LinearLayout updateCard;

    /** Asks GitHub for a newer MaterialTV each time the phone screen opens; shows a card if there is one. */
    private void checkForUpdate() {
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            Updater.check(app);
            final String v = Updater.available(app);
            if (v != null) runOnUiThread(() -> showUpdate(v));
        }, "m3home-phone-update").start();
    }

    private void showUpdate(String version) {
        if (isFinishing() || updateCard != null) return;
        updateCard = new LinearLayout(this);
        updateCard.setOrientation(LinearLayout.VERTICAL);
        updateCard.setPadding(dp(18), dp(16), dp(18), dp(16));
        updateCard.setBackground(M3.rounded(M3.PRIMARY_CONT, dp(20)));
        updateCard.addView(text("Update available: " + version, 17, M3.ON_PRIMARY_CONT, M3.MEDIUM));
        final TextView status = text("You have " + Updater.installedVersion(this) + ". Update so the phone setup matches the latest TV app.",
                14, M3.ON_PRIMARY_CONT, M3.REGULAR);
        status.setPadding(0, dp(4), 0, dp(12));
        updateCard.addView(status);
        final TextView btn = text("Update now", 16, M3.ON_PRIMARY, M3.MEDIUM);
        btn.setGravity(Gravity.CENTER);
        btn.setBackground(M3.rounded(M3.PRIMARY, dp(24)));
        btn.setOnClickListener(v -> {
            if (android.os.Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                status.setText("Allow MaterialTV to install apps (the switch on the next screen), then come back and press Update now.");
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + getPackageName())));
                } catch (RuntimeException ignored) { }
                return;
            }
            btn.setEnabled(false);
            status.setText("Downloading " + version + "…");
            final android.content.Context app = getApplicationContext();
            new Thread(() -> Updater.install(app, msg -> runOnUiThread(() -> {
                status.setText(msg);
                btn.setEnabled(true);
            })), "m3home-phone-install").start();
        });
        updateCard.addView(btn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        LinearLayout.LayoutParams lp = matchWrap();
        lp.bottomMargin = dp(16);
        column.addView(updateCard, Math.min(3, column.getChildCount()), lp);
    }

    private TextView text(String s, float sp, int color, Typeface face) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(face);
        return t;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(M3.rounded(0xCC1D1B20, dp(24)));
        c.setPadding(dp(18), dp(16), dp(18), dp(16));
        LinearLayout.LayoutParams lp = matchWrap();
        lp.bottomMargin = dp(12);
        column.addView(c, lp);
        return c;
    }

    private void toggle(LinearLayout card, String titleText, String sub, boolean initial, BoolSet set) {
        final boolean[] on = { initial };
        set.set(initial);
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(12), 0, dp(12));
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.addView(text(titleText, 17, M3.ON_SURFACE, M3.REGULAR));
        l.addView(text(sub, 13, M3.ON_SURFACE_VAR, M3.REGULAR));
        r.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final View track = new View(this);
        final GradientDrawable bg = M3.rounded(initial ? M3.PRIMARY : M3.SURFACE_HIGHEST, dp(16));
        if (!initial) bg.setStroke(dp(2), M3.OUTLINE);
        track.setBackground(bg);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(dp(52), dp(32));
        sl.leftMargin = dp(12);
        r.addView(track, sl);
        r.setOnClickListener(v -> {
            if (busy) return;
            on[0] = !on[0];
            set.set(on[0]);
            bg.setColor(on[0] ? M3.PRIMARY : M3.SURFACE_HIGHEST);
            bg.setStroke(on[0] ? 0 : dp(2), M3.OUTLINE);
        });
        card.addView(r, matchWrap());
    }
}
