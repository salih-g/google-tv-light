package dev.salih.tvhome;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Screensaver: the Home wallpapers full screen with a large clock, the date and the weather. Built to stay idle on
 * this TV's GPU: a scene crossfade every few minutes and a once-a-minute text update are the only frames drawn
 * (Google's ambient screen animated all the time and stuttered). The text block moves between corners every minute
 * so nothing stays burnt in.
 */
public final class Dream extends DreamService {
    private static final int SCENE_MINUTES = 5;
    private static final int[] GRAVITIES = {Gravity.BOTTOM | Gravity.START, Gravity.TOP | Gravity.END, Gravity.BOTTOM | Gravity.END, Gravity.TOP | Gravity.START};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.US);
    private SimpleDateFormat dateFmt;
    private Wallpapers wallpapers;
    private LinearLayout block;
    private TextView clock, date, weather;
    private ImageView weatherIcon;
    private int corner;

    private final Runnable minute = new Runnable() {
        @Override public void run() {
            update(true);
            Sky.refresh(Dream.this, false, () -> { update(false); wallpapers.weatherChanged(); });
            handler.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000);
        }
    };

    private int dp(float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false); setFullscreen(true); setScreenBright(true);
        dateFmt = new SimpleDateFormat("EEEE, d MMMM", getResources().getConfiguration().getLocales().get(0));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        ImageView back = new ImageView(this), front = new ImageView(this);
        for (ImageView v : new ImageView[]{back, front}) {
            v.setScaleType(ImageView.ScaleType.CENTER_CROP); v.setVisibility(View.INVISIBLE);
            root.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x33000000, 0x00000000, 0x66000000}));
        root.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        block = new LinearLayout(this); block.setOrientation(LinearLayout.VERTICAL);
        // a frosted panel like Home's pills: white text stays readable over bright skies in every corner
        GradientDrawable panel = new GradientDrawable(); panel.setCornerRadius(dp(28)); panel.setColor(0x8C0A0E1E);
        block.setBackground(panel); block.setPadding(dp(36), dp(20), dp(40), dp(28));
        clock = text(96, false); clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        date = text(22, false);
        LinearLayout wx = new LinearLayout(this); wx.setOrientation(LinearLayout.HORIZONTAL); wx.setGravity(Gravity.CENTER_VERTICAL);
        weatherIcon = new ImageView(this); weatherIcon.setImageTintList(ColorStateList.valueOf(0xF2FFFFFF));
        wx.addView(weatherIcon, new LinearLayout.LayoutParams(dp(26), dp(26)));
        weather = text(22, false); weather.setPadding(dp(8), 0, 0, 0); wx.addView(weather, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // WRAP_CONTENT, not the vertical default MATCH_PARENT: a match-parent TextView inside a wrap-content panel is
        // measured once while empty and Android 11 doesn't re-measure it on setText (fixed width), leaving it ~0 px wide
        block.addView(clock, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        block.addView(date, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wl.topMargin = dp(6); block.addView(wx, wl);
        root.addView(block, blockParams());
        setContentView(root);

        int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
        Wallpapers.sun = t -> Sky.slot(this, t);
        Live.phase = t -> Sky.phase(this, t);
        wallpapers = new Wallpapers(this, back, front, handler, SCENE_MINUTES, w, h);
        wallpapers.setWeather(() -> Sky.weatherName(this));   // rainy, snowy, foggy or cloudy frames when they ship
    }

    private TextView text(int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); t.setTextColor(0xF2FFFFFF);
        t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        t.setShadowLayer(12f, 0f, 2f, 0x99000000);
        return t;
    }

    private FrameLayout.LayoutParams blockParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, GRAVITIES[corner]);
        lp.setMargins(dp(56), dp(44), dp(56), dp(44));
        return lp;
    }

    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        wallpapers.start();
        Sky.refresh(this, false, () -> { update(false); wallpapers.weatherChanged(); });
        update(false);
        handler.postDelayed(minute, 60_000 - System.currentTimeMillis() % 60_000);
    }

    @Override public void onDreamingStopped() {
        super.onDreamingStopped();
        handler.removeCallbacks(minute);
        wallpapers.stop();
    }

    @Override public void onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }

    /** Text refresh; on the minute tick the block also hops to the next corner with a short fade. */
    private void update(boolean move) {
        Date now = new Date();
        Runnable fill = () -> {
            clock.setText(timeFmt.format(now));
            date.setText(dateFmt.format(now));
            Integer t = Sky.temperature(this);
            if (t == null) { weather.setText(Sky.city(this)); weatherIcon.setVisibility(View.GONE); }
            else {
                int code = Sky.code(this);
                weatherIcon.setImageResource(Sky.icon(code, Sky.isDay(this))); weatherIcon.setVisibility(View.VISIBLE);
                String place = Sky.city(this);
                weather.setText(t + "°  " + getString(Sky.label(code)) + (place.isEmpty() ? "" : "  ·  " + place));
            }
        };
        if (!move) { fill.run(); return; }
        block.animate().alpha(0f).setDuration(400).withEndAction(() -> {
            corner = (corner + 1) % GRAVITIES.length;
            block.setLayoutParams(blockParams());
            fill.run();
            block.animate().alpha(1f).setDuration(400).start();
        }).start();
    }
}
