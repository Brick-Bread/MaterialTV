package app.m3home;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Shows a QR code for the companion site and the current deadlines; the site runs while this is open. */
public class DeadlinesActivity extends Activity {
    private Companion companion;
    private LinearLayout list;

    private int dp(float v) { return M3.dp(this, v); }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        M3.init(this);
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
        left.addView(text("Deadlines & countdown", 34, M3.ON_SURFACE, M3.REGULAR));
        TextView how = text("Scan with your phone's camera to edit them. Your phone needs to be on the same "
                + "Wi-Fi as the TV, or on Tailscale.", 18, M3.ON_SURFACE_VAR, M3.REGULAR);
        how.setPadding(0, dp(8), 0, dp(24));
        left.addView(how);

        String lan = lanIp(), ts = Net.tailscaleIp(this);
        String key = Companion.key(this);
        String host = lan != null ? lan : ts;
        FrameLayout qrCard = new FrameLayout(this);
        qrCard.setBackground(M3.rounded(Color.WHITE, dp(28)));
        qrCard.setPadding(dp(18), dp(18), dp(18), dp(18));
        ImageView qr = new ImageView(this);
        if (host != null) qr.setImageBitmap(qr(url(host, key), dp(200)));
        qrCard.addView(qr, new FrameLayout.LayoutParams(dp(200), dp(200)));
        left.addView(qrCard, new LinearLayout.LayoutParams(dp(236), dp(236)));

        StringBuilder links = new StringBuilder();
        if (lan != null) links.append("Wi-Fi:  http://").append(Net.show(this, lan)).append(':').append(Companion.PORT);
        if (ts != null && !ts.isEmpty()) {
            if (links.length() > 0) links.append('\n');
            links.append("Tailscale:  http://").append(Net.show(this, ts)).append(':').append(Companion.PORT);
        }
        if (links.length() == 0) links.append("No network connection");
        TextView urls = text(links.toString(), 16, M3.ON_SURFACE_VAR, M3.REGULAR);
        urls.setPadding(0, dp(20), 0, 0);
        left.addView(urls);
        TextView back = text("Press Back when you're done.", 15, M3.PRIMARY, M3.MEDIUM);
        back.setPadding(0, dp(14), 0, 0);
        left.addView(back);
        row.addView(left, new LinearLayout.LayoutParams(dp(520), ViewGroup.LayoutParams.MATCH_PARENT));

        // Right: what's saved now (updates live when the phone saves)
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackground(M3.rounded(0xCC1D1B20, dp(28)));
        list.setPadding(dp(32), dp(28), dp(32), dp(28));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(48);
        row.addView(list, lp);
        render();

        companion = new Companion(this, () -> runOnUiThread(this::render));
        if (!companion.start()) {
            urls.setText("Couldn't start the companion site (port " + Companion.PORT + " is busy).");
        }
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

    private static String url(String host, String key) {
        return "http://" + host + ":" + Companion.PORT + "/?k=" + key;
    }

    /** The TV's address on the home network (not the Tailscale one). */
    private String lanIp() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            if (nc == null || nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp == null) continue;
            for (LinkAddress la : lp.getLinkAddresses()) {
                InetAddress a = la.getAddress();
                if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
            }
        }
        return null;
    }

    private static Bitmap qr(String text, int size) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 0);
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
            int w = m.getWidth(), h = m.getHeight();
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) px[y * w + x] = m.get(x, y) ? 0xFF1D1B20 : 0xFFFFFFFF;
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        } catch (Exception e) {
            return null;
        }
    }
}
