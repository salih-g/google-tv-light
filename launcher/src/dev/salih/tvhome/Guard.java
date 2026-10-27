package dev.salih.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The home network's AdGuard Home, through its own HTTP API (/control/*, Basic auth with the dashboard's user and
 * password, typed on the TV in Home settings and kept in this app's private preferences). Read: protection state and
 * the last 24 hours' counts. Write: only pausing protection for a few minutes and turning it back on.
 */
final class Guard {
    static final String URL = "ag_url", USER = "ag_user", PASS = "ag_pass";
    private static final ExecutorService NET = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static final class Status {
        final boolean on; final long pausedMs; final int queries, blocked; final double avgMs;
        Status(boolean on, long pausedMs, int queries, int blocked, double avgMs) {
            this.on = on; this.pausedMs = pausedMs; this.queries = queries; this.blocked = blocked; this.avgMs = avgMs;
        }
        int blockedPercent() { return queries == 0 ? 0 : Math.round(blocked * 100f / queries); }
    }

    interface Result<T> { void done(T value, String error); }

    private Guard() { }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(Sky.PREFS, Context.MODE_PRIVATE); }

    static boolean configured(Context c) { return !prefs(c).getString(URL, "").isEmpty(); }

    /** "192.168.2.1:3000" or a full URL; no trailing slash. */
    static String normalize(String address) {
        String a = address.trim();
        if (a.isEmpty()) return a;
        if (!a.startsWith("http://") && !a.startsWith("https://")) a = "http://" + a;
        while (a.endsWith("/")) a = a.substring(0, a.length() - 1);
        return a;
    }

    static void status(Context context, Result<Status> result) {
        Context c = context.getApplicationContext();
        NET.execute(() -> {
            try {
                JSONObject s = new JSONObject(call(c, "GET", "/control/status", null));
                JSONObject st = new JSONObject(call(c, "GET", "/control/stats", null));
                Status out = new Status(s.optBoolean("protection_enabled", true), s.optLong("protection_disabled_duration", 0),
                        st.optInt("num_dns_queries"), st.optInt("num_blocked_filtering"), st.optDouble("avg_processing_time", 0) * 1000);
                MAIN.post(() -> result.done(out, null));
            } catch (IOException | JSONException e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                MAIN.post(() -> result.done(null, msg));
            }
        });
    }

    /** Turns protection off for `minutes` (AdGuard Home turns it back on by itself), or on now when minutes is 0. */
    static void protect(Context context, int minutes, Result<Status> result) {
        Context c = context.getApplicationContext();
        NET.execute(() -> {
            try {
                JSONObject body = new JSONObject().put("enabled", minutes == 0);
                if (minutes > 0) body.put("duration", minutes * 60_000L);
                call(c, "POST", "/control/protection", body.toString());
            } catch (IOException | JSONException e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                MAIN.post(() -> result.done(null, msg));
                return;
            }
            status(c, result);
        });
    }

    private static String call(Context c, String method, String path, String json) throws IOException {
        SharedPreferences p = prefs(c);
        HttpURLConnection http = (HttpURLConnection) new URL(p.getString(URL, "") + path).openConnection();
        http.setConnectTimeout(4000); http.setReadTimeout(6000);
        http.setRequestMethod(method);
        String auth = p.getString(USER, "") + ":" + p.getString(PASS, "");
        http.setRequestProperty("Authorization", "Basic " + Base64.encodeToString(auth.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
        try {
            if (json != null) {
                http.setDoOutput(true);
                http.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = http.getOutputStream()) { out.write(json.getBytes(StandardCharsets.UTF_8)); }
            }
            int code = http.getResponseCode();
            if (code == 401 || code == 403) throw new IOException(c.getString(R.string.ag_wrong_login));
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = http.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            http.disconnect();
        }
    }
}
