package pt.luminai.filmes;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.util.Base64;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Comando universal para Smart TVs na rede local:
 *  - Samsung (Tizen, 2016+): WebSocket samsung.remote.control (8002 wss / 8001 ws)
 *  - LG (webOS): SSAP por WebSocket (3000 ws / 3001 wss) + pointer socket para as setas
 *  - Roku: ECP por HTTP (porta 8060)
 * Descoberta por SSDP (UPnP). A primeira ligação pede autorização no ecrã da TV.
 */
final class SmartTv {
    private static final Map<String, JSONObject> found = new LinkedHashMap<>();
    private static final Map<String, Conn> conns = new HashMap<>();
    private static volatile boolean scanning = false;

    // ---------------- descoberta ----------------
    static void discover(final Context ctx) {
        if (scanning) return;
        scanning = true;
        Diag.log("SSDP: a procurar TVs… (IP deste telemóvel: " + RemoteHub.ip(ctx) + ")");
        new Thread(() -> {
            WifiManager.MulticastLock lock = null;
            try {
                WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                lock = wm.createMulticastLock("lumin-ssdp"); lock.setReferenceCounted(false); lock.acquire();
                DatagramSocket s = new DatagramSocket();
                s.setSoTimeout(1500);
                String[] sts = {"urn:samsung.com:device:RemoteControlReceiver:1", "urn:lge-com:service:webos-second-screen:1",
                                "roku:ecp", "urn:dial-multiscreen-org:service:dial:1"};
                InetAddress grp = InetAddress.getByName("239.255.255.250");
                for (int round = 0; round < 2; round++) {
                    for (String st : sts) {
                        String m = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: " + st + "\r\n\r\n";
                        byte[] b = m.getBytes(StandardCharsets.US_ASCII);
                        s.send(new DatagramPacket(b, b.length, grp, 1900));
                    }
                    long end = System.currentTimeMillis() + 3000;
                    while (System.currentTimeMillis() < end) {
                        try {
                            byte[] buf = new byte[2048];
                            DatagramPacket p = new DatagramPacket(buf, buf.length);
                            s.receive(p);
                            onReply(p.getAddress().getHostAddress(), new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8));
                        } catch (java.net.SocketTimeoutException ignored) {}
                    }
                }
                s.close();
            } catch (Exception e) {
                Diag.log("SSDP: falhou " + e);
            } finally {
                if (lock != null) try { lock.release(); } catch (Exception ignored) {}
                scanning = false;
                synchronized (found) { Diag.log("SSDP: fim, " + found.size() + " TV(s) reconhecida(s)"); }
            }
        }, "lumin-ssdp").start();
    }

    private static String header(String r, String name) {
        Matcher m = Pattern.compile("(?im)^" + name + ":\\s*(.+)$").matcher(r);
        return m.find() ? m.group(1).trim() : "";
    }

    private static void onReply(String ip, String r) {
        String st = header(r, "ST").toLowerCase(), server = header(r, "SERVER").toLowerCase(), loc = header(r, "LOCATION");
        Diag.log("SSDP: resposta de " + ip + " | " + header(r, "SERVER") + " | " + header(r, "ST"));
        String type = null;
        if (st.contains("samsung") || server.contains("samsung") || server.contains("tizen")) type = "samsung";
        else if (st.contains("lge") || server.contains("webos")) type = "lg";
        else if (st.contains("roku") || server.contains("roku")) type = "roku";
        if (type == null) return;
        String key = type + "@" + ip;
        synchronized (found) { if (found.containsKey(key)) return; }
        String name = friendlyName(loc);
        if (name.isEmpty()) name = type.equals("samsung") ? "Samsung TV" : type.equals("lg") ? "LG TV" : "Roku";
        try {
            JSONObject o = new JSONObject();
            o.put("kind", type); o.put("host", ip); o.put("name", name);
            synchronized (found) { found.put(key, o); }
        } catch (Exception ignored) {}
        push();
    }

    private static String friendlyName(String loc) {
        if (loc == null || loc.isEmpty()) return "";
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(loc).openConnection();
            c.setConnectTimeout(2000); c.setReadTimeout(2500);
            String x = read(c.getInputStream());
            Matcher m = Pattern.compile("<friendlyName>([^<]+)</friendlyName>").matcher(x);
            return m.find() ? m.group(1).replace("&amp;", "&").trim() : "";
        } catch (Exception e) { return ""; }
    }

    static void push() {
        JSONArray a = new JSONArray();
        synchronized (found) { for (JSONObject o : found.values()) a.put(o); }
        RemoteHub.js("window.__luminSTVs && window.__luminSTVs(" + a + ")");
    }

    // ---------------- comandos ----------------
    static void key(Context ctx, String kind, String host, String k) {
        new Thread(() -> {
            try {
                if ("roku".equals(kind)) roku(host, k);
                else conn(ctx, kind, host).key(k);
            } catch (Exception e) { status(host, "erro: " + e.getMessage()); }
        }).start();
    }

    static void text(Context ctx, String kind, String host, String t) {
        new Thread(() -> {
            try {
                if ("roku".equals(kind)) { for (char ch : t.toCharArray()) post("http://" + host + ":8060/keypress/Lit_" + java.net.URLEncoder.encode(String.valueOf(ch), "UTF-8")); }
                else conn(ctx, kind, host).text(t);
            } catch (Exception e) { status(host, "erro: " + e.getMessage()); }
        }).start();
    }

    static void openUrl(Context ctx, String kind, String host, String url) {
        new Thread(() -> {
            try { conn(ctx, kind, host).openUrl(url); } catch (Exception e) { status(host, "erro: " + e.getMessage()); }
        }).start();
    }

    static void connect(Context ctx, String kind, String host) {
        new Thread(() -> {
            try {
                if ("roku".equals(kind)) {
                    HttpURLConnection c = (HttpURLConnection) new URL("http://" + host + ":8060/query/device-info").openConnection();
                    c.setConnectTimeout(2500); c.setReadTimeout(3000);
                    status(host, c.getResponseCode() == 200 ? "ok" : "erro: Roku não respondeu"); c.disconnect(); return;
                }
                conn(ctx, kind, host);
            } catch (Exception e) { status(host, "erro: " + e.getMessage()); }
        }).start();
    }

    static void status(String host, String s) {
        Diag.log("TV " + host + ": " + s);
        RemoteHub.js("window.__luminSTVStatus && window.__luminSTVStatus(" + JSONObject.quote(host) + "," + JSONObject.quote(s) + ")");
    }

    private static synchronized Conn conn(Context ctx, String kind, String host) throws Exception {
        String k = kind + "@" + host;
        Conn c = conns.get(k);
        if (c != null && c.alive()) return c;
        c = "samsung".equals(kind) ? new Samsung(ctx, host) : new Lg(ctx, host);
        c.open();
        conns.put(k, c);
        return c;
    }

    // ---------------- Roku ----------------
    private static void roku(String host, String k) throws Exception {
        String key;
        switch (k) {
            case "up": key = "Up"; break; case "down": key = "Down"; break; case "left": key = "Left"; break; case "right": key = "Right"; break;
            case "ok": key = "Select"; break; case "back": key = "Back"; break; case "home": key = "Home"; break;
            case "playpause": key = "Play"; break; case "ffw": key = "Fwd"; break; case "rew": key = "Rev"; break;
            case "volup": key = "VolumeUp"; break; case "voldown": key = "VolumeDown"; break; case "mute": key = "VolumeMute"; break;
            case "search": key = "Search"; break; default: return;
        }
        post("http://" + host + ":8060/keypress/" + key);
    }

    private static void post(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setRequestMethod("POST"); c.setConnectTimeout(2500); c.setReadTimeout(3000); c.setDoOutput(true);
        c.getOutputStream().write(new byte[0]);
        c.getResponseCode(); c.disconnect();
    }

    // ---------------- base WebSocket ----------------
    abstract static class Conn {
        final Context ctx; final String host; volatile WebSocketClient ws; volatile boolean ready;
        Conn(Context c, String h) { ctx = c.getApplicationContext(); host = h; }
        boolean alive() { return ws != null && ws.isOpen() && ready; }
        abstract void open() throws Exception;
        abstract void key(String k) throws Exception;
        abstract void text(String t) throws Exception;
        void openUrl(String url) throws Exception { throw new Exception("não suportado"); }
        SharedPreferences prefs() { return ctx.getSharedPreferences("lumin-stv", 0); }
    }

    private static SSLContext trustAll() throws Exception {
        // As TVs usam certificados próprios (auto-assinados) na rede local
        TrustManager[] tm = {new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }};
        SSLContext sc = SSLContext.getInstance("TLS"); sc.init(null, tm, new java.security.SecureRandom());
        return sc;
    }

    private interface OnMsg { void on(String m); }

    private static WebSocketClient client(String url, OnMsg onMsg, Runnable onOpen) throws Exception {
        WebSocketClient c = new WebSocketClient(new URI(url)) {
            @Override public void onOpen(ServerHandshake h) { if (onOpen != null) onOpen.run(); }
            @Override public void onMessage(String m) { Diag.log("WS<- " + (m.length() > 160 ? m.substring(0, 160) + "…" : m)); onMsg.on(m); }
            @Override public void onClose(int code, String reason, boolean remote) { Diag.log("WS fechado " + code + " " + reason); }
            @Override public void onError(Exception ex) { Diag.log("WS erro " + ex); }
        };
        if (url.startsWith("wss")) c.setSocketFactory(trustAll().getSocketFactory());
        c.setConnectionLostTimeout(30);
        return c;
    }

    // ---------------- Samsung ----------------
    static final class Samsung extends Conn {
        Samsung(Context c, String h) { super(c, h); }

        @Override void open() throws Exception {
            String name = Base64.encodeToString("LUMIN Filmes".getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            String token = prefs().getString("sam-" + host, "");
            String[] urls = {
                "wss://" + host + ":8002/api/v2/channels/samsung.remote.control?name=" + name + (token.isEmpty() ? "" : "&token=" + token),
                "ws://" + host + ":8001/api/v2/channels/samsung.remote.control?name=" + name
            };
            status(host, "A ligar… aceita o pedido na TV");
            for (String u : urls) {
                final Object lock = new Object();
                ws = client(u, m -> {
                    try {
                        JSONObject j = new JSONObject(m);
                        if ("ms.channel.connect".equals(j.optString("event"))) {
                            String t = j.optJSONObject("data") != null ? j.getJSONObject("data").optString("token") : "";
                            if (!t.isEmpty()) prefs().edit().putString("sam-" + host, t).apply();
                            ready = true; status(host, "ok");
                            synchronized (lock) { lock.notifyAll(); }
                        } else if ("ms.channel.unauthorized".equals(j.optString("event"))) {
                            status(host, "recusado na TV");
                            synchronized (lock) { lock.notifyAll(); }
                        }
                    } catch (Exception ignored) {}
                }, null);
                Diag.log("Samsung: a tentar " + u.replaceAll("token=[^&]+", "token=***"));
                try { ws.connectBlocking(4, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception e) { Diag.log("Samsung: " + e); }
                Diag.log("Samsung: ligação " + (ws.isOpen() ? "aberta" : "falhou"));
                if (ws.isOpen()) {
                    synchronized (lock) { if (!ready) lock.wait(30000); }  // tempo para carregar "Permitir" na TV
                    if (ready) return;
                }
                try { ws.close(); } catch (Exception ignored) {}
            }
            throw new Exception("não foi possível ligar à Samsung");
        }

        private void send(String key) throws Exception {
            JSONObject p = new JSONObject();
            p.put("Cmd", "Click"); p.put("DataOfCmd", key); p.put("Option", "false"); p.put("TypeOfRemote", "SendRemoteKey");
            ws.send(new JSONObject().put("method", "ms.remote.control").put("params", p).toString());
        }

        @Override void key(String k) throws Exception {
            String key;
            switch (k) {
                case "up": key = "KEY_UP"; break; case "down": key = "KEY_DOWN"; break; case "left": key = "KEY_LEFT"; break; case "right": key = "KEY_RIGHT"; break;
                case "ok": key = "KEY_ENTER"; break; case "back": key = "KEY_RETURN"; break; case "home": key = "KEY_HOME"; break;
                case "playpause": key = "KEY_PLAY"; break; case "ffw": key = "KEY_FF"; break; case "rew": key = "KEY_REWIND"; break;
                case "volup": key = "KEY_VOLUP"; break; case "voldown": key = "KEY_VOLDOWN"; break; case "mute": key = "KEY_MUTE"; break;
                case "search": key = "KEY_HOME"; break; default: return;
            }
            send(key);
        }

        @Override void text(String t) throws Exception {
            JSONObject p = new JSONObject();
            p.put("Cmd", Base64.encodeToString(t.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
            p.put("DataOfCmd", "base64"); p.put("TypeOfRemote", "SendInputString");
            ws.send(new JSONObject().put("method", "ms.remote.control").put("params", p).toString());
        }
    }

    // ---------------- LG webOS ----------------
    static final class Lg extends Conn {
        private WebSocketClient input;  // pointer socket (setas/OK/voltar)
        private int seq = 0;
        private boolean muted = false, playing = true;
        private final Map<String, OnMsg> pending = new HashMap<>();
        Lg(Context c, String h) { super(c, h); }

        @Override void open() throws Exception {
            status(host, "A ligar… aceita o pedido na TV");
            String[] urls = {"ws://" + host + ":3000", "wss://" + host + ":3001"};
            for (String u : urls) {
                final Object lock = new Object();
                ws = client(u, m -> {
                    try {
                        JSONObject j = new JSONObject(m);
                        String type = j.optString("type"), id = j.optString("id");
                        if ("registered".equals(type)) {
                            String key = j.optJSONObject("payload") != null ? j.getJSONObject("payload").optString("client-key") : "";
                            if (!key.isEmpty()) prefs().edit().putString("lg-" + host, key).apply();
                            ready = true; status(host, "ok");
                            synchronized (lock) { lock.notifyAll(); }
                        } else if ("error".equals(type) && id.startsWith("reg")) {
                            if (j.optString("error").contains("blacklisted")) register(true);
                            else { status(host, "recusado na TV"); synchronized (lock) { lock.notifyAll(); } }
                        } else {
                            OnMsg cb; synchronized (pending) { cb = pending.remove(id); }
                            if (cb != null) cb.on(m);
                        }
                    } catch (Exception ignored) {}
                }, null);
                Diag.log("LG: a tentar " + u);
                try { ws.connectBlocking(4, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception e) { Diag.log("LG: " + e); }
                Diag.log("LG: ligação " + (ws.isOpen() ? "aberta" : "falhou"));
                if (ws.isOpen()) {
                    register(false);
                    synchronized (lock) { if (!ready) lock.wait(30000); }
                    if (ready) { openInput(); return; }
                }
                try { ws.close(); } catch (Exception ignored) {}
            }
            throw new Exception("não foi possível ligar à LG");
        }

        private void register(boolean unsigned) throws Exception {
            JSONObject pairing = new JSONObject(asset("lg-pairing.json"));
            if (unsigned) {
                JSONObject man = pairing.getJSONObject("manifest");
                man.remove("signed"); man.put("appVersion", "1.0");
                man.getJSONArray("permissions").put("CONTROL_INPUT_TEXT").put("CONTROL_MOUSE_AND_KEYBOARD");
            }
            String key = prefs().getString("lg-" + host, "");
            if (!key.isEmpty()) pairing.put("client-key", key);
            ws.send(new JSONObject().put("type", "register").put("id", "reg" + (seq++)).put("payload", pairing).toString());
        }

        private String asset(String name) throws Exception {
            InputStream in = ctx.getAssets().open(name);
            return read(in);
        }

        private void request(String uri, JSONObject payload, OnMsg cb) throws Exception {
            String id = "r" + (seq++);
            if (cb != null) synchronized (pending) { pending.put(id, cb); }
            JSONObject o = new JSONObject().put("type", "request").put("id", id).put("uri", uri);
            if (payload != null) o.put("payload", payload);
            ws.send(o.toString());
        }

        private void openInput() throws Exception {
            final Object lock = new Object();
            request("ssap://com.webos.service.networkinput/getPointerInputSocket", null, m -> {
                try {
                    String path = new JSONObject(m).getJSONObject("payload").optString("socketPath");
                    if (!path.isEmpty()) {
                        input = client(path, x -> {}, null);
                        input.connectBlocking(4, java.util.concurrent.TimeUnit.SECONDS);
                    }
                } catch (Exception ignored) {}
                synchronized (lock) { lock.notifyAll(); }
            });
            synchronized (lock) { lock.wait(5000); }
        }

        private void button(String name) throws Exception {
            if (input == null || !input.isOpen()) openInput();
            if (input != null && input.isOpen()) input.send("type:button\nname:" + name + "\n\n");
        }

        @Override void key(String k) throws Exception {
            switch (k) {
                case "up": button("UP"); break; case "down": button("DOWN"); break; case "left": button("LEFT"); break; case "right": button("RIGHT"); break;
                case "ok": button("ENTER"); break; case "back": button("BACK"); break; case "home": button("HOME"); break;
                case "search": button("HOME"); break;
                case "playpause": request(playing ? "ssap://media.controls/pause" : "ssap://media.controls/play", null, null); playing = !playing; break;
                case "ffw": request("ssap://media.controls/fastForward", null, null); break;
                case "rew": request("ssap://media.controls/rewind", null, null); break;
                case "volup": request("ssap://audio/volumeUp", null, null); break;
                case "voldown": request("ssap://audio/volumeDown", null, null); break;
                case "mute": muted = !muted; request("ssap://audio/setMute", new JSONObject().put("mute", muted), null); break;
            }
        }

        @Override void text(String t) throws Exception {
            request("ssap://com.webos.service.ime/insertText", new JSONObject().put("text", t).put("replace", 0), null);
        }

        @Override void openUrl(String url) throws Exception {
            request("ssap://system.launcher/open", new JSONObject().put("target", url), null);
        }
    }

    static String read(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192]; int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        in.close();
        return out.toString("UTF-8");
    }
}
