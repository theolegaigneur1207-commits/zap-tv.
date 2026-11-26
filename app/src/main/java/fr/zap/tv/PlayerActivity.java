package fr.zap.tv;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Lecteur plein écran (ExoPlayer), réglé pour la stabilité :
 * - réseau Net (DNS sécurisé, connexions réutilisées, VPN si activé) ;
 * - reconnexion automatique, bascule TS ⇄ HLS si un format échoue ;
 * - surveillance des blocages : si l'image reste figée, on relance le flux tout seul ;
 * - trois profils de mémoire tampon (rapide, équilibré, anti-coupure).
 * Direct : ↑ ↓ ou CH+ CH- pour zapper, chiffres pour taper un numéro, OK pour pause.
 */
public class PlayerActivity extends Activity {

    public static final String EXTRA_DATA = "data";
    public static final String EXTRA_RESULT = "result";

    private static final int VIOLET = Color.rgb(0x8B, 0x6C, 0xFF);
    private static final int RED = Color.rgb(0xFF, 0x4A, 0x3D);

    private ExoPlayer player;
    private PlayerView view;
    private TextView osd, status, title;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private JSONArray items = new JSONArray();
    private boolean live, ended, triedHls, useAlt;
    private String key = "", buf = "balanced";
    private int current, retries;
    private long lastPos;
    private String digits = "";

    private final Runnable hideOsd = () -> osd.animate().alpha(0f).setDuration(250).start();
    private final Runnable hideStatus = () -> status.setVisibility(View.GONE);
    private final Runnable applyDigits = this::jumpToDigits;
    private final Runnable stallCheck = this::onStall;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);

        long resume = 0;
        try {
            JSONObject d = new JSONObject(getIntent().getStringExtra(EXTRA_DATA));
            items = d.optJSONArray("items");
            if (items == null) items = new JSONArray();
            live = d.optBoolean("live", false);
            key = d.optString("key", "");
            buf = d.optString("buffer", "balanced");
            current = Math.max(0, Math.min(d.optInt("index", 0), Math.max(0, items.length() - 1)));
            resume = d.optLong("resume", 0);
        } catch (Exception e) {
            finish();
            return;
        }
        if (items.length() == 0) {
            finish();
            return;
        }

        setContentView(R.layout.activity_player);
        FrameLayout root = findViewById(R.id.root);
        view = findViewById(R.id.player_view);
        view.setKeepContentOnPlayerReset(true);
        view.setUseController(!live);
        view.setControllerShowTimeoutMs(3500);

        title = new TextView(this);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setShadowLayer(14f, 0f, 2f, 0xCC000000);
        title.setPadding(dp(32), dp(24), dp(32), dp(48));
        title.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xB3000000, 0x00000000}));
        title.setAlpha(0f);
        root.addView(title, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        if (!live) {
            view.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) visibility ->
                    title.animate().alpha(visibility == View.VISIBLE ? 1f : 0f).setDuration(220).start());
        }

        osd = new TextView(this);
        osd.setTextColor(Color.WHITE);
        osd.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        osd.setGravity(Gravity.END);
        osd.setShadowLayer(18f, 0f, 2f, 0xCC000000);
        osd.setAlpha(0f);
        FrameLayout.LayoutParams lo = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        lo.setMargins(dp(40), dp(28), dp(40), 0);
        root.addView(osd, lo);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(28), dp(18), dp(28), dp(18));
        GradientDrawable box = new GradientDrawable();
        box.setColor(0xE6181523);
        box.setCornerRadius(dp(18));
        status.setBackground(box);
        status.setVisibility(View.GONE);
        root.addView(status, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        boolean lowRam = am != null && (am.isLowRamDevice() || am.getMemoryClass() < 160);

        // Mémoire tampon : min, max, avant de démarrer, après une coupure (en millisecondes)
        int[] b;
        switch (buf) {
            case "fast":
                b = live ? new int[]{6000, 25000, 700, 1800} : new int[]{12000, 40000, 1000, 2000};
                break;
            case "stable":
                b = live ? new int[]{25000, 60000, 2500, 6000} : new int[]{30000, 90000, 2500, 5000};
                break;
            default:
                b = live ? new int[]{12000, 40000, 1200, 3000} : new int[]{20000, 60000, 1500, 3000};
        }
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(b[0], b[1], b[2], b[3])
                .setTargetBufferBytes((lowRam ? 32 : 96) * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build();
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this).setEnableDecoderFallback(true);
        OkHttpDataSource.Factory http = new OkHttpDataSource.Factory(Net.player(this))
                .setUserAgent("ZapTV/1.2 (Linux; Android)");
        DefaultMediaSourceFactory sources = new DefaultMediaSourceFactory(this)
                .setDataSourceFactory(http)
                .setLoadErrorHandlingPolicy(new DefaultLoadErrorHandlingPolicy(live ? 8 : 5));
        player = new ExoPlayer.Builder(this, renderers)
                .setLoadControl(loadControl)
                .setMediaSourceFactory(sources)
                .build();
        view.setPlayer(player);
        player.addListener(listener);

        if (live) {
            player.setMediaItem(item(current));
        } else {
            List<MediaItem> list = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) list.add(item(i));
            player.setMediaItems(list, current, Math.max(0, resume));
        }
        player.prepare();
        player.setPlayWhenReady(true);
        view.requestFocus();
        updateTitle();
        showOsd();
    }

    private final Player.Listener listener = new Player.Listener() {
        @Override
        public void onPlayerError(PlaybackException error) {
            int code = error.errorCode;
            handler.removeCallbacks(stallCheck);
            if (code == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                player.seekToDefaultPosition();
                player.prepare();
                return;
            }
            boolean parsing = code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
                    || code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                    || code == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED;
            boolean network = code >= 2000 && code < 3000;
            int idx = live ? current : player.getCurrentMediaItemIndex();

            // 1. Format mal reconnu : on essaie l'autre format (TS ⇄ HLS)
            if (parsing && !useAlt && hasAlt(idx)) {
                useAlt = true;
                restart(idx, 0);
                return;
            }
            if (parsing && !triedHls) {
                triedHls = true;
                restart(idx, 0);
                return;
            }
            // 2. Coupure réseau : on se reconnecte tout seul, de plus en plus patiemment
            if ((network || parsing) && retries < (live ? 5 : 4)) {
                retries++;
                soft("Reconnexion… (" + retries + ")");
                final long pos = live ? 0 : Math.max(lastPos, player.getCurrentPosition());
                handler.postDelayed(() -> restart(idx, pos), 700L * retries);
                return;
            }
            // 3. Le format habituel ne marche plus : dernier essai avec l'autre format
            if (!useAlt && hasAlt(idx)) {
                useAlt = true;
                retries = 0;
                soft("Essai d'un autre format…");
                restart(idx, live ? 0 : Math.max(lastPos, player.getCurrentPosition()));
                return;
            }
            String msg;
            if (network) msg = "Ce flux ne répond pas pour le moment.";
            else if (code == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED || code == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)
                msg = "Cette TV ne sait pas décoder ce format vidéo.";
            else msg = "Lecture impossible.";
            showStatus(msg + (live ? "\n↑ ↓ pour changer de chaîne" : "\nRetour pour revenir à la fiche"));
        }

        @Override
        public void onPlaybackStateChanged(int state) {
            handler.removeCallbacks(stallCheck);
            if (state == Player.STATE_READY) {
                handler.removeCallbacks(hideStatus);
                status.setVisibility(View.GONE);
            } else if (state == Player.STATE_BUFFERING) {
                handler.postDelayed(stallCheck, live ? 9000 : 15000);
            } else if (state == Player.STATE_ENDED && !live) {
                ended = true;
                finishWithResult();
            }
        }

        @Override
        public void onIsPlayingChanged(boolean playing) {
            if (playing) handler.postDelayed(() -> {
                if (player != null && player.isPlaying()) retries = 0;
            }, 8000);
        }

        @Override
        public void onMediaItemTransition(MediaItem mediaItem, int reason) {
            triedHls = false;
            if (!live) {
                current = player.getCurrentMediaItemIndex();
                updateTitle();
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) showOsd();
            }
        }
    };

    /** L'image est bloquée depuis trop longtemps : on relance le flux au même endroit (ou au direct). */
    private void onStall() {
        if (player == null || !player.getPlayWhenReady() || player.getPlaybackState() != Player.STATE_BUFFERING) return;
        if (retries >= 6) {
            showStatus("Le flux est très lent en ce moment.\nEssayez le profil « Anti-coupure » ou le VPN dans Réglages.");
            return;
        }
        retries++;
        soft("Relance du flux…");
        int idx = live ? current : player.getCurrentMediaItemIndex();
        restart(idx, live ? 0 : player.getCurrentPosition());
    }

    private void restart(int idx, long pos) {
        if (player == null) return;
        if (live) {
            player.setMediaItem(item(idx));
            player.prepare();
            player.play();
        } else {
            player.replaceMediaItem(idx, item(idx));
            player.seekTo(idx, pos);
            player.prepare();
            player.play();
        }
    }

    private boolean hasAlt(int i) {
        JSONObject o = items.optJSONObject(i);
        return o != null && !o.optString("alt", "").isEmpty();
    }

    private MediaItem item(int i) {
        JSONObject o = items.optJSONObject(i);
        String url = o == null ? "" : o.optString("url", "");
        String alt = o == null ? "" : o.optString("alt", "");
        if (useAlt && !alt.isEmpty()) url = alt;
        String t = o == null ? "" : o.optString("title", "");
        String low = url.toLowerCase();
        MediaItem.Builder b = new MediaItem.Builder()
                .setUri(url)
                .setMediaMetadata(new MediaMetadata.Builder().setTitle(t).build());
        if (triedHls || low.contains(".m3u8")) b.setMimeType(MimeTypes.APPLICATION_M3U8);
        else if (low.matches(".*\\.ts(\\?.*)?$")) b.setMimeType(MimeTypes.VIDEO_MP2T);
        if (live) {
            long off = "fast".equals(buf) ? 5000 : "stable".equals(buf) ? 15000 : 9000;
            b.setLiveConfiguration(new MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(off).build());
        }
        return b.build();
    }

    private void updateTitle() {
        JSONObject o = items.optJSONObject(current);
        if (o == null || live) return;
        String t = o.optString("title", ""), sub = o.optString("sub", "");
        SpannableStringBuilder sb = new SpannableStringBuilder(t);
        sb.setSpan(new StyleSpan(Typeface.BOLD), 0, t.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (!sub.isEmpty()) {
            int s = sb.length() + 1;
            sb.append("\n").append(sub);
            sb.setSpan(new RelativeSizeSpan(0.75f), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(0xFFCFC8D6), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        title.setText(sb);
    }

    private void zap(int delta) {
        int n = items.length();
        if (n == 0) return;
        current = ((current + delta) % n + n) % n;
        playCurrentLive();
    }

    private void playCurrentLive() {
        triedHls = false;
        useAlt = false;
        retries = 0;
        handler.removeCallbacks(stallCheck);
        status.setVisibility(View.GONE);
        player.stop(); // libère tout de suite l'ancienne connexion (abonnements limités à 1 écran)
        player.setMediaItem(item(current));
        player.prepare();
        player.play();
        showOsd();
    }

    private void jumpToDigits() {
        String wanted = digits;
        digits = "";
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o != null && wanted.equals(o.optString("n"))) {
                current = i;
                playCurrentLive();
                return;
            }
        }
        setOsd("", "Aucune chaîne " + wanted + " dans cette catégorie", "");
    }

    private void showOsd() {
        JSONObject o = items.optJSONObject(current);
        if (o == null) return;
        if (live) setOsd(o.optString("n", ""), o.optString("title", ""), o.optString("now", ""));
        else setOsd("", o.optString("title", ""), o.optString("sub", ""));
    }

    private void setOsd(String big, String name, String small) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (!big.isEmpty()) {
            sb.append(big);
            sb.setSpan(new RelativeSizeSpan(3.4f), 0, big.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new StyleSpan(Typeface.BOLD), 0, big.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (big.endsWith("_")) sb.setSpan(new ForegroundColorSpan(VIOLET), big.length() - 1, big.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (!name.isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            int s = sb.length();
            sb.append(name);
            sb.setSpan(new StyleSpan(Typeface.BOLD), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (!small.isEmpty()) {
            sb.append("\n");
            int s = sb.length();
            sb.append(live ? "● " + small : small);
            sb.setSpan(new RelativeSizeSpan(0.8f), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (live) sb.setSpan(new ForegroundColorSpan(RED), s, s + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        osd.setText(sb);
        osd.animate().cancel();
        osd.setTranslationY(-dp(8));
        osd.animate().alpha(1f).translationY(0f).setDuration(220).start();
        handler.removeCallbacks(hideOsd);
        handler.postDelayed(hideOsd, 3000);
    }

    /** Petit message qui disparaît seul (reconnexion en cours). */
    private void soft(String msg) {
        status.setText(msg);
        status.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideStatus);
        handler.postDelayed(hideStatus, 4000);
    }

    private void showStatus(String msg) {
        handler.removeCallbacks(hideStatus);
        status.setText(msg);
        status.setVisibility(View.VISIBLE);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (live && event.getAction() == KeyEvent.ACTION_DOWN) {
            int k = event.getKeyCode();
            switch (k) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_CHANNEL_DOWN:
                case KeyEvent.KEYCODE_PAGE_UP:
                    zap(-1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_CHANNEL_UP:
                case KeyEvent.KEYCODE_PAGE_DOWN:
                    zap(1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_SPACE:
                    if (player.isPlaying()) player.pause();
                    else {
                        // reprise du direct : on revient au présent
                        player.seekToDefaultPosition();
                        player.play();
                    }
                    showOsd();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_INFO:
                    showOsd();
                    return true;
                default:
                    if (k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9) {
                        digits = (digits + (k - KeyEvent.KEYCODE_0));
                        if (digits.length() > 4) digits = digits.substring(digits.length() - 4);
                        setOsd(digits + "_", "", "");
                        handler.removeCallbacks(applyDigits);
                        handler.postDelayed(applyDigits, 1200);
                        return true;
                    }
            }
        }
        if (live && event.getAction() == KeyEvent.ACTION_UP) {
            int k = event.getKeyCode();
            if (k != KeyEvent.KEYCODE_BACK && k != KeyEvent.KEYCODE_ESCAPE) return true;
        }
        if (!live && view != null && view.dispatchKeyEvent(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (!live && view != null && view.isControllerFullyVisible()) {
            view.hideController();
            return;
        }
        finishWithResult();
    }

    private void finishWithResult() {
        try {
            JSONObject r = new JSONObject();
            r.put("key", key);
            r.put("live", live);
            r.put("index", live ? current : player.getCurrentMediaItemIndex());
            long dur = player.getDuration();
            r.put("pos", Math.max(0, Math.max(lastPos, player.getCurrentPosition())));
            r.put("dur", dur == C.TIME_UNSET ? 0 : dur);
            r.put("ended", ended);
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_RESULT, r.toString()));
        } catch (Exception ignored) {
            setResult(RESULT_CANCELED);
        }
        finish();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null && !live) lastPos = Math.max(lastPos, player.getCurrentPosition());
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player != null) player.pause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            player.removeListener(listener);
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
