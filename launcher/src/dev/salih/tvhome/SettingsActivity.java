package dev.salih.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Home settings on the TV: which Home and screensaver run, what each remote app button opens, the weather city and
 * unit, and how often the wallpaper changes. Values live in the "home" preferences; the build's config.xml only
 * provides the defaults. Reached from the all-apps grid and by long-pressing the Settings pill.
 */
public final class SettingsActivity extends Activity {
    /** Remote app buttons offered here: TCL key code and its label. */
    private static final int[] KEYS = {4062, 4065, 4099, 4020, 4140};
    private static final int[] KEY_LABELS = {R.string.key_netflix, R.string.key_youtube, R.string.key_prime, R.string.key_t, R.string.key_guard};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private LinearLayout list;
    private TextView status;
    private boolean busy;

    private int dp(float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(Sky.PREFS, MODE_PRIVATE);
        ScrollView scroll = new ScrollView(this); scroll.setBackgroundColor(0xFF0B1020); scroll.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(64), dp(40), dp(64), dp(40));
        scroll.addView(list);
        setContentView(scroll);
        build();
    }

    @Override protected void onResume() { super.onResume(); if (!busy) build(); }

    private void build() {
        int keep = list.getFocusedChild() == null ? -1 : list.indexOfChild(list.getFocusedChild());
        list.removeAllViews();
        TextView title = text(26, true, 0xFFFFFFFF); title.setText(R.string.home_settings); list.addView(title);
        status = text(14, false, 0xFFFFD27A); status.setVisibility(View.GONE);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = dp(8); list.addView(status, sl);

        section(R.string.section_home);
        boolean ourHome = HomeSwitch.ourHome(this), ourDream = HomeSwitch.ourDream(this);
        row(getString(R.string.home_screen), getString(ourHome ? R.string.this_home : R.string.google_home), this::switchHome);
        row(getString(R.string.screensaver), getString(ourDream ? R.string.this_dream : R.string.google_dream), () -> switchDream(!ourDream));
        boolean byUse = prefs.getBoolean(Usage.PREF, false);
        row(getString(R.string.dock_order), getString(byUse ? R.string.dock_by_use : R.string.dock_arranged), () -> {
            if (byUse || Usage.allowed(this)) { prefs.edit().putBoolean(Usage.PREF, !byUse).apply(); build(); }
            else adbJob(() -> Usage.allow(this, prompt), () -> { prefs.edit().putBoolean(Usage.PREF, true).apply(); build(); });
        });

        section(R.string.section_keys);
        for (int i = 0; i < KEYS.length; i++) { final int code = KEYS[i]; row(getString(KEY_LABELS[i]), keyValue(code), () -> pickKeyApp(code)); }

        section(R.string.section_weather);
        String city = Sky.city(this);
        row(getString(R.string.city), city.isEmpty() ? getString(R.string.automatic) : city, this::pickCity);
        row(getString(R.string.temperature), Sky.fahrenheit(this) ? "°F" : "°C", () -> {
            prefs.edit().putString("temp_unit", Sky.fahrenheit(this) ? "C" : "F").remove("wx_at").apply();
            Sky.refresh(this, true, this::build); build();
        });
        int minutes = wallpaperMinutes();
        row(getString(R.string.wallpaper_every), getString(R.string.minutes, minutes), () -> {
            int next = minutes == 15 ? 30 : minutes == 30 ? 60 : 15;
            prefs.edit().putInt("wallpaper_minutes", next).apply(); build();
        });

        section(R.string.section_adguard);
        String agUrl = prefs.getString(Guard.URL, ""), agUser = prefs.getString(Guard.USER, "");
        row(getString(R.string.ag_address), agUrl.isEmpty() ? getString(R.string.ag_not_set) : agUrl.replaceFirst("^https?://", ""), () ->
                askText(R.string.ag_address, "192.168.1.1:3000", false, v -> { prefs.edit().putString(Guard.URL, Guard.normalize(v)).apply(); build(); testGuard(); }));
        if (!agUrl.isEmpty()) {
            row(getString(R.string.ag_user), agUser.isEmpty() ? "—" : agUser, () ->
                    askText(R.string.ag_user, "admin", false, v -> { prefs.edit().putString(Guard.USER, v.trim()).apply(); build(); testGuard(); }));
            row(getString(R.string.ag_password), prefs.getString(Guard.PASS, "").isEmpty() ? "—" : "••••••", () ->
                    askText(R.string.ag_password, "", true, v -> { prefs.edit().putString(Guard.PASS, v).apply(); build(); testGuard(); }));
        }

        View first = keep >= 0 && keep < list.getChildCount() ? list.getChildAt(keep) : null;
        if (first == null || !first.isFocusable()) for (int i = 0; i < list.getChildCount(); i++) if (list.getChildAt(i).isFocusable()) { first = list.getChildAt(i); break; }
        if (first != null) first.requestFocus();
    }

    int wallpaperMinutes() { return prefs.getInt("wallpaper_minutes", getResources().getInteger(R.integer.wallpaper_minutes)); }

    // ---------- home and screensaver (through the TV's own ADB) ----------

    private interface Job { void run() throws Exception; }

    /** Runs an ADB-backed switch off the main thread, with the debugging prompt explained on screen. */
    private void adbJob(Job job, Runnable after) {
        if (busy) return;
        busy = true;
        say(getString(R.string.working));
        new Thread(() -> {
            String error = null;
            try { job.run(); } catch (Exception e) { error = e.getMessage(); }
            String err = error;
            handler.post(() -> {
                busy = false;
                build();
                if (err != null) say(getString(R.string.switch_failed, err)); else if (after != null) after.run();
            });
        }, "home-switch").start();
    }

    private final HomeSwitch.Waiting prompt = () -> handler.post(() -> say(getString(R.string.allow_debugging)));

    private void switchHome() {
        if (HomeSwitch.ourHome(this)) {
            new AlertDialog.Builder(this).setTitle(R.string.google_home).setMessage(R.string.google_home_confirm)
                    .setPositiveButton(R.string.switch_now, (d, w) -> adbJob(() -> HomeSwitch.useGoogleHome(this, prompt), null))
                    .setNegativeButton(android.R.string.cancel, null).show();
        } else {
            adbJob(() -> HomeSwitch.useOurHome(this, prompt), () -> startActivity(HomeSwitch.homeIntent()));
        }
    }

    private void switchDream(boolean ours) { adbJob(() -> HomeSwitch.useOurDream(this, ours, prompt), null); }

    // ---------- remote buttons ----------

    /** "@apps" (all-apps grid), "@off" (left to the TV) or a package. Defaults come from config.xml app_keys. */
    static String keyTarget(SharedPreferences prefs, android.content.res.Resources res, int code) {
        String saved = prefs.getString("key_" + code, null);
        if (saved != null) return saved;
        for (String item : res.getStringArray(R.array.app_keys)) {
            String[] p = item.split("\\|", 2);
            if (p.length == 2 && p[0].trim().equals(String.valueOf(code))) return p[1].trim();
        }
        return "@off";
    }

    private String keyValue(int code) {
        String t = keyTarget(prefs, getResources(), code);
        if (t.equals("@apps")) return getString(R.string.all_apps);
        if (t.equals("@off")) return getString(R.string.off);
        try { return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(t, 0)).toString(); }
        catch (PackageManager.NameNotFoundException e) { return knownName(t) + " " + getString(R.string.not_installed); }
    }

    /** Names for the default targets when the app isn't installed (the system can't label a missing package). */
    private static String knownName(String pkg) {
        switch (pkg) {
            case "com.netflix.ninja": return "Netflix";
            case "com.google.android.youtube.tv": return "YouTube";
            case "com.amazon.amazonvideo.livingroom": return "Prime Video";
            case "com.disney.disneyplus": return "Disney+";
            case "com.apple.atve.androidtv.appletv": return "Apple TV";
            case "com.nuvio.tv": return "Nuvio";
            default: return pkg;
        }
    }

    private void pickKeyApp(int code) {
        List<String> labels = new ArrayList<>(), targets = new ArrayList<>();
        labels.add(getString(R.string.all_apps)); targets.add("@apps");
        labels.add(getString(R.string.off)); targets.add("@off");
        PackageManager pm = getPackageManager();
        List<ResolveInfo> apps = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
        List<String[]> named = new ArrayList<>();
        for (ResolveInfo r : apps) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            named.add(new String[]{r.loadLabel(pm).toString(), pkg});
        }
        Collections.sort(named, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        for (String[] n : named) { labels.add(n[0]); targets.add(n[1]); }
        int current = targets.indexOf(keyTarget(prefs, getResources(), code));   // -1: a missing app, nothing ticked
        new AlertDialog.Builder(this).setTitle(getString(KEY_LABELS[indexOf(code)]))
                .setSingleChoiceItems(labels.toArray(new String[0]), current, (d, which) -> {
                    prefs.edit().putString("key_" + code, targets.get(which)).apply();
                    d.dismiss(); build();
                }).show();
    }

    private static int indexOf(int code) { for (int i = 0; i < KEYS.length; i++) if (KEYS[i] == code) return i; return 0; }

    // ---------- city ----------

    private void pickCity() {
        EditText input = new EditText(this);
        input.setSingleLine(true); input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH); input.setHint(Sky.city(this));   // empty field: the TV keyboard drops selections
        AlertDialog d = new AlertDialog.Builder(this).setTitle(R.string.city).setView(input)
                .setPositiveButton(R.string.search, null).setNegativeButton(android.R.string.cancel, null).create();
        Runnable go = () -> { String q = input.getText().toString(); if (q.trim().length() >= 2) { d.dismiss(); searchCity(q); } };
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> go.run()));
        input.setOnEditorActionListener((v, action, ev) -> { go.run(); return true; });
        d.show();
    }

    private interface Text { void entered(String value); }

    /** One-line text dialog; an empty field (the TV keyboard drops selections), OK or the keyboard's Done saves. */
    private void askText(int title, String hint, boolean password, Text done) {
        EditText input = new EditText(this);
        input.setSingleLine(true); input.setHint(hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | (password ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_URI));
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        AlertDialog d = new AlertDialog.Builder(this).setTitle(title).setView(input)
                .setPositiveButton(android.R.string.ok, null).setNegativeButton(android.R.string.cancel, null).create();
        Runnable go = () -> { d.dismiss(); done.entered(input.getText().toString()); };
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> go.run()));
        input.setOnEditorActionListener((v, action, ev) -> { go.run(); return true; });
        d.show();
    }

    /** Tries AdGuard Home with what is saved and says how it went. */
    private void testGuard() {
        if (!Guard.configured(this) || prefs.getString(Guard.PASS, "").isEmpty()) return;
        Guard.status(this, (s, err) -> toast(err != null ? getString(R.string.ag_failed, err)
                : getString(R.string.ag_ok, s.blocked, s.queries)));
    }

    private void searchCity(String query) {
        toast(getString(R.string.searching));
        new Thread(() -> {
            List<Sky.Place> found;
            try { found = Sky.search(query); } catch (Exception e) { found = null; }
            List<Sky.Place> places = found;
            handler.post(() -> {
                if (places == null) { toast(getString(R.string.no_network)); return; }
                if (places.isEmpty()) { toast(getString(R.string.no_city, query)); return; }
                String[] names = new String[places.size()];
                for (int i = 0; i < names.length; i++) names[i] = places.get(i).toString();
                new AlertDialog.Builder(this).setTitle(R.string.city).setItems(names, (dd, which) -> {
                    Sky.setPlace(this, places.get(which));
                    Sky.refresh(this, true, this::build);
                    build();
                }).show();
            });
        }, "city-search").start();
    }

    // ---------- views ----------

    private void say(String s) { status.setText(s); status.setVisibility(View.VISIBLE); }
    private void toast(String s) { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show(); }

    private TextView text(int sp, boolean bold, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); t.setTextColor(color);
        t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return t;
    }

    private void section(int res) {
        TextView h = text(13, false, 0xB3FFFFFF); h.setText(res);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(28); lp.bottomMargin = dp(10); list.addView(h, lp);
    }

    /** A label on the left, its value in a small pill on the right; focus turns the row white, as in the browser. */
    private void row(String label, String value, Runnable action) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), 0, dp(18), 0); row.setFocusable(true); row.setClickable(true);
        GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(dp(12)); bg.setColor(0x14FFFFFF); row.setBackground(bg);
        TextView l = text(16, false, 0xFFFFFFFF); l.setText(label);
        row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = text(14, false, 0xD9FFFFFF); v.setText(value);
        GradientDrawable vb = new GradientDrawable(); vb.setCornerRadius(dp(14)); vb.setColor(0x2EFFFFFF); v.setBackground(vb);
        v.setPadding(dp(12), dp(5), dp(12), dp(5)); v.setSingleLine(true);
        row.addView(v);
        row.setOnFocusChangeListener((x, f) -> {
            bg.setColor(f ? 0xF2FFFFFF : 0x14FFFFFF);
            l.setTextColor(f ? 0xFF111111 : 0xFFFFFFFF); v.setTextColor(f ? 0xFF111111 : 0xD9FFFFFF);
            vb.setColor(f ? 0x1A000000 : 0x2EFFFFFF);
        });
        row.setOnClickListener(x -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(720), dp(54)); lp.bottomMargin = dp(10);
        list.addView(row, lp);
    }
}
