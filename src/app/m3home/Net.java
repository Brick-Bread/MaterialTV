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

    /**
     * A plain-English reason a connection to a website failed, so "Couldn't reach GitHub" can say why.
     * code is the HTTP status if there was one (0 if not), e the exception if there was one.
     */
    static String explain(int code, Throwable e) {
        if (code == 403 || code == 429) return "GitHub is limiting requests from your internet connection. Try again in an hour.";
        if (code >= 500) return "GitHub is having problems (error " + code + "). Try again later.";
        if (code == 404) return "That file isn't on GitHub (error 404).";
        if (code > 0 && code != 200) return "GitHub answered with error " + code + ".";
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.UnknownHostException) return "The TV can't find GitHub. Check the TV is online (or that your network isn't blocking github.com).";
            if (t instanceof java.net.SocketTimeoutException) return "GitHub took too long to answer. The connection may be slow; try again.";
            if (t instanceof java.net.ConnectException) return "The connection to GitHub was refused. A firewall, VPN or network filter may be blocking it.";
            if (t instanceof javax.net.ssl.SSLException || t instanceof java.security.cert.CertificateException) {
                int year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
                if (year < 2025) return "The TV's date is wrong (" + year + "), so secure sites fail. Fix the date in Fire TV Settings.";
                return "This network is intercepting secure connections (common on school or work Wi-Fi), so GitHub can't be reached safely.";
            }
        }
        return e == null ? "Couldn't reach GitHub." : "Couldn't reach GitHub (" + e.getClass().getSimpleName() + ").";
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
