package fr.zap.tv;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.net.URLConnection;
import java.util.HashMap;
import java.util.Map;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Interface Zap (assets/index.html) en plein écran.
 * Tout le réseau de la page passe par Net (DNS sécurisé + cache des images),
 * les vidéos partent dans PlayerActivity (ExoPlayer), et le VPN est piloté par ZapVpn.
 */
public class MainActivity extends Activity {

    private static final int REQ_PLAY = 42, REQ_VPN = 43, REQ_FILE = 44;
    private WebView web;
    private String pendingVpn = "warp";
    private ValueCallback<Uri[]> fileCb;
    private boolean lowRam;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        lowRam = am != null && (am.isLowRamDevice() || am.getMemoryClass() < 160);
        Net.base(this);

        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(0x0E, 0x0C, 0x15));
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);           // favoris, coups de cœur, reprise, sources
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setUserAgentString(s.getUserAgentString() + " ZapTV/1.2");
        // Pré-rendu hors écran : défilement plus fluide, sauf sur les TV à petite mémoire
        if (Build.VERSION.SDK_INT >= 23 && !lowRam) s.setOffscreenPreRaster(true);

        web.addJavascriptInterface(new Bridge(), "ZapNative");
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try {
                    startActivityForResult(p.createIntent(), REQ_FILE);
                    return true;
                } catch (Exception e) {
                    fileCb = null;
                    return false;
                }
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("file".equals(u.getScheme())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) {
                    // Pas d'application pour ce lien sur la TV
                }
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                return intercept(req);
            }
        });

        ZapVpn vpn = ZapVpn.get(this);
        vpn.setListener(json -> runOnUiThread(() -> js("window.zapVpn&&zapVpn(" + JSONObject.quote(json) + ")")));
        if (vpn.wantsAuto()) {
            try {
                if (VpnService.prepare(this) == null) vpn.connect(vpn.savedMode());
            } catch (Exception ignored) {
            }
        }

        if (state != null) web.restoreState(state);
        else web.loadUrl("file:///android_asset/index.html");
        web.requestFocus(View.FOCUS_DOWN);
    }

    /** Fait passer les requêtes de la page par OkHttp : DNS sécurisé, connexions réutilisées, cache des images. */
    private WebResourceResponse intercept(WebResourceRequest req) {
        Uri u = req.getUrl();
        String sc = u.getScheme();
        if (!"GET".equalsIgnoreCase(req.getMethod()) || !("http".equals(sc) || "https".equals(sc))) return null;
        try {
            Request.Builder rb = new Request.Builder().url(u.toString());
            for (Map.Entry<String, String> h : req.getRequestHeaders().entrySet()) {
                String k = h.getKey();
                if (k.equalsIgnoreCase("Range")) return null;
                if (k.equalsIgnoreCase("Accept-Encoding") || k.equalsIgnoreCase("Origin") || k.equalsIgnoreCase("Referer")) continue;
                rb.header(k, h.getValue());
            }
            Response r = Net.web(this).newCall(rb.build()).execute();
            ResponseBody body = r.body();
            int code = r.code();
            if (body == null || (code >= 300 && code < 400)) {
                r.close();
                return null;
            }
            MediaType mt = body.contentType();
            String mime = mt != null ? mt.type() + "/" + mt.subtype() : URLConnection.guessContentTypeFromName(u.getLastPathSegment() == null ? "" : u.getLastPathSegment());
            if (mime == null) mime = "application/octet-stream";
            String enc = mt != null && mt.charset() != null ? mt.charset().name() : null;
            Map<String, String> hs = new HashMap<>();
            for (String n : r.headers().names()) {
                String l = n.toLowerCase();
                if (l.equals("content-type") || l.equals("content-encoding") || l.equals("content-length") || l.equals("transfer-encoding") || l.startsWith("access-control-")) continue;
                hs.put(n, r.header(n));
            }
            hs.put("Access-Control-Allow-Origin", "*");
            String reason = r.message();
            if (reason == null || reason.trim().isEmpty()) reason = code < 400 ? "OK" : "Error";
            return new WebResourceResponse(mime, enc, code, reason, hs, body.byteStream());
        } catch (Exception e) {
            return null; // la page réessaie par le réseau normal de la WebView
        }
    }

    private void js(String code) {
        if (web != null) web.evaluateJavascript(code, null);
    }

    /** Appelé depuis la page : window.ZapNative.xxx(...) */
    private class Bridge {
        @JavascriptInterface
        public void play(final String json) {
            runOnUiThread(() -> {
                Intent i = new Intent(MainActivity.this, PlayerActivity.class);
                i.putExtra(PlayerActivity.EXTRA_DATA, json);
                startActivityForResult(i, REQ_PLAY);
                overridePendingTransition(0, 0);
            });
        }

        @JavascriptInterface
        public String version() {
            return "1.2";
        }

        @JavascriptInterface
        public boolean isLowRam() {
            return lowRam;
        }

        @JavascriptInterface
        public void warm(String url) {
            Net.warm(MainActivity.this, url);
        }

        @JavascriptInterface
        public void setSecureDns(boolean on) {
            Net.setSecureDns(on);
        }

        @JavascriptInterface
        public void clearCache() {
            Net.clearCache();
            runOnUiThread(() -> web.clearCache(true));
        }

        @JavascriptInterface
        public String vpnStatus() {
            return ZapVpn.get(MainActivity.this).status();
        }

        @JavascriptInterface
        public String vpnSaveConfig(String text) {
            return ZapVpn.get(MainActivity.this).saveCustom(text);
        }

        @JavascriptInterface
        public void vpnOff() {
            ZapVpn.get(MainActivity.this).disconnect();
        }

        @JavascriptInterface
        public void vpnOn(final String mode) {
            runOnUiThread(() -> {
                ZapVpn vpn = ZapVpn.get(MainActivity.this);
                pendingVpn = mode;
                Intent p;
                try {
                    p = VpnService.prepare(MainActivity.this);
                } catch (Exception e) {
                    vpn.fail("Cette TV ne permet pas aux applications de créer un VPN.");
                    return;
                }
                if (p == null) {
                    vpn.connect(mode);
                    return;
                }
                try {
                    startActivityForResult(p, REQ_VPN);
                } catch (ActivityNotFoundException e) {
                    vpn.fail("Cette TV ne permet pas aux applications de créer un VPN.");
                }
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) ZapVpn.get(this).connect(pendingVpn);
            else ZapVpn.get(this).fail("Vous n'avez pas autorisé le VPN.");
            return;
        }
        if (requestCode == REQ_FILE) {
            if (fileCb != null) fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCb = null;
            return;
        }
        if (requestCode != REQ_PLAY || data == null) return;
        String result = data.getStringExtra(PlayerActivity.EXTRA_RESULT);
        if (result == null) return;
        js("window.zapNativeResult&&window.zapNativeResult(" + JSONObject.quote(result) + ")");
    }

    /** Retour : la page ferme d'abord ce qui est ouvert, puis on quitte l'app. */
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        web.evaluateJavascript("(window.zapBack?window.zapBack():false)", value -> {
            if (!"true".equals(value)) finish();
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        web.resumeTimers();
    }

    @Override
    protected void onPause() {
        web.onPause();
        web.pauseTimers(); // la page se met en veille pendant la vidéo : tout le processeur va au décodage
        super.onPause();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_LOW) js("window.zapTrim&&zapTrim()");
    }

    @Override
    protected void onDestroy() {
        ZapVpn vpn = ZapVpn.get(this);
        vpn.setListener(null);
        if (isFinishing()) vpn.stopQuietly();
        web.destroy();
        super.onDestroy();
    }
}
