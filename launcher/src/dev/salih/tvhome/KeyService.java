package dev.salih.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.SparseBooleanArray;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Remote app buttons (Netflix, YouTube, ...). On this Google TV build the manufacturer's key handler only forwards
 * them to the stock Google TV home, which is asleep so this launcher can be Home; without a receiver the buttons
 * do nothing. Key filtering runs before that handler, so the buttons are taken here and the app opened directly.
 * Mapping: Home settings (SettingsActivity.keyTarget), defaults from R.array.app_keys ("keycode|package", "@apps" for
 * the all-apps grid, "@off" to leave the key to the TV). Other keys pass through.
 */
public final class KeyService extends AccessibilityService {
    static final String ACTION_SHOW_APPS = "dev.salih.tvhome.action.SHOW_APPS";
    private static final String NETFLIX = "com.netflix.ninja";
    private static final int FLAG_REORDER_TO_FRONT = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT;

    private final SparseBooleanArray taken = new SparseBooleanArray();   // keycodes whose DOWN we consumed
    private android.content.SharedPreferences prefs;

    @Override protected void onServiceConnected() { prefs = getSharedPreferences(Sky.PREFS, MODE_PRIVATE); }

    @Override protected boolean onKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code < 4000 || prefs == null) return false;   // TCL's app buttons are 40xx/41xx; everything else passes untouched
        String target = SettingsActivity.keyTarget(prefs, getResources(), code);
        if (target.equals("@off")) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() == 0) taken.put(code, open(target));
            return taken.get(code);
        }
        boolean consumed = taken.get(code);   // keep DOWN and UP together
        taken.delete(code);
        return consumed;
    }

    /** True if the key was handled (the app, its store page or the grid was opened). */
    private boolean open(String target) {
        try {
            if (target.equals("@apps")) {
                startActivity(new Intent(this, MainActivity.class).setAction(ACTION_SHOW_APPS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            }
            PackageManager pm = getPackageManager();
            if (target.equals(NETFLIX) && installed(pm, NETFLIX)) {
                // Netflix's own remote-button entry point, as the manufacturer uses it on non-Google-TV builds
                Intent key = new Intent("com.netflix.action.NETFLIX_KEY_START").setPackage(NETFLIX).putExtra("power_on", false)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | FLAG_REORDER_TO_FRONT);
                try { startActivity(key); return true; } catch (ActivityNotFoundException ignored) { /* older Netflix: plain launch below */ }
            }
            Intent launch = pm.getLeanbackLaunchIntentForPackage(target);
            if (launch == null) launch = pm.getLaunchIntentForPackage(target);
            if (launch != null) {
                startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | FLAG_REORDER_TO_FRONT));
                return true;
            }
            // not installed: its Play Store page, so the button still leads somewhere useful
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;   // let the system have the key
        }
    }

    private static boolean installed(PackageManager pm, String pkg) {
        try { pm.getPackageInfo(pkg, 0); return true; } catch (PackageManager.NameNotFoundException e) { return false; }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
}
