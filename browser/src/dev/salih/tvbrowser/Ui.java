package dev.salih.tvbrowser;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.FocusFinder;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The launcher's visual language: frosted pills, rounded tiles, spring focus, key caps. */
final class Ui {
    static final OvershootInterpolator SPRING = new OvershootInterpolator(1.4f);
    static final int PILL_BG = 0x8C0A0E1E, PILL_FOCUS = 0xF2FFFFFF, TEXT = 0xFFFFFFFF, TEXT_DARK = 0xFF111111;
    private final Context ctx;

    Ui(Context ctx) { this.ctx = ctx; }

    int dp(float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics()); }

    TextView text(int sp, boolean bold, int color) {
        TextView t = new TextView(ctx);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif" + (bold ? "-medium" : ""), Typeface.NORMAL));
        t.setShadowLayer(8f, 0f, 2f, 0x80000000);
        return t;
    }

    ImageView icon(int res, int size, int color) {
        ImageView i = new ImageView(ctx); i.setImageResource(res); i.setImageTintList(ColorStateList.valueOf(color));
        i.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
        return i;
    }

    /**
     * Pill with an icon and a label. {@code labelAlways} keeps the label visible; otherwise the
     * label slides open on focus (rail style). Unfocused rail pills are bare icons (no background).
     */
    LinearLayout pill(int iconRes, String label, boolean labelAlways, boolean bareWhenIdle) {
        LinearLayout p = new LinearLayout(ctx); p.setOrientation(LinearLayout.HORIZONTAL); p.setGravity(Gravity.CENTER_VERTICAL);
        p.setPadding(dp(11), 0, dp(labelAlways ? 14 : 11), 0);
        ImageView icon = iconRes != 0 ? icon(iconRes, 20, TEXT) : null;
        if (icon != null) p.addView(icon);
        TextView t = text(14, false, labelAlways ? TEXT : TEXT_DARK); t.setText(label); t.setShadowLayer(0, 0, 0, 0);
        t.setSingleLine(true); t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setPadding(iconRes != 0 ? dp(8) : 0, 0, 0, 0); t.setVisibility(labelAlways ? View.VISIBLE : View.GONE);
        p.addView(t);
        p.setFocusable(true); p.setClickable(true);
        GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(dp(19)); bg.setColor(bareWhenIdle ? 0 : PILL_BG);
        p.setBackground(bg);
        p.setOnFocusChangeListener((v, f) -> {
            bg.setColor(f ? PILL_FOCUS : bareWhenIdle ? 0 : PILL_BG);
            if (icon != null) icon.setImageTintList(ColorStateList.valueOf(f ? TEXT_DARK : TEXT));
            t.setTextColor(f ? TEXT_DARK : labelAlways ? TEXT : TEXT_DARK);
            if (!labelAlways) {
                if (f) { t.setAlpha(0f); t.setVisibility(View.VISIBLE); t.animate().alpha(1f).setDuration(180).start(); }
                else t.setVisibility(View.GONE);
            }
            v.animate().scaleX(f ? 1.06f : 1f).scaleY(f ? 1.06f : 1f).setDuration(200).setInterpolator(SPRING).start();
        });
        return p;
    }

    static TextView label(LinearLayout pill) { return (TextView) pill.getChildAt(pill.getChildCount() - 1); }

    /** Rounded card; focus = spring scale + shadow, no ring. */
    FrameLayout card(int w, int h, int bg) {
        FrameLayout card = new FrameLayout(ctx);
        card.setFocusable(true); card.setClickable(true); card.setBackground(rounded(bg, 9)); // a rounded drawable: the outline clip alone is not reliable on this TV
        card.setLayoutParams(new ViewGroup.LayoutParams(w, h));
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(9)); }
        });
        card.setClipToOutline(true);
        card.setOnFocusChangeListener((v, f) -> {
            v.animate().scaleX(f ? 1.12f : 1f).scaleY(f ? 1.12f : 1f).setDuration(f ? 260 : 180)
                    .setInterpolator(f ? SPRING : new DecelerateInterpolator()).start();
            v.setElevation(f ? dp(14) : 0);
        });
        return card;
    }

    /** A small rounded "remote key" glyph for hint rows: an icon or short text like OK. */
    View keyCap(int iconRes, String text) {
        FrameLayout k = new FrameLayout(ctx);
        GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(dp(iconRes == 0 ? 13 : 6)); bg.setColor(0x2EFFFFFF); bg.setStroke(dp(1), 0x59FFFFFF);
        k.setBackground(bg);
        int s = dp(iconRes == 0 ? 26 : 22);
        k.setLayoutParams(new LinearLayout.LayoutParams(iconRes == 0 ? ViewGroup.LayoutParams.WRAP_CONTENT : s, s));
        if (iconRes != 0) { ImageView i = icon(iconRes, 14, TEXT); FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER); k.addView(i, lp); }
        else { TextView t = text(10, true, TEXT); t.setText(text); t.setShadowLayer(0, 0, 0, 0); t.setPadding(dp(7), 0, dp(7), 0); t.setGravity(Gravity.CENTER); k.addView(t, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER)); }
        return k;
    }

    /** "[cap] text" group for a hint row. */
    LinearLayout hint(int[] capIcons, String capText, String label) {
        LinearLayout g = new LinearLayout(ctx); g.setOrientation(LinearLayout.HORIZONTAL); g.setGravity(Gravity.CENTER_VERTICAL);
        for (int ic : capIcons) { View c = keyCap(ic, null); LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) c.getLayoutParams(); lp.setMarginEnd(dp(4)); g.addView(c, lp); }
        if (capText != null) { View c = keyCap(0, capText); LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) c.getLayoutParams(); lp.setMarginEnd(dp(4)); g.addView(c, lp); }
        TextView t = text(12, false, 0xD9FFFFFF); t.setText(label); t.setPadding(dp(4), 0, dp(22), 0); g.addView(t);
        return g;
    }

    /**
     * Horizontal scroller that keeps the focused item inside its padding. The stock
     * HorizontalScrollView reveals a focused child only up to its outer edge, so with a gutter
     * (clipToPadding off) the last card would stop flush against the screen edge.
     */
    static final class CardScroller extends HorizontalScrollView {
        CardScroller(Context ctx) { super(ctx); }
        @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) {
            if (getChildCount() == 0) return 0;
            int sx = getScrollX(), w = getWidth();
            int left = sx + getPaddingLeft(), right = sx + w - getPaddingRight();
            int delta = r.right > right ? r.right - right : r.left < left ? r.left - left : 0;
            int maxScroll = Math.max(0, getChildAt(0).getWidth() - (w - getPaddingLeft() - getPaddingRight()));
            return Math.max(-sx, Math.min(delta, maxScroll - sx));
        }
        /** At either end of the row there is nothing to reveal; the stock "page scroll" would drift by the padding. */
        @Override public boolean arrowScroll(int direction) {
            View cur = findFocus();
            if (cur != null && FocusFinder.getInstance().findNextFocus(this, cur, direction) == null) return false;
            return super.arrowScroll(direction);
        }
    }

    GradientDrawable rounded(int color, int radiusDp) { GradientDrawable d = new GradientDrawable(); d.setCornerRadius(dp(radiusDp)); d.setColor(color); return d; }
}
