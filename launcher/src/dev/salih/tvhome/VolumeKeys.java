package dev.salih.tvhome;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;

/**
 * One volume step per press while KeyService filters keys. This TCL firmware (G03, Android 11) applies a volume key
 * before the accessibility filter sees it and again when the filter hands it back (the key is re-injected), so one
 * tap moved two steps (measured: TclPhoneWindowManager logs the key twice). KeyService swallows Volume Up/Down, which
 * leaves the firmware's first step; a swallowed key gets no auto-repeat, so a held one is repeated here at about
 * Android's rate. Approach from cleanyourtv (VolumeKeys.java, MIT, Copyright (c) 2026 Gathin).
 */
final class VolumeKeys {
    private static final long REPEAT_DELAY_MS = 500, REPEAT_INTERVAL_MS = 60;
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static Runnable step;

    private VolumeKeys() { }

    /** True when the event is a volume key, now swallowed (and repeated while held). Other keys: false, untouched. */
    static boolean handle(Context context, KeyEvent e) {
        int direction = e.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP ? AudioManager.ADJUST_RAISE
                : e.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN ? AudioManager.ADJUST_LOWER : 0;
        if (direction == 0) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() > 0) return true;   // our own repeat runs instead
        stop();
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio == null) return true;
        step = new Runnable() {
            @Override public void run() {
                audio.adjustVolume(direction, AudioManager.FLAG_SHOW_UI);
                HANDLER.postDelayed(this, REPEAT_INTERVAL_MS);
            }
        };
        HANDLER.postDelayed(step, REPEAT_DELAY_MS);
        return true;
    }

    static void stop() {
        if (step != null) HANDLER.removeCallbacks(step);
        step = null;
    }
}
