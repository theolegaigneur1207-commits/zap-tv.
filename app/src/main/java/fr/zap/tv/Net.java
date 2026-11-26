package fr.zap.tv;

import android.content.Context;

import java.io.File;
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.Cache;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.dnsoverhttps.DnsOverHttps;

/**
 * Un seul réseau pour toute l'app :
 * - DNS sécurisé (DNS-over-HTTPS Cloudflare) : contourne les DNS du fournisseur d'accès,
 *   avec repli automatique sur le DNS classique ;
 * - connexions réutilisées (pool) : zapping et chargements plus rapides ;
 * - cache disque de 250 Mo pour les affiches, logos et polices.
 */
public final class Net {

    private static OkHttpClient base, player, web;
    private static Cache cache;
    private static volatile boolean secureDns = true;
    private static final ConcurrentHashMap<String, Object[]> memo = new ConcurrentHashMap<>();
    private static final ExecutorService bg = Executors.newSingleThreadExecutor();

    private Net() {}

    public static void setSecureDns(boolean on) {
        secureDns = on;
        memo.clear();
    }

    public static synchronized OkHttpClient base(Context c) {
        if (base != null) return base;
        DnsOverHttps doh = null;
        try {
            OkHttpClient boot = new OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(6, TimeUnit.SECONDS)
                    .build();
            doh = new DnsOverHttps.Builder()
                    .client(boot)
                    .url(HttpUrl.get("https://cloudflare-dns.com/dns-query"))
                    .bootstrapDnsHosts(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("1.0.0.1"))
                    .includeIPv6(false)
                    .build();
        } catch (Exception ignored) {
        }
        final DnsOverHttps d = doh;
        Dns dns = host -> {
            if (host.matches("^[0-9.]+$") || host.contains(":")) return Dns.SYSTEM.lookup(host);
            Object[] hit = memo.get(host);
            if (hit != null && (long) hit[0] > System.currentTimeMillis()) {
                @SuppressWarnings("unchecked") List<InetAddress> l = (List<InetAddress>) hit[1];
                return l;
            }
            List<InetAddress> r = null;
            if (secureDns && d != null) {
                try {
                    r = d.lookup(host);
                } catch (Exception ignored) {
                    // DNS sécurisé injoignable : on passe au DNS classique
                }
            }
            if (r == null || r.isEmpty()) r = Dns.SYSTEM.lookup(host);
            memo.put(host, new Object[]{System.currentTimeMillis() + 10 * 60_000L, r});
            return r;
        };
        Dispatcher disp = new Dispatcher();
        disp.setMaxRequests(48);
        disp.setMaxRequestsPerHost(12);
        base = new OkHttpClient.Builder()
                .dns(dns)
                .dispatcher(disp)
                .connectionPool(new ConnectionPool(12, 5, TimeUnit.MINUTES))
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .followRedirects(true)
                .followSslRedirects(true)
                .build();
        cache = new Cache(new File(c.getCacheDir(), "http"), 250L * 1024 * 1024);
        return base;
    }

    /** Lecteur vidéo : pas de cache disque (inutile pour un flux). */
    public static synchronized OkHttpClient player(Context c) {
        if (player == null) player = base(c).newBuilder().readTimeout(15, TimeUnit.SECONDS).build();
        return player;
    }

    /** Interface : images et polices gardées 30 jours en cache, même si le serveur dit le contraire. */
    public static synchronized OkHttpClient web(Context c) {
        if (web == null) {
            web = base(c).newBuilder()
                    .cache(cache)
                    .addNetworkInterceptor(chain -> {
                        Response r = chain.proceed(chain.request());
                        String ct = r.header("Content-Type", "");
                        String p = chain.request().url().encodedPath().toLowerCase();
                        boolean asset = ct.startsWith("image/") || ct.startsWith("font/")
                                || p.matches(".*\\.(jpe?g|png|webp|gif|svg|woff2?|ttf)$");
                        if (asset && r.isSuccessful()) {
                            return r.newBuilder()
                                    .removeHeader("Pragma").removeHeader("Expires").removeHeader("Vary")
                                    .header("Cache-Control", "public, max-age=2592000")
                                    .build();
                        }
                        return r;
                    })
                    .build();
        }
        return web;
    }

    /** Résout le nom du serveur à l'avance : la chaîne démarre plus vite quand on la lance. */
    public static void warm(Context c, String url) {
        final OkHttpClient b = base(c);
        bg.execute(() -> {
            try {
                HttpUrl u = HttpUrl.parse(url);
                if (u != null) b.dns().lookup(u.host());
            } catch (Exception ignored) {
            }
        });
    }

    /** À appeler quand le VPN s'allume ou s'éteint : les anciennes connexions ne passent pas par le tunnel. */
    public static void resetConnections() {
        memo.clear();
        if (base != null) base.connectionPool().evictAll();
        if (player != null) player.connectionPool().evictAll();
        if (web != null) web.connectionPool().evictAll();
    }

    public static void clearCache() {
        bg.execute(() -> {
            try {
                if (cache != null) cache.evictAll();
            } catch (Exception ignored) {
            }
        });
    }
}
