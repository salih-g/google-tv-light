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
import java.util.HashSet;
import java.util.List;
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
    private TextView clock, greeting, focusedName, wifiLabel;
    private HorizontalScrollView dockScroll;
    private LinearLayout dock, inputsRow, pills, hint;
    private GridLayout grid;
    private Wallpapers wallpapers;
    private List<App> apps = new ArrayList<>();
    private View lastDockFocus;
    private PopupWindow inputsPopup;
    private boolean receiverRegistered;
    private ObjectAnimator kenBurns;
    private SharedPreferences prefs;
    private View prevFocus;
    private int lastDir = View.FOCUS_RIGHT;

    // ---------- lifecycle ----------

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        dateFmt = new SimpleDateFormat("d MMMM EEEE", getResources().getConfiguration().getLocales().get(0));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        buildUi();
        setContentView(root);
        int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
        wallpapers = new Wallpapers(this, wallBack, wallFront, handler, getResources().getInteger(R.integer.wallpaper_minutes), w, h);
        loadApps();
        gridOnResume = KeyService.ACTION_SHOW_APPS.equals(getIntent().getAction());
        // First paint: UI fades in over the wallpaper instead of popping.
        for (View v : new View[]{clock, greeting, pills, dockScroll, hint}) { v.setAlpha(0f); v.animate().alpha(1f).setDuration(600).setStartDelay(150).start(); }
    }

    /** Set by the T key (KeyService) so the all-apps grid, not the dock, shows when the launcher comes up. */
    private boolean gridOnResume;

    @Override protected void onResume() {
        super.onResume();
        wallpapers.start();
        startKenBurns();
        handler.post(minuteTick);
        if (!receiverRegistered) {
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_PACKAGE_ADDED); f.addAction(Intent.ACTION_PACKAGE_REMOVED); f.addAction(Intent.ACTION_PACKAGE_CHANGED);
            f.addDataScheme("package");
            registerReceiver(packageReceiver, f); receiverRegistered = true;
        }
        loadApps(); // picks up installs that happened while another app was in front
        if (gridOnResume) { gridOnResume = false; showGrid(); } else showDock(false);
    }

    @Override protected void onPause() {
        super.onPause();
        wallpapers.stop();
        if (kenBurns != null) kenBurns.pause();
        handler.removeCallbacks(minuteTick);
        if (receiverRegistered) { unregisterReceiver(packageReceiver); receiverRegistered = false; }
        if (inputsPopup != null && inputsPopup.isShowing()) inputsPopup.dismiss();
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
            long now = System.currentTimeMillis();
            handler.postDelayed(this, 60_000 - now % 60_000 + 50);
        }
    };

    /** Very slow zoom on the wallpaper layer (both frames), the way Apple TV screensavers breathe. */
    private void startKenBurns() {
        if (kenBurns == null) {
            kenBurns = ObjectAnimator.ofFloat(wallLayer, View.SCALE_X, 1f, 1.06f);
            kenBurns.setDuration(45_000); kenBurns.setRepeatCount(ValueAnimator.INFINITE); kenBurns.setRepeatMode(ValueAnimator.REVERSE);
            kenBurns.setInterpolator(new AccelerateDecelerateInterpolator());
            kenBurns.addUpdateListener(a -> wallLayer.setScaleY((Float) a.getAnimatedValue()));
            kenBurns.start();
        } else if (kenBurns.isPaused()) kenBurns.resume();
    }

    // ---------- keys ----------

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_MENU:
                showInputs(null);
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (inputsPopup != null && inputsPopup.isShowing()) { inputsPopup.dismiss(); return true; }
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
        for (View p : new View[]{inputsPill, wifiPill, settingsPill}) {
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
        dockScroll.addView(dock, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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
        TextView h1 = text(13, false, 0xB3FFFFFF); h1.setText(getString(R.string.apps)); column.addView(h1);
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
            if (hidden.contains(pkg) || !seen.add(pkg)) continue;
            Drawable banner = ri.activityInfo.loadBanner(pm);
            if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            if (banner == null) banner = ri.loadIcon(pm);
            list.add(new App(pkg, String.valueOf(ri.loadLabel(pm)), new ComponentName(pkg, ri.activityInfo.name), banner));
        }
        Collections.sort(list, (a, b) -> a.label.compareToIgnoreCase(b.label));
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

    private void fillDock(String focusedPkg) {
        dock.removeAllViews(); lastDockFocus = null;
        List<App> order = new ArrayList<>();
        for (String pkg : dockPackages()) { App a = find(pkg); if (a != null) order.add(a); }
        if (order.isEmpty()) order = apps.subList(0, Math.min(7, apps.size())); // nothing configured is installed
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
        if (inDock) {
            labels = new String[]{getString(R.string.move_left), getString(R.string.move_right), getString(R.string.remove_from_dock)};
            actions = new Runnable[]{
                () -> { if (idx > 0) { Collections.swap(pkgs, idx, idx - 1); saveDock(pkgs); fillDock(a.pkg); } },
                () -> { if (idx >= 0 && idx < pkgs.size() - 1) { Collections.swap(pkgs, idx, idx + 1); saveDock(pkgs); fillDock(a.pkg); } },
                () -> { pkgs.remove(a.pkg); saveDock(pkgs); fillDock(null); }};
        } else {
            boolean present = idx >= 0;
            labels = new String[]{getString(present ? R.string.remove_from_dock : R.string.add_to_dock)};
            actions = new Runnable[]{() -> { if (present) pkgs.remove(a.pkg); else pkgs.add(a.pkg); saveDock(pkgs); fillDock(null); }};
        }
        for (int i = 0; i < labels.length; i++) {
            final Runnable act = actions[i];
            View p = inputPill(new Input(labels[i], 0, () -> { popup[0].dismiss(); act.run(); showDock(false); }));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36));
            lp.setMargins(0, dp(3), 0, dp(3)); list.addView(p, lp);
        }
        popup[0] = new PopupWindow(list, dp(230), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup[0].setElevation(dp(16)); popup[0].setAnimationStyle(android.R.style.Animation_Dialog);
        if (inDock) { // the dock sits at the bottom edge: open the menu above the tile
            list.measure(View.MeasureSpec.makeMeasureSpec(dp(230), View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
            popup[0].showAsDropDown(tile, 0, -tile.getHeight() - list.getMeasuredHeight() - dp(40));
        } else popup[0].showAsDropDown(tile, 0, -dp(8));
        list.getChildAt(0).requestFocus();
    }

    private void setFocusedName(String name) {
        if (name.equals(String.valueOf(focusedName.getText())) && focusedName.getAlpha() == 1f) return;
        focusedName.animate().cancel();
        focusedName.setAlpha(0f); focusedName.setTranslationY(dp(6)); focusedName.setText(name);
        focusedName.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(new DecelerateInterpolator()).start();
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

    private static final class Input { final String label; final int icon; final Runnable open; Input(String l, int i, Runnable o) { label = l; icon = i; open = o; } }

    private List<Input> inputs() {
        List<Input> list = new ArrayList<>();
        String liveTv = getString(R.string.live_tv_package);
        Intent live = getPackageManager().getLeanbackLaunchIntentForPackage(liveTv);
        if (live == null) live = getPackageManager().getLaunchIntentForPackage(liveTv);
        final boolean hasTvApp = live != null;
        if (hasTvApp) { final Intent li = live; list.add(new Input(getString(R.string.live_tv), R.drawable.ic_live_tv, () -> launch(li))); }
        TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        if (tim != null) for (TvInputInfo t : tim.getTvInputList()) {
            if (!t.isPassthroughInput()) continue;
            CharSequence custom = t.loadCustomLabel(this), name = t.loadLabel(this); // custom = the name the owner gave the input (e.g. "PlayStation 5")
            String label = custom != null && custom.length() > 0 ? custom.toString() : name == null ? t.getId() : name.toString();
            final String id = t.getId();
            list.add(new Input(label, R.drawable.ic_hdmi, () -> {
                Intent i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id));
                if (hasTvApp) i.setPackage(liveTv); // the TV app renders passthrough inputs
                try { startActivity(i); } catch (RuntimeException e) {
                    Intent picker = getPackageManager().getLaunchIntentForPackage(getString(R.string.input_picker_package));
                    if (picker != null) launch(picker);
                }
            }));
        }
        return list;
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

    private void fillInputsRow() {
        inputsRow.removeAllViews();
        for (Input in : inputs()) {
            View p = inputPill(in);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
            lp.setMarginEnd(dp(10)); inputsRow.addView(p, lp);
        }
    }

    private void showInputs(View anchor) {
        if (inputsPopup != null && inputsPopup.isShowing()) { inputsPopup.dismiss(); return; }
        List<Input> ins = inputs();
        if (ins.isEmpty()) { launch(new Intent(Settings.ACTION_SETTINGS)); return; }
        LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(dp(16)); bg.setColor(0xF2151B2E);
        list.setBackground(bg);
        for (Input in : ins) {
            View p = inputPill(in);
            p.setOnClickListener(v -> { inputsPopup.dismiss(); in.open.run(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36));
            lp.setMargins(0, dp(3), 0, dp(3)); list.addView(p, lp);
        }
        inputsPopup = new PopupWindow(list, dp(250), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        inputsPopup.setElevation(dp(16));
        inputsPopup.setAnimationStyle(android.R.style.Animation_Dialog);
        if (anchor != null) inputsPopup.showAsDropDown(anchor, 0, dp(8));
        else inputsPopup.showAtLocation(root, Gravity.TOP | Gravity.END, dp(48), dp(84));
        list.getChildAt(0).requestFocus();
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
