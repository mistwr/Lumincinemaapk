package pt.luminai.filmes;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Comando nativo para Google TV / Android TV (o mesmo protocolo da app "Google TV").
 * Emparelhamento na porta 6467 (código de 6 caracteres no ecrã da TV), comandos na 6466.
 * Permite setas, OK, voltar, início, canais, volume, power e abrir links/apps na TV.
 */
final class GoogleTv {
    private static NsdManager.DiscoveryListener disc;
    private static final Map<String, Remote> remotes = new HashMap<>();
    private static final Map<String, Pairing> pairings = new HashMap<>();

    // ---------------- descoberta (mDNS) ----------------
    static void discover(Context ctx) {
        final NsdManager nsd = (NsdManager) ctx.getApplicationContext().getSystemService(Context.NSD_SERVICE);
        final String self = RemoteHub.ip(ctx);
        try { if (disc != null) nsd.stopServiceDiscovery(disc); } catch (Exception ignored) {}
        disc = new NsdManager.DiscoveryListener() {
            public void onStartDiscoveryFailed(String t, int e) { Diag.log("GoogleTV: descoberta falhou (" + e + ")"); }
            public void onStopDiscoveryFailed(String t, int e) {}
            public void onDiscoveryStarted(String t) { Diag.log("GoogleTV: a procurar…"); }
            public void onDiscoveryStopped(String t) {}
            public void onServiceLost(NsdServiceInfo si) {}
            public void onServiceFound(NsdServiceInfo si) {
                try {
                    nsd.resolveService(si, new NsdManager.ResolveListener() {
                        public void onResolveFailed(NsdServiceInfo i, int e) { Diag.log("GoogleTV: não resolveu " + i.getServiceName() + " (" + e + ")"); }
                        public void onServiceResolved(NsdServiceInfo i) {
                            String host = i.getHost().getHostAddress();
                            if (host.equals(self)) return;
                            Diag.log("GoogleTV: encontrada " + i.getServiceName() + " em " + host);
                            SmartTv.add("gtv", host, i.getServiceName());
                        }
                    });
                } catch (Exception ignored) {}
            }
        };
        try { nsd.discoverServices("_androidtvremote2._tcp.", NsdManager.PROTOCOL_DNS_SD, disc); } catch (Exception e) { Diag.log("GoogleTV: " + e); }
    }

    // ---------------- certificado do cliente (guardado) ----------------
    private static KeyStore keyStore(Context ctx) throws Exception {
        SharedPreferences p = ctx.getSharedPreferences("lumin-gtv", 0);
        String k = p.getString("key", null), c = p.getString("cert", null);
        PrivateKey key; X509Certificate cert;
        if (k == null || c == null) {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048, new SecureRandom());
            KeyPair kp = g.generateKeyPair();
            byte[] der = selfSigned(kp);
            key = kp.getPrivate();
            cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(der));
            p.edit().putString("key", Base64.encodeToString(key.getEncoded(), Base64.NO_WRAP))
                    .putString("cert", Base64.encodeToString(der, Base64.NO_WRAP)).apply();
        } else {
            key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(k, Base64.NO_WRAP)));
            cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(Base64.decode(c, Base64.NO_WRAP)));
        }
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setKeyEntry("lumin", key, new char[0], new java.security.cert.Certificate[]{cert});
        return ks;
    }

    private static X509Certificate clientCert(Context ctx) throws Exception {
        return (X509Certificate) keyStore(ctx).getCertificate("lumin");
    }

    private static SSLSocket connect(Context ctx, String host, int port) throws Exception {
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore(ctx), new char[0]);
        TrustManager[] tm = {new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }};
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(kmf.getKeyManagers(), tm, new SecureRandom());
        SSLSocket s = (SSLSocket) sc.getSocketFactory().createSocket();
        s.connect(new java.net.InetSocketAddress(host, port), 5000);
        s.setSoTimeout(0);
        s.startHandshake();
        return s;
    }

    // ---------------- emparelhamento (porta 6467) ----------------
    static final class Pairing {
        SSLSocket s; InputStream in; OutputStream out; X509Certificate server;
    }

    static void startPairing(final Context ctx, final String host) {
        new Thread(() -> {
            try {
                Diag.log("GoogleTV: a emparelhar com " + host);
                Pairing p = new Pairing();
                p.s = connect(ctx, host, 6467);
                p.s.setSoTimeout(20000);
                p.in = p.s.getInputStream(); p.out = p.s.getOutputStream();
                p.server = (X509Certificate) p.s.getSession().getPeerCertificates()[0];
                // 1. pedido de emparelhamento
                send(p.out, msg(new Pb().i(1, 2).i(2, 200).m(10, new Pb().s(1, "atvremote").s(2, "LUMIN Filmes"))));
                expectOk(p.in, "pedido");
                // 2. opções: código hexadecimal de 6 caracteres, nós escrevemos o código
                Pb enc = new Pb().i(1, 3).i(2, 6);
                send(p.out, msg(new Pb().i(1, 2).i(2, 200).m(20, new Pb().m(1, enc).i(3, 1))));
                expectOk(p.in, "opções");
                // 3. configuração -> a TV mostra o código
                send(p.out, msg(new Pb().i(1, 2).i(2, 200).m(30, new Pb().m(1, enc).i(2, 1))));
                expectOk(p.in, "configuração");
                synchronized (pairings) { pairings.put(host, p); }
                SmartTv.status(host, "code");
            } catch (Exception e) {
                Diag.log("GoogleTV: emparelhamento falhou " + e);
                SmartTv.status(host, "erro: não foi possível emparelhar (" + e.getMessage() + ")");
            }
        }).start();
    }

    static void finishPairing(final Context ctx, final String host, final String codeIn) {
        new Thread(() -> {
            Pairing p; synchronized (pairings) { p = pairings.remove(host); }
            if (p == null) { SmartTv.status(host, "erro: recomeça o emparelhamento"); return; }
            try {
                String code = codeIn.trim().toUpperCase();
                RSAPublicKey c = (RSAPublicKey) clientCert(ctx).getPublicKey(), sv = (RSAPublicKey) p.server.getPublicKey();
                MessageDigest d = MessageDigest.getInstance("SHA-256");
                d.update(unsigned(c.getModulus())); d.update(unsigned(c.getPublicExponent()));
                d.update(unsigned(sv.getModulus())); d.update(unsigned(sv.getPublicExponent()));
                d.update(hex(code.substring(2)));
                byte[] h = d.digest();
                if ((h[0] & 0xff) != Integer.parseInt(code.substring(0, 2), 16)) {
                    SmartTv.status(host, "erro: código errado"); p.s.close(); return;
                }
                send(p.out, msg(new Pb().i(1, 2).i(2, 200).m(40, new Pb().b(1, h))));
                expectOk(p.in, "código");
                p.s.close();
                ctx.getSharedPreferences("lumin-gtv", 0).edit().putBoolean("paired-" + host, true).apply();
                Diag.log("GoogleTV: emparelhada " + host);
                remote(ctx, host);
            } catch (Exception e) {
                Diag.log("GoogleTV: código falhou " + e);
                SmartTv.status(host, "erro: código recusado pela TV");
            }
        }).start();
    }

    static boolean paired(Context ctx, String host) { return ctx.getSharedPreferences("lumin-gtv", 0).getBoolean("paired-" + host, false); }

    // ---------------- comando (porta 6466) ----------------
    static final class Remote {
        SSLSocket s; OutputStream out; volatile boolean ok;
        synchronized void send(byte[] m) throws Exception { GoogleTv.send(out, m); }
    }

    private static Remote remote(final Context ctx, final String host) throws Exception {
        synchronized (remotes) {
            Remote r = remotes.get(host);
            if (r != null && r.ok && !r.s.isClosed()) return r;
        }
        final Remote r = new Remote();
        try {
            r.s = connect(ctx, host, 6466);
        } catch (Exception e) {
            // certificado não reconhecido -> é preciso emparelhar outra vez
            ctx.getSharedPreferences("lumin-gtv", 0).edit().remove("paired-" + host).apply();
            throw e;
        }
        r.out = r.s.getOutputStream();
        final InputStream in = r.s.getInputStream();
        r.ok = true;
        synchronized (remotes) { remotes.put(host, r); }
        new Thread(() -> {
            try {
                while (true) {
                    byte[] m = read(in);
                    Map<Integer, List<Object>> f = Pb.parse(m);
                    if (f.containsKey(1)) {        // remote_configure -> responder
                        String model = Build.MODEL != null ? Build.MODEL : "Android";
                        r.send(msg(new Pb().m(1, new Pb().i(1, 611).m(2, new Pb().s(1, model).s(2, Build.MANUFACTURER).i(3, 1).s(4, "1").s(5, "atvremote").s(6, "1.0.0")))));
                    } else if (f.containsKey(2)) { // remote_set_active
                        r.send(msg(new Pb().m(2, new Pb().i(1, 611))));
                        SmartTv.status(host, "ok");
                    } else if (f.containsKey(8)) { // ping -> pong
                        long v = 0;
                        try { v = (Long) Pb.parse((byte[]) f.get(8).get(0)).get(1).get(0); } catch (Exception ignored) {}
                        r.send(msg(new Pb().m(9, new Pb().i(1, v))));
                    } else if (f.containsKey(3)) {
                        Diag.log("GoogleTV: erro do comando " + host);
                    }
                }
            } catch (Exception e) {
                r.ok = false;
                Diag.log("GoogleTV: ligação terminou " + e);
            }
        }, "gtv-remote").start();
        return r;
    }

    static void connectRemote(final Context ctx, final String host) {
        new Thread(() -> {
            try {
                if (!paired(ctx, host)) { startPairing(ctx, host); return; }
                SmartTv.status(host, "A ligar…");
                remote(ctx, host);
            } catch (Exception e) {
                Diag.log("GoogleTV: " + e);
                startPairing(ctx, host);
            }
        }).start();
    }

    static void key(final Context ctx, final String host, final String k) {
        new Thread(() -> {
            int code = code(k);
            if (code == 0) return;
            try { remote(ctx, host).send(msg(new Pb().m(10, new Pb().i(1, code).i(2, 3)))); }
            catch (Exception e) { SmartTv.status(host, "erro: " + e.getMessage()); }
        }).start();
    }

    static void openLink(final Context ctx, final String host, final String url) {
        new Thread(() -> {
            try { remote(ctx, host).send(msg(new Pb().m(90, new Pb().s(1, url)))); Diag.log("GoogleTV: abrir " + url); }
            catch (Exception e) { SmartTv.status(host, "erro: " + e.getMessage()); }
        }).start();
    }

    private static int code(String k) {
        switch (k) {
            case "up": return 19; case "down": return 20; case "left": return 21; case "right": return 22;
            case "ok": return 23; case "back": return 4; case "home": return 3; case "search": return 84;
            case "playpause": return 85; case "ffw": return 90; case "rew": return 89;
            case "volup": return 24; case "voldown": return 25; case "mute": return 164;
            case "chup": return 166; case "chdown": return 167; case "power": return 26;
            case "guide": return 172; case "settings": return 176; case "input": return 178;
            default:
                if (k.length() == 1 && Character.isDigit(k.charAt(0))) return 7 + (k.charAt(0) - '0');
                return 0;
        }
    }

    // ---------------- protobuf mínimo + molduras ----------------
    static final class Pb {
        final ByteArrayOutputStream o = new ByteArrayOutputStream();
        Pb i(int f, long v) { varint(o, (f << 3)); varint(o, v); return this; }
        Pb s(int f, String v) { return b(f, v.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        Pb b(int f, byte[] v) { varint(o, (f << 3) | 2); varint(o, v.length); o.write(v, 0, v.length); return this; }
        Pb m(int f, Pb v) { return b(f, v.o.toByteArray()); }
        byte[] bytes() { return o.toByteArray(); }

        static Map<Integer, List<Object>> parse(byte[] b) {
            Map<Integer, List<Object>> r = new HashMap<>();
            int[] pos = {0};
            while (pos[0] < b.length) {
                long tag = rv(b, pos); int f = (int) (tag >>> 3), wt = (int) (tag & 7);
                Object v;
                if (wt == 0) v = rv(b, pos);
                else if (wt == 2) { int n = (int) rv(b, pos); byte[] x = new byte[n]; System.arraycopy(b, pos[0], x, 0, n); pos[0] += n; v = x; }
                else if (wt == 5) { pos[0] += 4; continue; }
                else if (wt == 1) { pos[0] += 8; continue; }
                else break;
                List<Object> l = r.get(f); if (l == null) { l = new ArrayList<>(); r.put(f, l); }
                l.add(v);
            }
            return r;
        }
        private static long rv(byte[] b, int[] p) { long v = 0; int sh = 0; while (true) { int x = b[p[0]++] & 0xff; v |= (long) (x & 0x7f) << sh; if ((x & 0x80) == 0) return v; sh += 7; } }
    }

    private static void varint(ByteArrayOutputStream o, long v) {
        while ((v & ~0x7fL) != 0) { o.write((int) ((v & 0x7f) | 0x80)); v >>>= 7; }
        o.write((int) v);
    }

    private static byte[] msg(Pb p) { return p.bytes(); }

    static void send(OutputStream out, byte[] m) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        varint(o, m.length); o.write(m, 0, m.length);
        out.write(o.toByteArray()); out.flush();
    }

    static byte[] read(InputStream in) throws Exception {
        long len = 0; int sh = 0;
        while (true) { int x = in.read(); if (x < 0) throw new java.io.EOFException(); len |= (long) (x & 0x7f) << sh; if ((x & 0x80) == 0) break; sh += 7; }
        byte[] b = new byte[(int) len]; int r = 0;
        while (r < len) { int n = in.read(b, r, (int) len - r); if (n < 0) throw new java.io.EOFException(); r += n; }
        return b;
    }

    private static void expectOk(InputStream in, String step) throws Exception {
        Map<Integer, List<Object>> f = Pb.parse(read(in));
        long st = f.containsKey(2) ? (Long) f.get(2).get(0) : 0;
        if (st != 200) throw new Exception(step + ": estado " + st);
    }

    private static byte[] unsigned(BigInteger n) {
        String h = n.toString(16).toUpperCase();
        if (h.length() % 2 == 1) h = "0" + h;
        return hex(h);
    }

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    // ---------------- certificado X.509 auto-assinado (DER mínimo) ----------------
    private static byte[] selfSigned(KeyPair kp) throws Exception {
        byte[] sha256rsa = der(0x30, cat(der(0x06, new byte[]{0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x0b}), new byte[]{0x05, 0x00}));
        byte[] cn = der(0x30, der(0x31, der(0x30, cat(der(0x06, new byte[]{0x55, 0x04, 0x03}), der(0x0c, "atvremote".getBytes())))));
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyMMddHHmmss'Z'", java.util.Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        long now = System.currentTimeMillis();
        byte[] validity = der(0x30, cat(der(0x17, f.format(new java.util.Date(now - 86400000L)).getBytes()),
                                        der(0x17, f.format(new java.util.Date(now + 3650L * 86400000L)).getBytes())));
        byte[] serial = der(0x02, BigInteger.valueOf(Math.abs(new SecureRandom().nextInt()) + 1L).toByteArray());
        byte[] version = der(0xa0, der(0x02, new byte[]{0x02}));
        byte[] tbs = der(0x30, cat(version, serial, sha256rsa, cn, validity, cn, kp.getPublic().getEncoded()));
        Signature sg = Signature.getInstance("SHA256withRSA");
        sg.initSign(kp.getPrivate()); sg.update(tbs);
        byte[] sig = sg.sign();
        return der(0x30, cat(tbs, sha256rsa, der(0x03, cat(new byte[]{0}, sig))));
    }

    private static byte[] der(int tag, byte[] v) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(tag);
        int n = v.length;
        if (n < 128) o.write(n);
        else if (n < 256) { o.write(0x81); o.write(n); }
        else if (n < 65536) { o.write(0x82); o.write(n >> 8); o.write(n & 0xff); }
        else { o.write(0x83); o.write(n >> 16); o.write((n >> 8) & 0xff); o.write(n & 0xff); }
        o.write(v, 0, v.length);
        return o.toByteArray();
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }
}
