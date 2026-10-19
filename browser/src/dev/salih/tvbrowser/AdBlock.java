package dev.salih.tvbrowser;

import android.content.Context;
import android.webkit.WebResourceResponse;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Network-layer ad blocking: a set of blocked domains (StevenBlack hosts + HaGeZi Pro, see
 * tools/build-blocklist.py). A request is blocked when its host or any parent domain is listed.
 * Lookup is a few hash probes per request; the set costs a few MB of heap and loads once.
 */
final class AdBlock {
    private final Set<String> domains = new HashSet<>(300_000);
    private volatile boolean ready;
    int blockedCount;

    AdBlock(Context ctx) {
        new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(ctx.getAssets().open("blocklist.txt.gz"))), 1 << 16)) {
                String line;
                while ((line = r.readLine()) != null) if (!line.isEmpty()) domains.add(line);
            } catch (IOException ignored) { }
            ready = true;
        }, "adblock-load").start();
    }

    boolean isBlocked(String host) {
        if (!ready || host == null) return false;
        host = host.toLowerCase();
        int from = 0;
        while (true) {
            if (domains.contains(host.substring(from))) return true;
            int dot = host.indexOf('.', from);
            if (dot < 0) return false;
            from = dot + 1;
            if (host.indexOf('.', from) < 0) return false; // never block a bare TLD
        }
    }

    /** An empty response so the page sees a fast failure instead of a hanging request. */
    static WebResourceResponse empty() {
        return new WebResourceResponse("text/plain", "utf-8", 204, "No Content", null, new ByteArrayInputStream(new byte[0]));
    }
}
