package fr.zap.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
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
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Lecteur plein écran (ExoPlayer).
 * Direct : ↑ ↓ ou CH+ CH- pour zapper, chiffres pour taper un numéro, OK pour pause.
 * Films et séries : commandes standard, épisode suivant enchaîné automatiquement.
 * Au retour, renvoie la position à la page pour « Reprendre la lecture ».
 */
public class PlayerActivity extends Activity {

    public static final String EXTRA_DATA = "data";
    public static final String EXTRA_RESULT = "result";

    private static final int RED = Color.rgb(0xFF, 0x4A, 0x3D);

    private ExoPlayer player;
    private PlayerView view;
    private TextView osd, status;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private JSONArray items = new JSONArray();
    private boolean live, ended, triedHls;
    private String key = "";
    private int current;
    private String digits = "";

    private final Runnable hideOsd = () -> osd.setVisibility(View.GONE);
    private final Runnable applyDigits = this::jumpToDigits;

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

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        view = new PlayerView(this);
        view.setKeepContentOnPlayerReset(true);
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS);
        view.setUseController(!live);
        view.setControllerShowTimeoutMs(4000);
        root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        osd = new TextView(this);
        osd.setTextColor(Color.WHITE);
        osd.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        osd.setTypeface(Typeface.DEFAULT_BOLD);
        osd.setGravity(Gravity.END);
        osd.setShadowLayer(16f, 0f, 2f, 0xCC000000);
        osd.setVisibility(View.GONE);
        FrameLayout.LayoutParams lo = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        lo.setMargins(0, dp(28), dp(40), 0);
        root.addView(osd, lo);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(24), dp(16), dp(24), dp(16));
        status.setBackgroundColor(0xCC121015);
        status.setVisibility(View.GONE);
        root.addView(status, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        setContentView(root);

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent("ZapTV/1.0 (Linux; Android)")
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000);
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(this).setDataSourceFactory(http))
                .build();
        view.setPlayer(player);
        player.addListener(listener);

        if (live) {
            player.setMediaItem(item(current, false));
        } else {
            List<MediaItem> list = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) list.add(item(i, false));
            player.setMediaItems(list, current, Math.max(0, resume));
        }
        player.prepare();
        player.setPlayWhenReady(true);
        view.requestFocus();
        showOsd();
    }

    private final Player.Listener listener = new Player.Listener() {
        @Override
        public void onPlayerError(PlaybackException error) {
            int code = error.errorCode;
            if (code == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                player.seekToDefaultPosition();
                player.prepare();
                return;
            }
            // Flux direct sans extension qui est en fait du HLS : on réessaie en HLS.
            if (!triedHls && (code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
                    || code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)) {
                triedHls = true;
                int idx = live ? current : player.getCurrentMediaItemIndex();
                if (live) {
                    player.setMediaItem(item(idx, true));
                } else {
                    player.replaceMediaItem(idx, item(idx, true));
                }
                player.prepare();
                return;
            }
            String msg;
            if (code == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS || code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                    || code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT) {
                msg = "Ce flux ne répond pas pour le moment.";
            } else if (code == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED || code == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) {
                msg = "Cette TV ne sait pas décoder ce format vidéo.";
            } else {
                msg = "Lecture impossible.";
            }
            showStatus(msg + (live ? "\n↑ ↓ pour changer de chaîne" : "\nRetour pour revenir à la fiche"));
        }

        @Override
        public void onPlaybackStateChanged(int state) {
            if (state == Player.STATE_READY) {
                status.setVisibility(View.GONE);
            } else if (state == Player.STATE_ENDED && !live) {
                ended = true;
                finishWithResult();
            }
        }

        @Override
        public void onMediaItemTransition(MediaItem mediaItem, int reason) {
            triedHls = false;
            if (!live && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                current = player.getCurrentMediaItemIndex();
                showOsd();
            }
        }
    };

    private MediaItem item(int i, boolean forceHls) {
        JSONObject o = items.optJSONObject(i);
        String url = o == null ? "" : o.optString("url", "");
        String title = o == null ? "" : o.optString("title", "");
        MediaItem.Builder b = new MediaItem.Builder()
                .setUri(url)
                .setMediaMetadata(new MediaMetadata.Builder().setTitle(title).build());
        if (forceHls || url.toLowerCase().contains(".m3u8")) b.setMimeType(MimeTypes.APPLICATION_M3U8);
        if (live) b.setLiveConfiguration(new MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(8000).build());
        return b.build();
    }

    private void zap(int delta) {
        int n = items.length();
        if (n == 0) return;
        current = ((current + delta) % n + n) % n;
        playCurrentLive();
    }

    private void playCurrentLive() {
        triedHls = false;
        status.setVisibility(View.GONE);
        player.setMediaItem(item(current, false));
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
        setOsd("", "Aucune chaîne " + wanted + " dans cette catégorie");
    }

    private void showOsd() {
        JSONObject o = items.optJSONObject(current);
        if (o == null) return;
        String n = live ? o.optString("n", "") : "";
        setOsd(n, o.optString("title", ""));
    }

    private void setOsd(String big, String small) {
        String text = big.isEmpty() ? small : big + "\n" + small;
        SpannableString ss = new SpannableString(text);
        if (!big.isEmpty()) {
            ss.setSpan(new RelativeSizeSpan(3.2f), 0, big.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (big.endsWith("_")) ss.setSpan(new ForegroundColorSpan(RED), big.length() - 1, big.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        osd.setText(ss);
        osd.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideOsd);
        handler.postDelayed(hideOsd, 2800);
    }

    private void showStatus(String msg) {
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
                    if (player.isPlaying()) player.pause(); else player.play();
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
                        setOsd(digits + "_", "");
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
            r.put("pos", Math.max(0, player.getCurrentPosition()));
            r.put("dur", dur == C.TIME_UNSET ? 0 : dur);
            r.put("ended", ended);
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_RESULT, r.toString()));
        } catch (Exception ignored) {
            setResult(RESULT_CANCELED);
        }
        finish();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
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
