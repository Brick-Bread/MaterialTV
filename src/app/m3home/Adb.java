package app.m3home;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

/**
 * A minimal ADB client that talks to the TV's own adbd (127.0.0.1:5555), the same way a computer
 * running `adb` does. The first time, the TV shows "Allow USB debugging?" for MaterialTV's key; tick
 * "Always allow". This is what lets the setup do things an ordinary app can't (switch apps off,
 * grant permissions, change protected settings). Needs ADB debugging on in Developer options.
 */
final class Adb implements Closeable {
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f,
            OKAY = 0x59414b4f, CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int VERSION = 0x01000000, MAX_DATA = 256 * 1024;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    // DER prefix of a SHA-1 DigestInfo: adbd checks the token as if it were a SHA-1 hash.
    private static final byte[] SHA1_PREFIX = { 0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03,
            0x02, 0x1a, 0x05, 0x00, 0x04, 0x14 };

    interface Waiting { void approveOnTv(); }

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private int nextId = 1;

    private Adb(Socket s) throws IOException {
        socket = s;
        in = new DataInputStream(s.getInputStream());
        out = s.getOutputStream();
    }

    /** Connects and authenticates. Calls waiting.approveOnTv() if the TV needs you to press Allow. */
    static Adb connect(Context c, Waiting waiting) throws IOException {
        // Some Fire OS builds drop ADB connections an app makes to 127.0.0.1, but accept the same
        // app connecting to the TV's own network address, so try each in turn.
        IOException last = null;
        boolean anyListening = false;
        for (String host : hosts(c)) {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(host, 5555), 4000);
            } catch (IOException e) {
                try { s.close(); } catch (IOException ignored) { }
                last = e;
                continue;
            }
            anyListening = true;
            s.setSoTimeout(15_000);
            Adb adb = new Adb(s);
            try {
                adb.handshake(c, waiting);
                return adb;
            } catch (IOException e) {
                adb.close();
                last = new IOException(e.getMessage() + " (" + host + ", after: " + adb.stage + ")", e);
                if (adb.authStarted) throw last; // it reached the Allow prompt: don't retry elsewhere
            }
        }
        if (!anyListening) throw new java.net.ConnectException("ADB isn't listening");
        throw last != null ? last : new IOException("couldn't connect to ADB");
    }

    private boolean authStarted;

    /** Connects to another device's ADB (phone mode: the TV at host:5555). */
    static Adb connectTo(Context c, String host, Waiting waiting) throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, 5555), 6000);
        } catch (IOException e) {
            try { s.close(); } catch (IOException ignored) { }
            throw new java.net.ConnectException("can't reach " + host + ":5555");
        }
        s.setSoTimeout(15_000);
        Adb adb = new Adb(s);
        try {
            adb.handshake(c, waiting);
            return adb;
        } catch (IOException e) {
            adb.close();
            throw new IOException(e.getMessage() + " (after: " + adb.stage + ")", e);
        }
    }

    /** True when an error looks like Fire OS refusing an app's own-device ADB connection. */
    static boolean looksBlocked(IOException e) {
        String m = String.valueOf(e.getMessage());
        return m.contains("after: connecting") || m.contains("after: sent CNXN");
    }

    /** 127.0.0.1 first, then the TV's own IPv4 addresses on its networks. */
    private static java.util.List<String> hosts(Context c) {
        java.util.List<String> out = new java.util.ArrayList<>();
        out.add("127.0.0.1");
        try {
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                for (android.net.Network n : cm.getAllNetworks()) {
                    android.net.LinkProperties lp = cm.getLinkProperties(n);
                    if (lp == null) continue;
                    for (android.net.LinkAddress la : lp.getLinkAddresses()) {
                        java.net.InetAddress a = la.getAddress();
                        if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()) {
                            String ip = a.getHostAddress();
                            if (!out.contains(ip)) out.add(ip);
                        }
                    }
                }
            }
        } catch (RuntimeException ignored) { }
        return out;
    }

    private void handshake(Context c, Waiting waiting) throws IOException {
        KeyPair keys = keys(c);
        send(CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.UTF_8));
        log("sent CNXN");
        boolean signed = false, offeredKey = false;
        while (true) {
            Msg m = read();
            log("got " + Integer.toHexString(m.cmd) + " arg0=" + m.arg0 + " len=" + m.data.length);
            if (m.cmd == CNXN) return;
            if (m.cmd != AUTH || m.arg0 != AUTH_TOKEN) throw new IOException("unexpected reply from adbd");
            if (!signed) {
                byte[] sig = sign(keys.getPrivate(), m.data);
                log("signature " + sig.length + " bytes");
                send(AUTH, AUTH_SIGNATURE, 0, sig);
                signed = true;
            } else if (!offeredKey) {
                byte[] pub = (adbPublicKey((RSAPublicKey) keys.getPublic()) + "\0").getBytes(StandardCharsets.UTF_8);
                send(AUTH, AUTH_RSAPUBLICKEY, 0, pub);
                offeredKey = true;
                authStarted = true;
                if (waiting != null) waiting.approveOnTv();
                socket.setSoTimeout(120_000); // time to press Allow on the TV
            } else {
                throw new IOException("the TV didn't accept MaterialTV's key");
            }
        }
    }

    /** Runs a shell command and returns everything it printed. */
    synchronized String shell(String command) throws IOException {
        int local = nextId++;
        socket.setSoTimeout(120_000);
        send(OPEN, local, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
        int remote = 0;
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        while (true) {
            Msg m = read();
            if (m.arg1 != local) continue; // not ours
            if (m.cmd == OKAY) {
                remote = m.arg0;
            } else if (m.cmd == WRTE) {
                result.write(m.data);
                send(OKAY, local, m.arg0, new byte[0]);
            } else if (m.cmd == CLSE) {
                send(CLSE, local, m.arg0 != 0 ? m.arg0 : remote, new byte[0]);
                break;
            }
        }
        return result.toString("UTF-8").trim();
    }

    /** The last step reached, so a failure can say where it stopped. */
    private String stage = "connecting";

    private void log(String s) {
        stage = s;
    }

    @Override
    public void close() {
        try { socket.close(); } catch (IOException ignored) { }
    }

    // ---- wire format ----------------------------------------------------------------------

    private static final class Msg {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        int sum = 0;
        for (byte b : data) sum += b & 0xFF;
        h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~cmd);
        out.write(h.array());
        out.write(data);
        out.flush();
    }

    private Msg read() throws IOException {
        byte[] head = new byte[24];
        in.readFully(head);
        ByteBuffer h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = h.getInt();
        m.arg0 = h.getInt();
        m.arg1 = h.getInt();
        int len = h.getInt();
        if (len < 0 || len > MAX_DATA * 4) throw new IOException("bad message from adbd");
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    // ---- keys -------------------------------------------------------------------------------

    /** MaterialTV's own ADB key pair, made once and kept in the app's private storage. */
    private static KeyPair keys(Context c) throws IOException {
        File priv = new File(c.getFilesDir(), "adbkey"), pub = new File(c.getFilesDir(), "adbkey.pub");
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            if (priv.exists() && pub.exists()) {
                return new KeyPair(kf.generatePublic(new X509EncodedKeySpec(readAll(pub))),
                        kf.generatePrivate(new PKCS8EncodedKeySpec(readAll(priv))));
            }
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair kp = g.generateKeyPair();
            writeAll(priv, kp.getPrivate().getEncoded());
            writeAll(pub, kp.getPublic().getEncoded());
            return kp;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("couldn't make MaterialTV's ADB key", e);
        }
    }

    private static byte[] sign(PrivateKey key, byte[] token) throws IOException {
        try {
            Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            c.init(Cipher.ENCRYPT_MODE, key);
            c.update(SHA1_PREFIX);
            return c.doFinal(token);
        } catch (Exception e) {
            throw new IOException("couldn't sign the ADB token", e);
        }
    }

    /** The public key in adbd's own format (mincrypt RSAPublicKey, base64) plus a name. */
    private static String adbPublicKey(RSAPublicKey key) {
        BigInteger r32 = BigInteger.ZERO.setBit(32);
        BigInteger n = key.getModulus();
        BigInteger rr = BigInteger.ZERO.setBit(2048).modPow(BigInteger.valueOf(2), n);
        BigInteger n0inv = n.remainder(r32).modInverse(r32);
        int words = 2048 / 32;
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words);
        b.putInt(n0inv.negate().intValue());
        BigInteger nn = n;
        for (int i = 0; i < words; i++) {
            BigInteger[] qr = nn.divideAndRemainder(r32);
            nn = qr[0];
            b.putInt(qr[1].intValue());
        }
        for (int i = 0; i < words; i++) {
            BigInteger[] qr = rr.divideAndRemainder(r32);
            rr = qr[0];
            b.putInt(qr[1].intValue());
        }
        b.putInt(key.getPublicExponent().intValue());
        return Base64.encodeToString(b.array(), Base64.NO_WRAP) + " M3Home@FireTV";
    }

    private static byte[] readAll(File f) throws IOException {
        try (InputStream is = new FileInputStream(f)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) o.write(buf, 0, n);
            return o.toByteArray();
        }
    }

    private static void writeAll(File f, byte[] data) throws IOException {
        try (OutputStream o = new FileOutputStream(f)) { o.write(data); }
    }
}
