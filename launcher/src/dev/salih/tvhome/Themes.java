package dev.salih.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Photo-less ambient wallpapers: per time-of-day slot, a vertical gradient (two colours) under soft light blobs (the
 * other colours), drawn once per wallpaper change, never animated, so Home stays at zero frames while idle. Themes
 * ship in the APK (assets/themes.json) and can be added or overridden without a rebuild by pushing a file of the same
 * shape to the app's files folder on the TV (tvclean.py themes FILE): Android/data/dev.salih.tvhome/files/themes.json.
 */
final class Themes {
    static final String PREF = "wall_theme";   // "" = photos

    static final class Theme {
        final String name; final Map<String, int[]> slots = new LinkedHashMap<>();
        Theme(String name) { this.name = name; }
    }

    private Themes() { }

    /** All themes, the APK's first, then the pushed file's (a pushed theme with the same name replaces the built-in). */
    static List<Theme> all(Context c) {
        Map<String, Theme> out = new LinkedHashMap<>();
        try (InputStream in = c.getAssets().open("themes.json")) { parse(read(in), out); }
        catch (IOException | JSONException e) { android.util.Log.w("tvhome", "built-in themes: " + e); }
        File pushed = new File(c.getExternalFilesDir(null), "themes.json");
        if (pushed.isFile()) {
            try (InputStream in = new FileInputStream(pushed)) { parse(read(in), out); }
            catch (IOException | JSONException e) { android.util.Log.w("tvhome", "pushed themes.json ignored: " + e); }
        }
        return new ArrayList<>(out.values());
    }

    static Theme find(Context c, String name) {
        if (name == null || name.isEmpty()) return null;
        for (Theme t : all(c)) if (t.name.equals(name)) return t;
        return null;
    }

    private static void parse(String json, Map<String, Theme> out) throws JSONException {
        JSONArray list = new JSONObject(json).getJSONArray("themes");
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.getJSONObject(i);
            Theme t = new Theme(o.getString("name"));
            for (String slot : new String[]{"sabah", "gunduz", "aksam", "gece"}) {
                JSONArray cs = o.optJSONArray(slot);
                if (cs == null || cs.length() < 2) continue;
                int[] colors = new int[cs.length()];
                for (int k = 0; k < colors.length; k++) colors[k] = Color.parseColor(cs.getString(k));
                t.slots.put(slot, colors);
            }
            if (!t.slots.isEmpty()) out.put(t.name, t);
        }
    }

    /** Colours for a slot; a missing slot borrows the nearest earlier one (night → evening → day → morning). */
    static int[] colors(Theme t, String slot) {
        String[] order = {"sabah", "gunduz", "aksam", "gece"};
        int i = java.util.Arrays.asList(order).indexOf(slot);
        for (int k = Math.max(i, 0); k >= 0; k--) if (t.slots.containsKey(order[k])) return t.slots.get(order[k]);
        return t.slots.values().iterator().next();
    }

    /**
     * Draws one frame. `seed` moves the light blobs, so each wallpaper change shows the same palette in a new
     * arrangement. Half screen size in ARGB_8888 (no banding in the gradients; the view scales it up smoothly).
     */
    static Bitmap render(Theme t, String slot, long seed, int w, int h) {
        int[] c = colors(t, slot);
        Bitmap b = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, h, c[0], c[1], Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, p);
        Random r = new Random(seed * 31 + t.name.hashCode() * 17L + slot.hashCode());
        for (int k = 2; k < c.length; k++) {
            float x = w * (0.1f + 0.8f * r.nextFloat()), y = h * (0.05f + 0.7f * r.nextFloat());
            float radius = w * (0.35f + 0.25f * r.nextFloat());
            int core = (c[k] & 0x00FFFFFF) | 0x8C000000, edge = c[k] & 0x00FFFFFF;
            p.setShader(new RadialGradient(x, y, radius, new int[]{core, (c[k] & 0x00FFFFFF) | 0x33000000, edge},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawCircle(x, y, radius, p);
        }
        return b;
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
