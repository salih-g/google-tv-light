package dev.salih.tvhome;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

/**
 * One-screen home: wallpaper, clock, three icon pills (inputs, Wi-Fi, settings) and a scrolling
 * dock of favourite apps. Down opens the all-apps grid with an inputs row; Back returns to the dock.
 * Every key the launcher does not use falls through to the system, so the remote's volume, mute,
 * power, mic, source, settings, channel and app-shortcut buttons keep their native behaviour.
 */
public class MainActivity extends Activity {

    private static final class App {
        final String pkg, label; final ComponentName component; final Drawable banner;
        int accent;   // the banner's most vivid colour, for the dock's glow; 0 until first needed
        App(String pkg, String label, ComponentName component, Drawable banner) {
            this.pkg = pkg; this.label = label; this.component = component; this.banner = banner;
        }
    }

    private static final float FOCUS_SCALE = 1.18f;
    private static final OvershootInterpolator SPRING = new OvershootInterpolator(1.4f);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.US);
    private SimpleDateFormat dateFmt;

    private FrameLayout root, wallLayer, gridPanel;
    private ImageView wallBack, wallFront;
    private TextView clock, greeting, focusedName, wifiLabel, weatherText;
    private ImageView weatherIcon;
    private View weatherPill;
    private int wallpaperMinutes;
    private HorizontalScrollView dockScroll;
    private LinearLayout dock, inputsRow, pills, hint;
    private GridLayout grid;
    private Wallpapers wallpapers;
    private List<App> apps = new ArrayList<>();
    private View lastDockFocus;
    private PopupWindow inputsPopup;
    private View guardPill;   // shown once AdGuard Home is set up in Home settings
    private boolean receiverRegistered;
    private SharedPreferences prefs;
    private View prevFocus;
    private int lastDir = View.FOCUS_RIGHT;

    // ---------- lifecycle ----------

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        dateFmt = new SimpleDateFormat("d MMMM EEEE", getResources().getConfiguration().getLocales().get(0));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        Watchdog.install(this);
        prefs = getSharedPreferences(Sky.PREFS, MODE_PRIVATE);
        Context app = getApplicationContext();
        Wallpapers.sun = t -> Sky.slot(app, t);   // real sunrise/sunset once the weather has been fetched
        buildUi();
        setContentView(root);
        int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
        wallpaperMinutes = wallpaperMinutes();
        wallpapers = new Wallpapers(this, wallBack, wallFront, handler, wallpaperMinutes, w, h);
        loadApps();
        gridOnResume = KeyService.ACTION_SHOW_APPS.equals(getIntent().getAction());
        // First paint: UI fades in over the wallpaper instead of popping.
        for (View v : new View[]{clock, greeting, pills, dockScroll, hint}) { v.setAlpha(0f); v.animate().alpha(1f).setDuration(600).setStartDelay(150).start(); }
    }

    /** Set by the T key (KeyService) so the all-apps grid, not the dock, shows when the launcher comes up. */
    private boolean gridOnResume;

    @Override protected void onResume() {
        super.onResume();
        if (!HomeSwitch.keysOn(this)) new Thread(() -> HomeSwitch.repairKeys(getApplicationContext())).start();
        wallpapers.start();
        handler.post(minuteTick);
        if (!receiverRegistered) {
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_PACKAGE_ADDED); f.addAction(Intent.ACTION_PACKAGE_REMOVED); f.addAction(Intent.ACTION_PACKAGE_CHANGED);
            f.addDataScheme("package");
            registerReceiver(packageReceiver, f); receiverRegistered = true;
        }
        if (wallpaperMinutes != wallpaperMinutes()) {   // changed in Home settings
            wallpapers.stop(); wallpaperMinutes = wallpaperMinutes();
            int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
            wallpapers = new Wallpapers(this, wallBack, wallFront, handler, wallpaperMinutes, w, h);
            wallpapers.start();
        }
        updateWeather();
        Sky.refresh(this, false, this::updateWeather);
        guardPill.setVisibility(Guard.configured(this) ? View.VISIBLE : View.GONE);
        loadApps(); // picks up installs that happened while another app was in front
        if (prefs.getBoolean(Usage.PREF, false)) {
            Context app = getApplicationContext();
            new Thread(() -> {
                Map<String, Integer> fresh = Usage.opens(app);
                handler.post(() -> { if (!fresh.equals(opens)) { opens = fresh; fillDock(); } });
            }).start();
        }
        if (gridOnResume) { gridOnResume = false; showGrid(); } else showDock(false);
    }

    @Override protected void onPause() {
        super.onPause();
        stopMoving();
        wallpapers.stop();
        handler.removeCallbacks(minuteTick);
        if (receiverRegistered) { unregisterReceiver(packageReceiver); receiverRegistered = false; }
        dismissCards();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // onResume follows and decides what shows: the grid for the T key, otherwise the dock (Home pressed on top)
        gridOnResume = KeyService.ACTION_SHOW_APPS.equals(intent.getAction());
    }

    /**
     * The TV's own navigation sound. ViewRootImpl plays it for key-driven focus moves, except inside
     * scrolling containers (the dock and the grid), which move focus themselves silently; this fills
     * that gap only for moves between siblings, so cross-container moves don't sound twice.
     */
    private void navSound(View v) {
        if (prevFocus != null && prevFocus.getParent() == v.getParent()) v.playSoundEffect(SoundEffectConstants.getContantForFocusDirection(lastDir));
        prevFocus = v;
    }

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { loadApps(); }
    };

    private final Runnable minuteTick = new Runnable() {
        @Override public void run() {
            updateClock();
            wallpapers.onMinute();
            Sky.refresh(MainActivity.this, false, MainActivity.this::updateWeather);
            long now = System.currentTimeMillis();
            handler.postDelayed(this, 60_000 - now % 60_000 + 50);
        }
    };

    /** Very slow zoom on the wallpaper layer (both frames), the way Apple TV screensavers breathe. */

    // ---------- keys ----------

    /** Move mode takes the arrows, OK and Back before the focused tile does (OK would open the app). */
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (moving != null && gridPanel.getVisibility() == View.VISIBLE) {
            int code = e.getKeyCode();
            boolean ours = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_UP
                    || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                    || code == KeyEvent.KEYCODE_BACK;
            if (ours) {
                if (e.getAction() == KeyEvent.ACTION_DOWN) moveStep(code);
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_MENU:
                showInputs(null);
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (dismissCards()) return true;
                if (gridPanel.getVisibility() == View.VISIBLE) { showDock(true); return true; }
                return true; // Back on the home screen does nothing (never falls into the stock launcher)
            case KeyEvent.KEYCODE_DPAD_DOWN:
                lastDir = View.FOCUS_DOWN;
                if (gridPanel.getVisibility() != View.VISIBLE && dock.hasFocus()) { showGrid(); return true; }
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                lastDir = View.FOCUS_UP;
                if (gridPanel.getVisibility() == View.VISIBLE && isOnFirstGridRow()) { showDock(true); return true; }
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
                lastDir = code == KeyEvent.KEYCODE_DPAD_LEFT ? View.FOCUS_LEFT : View.FOCUS_RIGHT;
                tilt(getCurrentFocus(), code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1, e.getRepeatCount() > 0);
                break;
        }
        return super.onKeyDown(code, e); // volume, mute, power, mic, source, settings, channel, app shortcuts: system handles
    }

    @Override public boolean onKeyUp(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) tilt(getCurrentFocus(), 0, false);
        return super.onKeyUp(code, e);
    }

    /** Apple TV parallax: the focused tile leans a few degrees toward the direction being pressed. */
    private void tilt(View v, int dir, boolean held) {
        if (v == null || !(v.getTag() instanceof App)) return;
        v.animate().rotationY(dir * (held ? 7f : 4f)).setDuration(dir == 0 ? 260 : 120)
                .setInterpolator(dir == 0 ? SPRING : new DecelerateInterpolator()).start();
    }

    private boolean isOnFirstGridRow() {
        View f = grid.findFocus();
        if (f == null) return false;
        int idx = grid.indexOfChild(f);
        return idx >= 0 && idx < grid.getColumnCount();
    }

    // ---------- UI construction ----------

    private int dp(float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        wallLayer = new FrameLayout(this);
        wallBack = new ImageView(this); wallFront = new ImageView(this);
        for (ImageView v : new ImageView[]{wallBack, wallFront}) {
            v.setScaleType(ImageView.ScaleType.CENTER_CROP); v.setVisibility(View.INVISIBLE);
            wallLayer.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        root.addView(wallLayer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // Scrims keep text readable on bright frames: a soft band at the top, a stronger one behind the dock.
        View top = new View(this);
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x66000000, 0x00000000}));
        root.addView(top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(140), Gravity.TOP));
        View bottom = new View(this);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0x80000000, 0xCC000000}));
        root.addView(bottom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(280), Gravity.BOTTOM));
        // Ambient glow: a soft light from below in the focused app's colour.
        glowShape = new GradientDrawable(); glowShape.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowShape.setGradientCenter(0.5f, 1f); glowShape.setGradientRadius(dp(340)); glowShape.setColors(new int[]{0, 0});
        View glow = new View(this); glow.setBackground(glowShape);
        root.addView(glow, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(340), Gravity.BOTTOM));

        // Top-left: clock, greeting + date
        LinearLayout topLeft = new LinearLayout(this); topLeft.setOrientation(LinearLayout.VERTICAL);
        clock = text(44, true, 0xFFFFFFFF); greeting = text(15, false, 0xE6FFFFFF);
        topLeft.addView(clock); topLeft.addView(greeting);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        tl.setMargins(dp(48), dp(32), 0, 0);
        root.addView(topLeft, tl);

        // Top-right: icon pills that reveal their label on focus
        pills = new LinearLayout(this); pills.setOrientation(LinearLayout.HORIZONTAL); pills.setClipChildren(false);
        View inputsPill = pill(R.drawable.ic_hdmi, getString(R.string.inputs));
        inputsPill.setOnClickListener(v -> showInputs(v));
        View wifiPill = pill(R.drawable.ic_wifi, getString(R.string.wifi)); wifiLabel = (TextView) ((LinearLayout) wifiPill).getChildAt(1);
        wifiPill.setOnClickListener(v -> launch(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        View settingsPill = pill(R.drawable.ic_settings, getString(R.string.settings));
        settingsPill.setOnClickListener(v -> launch(new Intent(Settings.ACTION_SETTINGS)));
        settingsPill.setOnLongClickListener(v -> { launch(new Intent(this, SettingsActivity.class)); return true; });
        weatherPill = weatherPill();
        guardPill = pill(R.drawable.ic_shield, "AdGuard");
        guardPill.setOnClickListener(this::showGuard);
        guardPill.setVisibility(Guard.configured(this) ? View.VISIBLE : View.GONE);
        for (View p : new View[]{weatherPill, guardPill, inputsPill, wifiPill, settingsPill}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
            lp.setMarginStart(dp(8)); pills.addView(p, lp);
        }
        FrameLayout.LayoutParams pr = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        pr.setMargins(0, dp(38), dp(48), 0);
        root.addView(pills, pr);

        // Focused app name above the dock
        focusedName = text(18, true, 0xFFFFFFFF);
        focusedName.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams fn = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        fn.setMargins(0, 0, 0, dp(158));
        root.addView(focusedName, fn);

        // Dock: horizontal scroller, focused tile is scrolled to the centre
        dockScroll = new HorizontalScrollView(this);
        dockScroll.setHorizontalScrollBarEnabled(false); dockScroll.setFillViewport(true);
        dockScroll.setClipChildren(false); dockScroll.setClipToPadding(false);
        dockScroll.setFocusable(false); dockScroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        dock = new LinearLayout(this); dock.setOrientation(LinearLayout.HORIZONTAL); dock.setGravity(Gravity.CENTER);
        dock.setClipChildren(false); dock.setClipToPadding(false);
        dock.setPadding(dp(48), dp(24), dp(48), dp(24));
        // The dock sits on a frame with the glass slab behind it, so the slab scrolls with the tiles.
        FrameLayout dockFrame = new FrameLayout(this); dockFrame.setClipChildren(false); dockFrame.setClipToPadding(false);
        glass = new View(this);
        GradientDrawable gd = new GradientDrawable(); gd.setCornerRadius(dp(14)); gd.setColor(0x26FFFFFF); gd.setStroke(Math.max(1, dp(1)), 0x4DFFFFFF);
        glass.setBackground(gd); glass.setAlpha(0f);
        dockFrame.addView(glass, new FrameLayout.LayoutParams(0, 0));
        dockFrame.addView(dock, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));   // centred while it fits
        dockScroll.addView(dockFrame, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((old, now) -> dockFocus(now));
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        dl.setMargins(0, 0, 0, dp(40));
        root.addView(dockScroll, dl);

        hint = new LinearLayout(this); hint.setOrientation(LinearLayout.HORIZONTAL); hint.setGravity(Gravity.CENTER);
        ImageView hi = new ImageView(this); hi.setImageResource(R.drawable.ic_apps); hi.setImageTintList(ColorStateList.valueOf(0x99FFFFFF));
        hint.addView(hi, new LinearLayout.LayoutParams(dp(14), dp(14)));
        TextView ht = text(12, false, 0x99FFFFFF); ht.setText(getString(R.string.all_apps)); ht.setPadding(dp(6), 0, 0, 0);
        hint.addView(ht);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        hl.setMargins(0, 0, 0, dp(16));
        root.addView(hint, hl);

        // All-apps panel (hidden until Down)
        gridPanel = new FrameLayout(this);
        gridPanel.setBackgroundColor(0xD90B1020);
        gridPanel.setVisibility(View.GONE);
        ScrollView scroll = new ScrollView(this); scroll.setClipChildren(false); scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(48), dp(40), dp(48), dp(48)); column.setClipChildren(false); column.setClipToPadding(false);
        gridTitle = text(13, false, 0xB3FFFFFF); gridTitle.setText(getString(R.string.apps)); column.addView(gridTitle);
        grid = new GridLayout(this); grid.setColumnCount(getResources().getInteger(R.integer.columns));
        grid.setClipChildren(false); grid.setClipToPadding(false);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gl.topMargin = dp(12); column.addView(grid, gl);
        TextView h2 = text(13, false, 0xB3FFFFFF); h2.setText(getString(R.string.inputs));
        LinearLayout.LayoutParams h2l = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        h2l.topMargin = dp(28); column.addView(h2, h2l);
        inputsRow = new LinearLayout(this); inputsRow.setOrientation(LinearLayout.HORIZONTAL);
        inputsRow.setClipChildren(false); inputsRow.setClipToPadding(false);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        il.topMargin = dp(12); column.addView(inputsRow, il);
        scroll.addView(column);
        gridPanel.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(gridPanel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        updateClock();
    }

    private TextView text(int sp, boolean bold, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif" + (bold ? "-medium" : ""), Typeface.NORMAL));
        t.setShadowLayer(8f, 0f, 2f, 0x80000000);
        return t;
    }

    /** Dark frosted pill with an icon; the label slides open when focused, the pill turns white. */
    private View pill(int iconRes, String label) {
        LinearLayout p = new LinearLayout(this); p.setOrientation(LinearLayout.HORIZONTAL); p.setGravity(Gravity.CENTER_VERTICAL);
        p.setPadding(dp(10), 0, dp(10), 0);
        ImageView icon = new ImageView(this); if (iconRes != 0) icon.setImageResource(iconRes); icon.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
        p.addView(icon, new LinearLayout.LayoutParams(dp(18), dp(18)));
        TextView t = text(13, false, 0xFF111111); t.setText(label); t.setShadowLayer(0, 0, 0, 0);
        t.setPadding(dp(8), 0, 0, 0); t.setVisibility(View.GONE);
        p.addView(t);
        p.setFocusable(true); p.setClickable(true);
        GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(dp(17)); bg.setColor(0x8C0A0E1E);
        p.setBackground(bg);
        p.setOnFocusChangeListener((v, f) -> {
            if (f) prevFocus = v; // ViewRootImpl already plays the sound for pill moves
            bg.setColor(f ? 0xF2FFFFFF : 0x8C0A0E1E);
            icon.setImageTintList(ColorStateList.valueOf(f ? 0xFF111111 : 0xFFFFFFFF));
            if (f) { t.setAlpha(0f); t.setVisibility(View.VISIBLE); t.animate().alpha(1f).setDuration(180).start(); }
            else t.setVisibility(View.GONE);
            v.animate().scaleX(f ? 1.06f : 1f).scaleY(f ? 1.06f : 1f).setDuration(200).setInterpolator(SPRING).start();
        });
        return p;
    }

    // ---------- apps ----------

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Set<String> hidden = new HashSet<>(Arrays.asList(getResources().getStringArray(R.array.hidden_apps)));
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<App> list = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            String pkg = ri.activityInfo.packageName;
            boolean ourSettings = ri.activityInfo.name.equals(SettingsActivity.class.getName());
            if ((hidden.contains(pkg) && !ourSettings) || !seen.add(pkg)) continue;
            Drawable banner = ri.activityInfo.loadBanner(pm);
            if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            if (banner == null) banner = ri.loadIcon(pm);
            list.add(new App(pkg, String.valueOf(ri.loadLabel(pm)), new ComponentName(pkg, ri.activityInfo.name), banner));
        }
        Collections.sort(list, (a, b) -> a.label.compareToIgnoreCase(b.label));
        // The owner's grid order (move mode) first; apps installed since then follow, alphabetically.
        List<String> order = Arrays.asList(prefs.getString("grid_order", "").split(","));
        Collections.sort(list, (a, b) -> {
            int i = order.indexOf(a.pkg), j = order.indexOf(b.pkg);
            return i < 0 && j < 0 ? 0 : i < 0 ? 1 : j < 0 ? -1 : i - j;   // stable: unknown ones keep A-Z
        });
        apps = list;
        fillDock(); fillGrid(); fillInputsRow();
    }

    private App find(String pkg) { for (App a : apps) if (a.pkg.equals(pkg)) return a; return null; }

    /** Dock order: what the owner arranged on the TV, else the list in config.xml. */
    private List<String> dockPackages() {
        String saved = prefs.getString("dock", null);
        if (saved != null) return new ArrayList<>(Arrays.asList(saved.split(",")));
        return new ArrayList<>(Arrays.asList(getResources().getStringArray(R.array.dock_apps)));
    }

    private void saveDock(List<String> pkgs) { prefs.edit().putString("dock", String.join(",", pkgs)).apply(); }

    private void fillDock() {
        fillDock(lastDockFocus != null && lastDockFocus.getTag() instanceof App ? ((App) lastDockFocus.getTag()).pkg : null);
    }

    /** Opens per app over the last 30 days (Usage), when the dock is ordered by use; empty otherwise. */
    private Map<String, Integer> opens = new HashMap<>();

    private boolean dockByUse() { return prefs.getBoolean(Usage.PREF, false); }

    private void fillDock(String focusedPkg) {
        dock.removeAllViews(); lastDockFocus = null;
        List<App> order = new ArrayList<>();
        for (String pkg : dockPackages()) { App a = find(pkg); if (a != null) order.add(a); }
        if (order.isEmpty()) order = apps.subList(0, Math.min(7, apps.size())); // nothing configured is installed
        else if (dockByUse()) Collections.sort(order, (x, y) -> opens.getOrDefault(y.pkg, 0) - opens.getOrDefault(x.pkg, 0)); // stable: ties keep the arranged order
        int w = dp(112), h = dp(63);
        for (App a : order) {
            View tile = tile(a, w, h);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h); lp.setMargins(dp(7), 0, dp(7), 0);
            dock.addView(tile, lp);
            if (lastDockFocus == null || a.pkg.equals(focusedPkg)) lastDockFocus = tile;
        }
    }

    private void fillGrid() {
        grid.removeAllViews();
        int cols = grid.getColumnCount();
        int cell = (getResources().getDisplayMetrics().widthPixels - dp(96) - dp(18) * cols) / cols;
        for (App a : apps) {
            View tile = tile(a, cell, cell * 9 / 16);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(); lp.width = cell; lp.height = cell * 9 / 16; lp.setMargins(dp(9), dp(9), dp(9), dp(9));
            grid.addView(tile, lp);
        }
    }

    /** 16:9 rounded tile with the app banner. Focus = spring scale + soft shadow, no ring. */
    private View tile(App a, int w, int h) {
        FrameLayout card = new FrameLayout(this);
        card.setTag(a); card.setFocusable(true); card.setClickable(true);
        ImageView img = new ImageView(this); img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setImageDrawable(a.banner); img.setBackgroundColor(0xFF1C2238);
        card.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(8)); }
        });
        card.setClipToOutline(true);
        card.setCameraDistance(8000 * getResources().getDisplayMetrics().density);
        card.setOnFocusChangeListener((v, f) -> {
            v.animate().scaleX(f ? FOCUS_SCALE : 1f).scaleY(f ? FOCUS_SCALE : 1f).rotationY(0f)
                    .setDuration(f ? 260 : 180).setInterpolator(f ? SPRING : new DecelerateInterpolator()).start();
            ObjectAnimator.ofFloat(v, "elevation", f ? dp(14) : 0f).setDuration(200).start();
            if (f) navSound(v);
            if (f && v.getParent() == dock) { // the grid shows no floating name
                setFocusedName(a.label);
                lastDockFocus = v; centerInDock(v);
            }
        });
        card.setOnLongClickListener(v -> { showTileMenu(v, a); return true; });
        card.setOnClickListener(v -> {
            // Apple TV press: quick dip then launch
            v.animate().scaleX(FOCUS_SCALE * 0.94f).scaleY(FOCUS_SCALE * 0.94f).setDuration(70).withEndAction(() ->
                    v.animate().scaleX(FOCUS_SCALE).scaleY(FOCUS_SCALE).setDuration(120).start()).start();
            launch(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setComponent(a.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        });
        return card;
    }

    /** Long press on a tile: rearrange the dock (move / remove) or add a grid app to it. */
    private void showTileMenu(View tile, App a) {
        boolean inDock = tile.getParent() == dock;
        List<String> pkgs = dockPackages();
        int idx = pkgs.indexOf(a.pkg);
        LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(dp(16)); bg.setColor(0xF2151B2E);
        list.setBackground(bg);
        PopupWindow[] popup = new PopupWindow[1];
        Runnable[] actions; String[] labels;
        if (inDock && dockByUse()) {   // the order follows use; only removing makes sense here
            labels = new String[]{getString(R.string.remove_from_dock)};
            actions = new Runnable[]{() -> { pkgs.remove(a.pkg); saveDock(pkgs); fillDock(null); }};
        } else if (inDock) {
            labels = new String[]{getString(R.string.move_left), getString(R.string.move_right), getString(R.string.remove_from_dock)};
            actions = new Runnable[]{
                () -> { if (idx > 0) { Collections.swap(pkgs, idx, idx - 1); saveDock(pkgs); fillDock(a.pkg); } },
                () -> { if (idx >= 0 && idx < pkgs.size() - 1) { Collections.swap(pkgs, idx, idx + 1); saveDock(pkgs); fillDock(a.pkg); } },
                () -> { pkgs.remove(a.pkg); saveDock(pkgs); fillDock(null); }};
        } else {
            boolean present = idx >= 0;
            labels = new String[]{getString(R.string.move), getString(present ? R.string.remove_from_dock : R.string.add_to_dock)};
            actions = new Runnable[]{() -> startMoving(a),
                    () -> { if (present) pkgs.remove(a.pkg); else pkgs.add(a.pkg); saveDock(pkgs); fillDock(null); }};
        }
        for (int i = 0; i < labels.length; i++) {
            final Runnable act = actions[i];
            boolean move = !inDock && i == 0;
            View p = inputPill(new Input(labels[i], 0, Input.OTHER, Input.NONE, () -> { popup[0].dismiss(); act.run(); if (!move) showDock(false); }));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36));
            lp.setMargins(0, dp(3), 0, dp(3)); list.addView(p, lp);
        }
        popup[0] = new PopupWindow(list, dp(230), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup[0].setElevation(dp(16)); popup[0].setAnimationStyle(android.R.style.Animation_Dialog);
        if (inDock) { // the dock sits at the bottom edge: open the menu above the tile
            list.measure(View.MeasureSpec.makeMeasureSpec(dp(230), View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
            popup[0].showAsDropDown(tile, 0, -tile.getHeight() - list.getMeasuredHeight() - dp(40));
        } else popup[0].showAsDropDown(tile, 0, -dp(8));
        for (int i = 0; i < list.getChildCount(); i++) if (list.getChildAt(i).isFocusable()) { list.getChildAt(i).requestFocus(); break; }
    }

    private void setFocusedName(String name) {
        if (name.equals(String.valueOf(focusedName.getText())) && focusedName.getAlpha() == 1f) return;
        focusedName.animate().cancel();
        focusedName.setAlpha(0f); focusedName.setTranslationY(dp(6)); focusedName.setText(name);
        focusedName.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(new DecelerateInterpolator()).start();
    }

    // ---------- grid move mode ----------

    private TextView gridTitle;
    private App moving;   // the app being moved in the grid, null outside move mode

    /** Move mode: the arrows carry the app through the grid, OK or Back drops it there and saves the order. */
    private void startMoving(App a) {
        moving = a;
        gridTitle.setText(getString(R.string.moving, a.label));
        focusGridApp(a);
    }

    private void stopMoving() {
        if (moving == null) return;
        App a = moving; moving = null;
        List<String> order = new ArrayList<>();
        for (App x : apps) order.add(x.pkg);
        prefs.edit().putString("grid_order", String.join(",", order)).apply();
        gridTitle.setText(getString(R.string.apps));
        focusGridApp(a);
    }

    /** One step in move mode. */
    private void moveStep(int code) {
        int i = apps.indexOf(moving), cols = grid.getColumnCount(), to;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: to = i - 1; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: to = i + 1; break;
            case KeyEvent.KEYCODE_DPAD_UP: to = i - cols; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: to = i + cols; break;
            default: stopMoving(); return;   // OK, Back
        }
        if (i < 0 || to < 0 || to >= apps.size()) return;   // edge: stay put
        List<App> list = new ArrayList<>(apps);
        list.remove(i); list.add(to, moving);
        apps = list;
        fillGrid();
        focusGridApp(moving);
    }

    /** Focuses an app's grid tile; in move mode it is lifted with a white outline. */
    private void focusGridApp(App a) {
        for (int k = 0; k < grid.getChildCount(); k++) {
            View t = grid.getChildAt(k);
            boolean lifted = moving != null && t.getTag() == moving;
            t.setForeground(lifted ? movingOutline() : null);
            if (t.getTag() == a) { prevFocus = null; t.requestFocus(); }
        }
    }

    private Drawable movingOutline() {
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(dp(8)); g.setStroke(dp(3), 0xFFFFFFFF); g.setColor(0x1AFFFFFF);
        return g;
    }

    // ---------- glass slab and glow ----------

    private View glass;
    private GradientDrawable glowShape;
    private int glowColor;
    private ValueAnimator glowAnim;

    /** The glass slab slides to the focused dock tile and the glow takes its colour; both fade when focus leaves the dock. */
    private void dockFocus(View now) {
        boolean inDock = now != null && now.getParent() == dock && now.getTag() instanceof App;
        if (!inDock) {
            glass.animate().alpha(0f).setDuration(160).start();
            setGlow(0);
            return;
        }
        int pad = dp(6);
        int w = Math.round(now.getWidth() * FOCUS_SCALE) + 2 * pad, h = Math.round(now.getHeight() * FOCUS_SCALE) + 2 * pad;
        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != w || lp.height != h) { lp.width = w; lp.height = h; glass.setLayoutParams(lp); }
        float x = dock.getLeft() + now.getLeft() + now.getWidth() / 2f - w / 2f;
        float y = dock.getTop() + now.getTop() + now.getHeight() / 2f - h / 2f;
        if (glass.getAlpha() == 0f) { glass.setTranslationX(x); glass.setTranslationY(y); }   // appear in place, don't fly in
        glass.animate().translationX(x).translationY(y).alpha(1f).setDuration(280).setInterpolator(SPRING).start();
        int[] at = new int[2]; now.getLocationInWindow(at);
        float cx = (at[0] + now.getWidth() / 2f) / Math.max(1, root.getWidth());
        glowShape.setGradientCenter(Math.max(0.15f, Math.min(0.85f, cx)), 1f);   // the light comes from under the focused tile
        setGlow(accent((App) now.getTag()));
    }

    private void setGlow(int color) {
        if (color == glowColor) return;
        if (glowAnim != null) glowAnim.cancel();
        glowAnim = ValueAnimator.ofArgb(glowColor, color);
        glowAnim.setDuration(420);
        glowAnim.addUpdateListener(a -> {
            glowColor = (int) a.getAnimatedValue();
            glowShape.setColors(new int[]{glowColor, glowColor & 0x00FFFFFF});
        });
        glowAnim.start();
    }

    /** The banner's most vivid colour, brightened and made translucent for the glow (white banners give their logo's). */
    private static int accent(App a) {
        if (a.accent != 0) return a.accent;
        int color = 0xFF6E7FD8;   // a banner with no colour at all: a calm blue
        try {
            Bitmap b = Bitmap.createBitmap(24, 14, Bitmap.Config.ARGB_8888);
            Drawable d = a.banner.getConstantState() != null ? a.banner.getConstantState().newDrawable().mutate() : a.banner;
            d.setBounds(0, 0, 24, 14); d.draw(new Canvas(b));
            float[] hsv = new float[3]; float best = 0.2f;
            for (int y = 0; y < 14; y++) for (int x = 0; x < 24; x++) {
                int px = b.getPixel(x, y);
                if (Color.alpha(px) < 128) continue;
                Color.colorToHSV(px, hsv);
                float score = hsv[1] * hsv[2];
                if (score > best) { best = score; color = px; }
            }
            b.recycle();
        } catch (RuntimeException ignored) { }
        float[] hsv = new float[3]; Color.colorToHSV(color, hsv);
        hsv[2] = Math.max(hsv[2], 0.85f);
        a.accent = (Color.HSVToColor(hsv) & 0x00FFFFFF) | 0x52000000;
        return a.accent;
    }

    private void centerInDock(View tile) {
        int target = tile.getLeft() + tile.getWidth() / 2 - dockScroll.getWidth() / 2;
        dockScroll.smoothScrollTo(Math.max(0, target), 0);
    }

    private void launch(Intent i) {
        try { startActivity(i); } catch (RuntimeException e) { setFocusedName(getString(R.string.could_not_open)); }
    }

    // ---------- dock / grid switching ----------

    private void showDock(boolean animate) {
        if (gridPanel.getVisibility() == View.VISIBLE && animate) {
            gridPanel.animate().alpha(0f).translationY(dp(40)).setDuration(220).setInterpolator(new AccelerateDecelerateInterpolator())
                    .withEndAction(() -> gridPanel.setVisibility(View.GONE)).start();
        } else gridPanel.setVisibility(View.GONE);
        for (View v : new View[]{dockScroll, focusedName, hint, pills, clock, greeting}) {
            v.setVisibility(View.VISIBLE);
            if (animate) v.animate().alpha(1f).setDuration(220).start(); else v.setAlpha(1f);
        }
        prevFocus = null; // programmatic move, no sound
        View f = lastDockFocus != null ? lastDockFocus : dock.getChildAt(0);
        if (f != null) f.requestFocus();
    }

    private void showGrid() {
        gridPanel.setAlpha(0f); gridPanel.setTranslationY(dp(40)); gridPanel.setVisibility(View.VISIBLE);
        gridPanel.animate().alpha(1f).translationY(0f).setDuration(280).setInterpolator(new DecelerateInterpolator(1.8f)).start();
        // everything of the home layer goes, the clock and greeting too: the grid's backdrop is translucent
        for (View v : new View[]{focusedName, hint, pills, clock, greeting}) v.animate().alpha(0f).setDuration(160).start();
        prevFocus = null; // programmatic move, no sound
        if (grid.getChildCount() > 0) grid.getChildAt(0).requestFocus();
        dockScroll.animate().alpha(0f).setDuration(160).withEndAction(() -> dockScroll.setVisibility(View.INVISIBLE)).start();
    }

    // ---------- inputs ----------

    /**
     * kind: HDMI, another passthrough port (AV), or the Live TV app. power: ON/OFF for a device the TV knows by HDMI-CEC,
     * PLUGGED for a port with something in it but no power info, EMPTY for a port with nothing in it (or AV, which has
     * no hotplug detection), NONE for Live TV.
     */
    private static final class Input {
        static final int HDMI = 0, OTHER = 1, LIVE = 2;
        static final int NONE = 0, EMPTY = 1, PLUGGED = 2, OFF = 3, ON = 4;
        final String label; final int icon, kind, power; final Runnable open;
        Input(String l, int i, int k, int p, Runnable o) { label = l; icon = i; kind = k; power = p; open = o; }
        boolean device() { return power >= PLUGGED; }
    }

    /**
     * Devices first (on, then off), then the empty ports, then Live TV. The public input state can't tell a sleeping
     * device from an empty port on this TCL (both CONNECTED_STANDBY on the port, and the CEC device's own input stays
     * CONNECTED while it sleeps), so a CEC device's power comes from Cec; a port with a CEC device behind it shows the
     * device (its input switches to the port and wakes it) instead of the bare port.
     */
    private List<Input> inputs() {
        List<Input> list = new ArrayList<>();
        String liveTv = getString(R.string.live_tv_package);
        Intent live = getPackageManager().getLeanbackLaunchIntentForPackage(liveTv);
        if (live == null) live = getPackageManager().getLaunchIntentForPackage(liveTv);
        final boolean hasTvApp = live != null;
        TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        List<TvInputInfo> all = new ArrayList<>();
        if (tim != null) for (TvInputInfo t : tim.getTvInputList()) if (t.isPassthroughInput()) all.add(t);
        java.util.Set<String> parents = new java.util.HashSet<>();
        int children = 0;
        for (TvInputInfo t : all) if (t.getParentId() != null) { parents.add(t.getParentId()); children++; }
        for (TvInputInfo t : all) {
            final String id = t.getId();
            if (parents.contains(id)) continue;   // its CEC device stands for it
            CharSequence custom = t.loadCustomLabel(this), name = t.loadLabel(this); // custom = the name the owner gave the input (e.g. "PlayStation 5")
            String label = custom != null && custom.length() > 0 ? custom.toString() : name == null ? id : name.toString();
            boolean isHdmi = t.getType() == TvInputInfo.TYPE_HDMI;
            int power;
            if (t.getParentId() != null) {
                Cec.Device d = Cec.byName(name == null ? null : name.toString());
                if (d == null && children == 1) d = Cec.only();
                power = d == null ? Input.PLUGGED : d.on() ? Input.ON : Input.OFF;
            } else {
                int state;
                try { state = isHdmi ? tim.getInputState(id) : -1; } catch (RuntimeException e) { state = -1; }
                power = state == TvInputManager.INPUT_STATE_CONNECTED ? Input.PLUGGED : Input.EMPTY;
            }
            list.add(new Input(label, isHdmi ? R.drawable.ic_hdmi : R.drawable.ic_av, isHdmi ? Input.HDMI : Input.OTHER, power, () -> {
                Intent i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id));
                if (hasTvApp) i.setPackage(liveTv); // the TV app renders passthrough inputs
                try { startActivity(i); } catch (RuntimeException e) {
                    Intent picker = getPackageManager().getLaunchIntentForPackage(getString(R.string.input_picker_package));
                    if (picker != null) launch(picker);
                }
            }));
        }
        // Stable sort: devices on, then off/plugged, then empty ports; HDMI before AV within each, in the TV's order.
        java.util.Collections.sort(list, (a, b) -> a.power != b.power ? b.power - a.power : a.kind - b.kind);
        if (hasTvApp) { final Intent li = live; list.add(new Input(getString(R.string.live_tv), R.drawable.ic_live_tv, Input.LIVE, Input.NONE, () -> launch(li))); }
        return list;
    }

    /** A row of the Inputs drop-down: icon, name, and for a device a green (on) or red (off) dot with its state. */
    private View inputRow(int icon, String label, int power, String extra) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), 0, dp(14), 0); row.setFocusable(true); row.setClickable(true);
        GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(dp(12)); bg.setColor(0x00000000); row.setBackground(bg);
        ImageView iv = new ImageView(this); iv.setImageResource(icon); iv.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
        row.addView(iv, new LinearLayout.LayoutParams(dp(18), dp(18)));
        TextView name = text(14, false, 0xFFFFFFFF); name.setShadowLayer(0, 0, 0, 0); name.setText(label); name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END); name.setPadding(dp(12), 0, dp(8), 0);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView status = null;
        if (power >= Input.PLUGGED) {
            View dot = new View(this);
            GradientDrawable d = new GradientDrawable(); d.setShape(GradientDrawable.OVAL);
            d.setColor(power == Input.OFF ? 0xFFFF453A : 0xFF34C759); dot.setBackground(d);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(7), dp(7)); dl.setMarginEnd(dp(6)); row.addView(dot, dl);
        }
        if (power >= Input.PLUGGED || extra != null) {
            status = text(11, false, 0x99FFFFFF); status.setShadowLayer(0, 0, 0, 0);
            status.setText(extra != null ? extra : getString(power == Input.ON ? R.string.input_on : power == Input.OFF ? R.string.input_off : R.string.input_connected));
            row.addView(status);
        }
        final TextView st = status;
        row.setOnFocusChangeListener((v, f) -> {
            bg.setColor(f ? 0xF2FFFFFF : 0x00000000);
            iv.setImageTintList(ColorStateList.valueOf(f ? 0xFF111111 : 0xFFFFFFFF));
            name.setTextColor(f ? 0xFF111111 : 0xFFFFFFFF);
            if (st != null) st.setTextColor(f ? 0x99111111 : 0x99FFFFFF);
        });
        return row;
    }

    private View separator() {
        View v = new View(this); v.setBackgroundColor(0x26FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2));
        lp.setMargins(dp(14), dp(6), dp(14), dp(6)); v.setLayoutParams(lp);
        return v;
    }

    /** Pill variant whose label is always visible, for the inputs row and popups. */
    private View inputPill(Input in) {
        View p = pill(in.icon, in.label);
        if (in.icon == 0) ((LinearLayout) p).getChildAt(0).setVisibility(View.GONE);
        TextView t = (TextView) ((LinearLayout) p).getChildAt(1);
        t.setVisibility(View.VISIBLE); t.setTextColor(0xFFFFFFFF);
        View.OnFocusChangeListener base = p.getOnFocusChangeListener();
        p.setOnFocusChangeListener((v, f) -> { base.onFocusChange(v, f); t.setVisibility(View.VISIBLE); t.setAlpha(1f); t.setTextColor(f ? 0xFF111111 : 0xFFFFFFFF); });
        p.setOnClickListener(v -> in.open.run());
        return p;
    }

    /** The grid's Inputs row: devices and Live TV; empty ports stay in the drop-down's "Other inputs". */
    private void fillInputsRow() {
        inputsRow.removeAllViews();
        for (Input in : inputs()) {
            if (in.power == Input.EMPTY) continue;
            View p = inputPill(in);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
            lp.setMarginEnd(dp(10)); inputsRow.addView(p, lp);
        }
    }

    private boolean inputsExpanded;

    private void showInputs(View anchor) {
        if (inputsPopup != null && inputsPopup.isShowing()) { inputsPopup.dismiss(); return; }
        LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(dp(16)); bg.setColor(0xF2151B2E);
        list.setBackground(bg);
        inputsExpanded = false;
        if (!fillInputs(list, 0)) { launch(new Intent(Settings.ACTION_SETTINGS)); return; }
        inputsPopup = new PopupWindow(list, dp(300), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        inputsPopup.setElevation(dp(16));
        inputsPopup.setAnimationStyle(android.R.style.Animation_Dialog);
        if (anchor != null) inputsPopup.showAsDropDown(anchor, 0, dp(8));
        else inputsPopup.showAtLocation(root, Gravity.TOP | Gravity.END, dp(48), dp(84));
        // Power states come from the TV's CEC service in the background; redraw in place if they changed.
        final PopupWindow shown = inputsPopup;
        Cec.refresh(this, () -> {
            if (shown != inputsPopup || !shown.isShowing()) return;
            View f = list.findFocus();
            fillInputs(list, f == null ? 0 : Math.max(0, list.indexOfChild(f)));
        });
    }

    /** (Re)builds the drop-down's rows and focuses the row at `focusIndex`; false when there is nothing to show. */
    private boolean fillInputs(LinearLayout list, int focusIndex) {
        List<Input> ins = inputs();
        if (ins.isEmpty()) return false;
        list.removeAllViews();
        List<Input> empty = new ArrayList<>();
        Input liveTv = null;
        boolean any = false;
        for (Input in : ins) {
            if (in.kind == Input.LIVE) { liveTv = in; continue; }
            if (in.power == Input.EMPTY) { empty.add(in); continue; }
            addInputRow(list, in); any = true;
        }
        if (!empty.isEmpty()) {
            if (any) list.addView(separator());
            if (inputsExpanded || !any) for (Input in : empty) addInputRow(list, in);
            else {
                View more = inputRow(R.drawable.ic_more, getString(R.string.other_inputs), Input.NONE, String.valueOf(empty.size()));
                more.setOnClickListener(v -> {
                    inputsExpanded = true;
                    int at = list.indexOfChild(v);
                    fillInputs(list, at);
                });
                addRow(list, more);
            }
        }
        if (liveTv != null) {
            if (list.getChildCount() > 0) list.addView(separator());
            addInputRow(list, liveTv);
        }
        View target = list.getChildAt(Math.min(focusIndex, list.getChildCount() - 1));
        if (target == null || !target.isFocusable()) target = list.getChildAt(0);
        if (target != null) target.requestFocus();
        return true;
    }

    private void addInputRow(LinearLayout list, Input in) {
        View row = inputRow(in.icon, in.label, in.power, null);
        row.setOnClickListener(v -> { inputsPopup.dismiss(); in.open.run(); });
        addRow(list, row);
    }

    private void addRow(LinearLayout list, View row) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        lp.setMargins(0, dp(2), 0, dp(2)); list.addView(row, lp);
    }

    // ---------- cards (AdGuard Home, weather) ----------

    private PopupWindow card;

    private boolean dismissCards() {
        boolean shown = false;
        if (inputsPopup != null && inputsPopup.isShowing()) { inputsPopup.dismiss(); shown = true; }
        if (card != null && card.isShowing()) { card.dismiss(); shown = true; }
        return shown;
    }

    /** A drop-down card under a top-bar pill, styled like the Inputs menu; focus goes to its first focusable row. */
    private LinearLayout showCard(View anchor, int widthDp) {
        dismissCards();
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(12));
        GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(dp(16)); bg.setColor(0xF2151B2E);
        box.setBackground(bg);
        card = new PopupWindow(box, dp(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        card.setElevation(dp(16));
        card.setAnimationStyle(android.R.style.Animation_Dialog);
        return box;
    }

    private void openCard(View anchor, LinearLayout box) {
        card.showAsDropDown(pills, 0, dp(8), Gravity.END);   // right-aligned with the pill row, inside the screen
        focusFirst(box);
    }

    private static boolean focusFirst(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            if (v.isFocusable() && v.getVisibility() == View.VISIBLE) { v.requestFocus(); return true; }
            if (v instanceof ViewGroup && focusFirst((ViewGroup) v)) return true;
        }
        return false;
    }

    private TextView cardText(LinearLayout box, int sp, boolean bold, int color, String s, int topDp) {
        TextView t = text(sp, bold, color); t.setShadowLayer(0, 0, 0, 0); t.setText(s);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp); box.addView(t, lp);
        return t;
    }

    private View dot(int color) {
        View d = new View(this);
        GradientDrawable g = new GradientDrawable(); g.setShape(GradientDrawable.OVAL); g.setColor(color); d.setBackground(g);
        return d;
    }

    /** A focusable action row at the bottom of a card. */
    private void cardAction(LinearLayout box, int icon, String label, Runnable action) {
        View row = inputRow(icon, label, Input.NONE, null);
        row.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        lp.topMargin = dp(8); box.addView(row, lp);
    }

    /** AdGuard Home: protection state, the last 24 hours, and pause for 5 minutes / turn back on. */
    private void showGuard(View anchor) {
        if (card != null && card.isShowing()) { card.dismiss(); return; }
        LinearLayout box = showCard(anchor, 320);
        LinearLayout head = new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView shield = new ImageView(this); shield.setImageResource(R.drawable.ic_shield); shield.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
        head.addView(shield, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView title = text(16, true, 0xFFFFFFFF); title.setShadowLayer(0, 0, 0, 0); title.setText("AdGuard Home"); title.setPadding(dp(10), 0, 0, 0);
        head.addView(title);
        box.addView(head);
        LinearLayout state = new LinearLayout(this); state.setGravity(Gravity.CENTER_VERTICAL);
        View stateDot = dot(0x66FFFFFF); state.addView(stateDot, new LinearLayout.LayoutParams(dp(7), dp(7)));
        TextView stateText = text(13, false, 0xCCFFFFFF); stateText.setShadowLayer(0, 0, 0, 0); stateText.setText("…"); stateText.setPadding(dp(8), 0, 0, 0);
        state.addView(stateText);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); sl.topMargin = dp(8);
        box.addView(state, sl);
        LinearLayout stats = new LinearLayout(this);
        LinearLayout.LayoutParams stl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); stl.topMargin = dp(14);
        box.addView(stats, stl);
        TextView caption = cardText(box, 11, false, 0x80FFFFFF, getString(R.string.ag_last_day), 6);
        caption.setVisibility(View.GONE);
        LinearLayout actions = new LinearLayout(this); actions.setOrientation(LinearLayout.VERTICAL);
        box.addView(actions);
        // something focusable while the status loads, so the card takes the remote's keys
        cardAction(actions, R.drawable.ic_settings, getString(R.string.home_settings), () -> { card.dismiss(); launch(new Intent(this, SettingsActivity.class)); });
        openCard(anchor, box);
        Guard.Result<Guard.Status> show = new Guard.Result<Guard.Status>() {
            @Override public void done(Guard.Status s, String err) {
                if (!card.isShowing() || card.getContentView() != box) return;
                if (err != null) { stateText.setText(err); ((GradientDrawable) stateDot.getBackground()).setColor(0xFFFF453A); return; }
                boolean paused = !s.on && s.pausedMs > 0;
                ((GradientDrawable) stateDot.getBackground()).setColor(s.on ? 0xFF34C759 : paused ? 0xFFFF9F0A : 0xFFFF453A);
                stateText.setText(s.on ? getString(R.string.ag_on) : paused ? getString(R.string.ag_paused, (int) Math.ceil(s.pausedMs / 60_000.0)) : getString(R.string.ag_disabled));
                stats.removeAllViews();
                stat(stats, getString(R.string.ag_queries), String.format(Locale.getDefault(), "%,d", s.queries));
                stat(stats, getString(R.string.ag_blocked), String.format(Locale.getDefault(), "%,d · %d%%", s.blocked, s.blockedPercent()));
                stat(stats, getString(R.string.ag_speed), String.format(Locale.getDefault(), "%.0f ms", s.avgMs));
                caption.setVisibility(View.VISIBLE);
                actions.removeAllViews();
                final Guard.Result<Guard.Status> again = this;
                if (s.on) cardAction(actions, R.drawable.ic_shield, getString(R.string.ag_pause), () -> Guard.protect(MainActivity.this, 5, again));
                else cardAction(actions, R.drawable.ic_shield, getString(R.string.ag_resume), () -> Guard.protect(MainActivity.this, 0, again));
                focusFirst(actions);
            }
        };
        Guard.status(this, show);
    }

    private void stat(LinearLayout row, String label, String value) {
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        TextView v = text(17, true, 0xFFFFFFFF); v.setShadowLayer(0, 0, 0, 0); v.setText(value); v.setSingleLine(true);
        TextView l = text(11, false, 0x99FFFFFF); l.setShadowLayer(0, 0, 0, 0); l.setText(label);
        col.addView(v); col.addView(l);
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    /** Weather: now (feels like, humidity, wind), air quality, today and the next three days; a row to its settings. */
    private void showWeather(View anchor) {
        if (card != null && card.isShowing()) { card.dismiss(); return; }
        Integer t = Sky.temperature(this);
        if (t == null) { launch(new Intent(this, SettingsActivity.class)); return; }   // no city yet: pick one
        org.json.JSONObject more = Sky.more(this);
        if (more == null) Sky.refresh(this, true, this::updateWeather);   // saved before the card existed
        LinearLayout box = showCard(anchor, 380);
        int code = Sky.code(this);
        LinearLayout now = new LinearLayout(this); now.setGravity(Gravity.CENTER_VERTICAL);
        ImageView big = new ImageView(this); big.setImageResource(Sky.icon(code, Sky.isDay(this))); big.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
        now.addView(big, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView temp = text(34, false, 0xFFFFFFFF); temp.setShadowLayer(0, 0, 0, 0); temp.setText(t + "°"); temp.setPadding(dp(12), 0, dp(12), 0);
        now.addView(temp);
        LinearLayout names = new LinearLayout(this); names.setOrientation(LinearLayout.VERTICAL);
        TextView what = text(15, true, 0xFFFFFFFF); what.setShadowLayer(0, 0, 0, 0); what.setText(getString(Sky.label(code)));
        TextView where = text(12, false, 0x99FFFFFF); where.setShadowLayer(0, 0, 0, 0); where.setText(Sky.city(this));
        names.addView(what); names.addView(where);
        now.addView(names);
        box.addView(now);
        if (more != null) {
            String unit = Sky.fahrenheit(this) ? "mph" : "km/h";
            cardText(box, 13, false, 0xCCFFFFFF, getString(R.string.wx_feels, (int) Math.round(more.optDouble("feels", t)))
                    + "  ·  " + getString(R.string.wx_humidity, more.optInt("humidity"))
                    + "  ·  " + getString(R.string.wx_wind, (int) Math.round(more.optDouble("wind")), unit), 10);
            if (more.has("aqi")) {
                int aqi = more.optInt("aqi");
                LinearLayout air = new LinearLayout(this); air.setGravity(Gravity.CENTER_VERTICAL);
                air.addView(dot(Sky.aqiColor(aqi)), new LinearLayout.LayoutParams(dp(7), dp(7)));
                TextView at = text(13, false, 0xCCFFFFFF); at.setShadowLayer(0, 0, 0, 0); at.setPadding(dp(8), 0, 0, 0);
                at.setText(getString(R.string.air_quality, getString(Sky.aqiLabel(aqi)), aqi));
                air.addView(at);
                LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); al.topMargin = dp(6);
                box.addView(air, al);
            }
            org.json.JSONArray days = more.optJSONArray("days");
            if (days != null && days.length() > 0) {
                box.addView(separator());
                LinearLayout row = new LinearLayout(this);
                SimpleDateFormat dayFmt = new SimpleDateFormat("EEE", getResources().getConfiguration().getLocales().get(0));
                for (int i = 0; i < Math.min(4, days.length()); i++) {
                    org.json.JSONArray d = days.optJSONArray(i);
                    if (d == null) continue;
                    LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL); col.setGravity(Gravity.CENTER_HORIZONTAL);
                    TextView name = text(12, false, 0xB3FFFFFF); name.setShadowLayer(0, 0, 0, 0);
                    name.setText(i == 0 ? getString(R.string.today) : dayFmt.format(new Date(d.optLong(0) * 1000)));
                    col.addView(name);
                    ImageView ic = new ImageView(this); ic.setImageResource(Sky.icon(d.optInt(1), true)); ic.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
                    LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(22), dp(22)); il.topMargin = dp(6); il.bottomMargin = dp(6);
                    col.addView(ic, il);
                    TextView range = text(13, false, 0xFFFFFFFF); range.setShadowLayer(0, 0, 0, 0);
                    range.setText(Math.round(d.optDouble(2)) + "° / " + Math.round(d.optDouble(3)) + "°");
                    col.addView(range);
                    int rain = d.optInt(4, -1);
                    if (rain > 0) {
                        TextView r = text(11, false, 0xFF64D2FF); r.setShadowLayer(0, 0, 0, 0); r.setText(getString(R.string.wx_rain_chance, rain));
                        col.addView(r);
                    }
                    row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                }
                box.addView(row);
            }
        }
        cardAction(box, R.drawable.ic_settings, getString(R.string.weather_settings), () -> { card.dismiss(); launch(new Intent(this, SettingsActivity.class)); });
        openCard(anchor, box);
    }

    // ---------- weather ----------

    private int wallpaperMinutes() { return prefs.getInt("wallpaper_minutes", getResources().getInteger(R.integer.wallpaper_minutes)); }

    /** Icon and temperature; focused, it also names the weather and the city. OK opens the weather card. */
    private View weatherPill() {
        LinearLayout p = new LinearLayout(this); p.setOrientation(LinearLayout.HORIZONTAL); p.setGravity(Gravity.CENTER_VERTICAL);
        p.setPadding(dp(10), 0, dp(12), 0);
        weatherIcon = new ImageView(this); weatherIcon.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
        p.addView(weatherIcon, new LinearLayout.LayoutParams(dp(18), dp(18)));
        weatherText = text(13, false, 0xFFFFFFFF); weatherText.setShadowLayer(0, 0, 0, 0); weatherText.setPadding(dp(6), 0, 0, 0);
        p.addView(weatherText);
        p.setFocusable(true); p.setClickable(true);
        GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(dp(17)); bg.setColor(0x8C0A0E1E); p.setBackground(bg);
        p.setOnFocusChangeListener((v, f) -> {
            if (f) prevFocus = v;
            bg.setColor(f ? 0xF2FFFFFF : 0x8C0A0E1E);
            weatherIcon.setImageTintList(ColorStateList.valueOf(f ? 0xFF111111 : 0xFFFFFFFF));
            weatherText.setTextColor(f ? 0xFF111111 : 0xFFFFFFFF);
            updateWeather();
            v.animate().scaleX(f ? 1.06f : 1f).scaleY(f ? 1.06f : 1f).setDuration(200).setInterpolator(SPRING).start();
        });
        p.setOnClickListener(this::showWeather);
        p.setVisibility(View.GONE);   // until the first forecast arrives
        return p;
    }

    private void updateWeather() {
        if (weatherPill == null) return;
        Integer t = Sky.temperature(this);
        if (t == null) {
            if (!Sky.city(this).isEmpty()) { weatherPill.setVisibility(View.GONE); return; }   // fetching or offline
            // no city yet (the TV's time zone names none): ask for one, OK opens Home settings
            weatherIcon.setImageResource(R.drawable.ic_w_partly_cloudy_day);
            weatherText.setText(R.string.set_city);
            weatherPill.setVisibility(View.VISIBLE);
            return;
        }
        int code = Sky.code(this);
        weatherIcon.setImageResource(Sky.icon(code, Sky.isDay(this)));
        String text = t + "°";
        if (weatherPill.hasFocus() || weatherPill.isFocused()) {
            String city = Sky.city(this);
            text += "  " + getString(Sky.label(code)) + (city.isEmpty() ? "" : "  ·  " + city);
        }
        weatherText.setText(text);
        weatherPill.setVisibility(View.VISIBLE);
    }

    // ---------- clock / wifi ----------

    private void updateClock() {
        Date now = new Date();
        clock.setText(timeFmt.format(now));
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int hello = h < 6 ? R.string.greeting_night : h < 12 ? R.string.greeting_morning : h < 18 ? R.string.greeting_day : R.string.greeting_evening;
        String owner = getString(R.string.owner_name).trim();
        greeting.setText(getString(hello) + (owner.isEmpty() ? "" : ", " + owner) + "  ·  " + dateFmt.format(now));
        if (wifiLabel != null) {
            String ssid = null;
            try {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                String s = wm.getConnectionInfo().getSSID();
                if (s != null && !s.equals("<unknown ssid>")) ssid = s.replace("\"", "");
            } catch (RuntimeException ignored) { }
            wifiLabel.setText(ssid == null ? getString(R.string.wifi) : ssid);
        }
    }
}
