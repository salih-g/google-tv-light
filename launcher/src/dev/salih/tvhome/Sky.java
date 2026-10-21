package dev.salih.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sunrise, sunset and the current weather for the chosen city, from Open-Meteo (free, no key). One small request
 * every 30 minutes at most, cached in the "home" preferences so Home and the screensaver read it without the network.
 * The city starts as the TV's time zone city and can be changed in Home settings.
 */
final class Sky {
    static final String PREFS = "home";
    private static final long STALE_MS = 30 * 60_000L;
    private static final ExecutorService NET = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    interface Listener { void changed(); }

    static final class Place {
        final String name, area, country; final double lat, lon;
        Place(String name, String area, String country, double lat, double lon) { this.name = name; this.area = area; this.country = country; this.lat = lat; this.lon = lon; }
        @Override public String toString() { return area.isEmpty() ? name : name + ", " + area; }
    }

    private Sky() { }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    // ---------- reading (main thread, no network) ----------

    /** The few countries that use Fahrenheit (by the city's country, not the TV's language: en-US TVs are everywhere). */
    private static final java.util.Set<String> FAHRENHEIT = new java.util.HashSet<>(java.util.Arrays.asList("US", "LR", "MM", "BS", "BZ", "KY", "PW", "FM", "MH"));

    static boolean fahrenheit(Context c) {
        String u = prefs(c).getString("temp_unit", "");
        if (!u.isEmpty()) return u.equals("F");
        String cc = prefs(c).getString("city_cc", "");
        return FAHRENHEIT.contains(cc.isEmpty() ? Locale.getDefault().getCountry() : cc);
    }

    static String city(Context c) { return prefs(c).getString("city_name", ""); }

    /** Current temperature, or null before the first successful fetch or when the data is older than two hours. */
    static Integer temperature(Context c) {
        SharedPreferences p = prefs(c);
        if (System.currentTimeMillis() - p.getLong("wx_at", 0) > 2 * 3600_000L || !p.contains("wx_temp")) return null;
        return Math.round(p.getFloat("wx_temp", 0));
    }

    static int code(Context c) { return prefs(c).getInt("wx_code", 0); }

    static boolean isDay(Context c) { return prefs(c).getBoolean("wx_day", true); }

    /** Time-of-day slot from the real sunrise and sunset, or null when there is no data for today. */
    static String slot(Context c, long now) {
        String rises = prefs(c).getString("sun_rise", ""), sets = prefs(c).getString("sun_set", "");
        if (rises.isEmpty() || sets.isEmpty()) return null;
        String[] r = rises.split(","), s = sets.split(",");
        long rise = 0, set = 0, best = Long.MAX_VALUE;
        for (int i = 0; i < Math.min(r.length, s.length); i++) {
            long ri = Long.parseLong(r[i]) * 1000, si = Long.parseLong(s[i]) * 1000;
            long d = Math.abs(now - (ri + si) / 2);
            if (d < best) { best = d; rise = ri; set = si; }
        }
        if (best > 20 * 3600_000L) return null;   // stale: no day near now
        long min = 60_000L;
        if (now >= rise - 30 * min && now < rise + 150 * min) return "sabah";
        if (now >= rise + 150 * min && now < set - 75 * min) return "gunduz";
        if (now >= set - 75 * min && now < set + 45 * min) return "aksam";
        return "gece";
    }

    static int icon(int code, boolean day) {
        if (code == 0) return day ? R.drawable.ic_w_clear_day : R.drawable.ic_w_clear_night;
        if (code <= 2) return day ? R.drawable.ic_w_partly_cloudy_day : R.drawable.ic_w_partly_cloudy_night;
        if (code == 3) return R.drawable.ic_w_cloud;
        if (code == 45 || code == 48) return R.drawable.ic_w_foggy;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return R.drawable.ic_w_weather_snowy;
        if (code >= 95) return R.drawable.ic_w_thunderstorm;
        return R.drawable.ic_w_rainy;   // drizzle, rain, showers
    }

    static int label(int code) {
        if (code == 0) return R.string.wx_clear;
        if (code <= 2) return R.string.wx_partly;
        if (code == 3) return R.string.wx_cloudy;
        if (code == 45 || code == 48) return R.string.wx_fog;
        if (code >= 51 && code <= 57) return R.string.wx_drizzle;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return R.string.wx_snow;
        if (code >= 95) return R.string.wx_storm;
        return R.string.wx_rain;
    }

    // ---------- fetching (background) ----------

    /** Refreshes when the cache is stale (or when forced), then calls the listener on the main thread. */
    static void refresh(Context context, boolean force, Listener listener) {
        Context c = context.getApplicationContext();
        boolean fresh = System.currentTimeMillis() - prefs(c).getLong("wx_at", 0) < STALE_MS;
        if (!force && fresh && prefs(c).contains("city_cc")) return;
        NET.execute(() -> {
            try {
                if (!prefs(c).contains("city_lat") || !prefs(c).contains("city_cc")) {
                    // first run: the TV's time zone city; older saves without a country: look the saved city up again
                    String zone = TimeZone.getDefault().getID();
                    String saved = city(c);
                    String guess = saved.isEmpty() ? zone.substring(zone.lastIndexOf('/') + 1).replace('_', ' ') : saved;
                    // a zone like "GMT+03:00" names no city: wait for one to be picked in Home settings
                    if (guess.isEmpty() || guess.startsWith("GMT") || guess.startsWith("UTC") || guess.matches(".*\\d.*")) return;
                    List<Place> found = search(guess);
                    if (found.isEmpty()) return;
                    setPlace(c, found.get(0));
                }
                fetch(c);
                if (listener != null) MAIN.post(listener::changed);
            } catch (IOException | JSONException | RuntimeException e) {
                // offline or a bad reply: keep the cached values; the next refresh tries again
                android.util.Log.w("tvhome", "weather refresh failed: " + e);
            }
        });
    }

    static void setPlace(Context c, Place p) {
        prefs(c).edit().putString("city_name", p.name).putString("city_cc", p.country)
                .putFloat("city_lat", (float) p.lat).putFloat("city_lon", (float) p.lon)
                .remove("wx_at").apply();
    }

    private static void fetch(Context c) throws IOException, JSONException {
        SharedPreferences p = prefs(c);
        String url = String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code,is_day"
                        + "&daily=sunrise,sunset&timezone=auto&timeformat=unixtime&forecast_days=2%s",
                p.getFloat("city_lat", 0), p.getFloat("city_lon", 0), fahrenheit(c) ? "&temperature_unit=fahrenheit" : "");
        JSONObject o = new JSONObject(get(url));
        JSONObject cur = o.getJSONObject("current");
        JSONObject daily = o.getJSONObject("daily");
        p.edit()
                .putFloat("wx_temp", (float) cur.getDouble("temperature_2m"))
                .putInt("wx_code", cur.getInt("weather_code"))
                .putBoolean("wx_day", cur.optInt("is_day", 1) == 1)
                .putString("sun_rise", join(daily.getJSONArray("sunrise")))
                .putString("sun_set", join(daily.getJSONArray("sunset")))
                .putLong("wx_at", System.currentTimeMillis())
                .apply();
    }

    /** City search for Home settings. Blocking: call off the main thread. */
    static List<Place> search(String query) throws IOException, JSONException {
        String url = "https://geocoding-api.open-meteo.com/v1/search?count=8&format=json&language="
                + Locale.getDefault().getLanguage() + "&name=" + URLEncoder.encode(query.trim(), "UTF-8");
        JSONArray results = new JSONObject(get(url)).optJSONArray("results");
        List<Place> out = new ArrayList<>();
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject r = results.getJSONObject(i);
            String area = r.optString("admin1", "");
            String country = r.optString("country", "");
            out.add(new Place(r.getString("name"), area.isEmpty() ? country : area + (country.isEmpty() ? "" : ", " + country),
                    r.optString("country_code", ""), r.getDouble("latitude"), r.getDouble("longitude")));
        }
        return out;
    }

    private static String join(JSONArray a) throws JSONException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length(); i++) { if (i > 0) sb.append(','); sb.append(a.getLong(i)); }
        return sb.toString();
    }

    private static String get(String url) throws IOException {
        HttpURLConnection http = (HttpURLConnection) new URL(url).openConnection();
        http.setConnectTimeout(8000); http.setReadTimeout(8000);
        try (InputStream in = http.getInputStream()) {
            if (http.getResponseCode() != 200) throw new IOException("HTTP " + http.getResponseCode());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);   // InputStream.readAllBytes needs API 33
            return out.toString(StandardCharsets.UTF_8.name());
        } finally {
            http.disconnect();
        }
    }
}
