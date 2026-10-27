package dev.salih.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Settings;

import java.io.IOException;

/**
 * Switches the TV between this Home and Google TV's own, and between this screensaver and Google's, from the TV
 * itself (Home settings and the crash watchdog). Runs only the fixed commands below through the TV's own ADB (Adb);
 * the first use shows the TV's "Allow USB debugging?" prompt once. Blocking: call off the main thread.
 * repairKeys writes the accessibility setting itself, with the WRITE_SECURE_SETTINGS grant useOurHome made.
 */
final class HomeSwitch {
    static final String STOCK_HOME = "com.google.android.apps.tv.launcherx";
    static final String SETUP_HOME = "com.google.android.tungsten.setupwraith";   // recovery home, priority 1
    static final String GOOGLE_DREAM_PKG = "com.google.android.apps.tv.dreamx";
    static final String GOOGLE_DREAM = GOOGLE_DREAM_PKG + "/.service.Backdrop";
    static final String OUR_HOME = "dev.salih.tvhome/.MainActivity";
    static final String OUR_DREAM = "dev.salih.tvhome/.Dream";
    static final String OUR_KEYS = "dev.salih.tvhome/.KeyService";

    /** Shown while the TV waits for the debugging prompt to be accepted. */
    interface Waiting { void waiting(); }

    private HomeSwitch() { }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(Sky.PREFS, Context.MODE_PRIVATE); }

    // ---------- state, read without ADB ----------

    static boolean ourHome(Context c) { return !enabled(c, STOCK_HOME); }

    static boolean ourDream(Context c) {
        return OUR_DREAM.equals(Settings.Secure.getString(c.getContentResolver(), "screensaver_components"));
    }

    private static boolean enabled(Context c, String pkg) {
        try { return c.getPackageManager().getApplicationInfo(pkg, 0).enabled; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }

    // ---------- switching ----------

    /** This Home: Google's homes asleep (Android gives Home to the highest priority otherwise), ours set, keys on. */
    static void useOurHome(Context c, Waiting w) throws IOException {
        try (Adb adb = Adb.connect(c, 60_000, w == null ? null : w::waiting)) {
            adb.shell("pm disable-user --user 0 " + STOCK_HOME);
            adb.shell("pm disable-user --user 0 " + SETUP_HOME);
            expect(adb.shell("cmd package set-home-activity " + OUR_HOME), "Success");
            adb.shell("appops set dev.salih.tvhome APP_AUTO_START allow");   // TCL's auto-start gate (Safety Guard's switch)
            adb.shell("pm grant dev.salih.tvhome " + WRITE_SECURE_SETTINGS);   // lets repairKeys work without ADB
            enableKeys(c);
            Watchdog.clear(c);
        }
    }

    // ---------- the remote's app-button service ----------

    private static final String WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS";

    /**
     * Puts the remote's app-button service (KeyService) back when something turned it off: Settings, an accessibility
     * reset after a firmware update, TCL's Safety Guard. Called each time Home shows; nothing to do while it is on.
     * Uses the WRITE_SECURE_SETTINGS grant useOurHome makes; a TV switched before that grant existed gets it here over
     * the TV's ADB (2 s for a prompt; a key not allowed yet leaves the service off, as before).
     * Blocking when it has work: call off the main thread.
     */
    static void repairKeys(Context c) {
        if (!ourHome(c) || keysOn(c)) return;
        try {
            if (c.checkSelfPermission(WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
                try (Adb adb = Adb.connect(c, 2000, null)) { adb.shell("pm grant dev.salih.tvhome " + WRITE_SECURE_SETTINGS); }
            }
            enableKeys(c);
        } catch (IOException | RuntimeException ignored) {
            // ADB off or the grant refused: Home works, the app buttons stay with the TV until Home settings fixes it
        }
    }

    static boolean keysOn(Context c) {
        return enabledServices(c).contains(KEYS)
                && Settings.Secure.getInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1;
    }

    private static final ComponentName KEYS = ComponentName.unflattenFromString(OUR_KEYS);

    /** Adds KeyService to the enabled accessibility services, keeping the others (TalkBack and so on). */
    private static void enableKeys(Context c) {
        java.util.Set<ComponentName> on = enabledServices(c);
        if (!on.contains(KEYS)) {
            String current = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            String list = current == null || current.isEmpty() ? OUR_KEYS : current + ":" + OUR_KEYS;
            Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list);
        }
        Settings.Secure.putInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
    }

    /** The enabled services as components ("pkg/.Cls" and "pkg/pkg.Cls" are the same service). */
    private static java.util.Set<ComponentName> enabledServices(Context c) {
        java.util.Set<ComponentName> set = new java.util.HashSet<>();
        String v = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (v != null) for (String part : v.split(":")) {
            ComponentName cn = ComponentName.unflattenFromString(part.trim());
            if (cn != null) set.add(cn);
        }
        return set;
    }

    /** Google TV's Home back, exactly as it came; this app stays installed. */
    static void useGoogleHome(Context c, Waiting w) throws IOException {
        try (Adb adb = Adb.connect(c, 60_000, w == null ? null : w::waiting)) {
            adb.shell("pm enable " + SETUP_HOME);
            adb.shell("pm enable " + STOCK_HOME);
            adb.shell("am start -a android.intent.action.MAIN -c android.intent.category.HOME");
        }
    }

    /** This screensaver (and Google's asleep, it holds ~250 MB when it runs), or Google's back. */
    static void useOurDream(Context c, boolean ours, Waiting w) throws IOException {
        try (Adb adb = Adb.connect(c, 60_000, w == null ? null : w::waiting)) {
            if (ours) {
                String current = adb.shell("settings get secure screensaver_components").trim();
                if (!current.equals(OUR_DREAM) && !current.isEmpty() && !current.equals("null"))
                    prefs(c).edit().putString("original_dream", current).apply();
                adb.shell("settings put secure screensaver_components " + OUR_DREAM);
                adb.shell("settings put secure screensaver_enabled 1");
                adb.shell("pm disable-user --user 0 " + GOOGLE_DREAM_PKG);
            } else {
                adb.shell("pm enable " + GOOGLE_DREAM_PKG);
                adb.shell("settings put secure screensaver_components " + originalDream(c));
            }
        }
    }

    /** The watchdog's way out: Google's Home and screensaver back, nothing else touched. */
    static void rescue(Context c) throws IOException {
        try (Adb adb = Adb.connect(c, 10_000, null)) {
            adb.shell("pm enable " + SETUP_HOME);
            adb.shell("pm enable " + STOCK_HOME);
            adb.shell("pm enable " + GOOGLE_DREAM_PKG);
            adb.shell("settings put secure screensaver_components " + originalDream(c));
            adb.shell("am start -a android.intent.action.MAIN -c android.intent.category.HOME");
        }
    }

    /** The screensaver that was set before ours; Google's ambient screen when unknown or no longer installed. */
    private static String originalDream(Context c) {
        String saved = prefs(c).getString("original_dream", "");
        if (!saved.isEmpty() && !saved.equals(OUR_DREAM)) {
            String pkg = saved.substring(0, Math.max(0, saved.indexOf('/')));
            try { c.getPackageManager().getPackageInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS); return saved; }
            catch (PackageManager.NameNotFoundException ignored) { }
        }
        return GOOGLE_DREAM;
    }

    private static void expect(String out, String word) throws IOException {
        if (!out.contains(word)) throw new IOException(out.trim().isEmpty() ? "no reply" : out.trim());
    }

    /** Opens Home (ours or Google's, whichever the system now resolves). */
    static Intent homeIntent() {
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
