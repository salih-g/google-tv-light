package dev.salih.tvhome;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
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
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
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
 * One-screen home: wallpaper, clock, three pills (inputs, Wi-Fi, settings) and a dock of favourite
 * apps. Down opens the all-apps grid with an inputs row; Back returns to the dock. Every key the
 * launcher does not use falls through to the system, so the remote's volume, mute, power, mic,
 * source, settings, channel and app-shortcut buttons keep their native behaviour.
 */
public class MainActivity extends Activity {

    private static final class App {
        final String pkg, label; final ComponentName component; final Drawable banner;
        App(String pkg, String label, ComponentName component, Drawable banner) {
            this.pkg = pkg; this.label = label; this.component = component; this.banner = banner;
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Locale tr = new Locale("tr", "TR");
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.US);
    private final SimpleDateFormat dateFmt;

    private FrameLayout root;
    private ImageView wallBack, wallFront;
    private TextView clock, greeting, focusedName, wifiText, hint;
    private LinearLayout dock;
    private FrameLayout gridPanel;
    private GridLayout grid;
    private LinearLayout inputsRow;
    private Wallpapers wallpapers;
    private List<App> apps = new ArrayList<>();
    private View lastDockFocus;
    private PopupWindow inputsPopup;
    private boolean receiverRegistered;

    public MainActivity() { dateFmt = new SimpleDateFormat("d MMMM EEEE", tr); }

    // ---------- lifecycle ----------

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        buildUi();
        setContentView(root);
        int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
        wallpapers = new Wallpapers(this, wallBack, wallFront, handler, getResources().getInteger(R.integer.wallpaper_minutes), w, h);
        loadApps();
    }

    @Override protected void onResume() {
        super.onResume();
        wallpapers.start();
        handler.post(minuteTick);
        if (!receiverRegistered) {
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_PACKAGE_ADDED); f.addAction(Intent.ACTION_PACKAGE_REMOVED); f.addAction(Intent.ACTION_PACKAGE_CHANGED);
            f.addDataScheme("package");
            registerReceiver(packageReceiver, f); receiverRegistered = true;
        }
        loadApps(); // picks up installs that happened while another app was in front
        showDock();
    }

    @Override protected void onPause() {
        super.onPause();
        wallpapers.stop();
        handler.removeCallbacks(minuteTick);
        if (receiverRegistered) { unregisterReceiver(packageReceiver); receiverRegistered = false; }
        if (inputsPopup != null && inputsPopup.isShowing()) inputsPopup.dismiss();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); // Home pressed while already on top: go back to the dock
        showDock();
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

    // ---------- keys ----------

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_TV_INPUT: // Source key: show our picker; the system's own picker also fires if it owns the key
                showInputs(null);
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (gridPanel.getVisibility() == View.VISIBLE) { showDock(); return true; }
                return true; // Back on the home screen does nothing (never falls into the stock launcher)
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (gridPanel.getVisibility() != View.VISIBLE && dock.hasFocus()) { showGrid(); return true; }
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (gridPanel.getVisibility() == View.VISIBLE && isOnFirstGridRow()) { showDock(); return true; }
                break;
        }
        return super.onKeyDown(code, e); // volume, mute, power, mic, settings, channel, app shortcuts: system handles
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
        wallBack = new ImageView(this); wallFront = new ImageView(this);
        for (ImageView v : new ImageView[]{wallBack, wallFront}) {
            v.setScaleType(ImageView.ScaleType.CENTER_CROP);
            v.setVisibility(View.INVISIBLE);
            root.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        // Bottom scrim keeps the dock and labels readable on bright frames.
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0x66000000, 0xB3000000}));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260), Gravity.BOTTOM);
        root.addView(scrim, sp);

        // Top-left: clock, greeting + date
        LinearLayout topLeft = new LinearLayout(this); topLeft.setOrientation(LinearLayout.VERTICAL);
        clock = text(44, true, 0xFFFFFFFF); greeting = text(15, false, 0xDDFFFFFF);
        topLeft.addView(clock); topLeft.addView(greeting);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        tl.setMargins(dp(40), dp(32), 0, 0);
        root.addView(topLeft, tl);

        // Top-right: pills
        LinearLayout pills = new LinearLayout(this); pills.setOrientation(LinearLayout.HORIZONTAL);
        View inputsPill = pill("Girişler");
        inputsPill.setOnClickListener(v -> showInputs(v));
        wifiText = null;
        View wifiPill = pill("Wi-Fi"); wifiText = (TextView) ((LinearLayout) wifiPill).getChildAt(0);
        wifiPill.setOnClickListener(v -> launch(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        View settingsPill = pill("Ayarlar");
        settingsPill.setOnClickListener(v -> launch(new Intent(Settings.ACTION_SETTINGS)));
        for (View p : new View[]{inputsPill, wifiPill, settingsPill}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(dp(8)); pills.addView(p, lp);
        }
        FrameLayout.LayoutParams pr = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        pr.setMargins(0, dp(36), dp(40), 0);
        root.addView(pills, pr);

        // Focused app name above the dock
        focusedName = text(18, true, 0xFFFFFFFF);
        focusedName.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams fn = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        fn.setMargins(0, 0, 0, dp(150));
        root.addView(focusedName, fn);

        // Dock
        dock = new LinearLayout(this); dock.setOrientation(LinearLayout.HORIZONTAL); dock.setGravity(Gravity.CENTER);
        dock.setClipChildren(false); dock.setClipToPadding(false);
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        dl.setMargins(dp(40), 0, dp(40), dp(56));
        root.addView(dock, dl);

        hint = text(12, false, 0x99FFFFFF); hint.setText("▼  tüm uygulamalar"); hint.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        hl.setMargins(0, 0, 0, dp(18));
        root.addView(hint, hl);

        // All-apps panel (hidden until Down)
        gridPanel = new FrameLayout(this);
        gridPanel.setBackgroundColor(0xCC0B1020);
        gridPanel.setVisibility(View.GONE);
        ScrollView scroll = new ScrollView(this); scroll.setClipChildren(false); scroll.setClipToPadding(false);
        LinearLayout column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(48), dp(40), dp(48), dp(40)); column.setClipChildren(false); column.setClipToPadding(false);
        TextView h1 = text(13, false, 0xB3FFFFFF); h1.setText("Uygulamalar"); column.addView(h1);
        grid = new GridLayout(this); grid.setColumnCount(getResources().getInteger(R.integer.columns));
        grid.setClipChildren(false); grid.setClipToPadding(false);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gl.topMargin = dp(10); column.addView(grid, gl);
        TextView h2 = text(13, false, 0xB3FFFFFF); h2.setText("Girişler");
        LinearLayout.LayoutParams h2l = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        h2l.topMargin = dp(28); column.addView(h2, h2l);
        inputsRow = new LinearLayout(this); inputsRow.setOrientation(LinearLayout.HORIZONTAL);
        inputsRow.setClipChildren(false); inputsRow.setClipToPadding(false);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        il.topMargin = dp(10); column.addView(inputsRow, il);
        scroll.addView(column);
        gridPanel.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(gridPanel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        updateClock();
    }

    private TextView text(int sp, boolean bold, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif" + (bold ? "-medium" : ""), Typeface.NORMAL));
        t.setShadowLayer(6f, 0f, 2f, 0x80000000);
        return t;
    }

    /** Frosted pill: rounded translucent background, brighter when focused. */
    private View pill(String label) {
        LinearLayout p = new LinearLayout(this); p.setOrientation(LinearLayout.HORIZONTAL); p.setGravity(Gravity.CENTER_VERTICAL);
        p.setPadding(dp(14), dp(7), dp(14), dp(7));
        TextView t = text(13, false, 0xFFFFFFFF); t.setText(label); t.setShadowLayer(0, 0, 0, 0);
        p.addView(t);
        p.setFocusable(true); p.setClickable(true);
        GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(dp(20)); bg.setColor(0x33FFFFFF);
        p.setBackground(bg);
        p.setOnFocusChangeListener((v, f) -> { bg.setColor(f ? 0xE6FFFFFF : 0x33FFFFFF); t.setTextColor(f ? 0xFF111111 : 0xFFFFFFFF); });
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

    private void fillDock() {
        String focusedPkg = lastDockFocus != null && lastDockFocus.getTag() instanceof App ? ((App) lastDockFocus.getTag()).pkg : null;
        dock.removeAllViews(); lastDockFocus = null;
        for (String pkg : getResources().getStringArray(R.array.dock_apps)) {
            App a = find(pkg); if (a == null) continue;
            View tile = tile(a, dp(118), dp(66));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(118), dp(66)); lp.setMargins(dp(9), 0, dp(9), 0);
            dock.addView(tile, lp);
            if (lastDockFocus == null || pkg.equals(focusedPkg)) lastDockFocus = tile;
        }
        if (dock.getChildCount() == 0) for (App a : apps.subList(0, Math.min(7, apps.size()))) { // no configured dock app installed
            View tile = tile(a, dp(118), dp(66));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(118), dp(66)); lp.setMargins(dp(9), 0, dp(9), 0);
            dock.addView(tile, lp); if (lastDockFocus == null) lastDockFocus = tile;
        }
    }

    private void fillGrid() {
        grid.removeAllViews();
        int cols = grid.getColumnCount();
        int cell = (getResources().getDisplayMetrics().widthPixels - dp(96) - dp(16) * cols) / cols;
        for (App a : apps) {
            View tile = tile(a, cell, cell * 9 / 16);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(); lp.width = cell; lp.height = cell * 9 / 16; lp.setMargins(dp(8), dp(8), dp(8), dp(8));
            grid.addView(tile, lp);
        }
    }

    /** 16:9 rounded tile showing the app banner; scales up with a white outline when focused. */
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
        GradientDrawable ring = new GradientDrawable(); ring.setCornerRadius(dp(8)); ring.setStroke(dp(3), 0x00FFFFFF); ring.setColor(0);
        card.setForeground(ring);
        card.setOnFocusChangeListener((v, f) -> {
            ring.setStroke(dp(3), f ? 0xFFFFFFFF : 0x00FFFFFF);
            ObjectAnimator.ofPropertyValuesHolder(v, PropertyValuesHolder.ofFloat(View.SCALE_X, f ? 1.16f : 1f), PropertyValuesHolder.ofFloat(View.SCALE_Y, f ? 1.16f : 1f))
                    .setDuration(140).start();
            v.setElevation(f ? dp(8) : 0);
            if (f) { focusedName.setText(a.label); if (v.getParent() == dock) lastDockFocus = v; }
        });
        card.setOnClickListener(v -> launch(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(a.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)));
        return card;
    }

    private void launch(Intent i) {
        try { startActivity(i); } catch (RuntimeException e) { focusedName.setText("Açılamadı"); }
    }

    // ---------- dock / grid switching ----------

    private void showDock() {
        gridPanel.setVisibility(View.GONE);
        dock.setVisibility(View.VISIBLE); focusedName.setVisibility(View.VISIBLE); hint.setVisibility(View.VISIBLE);
        View f = lastDockFocus != null ? lastDockFocus : dock.getChildAt(0);
        if (f != null) f.requestFocus();
    }

    private void showGrid() {
        gridPanel.setAlpha(0f); gridPanel.setVisibility(View.VISIBLE);
        gridPanel.animate().alpha(1f).setDuration(180).setInterpolator(new DecelerateInterpolator()).start();
        focusedName.setVisibility(View.INVISIBLE); hint.setVisibility(View.INVISIBLE);
        if (grid.getChildCount() > 0) grid.getChildAt(0).requestFocus();
        dock.setVisibility(View.INVISIBLE); // after focus moved, so the dock tile's focus listener doesn't fire
    }

    // ---------- inputs ----------

    private static final class Input { final String label; final Runnable open; Input(String l, Runnable o) { label = l; open = o; } }

    private List<Input> inputs() {
        List<Input> list = new ArrayList<>();
        String liveTv = getString(R.string.live_tv_package);
        Intent live = getPackageManager().getLeanbackLaunchIntentForPackage(liveTv);
        if (live == null) live = getPackageManager().getLaunchIntentForPackage(liveTv);
        final boolean hasTvApp = live != null;
        if (hasTvApp) { final Intent li = live; list.add(new Input("Canlı TV", () -> launch(li))); }
        TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        if (tim != null) for (TvInputInfo t : tim.getTvInputList()) {
            if (!t.isPassthroughInput()) continue;
            CharSequence custom = t.loadCustomLabel(this), name = t.loadLabel(this); // custom = the name the owner gave the input (e.g. "PS5")
            String label = custom != null && custom.length() > 0 ? custom.toString() : name == null ? t.getId() : name.toString();
            final String id = t.getId();
            list.add(new Input(label, () -> {
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

    private void fillInputsRow() {
        inputsRow.removeAllViews();
        for (Input in : inputs()) {
            View p = pill(in.label); p.setOnClickListener(v -> in.open.run());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(10)); inputsRow.addView(p, lp);
        }
    }

    private void showInputs(View anchor) {
        if (inputsPopup != null && inputsPopup.isShowing()) { inputsPopup.dismiss(); return; }
        LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(dp(14)); bg.setColor(0xF2151B2E);
        list.setBackground(bg);
        List<Input> ins = inputs();
        if (ins.isEmpty()) { Intent s = new Intent(Settings.ACTION_SETTINGS); launch(s); return; }
        for (Input in : ins) {
            View p = pill(in.label);
            p.setOnClickListener(v -> { inputsPopup.dismiss(); in.open.run(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(3), 0, dp(3)); list.addView(p, lp);
        }
        inputsPopup = new PopupWindow(list, dp(240), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        inputsPopup.setElevation(dp(12));
        if (anchor != null) inputsPopup.showAsDropDown(anchor, 0, dp(8));
        else inputsPopup.showAtLocation(root, Gravity.TOP | Gravity.END, dp(40), dp(80));
        list.getChildAt(0).requestFocus();
    }

    // ---------- clock / wifi ----------

    private void updateClock() {
        Date now = new Date();
        clock.setText(timeFmt.format(now));
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String hello = h < 6 ? "İyi geceler" : h < 12 ? "Günaydın" : h < 18 ? "İyi günler" : "İyi akşamlar";
        greeting.setText(hello + ", " + getString(R.string.owner_name) + "  ·  " + dateFmt.format(now));
        if (wifiText != null) {
            String ssid = null;
            try {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                String s = wm.getConnectionInfo().getSSID();
                if (s != null && !s.equals("<unknown ssid>")) ssid = s.replace("\"", "");
            } catch (RuntimeException ignored) { }
            wifiText.setText(ssid == null ? "Wi-Fi" : ssid);
        }
    }
}
