package io.github.vesaaa.cleanviddatv;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Finds 飞牛影视 servers on the TV's own network: every address of its /24 is tried on port 5666, and the ones that
 * answer are asked for their version, which needs no account. Blocking, a few seconds: run it off the main thread.
 */
final class FeiniuScan {

    private static final int CONNECT_MS = 350, THREADS = 48;

    /** A server found: its base (http://host:5666), the NAS's name and the 飞牛影视 version. */
    static final class Server {
        final String base, name, version;

        Server(String base, String name, String version) {
            this.base = base;
            this.name = name;
            this.version = version;
        }
    }

    private FeiniuScan() { }

    /** The TV's own IPv4 address on its active network, or null (no network, IPv6 only). */
    static Inet4Address ownAddress(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network net = cm == null ? null : cm.getActiveNetwork();
            LinkProperties lp = net == null ? null : cm.getLinkProperties(net);
            if (lp == null) return null;
            for (LinkAddress a : lp.getLinkAddresses()) {
                InetAddress ip = a.getAddress();
                if (ip instanceof Inet4Address && !ip.isLoopbackAddress()) return (Inet4Address) ip;
            }
        } catch (RuntimeException ignored) {
            // no network information
        }
        return null;
    }

    /** The other 253 addresses of the /24 an address is in, nearest first so a NAS next to the router shows up early. */
    static List<String> neighbours(byte[] own) {
        List<String> out = new ArrayList<>();
        int self = own[3] & 0xFF;
        String prefix = (own[0] & 0xFF) + "." + (own[1] & 0xFF) + "." + (own[2] & 0xFF) + ".";
        for (int d = 1; d < 255; d++) {
            for (int host : new int[]{self - d, self + d}) {
                if (host >= 1 && host <= 254 && host != self) out.add(prefix + host);
            }
        }
        return out;
    }

    /** Every server on the network, by address. Empty when the TV has no IPv4 network. */
    static List<Server> scan(Context ctx) {
        Inet4Address own = ownAddress(ctx);
        if (own == null) return new ArrayList<>();
        String key = Feiniu.apiKey(ctx), secret = Feiniu.apiSecret(ctx);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS, r -> new Thread(r, "feiniu-scan"));
        List<Future<Server>> tries = new ArrayList<>();
        for (String host : neighbours(own.getAddress())) tries.add(pool.submit(() -> tryHost(host, key, secret)));
        List<Server> found = new ArrayList<>();
        try {
            for (Future<Server> f : tries) {
                try {
                    Server s = f.get(15, TimeUnit.SECONDS);
                    if (s != null) found.add(s);
                } catch (Exception ignored) {
                    // that address does not count
                }
            }
        } finally {
            pool.shutdownNow();
        }
        Collections.sort(found, (a, b) -> Integer.compare(lastOctet(a.base), lastOctet(b.base)));
        return found;
    }

    /** One address: open the port, then ask it whether it is 飞牛影视. */
    static Server tryHost(String host, String key, String secret) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, Feiniu.PORT), CONNECT_MS);
        } catch (IOException e) {
            return null;
        }
        String base = "http://" + host + ":" + Feiniu.PORT;
        try {
            String[] info = Feiniu.probe(base, key, secret);
            return new Server(base, info[0], info[1]);
        } catch (Feiniu.ApiException e) {
            // A 飞牛影视 that refuses our signature is still one: keep it, signing in will say why.
            return e.badSign() ? new Server(base, "", "") : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static int lastOctet(String base) {
        try {
            String host = Feiniu.display(base);
            int colon = host.indexOf(':');
            if (colon >= 0) host = host.substring(0, colon);
            return Integer.parseInt(host.substring(host.lastIndexOf('.') + 1));
        } catch (RuntimeException e) {
            return 999;
        }
    }
}
