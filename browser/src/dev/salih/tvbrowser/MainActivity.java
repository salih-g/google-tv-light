package dev.salih.tvbrowser;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognizerIntent;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Remote-first browser. Screens: START (rail + bookmarks + recent), KEYBOARD, PAGE (one live WebView,
 * link mode or cursor mode) and VIDEO (HUD over a playing video). Every key the browser does not use
 * falls through to the system.
 */
public class MainActivity extends Activity {

    private enum Screen { START, KEYBOARD, PAGE }
    private static final int VOICE_REQUEST = 7;
    private static final long HUD_MS = 3000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Ui ui;
    private Store store;
    private AdBlock adBlock;
    private Wallpapers wallpapers;
    private String navJs, cosmeticCss;

    // root layers, bottom to top
    private FrameLayout root, wallLayer, webContainer, fullscreenContainer, startPanel, keyboardPanel, hudPanel, overlayPanel;
    private LinearLayout overlayBody; private TextView overlayTitle;
    private ImageView wallBack, wallFront, cursor;
    private LinearLayout topBar, hintRow, startHint;
    private View progress, topScrim;
    private TextView urlText, blockedText, modeText, tabsText, toast, hudTitle, hudMeta, hudTime, clock;
    private View hudFill, hudKnob;
    private LinearLayout rail, bookmarksRow, recentRow, startColumn;
    private TextView recentHeader;
    private WebView web;
    private View fullscreenView; private WebChromeClient.CustomViewCallback fullscreenCallback;

    private Screen screen = Screen.START;
    private boolean cursorMode, topBarShown, videoActive, videoPlaying;
    private int videoPos, videoDur; private float videoRate = 1f;
    private float cursorX, cursorY, cursorSpeed;
    private String focusedHref = "";
    private Runnable hideHud = () -> { hudPanel.animate().alpha(0f).setDuration(250).withEndAction(() -> hudPanel.setVisibility(View.GONE)).start(); };

    // keyboard state
    private StringBuilder typed = new StringBuilder();
    private TextView typedView; private LinearLayout suggestions; private GridLayout keyGrid;
    private boolean keyboardForField, symbols;
    private Runnable keyboardGo;
    private ObjectAnimator kenBurns;

    // ---------- lifecycle ----------

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        ui = new Ui(this);
        store = new Store(this, getResources().getStringArray(R.array.default_bookmarks));
        adBlock = new AdBlock(this);
        navJs = asset("nav.js"); cosmeticCss = asset("cosmetic.css");
        buildUi();
        setContentView(root);
        int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
        wallpapers = new Wallpapers(this, wallBack, wallFront, handler, getResources().getInteger(R.integer.wallpaper_minutes), w, h);
        Uri data = getIntent().getData();
        if (data != null) openUrl(data.toString(), true);
        else showStart();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getData() != null) openUrl(intent.getData().toString(), true);
    }

    @Override protected void onResume() {
        super.onResume();
        if (screen == Screen.START) { wallpapers.start(); startKenBurns(); }
        if (web != null) web.onResume();
        handler.post(minuteTick);
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(minuteTick);
        if (web != null) web.onPause();
        wallpapers.stop(); if (kenBurns != null) kenBurns.pause();
    }

    /** Leaving the app: persist tabs and drop the WebView so the process shrinks to a few MB. */
    @Override protected void onStop() {
        super.onStop();
        saveActiveTab(); store.saveTabs();
        destroyWeb();
    }

    @Override protected void onStart() {
        super.onStart();
        if (screen == Screen.PAGE && web == null && !store.tabs.isEmpty()) loadTab(store.activeTab); // restore after a stop
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN) { saveActiveTab(); destroyWeb(); }
        else if (level >= TRIM_MEMORY_RUNNING_LOW && web != null) web.clearCache(false);
    }

    private final Runnable minuteTick = new Runnable() {
        @Override public void run() {
            clock.setText(new SimpleDateFormat("HH:mm", Locale.US).format(new Date()));
            wallpapers.onMinute();
            handler.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000 + 50);
        }
    };

    private void startKenBurns() {
        if (kenBurns == null) {
            kenBurns = ObjectAnimator.ofFloat(wallLayer, View.SCALE_X, 1f, 1.06f);
            kenBurns.setDuration(45_000); kenBurns.setRepeatCount(ValueAnimator.INFINITE); kenBurns.setRepeatMode(ValueAnimator.REVERSE);
            kenBurns.setInterpolator(new AccelerateDecelerateInterpolator());
            kenBurns.addUpdateListener(a -> wallLayer.setScaleY((Float) a.getAnimatedValue()));
            kenBurns.start();
        } else if (kenBurns.isPaused()) kenBurns.resume();
    }

    private String asset(String name) {
        // InputStream.readAllBytes needs API 33; the TV is API 30.
        try (InputStream in = getAssets().open(name); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[16384]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) { return ""; }
    }

    // ---------- UI ----------

    private void buildUi() {
        root = new FrameLayout(this); root.setBackgroundColor(Color.BLACK);
        FrameLayout.LayoutParams full = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);

        wallLayer = new FrameLayout(this);
        wallBack = new ImageView(this); wallFront = new ImageView(this);
        for (ImageView v : new ImageView[]{wallBack, wallFront}) { v.setScaleType(ImageView.ScaleType.CENTER_CROP); v.setVisibility(View.INVISIBLE); wallLayer.addView(v, new FrameLayout.LayoutParams(full)); }
        root.addView(wallLayer, new FrameLayout.LayoutParams(full));

        webContainer = new FrameLayout(this); webContainer.setVisibility(View.GONE);
        root.addView(webContainer, new FrameLayout.LayoutParams(full));
        fullscreenContainer = new FrameLayout(this); fullscreenContainer.setBackgroundColor(Color.BLACK); fullscreenContainer.setVisibility(View.GONE);
        root.addView(fullscreenContainer, new FrameLayout.LayoutParams(full));

        buildStart(); buildTopBar(); buildCursor(); buildKeyboard(); buildHud(); buildOverlay();

        toast = ui.text(13, false, Ui.TEXT); toast.setBackground(ui.rounded(0xBF0A0E1E, 16)); toast.setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8)); toast.setVisibility(View.GONE);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        tl.setMargins(0, ui.dp(40), ui.dp(48), 0); root.addView(toast, tl);
        clock.setText(new SimpleDateFormat("HH:mm", Locale.US).format(new Date()));
    }

    private void buildStart() {
        startPanel = new FrameLayout(this); startPanel.setClipChildren(false); // card rows reach into the column's gutter
        View scrim = new View(this); scrim.setBackgroundColor(0x85000000);
        startPanel.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        rail = new LinearLayout(this); rail.setOrientation(LinearLayout.VERTICAL); rail.setClipChildren(false);
        LinearLayout search = ui.pill(R.drawable.ic_search, getString(R.string.search), false, true); search.setOnClickListener(v -> showKeyboard(false, null));
        LinearLayout marks = ui.pill(R.drawable.ic_bookmark, getString(R.string.bookmarks), false, true); marks.setOnClickListener(v -> bookmarksRow.getChildAt(0).requestFocus());
        LinearLayout hist = ui.pill(R.drawable.ic_history, getString(R.string.history), false, true); hist.setOnClickListener(v -> { if (recentRow.getChildCount() > 0) recentRow.getChildAt(0).requestFocus(); });
        LinearLayout tabsPill = ui.pill(R.drawable.ic_tabs, getString(R.string.tabs), false, true); tabsPill.setOnClickListener(v -> showTabs());
        LinearLayout settings = ui.pill(R.drawable.ic_settings, getString(R.string.settings), false, true); settings.setOnClickListener(v -> showSettings());
        for (LinearLayout p : new LinearLayout[]{search, marks, hist, tabsPill}) { LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(38)); lp.bottomMargin = ui.dp(14); rail.addView(p, lp); }
        View spacer = new View(this); rail.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));
        rail.addView(settings, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(38)));
        FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        rl.setMargins(ui.dp(40), ui.dp(44), 0, ui.dp(44)); startPanel.addView(rail, rl);

        LinearLayout topRight = new LinearLayout(this); topRight.setOrientation(LinearLayout.HORIZONTAL);
        tabsText = ui.text(13, false, Ui.TEXT); tabsText.setBackground(ui.rounded(Ui.PILL_BG, 15)); tabsText.setPadding(ui.dp(11), ui.dp(6), ui.dp(11), ui.dp(6)); tabsText.setShadowLayer(0, 0, 0, 0);
        clock = ui.text(13, false, Ui.TEXT); clock.setBackground(ui.rounded(Ui.PILL_BG, 15)); clock.setPadding(ui.dp(11), ui.dp(6), ui.dp(11), ui.dp(6)); clock.setShadowLayer(0, 0, 0, 0);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); cl.setMarginStart(ui.dp(8));
        topRight.addView(tabsText); topRight.addView(clock, cl);
        FrameLayout.LayoutParams trl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        trl.setMargins(0, ui.dp(44), ui.dp(48), 0); startPanel.addView(topRight, trl);

        LinearLayout column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL); column.setClipChildren(false);
        TextView h1 = ui.text(15, true, 0xEBFFFFFF); h1.setText(getString(R.string.bookmarks)); column.addView(h1);
        bookmarksRow = row(column, ui.dp(14));
        recentHeader = ui.text(15, true, 0xEBFFFFFF); TextView h2 = recentHeader; h2.setText(getString(R.string.recent));
        LinearLayout.LayoutParams h2l = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); h2l.topMargin = ui.dp(34); column.addView(h2, h2l);
        recentRow = row(column, ui.dp(12));
        FrameLayout.LayoutParams col = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        col.setMargins(ui.dp(140), ui.dp(100), ui.dp(START_END_INSET_DP), 0); startPanel.addView(column, col);
        startColumn = column;
        // YouTube TV style: while the rail has focus its expanded pill needs room, so the content slides right and dims.
        startPanel.getViewTreeObserver().addOnGlobalFocusChangeListener((old, now) -> {
            boolean inRail = now != null && isDescendant(rail, now);
            startColumn.animate().translationX(inRail ? ui.dp(96) : 0).alpha(inRail ? 0.55f : 1f).setDuration(220).setInterpolator(new DecelerateInterpolator()).start();
            startHint.animate().translationX(inRail ? ui.dp(96) : 0).alpha(inRail ? 0.4f : 1f).setDuration(220).start();
        });

        startHint = new LinearLayout(this); startHint.setOrientation(LinearLayout.HORIZONTAL); startHint.setGravity(Gravity.CENTER_VERTICAL);
        startHint.addView(ui.hint(new int[0], "OK", openWord()));
        startHint.addView(ui.hint(new int[]{R.drawable.ic_search}, null, getString(R.string.search)));
        startHint.addView(ui.hint(new int[]{R.drawable.ic_mic}, null, getString(R.string.voice_hint)));
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        hl.setMargins(ui.dp(140), 0, 0, ui.dp(36)); startPanel.addView(startHint, hl);
        root.addView(startPanel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private static boolean isDescendant(View parent, View v) {
        for (android.view.ViewParent p = v.getParent(); p != null; p = p.getParent()) if (p == parent) return true;
        return false;
    }

    private String openWord() { return Locale.getDefault().getLanguage().equals("tr") ? "aç" : "open"; }

    /**
     * Horizontal scroller for focus-scaled cards. A focused card grows past its slot, so the
     * scroller keeps a gutter of padding it may draw into (clipToPadding off) and is pulled out
     * by the same amount, which keeps the first card aligned with the header above it.
     * The row must not be clipped to its own bounds (clipChildren off), so the scroller clips
     * itself to its outer bounds instead: scrolled-away cards stop at the gutter edge.
     */
    private static final int CARD_GUTTER_DP = 28, CARD_GUTTER_V_DP = 16, START_END_INSET_DP = 48, OVERLAY_INSET_DP = 56;
    private HorizontalScrollView cardScroller(int endInsetDp) {
        HorizontalScrollView sv = new Ui.CardScroller(this); sv.setHorizontalScrollBarEnabled(false); sv.setClipToPadding(false); sv.setClipChildren(false);
        sv.setOutlineProvider(android.view.ViewOutlineProvider.BOUNDS); sv.setClipToOutline(true);
        sv.setPadding(ui.dp(CARD_GUTTER_DP), ui.dp(CARD_GUTTER_V_DP), ui.dp(endInsetDp), ui.dp(CARD_GUTTER_V_DP));
        return sv;
    }
    /** {@code endInsetDp}: distance from the parent's end to the screen edge, so the row runs off-screen on the right. */
    private LinearLayout.LayoutParams cardScrollerParams(int top, int endInsetDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top - ui.dp(CARD_GUTTER_V_DP); lp.setMarginStart(-ui.dp(CARD_GUTTER_DP)); lp.setMarginEnd(-ui.dp(endInsetDp));
        return lp;
    }

    private LinearLayout row(LinearLayout column, int top) {
        HorizontalScrollView sv = cardScroller(START_END_INSET_DP);
        LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); r.setClipChildren(false);
        sv.addView(r);
        column.addView(sv, cardScrollerParams(top, START_END_INSET_DP));
        return r;
    }

    private void fillStart() {
        bookmarksRow.removeAllViews();
        int w = ui.dp(150), h = ui.dp(84);
        for (Store.Entry e : store.bookmarks) {
            LinearLayout cell = new LinearLayout(this); cell.setOrientation(LinearLayout.VERTICAL); cell.setGravity(Gravity.CENTER_HORIZONTAL); cell.setClipChildren(false);
            FrameLayout card = ui.card(w, h, colorFor(e.url));
            android.graphics.Bitmap ic = store.icon(host(e.url));
            if (ic != null) {
                ImageView iv = new ImageView(this); iv.setImageBitmap(ic); iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iv.setBackground(ui.rounded(0xFFFFFFFF, 10)); iv.setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6));
                card.addView(iv, new FrameLayout.LayoutParams(ui.dp(44), ui.dp(44), Gravity.CENTER));
            } else {
                TextView letter = ui.text(28, true, Ui.TEXT); letter.setText(initial(e)); letter.setGravity(Gravity.CENTER);
                card.addView(letter, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                fetchIcon(host(e.url));
            }
            card.setOnClickListener(v -> openUrl(e.url, true));
            card.setOnLongClickListener(v -> { showBookmarkMenu(v, e); return true; });
            cell.addView(card, new LinearLayout.LayoutParams(w, h));
            TextView name = ui.text(12, false, 0xC2FFFFFF); name.setText(e.title); name.setMaxLines(1); name.setPadding(0, ui.dp(8), 0, 0);
            cell.addView(name);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); lp.setMarginEnd(ui.dp(18));
            bookmarksRow.addView(cell, lp);
        }
        LinearLayout addCell = new LinearLayout(this); addCell.setOrientation(LinearLayout.VERTICAL); addCell.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout add = ui.card(w, h, 0x24FFFFFF);
        add.addView(ui.icon(R.drawable.ic_add, 30, 0xA6FFFFFF), new FrameLayout.LayoutParams(ui.dp(30), ui.dp(30), Gravity.CENTER));
        add.setOnClickListener(v -> showKeyboard(false, null));
        addCell.addView(add, new LinearLayout.LayoutParams(w, h));
        TextView an = ui.text(12, false, 0xC2FFFFFF); an.setText(getString(R.string.add)); an.setPadding(0, ui.dp(8), 0, 0); addCell.addView(an);
        bookmarksRow.addView(addCell);

        recentRow.removeAllViews();
        recentHeader.setVisibility(store.history.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        for (Store.Entry e : store.history.subList(0, Math.min(8, store.history.size()))) {
            LinearLayout r = ui.pill(0, "", true, false);
            r.setPadding(ui.dp(10), 0, ui.dp(14), 0);
            android.graphics.Bitmap ic = store.icon(host(e.url));
            if (ic != null) {
                ImageView iv = new ImageView(this); iv.setImageBitmap(ic); iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iv.setBackground(ui.rounded(0xFFFFFFFF, 5)); iv.setPadding(ui.dp(2), ui.dp(2), ui.dp(2), ui.dp(2));
                r.addView(iv, 0, new LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)));
            } else {
                TextView fav = ui.text(11, true, Ui.TEXT); fav.setText(initial(e)); fav.setGravity(Gravity.CENTER); fav.setShadowLayer(0, 0, 0, 0);
                fav.setBackground(ui.rounded(colorFor(e.url), 6)); fav.setWidth(ui.dp(22)); fav.setHeight(ui.dp(22));
                r.addView(fav, 0, new LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)));
            }
            TextView t = Ui.label(r); t.setText(shortUrl(e.url)); t.setMaxLines(1); t.setPadding(ui.dp(9), 0, 0, 0);
            r.setOnClickListener(v -> openUrl(e.url, true));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(40)); lp.setMarginEnd(ui.dp(10));
            recentRow.addView(r, lp);
        }
        int n = store.tabs.size();
        tabsText.setVisibility(n == 0 ? View.GONE : View.VISIBLE);
        tabsText.setText(getResources().getQuantityString(R.plurals.tab_count, n, n));
    }

    private final java.util.Set<String> iconFetches = new java.util.HashSet<>();

    /** Bookmarks never opened have no icon yet: fetch one from Google's favicon service (what Chrome's new tab page uses). */
    private void fetchIcon(String h) {
        if (h.isEmpty() || !iconFetches.add(h)) return;
        new Thread(() -> {
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL("https://www.google.com/s2/favicons?sz=64&domain=" + Uri.encode(h)).openConnection();
                c.setConnectTimeout(4000); c.setReadTimeout(4000);
                android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeStream(c.getInputStream());
                if (b != null && b.getWidth() > 16) { store.saveIcon(h, b); handler.post(() -> { if (screen == Screen.START) { View f = getCurrentFocus(); int idx = f == null ? -1 : bookmarksRow.indexOfChild(f.getParent() instanceof View ? (View) f.getParent() : f); fillStart(); if (idx >= 0 && idx < bookmarksRow.getChildCount()) ((ViewGroup) bookmarksRow.getChildAt(idx)).getChildAt(0).requestFocus(); } }); }
            } catch (Exception ignored) { }
        }, "favicon").start();
    }

    private static String host(String url) { try { String h = Uri.parse(url).getHost(); return h == null ? url : h.replaceFirst("^www\\.", ""); } catch (RuntimeException e) { return url; } }
    private static String shortUrl(String url) { String s = url.replaceFirst("^https?://(www\\.)?", ""); return s.length() > 34 ? s.substring(0, 33) + "…" : s; }
    private static String initial(Store.Entry e) { String h = host(e.url); return h.isEmpty() ? "?" : h.substring(0, 1).toUpperCase(Locale.ROOT); }
    private static int colorFor(String url) {
        int[] palette = {0xFF1E2A44, 0xFF7A2E2E, 0xFF2E5A3C, 0xFF3C2E7A, 0xFF7A5A2E, 0xFF2E6A7A, 0xFF5A2E7A, 0xFF7A2E5A};
        return palette[Math.abs(host(url).hashCode()) % palette.length];
    }

    private void buildTopBar() {
        topBar = new LinearLayout(this); topBar.setOrientation(LinearLayout.HORIZONTAL); topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackground(ui.rounded(0xE60A0E1E, 24)); topBar.setPadding(ui.dp(12), 0, ui.dp(12), 0); topBar.setClipChildren(false);
        topBar.setVisibility(View.GONE); topBar.setElevation(ui.dp(16));
        LinearLayout back = ui.pill(R.drawable.ic_arrow_left, "", true, true); back.setOnClickListener(v -> { if (web != null && web.canGoBack()) web.goBack(); });
        LinearLayout fwd = ui.pill(R.drawable.ic_arrow_right, "", true, true); fwd.setOnClickListener(v -> { if (web != null && web.canGoForward()) web.goForward(); });
        LinearLayout reload = ui.pill(R.drawable.ic_refresh, "", true, true); reload.setOnClickListener(v -> { if (web != null) web.reload(); hideTopBar(); });
        urlText = ui.text(14, false, Ui.TEXT); urlText.setMaxLines(1); urlText.setShadowLayer(0, 0, 0, 0); urlText.setPadding(ui.dp(8), 0, ui.dp(8), 0);
        urlText.setFocusable(true); urlText.setClickable(true); urlText.setBackground(ui.rounded(0, 14));
        urlText.setOnFocusChangeListener((v, f) -> { ((GradientDrawable) v.getBackground()).setColor(f ? 0x33FFFFFF : 0); });
        urlText.setOnClickListener(v -> showKeyboard(false, null));
        LinearLayout blocked = ui.pill(R.drawable.ic_shield, "", true, false); blockedText = Ui.label(blocked); blocked.setFocusable(false);
        LinearLayout mode = ui.pill(R.drawable.ic_pointer, getString(R.string.cursor_mode), true, false); modeText = Ui.label(mode); mode.setOnClickListener(v -> { toggleCursor(); hideTopBar(); });
        LinearLayout mark = ui.pill(R.drawable.ic_bookmark, "", true, true); mark.setOnClickListener(v -> toggleBookmark());
        LinearLayout tabs = ui.pill(R.drawable.ic_tabs, "", true, false); tabs.setOnClickListener(v -> showTabs());
        LinearLayout more = ui.pill(R.drawable.ic_settings, "", true, true); more.setOnClickListener(v -> showPageMenu(v));
        for (View v : new View[]{back, fwd, reload}) { LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(36)); topBar.addView(v, lp); }
        topBar.addView(urlText, new LinearLayout.LayoutParams(0, ui.dp(36), 1f));
        for (View v : new View[]{blocked, mode, mark, tabs, more}) { LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(36)); lp.setMarginStart(ui.dp(6)); topBar.addView(v, lp); }
        topScrim = new View(this); topScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xD9000000, 0x00000000})); topScrim.setVisibility(View.GONE);
        root.addView(topScrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(140), Gravity.TOP));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(50), Gravity.TOP);
        lp.setMargins(ui.dp(48), ui.dp(28), ui.dp(48), 0);
        root.addView(topBar, lp);

        progress = new View(this); progress.setBackgroundColor(0xFF2F7DFF); progress.setVisibility(View.GONE);
        root.addView(progress, new FrameLayout.LayoutParams(0, ui.dp(3), Gravity.TOP | Gravity.START));

        hintRow = new LinearLayout(this); hintRow.setOrientation(LinearLayout.HORIZONTAL); hintRow.setGravity(Gravity.CENTER_VERTICAL);
        hintRow.setBackground(ui.rounded(0xB30A0E1E, 18)); hintRow.setPadding(ui.dp(14), ui.dp(6), ui.dp(6), ui.dp(6)); hintRow.setVisibility(View.GONE);
        hintRow.addView(ui.hint(new int[]{R.drawable.ic_arrow_up}, null, getString(R.string.search)));
        hintRow.addView(ui.hint(new int[0], "OK", getString(R.string.cursor_mode)));
        FrameLayout.LayoutParams hrl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        hrl.setMargins(0, 0, ui.dp(48), ui.dp(28)); root.addView(hintRow, hrl);
    }

    private void buildCursor() {
        cursor = ui.icon(R.drawable.ic_pointer, 28, Ui.TEXT); cursor.setVisibility(View.GONE); cursor.setElevation(ui.dp(20));
        cursor.setBackground(ui.rounded(0x99000000, 14)); cursor.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4));
        root.addView(cursor, new FrameLayout.LayoutParams(ui.dp(36), ui.dp(36)));
    }

    private void buildKeyboard() {
        keyboardPanel = new FrameLayout(this); keyboardPanel.setBackgroundColor(0xF2141C2E); keyboardPanel.setVisibility(View.GONE);
        LinearLayout column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL); column.setClipChildren(false);
        column.setPadding(ui.dp(56), ui.dp(40), ui.dp(56), ui.dp(28));
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.HORIZONTAL); box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackground(ui.rounded(0x1AFFFFFF, 23)); box.setPadding(ui.dp(18), 0, ui.dp(18), 0);
        box.addView(ui.icon(R.drawable.ic_search, 22, Ui.TEXT));
        typedView = ui.text(17, false, Ui.TEXT); typedView.setShadowLayer(0, 0, 0, 0); typedView.setPadding(ui.dp(12), 0, ui.dp(12), 0); typedView.setMaxLines(1);
        box.addView(typedView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(ui.icon(R.drawable.ic_mic, 18, 0xBFFFFFFF));
        TextView vh = ui.text(12, false, 0xBFFFFFFF); vh.setText(getString(R.string.voice_hint)); vh.setShadowLayer(0, 0, 0, 0); vh.setPadding(ui.dp(8), 0, 0, 0); box.addView(vh);
        column.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(46)));

        suggestions = new LinearLayout(this); suggestions.setOrientation(LinearLayout.HORIZONTAL); suggestions.setClipChildren(false);
        HorizontalScrollView ssv = new HorizontalScrollView(this); ssv.setHorizontalScrollBarEnabled(false); ssv.setClipChildren(false); ssv.setClipToPadding(false);
        ssv.setPadding(0, ui.dp(6), 0, ui.dp(6)); ssv.addView(suggestions);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(46)); sl.topMargin = ui.dp(10);
        column.addView(ssv, sl);

        LinearLayout main = new LinearLayout(this); main.setOrientation(LinearLayout.HORIZONTAL); main.setClipChildren(false);
        keyGrid = new GridLayout(this); keyGrid.setColumnCount(7); keyGrid.setClipChildren(false);
        LinearLayout.LayoutParams kgl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 7f);
        kgl.setMarginStart(-ui.dp(3)); // key cells carry a 3dp margin; pull the grid out so key edges align with the search box
        main.addView(keyGrid, kgl);
        LinearLayout fn = new LinearLayout(this); fn.setOrientation(LinearLayout.VERTICAL); fn.setClipChildren(false);
        fn.setPadding(ui.dp(24), 0, 0, 0);
        fn.addView(keyButton(R.drawable.ic_space, getString(R.string.space), v -> type(" ")));
        View del = keyButton(R.drawable.ic_backspace, getString(R.string.delete), v -> { if (typed.length() > 0) typed.setLength(typed.length() - 1); typedChanged(); });
        del.setOnLongClickListener(v -> { int i = typed.lastIndexOf(" "); typed.setLength(Math.max(0, i)); typedChanged(); return true; });
        fn.addView(del);
        fn.addView(keyButton(R.drawable.ic_trash, getString(R.string.clear), v -> { typed.setLength(0); typedChanged(); }));
        fn.addView(keyButton(0, ".com", v -> type(".com")));
        fn.addView(keyButton(0, getString(R.string.symbols), v -> { symbols = !symbols; fillKeys(); }));
        View go = keyButton(R.drawable.ic_arrow_right, getString(R.string.go), v -> keyboardGo());
        ((GradientDrawable) go.getBackground()).setColor(0xFF2F7DFF);
        fn.addView(go);
        main.addView(fn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2.3f));
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); ml.topMargin = ui.dp(20);
        column.addView(main, ml);

        LinearLayout kh = new LinearLayout(this); kh.setOrientation(LinearLayout.HORIZONTAL); kh.setGravity(Gravity.CENTER_VERTICAL);
        kh.addView(ui.hint(new int[0], "OK", Locale.getDefault().getLanguage().equals("tr") ? "yaz" : "type"));
        kh.addView(ui.hint(new int[]{R.drawable.ic_play, R.drawable.ic_pause}, null, getString(R.string.space)));
        kh.addView(ui.hint(new int[]{R.drawable.ic_mic}, null, getString(R.string.search)));
        LinearLayout.LayoutParams khl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); khl.topMargin = ui.dp(18);
        column.addView(kh, khl);
        keyboardPanel.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(keyboardPanel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        fillKeys();
    }

    private View keyButton(int icon, String label, View.OnClickListener click) {
        LinearLayout b = ui.pill(icon, label, true, false);
        ((GradientDrawable) b.getBackground()).setCornerRadius(ui.dp(9)); ((GradientDrawable) b.getBackground()).setColor(0x1FFFFFFF);
        b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(40)); lp.bottomMargin = ui.dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private static final String LETTERS = "abcçdefgğhıijklmnoöprsştuüvyzwxq1234567890";
    private static final String SYMBOLS = ".-/:@?=&_#%+!,;'\"()[]{}<>~*^$|\\0123456789";

    private void fillKeys() {
        keyGrid.removeAllViews();
        String keys = symbols ? SYMBOLS : LETTERS;
        // grid gets 7/9.3 of the row (see buildKeyboard); 7 columns with 3dp margins each side
        int gridWidth = (int) ((getResources().getDisplayMetrics().widthPixels - ui.dp(112)) * 7 / 9.3f);
        int cell = gridWidth / 7 - ui.dp(6);
        for (int i = 0; i < keys.length(); i++) {
            final String ch = String.valueOf(keys.charAt(i));
            TextView k = ui.text(17, false, Ui.TEXT); k.setText(ch); k.setGravity(Gravity.CENTER); k.setShadowLayer(0, 0, 0, 0);
            k.setFocusable(true); k.setClickable(true);
            GradientDrawable bg = ui.rounded(0x1FFFFFFF, 8); k.setBackground(bg);
            k.setOnFocusChangeListener((v, f) -> { bg.setColor(f ? Ui.PILL_FOCUS : 0x1FFFFFFF); k.setTextColor(f ? Ui.TEXT_DARK : Ui.TEXT);
                v.animate().scaleX(f ? 1.1f : 1f).scaleY(f ? 1.1f : 1f).setDuration(160).setInterpolator(Ui.SPRING).start(); v.setElevation(f ? ui.dp(8) : 0); });
            k.setOnClickListener(v -> type(ch));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(); lp.width = cell; lp.height = ui.dp(40); lp.setMargins(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3));
            keyGrid.addView(k, lp);
        }
    }

    private void type(String s) { typed.append(s); typedChanged(); }

    private void typedChanged() {
        typedView.setText(typed.length() == 0 ? getString(R.string.search_hint) : typed.toString());
        typedView.setTextColor(typed.length() == 0 ? 0x99FFFFFF : Ui.TEXT);
        suggestions.removeAllViews();
        if (keyboardForField) return;
        for (Store.Entry e : store.suggest(typed.toString(), 3)) addSuggestion(store.bookmarkFor(e.url) != null ? R.drawable.ic_bookmark : R.drawable.ic_history, shortUrl(e.url), () -> openUrl(e.url, true));
        handler.removeCallbacks(fetchSuggest);
        if (typed.length() >= 2) handler.postDelayed(fetchSuggest, 250);
    }

    private void addSuggestion(int icon, String label, Runnable open) {
        LinearLayout p = ui.pill(icon, label, true, false);
        p.setOnClickListener(v -> open.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(34)); lp.setMarginEnd(ui.dp(8));
        suggestions.addView(p, lp);
    }

    /** Google's autocomplete endpoint (the same one Chrome and the YouTube/Google TV search use). */
    private final Runnable fetchSuggest = () -> {
        final String q = typed.toString().trim();
        new Thread(() -> {
            List<String> out = new ArrayList<>();
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL("https://suggestqueries.google.com/complete/search?client=firefox&hl="
                        + Locale.getDefault().getLanguage() + "&q=" + Uri.encode(q)).openConnection();
                c.setConnectTimeout(3000); c.setReadTimeout(3000);
                try (InputStream in = c.getInputStream(); java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream()) {
                    byte[] buf = new byte[4096]; int n; while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    org.json.JSONArray arr = new org.json.JSONArray(new String(bo.toByteArray(), StandardCharsets.UTF_8)).getJSONArray(1);
                    for (int i = 0; i < arr.length() && out.size() < 5; i++) out.add(arr.getString(i));
                }
            } catch (Exception ignored) { }
            handler.post(() -> {
                if (!q.equals(typed.toString().trim()) || screen != Screen.KEYBOARD) return; // stale
                for (String sug : out) addSuggestion(R.drawable.ic_search, sug, () -> openUrl(toUrl(sug), true));
            });
        }, "suggest").start();
    };

    private void showKeyboard(boolean forField, String initial) {
        keyboardForField = forField; symbols = false; fillKeys();
        typed.setLength(0); if (initial != null) typed.append(initial);
        typedChanged();
        keyboardPanel.setAlpha(0f); keyboardPanel.setVisibility(View.VISIBLE);
        keyboardPanel.animate().alpha(1f).setDuration(200).start();
        if (screen == Screen.START) startPanel.setVisibility(View.INVISIBLE); // wallpaper stays, content doesn't bleed through
        hideTopBar();
        screen = Screen.KEYBOARD;
        keyGrid.getChildAt(0).requestFocus();
    }

    private void hideKeyboard() {
        keyboardPanel.animate().alpha(0f).setDuration(160).withEndAction(() -> keyboardPanel.setVisibility(View.GONE)).start();
        screen = web != null && webContainer.getVisibility() == View.VISIBLE ? Screen.PAGE : Screen.START;
        if (screen == Screen.START) { startPanel.setVisibility(View.VISIBLE); focusStart(); } else if (web != null) web.requestFocus();
    }

    private void keyboardGo() {
        String text = typed.toString().trim();
        if (keyboardForField) { js("window.tvnav && tvnav.setText(" + quote(text) + ")"); hideKeyboard(); return; }
        if (text.isEmpty()) return;
        openUrl(toUrl(text), true);
    }

    private String toUrl(String text) {
        if (text.matches("^[a-z]+://.*")) return text;
        if (!text.contains(" ") && text.contains(".")) return "https://" + text;
        String engine = store.searchEngine();
        String tpl = engine.equals("duckduckgo") ? "https://duckduckgo.com/?q=%s" : engine.equals("bing") ? "https://www.bing.com/search?q=%s" : getString(R.string.search_url);
        return String.format(tpl, Uri.encode(text));
    }

    private void buildHud() {
        hudPanel = new FrameLayout(this); hudPanel.setVisibility(View.GONE);
        View grad = new View(this); grad.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0xE6000000}));
        hudPanel.addView(grad, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(220), Gravity.BOTTOM));
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout t1 = new LinearLayout(this); t1.setOrientation(LinearLayout.HORIZONTAL); t1.setGravity(Gravity.BOTTOM);
        LinearLayout tt = new LinearLayout(this); tt.setOrientation(LinearLayout.VERTICAL);
        hudTitle = ui.text(20, true, Ui.TEXT); hudMeta = ui.text(12, false, 0xB3FFFFFF); hudMeta.setPadding(0, ui.dp(4), 0, 0);
        tt.addView(hudTitle); tt.addView(hudMeta);
        t1.addView(tt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        hudTime = ui.text(14, false, Ui.TEXT); t1.addView(hudTime);
        col.addView(t1);
        FrameLayout bar = new FrameLayout(this);
        View track = new View(this); track.setBackground(ui.rounded(0x47FFFFFF, 3)); bar.addView(track, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(6), Gravity.CENTER_VERTICAL));
        hudFill = new View(this); hudFill.setBackground(ui.rounded(0xFFFFFFFF, 3)); bar.addView(hudFill, new FrameLayout.LayoutParams(0, ui.dp(6), Gravity.CENTER_VERTICAL | Gravity.START));
        hudKnob = new View(this); hudKnob.setBackground(ui.rounded(0xFFFFFFFF, 8)); bar.addView(hudKnob, new FrameLayout.LayoutParams(ui.dp(16), ui.dp(16), Gravity.CENTER_VERTICAL | Gravity.START));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(16)); bl.topMargin = ui.dp(14); col.addView(bar, bl);
        LinearLayout keys = new LinearLayout(this); keys.setOrientation(LinearLayout.HORIZONTAL); keys.setGravity(Gravity.CENTER_VERTICAL);
        keys.addView(ui.hint(new int[]{R.drawable.ic_arrow_left, R.drawable.ic_arrow_right}, null, getString(R.string.hint_video_seek)));
        keys.addView(ui.hint(new int[0], "OK", getString(R.string.hint_video_pause)));
        keys.addView(ui.hint(new int[]{R.drawable.ic_arrow_down}, null, getString(R.string.hint_video_rate)));
        keys.addView(ui.hint(new int[]{R.drawable.ic_arrow_left}, null, getString(R.string.hint_video_exit)));
        LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); kl.topMargin = ui.dp(14); col.addView(keys, kl);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        cl.setMargins(ui.dp(56), 0, ui.dp(56), ui.dp(40)); hudPanel.addView(col, cl);
        root.addView(hudPanel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** Full-screen frosted panel used by the tabs and settings screens. */
    private void buildOverlay() {
        overlayPanel = new FrameLayout(this); overlayPanel.setBackgroundColor(0xF2141C2E); overlayPanel.setVisibility(View.GONE);
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL); col.setClipChildren(false); col.setClipToPadding(false);
        col.setPadding(ui.dp(OVERLAY_INSET_DP), ui.dp(44), ui.dp(OVERLAY_INSET_DP), ui.dp(28));
        overlayTitle = ui.text(22, true, Ui.TEXT); col.addView(overlayTitle);
        overlayBody = new LinearLayout(this); overlayBody.setOrientation(LinearLayout.VERTICAL); overlayBody.setClipChildren(false);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); bl.topMargin = ui.dp(24);
        col.addView(overlayBody, bl);
        overlayPanel.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(overlayPanel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private boolean overlayShowing() { return overlayPanel.getVisibility() == View.VISIBLE; }

    private void showOverlay(String title) {
        overlayTitle.setText(title); overlayBody.removeAllViews();
        if (screen == Screen.START) startPanel.setVisibility(View.INVISIBLE);
        hideTopBar();
        overlayPanel.setAlpha(0f); overlayPanel.setTranslationY(ui.dp(30)); overlayPanel.setVisibility(View.VISIBLE);
        overlayPanel.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(new DecelerateInterpolator()).start();
    }

    private void hideOverlay() {
        overlayPanel.animate().alpha(0f).translationY(ui.dp(30)).setDuration(160).withEndAction(() -> overlayPanel.setVisibility(View.GONE)).start();
        if (screen == Screen.START) { startPanel.setVisibility(View.VISIBLE); fillStart(); focusStart(); } else if (web != null) web.requestFocus();
    }

    /** Tabs: one 16:9 card per tab (favicon, title, host); OK switches, long-press closes, "+" opens a new one. */
    private void showTabs() {
        showOverlay(getString(R.string.tabs));
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setClipChildren(false);
        int w = ui.dp(230), h = ui.dp(130);
        for (int i = 0; i < store.tabs.size(); i++) {
            final int idx = i; Store.Entry t = store.tabs.get(i);
            FrameLayout card = ui.card(w, h, i == store.activeTab ? 0xFF24314F : 0xFF1A2238);
            LinearLayout inner = new LinearLayout(this); inner.setOrientation(LinearLayout.VERTICAL); inner.setGravity(Gravity.CENTER); inner.setPadding(ui.dp(16), 0, ui.dp(16), 0);
            android.graphics.Bitmap ic = store.icon(host(t.url));
            if (ic != null) { ImageView iv = new ImageView(this); iv.setImageBitmap(ic); iv.setBackground(ui.rounded(0xFFFFFFFF, 8)); iv.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)); inner.addView(iv, new LinearLayout.LayoutParams(ui.dp(36), ui.dp(36))); }
            else { TextView l = ui.text(22, true, Ui.TEXT); l.setText(initial(t)); l.setGravity(Gravity.CENTER); l.setBackground(ui.rounded(colorFor(t.url), 8)); inner.addView(l, new LinearLayout.LayoutParams(ui.dp(36), ui.dp(36))); }
            TextView title = ui.text(14, true, Ui.TEXT); title.setText(t.title.isEmpty() ? host(t.url) : t.title); title.setMaxLines(1); title.setEllipsize(android.text.TextUtils.TruncateAt.END); title.setGravity(Gravity.CENTER); title.setPadding(0, ui.dp(10), 0, 0);
            inner.addView(title);
            if (!t.title.isEmpty()) { TextView sub = ui.text(12, false, 0xB3FFFFFF); sub.setText(host(t.url)); sub.setMaxLines(1); sub.setGravity(Gravity.CENTER); inner.addView(sub); } // no duplicate host line when the title is the host
            card.addView(inner, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            card.setOnClickListener(v -> { saveActiveTab(); overlayPanel.setVisibility(View.GONE); loadTab(idx); });
            card.setOnLongClickListener(v -> { closeTab(idx); return true; });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h); lp.setMarginEnd(ui.dp(18)); row.addView(card, lp);
        }
        if (store.tabs.size() < getResources().getInteger(R.integer.max_tabs)) {
            FrameLayout add = ui.card(w, h, 0x24FFFFFF);
            add.addView(ui.icon(R.drawable.ic_add, 34, 0xA6FFFFFF), new FrameLayout.LayoutParams(ui.dp(34), ui.dp(34), Gravity.CENTER));
            add.setOnClickListener(v -> { saveActiveTab(); store.tabs.add(new Store.Entry("", "about:blank")); store.activeTab = store.tabs.size() - 1; store.saveTabs(); destroyWeb(); overlayPanel.setVisibility(View.GONE); showStart(); showKeyboard(false, null); });
            row.addView(add, new LinearLayout.LayoutParams(w, h));
        }
        HorizontalScrollView sv = cardScroller(OVERLAY_INSET_DP); sv.addView(row);
        overlayBody.addView(sv, cardScrollerParams(ui.dp(CARD_GUTTER_V_DP), OVERLAY_INSET_DP));
        if (store.tabs.size() > 1) {
            // two-step: the first OK arms the pill (red, asks again), the second closes everything
            LinearLayout all = ui.pill(R.drawable.ic_trash, getString(R.string.close_all_tabs), true, false);
            TextView allLabel = Ui.label(all); GradientDrawable allBg = (GradientDrawable) all.getBackground();
            View.OnFocusChangeListener base = all.getOnFocusChangeListener();
            boolean[] armed = {false};
            Runnable disarm = () -> { armed[0] = false; allLabel.setText(R.string.close_all_tabs); base.onFocusChange(all, all.hasFocus()); };
            all.setOnFocusChangeListener((v, f) -> { base.onFocusChange(v, f); if (!f && armed[0]) disarm.run(); });
            all.setOnClickListener(v -> {
                if (armed[0]) { handler.removeCallbacks(disarm); closeAllTabs(); return; }
                armed[0] = true; allLabel.setText(R.string.close_all_confirm); allLabel.setTextColor(Ui.TEXT); allBg.setColor(0xFFE5484D);
                ((ImageView) all.getChildAt(0)).setImageTintList(android.content.res.ColorStateList.valueOf(Ui.TEXT));
                handler.postDelayed(disarm, 3000);
            });
            LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(38)); al.topMargin = ui.dp(6);
            overlayBody.addView(all, al);
        }
        LinearLayout hint = new LinearLayout(this); hint.setOrientation(LinearLayout.HORIZONTAL); hint.setGravity(Gravity.CENTER_VERTICAL);
        hint.addView(ui.hint(new int[0], "OK", openWord())); hint.addView(ui.hint(new int[0], "OK", getString(R.string.hint_hold_close))); hint.addView(ui.hint(new int[]{R.drawable.ic_arrow_left}, null, getString(R.string.done).toLowerCase(Locale.getDefault())));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); hl.topMargin = ui.dp(20); overlayBody.addView(hint, hl);
        if (row.getChildCount() > 0) row.getChildAt(Math.min(store.activeTab, row.getChildCount() - 1)).requestFocus();
    }

    private void closeTab(int idx) {
        if (idx < 0 || idx >= store.tabs.size()) return;
        boolean wasActive = idx == store.activeTab;
        store.tabs.remove(idx);
        if (store.activeTab >= store.tabs.size()) store.activeTab = Math.max(0, store.tabs.size() - 1);
        store.saveTabs();
        if (wasActive) { destroyWeb(); if (screen == Screen.PAGE) { overlayPanel.setVisibility(View.GONE); if (store.tabs.isEmpty()) showStart(); else loadTab(store.activeTab); return; } }
        showTabs();
    }

    private void closeAllTabs() {
        store.tabs.clear(); store.activeTab = 0; store.saveTabs();
        destroyWeb();
        overlayPanel.setVisibility(View.GONE);
        showStart();
    }

    /** Settings: rows of label + value pill; OK toggles / cycles; destructive rows act at once. */
    private void showSettings() {
        showOverlay(getString(R.string.settings));
        settingRow(getString(R.string.setting_adblock), store.adBlock() ? getString(R.string.on) : getString(R.string.off), () -> { store.set("adblock", !store.adBlock()); showSettings(); });
        settingRow(getString(R.string.setting_desktop), store.desktopDefault() ? getString(R.string.desktop_site) : getString(R.string.mobile_site), () -> { store.set("desktop", !store.desktopDefault()); showSettings(); });
        String eng = store.searchEngine();
        settingRow(getString(R.string.setting_engine), eng.equals("duckduckgo") ? "DuckDuckGo" : eng.equals("bing") ? "Bing" : "Google", () -> { store.set("engine", eng.equals("google") ? "duckduckgo" : eng.equals("duckduckgo") ? "bing" : "google"); showSettings(); });
        settingRow(getString(R.string.clear_history), "", () -> { store.clearHistory(); showToast(getString(R.string.done)); });
        settingRow(getString(R.string.clear_cookies), "", () -> { CookieManager.getInstance().removeAllCookies(null); WebStorage.getInstance().deleteAllData(); if (web != null) web.clearCache(true); showToast(getString(R.string.done)); });
        settingRow(getString(R.string.about), getString(R.string.app_name) + " 0.1 · " + adBlockSize(), null);
        overlayBody.getChildAt(0).requestFocus();
    }

    private String adBlockSize() { return "262K " + (Locale.getDefault().getLanguage().equals("tr") ? "engelli alan adı" : "blocked domains"); }

    private void settingRow(String label, String value, Runnable action) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ui.dp(18), 0, ui.dp(18), 0); row.setFocusable(action != null); row.setClickable(action != null);
        GradientDrawable bg = ui.rounded(0x14FFFFFF, 12); row.setBackground(bg);
        TextView l = ui.text(16, false, Ui.TEXT); l.setText(label); l.setShadowLayer(0, 0, 0, 0);
        row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = ui.text(14, false, 0xD9FFFFFF); v.setText(value); v.setShadowLayer(0, 0, 0, 0);
        if (!value.isEmpty()) { v.setBackground(ui.rounded(0x2EFFFFFF, 14)); v.setPadding(ui.dp(12), ui.dp(5), ui.dp(12), ui.dp(5)); }
        row.addView(v);
        row.setOnFocusChangeListener((x, f) -> { bg.setColor(f ? Ui.PILL_FOCUS : 0x14FFFFFF); l.setTextColor(f ? Ui.TEXT_DARK : Ui.TEXT); v.setTextColor(f ? Ui.TEXT_DARK : 0xD9FFFFFF); if (!value.isEmpty()) ((GradientDrawable) v.getBackground()).setColor(f ? 0x1A000000 : 0x2EFFFFFF); });
        if (action != null) row.setOnClickListener(x -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.dp(620), ui.dp(52)); lp.bottomMargin = ui.dp(10);
        overlayBody.addView(row, lp);
    }

    // ---------- screens ----------

    private void showStart() {
        screen = Screen.START; videoActive = false;
        hideTopBar(); cursor.setVisibility(View.GONE); hintRow.setVisibility(View.GONE); hudPanel.setVisibility(View.GONE);
        webContainer.setVisibility(View.GONE);
        startPanel.setAlpha(0f); startPanel.setVisibility(View.VISIBLE); startPanel.animate().alpha(1f).setDuration(220).start();
        wallpapers.start(); startKenBurns();
        fillStart(); focusStart();
    }

    private void focusStart() { if (bookmarksRow.getChildCount() > 0) bookmarksRow.getChildAt(0).requestFocus(); else rail.getChildAt(0).requestFocus(); }

    private void showPage() {
        screen = Screen.PAGE;
        wallpapers.stop(); if (kenBurns != null) kenBurns.pause();
        startPanel.setVisibility(View.GONE); keyboardPanel.setVisibility(View.GONE);
        webContainer.setVisibility(View.VISIBLE);
        if (web != null) web.requestFocus();
        hintRow.setVisibility(View.VISIBLE); hintRow.setAlpha(1f);
        handler.postDelayed(() -> hintRow.animate().alpha(0f).setDuration(400).withEndAction(() -> hintRow.setVisibility(View.GONE)).start(), 4000);
    }

    // ---------- tabs & webview ----------

    private void openUrl(String url, boolean newTabIfNone) {
        if (store.tabs.isEmpty()) { store.tabs.add(new Store.Entry("", url)); store.activeTab = 0; }
        else if (newTabIfNone && screen == Screen.START && store.tabs.size() < getResources().getInteger(R.integer.max_tabs)) {
            // from the start page a bookmark opens in a new tab, like a TV app launch
            store.tabs.add(new Store.Entry("", url)); store.activeTab = store.tabs.size() - 1;
        } else { Store.Entry t = store.tabs.get(store.activeTab); t.url = url; t.title = ""; t.scroll = 0; }
        store.saveTabs();
        keyboardPanel.setVisibility(View.GONE);
        ensureWeb();
        showPage();
        web.loadUrl(url);
    }

    private void loadTab(int index) {
        store.activeTab = Math.max(0, Math.min(index, store.tabs.size() - 1));
        destroyWeb(); ensureWeb(); showPage();
        Store.Entry t = store.tabs.get(store.activeTab);
        web.loadUrl(t.url);
    }

    private void saveActiveTab() {
        if (web == null || store.tabs.isEmpty()) return;
        Store.Entry t = store.tabs.get(store.activeTab);
        if (web.getUrl() != null) t.url = web.getUrl();
        if (web.getTitle() != null) t.title = web.getTitle();
        t.scroll = web.getScrollY();
    }

    private void ensureWeb() {
        if (web != null) return;
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(false); s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setLoadWithOverviewMode(true); s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false); s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (store.desktopDefault()) s.setUserAgentString(getResources().getString(R.string.desktop_ua));
        web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true);
        web.setBackgroundColor(Color.BLACK);
        web.setFocusable(true); web.setFocusableInTouchMode(true);
        CookieManager.getInstance().setAcceptCookie(true);
        web.addJavascriptInterface(new Bridge(), "tvbridge");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        webContainer.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void destroyWeb() {
        if (web == null) return;
        webContainer.removeView(web);
        web.stopLoading(); web.loadUrl("about:blank"); web.destroy(); web = null;
        videoActive = false; hudPanel.setVisibility(View.GONE);
    }

    private final class Client extends WebViewClient {
        @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
            if (r.isForMainFrame() || !store.adBlock()) return null;
            if (adBlock.isBlocked(r.getUrl().getHost())) { adBlock.blockedCount++; handler.post(() -> blockedText.setText(String.format(getString(R.string.blocked), adBlock.blockedCount))); return AdBlock.empty(); }
            return null;
        }
        @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            String scheme = r.getUrl().getScheme();
            return !(scheme != null && (scheme.equals("http") || scheme.equals("https"))); // intent:, market:, tel: … are dropped
        }
        @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap favicon) {
            adBlock.blockedCount = 0; blockedText.setText(String.format(getString(R.string.blocked), 0));
            urlText.setText(shortUrl(url)); videoActive = false; hudPanel.setVisibility(View.GONE);
            progress.setVisibility(View.VISIBLE);
            String host = host(url);
            boolean desktop = store.desktopDefault() != store.mobileSite(host); // per-site toggle flips the default
            v.getSettings().setUserAgentString(desktop ? getResources().getString(R.string.desktop_ua) : null);
        }
        @Override public void onPageCommitVisible(WebView v, String url) {
            js("(function(){var s=document.createElement('style');s.id='__tvcss';s.textContent=" + quote(cosmeticCss) + ";(document.head||document.documentElement).appendChild(s);})()");
        }
        @Override public void onPageFinished(WebView v, String url) {
            progress.setVisibility(View.GONE);
            js(navJs);
            store.visited(v.getTitle(), url);
            Store.Entry t = store.tabs.get(store.activeTab); t.url = url; t.title = v.getTitle() == null ? "" : v.getTitle();
            store.saveTabs(); // persist the real title now: the process can be killed without onStop
            handler.postDelayed(() -> js("window.tvnav && tvnav.removeOverlays() && tvbridge.toast('overlay')"), 1500);
            if (!cursorMode) handler.postDelayed(() -> js("window.tvnav && tvnav.move('down')"), 300);
        }
        @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
            saveActiveTab(); destroyWeb(); handler.post(() -> loadTab(store.activeTab)); // rebuilt silently
            return true;
        }
    }

    private final class Chrome extends WebChromeClient {
        @Override public void onProgressChanged(WebView v, int p) {
            progress.getLayoutParams().width = root.getWidth() * p / 100; progress.requestLayout();
            if (p >= 100) progress.setVisibility(View.GONE);
        }
        @Override public void onReceivedTitle(WebView v, String title) { if (!store.tabs.isEmpty()) store.tabs.get(store.activeTab).title = title; }
        @Override public void onReceivedIcon(WebView v, android.graphics.Bitmap icon) { if (v.getUrl() != null) store.saveIcon(host(v.getUrl()), icon); }
        @Override public void onShowCustomView(View view, CustomViewCallback cb) {
            if (fullscreenView != null) { cb.onCustomViewHidden(); return; }
            fullscreenView = view; fullscreenCallback = cb;
            fullscreenContainer.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            fullscreenContainer.setVisibility(View.VISIBLE); webContainer.setVisibility(View.INVISIBLE);
            cursor.setVisibility(View.GONE); hideTopBar();
            videoActive = true; showHud();
        }
        @Override public void onHideCustomView() {
            if (fullscreenView == null) return;
            fullscreenContainer.removeView(fullscreenView); fullscreenContainer.setVisibility(View.GONE); webContainer.setVisibility(View.VISIBLE);
            fullscreenView = null; if (fullscreenCallback != null) fullscreenCallback.onCustomViewHidden(); fullscreenCallback = null;
            hudPanel.setVisibility(View.GONE); if (cursorMode) cursor.setVisibility(View.VISIBLE);
            if (web != null) web.requestFocus();
        }
        @Override public void onPermissionRequest(PermissionRequest r) {
            List<String> grant = new ArrayList<>();
            for (String res : r.getResources()) if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(res)) grant.add(res); // DRM only
            if (grant.isEmpty()) r.deny(); else r.grant(grant.toArray(new String[0]));
        }
    }

    /** Called from nav.js on the WebView's JS thread. */
    private final class Bridge {
        @JavascriptInterface public void ready(String title, String url) { }
        @JavascriptInterface public void focused(String label, String tag, String href) { focusedHref = href; }
        @JavascriptInterface public void editText(String value, String placeholder) { handler.post(() -> showKeyboard(true, value)); }
        @JavascriptInterface public void toast(String what) { handler.post(() -> showToast(getString(R.string.overlay_removed))); }
        @JavascriptInterface public void video(String report) {
            handler.post(() -> {
                if (report.isEmpty()) { if (videoActive && fullscreenView == null) { videoActive = false; hudPanel.setVisibility(View.GONE); } return; }
                String[] p = report.split("\\|", 5);
                videoPlaying = "1".equals(p[0]); videoPos = Integer.parseInt(p[1]); videoDur = Integer.parseInt(p[2]); videoRate = Float.parseFloat(p[3]);
                hudTitle.setText(p.length > 4 ? p[4] : ""); updateHud();
                if (videoPlaying && !videoActive) { videoActive = true; showHud(); }
            });
        }
    }

    private void js(String code) { if (web != null) web.evaluateJavascript(code, null); }
    private static String quote(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "").replace("</", "<\\/") + "\""; }

    // ---------- video HUD ----------

    private void showHud() {
        updateHud();
        handler.removeCallbacks(hideHud);
        hudPanel.setVisibility(View.VISIBLE); hudPanel.animate().alpha(1f).setDuration(150).start();
        handler.postDelayed(hideHud, HUD_MS);
    }

    private void updateHud() {
        String cur = web == null || web.getUrl() == null ? "" : host(web.getUrl());
        hudMeta.setText(cur + " · " + (videoRate == 1f ? "1.0×" : videoRate + "×"));
        hudTime.setText(clockText(videoPos) + " / " + clockText(videoDur));
        int w = root.getWidth() - ui.dp(112);
        float frac = videoDur > 0 ? Math.min(1f, videoPos / (float) videoDur) : 0f;
        hudFill.getLayoutParams().width = (int) (w * frac); hudFill.requestLayout();
        ((FrameLayout.LayoutParams) hudKnob.getLayoutParams()).setMarginStart((int) (w * frac) - ui.dp(8)); hudKnob.requestLayout();
    }

    private static String clockText(int s) { return s >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format(Locale.US, "%d:%02d", s / 60, s % 60); }

    // ---------- top bar, cursor, menus ----------

    private void showTopBar() {
        if (topBarShown) return;
        topBarShown = true;
        topScrim.setAlpha(0f); topScrim.setVisibility(View.VISIBLE); topScrim.animate().alpha(1f).setDuration(200).start();
        topBar.setAlpha(0f); topBar.setTranslationY(-ui.dp(20)); topBar.setVisibility(View.VISIBLE);
        topBar.animate().alpha(1f).translationY(0f).setDuration(200).setInterpolator(new DecelerateInterpolator()).start();
        urlText.requestFocus();
        if (web != null && web.getUrl() != null) urlText.setText(shortUrl(web.getUrl()));
        modeText.setText(getString(cursorMode ? R.string.link_mode : R.string.cursor_mode));
    }

    private void hideTopBar() {
        if (!topBarShown) return;
        topBarShown = false;
        topBar.animate().alpha(0f).translationY(-ui.dp(20)).setDuration(160).withEndAction(() -> topBar.setVisibility(View.GONE)).start();
        topScrim.animate().alpha(0f).setDuration(160).withEndAction(() -> topScrim.setVisibility(View.GONE)).start();
        if (web != null && screen == Screen.PAGE) web.requestFocus();
    }

    private void toggleCursor() {
        cursorMode = !cursorMode;
        if (cursorMode) {
            cursorX = root.getWidth() / 2f; cursorY = root.getHeight() / 2f; placeCursor();
            cursor.setVisibility(View.VISIBLE); js("window.tvnav && tvnav.clear()");
        } else { cursor.setVisibility(View.GONE); js("window.tvnav && tvnav.move('down')"); }
        showToast(getString(cursorMode ? R.string.cursor_mode : R.string.link_mode));
    }

    private void placeCursor() { cursor.setX(cursorX - ui.dp(6)); cursor.setY(cursorY - ui.dp(4)); }

    private void moveCursor(int dx, int dy, int repeat) {
        cursorSpeed = Math.min(ui.dp(34), ui.dp(9) + repeat * ui.dp(2));
        cursorX = Math.max(0, Math.min(root.getWidth(), cursorX + dx * cursorSpeed));
        cursorY = Math.max(0, Math.min(root.getHeight(), cursorY + dy * cursorSpeed));
        int edge = ui.dp(40);
        if (web != null) { if (dy > 0 && cursorY > root.getHeight() - edge) web.scrollBy(0, (int) cursorSpeed); if (dy < 0 && cursorY < edge) web.scrollBy(0, -(int) cursorSpeed); }
        placeCursor();
    }

    private void cursorClick() {
        if (web == null) return;
        long t = SystemClock.uptimeMillis();
        float x = cursorX, y = cursorY;
        web.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0));
        web.dispatchTouchEvent(MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0));
        cursor.animate().scaleX(0.8f).scaleY(0.8f).setDuration(70).withEndAction(() -> cursor.animate().scaleX(1f).scaleY(1f).setDuration(120).start()).start();
    }

    private void showToast(String text) {
        toast.setText(text); toast.setAlpha(0f); toast.setVisibility(View.VISIBLE);
        toast.animate().alpha(1f).setDuration(150).start();
        handler.postDelayed(() -> toast.animate().alpha(0f).setDuration(300).withEndAction(() -> toast.setVisibility(View.GONE)).start(), 2200);
    }

    private PopupWindow menu(View anchor, String[] labels, Runnable[] actions, boolean above) {
        LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); list.setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8));
        list.setBackground(ui.rounded(0xF2151B2E, 16));
        PopupWindow[] pw = new PopupWindow[1];
        for (int i = 0; i < labels.length; i++) {
            final Runnable a = actions[i];
            LinearLayout p = ui.pill(0, labels[i], true, false);
            p.setOnClickListener(v -> { pw[0].dismiss(); if (a != null) a.run(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(38)); lp.setMargins(0, ui.dp(3), 0, ui.dp(3)); list.addView(p, lp);
        }
        pw[0] = new PopupWindow(list, ui.dp(260), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pw[0].setElevation(ui.dp(16)); pw[0].setAnimationStyle(android.R.style.Animation_Dialog);
        pw[0].setOnDismissListener(() -> { if (screen == Screen.PAGE && web != null && !topBarShown) web.requestFocus(); });
        if (above) { list.measure(View.MeasureSpec.makeMeasureSpec(ui.dp(260), View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED); pw[0].showAsDropDown(anchor, 0, -anchor.getHeight() - list.getMeasuredHeight() - ui.dp(20)); }
        else pw[0].showAsDropDown(anchor, 0, ui.dp(8));
        list.getChildAt(0).requestFocus();
        return pw[0];
    }

    private void showBookmarkMenu(View anchor, Store.Entry e) {
        int idx = store.bookmarks.indexOf(e);
        menu(anchor, new String[]{getString(R.string.move_left), getString(R.string.move_right), getString(R.string.remove_bookmark)}, new Runnable[]{
                () -> { if (idx > 0) { store.bookmarks.remove(idx); store.bookmarks.add(idx - 1, e); store.saveBookmarks(); fillStart(); bookmarksRow.getChildAt(idx - 1).requestFocus(); } },
                () -> { if (idx < store.bookmarks.size() - 1) { store.bookmarks.remove(idx); store.bookmarks.add(idx + 1, e); store.saveBookmarks(); fillStart(); bookmarksRow.getChildAt(idx + 1).requestFocus(); } },
                () -> { store.bookmarks.remove(e); store.saveBookmarks(); fillStart(); focusStart(); }}, false);
    }

    private void toggleBookmark() {
        if (web == null || web.getUrl() == null) return;
        Store.Entry b = store.bookmarkFor(web.getUrl());
        if (b != null) { store.bookmarks.remove(b); showToast(getString(R.string.remove_bookmark)); }
        else { store.bookmarks.add(new Store.Entry(web.getTitle() == null || web.getTitle().isEmpty() ? host(web.getUrl()) : web.getTitle(), web.getUrl())); showToast(getString(R.string.add_bookmark)); }
        store.saveBookmarks();
    }

    private void showTabsMenu(View anchor) {
        List<String> labels = new ArrayList<>(); List<Runnable> actions = new ArrayList<>();
        for (int i = 0; i < store.tabs.size(); i++) {
            final int idx = i; Store.Entry t = store.tabs.get(i);
            labels.add((i == store.activeTab ? "• " : "") + (t.title.isEmpty() ? shortUrl(t.url) : t.title));
            actions.add(() -> { saveActiveTab(); loadTab(idx); });
        }
        if (store.tabs.size() < getResources().getInteger(R.integer.max_tabs)) { labels.add(getString(R.string.new_tab)); actions.add(() -> { saveActiveTab(); store.tabs.add(new Store.Entry("", "about:blank")); store.activeTab = store.tabs.size() - 1; store.saveTabs(); destroyWeb(); showStart(); }); }
        if (!store.tabs.isEmpty()) { labels.add(getString(R.string.close_tab)); actions.add(() -> { store.tabs.remove(store.activeTab); store.activeTab = Math.max(0, store.activeTab - 1); store.saveTabs(); destroyWeb(); if (store.tabs.isEmpty() || screen == Screen.START) showStart(); else loadTab(store.activeTab); }); }
        if (store.tabs.size() > 1) { labels.add(getString(R.string.close_all_tabs)); actions.add(this::closeAllTabs); }
        menu(anchor, labels.toArray(new String[0]), actions.toArray(new Runnable[0]), false);
    }

    private void showPageMenu(View anchor) {
        String h = web == null || web.getUrl() == null ? "" : host(web.getUrl());
        boolean mobile = store.mobileSite(h);
        menu(anchor, new String[]{getString(mobile ? R.string.desktop_site : R.string.mobile_site), getString(R.string.add_bookmark), getString(R.string.clear_history), getString(R.string.clear_cookies)}, new Runnable[]{
                () -> { store.setMobileSite(h, !mobile); if (web != null) web.reload(); },
                this::toggleBookmark,
                () -> { store.clearHistory(); showToast(getString(R.string.done)); },
                () -> { CookieManager.getInstance().removeAllCookies(null); WebStorage.getInstance().deleteAllData(); if (web != null) web.clearCache(true); showToast(getString(R.string.done)); }}, false);
    }

    private void showSettingsMenu(View anchor) {
        menu(anchor, new String[]{getString(R.string.clear_history), getString(R.string.clear_cookies)}, new Runnable[]{
                () -> { store.clearHistory(); fillStart(); showToast(getString(R.string.done)); },
                () -> { CookieManager.getInstance().removeAllCookies(null); WebStorage.getInstance().deleteAllData(); showToast(getString(R.string.done)); }}, false);
    }

    // ---------- voice ----------

    private void startVoice() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH);
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.search_hint));
        try { startActivityForResult(i, VOICE_REQUEST); } catch (ActivityNotFoundException e) { showToast(getString(R.string.no_voice)); }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == VOICE_REQUEST && res == RESULT_OK && data != null) {
            List<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (r != null && !r.isEmpty()) {
                if (screen == Screen.KEYBOARD && keyboardForField) { typed.setLength(0); typed.append(r.get(0)); typedChanged(); }
                else openUrl(toUrl(r.get(0)), true);
            }
        }
    }

    // ---------- keys ----------

    private boolean okHeld, okLongFired;
    private final Runnable okLong = () -> { okLongFired = true; if (screen == Screen.PAGE && !videoActive && !topBarShown) toggleCursor(); };

    /** The WebView consumes D-pad keys itself, so page keys are intercepted here, before view dispatch. */
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (overlayShowing()) {
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) { if (e.getAction() == KeyEvent.ACTION_UP) hideOverlay(); return true; }
            return super.dispatchKeyEvent(e);
        }
        if (screen == Screen.PAGE && !topBarShown && !keyboardShowing()) {
            int code = e.getKeyCode();
            boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
            if (ok && !videoActive) {
                if (e.getAction() == KeyEvent.ACTION_DOWN) {
                    if (e.getRepeatCount() == 0) { okHeld = true; okLongFired = false; handler.postDelayed(okLong, 550); }
                } else if (e.getAction() == KeyEvent.ACTION_UP && okHeld) {
                    okHeld = false; handler.removeCallbacks(okLong);
                    if (!okLongFired) { if (cursorMode) cursorClick(); else js("window.tvnav && tvnav.click()"); }
                }
                return true;
            }
            if (e.getAction() == KeyEvent.ACTION_DOWN && isPageKey(code)) return onKeyDown(code, e);
            if (e.getAction() == KeyEvent.ACTION_UP && isPageKey(code)) return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private boolean keyboardShowing() { return keyboardPanel.getVisibility() == View.VISIBLE; }

    private static boolean isPageKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_CHANNEL_UP: case KeyEvent.KEYCODE_CHANNEL_DOWN: case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY: case KeyEvent.KEYCODE_MEDIA_PAUSE: case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: case KeyEvent.KEYCODE_MEDIA_REWIND:
                return true;
        }
        return false;
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_SEARCH: case KeyEvent.KEYCODE_VOICE_ASSIST: startVoice(); return true;
        }
        if (screen == Screen.KEYBOARD) {
            if (code == KeyEvent.KEYCODE_BACK) { hideKeyboard(); return true; }
            if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_SPACE) { type(" "); return true; }
            return super.onKeyDown(code, e);
        }
        if (screen == Screen.START) {
            if (code == KeyEvent.KEYCODE_MENU) { showKeyboard(false, null); return true; }
            if (code == KeyEvent.KEYCODE_BACK && overlayShowing()) { hideOverlay(); return true; }
            return super.onKeyDown(code, e); // Back leaves the app, as on any TV app
        }
        // ---- PAGE ----
        if (videoActive) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_MEDIA_REWIND: js("tvnav.video('seek',-10)"); showHud(); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: js("tvnav.video('seek',10)"); showHud(); return true;
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: case KeyEvent.KEYCODE_MEDIA_PLAY: case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    js("tvnav.video('toggle')"); showHud(); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: { float r = videoRate >= 2f ? 1f : videoRate + 0.25f; videoRate = r; js("tvnav.video('rate'," + r + ")"); showHud(); return true; }
                case KeyEvent.KEYCODE_DPAD_UP: showHud(); return true;
                case KeyEvent.KEYCODE_BACK:
                    if (fullscreenView != null) { js("document.exitFullscreen && document.exitFullscreen()"); return true; }
                    videoActive = false; hudPanel.setVisibility(View.GONE); return true;
            }
        }
        if (topBarShown) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_DOWN) { hideTopBar(); return true; }
            return super.onKeyDown(code, e);
        }
        switch (code) {
            case KeyEvent.KEYCODE_MENU: showTopBar(); return true;
            case KeyEvent.KEYCODE_BACK:
                if (web != null && web.canGoBack()) web.goBack(); else { saveActiveTab(); showStart(); }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN: {
                int dx = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
                int dy = code == KeyEvent.KEYCODE_DPAD_UP ? -1 : code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
                if (cursorMode) { moveCursor(dx, dy, e.getRepeatCount()); return true; }
                if (dy < 0 && web != null && web.getScrollY() == 0 && e.getRepeatCount() == 0) { showTopBar(); return true; }
                js("window.tvnav && tvnav.move('" + (dx < 0 ? "left" : dx > 0 ? "right" : dy < 0 ? "up" : "down") + "')");
                return true;
            }
            case KeyEvent.KEYCODE_CHANNEL_UP: if (web != null) web.pageUp(false); return true;
            case KeyEvent.KEYCODE_CHANNEL_DOWN: if (web != null) web.pageDown(false); return true;
        }
        return super.onKeyDown(code, e);
    }

}
