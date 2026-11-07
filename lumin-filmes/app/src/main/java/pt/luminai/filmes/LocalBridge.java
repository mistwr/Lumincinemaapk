package pt.luminai.filmes;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Ponte telemóvel → TV. O telemóvel serve, na rede Wi-Fi de casa:
 *   /           a LUMIN em versão web (para o browser de qualquer Smart TV)
 *   /lumin.apk  a própria APK (para instalar na Android TV / box)
 *   /poll       comandos do telemóvel para a versão web na TV (comando remoto)
 */
final class LocalBridge {
    static final int PORT = 8766;
    private static ServerSocket server;
    private static final List<JSONObject> queue = new ArrayList<>();
    private static int seq = 0;

    static boolean running() { return server != null && !server.isClosed(); }

    static String info(Context c) {
        try {
            String ip = RemoteHub.ip(c);
            JSONObject o = new JSONObject();
            o.put("running", running()); o.put("ip", ip);
            o.put("url", ip.isEmpty() ? "" : "http://" + ip + ":" + PORT + "/");
            o.put("apk", ip.isEmpty() ? "" : "http://" + ip + ":" + PORT + "/lumin.apk");
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    static synchronized void start(Context ctx) {
        if (running()) return;
        final Context app = ctx.getApplicationContext();
        try { server = new ServerSocket(PORT); } catch (Exception e) { server = null; return; }
        new Thread(() -> {
            while (running()) {
                try { final Socket s = server.accept(); new Thread(() -> handle(app, s)).start(); } catch (Exception ignored) {}
            }
        }, "lumin-bridge").start();
    }

    static synchronized void stop() {
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        server = null;
    }

    static void push(String json) {
        try {
            JSONObject o = new JSONObject(json);
            synchronized (queue) {
                o.put("_seq", ++seq); queue.add(o);
                while (queue.size() > 50) queue.remove(0);
                queue.notifyAll();
            }
        } catch (Exception ignored) {}
    }

    private static void handle(Context app, Socket s) {
        try {
            s.setSoTimeout(30000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String req = in.readLine();
            if (req == null) { s.close(); return; }
            String line; while ((line = in.readLine()) != null && !line.isEmpty()) { /* ignora cabeçalhos */ }
            String path = req.split(" ").length > 1 ? req.split(" ")[1] : "/";
            OutputStream os = s.getOutputStream();
            if (path.startsWith("/lumin.apk")) {
                File f = new File(app.getApplicationInfo().sourceDir);
                os.write(("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.android.package-archive\r\nContent-Disposition: attachment; filename=\"LUMIN-Filmes.apk\"\r\nContent-Length: " + f.length() + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                try (InputStream fi = new FileInputStream(f)) { byte[] b = new byte[65536]; int n; while ((n = fi.read(b)) > 0) os.write(b, 0, n); }
            } else if (path.startsWith("/poll")) {
                int since = 0;
                int i = path.indexOf("since=");
                if (i >= 0) try { since = Integer.parseInt(path.substring(i + 6).replaceAll("[^0-9].*$", "")); } catch (Exception ignored) {}
                JSONArray out = new JSONArray();
                synchronized (queue) {
                    // since=0: sincronização inicial, responde já com o número atual (sem comandos antigos)
                    long end = path.contains("sync=1") ? 0 : System.currentTimeMillis() + 20000;
                    while (true) {
                        for (JSONObject o : queue) if (o.optInt("_seq") > since) out.put(o);
                        if (path.contains("sync=1")) { out = new JSONArray(); break; }
                        if (out.length() > 0 || System.currentTimeMillis() > end) break;
                        queue.wait(Math.max(1, end - System.currentTimeMillis()));
                    }
                }
                byte[] b = new JSONObject().put("seq", seq).put("cmds", out).toString().getBytes(StandardCharsets.UTF_8);
                os.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nCache-Control: no-store\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                os.write(b);
            } else if (path.equals("/") || path.startsWith("/?") || path.startsWith("/index.html")) {
                String html = SmartTv.read(app.getAssets().open("index.html"));
                html = html.replace("<script>", "<script>window.LUMIN_BRIDGE=true;</script><script>");
                byte[] b = html.getBytes(StandardCharsets.UTF_8);
                os.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                os.write(b);
            } else {
                os.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            }
            os.flush(); s.close();
        } catch (Exception e) {
            try { s.close(); } catch (Exception ignored) {}
        }
    }
}
