package app.m3home;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.net.Inet4Address;
import java.net.InetAddress;

/** Is Tailscale up? Looks for a VPN network holding a 100.64.0.0/10 (tailnet) address. */
final class Net {
    private Net() {}

    /**
     * How an address is shown on screen. With "Hide IP addresses" on (Settings), only the first two
     * parts are shown, e.g. 192.168.•.• — handy for screenshots or sharing your screen.
     */
    static String show(Context c, String ip) {
        if (ip == null || ip.isEmpty() || !Prefs.of(c).getBoolean(Prefs.HIDE_IPS, false)) return ip;
        String[] p = ip.split("\\.");
        return p.length == 4 ? p[0] + "." + p[1] + ".••.••" : "•••";
    }

    /** The tailnet IPv4 address, "" if a VPN is up without one, or null if no VPN is up. */
    static String tailscaleIp(Context c) {
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        String result = null;
        try {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc == null || !nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                result = "";
                LinkProperties lp = cm.getLinkProperties(n);
                if (lp == null) continue;
                for (LinkAddress la : lp.getLinkAddresses()) {
                    InetAddress a = la.getAddress();
                    if (!(a instanceof Inet4Address)) continue;
                    byte[] b = a.getAddress();
                    if ((b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64) return a.getHostAddress();
                }
            }
        } catch (RuntimeException ignored) {
            // Connectivity service hiccup: report what we have.
        }
        return result;
    }
}
