package br.sintonia.tv;

import android.app.Activity;
import android.content.Intent;
import android.webkit.JavascriptInterface;
import br.sintonia.tv.playback.PlaybackRequest;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import br.sintonia.tv.addons.AddonManager;
import br.sintonia.tv.addons.KodiAddonInstaller;
import java.io.File;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private WebView web;
    private AddonManager addons;
    private static final String KODI_ACTIVITY = "org.xbmc.kodi.Splash";

    /** Ponte restrita: só entrega URIs validadas ao runtime Kodi embutido neste mesmo APK (intent explícito, sem rede). */
    private volatile boolean kodiStarted;
    private static final java.util.regex.Pattern PLUGIN_QUERY = java.util.regex.Pattern.compile("^[A-Za-z0-9=&_.%-]{0,512}$");
    private File kodiAddonsDir() { File b = getExternalFilesDir(null); return b == null ? null : new File(b, ".kodi/addons"); }

    private final class RuivoNative {
        /** "ok" | "restart" (Kodi já iniciou neste processo; reabrir o app para ele enxergar o addon) | "error:motivo". */
        @JavascriptInterface public String installDemoAddon() {
            try {
                File dir = kodiAddonsDir();
                if (dir == null) return "error:armazenamento indisponível";
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                try (InputStream in = getAssets().open("kodi-addons/plugin.video.ruivo.demo.zip")) {
                    byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }
                KodiAddonInstaller.Result r = KodiAddonInstaller.install(dir, out.toByteArray(), true, false);
                if (!r.ok) return "error:" + r.message;
                return kodiStarted ? "restart" : "ok";
            } catch (Exception e) { return "error:" + e.getClass().getSimpleName(); }
        }
        /** Pede ao Kodi para executar plugin://id/?query (o addon roda no runtime Kodi e resolve o vídeo). */
        @JavascriptInterface public boolean playAddon(String addonId, String query) {
            try {
                File dir = kodiAddonsDir();
                if (!available() || dir == null || !KodiAddonInstaller.isInstalled(dir, addonId)) return false;
                if (query == null || !PLUGIN_QUERY.matcher(query).matches()) return false;
                final Intent i = new Intent(Intent.ACTION_VIEW);
                i.setClassName(MainActivity.this, KODI_ACTIVITY);
                i.setData(Uri.parse("plugin://" + addonId + "/?" + query));
                kodiStarted = true;
                runOnUiThread(() -> startActivity(i));
                return true;
            } catch (Exception e) { return false; }
        }
        /** Últimas linhas do log do próprio Kodi (arquivo privado do app), para diagnóstico na tela. */
        @JavascriptInterface public String kodiLogTail() {
            try {
                File b = getExternalFilesDir(null);
                if (b == null) return "(armazenamento indisponível)";
                File f = new File(b, ".kodi/temp/kodi.log");
                if (!f.isFile()) f = new File(b, ".kodi/temp/kodi.old.log");
                if (!f.isFile()) return "(sem log do Kodi ainda)";
                long len = f.length(), start = Math.max(0, len - 9000);
                try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "r")) {
                    r.seek(start); byte[] buf = new byte[(int) (len - start)]; r.readFully(buf);
                    String t = new String(buf, "UTF-8");
                    int nl = t.indexOf('\n'); if (start > 0 && nl >= 0) t = t.substring(nl + 1);
                    return t;
                }
            } catch (Exception e) { return "(erro ao ler o log: " + e.getClass().getSimpleName() + ")"; }
        }
        @JavascriptInterface public void restartApp() {
            runOnUiThread(() -> {
                Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
                if (launch == null) return;
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                android.app.PendingIntent pi = android.app.PendingIntent.getActivity(MainActivity.this, 0, launch,
                        android.app.PendingIntent.FLAG_CANCEL_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
                ((android.app.AlarmManager) getSystemService(ALARM_SERVICE)).set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 400, pi);
                finishAffinity();
                android.os.Process.killProcess(android.os.Process.myPid());
            });
        }
        @JavascriptInterface public boolean available() {
            try { Class.forName(KODI_ACTIVITY); return true; } catch (ClassNotFoundException e) { return false; }
        }
        @JavascriptInterface public boolean play(String url, String title) {
            try {
                if (!available()) return false;
                PlaybackRequest r = PlaybackRequest.builderAllowCleartext(java.util.UUID.randomUUID().toString(), url)
                        .title(title == null ? "" : title).build();
                final Intent i = new Intent(Intent.ACTION_VIEW);
                i.setClassName(MainActivity.this, KODI_ACTIVITY);
                i.setDataAndType(Uri.parse(r.uri.toString()), "video/*");
                kodiStarted = true;
                runOnUiThread(() -> startActivity(i));
                return true;
            } catch (Exception e) { return false; }
        }
    }

    private static String mime(String path) {
        if (path.endsWith(".html")) return "text/html";
        if (path.endsWith(".js")) return "application/javascript";
        if (path.endsWith(".json")) return "application/json";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".webp")) return "image/webp";
        return "application/octet-stream";
    }

    private static Map<String, String> cors(Map<String, String> h) {
        h.put("Access-Control-Allow-Origin", "*");
        h.put("Access-Control-Allow-Headers", "*");
        h.put("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS");
        h.put("Access-Control-Expose-Headers", "*");
        return h;
    }

    /** Serve os assets locais e mantém o proxy necessário ao player legado. */
    private WebResourceResponse intercept(WebResourceRequest r) {
        try {
            String host = r.getUrl().getHost();
            if (HOST.equals(host)) {
                String path = r.getUrl().getPath();
                if (path == null || path.equals("/")) path = "/index.html";
                if (path.contains("..") || path.contains("\\")) return null;
                InputStream in = getAssets().open(path.substring(1));
                Map<String, String> h = new HashMap<>();
                h.put("Cache-Control", "no-cache");
                WebResourceResponse res = new WebResourceResponse(mime(path), "utf-8", in);
                res.setResponseHeaders(h);
                return res;
            }
            Map<String, String> rh = r.getRequestHeaders();
            boolean xhr = rh != null && rh.containsKey("Origin") && !r.isForMainFrame();
            String u = r.getUrl().toString();
            if (!xhr || host == null || host.contains("youtube.com") || host.contains("google")) return null;
            if ("OPTIONS".equalsIgnoreCase(r.getMethod())) {
                WebResourceResponse res = new WebResourceResponse("text/plain", "utf-8", new java.io.ByteArrayInputStream(new byte[0]));
                res.setStatusCodeAndReasonPhrase(200, "OK");
                res.setResponseHeaders(cors(new HashMap<>()));
                return res;
            }
            if (!("GET".equalsIgnoreCase(r.getMethod()) || "HEAD".equalsIgnoreCase(r.getMethod()))) return null;
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            for (Map.Entry<String, String> e : rh.entrySet()) {
                String k = e.getKey();
                if (k.equalsIgnoreCase("Origin") || k.equalsIgnoreCase("Referer") || k.equalsIgnoreCase("Cookie") || k.equalsIgnoreCase("Authorization")) continue;
                c.setRequestProperty(k, e.getValue());
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) in = new java.io.ByteArrayInputStream(new byte[0]);
            String ct = c.getContentType();
            String type = ct == null ? "application/octet-stream" : ct.split(";")[0].trim();
            Map<String, String> h = cors(new HashMap<>());
            for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
                if (e.getKey() != null && !e.getValue().isEmpty() && (e.getKey().equalsIgnoreCase("Content-Range") || e.getKey().equalsIgnoreCase("Content-Length")))
                    h.put(e.getKey(), e.getValue().get(0));
            }
            WebResourceResponse res = new WebResourceResponse(type, "utf-8", in);
            String msg = c.getResponseMessage();
            res.setStatusCodeAndReasonPhrase(code, msg == null || msg.isEmpty() ? "OK" : msg);
            res.setResponseHeaders(h);
            return res;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WebView.setWebContentsDebuggingEnabled(false);
        web = new WebView(this);
        web.setBackgroundColor(0xFF0A0D13);
        setContentView(web);
        hideBars();

        addons = new AddonManager(this, web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        web.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage m) { return true; }
            @Override public void onShowCustomView(View v, CustomViewCallback cb) { setContentView(v); }
            @Override public void onHideCustomView() { setContentView(web); }
        });
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return intercept(r);
            }

            @Override public void onPageFinished(WebView v, String url) {
                super.onPageFinished(v, url);
                if (url != null && url.startsWith("https://" + HOST + "/")) {
                    v.evaluateJavascript("window.__RUIVO_ANDROID__=true;window.__ruivoNativeReady&&window.__ruivoNativeReady()", null);
                }
            }

            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri uri = r.getUrl();
                if (r.isForMainFrame() && "ruivo-addon".equals(uri.getScheme())) {
                    addons.handleUri(uri);
                    return true;
                }
                // Ações privilegiadas e navegação do app só existem no documento local.
                if (r.isForMainFrame() && !HOST.equals(uri.getHost())) return true;
                return false;
            }

        });
        web.addJavascriptInterface(new RuivoNative(), "RuivoNative");
        web.loadUrl("https://" + HOST + "/index.html");
        web.requestFocus();
    }

    private void hideBars() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) hideBars(); }

    private void js(String code) { web.evaluateJavascript(code, null); }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_CHANNEL_UP:   js("window.__tv&&__tv.chUp()"); return true;
            case KeyEvent.KEYCODE_CHANNEL_DOWN: js("window.__tv&&__tv.chDown()"); return true;
            case KeyEvent.KEYCODE_PROG_RED:     js("window.__tv&&__tv.remove()"); return true;
            case KeyEvent.KEYCODE_PROG_YELLOW:
            case KeyEvent.KEYCODE_MENU:         js("window.__tv&&__tv.fav()"); return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:  js("window.__tv&&__tv.playPause()"); return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.__tv?__tv.back():false", v -> {
            if (!"true".equals(v)) finish();
        });
    }

    @Override protected void onPause() { super.onPause(); web.onPause(); }
    @Override protected void onResume() { super.onResume(); web.onResume(); }
    @Override protected void onDestroy() {
        if (addons != null) addons.close();
        web.destroy();
        super.onDestroy();
    }
}
