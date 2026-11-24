package app.m3home;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Shows a QR code for the companion site; the site runs while this is open. Two flavours: deadlines
 * (the right panel lists what's on the TV now) and "Customise from your phone" (EXTRA_CUSTOMISE; the
 * right panel sums up the look). Changes from the phone show up here at once.
 */
public class DeadlinesActivity extends Activity {
    /** Shows the QR for the full "Customise from your phone" page instead of just deadlines. (1.9.0 contract) */
    static final String EXTRA_CUSTOMISE = "customise";
    private Companion companion;
    private LinearLayout list;
    private TextView urls;
    private boolean customise;
    private boolean serverFailed;
    private int builtRev;

    private int dp(float v) { return M3.dp(this, v); }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(M3.scaled(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        customise = getIntent().getBooleanExtra(EXTRA_CUSTOMISE, false);
        build();
        companion = new Companion(this, () -> runOnUiThread(this::changed));
        if (!companion.start()) {
            serverFailed = true;
            showServerProblem();
        }
    }

    /** The phone saved something: recolour if the look moved, else just refresh the panel. */
    private void changed() {
        if (isFinishing() || isDestroyed()) return;
        if (Prefs.lookRev(this) != builtRev) build(); // keeps the server running (recreate() would restart it)
        else render();
    }

    private void build() {
        M3.init(this);
        builtRev = Prefs.lookRev(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new Glow());
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(64), dp(56), dp(64), dp(48));
        root.addView(row);
        setContentView(root);

        // Left: QR code and instructions
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(text(customise ? "Customise from your phone" : "Deadlines & countdown", 34, M3.ON_SURFACE, M3.REGULAR));
        TextView how = text((customise
                ? "Scan with your phone's camera to change the Home screen, colours, background and screensaver. "
                : "Scan with your phone's camera to edit them. ")
                + "Your phone needs to be on the same Wi-Fi as the TV, or on Tailscale.", 18, M3.ON_SURFACE_VAR, M3.REGULAR);
        how.setPadding(0, dp(8), 0, dp(24));
        left.addView(how);

        String lan = Qr.lanIp(this), ts = Net.tailscaleIp(this);
        String key = Companion.key(this);
        String host = lan != null ? lan : (ts != null && !ts.isEmpty() ? ts : null);
        FrameLayout qrCard = new FrameLayout(this);
        qrCard.setBackground(M3.rounded(Color.WHITE, dp(28)));
        qrCard.setPadding(dp(18), dp(18), dp(18), dp(18));
        ImageView qr = new ImageView(this);
        if (host != null) qr.setImageBitmap(Qr.make(url(host, key), dp(200)));
        qrCard.addView(qr, new FrameLayout.LayoutParams(dp(200), dp(200)));
        left.addView(qrCard, new LinearLayout.LayoutParams(dp(236), dp(236)));

        StringBuilder links = new StringBuilder();
        if (lan != null) links.append("Wi-Fi:  http://").append(Net.show(this, lan)).append(':').append(Companion.PORT);
        if (ts != null && !ts.isEmpty()) {
            if (links.length() > 0) links.append('\n');
            links.append("Tailscale:  http://").append(Net.show(this, ts)).append(':').append(Companion.PORT);
        }
        if (links.length() == 0) links.append("No network connection");
        urls = text(links.toString(), 16, M3.ON_SURFACE_VAR, M3.REGULAR);
        urls.setPadding(0, dp(20), 0, 0);
        left.addView(urls);
        TextView back = text("Press Back when you're done.", 15, M3.PRIMARY, M3.MEDIUM);
        back.setPadding(0, dp(14), 0, 0);
        left.addView(back);
        row.addView(left, new LinearLayout.LayoutParams(dp(520), ViewGroup.LayoutParams.MATCH_PARENT));

        // Right: what's set now (updates live when the phone saves)
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackground(M3.rounded(M3.alpha(M3.SURFACE_LOW, 0xCC), dp(28)));
        list.setPadding(dp(32), dp(28), dp(32), dp(28));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(48);
        row.addView(list, lp);
        render();
        if (serverFailed) showServerProblem();
    }

    private void showServerProblem() {
        urls.setText("Couldn't start the companion site (port " + Companion.PORT + " is busy).");
    }

    @Override
    protected void onDestroy() {
        if (companion != null) companion.stop();
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isFinishing() && companion != null) companion.stop();
    }

    private void render() {
        list.removeAllViews();
        if (customise) renderLook();
        else renderDeadlines();
    }

    private void renderDeadlines() {
        list.addView(text("On the TV now", 15, M3.PRIMARY, M3.MEDIUM));
        List<Deadlines.Item> items = Deadlines.upcoming(this);
        if (items.isEmpty()) list.addView(pad(text("No upcoming deadlines", 20, M3.ON_SURFACE_VAR, M3.REGULAR)));
        for (int i = 0; i < Math.min(items.size(), 8); i++) {
            Deadlines.Item it = items.get(i);
            list.addView(pad(text(it.title + "  ·  " + it.when(), 22, M3.ON_SURFACE, M3.REGULAR)));
        }
        Deadlines.Item cd = Deadlines.countdown(this);
        TextView c = text(cd == null ? "No countdown set" : cd.title + "  ·  " + Math.max(0, cd.daysLeft()) + " days",
                22, cd == null ? M3.ON_SURFACE_VAR : M3.ON_SURFACE, M3.REGULAR);
        TextView h = text("Countdown", 15, M3.PRIMARY, M3.MEDIUM);
        h.setPadding(0, dp(24), 0, 0);
        list.addView(h);
        list.addView(pad(c));
        String place = Prefs.weatherPlace(this);
        TextView w = text(place == null ? "Weather: set your town on the phone page" : "Weather: " + place,
                16, M3.ON_SURFACE_VAR, M3.REGULAR);
        w.setPadding(0, dp(24), 0, 0);
        list.addView(w);
    }

    /** A short summary of the look, so you can see the phone's changes land. */
    private void renderLook() {
        list.addView(text("On the TV now", 15, M3.PRIMARY, M3.MEDIUM));
        String accent = Prefs.accent(this);
        String accentName = accent;
        for (int i = 0; i < Palette.ACCENTS.length && i < Palette.ACCENT_NAMES.length; i++) {
            if (Palette.ACCENTS[i].equals(accent)) accentName = Palette.ACCENT_NAMES[i];
        }
        line("Colours", accentName + " · " + (Prefs.light(this) ? "Light" : "Dark") + " · " + cap(Prefs.of(this).getString(Prefs.TEXT_SIZE, "normal")) + " text");
        String kind = Prefs.bgKind(this);
        line("Background", kind.equals("solid") ? "Solid colour" : kind.equals("wallpaper") ? "Wallpaper"
                : kind.equals("custom") ? "Your picture" : "Gradient");
        StringBuilder rows = new StringBuilder();
        for (String r : Prefs.homeRows(this).split(",")) {
            String name = r.equals("favourites") ? "Favourites" : r.equals("recents") ? "Recently used" : r.equals("apps") ? "All apps" : null;
            if (name == null) continue;
            if (rows.length() > 0) rows.append(" · ");
            rows.append(name);
        }
        line("Home rows", rows.length() == 0 ? "All apps" : rows.toString());
        line("Tiles", cap(Prefs.tileSize(this)));
        TextView note = text("Changes from your phone show up straight away.", 16, M3.ON_SURFACE_VAR, M3.REGULAR);
        note.setPadding(0, dp(24), 0, 0);
        list.addView(note);
    }

    private void line(String label, String value) {
        section(label);
        list.addView(pad(text(value, 22, M3.ON_SURFACE, M3.REGULAR)));
    }

    private void section(String label) {
        TextView h = text(label, 15, M3.PRIMARY, M3.MEDIUM);
        h.setPadding(0, list.getChildCount() <= 1 ? dp(8) : dp(20), 0, 0);
        list.addView(h);
    }

    private static String cap(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private TextView pad(TextView t) {
        t.setPadding(0, dp(10), 0, 0);
        return t;
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

    private String url(String host, String key) {
        return "http://" + host + ":" + Companion.PORT + "/?k=" + key + (customise ? "#customise" : "#deadlines");
    }
}
