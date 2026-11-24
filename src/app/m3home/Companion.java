package app.m3home;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * The companion site: a tiny web server on the stick (port 8765) that your phone opens by scanning
 * the QR code. It only runs while the Deadlines screen is open, and every request must carry the
 * secret key from the QR link, so other devices on the network can't change anything.
 */
final class Companion {
    static final int PORT = 8765;
    private static final int MAX_BODY = 64 * 1024;

    private final Context context;
    private final Runnable onChange;
    private ServerSocket server;
    private volatile boolean running;

    Companion(Context context, Runnable onChange) {
        this.context = context.getApplicationContext();
        this.onChange = onChange;
    }

    /** The secret in the QR link. Made once; "New link" in the TV screen replaces it. */
    static String key(Context c) {
        String k = Prefs.of(c).getString("companion_key", null);
        if (k == null) k = newKey(c);
        return k;
    }

    static String newKey(Context c) {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xFF));
        Prefs.of(c).edit().putString("companion_key", sb.toString()).apply();
        return sb.toString();
    }

    boolean start() {
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(PORT));
        } catch (IOException e) {
            return false;
        }
        running = true;
        new Thread(() -> {
            while (running) {
                try {
                    final Socket s = server.accept();
                    new Thread(() -> handle(s), "m3home-companion-req").start();
                } catch (IOException e) {
                    if (!running) return;
                }
            }
        }, "m3home-companion").start();
        return true;
    }

    void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) { }
    }

    // ---- HTTP -------------------------------------------------------------------------------

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(10_000);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            String requestLine = line(in);
            if (requestLine == null) return;
            int contentLength = 0;
            String h;
            while ((h = line(in)) != null && !h.isEmpty()) {
                int colon = h.indexOf(':');
                if (colon > 0 && h.substring(0, colon).trim().equalsIgnoreCase("content-length")) {
                    try { contentLength = Integer.parseInt(h.substring(colon + 1).trim()); } catch (NumberFormatException ignored) { }
                }
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0], target = parts[1];
            String path = target, query = "";
            int q = target.indexOf('?');
            if (q >= 0) { path = target.substring(0, q); query = target.substring(q + 1); }
            OutputStream out = sock.getOutputStream();

            if (!key(context).equals(param(query, "k"))) {
                send(out, 403, "text/plain", "This link has expired. Scan the QR code on the TV again.");
                return;
            }
            if (method.equals("GET") && path.equals("/")) {
                send(out, 200, "text/html; charset=utf-8", PAGE);
            } else if (method.equals("GET") && path.equals("/api/data")) {
                send(out, 200, "application/json", Deadlines.json(context));
            } else if (method.equals("POST") && path.equals("/api/save")) {
                if (contentLength <= 0 || contentLength > MAX_BODY) { send(out, 413, "text/plain", "Too large"); return; }
                byte[] body = new byte[contentLength];
                int read = 0;
                while (read < contentLength) {
                    int n = in.read(body, read, contentLength - read);
                    if (n < 0) break;
                    read += n;
                }
                String json = new String(body, 0, read, StandardCharsets.UTF_8);
                String err = Deadlines.save(context, json);
                String placeMsg = "";
                String place = err == null ? new JSONObject(json).optString("place", "").trim() : "";
                if (err == null && !place.isEmpty() && !place.equalsIgnoreCase(Prefs.weatherPlace(context))) {
                    String found = Weather.setPlace(context, place);
                    placeMsg = found == null ? "Couldn't find that town; weather unchanged." : "Weather now for " + found + ".";
                }
                if (err == null && onChange != null) onChange.run();
                send(out, err == null ? 200 : 400, "application/json",
                        new JSONObject().put("ok", err == null).put("message", err == null ? ("Saved. " + placeMsg).trim() : err).toString());
            } else {
                send(out, 404, "text/plain", "Not found");
            }
        } catch (Exception ignored) {
            // A dropped phone connection is fine.
        }
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) return null;
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    private static String param(String query, String name) {
        for (String kv : query.split("&")) {
            int e = kv.indexOf('=');
            if (e > 0 && kv.substring(0, e).equals(name)) return kv.substring(e + 1);
        }
        return null;
    }

    private static void send(OutputStream out, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String status = code == 200 ? "OK" : code == 403 ? "Forbidden" : code == 404 ? "Not Found" : "Error";
        String head = "HTTP/1.1 " + code + " " + status + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + b.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }

    // ---- the phone page (Material 3 dark, no external files) ---------------------------------

    private static final String PAGE = "<!doctype html><html lang=en><head><meta charset=utf-8>"
            + "<meta name=viewport content='width=device-width,initial-scale=1'><title>MaterialTV</title><style>"
            + ":root{--bg:#141218;--s1:#1D1B20;--s2:#2B2930;--p:#D0BCFF;--op:#381E72;--on:#E6E0E9;--v:#CAC4D0;--o:#49454F}"
            + "*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--on);font:16px/1.5 system-ui,Roboto,sans-serif;padding:20px 16px 48px}"
            + "h1{font-weight:400;font-size:28px;margin:4px 0 2px}h2{font-size:15px;color:var(--p);font-weight:500;margin:28px 0 10px}"
            + ".sub{color:var(--v);margin:0 0 8px}.card{background:var(--s1);border-radius:24px;padding:14px}"
            + ".row{display:flex;gap:8px;align-items:center;padding:8px 6px;border-bottom:1px solid var(--o)}.row:last-child{border:0}"
            + ".row b{flex:1;font-weight:400}.row small{color:var(--v)}"
            + "input{background:var(--s2);border:1px solid var(--o);color:var(--on);border-radius:14px;padding:12px;font:inherit;width:100%}"
            + ".f{display:grid;grid-template-columns:1fr;gap:8px;margin-top:10px}"
            + "button{border:0;border-radius:999px;padding:12px 20px;font:500 15px system-ui,sans-serif;background:var(--s2);color:var(--on)}"
            + "button.p{background:var(--p);color:var(--op)}.x{padding:6px 12px;font-size:13px}"
            + "#msg{color:var(--p);min-height:24px;margin-top:12px}</style></head><body>"
            + "<h1>MaterialTV</h1><p class=sub>Deadlines, countdown and weather for your Fire TV</p>"
            + "<h2>Deadlines</h2><div class=card><div id=list></div><div class=f>"
            + "<input id=dt placeholder='What is due' maxlength=60><input id=dd type=date>"
            + "<button onclick=add()>Add deadline</button></div></div>"
            + "<h2>Big countdown</h2><div class=card><div class=f>"
            + "<input id=ct placeholder='Countdown to (e.g. Christmas)' maxlength=60><input id=cd type=date>"
            + "<button onclick=clr()>Remove countdown</button></div></div>"
            + "<h2>Weather town</h2><div class=card><div class=f><input id=pl placeholder='Town or city'></div></div>"
            + "<p><button class=p onclick=save()>Save to TV</button></p><div id=msg></div>"
            + "<script>const k=new URLSearchParams(location.search).get('k');let D={deadlines:[]};"
            + "const $=i=>document.getElementById(i);"
            + "function esc(s){return s.replace(/[&<>\"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',\"'\":'&#39;'}[c]))}"
            + "function draw(){$('list').innerHTML=D.deadlines.length?D.deadlines.map((d,i)=>"
            + "'<div class=row><b>'+esc(d.title)+'</b><small>'+esc(d.date)+'</small><button class=x onclick=del('+i+')>Remove</button></div>').join('')"
            + ":'<p class=sub>No deadlines yet.</p>'}"
            + "function add(){const t=$('dt').value.trim(),d=$('dd').value;if(!t||!d){$('msg').textContent='Add a name and a date first';return}"
            + "D.deadlines.push({title:t,date:d});D.deadlines.sort((a,b)=>a.date<b.date?-1:1);$('dt').value='';draw();$('msg').textContent='Added. Press Save to TV.'}"
            + "function del(i){D.deadlines.splice(i,1);draw()}function clr(){$('ct').value='';$('cd').value=''}"
            + "async function save(){const body={deadlines:D.deadlines,place:$('pl').value};"
            + "if($('ct').value.trim()&&$('cd').value)body.countdown={title:$('ct').value.trim(),date:$('cd').value};"
            + "const r=await fetch('/api/save?k='+k,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});"
            + "const j=await r.json();$('msg').textContent=j.message}"
            + "fetch('/api/data?k='+k).then(r=>r.json()).then(j=>{D.deadlines=j.deadlines||[];"
            + "if(j.countdown){$('ct').value=j.countdown.title;$('cd').value=j.countdown.date}$('pl').value=j.place||'';draw()})"
            + ".catch(()=>{$('msg').textContent='Lost the connection to the TV. Is the QR screen still open?'});"
            + "</script></body></html>";
}
