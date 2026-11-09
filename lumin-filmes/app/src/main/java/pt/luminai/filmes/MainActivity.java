package pt.luminai.filmes;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * LUMIN Filmes — a interface vem dentro da APK (assets/index.html),
 * por isso não há servidor nem hosting. Os vídeos vêm do Internet Archive.
 */
public class MainActivity extends Activity {

    private CastHelper cast;

    /** Executa JS na interface (usado pelo comando remoto). */
    void runJs(String code) { if (web != null) web.evaluateJavascript(code, null); }

    private WebView web;
    private FrameLayout root;
    private View fullView;
    private WebChromeClient.CustomViewCallback fullCallback;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#050505"));
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#050505"));
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        web.addJavascriptInterface(new Bridge(), "LuminNative");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return openExternal(r.getUrl());
            }
            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return openExternal(Uri.parse(url));
            }
        });

        // Ecrã inteiro do player de vídeo
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback cb) {
                if (fullView != null) { cb.onCustomViewHidden(); return; }
                fullView = view; fullCallback = cb;
                root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                web.setVisibility(View.GONE);
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                hideBars();
            }
            @Override
            public void onHideCustomView() { exitFull(); }
        });

        hideBars();
        RemoteHub.main = this;
        try { cast = new CastHelper(this); } catch (Throwable t) { cast = null; }
        // Na Android TV (ou se o utilizador ligou o "Modo TV"), arranca o servidor do comando
        boolean isTv = ((android.app.UiModeManager) getSystemService(UI_MODE_SERVICE)).getCurrentModeType() == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION;
        if (isTv || getSharedPreferences("lumin", 0).getBoolean("tvmode", false)) RemoteHub.start(this);
        if (state != null) web.restoreState(state);
        else loadApp();
        deepLink(getIntent());
    }

    private static final String BASE_HOST = "app.luminai.pt";

    /**
     * Carrega a interface embutida com uma origem https (app.luminai.pt) para que
     * os trailers do YouTube e o localStorage funcionem. Nada é pedido a esse domínio.
     */
    private void loadApp() {
        try (InputStream in = getAssets().open("index.html")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            web.loadDataWithBaseURL("https://" + BASE_HOST + "/", out.toString("UTF-8"), "text/html", "UTF-8", null);
        } catch (Exception e) {
            web.loadData("<h3 style='color:#fff'>Erro ao abrir a app</h3>", "text/html", "UTF-8");
        }
    }

    /** Ponte JS ↔ Android. */
    private class Bridge {
        @JavascriptInterface
        public void openExternal(final String url) {
            runOnUiThread(() -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) {}
            });
        }

        /** Abre a página oficial do serviço dentro da LUMIN (WatchActivity). */
        @JavascriptInterface public boolean castAvailable() { return cast != null && cast.available(); }
        @JavascriptInterface public void castConnect() { runOnUiThread(() -> { if (cast != null) cast.choose(); }); }
        @JavascriptInterface public void castMedia(final String url, final String title, final String poster) {
            runOnUiThread(() -> { if (cast != null) cast.cast(url, title, poster); });
        }
        @JavascriptInterface public void tvDiscover() { RemoteHub.discover(MainActivity.this); }
        @JavascriptInterface public void tvDiscoverStop() { RemoteHub.stopDiscover(MainActivity.this); }
        @JavascriptInterface public void tvSend(String host, int port, String code, String json) { RemoteHub.send(host, port, code, json); }
        @JavascriptInterface public String tvServerInfo() { return RemoteHub.info(MainActivity.this); }
        @JavascriptInterface public void tvServerStart() { RemoteHub.start(MainActivity.this); }
        @JavascriptInterface public void tvServerStop() { RemoteHub.stop(MainActivity.this); }
        // Smart TVs (Samsung, LG, Roku)
        @JavascriptInterface public void stvDiscover() { SmartTv.discover(MainActivity.this); SmartTv.push(); }
        @JavascriptInterface public void stvConnect(String kind, String host) { SmartTv.connect(MainActivity.this, kind, host); }
        @JavascriptInterface public void stvKey(String kind, String host, String k) { SmartTv.key(MainActivity.this, kind, host, k); }
        @JavascriptInterface public void stvText(String kind, String host, String t) { SmartTv.text(MainActivity.this, kind, host, t); }
        @JavascriptInterface public void stvOpenUrl(String kind, String host, String url) { SmartTv.openUrl(MainActivity.this, kind, host, url); }
        // Ponte telemóvel -> TV
        @JavascriptInterface public void gtvCode(String host, String code) { GoogleTv.finishPairing(MainActivity.this, host, code); }
        @JavascriptInterface public void gtvPair(String host) { GoogleTv.startPairing(MainActivity.this, host); }
        /** Resolve os links "onde ver" num WebView escondido (passa as proteções do TMDB como um browser). */
        @JavascriptInterface public void resolveLinksWeb(final String tmdbLink, final String cbId) {
            runOnUiThread(() -> {
                final WebView w = new WebView(MainActivity.this);
                w.getSettings().setJavaScriptEnabled(true);
                w.getSettings().setDomStorageEnabled(true);
                w.getSettings().setUserAgentString(w.getSettings().getUserAgentString().replace("; wv)", ")"));
                final boolean[] done = {false};
                final Runnable finish = () -> { if (!done[0]) { done[0] = true; runJs("window.__luminLinks && window.__luminLinks(" + org.json.JSONObject.quote(cbId) + ",[])"); w.destroy(); } };
                w.setWebViewClient(new WebViewClient() {
                    @Override public void onPageFinished(WebView v, String u) {
                        if (done[0] || !u.contains("themoviedb.org")) return;
                        v.evaluateJavascript("(function(){var out=[],seen={};document.querySelectorAll('a[href*=\"click.justwatch.com\"]').forEach(function(a){try{var q=new URL(a.href).searchParams;var cx=q.get('cx').replace(/-/g,'+').replace(/_/g,'/');while(cx.length%4)cx+='=';var d=JSON.parse(atob(cx)).data[0].data;var k=d.provider+'|'+d.monetizationType;if(seen[k])return;seen[k]=1;out.push({provider:d.provider,type:d.monetizationType,url:a.href,target:q.get('r')})}catch(e){}});return JSON.stringify(out)})()", r -> {
                            if (done[0]) return; done[0] = true;
                            String json = "[]";
                            try { json = new org.json.JSONTokener(r).nextValue().toString(); } catch (Exception ignored) {}
                            Diag.log("Links (WebView): " + json.length() + " bytes");
                            runJs("window.__luminLinks && window.__luminLinks(" + org.json.JSONObject.quote(cbId) + "," + json + ")");
                            w.destroy();
                        });
                    }
                });
                w.loadUrl(tmdbLink);
                w.postDelayed(finish, 15000);
            });
        }
        @JavascriptInterface public String diag() { return Diag.dump(); }
        @JavascriptInterface public void diagLog(String s) { Diag.log("App: " + s); }
        @JavascriptInterface public String bridgeInfo() { return LocalBridge.info(MainActivity.this); }
        @JavascriptInterface public void bridgeStart() { LocalBridge.start(MainActivity.this); }
        @JavascriptInterface public void bridgeStop() { LocalBridge.stop(); }
        @JavascriptInterface public void bridgePush(String json) { LocalBridge.push(json); }

        @JavascriptInterface
        public void openInApp(final String url, final String title, final String provider) {
            runOnUiThread(() -> {
                Intent i = new Intent(MainActivity.this, WatchActivity.class);
                i.putExtra("url", url); i.putExtra("title", title); i.putExtra("provider", provider);
                startActivity(i);
            });
        }

        /**
         * Lê a página "onde ver" do TMDB e devolve os links diretos de cada serviço
         * (fornecidos pela JustWatch) como JSON para window.__luminLinks(id, json).
         */
        @JavascriptInterface
        public void resolveLinks(final String tmdbLink, final String cbId) {
            new Thread(() -> {
                String json = "[]";
                try { json = WatchLinks.resolve(tmdbLink); } catch (Exception ignored) {}
                final String js = "window.__luminLinks && window.__luminLinks(" + org.json.JSONObject.quote(cbId) + "," + json + ")";
                runOnUiThread(() -> web.evaluateJavascript(js, null));
            }).start();
        }
    }

    /** Links (ex.: página do filme no Internet Archive) abrem no browser do sistema. */
    private boolean openExternal(Uri uri) {
        String host = uri.getHost();
        if (BASE_HOST.equals(host)) return false;
        try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) {}
        return true;
    }

    private void exitFull() {
        if (fullView == null) return;
        root.removeView(fullView);
        fullView = null;
        if (fullCallback != null) fullCallback.onCustomViewHidden();
        fullCallback = null;
        web.setVisibility(View.VISIBLE);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }

    private void hideBars() {
        if (Build.VERSION.SDK_INT >= 19) {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    /** luminfilmes://open?key=movie:123  ou  luminfilmes://play?src=...&title=... (enviado pelo comando) */
    private void deepLink(Intent i) {
        if (i == null || i.getData() == null || !"luminfilmes".equals(i.getData().getScheme())) return;
        Uri u = i.getData();
        try {
            org.json.JSONObject cmd = new org.json.JSONObject();
            if ("play".equals(u.getHost())) { cmd.put("t", "play"); cmd.put("src", u.getQueryParameter("src")); cmd.put("title", u.getQueryParameter("title")); }
            else { cmd.put("t", "open"); cmd.put("key", u.getQueryParameter("key")); }
            final String js = "setTimeout(function(){window.luminRemote&&window.luminRemote(" + cmd + ")},1500)";
            web.postDelayed(() -> runJs(js), 300);
        } catch (Exception ignored) {}
    }

    @Override
    protected void onNewIntent(Intent i) { super.onNewIntent(i); deepLink(i); }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideBars();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // Botões de play/pause do comando da TV
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            String a = null;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: case KeyEvent.KEYCODE_MEDIA_PLAY: case KeyEvent.KEYCODE_MEDIA_PAUSE: a = "playpause"; break;
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: a = "ffw"; break;
                case KeyEvent.KEYCODE_MEDIA_REWIND: a = "rew"; break;
            }
            if (a != null) { web.evaluateJavascript("window.luminRemote && window.luminRemote({t:'media',a:'" + a + "'})", null); return true; }
        }
        if (e.getKeyCode() == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || e.getKeyCode() == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || e.getKeyCode() == KeyEvent.KEYCODE_MEDIA_REWIND) return true;
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void onBackPressed() {
        if (fullView != null) { exitFull(); return; }
        // Pergunta à página se tem algo para fechar (player, detalhe, pesquisa)
        web.evaluateJavascript("window.luminBack ? window.luminBack() : false", value -> {
            if (!"true".equals(value)) MainActivity.super.onBackPressed();
        });
    }

    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); web.saveState(out); }
    @Override protected void onPause() { super.onPause(); web.onPause(); }
    @Override protected void onResume() { super.onResume(); web.onResume(); hideBars(); RemoteHub.top = new java.lang.ref.WeakReference<>(this); }
    @Override protected void onDestroy() { if (RemoteHub.main == this) RemoteHub.main = null; if (web != null) web.destroy(); super.onDestroy(); }
}
