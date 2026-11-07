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

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        String url = getIntent().getStringExtra("url");
        String title = getIntent().getStringExtra("title");
        String provider = getIntent().getStringExtra("provider");
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

        Button reload = pill("↻", false);
        reload.setOnClickListener(v -> web.reload());
        bar.addView(reload);

        col.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        col.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        web = new WebView(this);
        web.setBackgroundColor(bg);
        col.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(col);
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
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return handle(r.getUrl()); }
            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String u) { return handle(Uri.parse(u)); }
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
        if (fullView != null) { exitFull(); return; }
        if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); if (web != null) web.saveState(out); }
    @Override protected void onPause() { super.onPause(); if (web != null) web.onPause(); }
    @Override protected void onResume() { super.onResume(); if (web != null) web.onResume(); }
    @Override protected void onDestroy() { if (web != null) { web.stopLoading(); web.destroy(); } super.onDestroy(); }
}
