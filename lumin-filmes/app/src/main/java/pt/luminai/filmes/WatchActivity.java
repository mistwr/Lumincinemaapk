package pt.luminai.filmes;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * Ecrã "Ver" dentro da LUMIN: abre a página oficial do serviço grátis
 * (Rakuten TV, Plex, RTP Play, Pluto TV...) sem sair da app.
 */
public class WatchActivity extends Activity {

    private WebView web;
    private FrameLayout root;
    private LinearLayout bar;
    private View fullView;
    private WebChromeClient.CustomViewCallback fullCb;
    private ProgressBar progress;
    private TextView cover;          // "A abrir RTP Play…" enquanto salta da página do TMDB
    private String provider;
    private boolean autoDone = false;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        String url = getIntent().getStringExtra("url");
        String title = getIntent().getStringExtra("title");
        provider = getIntent().getStringExtra("provider");
        if (url == null) { finish(); return; }

        int bg = Color.parseColor("#050505");
        root = new FrameLayout(this);
        root.setBackgroundColor(bg);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        // Barra LUMIN
        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.parseColor("#0e0d0b"));
        int p = dp(8);
        bar.setPadding(p, p, p, p);

        Button back = pill("← LUMIN", true);
        back.setOnClickListener(v -> finish());
        bar.addView(back);

        TextView t = new TextView(this);
        t.setText((title != null ? title : "") + (provider != null ? "  ·  " + provider : ""));
        t.setTextColor(Color.parseColor("#f4efe6"));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setPadding(dp(12), 0, dp(8), 0);
        bar.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Transmitir o ecrã para a TV (Chromecast / Smart View / Miracast): o leitor do serviço continua a ser o deles
        Button castScr = pill("📡 TV", false);
        castScr.setOnClickListener(v -> mirror());
        bar.addView(castScr);

        Button reload = pill("↻", false);
        reload.setOnClickListener(v -> web.reload());
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.leftMargin = dp(6);
        bar.addView(reload, rlp);

        col.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        col.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        web = new WebView(this);
        web.setBackgroundColor(bg);
        col.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(col);
        cover = new TextView(this);
        cover.setBackgroundColor(bg);
        cover.setTextColor(Color.parseColor("#ffdda0"));
        cover.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        cover.setTypeface(Typeface.DEFAULT_BOLD);
        cover.setGravity(Gravity.CENTER);
        cover.setText("A abrir " + (provider != null ? provider : "o serviço") + "…");
        cover.setVisibility(url.contains("themoviedb.org") ? View.VISIBLE : View.GONE);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        clp.topMargin = dp(56);
        root.addView(cover, clp);
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        // Muitos serviços (RTP Play, JustWatch TV...) recusam ou limitam o WebView ("; wv").
        // Usamos o user-agent do Chrome normal do próprio aparelho.
        String ua = s.getUserAgentString();
        ua = ua.replace("; wv)", ")").replaceAll("Version/\\d+\\.\\d+ ", "");
        s.setUserAgentString(ua);
        Diag.log("Ver: UA=" + ua);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface public void playing() { runOnUiThread(() -> cinema(true)); }
            @android.webkit.JavascriptInterface public void paused() { runOnUiThread(() -> { if (fullView == null) bar.setVisibility(View.VISIBLE); }); }
        }, "LuminWatch");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return handle(r.getUrl()); }
            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String u) { return handle(Uri.parse(u)); }
            @Override
            public void onReceivedError(WebView v, android.webkit.WebResourceRequest r, android.webkit.WebResourceError e) {
                if (r.isForMainFrame()) Diag.log("Ver: erro " + e.getErrorCode() + " " + e.getDescription() + " em " + r.getUrl());
            }
            @Override
            public void onReceivedHttpError(WebView v, android.webkit.WebResourceRequest r, android.webkit.WebResourceResponse resp) {
                if (r.isForMainFrame()) Diag.log("Ver: HTTP " + resp.getStatusCode() + " em " + r.getUrl());
            }
            @Override
            public void onPageFinished(WebView v, String u) {
                Diag.log("Ver: abriu " + u);
                Uri uri = Uri.parse(u);
                String host = uri.getHost() != null ? uri.getHost() : "";
                if (host.endsWith("themoviedb.org") && !autoDone) {
                    autoDone = true;
                    v.evaluateJavascript(autoPickJs(provider), r -> {
                        Diag.log("Ver: salto automático (" + provider + ") = " + r);
                        if (r == null || !r.contains("ok")) cover.setVisibility(View.GONE); // não encontrou: mostra a página
                    });
                } else if (!host.endsWith("themoviedb.org")) {
                    if (!host.endsWith("click.justwatch.com")) cover.setVisibility(View.GONE);
                    v.evaluateJavascript(CINEMA_JS, null);
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView v, int n) {
                progress.setProgress(n);
                progress.setVisibility(n >= 100 ? View.GONE : View.VISIBLE);
            }
            // Necessário para vídeo protegido (Widevine) usado pela Rakuten, Pluto, etc.
            @Override public void onPermissionRequest(PermissionRequest req) {
                for (String r : req.getResources()) {
                    if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(r)) {
                        req.grant(new String[]{PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID});
                        return;
                    }
                }
                req.deny();
            }
            @Override public void onShowCustomView(View view, CustomViewCallback cb) {
                if (fullView != null) { cb.onCustomViewHidden(); return; }
                fullView = view; fullCb = cb;
                root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                immersive(true);
            }
            @Override public void onHideCustomView() { exitFull(); }
        });

        if (state != null) web.restoreState(state); else web.loadUrl(url);
    }

    /**
     * Na página "onde ver" do TMDB, escolhe o link oficial (JustWatch) do serviço pedido,
     * preferindo grátis/anúncios, e salta logo para ele.
     */
    private static String autoPickJs(String provider) {
        String p = org.json.JSONObject.quote(provider != null ? provider : "");
        return "(function(P){function n(s){return String(s||'').toLowerCase().replace(/[^a-z0-9]/g,'').replace(/(tv|play|plus|beta)+$/,'')}"
            + "var want=n(P),best=null,rank=99,any=null,anyRank=99,order=['free','ads','flatrate','rent','buy'];"
            + "document.querySelectorAll('a[href*=\"click.justwatch.com\"]').forEach(function(a){try{"
            + "var cx=new URL(a.href).searchParams.get('cx').replace(/-/g,'+').replace(/_/g,'/');while(cx.length%4)cx+='=';"
            + "var d=JSON.parse(atob(cx)).data[0].data;var m=n(d.provider);var r=order.indexOf(d.monetizationType);if(r<0)r=50;"
            + "if(r<anyRank&&r<2){any=a.href;anyRank=r}"
            + "if(want&&(m==want||m.indexOf(want)>=0||want.indexOf(m)>=0)&&r<rank){best=a.href;rank=r}"
            + "}catch(e){}});var go=best||any;if(go){location.href=go;return 'ok'}return 'none'})(" + p + ")";
    }

    /**
     * "Modo cinema": quando o vídeo do serviço começa a tocar, o leitor DELES passa para
     * ecrã inteiro (com os anúncios deles intactos) e a barra LUMIN esconde-se.
     */
    private static final String CINEMA_JS =
        "(function(){if(window.__luminCinema)return;window.__luminCinema=1;" +
        "function box(v){var e=v,p;for(var i=0;i<6&&(p=e.parentElement);i++){var c=(p.className||'')+' '+(p.id||'');" +
        "if(/player|video|vjs|jw|shaka|bitmovin|theo/i.test(c)&&p.offsetWidth>=v.offsetWidth)e=p;}return e;}" +
        "function hook(v){if(v.__lumin)return;v.__lumin=1;" +
        "v.addEventListener('playing',function(){try{LuminWatch.playing()}catch(e){}" +
        "var b=box(v);if(!document.fullscreenElement&&b.requestFullscreen){b.requestFullscreen().catch(function(){})}});" +
        "v.addEventListener('pause',function(){try{LuminWatch.paused()}catch(e){}});}" +
        "function scan(){document.querySelectorAll('video').forEach(hook);}" +
        "scan();new MutationObserver(scan).observe(document.documentElement,{childList:true,subtree:true});})()";

    private void cinema(boolean on) {
        if (on) {
            bar.setVisibility(View.GONE);
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            immersiveOnly();
        }
    }

    private void immersiveOnly() {
        if (Build.VERSION.SDK_INT < 19) return;
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    /** http(s) fica dentro da LUMIN; esquemas de apps (intent://, market://) abrem a app oficial se existir. */
    private boolean handle(Uri u) {
        String sch = u.getScheme();
        if ("http".equals(sch) || "https".equals(sch)) return false;
        try {
            Intent i = "intent".equals(sch) ? Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME) : new Intent(Intent.ACTION_VIEW, u);
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            i.setComponent(null);
            startActivity(i);
        } catch (Exception e) {
            // Sem app instalada: tenta o link web de fallback, se houver
            try {
                Intent i = Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME);
                String fb = i.getStringExtra("browser_fallback_url");
                if (fb != null) web.loadUrl(fb);
            } catch (Exception ignored) {}
        }
        return true;
    }

    /** Abre o espelhamento de ecrã do sistema (Transmitir / Smart View). */
    private void mirror() {
        String[] actions = {"android.settings.CAST_SETTINGS", "android.settings.WIFI_DISPLAY_SETTINGS"};
        for (String a : actions) {
            try { startActivity(new Intent(a)); Diag.log("Ver: espelhar ecrã via " + a); return; } catch (Exception ignored) {}
        }
        android.widget.Toast.makeText(this, "Abre o painel rápido do telemóvel e toca em \"Smart View\" ou \"Transmitir\"", android.widget.Toast.LENGTH_LONG).show();
    }

    private void exitFull() {
        if (fullView == null) return;
        root.removeView(fullView);
        fullView = null;
        if (fullCb != null) fullCb.onCustomViewHidden();
        fullCb = null;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        immersive(false);
    }

    private void immersive(boolean on) {
        if (Build.VERSION.SDK_INT < 19) return;
        getWindow().getDecorView().setSystemUiVisibility(on
            ? View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
              | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            : View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        bar.setVisibility(on ? View.GONE : View.VISIBLE);
    }

    private Button pill(String text, boolean gold) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(gold ? Color.parseColor("#1a1305") : Color.parseColor("#f4efe6"));
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setCornerRadius(dp(18));
        d.setColor(gold ? Color.parseColor("#e7b95f") : Color.parseColor("#1c1a15"));
        b.setBackground(d);
        b.setMinHeight(dp(36)); b.setMinimumHeight(dp(36));
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setFocusable(true);
        return b;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override
    public void onBackPressed() {
        if (fullView != null) { exitFull(); bar.setVisibility(View.VISIBLE); return; }
        if (bar.getVisibility() != View.VISIBLE) { bar.setVisibility(View.VISIBLE); setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED); immersive(false); return; }
        if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); if (web != null) web.saveState(out); }
    @Override protected void onPause() { super.onPause(); if (web != null) web.onPause(); }
    @Override protected void onResume() { super.onResume(); if (web != null) web.onResume(); RemoteHub.top = new java.lang.ref.WeakReference<>(this); }
    @Override protected void onDestroy() { if (web != null) { web.stopLoading(); web.destroy(); } super.onDestroy(); }
}
