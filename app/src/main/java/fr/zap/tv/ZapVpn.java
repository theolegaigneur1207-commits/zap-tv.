package fr.zap.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.VpnService;

import com.wireguard.android.backend.GoBackend;
import com.wireguard.android.backend.Tunnel;
import com.wireguard.config.Config;
import com.wireguard.crypto.KeyPair;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * VPN intégré, sans application externe (bibliothèque officielle WireGuard).
 * - Mode « WARP » : réseau gratuit et illimité de Cloudflare, compte créé automatiquement.
 * - Mode « custom » : votre propre fichier WireGuard (par exemple Proton VPN Free).
 * Seul le trafic de Zap TV passe par le tunnel : le reste de la TV n'est pas touché.
 */
public final class ZapVpn {

    public interface Listener {
        void onState(String json);
    }

    private static final String API = "https://api.cloudflareclient.com/";
    private static final String[][] API_VERSIONS = {
            {"v0a2483", "a-6.81-2410012252.0"},
            {"v0a2158", "a-6.11-2223"},
    };
    private static final String WARP_PEER = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=";
    private static final String[] WARP_ENDPOINTS = {
            "", // remplacé par l'adresse renvoyée par Cloudflare
            "162.159.192.1:2408", "162.159.192.1:500", "162.159.192.1:1701", "162.159.192.1:4500",
    };
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private static ZapVpn inst;

    public static synchronized ZapVpn get(Context c) {
        if (inst == null) inst = new ZapVpn(c.getApplicationContext());
        return inst;
    }

    private final Context ctx;
    private final SharedPreferences sp;
    private final ExecutorService ex = Executors.newSingleThreadExecutor();
    private GoBackend backend;
    private volatile Listener listener;
    private volatile String state = "off", info = "", mode = "";

    private final Tunnel tunnel = new Tunnel() {
        @Override
        public String getName() {
            return "zap";
        }

        @Override
        public void onStateChange(Tunnel.State s) {
            if (s == Tunnel.State.DOWN && "on".equals(state)) set("off", "Le VPN s'est arrêté.");
        }
    };

    private ZapVpn(Context c) {
        ctx = c;
        sp = c.getSharedPreferences("vpn", Context.MODE_PRIVATE);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public String status() {
        try {
            return new JSONObject()
                    .put("state", state)
                    .put("info", info)
                    .put("mode", mode.isEmpty() ? sp.getString("mode", "warp") : mode)
                    .put("auto", sp.getBoolean("auto", false))
                    .put("custom", sp.getString("custom", null) != null)
                    .toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    public boolean wantsAuto() {
        return sp.getBoolean("auto", false);
    }

    public String savedMode() {
        return sp.getString("mode", "warp");
    }

    /** Vérifie et enregistre une config WireGuard collée par l'utilisateur. Renvoie "" si elle est valable. */
    public String saveCustom(String text) {
        try {
            String fixed = onlyThisApp(text);
            Config.parse(new ByteArrayInputStream(fixed.getBytes(StandardCharsets.UTF_8)));
            sp.edit().putString("custom", fixed).apply();
            return "";
        } catch (Exception e) {
            return "Ce fichier WireGuard n'est pas valable : " + e.getMessage();
        }
    }

    public void fail(String msg) {
        set("error", msg);
    }

    public void connect(final String m) {
        ex.execute(() -> {
            mode = m;
            set("connecting", "Connexion…");
            try {
                if (backend == null) backend = new GoBackend(ctx);
                if (VpnService.prepare(ctx) != null) {
                    set("error", "Autorisation VPN manquante.");
                    return;
                }
                List<String> confs = "custom".equals(m) ? customConfs() : warpConfs();
                int first = "custom".equals(m) ? 0 : Math.min(sp.getInt("warpIdx", 0), confs.size() - 1);
                for (int k = 0; k < confs.size(); k++) {
                    int i = (first + k) % confs.size();
                    set("connecting", confs.size() > 1 ? "Connexion… (essai " + (k + 1) + " sur " + confs.size() + ")" : "Connexion…");
                    Config cfg = Config.parse(new ByteArrayInputStream(confs.get(i).getBytes(StandardCharsets.UTF_8)));
                    backend.setState(tunnel, Tunnel.State.UP, cfg);
                    Net.resetConnections();
                    String ok = probe("custom".equals(m));
                    if (ok != null) {
                        sp.edit().putBoolean("auto", true).putString("mode", m).putInt("warpIdx", i).apply();
                        set("on", ok);
                        return;
                    }
                    backend.setState(tunnel, Tunnel.State.DOWN, null);
                }
                Net.resetConnections();
                set("error", "Le VPN n'a pas pu se connecter. Votre réseau bloque peut-être les connexions VPN (WireGuard).");
            } catch (Exception e) {
                downQuietly();
                set("error", e.getMessage() == null ? "Le VPN n'a pas pu démarrer." : e.getMessage());
            }
        });
    }

    public void disconnect() {
        ex.execute(() -> {
            sp.edit().putBoolean("auto", false).apply();
            downQuietly();
            set("off", "");
        });
    }

    /** À la fermeture de l'app : on coupe sans changer la préférence. */
    public void stopQuietly() {
        ex.execute(this::downQuietly);
    }

    private void downQuietly() {
        try {
            if (backend != null) backend.setState(tunnel, Tunnel.State.DOWN, null);
        } catch (Exception ignored) {
        }
        Net.resetConnections();
        if (!"error".equals(state)) state = "off";
    }

    private void set(String s, String i) {
        state = s;
        info = i == null ? "" : i;
        Listener l = listener;
        if (l != null) l.onState(status());
    }

    /** Vérifie que le tunnel laisse bien passer Internet, et dit par où. */
    private String probe(boolean custom) {
        OkHttpClient c = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build();
        for (int a = 0; a < 3; a++) {
            try {
                Thread.sleep(a == 0 ? 600 : 1500);
                try (Response r = c.newCall(new Request.Builder().url("https://www.cloudflare.com/cdn-cgi/trace").build()).execute()) {
                    if (!r.isSuccessful() || r.body() == null) continue;
                    String warp = "", colo = "", ip = "";
                    for (String line : r.body().string().split("\n")) {
                        if (line.startsWith("warp=")) warp = line.substring(5).trim();
                        else if (line.startsWith("colo=")) colo = line.substring(5).trim();
                        else if (line.startsWith("ip=")) ip = line.substring(3).trim();
                    }
                    if (custom) return "WireGuard actif" + (ip.isEmpty() ? "" : ", adresse " + ip);
                    if (warp.equals("on") || warp.equals("plus")) return "Cloudflare WARP actif" + (colo.isEmpty() ? "" : ", relais " + colo);
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private List<String> customConfs() throws IOException {
        String c = sp.getString("custom", null);
        if (c == null) throw new IOException("Aucun fichier WireGuard enregistré.");
        List<String> l = new ArrayList<>();
        l.add(c);
        return l;
    }

    private List<String> warpConfs() throws Exception {
        JSONObject a = warpAccount();
        List<String> l = new ArrayList<>();
        for (String ep : WARP_ENDPOINTS) {
            String e = ep.isEmpty() ? a.optString("host", "engage.cloudflareclient.com:2408") : ep;
            String v6 = a.optString("v6", "");
            l.add("[Interface]\n"
                    + "PrivateKey = " + a.getString("priv") + "\n"
                    + "Address = " + a.optString("v4", "172.16.0.2") + "/32" + (v6.isEmpty() ? "" : ", " + v6 + "/128") + "\n"
                    + "DNS = 1.1.1.1, 1.0.0.1\n"
                    + "MTU = 1280\n"
                    + "IncludedApplications = " + ctx.getPackageName() + "\n"
                    + "[Peer]\n"
                    + "PublicKey = " + a.optString("peer", WARP_PEER) + "\n"
                    + "AllowedIPs = 0.0.0.0/0, ::/0\n"
                    + "Endpoint = " + e + "\n"
                    + "PersistentKeepalive = 25\n");
        }
        return l;
    }

    /** Crée gratuitement un compte WARP (une seule fois, gardé ensuite). */
    private JSONObject warpAccount() throws Exception {
        String saved = sp.getString("warp", null);
        if (saved != null) return new JSONObject(saved);
        set("connecting", "Création de votre accès gratuit…");
        KeyPair kp = new KeyPair();
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        JSONObject body = new JSONObject()
                .put("key", kp.getPublicKey().toBase64())
                .put("install_id", "")
                .put("fcm_token", "")
                .put("tos", f.format(new Date()))
                .put("model", "Android TV")
                .put("type", "Android")
                .put("locale", "fr_FR");
        OkHttpClient c = Net.base(ctx);
        Exception last = null;
        for (String[] v : API_VERSIONS) {
            try {
                Request rq = new Request.Builder().url(API + v[0] + "/reg")
                        .header("User-Agent", "okhttp/3.12.1")
                        .header("CF-Client-Version", v[1])
                        .post(RequestBody.create(body.toString(), JSON))
                        .build();
                JSONObject res;
                try (Response r = c.newCall(rq).execute()) {
                    if (!r.isSuccessful() || r.body() == null) throw new IOException("Cloudflare a répondu " + r.code());
                    JSONObject j = new JSONObject(r.body().string());
                    res = j.optJSONObject("result") != null ? j.getJSONObject("result") : j;
                }
                String id = res.optString("id"), token = res.optString("token");
                if (!id.isEmpty() && !token.isEmpty()) {
                    try {
                        Request p = new Request.Builder().url(API + v[0] + "/reg/" + id)
                                .header("User-Agent", "okhttp/3.12.1")
                                .header("CF-Client-Version", v[1])
                                .header("Authorization", "Bearer " + token)
                                .patch(RequestBody.create("{\"warp_enabled\":true}", JSON))
                                .build();
                        c.newCall(p).execute().close();
                    } catch (Exception ignored) {
                    }
                }
                JSONObject cfg = res.getJSONObject("config");
                JSONObject peer = cfg.getJSONArray("peers").getJSONObject(0);
                JSONObject addr = cfg.getJSONObject("interface").getJSONObject("addresses");
                JSONObject ep = peer.optJSONObject("endpoint");
                JSONObject acc = new JSONObject()
                        .put("priv", kp.getPrivateKey().toBase64())
                        .put("peer", peer.optString("public_key", WARP_PEER))
                        .put("v4", addr.optString("v4", "172.16.0.2"))
                        .put("v6", addr.optString("v6", ""))
                        .put("host", ep != null ? ep.optString("host", "engage.cloudflareclient.com:2408") : "engage.cloudflareclient.com:2408");
                sp.edit().putString("warp", acc.toString()).apply();
                return acc;
            } catch (Exception e) {
                last = e;
            }
        }
        throw new IOException("Impossible de créer l'accès gratuit Cloudflare" + (last != null && last.getMessage() != null ? " (" + last.getMessage() + ")" : "") + ".");
    }

    /** Force le tunnel à ne concerner que Zap TV. */
    private String onlyThisApp(String text) {
        StringBuilder out = new StringBuilder();
        for (String line : text.replace("\r", "").split("\n")) {
            String t = line.trim().toLowerCase(Locale.ROOT);
            if (t.startsWith("includedapplications") || t.startsWith("excludedapplications")) continue;
            out.append(line).append("\n");
            if (t.equals("[interface]")) out.append("IncludedApplications = ").append(ctx.getPackageName()).append("\n");
        }
        return out.toString();
    }
}
