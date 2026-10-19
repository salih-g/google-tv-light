package dev.salih.tvhome;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.os.Handler;
import android.view.View;
import android.widget.ImageView;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Time-of-day wallpaper engine. Assets are named {@code <scene>-<slot>.jpg} where slot is one of
 * sabah, gunduz, aksam, gece. Every {@code intervalMs} the engine picks the next scene that has a
 * frame for the current slot and crossfades to it; when the slot changes (sunset) the same scene
 * crossfades to its next frame, so the switch looks like the light changing rather than a cut.
 * Frames decode at screen size in RGB_565: two frames resident at most, ~4 MB each.
 */
final class Wallpapers {
    private static final String[] SLOTS = {"sabah", "gunduz", "aksam", "gece"};
    private static final long FADE_MS = 1800;

    private final Context ctx;
    private final ImageView back, front;
    private final Handler handler;
    private final long intervalMs;
    private final int width, height;
    private final Map<String, List<String>> scenes = new HashMap<>(); // scene -> slots available
    private final List<String> sceneOrder = new ArrayList<>();
    private String currentScene, currentSlot;
    private boolean frontIsTop = true;

    Wallpapers(Context ctx, ImageView back, ImageView front, Handler handler, int minutes, int w, int h) {
        this.ctx = ctx; this.back = back; this.front = front; this.handler = handler;
        this.intervalMs = Math.max(1, minutes) * 60_000L; this.width = w; this.height = h;
        scan();
    }

    private void scan() {
        try {
            for (String f : ctx.getAssets().list("wallpapers")) {
                if (!f.endsWith(".jpg")) continue;
                String[] parts = f.substring(0, f.length() - 4).split("-");
                if (parts.length != 2 || slotIndex(parts[1]) < 0) continue; // ignore extras like "-pastel"
                if (!scenes.containsKey(parts[0])) { scenes.put(parts[0], new ArrayList<>()); sceneOrder.add(parts[0]); }
                scenes.get(parts[0]).add(parts[1]);
            }
        } catch (IOException ignored) { }
    }

    private static int slotIndex(String s) {
        for (int i = 0; i < SLOTS.length; i++) if (SLOTS[i].equals(s)) return i;
        return -1;
    }

    static String slotFor(Calendar c) {
        int h = c.get(Calendar.HOUR_OF_DAY);
        if (h >= 6 && h < 10) return "sabah";
        if (h >= 10 && h < 17) return "gunduz";
        if (h >= 17 && h < 20) return "aksam";
        return "gece";
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            String slot = slotFor(Calendar.getInstance());
            // Slot changed: keep the scene if it has the new frame, so the light changes in place.
            if (!slot.equals(currentSlot) && currentScene != null && scenes.get(currentScene).contains(slot)) {
                show(currentScene, slot, true);
            } else {
                show(nextScene(slot), slot, currentScene != null);
            }
            handler.postDelayed(this, intervalMs);
        }
    };

    void start() { handler.removeCallbacks(tick); handler.post(tick); }
    void stop() { handler.removeCallbacks(tick); }

    /** Called when the clock minute changes; switches frame exactly at a slot boundary. */
    void onMinute() {
        String slot = slotFor(Calendar.getInstance());
        if (currentScene != null && !slot.equals(currentSlot)) { handler.removeCallbacks(tick); handler.post(tick); }
    }

    private String nextScene(String slot) {
        if (sceneOrder.isEmpty()) return null;
        int start = currentScene == null ? (int) (System.currentTimeMillis() / intervalMs % sceneOrder.size())
                                         : sceneOrder.indexOf(currentScene) + 1;
        for (int i = 0; i < sceneOrder.size(); i++) {
            String s = sceneOrder.get((start + i) % sceneOrder.size());
            if (scenes.get(s).contains(slot)) return s;
        }
        return sceneOrder.get(start % sceneOrder.size()); // nothing has this slot: fall back below
    }

    private void show(String scene, String slot, boolean animate) {
        if (scene == null) return;
        String frame = scenes.get(scene).contains(slot) ? slot : fallbackSlot(scene, slot);
        if (scene.equals(currentScene) && frame.equals(currentSlot)) return;
        Bitmap bmp = decode("wallpapers/" + scene + "-" + frame + ".jpg");
        if (bmp == null) return;
        ImageView top = frontIsTop ? front : back, bottom = frontIsTop ? back : front;
        ImageView target = animate ? bottom : top;
        BitmapDrawable d = new BitmapDrawable(ctx.getResources(), bmp);
        target.setImageDrawable(d);
        if (animate) {
            target.setAlpha(0f); target.setVisibility(View.VISIBLE);
            ObjectAnimator a = ObjectAnimator.ofFloat(target, View.ALPHA, 0f, 1f);
            a.setDuration(FADE_MS);
            final ImageView old = top;
            a.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator anim) {
                    old.setImageDrawable(null); // release the previous frame
                    old.setVisibility(View.INVISIBLE);
                }
            });
            target.bringToFront(); a.start(); frontIsTop = !frontIsTop;
        } else {
            top.setAlpha(1f); top.setVisibility(View.VISIBLE); top.bringToFront();
        }
        currentScene = scene; currentSlot = frame;
    }

    /** A scene missing the exact slot shows its nearest earlier frame; night falls back to evening, then day. */
    private String fallbackSlot(String scene, String slot) {
        List<String> have = scenes.get(scene);
        for (int i = slotIndex(slot); i >= 0; i--) if (have.contains(SLOTS[i])) return SLOTS[i];
        return have.get(0);
    }

    private Bitmap decode(String asset) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        o.inJustDecodeBounds = true;
        try (InputStream in = ctx.getAssets().open(asset, AssetManager.ACCESS_STREAMING)) {
            BitmapFactory.decodeStream(in, null, o);
        } catch (IOException e) { return null; }
        int sample = 1;
        while (o.outWidth / (sample * 2) >= width && o.outHeight / (sample * 2) >= height) sample *= 2;
        o.inJustDecodeBounds = false; o.inSampleSize = sample;
        try (InputStream in = ctx.getAssets().open(asset, AssetManager.ACCESS_STREAMING)) {
            return BitmapFactory.decodeStream(in, null, o);
        } catch (IOException e) { return null; }
    }
}
