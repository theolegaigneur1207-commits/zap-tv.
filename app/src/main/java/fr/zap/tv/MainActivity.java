package fr.zap.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

/**
 * Affiche l'interface Zap (assets/index.html) dans une WebView plein écran.
 * Les vidéos partent dans PlayerActivity (ExoPlayer), qui lit bien mieux
 * les formats IPTV (MKV, TS, AC3…) qu'un navigateur.
 */
public class MainActivity extends Activity {

    private static final int REQ_PLAY = 42;
    private WebView web;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(0x12, 0x10, 0x15));
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);           // favoris, reprise, playlists
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        // La page est servie depuis file:// : on lui permet de lire les playlists
        // et l'API Xtream de n'importe quel serveur, sans blocage CORS.
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setUserAgentString(s.getUserAgentString() + " ZapTV/1.0");

        web.addJavascriptInterface(new Bridge(), "ZapNative");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("file".equals(u.getScheme())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) {
                    // Pas de navigateur sur la TV : on ignore le lien.
                }
                return true;
            }
        });

        if (state != null) {
            web.restoreState(state);
        } else {
            web.loadUrl("file:///android_asset/index.html");
        }
        web.requestFocus(View.FOCUS_DOWN);
    }

    /** Appelé depuis la page : window.ZapNative.play(json) */
    private class Bridge {
        @JavascriptInterface
        public void play(final String json) {
            runOnUiThread(() -> {
                Intent i = new Intent(MainActivity.this, PlayerActivity.class);
                i.putExtra(PlayerActivity.EXTRA_DATA, json);
                startActivityForResult(i, REQ_PLAY);
            });
        }

        @JavascriptInterface
        public String version() {
            return "1.0";
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PLAY || data == null) return;
        String result = data.getStringExtra(PlayerActivity.EXTRA_RESULT);
        if (result == null) return;
        web.evaluateJavascript("window.zapNativeResult&&window.zapNativeResult(" + JSONObject.quote(result) + ")", null);
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
    }

    @Override
    protected void onPause() {
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }
}
