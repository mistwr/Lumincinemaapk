package pt.luminai.filmes;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * "LUMIN na TV": a TV corre um pequeno servidor na rede local (com código de 4 dígitos)
 * e anuncia-se por NSD/mDNS. O telemóvel descobre a TV, liga-se com o código e envia
 * comandos (setas, OK, voltar, play/pausa, volume, abrir título, tocar vídeo).
 */
final class RemoteHub {
    static final String TYPE = "_luminfilmes._tcp.";
    static final int PORT = 8765;

    static WeakReference<Activity> top = new WeakReference<>(null);
    static MainActivity main;   // para executar JS na interface

    private static ServerSocket server;
    private static Thread serverThread;
    private static NsdManager.RegistrationListener regListener;
    private static NsdManager.DiscoveryListener discListener;
    private static final Map<String, JSONObject> found = new LinkedHashMap<>();
    private static final Handler ui = new Handler(Looper.getMainLooper());

    // ---------------- servidor (lado da TV) ----------------
    static String code(Context c) {
        SharedPreferences p = c.getSharedPreferences("lumin", 0);
        String code = p.getString("tvcode", null);
        if (code == null) {
            code = String.format("%04d", new SecureRandom().nextInt(10000));
            p.edit().putString("tvcode", code).apply();
        }
        return code;
    }

    static String name() { return "LUMIN · " + (Build.MODEL != null ? Build.MODEL : "TV"); }

    static boolean running() { return server != null && !server.isClosed(); }

    static String ip(Context c) {
        try {
            WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            int a = wm.getConnectionInfo().getIpAddress();
            if (a != 0) return (a & 255) + "." + (a >> 8 & 255) + "." + (a >> 16 & 255) + "." + (a >> 24 & 255);
        } catch (Exception ignored) {}
        return "";
    }

    static String info(Context c) {
        try {
            JSONObject o = new JSONObject();
            o.put("running", running()); o.put("code", code(c)); o.put("ip", ip(c)); o.put("port", PORT); o.put("name", name());
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    static synchronized void start(final Context ctx) {
        if (running()) return;
        final Context app = ctx.getApplicationContext();
        final String code = code(app);
        try {
            server = new ServerSocket(PORT);
        } catch (Exception e) { server = null; return; }
        serverThread = new Thread(() -> {
            while (running()) {
                try {
                    final Socket s = server.accept();
                    new Thread(() -> handle(app, s, code)).start();
                } catch (Exception ignored) {}
            }
        }, "lumin-remote");
        serverThread.start();
        app.getSharedPreferences("lumin", 0).edit().putBoolean("tvmode", true).apply();
        try {
            NsdServiceInfo si = new NsdServiceInfo();
            si.setServiceName(name()); si.setServiceType(TYPE); si.setPort(PORT);
            regListener = new NsdManager.RegistrationListener() {
                public void onServiceRegistered(NsdServiceInfo i) {}
                public void onRegistrationFailed(NsdServiceInfo i, int e) {}
                public void onServiceUnregistered(NsdServiceInfo i) {}
                public void onUnregistrationFailed(NsdServiceInfo i, int e) {}
            };
            ((NsdManager) app.getSystemService(Context.NSD_SERVICE)).registerService(si, NsdManager.PROTOCOL_DNS_SD, regListener);
        } catch (Exception ignored) {}
        js("window.__luminServer && window.__luminServer(" + info(app) + ")");
    }

    static synchronized void stop(Context ctx) {
        Context app = ctx.getApplicationContext();
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        server = null;
        try { if (regListener != null) ((NsdManager) app.getSystemService(Context.NSD_SERVICE)).unregisterService(regListener); } catch (Exception ignored) {}
        regListener = null;
        app.getSharedPreferences("lumin", 0).edit().putBoolean("tvmode", false).apply();
    }

    private static void handle(Context app, Socket s, String code) {
        try {
            s.setSoTimeout(8000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String line = in.readLine();
            if (line == null) { s.close(); return; }
            int len = 0; String gotCode = "";
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                String l = line.toLowerCase();
                if (l.startsWith("content-length:")) len = Integer.parseInt(line.substring(15).trim());
                if (l.startsWith("x-lumin-code:")) gotCode = line.substring(13).trim();
            }
            char[] buf = new char[Math.min(len, 65536)]; int r = 0;
            while (r < buf.length) { int n = in.read(buf, r, buf.length - r); if (n < 0) break; r += n; }
            String body = new String(buf, 0, r);
            int status = 200; String resp = "{\"ok\":true,\"name\":" + JSONObject.quote(name()) + "}";
            if (!code.equals(gotCode)) { status = 403; resp = "{\"ok\":false}"; }
            else exec(app, new JSONObject(body.isEmpty() ? "{}" : body));
            byte[] out = resp.getBytes(StandardCharsets.UTF_8);
            OutputStream os = s.getOutputStream();
            os.write(("HTTP/1.1 " + status + (status == 200 ? " OK" : " Forbidden") + "\r\nContent-Type: application/json\r\nContent-Length: " + out.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            os.write(out); os.flush(); s.close();
        } catch (Exception e) {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private static void exec(Context app, final JSONObject cmd) {
        String t = cmd.optString("t");
        if ("ping".equals(t)) return;
        if ("key".equals(t)) {
            String k = cmd.optString("k");
            AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            switch (k) {
                case "volup": am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI); return;
                case "voldown": am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI); return;
                case "mute": am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI); return;
            }
            final int code;
            switch (k) {
                case "up": code = KeyEvent.KEYCODE_DPAD_UP; break;
                case "down": code = KeyEvent.KEYCODE_DPAD_DOWN; break;
                case "left": code = KeyEvent.KEYCODE_DPAD_LEFT; break;
                case "right": code = KeyEvent.KEYCODE_DPAD_RIGHT; break;
                case "ok": code = KeyEvent.KEYCODE_DPAD_CENTER; break;
                case "back": code = KeyEvent.KEYCODE_BACK; break;
                case "playpause": code = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE; break;
                case "ffw": code = KeyEvent.KEYCODE_MEDIA_FAST_FORWARD; break;
                case "rew": code = KeyEvent.KEYCODE_MEDIA_REWIND; break;
                default: return;
            }
            ui.post(() -> {
                Activity a = top.get();
                if (a == null) return;
                long now = SystemClock.uptimeMillis();
                a.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
                a.dispatchKeyEvent(new KeyEvent(now, now + 30, KeyEvent.ACTION_UP, code, 0));
            });
            return;
        }
        // abrir/tocar/escrever: vai para a interface principal (fecha o ecrã "Ver" se estiver aberto)
        ui.post(() -> {
            Activity a = top.get();
            if (a instanceof WatchActivity) a.finish();
            if (main != null) main.runJs("window.luminRemote && window.luminRemote(" + cmd.toString() + ")");
        });
    }

    static void js(final String code) { ui.post(() -> { if (main != null) main.runJs(code); }); }

    // ---------------- cliente (lado do telemóvel) ----------------
    static void discover(Context ctx) {
        final NsdManager nsd = (NsdManager) ctx.getApplicationContext().getSystemService(Context.NSD_SERVICE);
        stopDiscover(ctx);
        synchronized (found) { found.clear(); }
        push();
        discListener = new NsdManager.DiscoveryListener() {
            public void onStartDiscoveryFailed(String t, int e) { Diag.log("NSD: falhou ao procurar (" + e + ")"); }
            public void onStopDiscoveryFailed(String t, int e) {}
            public void onDiscoveryStarted(String t) {}
            public void onDiscoveryStopped(String t) {}
            public void onServiceLost(NsdServiceInfo si) {
                synchronized (found) { found.remove(si.getServiceName()); } push();
            }
            public void onServiceFound(NsdServiceInfo si) {
                Diag.log("NSD: encontrado " + si.getServiceName());
                if (!si.getServiceType().contains("luminfilmes")) return;
                try {
                    nsd.resolveService(si, new NsdManager.ResolveListener() {
                        public void onResolveFailed(NsdServiceInfo i, int e) {}
                        public void onServiceResolved(NsdServiceInfo i) {
                            try {
                                InetAddress h = i.getHost();
                                JSONObject o = new JSONObject();
                                o.put("name", i.getServiceName()); o.put("host", h.getHostAddress()); o.put("port", i.getPort());
                                synchronized (found) { found.put(i.getServiceName(), o); }
                                push();
                            } catch (Exception ignored) {}
                        }
                    });
                } catch (Exception ignored) {}
            }
        };
        try { nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, discListener); } catch (Exception ignored) {}
    }

    static void stopDiscover(Context ctx) {
        try {
            if (discListener != null) ((NsdManager) ctx.getApplicationContext().getSystemService(Context.NSD_SERVICE)).stopServiceDiscovery(discListener);
        } catch (Exception ignored) {}
        discListener = null;
    }

    private static void push() {
        JSONArray a = new JSONArray();
        synchronized (found) { for (JSONObject o : found.values()) a.put(o); }
        js("window.__luminTVs && window.__luminTVs(" + a.toString() + ")");
    }

    static void send(final String host, final int port, final String code, final String json) {
        new Thread(() -> {
            boolean ok = false;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("http://" + host + ":" + port + "/cmd").openConnection();
                c.setConnectTimeout(3000); c.setReadTimeout(5000);
                c.setRequestMethod("POST"); c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("X-Lumin-Code", code);
                byte[] b = json.getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(b.length);
                c.getOutputStream().write(b);
                ok = c.getResponseCode() == 200;
                c.disconnect();
            } catch (Exception ignored) {}
            Diag.log("LUMIN TV " + host + ": " + (ok ? "ok" : "sem resposta / código errado"));
            js("window.__luminTVResult && window.__luminTVResult(" + ok + ")");
        }).start();
    }
}
