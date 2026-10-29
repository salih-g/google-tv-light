package dev.salih.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

import java.util.Random;

/**
 * A living pixel-art wallpaper (Valley). The scene paints a 128x72 buffer ten times a second; the buffer is scaled up with crisp
 * pixels and a thin mosaic grid, so each frame costs one tiny texture upload (measured on the TV: the same GPU time
 * per frame as a photo, no extra jank while navigating). It stops whenever Home or the screensaver is not on screen. Chosen in Home settings > Wallpaper ("live:<id>").
 */
abstract class Live extends Drawable implements Runnable {
    static final String PREFIX = "live:";
    static final String[] IDS = {"valley"};
    static final String[] LABELS = {"Pixel valley"};

    /** sabah, gunduz, ikindi, aksam or gece for a time (Sky.phase: the real sun); null = fixed hours. Home sets it. */
    static volatile java.util.function.LongFunction<String> phase;

    /** The current WMO weather code (Open-Meteo), or -1 when unknown. Home sets it. */
    static volatile java.util.function.IntSupplier weather;

    static int currentWeather() {
        java.util.function.IntSupplier w = weather;
        return w == null ? -1 : w.getAsInt();
    }

    static String currentPhase() {
        long now = System.currentTimeMillis();
        java.util.function.LongFunction<String> p = phase;
        String real = p == null ? null : p.apply(now);
        if (real != null) return real;
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        return h >= 6 && h < 9 ? "sabah" : h >= 9 && h < 15 ? "gunduz" : h >= 15 && h < 18 ? "ikindi" : h >= 18 && h < 20 ? "aksam" : "gece";
    }

    static final int W = 128, H = 72;
    private static final long FRAME_MS = 100;

    final int[] px = new int[W * H];
    private final Bitmap buf = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
    private final Paint pixels = new Paint();   // no filtering: crisp cells
    private final Paint grid = new Paint();
    private float[] lines = new float[0];
    private final long born = SystemClock.uptimeMillis();
    private boolean running, painted;
    final Random seed = new Random(7);

    static Live create(String pref) {
        if (pref == null || !pref.startsWith(PREFIX)) return null;
        switch (pref.substring(PREFIX.length())) {
            case "valley": return new Valley();
            default: return null;
        }
    }

    static String label(String pref) {
        for (int i = 0; i < IDS.length; i++) if ((PREFIX + IDS[i]).equals(pref)) return LABELS[i];
        return pref;
    }

    Live() {
        pixels.setFilterBitmap(false);
        grid.setColor(0x38000000);
    }

    /** Paints the frame at `t` seconds into px. */
    abstract void paint(float t);

    // ---------- animation ----------

    void start() {
        if (running) return;
        running = true;
        scheduleSelf(this, SystemClock.uptimeMillis() + FRAME_MS);
    }

    void stop() {
        running = false;
        unscheduleSelf(this);
    }

    @Override public void run() {
        if (!running) return;
        long now = SystemClock.uptimeMillis();
        step();
        invalidateSelf();
        scheduleSelf(this, now + FRAME_MS);
    }

    private void step() {
        paint((SystemClock.uptimeMillis() - born) / 1000f);
        buf.setPixels(px, 0, W, 0, 0, W, H);
    }

    // ---------- drawing ----------

    @Override public void draw(Canvas canvas) {
        if (!painted) { step(); painted = true; }   // the first frame (subclass fields exist only after construction)
        Rect b = getBounds();
        canvas.drawBitmap(buf, null, b, pixels);
        canvas.drawLines(lines, grid);
    }

    @Override protected void onBoundsChange(Rect b) {
        float cw = b.width() / (float) W, ch = b.height() / (float) H;
        grid.setStrokeWidth(Math.max(1f, cw * 0.12f));
        lines = new float[(W + H) * 4];
        int k = 0;
        for (int x = 0; x < W; x++) { float lx = b.left + x * cw; lines[k++] = lx; lines[k++] = b.top; lines[k++] = lx; lines[k++] = b.bottom; }
        for (int y = 0; y < H; y++) { float ly = b.top + y * ch; lines[k++] = b.left; lines[k++] = ly; lines[k++] = b.right; lines[k++] = ly; }
    }

    @Override public int getIntrinsicWidth() { return -1; }
    @Override public int getIntrinsicHeight() { return -1; }
    @Override public void setAlpha(int alpha) { pixels.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter cf) { pixels.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.OPAQUE; }

    // ---------- pixel helpers ----------

    static int rgb(int c) { return 0xFF000000 | c; }

    static int mix(int a, int b, float f) {
        f = Math.max(0f, Math.min(1f, f));
        int r = (int) (((a >> 16) & 255) + ((((b >> 16) & 255) - ((a >> 16) & 255)) * f));
        int g = (int) (((a >> 8) & 255) + ((((b >> 8) & 255) - ((a >> 8) & 255)) * f));
        int bl = (int) ((a & 255) + (((b & 255) - (a & 255)) * f));
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    void vertical(int top, int bottom) {
        for (int y = 0; y < H; y++) {
            int c = mix(top, bottom, y / (float) (H - 1));
            for (int x = 0; x < W; x++) px[y * W + x] = c;
        }
    }

    void set(int x, int y, int c) { if (x >= 0 && y >= 0 && x < W && y < H) px[y * W + x] = c; }

    /** Blends `c` over the cell by f (0..1). */
    void blend(int x, int y, int c, float f) { if (x >= 0 && y >= 0 && x < W && y < H) px[y * W + x] = mix(px[y * W + x], c, f); }

    void disc(float cx, float cy, float r, int c) {
        for (int y = (int) Math.floor(cy - r); y <= cy + r; y++)
            for (int x = (int) Math.floor(cx - r); x <= cx + r; x++)
                if ((x + 0.5f - cx) * (x + 0.5f - cx) + (y + 0.5f - cy) * (y + 0.5f - cy) <= r * r) set(x, y, c);
    }

    static float wrap(float v, float span) { v %= span; return v < 0 ? v + span : v; }

    // ---------- the scene ----------

    /**
     * A pixel mountain valley: far snowy peaks, nearer ridges, a meadow with a lake, pine hills and a small cabin.
     * The light follows the real sun (dawn, day, golden afternoon, sunset, moonlit night) and the sky the real
     * weather (clear, partly cloudy, overcast, fog, drizzle, rain, snow, thunderstorm); both change gradually.
     */
    static final class Valley extends Live {
        /** name, sky top, sky bottom, cloud, cloud underside, sun or moon, halo, stars, body x, body y, radius, moon?,
         *  terrain light (0..1), terrain tint */
        private static final Object[][] PALETTES = {
                {"sabah",  0x3B4A8C, 0xF6B38E, 0xFFE3D3, 0xD9A0A8, 0xFFD9A0, 0xFFC48A, 0.15f, 0.22f, 0.30f, 5f, false, 0.80f, 0xF6B38E},
                {"gunduz", 0x2F7BD9, 0xA8D8F7, 0xFFFFFF, 0xC9DCF0, 0xFFF4B8, 0xFFF9D6, 0.00f, 0.76f, 0.14f, 5f, false, 1.00f, 0xFFFFFF},
                {"ikindi", 0x3B78C0, 0xF5D7A1, 0xFFF6E6, 0xE3C59E, 0xFFE08A, 0xFFD27A, 0.00f, 0.70f, 0.24f, 5.5f, false, 0.92f, 0xF5D7A1},
                {"aksam",  0x3A2466, 0xFF8A5B, 0xFFB7A5, 0xB5677F, 0xFFB347, 0xFF8A5B, 0.25f, 0.50f, 0.26f, 6.5f, false, 0.62f, 0xFF8A5B},
                {"gece",   0x090E2B, 0x1B2A66, 0xC9CCE8, 0x8E94C4, 0xF2E9C9, 0x8A93C9, 1.00f, 0.78f, 0.16f, 6f, true, 0.30f, 0x3B4A8C}};
        private static final int FAR = 0x7F97BA, SNOWCAP = 0xEEF3F8, MID = 0x4C7558, MEADOW = 0x6A9A4A, NEAR = 0x3F6E36,
                TREE = 0x24452A, WALL = 0x8A5A3C, ROOF = 0x4A2C2C, NIGHT = 0x0A0F22;
        private static final int LAKE_X = 66, LAKE_Y = 46;

        private final int[] far = new int[W], mid = new int[W], near = new int[W];
        private final int[][] trees = new int[16][2];   // x, height
        private final float[][] stars = new float[80][3], rain = new float[70][3], snow = new float[110][4];
        private final float[][] clouds = {{8, 9, 0.9f, 1.0f}, {52, 15, 0.6f, 0.8f}, {96, 7, 1.1f, 1.2f}, {30, 19, 0.75f, 0.9f},
                {78, 12, 0.5f, 1.1f}, {118, 18, 0.8f, 0.85f}, {18, 4, 0.65f, 1.15f}, {64, 3, 0.95f, 1.0f}};
        private Object[] from, to;
        private float changedAt = -1000;
        /** cloud cover, rain, snow, fog, storm, snow on the ground: the shown values ease toward the weather's */
        private float[] wx;

        Valley() {
            for (int x = 0; x < W; x++) {
                far[x] = (int) (26 + 5 * Math.sin(x * 0.065 + 0.3) + 3 * Math.sin(x * 0.16 + 1.7) + 1.5 * Math.sin(x * 0.37));
                mid[x] = (int) (40 + 3.5 * Math.sin(x * 0.08 + 2.1) + 2 * Math.sin(x * 0.21 + 0.5));
                near[x] = (int) (52 + 3 * Math.sin(x * 0.05 + 1.2) + 2 * Math.sin(x * 0.15) + 4 * Math.exp(-Math.pow((x - LAKE_X) / 20.0, 2)));
            }
            for (int i = 0; i < trees.length; i++) {
                int x;
                do { x = seed.nextInt(W); } while (Math.abs(x - 100) < 6 || Math.abs(x - LAKE_X) < 14);   // not on the cabin or the lake
                trees[i][0] = x; trees[i][1] = 4 + seed.nextInt(3);
            }
            for (float[] s : stars) { s[0] = seed.nextInt(W); s[1] = seed.nextInt(30); s[2] = seed.nextFloat() * 6.3f; }
            for (float[] r : rain) { r[0] = seed.nextFloat() * W; r[1] = seed.nextFloat() * H; r[2] = 10 + seed.nextFloat() * 6; }   // cells a second: about one cell a frame, so the steps read as falling, not jumping
            for (float[] s : snow) { s[0] = seed.nextFloat() * W; s[1] = seed.nextFloat() * H; s[2] = 3 + seed.nextFloat() * 4; s[3] = seed.nextFloat() * 6.3f; }
        }

        /** Target weather for a WMO code (Open-Meteo): cloud, rain, snow, fog, storm. */
        private static float[] weatherFor(int code) {
            if (code < 0 || code == 0) return new float[]{0.10f, 0, 0, 0, 0};
            if (code == 1) return new float[]{0.25f, 0, 0, 0, 0};
            if (code == 2) return new float[]{0.50f, 0, 0, 0, 0};
            if (code == 3) return new float[]{0.95f, 0, 0, 0, 0};
            if (code == 45 || code == 48) return new float[]{0.60f, 0, 0, 1, 0};
            if (code >= 51 && code <= 57) return new float[]{0.80f, 0.35f, 0, 0.2f, 0};
            if ((code >= 71 && code <= 77) || code == 85 || code == 86) return new float[]{0.85f, 0, code == 75 || code == 86 ? 1 : 0.7f, 0, 0};
            if (code >= 95) return new float[]{1.00f, 1, 0, 0, 1};
            return new float[]{0.90f, code == 65 || code == 82 ? 1 : 0.75f, 0, 0, 0};   // rain, showers
        }

        private static Object[] palette(String name) {
            for (Object[] p : PALETTES) if (p[0].equals(name)) return p;
            return PALETTES[4];
        }

        @Override void paint(float t) {
            // light: the part of the day, blended over a minute when it changes
            Object[] want = palette(currentPhase());
            if (to == null) { from = to = want; } else if (want != to) { from = to; to = want; changedAt = t; }
            float k = Math.min(1f, (t - changedAt) / 60f);
            // weather: eases toward the forecast (snow settles on the ground more slowly)
            float[] target = weatherFor(currentWeather());
            if (wx == null) wx = new float[]{target[0], target[1], target[2], target[3], target[4], target[2] > 0 ? 1 : 0};
            for (int i = 0; i < 5; i++) wx[i] += (target[i] - wx[i]) * 0.012f;
            wx[5] += ((target[2] > 0 ? 1 : 0) - wx[5]) * 0.004f;
            float cloud = wx[0], wet = wx[1], snowing = wx[2], fog = wx[3], storm = wx[4], cover = wx[5];
            float overcast = Math.max(0, (cloud - 0.4f) / 0.6f), light = num(12, k) * (1 - 0.25f * overcast);
            int gray = mix(rgb(0x8E99A8), rgb(0x1A1F2B), 1 - num(12, k));

            int top = mix(rgb(col(1, k)), gray, overcast * 0.75f), bottom = mix(rgb(col(2, k)), gray, overcast * 0.7f);
            float flash = storm > 0.5f ? lightning(t) : 0;
            if (flash > 0) { top = mix(top, rgb(0xE6E9FF), 0.5f * flash); bottom = mix(bottom, rgb(0xE6E9FF), 0.4f * flash); }
            vertical(top, bottom);

            float starA = num(7, k) * (1 - cloud);
            if (starA > 0.01f) for (float[] s : stars)
                blend((int) s[0], (int) s[1], rgb(0xFFFFFF), starA * (0.35f + 0.45f * (float) Math.sin(t * 1.7 + s[2])));
            body(W * num(8, k), H * num(9, k), num(10, k), rgb(col(5, k)), rgb(col(6, k)), (boolean) (k < 0.5f ? from : to)[11], 1 - overcast * 0.9f);

            int tint = rgb(col(13, k));
            // far peaks, hazy with the sky behind them, snow on the tops
            for (int x = 0; x < W; x++) for (int y = far[x]; y < H; y++) {
                boolean cap = far[x] < 27 && y < far[x] + 2;
                set(x, y, mix(land(cap ? SNOWCAP : FAR, tint, light, overcast), bottom, 0.35f));
            }
            int cTop = mix(rgb(col(3, k)), mix(rgb(0x5A6170), rgb(0x20242E), 1 - light), Math.min(1, wet * 0.6f + storm * 0.4f + overcast * 0.35f + snowing * 0.3f));
            int cUnder = mix(rgb(col(4, k)), mix(rgb(0x434957), rgb(0x161A22), 1 - light), Math.min(1, wet * 0.6f + storm * 0.4f + overcast * 0.35f + snowing * 0.3f));
            int shown = Math.round(2 + cloud * (clouds.length - 2));
            for (int i = 0; i < shown; i++) { float[] c = clouds[i]; cloud(wrap(c[0] + t * c[2], W + 40) - 20, c[1], c[3], cTop, cUnder); }
            if (flash > 0.6f) bolt(t);

            // the ridge, the meadow, the lake, the pine hills and the cabin
            for (int x = 0; x < W; x++) for (int y = mid[x]; y < H; y++) set(x, y, land(snowy(MID, y - mid[x], cover), tint, light, overcast));
            for (int x = 0; x < W; x++) for (int y = mid[x] + 3; y < H; y++) set(x, y, land(snowy(MEADOW, y - mid[x] - 3, cover), tint, light, overcast));
            lake(t, top, bottom, light, wet);
            for (int x = 0; x < W; x++) for (int y = near[x]; y < H; y++) set(x, y, land(snowy(NEAR, y - near[x], cover), tint, light, overcast));
            for (int[] tr : trees) tree(tr[0], near[tr[0]], tr[1], tint, light, overcast, cover);
            cabin(100, near[100], tint, light, overcast, cover, t);

            if (fog > 0.01f) {
                int fogC = mix(rgb(0xD5DAE0), rgb(0x2B3140), 1 - light);
                for (int y = 12; y < H; y++) {
                    float row = 0.4f + 0.35f * (float) Math.exp(-Math.pow((y - 40) / 12.0, 2));   // thickest over the valley floor
                    float bank = 0.85f + 0.15f * (float) Math.sin(y * 0.55 + 1.3);
                    for (int x = 0; x < W; x++)
                        blend(x, y, fogC, fog * row * bank * (0.85f + 0.15f * (float) Math.sin(x * 0.045 + t * 0.2 + y * 0.08)));
                }
            }
            int drop = mix(rgb(0xA9B8CC), rgb(0x5C6A80), 1 - light);
            for (int i = 0; i < Math.round(rain.length * wet); i++) {
                float[] r = rain[i];
                int x = (int) wrap(r[0] - t * r[2] * 0.3f, W), y = (int) wrap(r[1] + t * r[2], H);
                blend(x, y, drop, 0.65f); blend(x + 1, y - 1, drop, 0.45f); blend(x + 2, y - 2, drop, 0.2f);   // a slanted streak
            }
            for (int i = 0; i < Math.round(snow.length * snowing); i++) {
                float[] s = snow[i];
                blend((int) (s[0] + Math.sin(t * 0.8 + s[3]) * 1.5f), (int) wrap(s[1] + t * s[2], H), rgb(0xFFFFFF), 0.85f);
            }
        }

        private int col(int i, float k) { return mix(0xFF000000 | (int) from[i], 0xFF000000 | (int) to[i], k) & 0xFFFFFF; }

        private float num(int i, float k) { return (float) from[i] + ((float) to[i] - (float) from[i]) * k; }

        /** A land colour in this light: tinted by the sky, darker at night, a little grey under heavy cloud. */
        private static int land(int base, int tint, float light, float overcast) {
            int c = mix(0xFF000000 | base, tint, 0.18f);
            c = mix(c, rgb(NIGHT), 1 - light);
            return mix(c, rgb(0x6B7280), overcast * 0.15f);
        }

        /** Ground under snow, before the light: white on top, the colour showing through a little deeper down. */
        private static int snowy(int base, int depth, float cover) {
            if (cover < 0.01f) return base;
            float f = cover * (depth == 0 ? 1f : depth < 3 ? 0.85f : 0.7f);
            return mix(0xFF000000 | base, rgb(depth == 0 ? 0xF4F7FA : 0xDCE3EA), f) & 0xFFFFFF;
        }

        private void body(float bx, float by, float r, int c, int halo, boolean moon, float shown) {
            if (shown <= 0.05f) return;
            for (int y = (int) (by - r - 4); y <= by + r + 4; y++) for (int x = (int) (bx - r - 4); x <= bx + r + 4; x++) {
                float d = (float) Math.hypot(x + 0.5f - bx, y + 0.5f - by);
                boolean in = d <= r && (!moon || Math.hypot(x + 0.5f - bx - 3, y + 0.5f - by + 2) > r * 0.9f);
                if (in) blend(x, y, c, shown);
                else if (d <= r + 4 && d > r) blend(x, y, halo, shown * (moon ? 0.12f : 0.22f) * (r + 4 - d) / 4f * 3f);
            }
        }

        private void cloud(float x, float y, float s, int top, int under) {
            float[][] bumps = {{0, 0, 3.2f}, {4, -2, 4.2f}, {9, -1, 3.6f}, {13, 0.5f, 2.8f}};
            for (float[] b : bumps) disc(x + b[0] * s, y + b[1] * s + 1.2f, b[2] * s, under);   // shaded underside
            for (float[] b : bumps) disc(x + b[0] * s, y + b[1] * s, b[2] * s, top);
        }

        /** The lake mirrors the sky; it glints, and rain rings it. */
        private void lake(float t, int top, int bottom, float light, float wet) {
            for (int y = LAKE_Y; y < LAKE_Y + 6; y++) {
                float half = 26 - (y - LAKE_Y) * (y - LAKE_Y) * 0.9f;
                for (int x = (int) (LAKE_X - half); x <= LAKE_X + half; x++) {
                    if (y >= near[Math.max(0, Math.min(W - 1, x))]) continue;
                    int c = mix(mix(bottom, top, (y - LAKE_Y) / 6f), rgb(0x0A1A2A), 0.3f + 0.3f * (1 - light));
                    if (Math.sin(x * 0.5 + t * 1.5 + y * 1.3) > 0.88) c = mix(c, rgb(0xFFFFFF), 0.25f * light + 0.05f);
                    if (wet > 0.15f && hash(x * 31 + y * 17 + (int) (t * 3)) < wet * 0.12f) c = mix(c, rgb(0xC8D4E2), 0.5f);
                    set(x, y, c);
                }
            }
        }

        private void tree(int x, int ground, int h, int tint, float light, float overcast, float cover) {
            int c = land(TREE, tint, light, overcast), white = land(snowy(TREE, 0, cover * 0.8f), tint, light, overcast);
            for (int k = 0; k < h; k++) {
                int w = (h - k) / 2;
                for (int dx = -w; dx <= w; dx++) set(x + dx, ground - 1 - k, k == h - 1 || dx == -w || dx == w ? white : c);
            }
        }

        private void cabin(int x, int ground, int tint, float light, float overcast, float cover, float t) {
            int wall = land(WALL, tint, light, overcast), roof = land(snowy(ROOF, 0, cover), tint, light, overcast);
            for (int dy = 1; dy <= 3; dy++) for (int dx = -3; dx <= 3; dx++) set(x + dx, ground - dy, wall);
            for (int dy = 0; dy < 3; dy++) for (int dx = -4 + dy; dx <= 4 - dy; dx++) set(x + dx, ground - 4 - dy, roof);
            set(x + 2, ground - 7, land(WALL, tint, light, overcast));   // chimney
            boolean lit = light < 0.7f;
            set(x - 1, ground - 2, lit ? rgb(0xFFD27A) : rgb(0x2A2A2A));
            set(x + 1, ground - 2, lit ? rgb(0xFFD27A) : rgb(0x2A2A2A));
            if (lit || cover > 0.3f) for (int i = 0; i < 4; i++) {   // smoke when it is dark or cold
                float age = wrap(t * 0.6f + i * 0.25f, 1f);
                blend(Math.round(x + 2 + (float) Math.sin(t + i) * age * 2), Math.round(ground - 8 - age * 6), rgb(0xB8BEC8), 0.5f * (1 - age));
            }
        }

        /** A storm's flash: about every nine seconds, brightness 0..1. */
        private static float lightning(float t) {
            float p = wrap(t + 4, 8.7f) / 8.7f;
            return p < 0.03f ? 1f : p > 0.05f && p < 0.07f ? 0.7f : 0f;
        }

        private void bolt(float t) {
            Random r = new Random((long) ((t + 4) / 8.7f));
            int x = 20 + r.nextInt(W - 40), y = 8;
            while (y < far[Math.max(0, Math.min(W - 1, x))]) { set(x, y, rgb(0xFFFDE7)); y++; x += r.nextInt(3) - 1; }
        }

        private static float hash(int n) {
            n = (n << 13) ^ n;
            return ((n * (n * n * 15731 + 789221) + 1376312589) & 0x7fffffff) / (float) 0x7fffffff;
        }
    }
}
