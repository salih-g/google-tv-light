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
 * Living pixel-art wallpapers. A scene paints a 128x72 buffer ten times a second; the buffer is scaled up with crisp
 * pixels and a thin mosaic grid, so each frame costs one tiny texture upload. This TV composites every frame on the
 * GPU, so the animation pauses while a remote key was pressed in the last 1.5 s (navigation stays smooth) and stops
 * whenever Home or the screensaver is not on screen. Chosen in Home settings > Wallpaper ("live:<id>").
 */
abstract class Live extends Drawable implements Runnable {
    static final String PREFIX = "live:";
    static final String[] IDS = {"sky", "koi", "fireflies", "jellyfish"};
    static final String[] LABELS = {"Sky", "Koi pond", "Fireflies", "Jellyfish"};

    /** sabah, gunduz, ikindi, aksam or gece for a time (Sky.phase: the real sun); null = fixed hours. Home sets it. */
    static volatile java.util.function.LongFunction<String> phase;

    static String currentPhase() {
        long now = System.currentTimeMillis();
        java.util.function.LongFunction<String> p = phase;
        String real = p == null ? null : p.apply(now);
        if (real != null) return real;
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        return h >= 6 && h < 9 ? "sabah" : h >= 9 && h < 15 ? "gunduz" : h >= 15 && h < 18 ? "ikindi" : h >= 18 && h < 20 ? "aksam" : "gece";
    }

    static final int W = 128, H = 72;
    private static final long FRAME_MS = 100, HOLD_MS = 1500;

    final int[] px = new int[W * H];
    private final Bitmap buf = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
    private final Paint pixels = new Paint();   // no filtering: crisp cells
    private final Paint grid = new Paint();
    private float[] lines = new float[0];
    private final long born = SystemClock.uptimeMillis();
    private boolean running, painted;
    private long heldUntil;
    final Random seed = new Random(7);

    static Live create(String pref) {
        if (pref == null || !pref.startsWith(PREFIX)) return null;
        switch (pref.substring(PREFIX.length())) {
            case "koi": return new Koi();
            case "fireflies": return new Fireflies();
            case "sky": return new Sky();
            case "jellyfish": return new Jellyfish();
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

    /** A remote key was pressed: keep still for a moment so the GPU is free for the focus animations. */
    void hold() { heldUntil = SystemClock.uptimeMillis() + HOLD_MS; }

    @Override public void run() {
        if (!running) return;
        long now = SystemClock.uptimeMillis();
        if (now < heldUntil) { scheduleSelf(this, heldUntil); return; }
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

    // ---------- scenes ----------

    /** Orange and white koi circling under lily pads; the water glints. */
    static final class Koi extends Live {
        private final float[][] fish = {   // cx, cy, rx, ry, speed, phase, pattern
                {40, 30, 26, 14, 0.22f, 0.0f, 0}, {84, 44, 30, 16, -0.17f, 2.0f, 1},
                {64, 36, 46, 22, 0.13f, 4.1f, 2}, {96, 22, 18, 10, -0.27f, 1.0f, 1}};
        private final float[][] pads = {{18, 14, 6}, {108, 58, 7}, {30, 60, 5}, {116, 16, 5}, {70, 10, 4}};

        @Override void paint(float t) {
            vertical(rgb(0x0F6670), rgb(0x0A3B47));
            for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
                double s = Math.sin(x * 0.31 + t * 1.3 + Math.sin(y * 0.45 + t * 0.6) * 1.8) * Math.sin(y * 0.27 - t * 0.9);
                if (s > 0.86) blend(x, y, rgb(0x6FD3D1), (float) (s - 0.86) * 4f);
            }
            for (float[] f : fish) fish(f, t, true);
            for (float[] f : fish) fish(f, t, false);
            for (float[] p : pads) pad(p[0], p[1], p[2]);
            disc(19, 13, 1.6f, rgb(0xF4A6C0)); set(19, 13, rgb(0xFFD166));   // a flower on the first pad
        }

        private void fish(float[] f, float t, boolean shadow) {
            float a = t * f[4] + f[5];
            float hx = f[0] + (float) Math.cos(a) * f[2], hy = f[1] + (float) Math.sin(a) * f[3];
            float dx = (float) -Math.sin(a) * f[2] * Math.signum(f[4]), dy = (float) Math.cos(a) * f[3] * Math.signum(f[4]);
            float len = (float) Math.hypot(dx, dy); dx /= len; dy /= len;   // heading
            float nx = -dy, ny = dx;
            int body = rgb(0xF26B1D), white = rgb(0xF5F1E8), fin = rgb(0xF59A5B), shade = rgb(0x05222A);
            final float L = 11;
            for (float s = 0; s <= L + 3; s += 0.4f) {
                float wig = (float) Math.sin(t * 6 + s * 0.6) * 0.1f * s;   // the body bends more toward the tail
                float x = hx - dx * s + nx * wig, y = hy - dy * s + ny * wig;
                if (s > L) {   // tail fin: a fan that opens behind the body
                    float spread = (s - L) * 0.9f + 0.5f;
                    for (int side = -1; side <= 1; side += 2) {
                        float fx = x + nx * spread * side, fy = y + ny * spread * side;
                        if (shadow) blend(Math.round(fx + 2), Math.round(fy + 2), shade, 0.3f); else set(Math.round(fx), Math.round(fy), fin);
                    }
                    continue;
                }
                float r = s < 3 ? 1.5f + 0.3f * s : s < 6 ? 2.4f : 2.4f * (1 - (s - 6) / (L - 6)) + 0.5f;
                if (shadow) { shadowDisc(x + 2, y + 2, r, shade); continue; }
                boolean patch = f[6] == 0 ? s > 3.5f && s < 6.5f : f[6] == 1 ? s < 2.5f || (s > 6 && s < 7.5f) : s > 1.5f && s < 4f;
                disc(x, y, r, patch ? white : body);
            }
            if (shadow) return;
            for (int side = -1; side <= 1; side += 2) {   // pectoral fins and eyes
                float flap = (float) Math.sin(t * 4 + side) * 0.5f;
                set(Math.round(hx - dx * 3.5f + nx * (3.4f + flap) * side), Math.round(hy - dy * 3.5f + ny * (3.4f + flap) * side), fin);
                set(Math.round(hx - dx * 0.8f + nx * 0.9f * side), Math.round(hy - dy * 0.8f + ny * 0.9f * side), rgb(0x1A1A1A));
            }
        }

        private void shadowDisc(float cx, float cy, float r, int c) {
            for (int y = (int) Math.floor(cy - r); y <= cy + r; y++)
                for (int x = (int) Math.floor(cx - r); x <= cx + r; x++)
                    if ((x + 0.5f - cx) * (x + 0.5f - cx) + (y + 0.5f - cy) * (y + 0.5f - cy) <= r * r) blend(x, y, c, 0.06f);
        }

        private void pad(float cx, float cy, float r) {
            for (int y = (int) (cy - r); y <= cy + r; y++) for (int x = (int) (cx - r); x <= cx + r; x++) {
                float ddx = x + 0.5f - cx, ddy = y + 0.5f - cy, d = ddx * ddx + ddy * ddy;
                if (d > r * r) continue;
                double ang = Math.atan2(ddy, ddx);
                if (ang > 0.15 && ang < 0.75) continue;   // the notch
                set(x, y, d > (r - 1.2f) * (r - 1.2f) ? rgb(0x5DB75F) : rgb(0x3E8E41));
            }
        }
    }

    /** Fireflies blinking over dark grass under a tree line. */
    static final class Fireflies extends Live {
        private final float[][] flies = new float[26][6];
        private final int[] trees = new int[W], grass = new int[W];

        Fireflies() {
            for (float[] f : flies) {
                f[0] = seed.nextFloat() * W; f[1] = 14 + seed.nextFloat() * 44;
                f[2] = 0.2f + seed.nextFloat() * 0.5f; f[3] = seed.nextFloat() * 6.3f;
                f[4] = 0.6f + seed.nextFloat() * 1.2f; f[5] = seed.nextFloat() * 6.3f;
            }
            for (int x = 0; x < W; x++) {
                trees[x] = 40 + (int) (Math.sin(x * 0.11) * 3 + Math.sin(x * 0.37 + 1) * 2 + seed.nextInt(2));
                grass[x] = 4 + seed.nextInt(6);
            }
        }

        @Override void paint(float t) {
            vertical(rgb(0x03100C), rgb(0x0A2418));
            int tree = rgb(0x061A10), blade = rgb(0x0E3B22), tip = rgb(0x14502E);
            for (int x = 0; x < W; x++) for (int y = trees[x]; y < H; y++) set(x, y, tree);
            for (int x = 0; x < W; x++) {
                int sway = (int) Math.round(Math.sin(t * 0.9 + x * 0.33) * 0.7);
                for (int k = 0; k < grass[x]; k++) set(x + (k > grass[x] - 3 ? sway : 0), H - 1 - k, k == grass[x] - 1 ? tip : blade);
            }
            int glow = rgb(0xE9FF8A);
            for (float[] f : flies) {
                float x = f[0] + (float) Math.sin(t * f[2] + f[3]) * 7, y = f[1] + (float) Math.cos(t * f[2] * 0.8f + f[5]) * 4;
                float b = (float) Math.pow(Math.max(0, Math.sin(t * f[4] + f[5])), 3);
                if (b < 0.02f) continue;
                int cx = Math.round(x), cy = Math.round(y);
                blend(cx, cy, glow, b);
                blend(cx + 1, cy, glow, b * 0.4f); blend(cx - 1, cy, glow, b * 0.4f);
                blend(cx, cy + 1, glow, b * 0.4f); blend(cx, cy - 1, glow, b * 0.4f);
                blend(cx + 1, cy + 1, glow, b * 0.15f); blend(cx - 1, cy - 1, glow, b * 0.15f);
                blend(cx + 1, cy - 1, glow, b * 0.15f); blend(cx - 1, cy + 1, glow, b * 0.15f);
            }
        }
    }

    /**
     * The sky through the day: dawn, day, golden afternoon, sunset and a moonlit night, with the same slow pixel
     * clouds. The part of the day comes from `phase` (the real sunrise and sunset); a change blends over a minute.
     */
    static final class Sky extends Live {
        /** top, bottom, cloud, cloud underside, body (sun or moon), halo; then star strength, body x, y, radius, moon? */
        private static final Object[][] PALETTES = {
                {"sabah",  0x3B4A8C, 0xF6B38E, 0xFFE3D3, 0xD9A0A8, 0xFFD9A0, 0xFFC48A, 0.15f, 0.22f, 0.62f, 6f, false},
                {"gunduz", 0x2F7BD9, 0xA8D8F7, 0xFFFFFF, 0xC9DCF0, 0xFFF4B8, 0xFFF9D6, 0.00f, 0.76f, 0.22f, 6f, false},
                {"ikindi", 0x3B78C0, 0xF5D7A1, 0xFFF6E6, 0xE3C59E, 0xFFE08A, 0xFFD27A, 0.00f, 0.70f, 0.42f, 6.5f, false},
                {"aksam",  0x3A2466, 0xFF8A5B, 0xFFB7A5, 0xB5677F, 0xFFB347, 0xFF8A5B, 0.25f, 0.50f, 0.66f, 8f, false},
                {"gece",   0x090E2B, 0x1B2A66, 0xC9CCE8, 0x8E94C4, 0xF2E9C9, 0x8A93C9, 1.00f, 0.78f, 0.27f, 8f, true}};
        private final float[][] stars = new float[80][3];
        private final float[][] clouds = {{10, 14, 0.9f, 1.0f}, {60, 24, 0.6f, 0.8f}, {100, 46, 1.2f, 1.2f}, {30, 52, 0.75f, 0.9f}};
        private Object[] from, to;
        private float changedAt = -1000;

        Sky() {
            for (float[] s : stars) { s[0] = seed.nextInt(W); s[1] = seed.nextInt(H * 3 / 4); s[2] = seed.nextFloat() * 6.3f; }
        }

        private static Object[] palette(String name) {
            for (Object[] p : PALETTES) if (p[0].equals(name)) return p;
            return PALETTES[4];
        }

        @Override void paint(float t) {
            Object[] want = palette(currentPhase());
            if (to == null) { from = to = want; }
            else if (want != to) { from = to; to = want; changedAt = t; }
            float k = Math.min(1f, (t - changedAt) / 60f);   // a minute from the old light to the new
            vertical(rgb(col(1, k)), rgb(col(2, k)));
            float starA = num(7, k);
            if (starA > 0.01f) for (float[] s : stars)
                blend((int) s[0], (int) s[1], rgb(0xFFFFFF), starA * (0.35f + 0.45f * (float) Math.sin(t * 1.7 + s[2])));
            body(W * num(8, k), H * num(9, k), num(10, k), rgb(col(5, k)), rgb(col(6, k)), (boolean) (k < 0.5f ? from : to)[11]);
            for (float[] c : clouds) cloud(wrap(c[0] + t * c[2], W + 40) - 20, c[1], c[3], rgb(col(3, k)), rgb(col(4, k)));
        }

        private int col(int i, float k) { return mix(0xFF000000 | (int) from[i], 0xFF000000 | (int) to[i], k) & 0xFFFFFF; }

        private float num(int i, float k) { return (float) from[i] + ((float) to[i] - (float) from[i]) * k; }

        /** The sun with a soft halo, or the crescent moon. */
        private void body(float bx, float by, float r, int c, int halo, boolean moon) {
            for (int y = (int) (by - r - 4); y <= by + r + 4; y++) for (int x = (int) (bx - r - 4); x <= bx + r + 4; x++) {
                float d = (float) Math.hypot(x + 0.5f - bx, y + 0.5f - by);
                boolean in = d <= r && (!moon || Math.hypot(x + 0.5f - bx - 3.5f, y + 0.5f - by + 2.5f) > r * 0.9f);
                if (in) set(x, y, c);
                else if (d <= r + 4 && d > r) blend(x, y, halo, (moon ? 0.12f : 0.22f) * (r + 4 - d) / 4f * 3f);
            }
        }

        private void cloud(float x, float y, float s, int top, int under) {
            float[][] bumps = {{0, 0, 3.2f}, {4, -2, 4.2f}, {9, -1, 3.6f}, {13, 0.5f, 2.8f}};
            for (float[] b : bumps) disc(x + b[0] * s, y + b[1] * s + 1.2f, b[2] * s, under);   // shaded underside
            for (float[] b : bumps) disc(x + b[0] * s, y + b[1] * s, b[2] * s, top);
        }
    }

    /** Jellyfish pulsing upward through blue water, with light rays and drifting specks. */
    static final class Jellyfish extends Live {
        private final float[][] jelly = {{28, 0, 1.6f, 0.0f, 1.0f}, {78, 30, 1.1f, 2.0f, 1.3f}, {108, 55, 1.9f, 4.0f, 0.8f}};
        private final float[][] specks = new float[45][3];

        Jellyfish() { for (float[] s : specks) { s[0] = seed.nextInt(W); s[1] = seed.nextFloat() * H; s[2] = 0.5f + seed.nextFloat(); } }

        @Override void paint(float t) {
            vertical(rgb(0x0F4C81), rgb(0x061633));
            for (int y = 0; y < H * 2 / 3; y++) for (int x = 0; x < W; x++)
                if (wrap(x + y * 0.55f - t * 1.5f, 34) < 5) blend(x, y, rgb(0x5FA8E8), 0.18f * (1 - y / (H * 0.66f)));
            for (float[] s : specks) blend((int) s[0], (int) wrap(s[1] - t * s[2], H), rgb(0xB8D8F5), 0.35f);
            for (float[] j : jelly) jelly(j, t);
        }

        private void jelly(float[] j, float t) {
            float x = j[0] + (float) Math.sin(t * 0.3 + j[3]) * 6;
            float y = wrap(j[1] - t * j[2], H + 30) + 6;
            float p = 0.5f + 0.5f * (float) Math.sin(t * 2.2 + j[3]);
            float rx = (5 + p) * j[4], ry = (4 - 0.8f * p) * j[4];
            int bell = rgb(0xE58FD8), rim = rgb(0xF7C6EE), tent = rgb(0xC77BC0);
            for (int yy = (int) (y - ry); yy <= y; yy++) for (int xx = (int) (x - rx); xx <= x + rx; xx++) {
                float u = (xx + 0.5f - x) / rx, v = (yy + 0.5f - y) / ry;
                if (u * u + v * v <= 1) set(xx, yy, u * u + v * v > 0.6f ? rim : bell);
            }
            for (int i = -2; i <= 2; i++) for (int k = 1; k < 10 * j[4]; k++) {
                float tx = x + i * rx * 0.4f + (float) Math.sin(t * 3 + k * 0.5f + i) * 1.1f;
                blend(Math.round(tx), Math.round(y + k), tent, 1f - k / (10f * j[4]));
            }
        }
    }
}
