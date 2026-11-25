package com.arilson.tvremote;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Peças visuais simples e grandes, pensadas para o controle remoto da TV. */
final class Ui {
    static final int NAVY = Color.rgb(10, 27, 51);
    static final int CARD = Color.rgb(20, 40, 70);
    static final int CARD2 = Color.rgb(28, 52, 88);
    static final int ORANGE = Color.rgb(244, 88, 60);
    static final int GREEN = Color.rgb(34, 197, 94);
    static final int BLUE = Color.rgb(37, 99, 235);
    static final int GOLD = Color.rgb(255, 205, 60);
    static final int MUTED = Color.rgb(160, 175, 200);

    static int dp(Context c, float v) { return Math.round(c.getResources().getDisplayMetrics().density * v); }

    static GradientDrawable round(Context c, int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color); g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    static TextView text(Context c, String t, float sp, int color, boolean bold) {
        TextView v = new TextView(c);
        v.setText(t); v.setTextSize(sp); v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    static TextView brand(Context c, float sp) {
        TextView v = new TextView(c);
        SpannableString ss = new SpannableString("TECHSOLUTIONS");
        ss.setSpan(new ForegroundColorSpan(ORANGE), 4, 13, 0);
        v.setText(ss); v.setTextColor(Color.WHITE); v.setTextSize(sp);
        v.setTypeface(Typeface.DEFAULT_BOLD); v.setLetterSpacing(0.08f);
        return v;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(round(c, CARD, 18));
        l.setPadding(dp(c, 26), dp(c, 22), dp(c, 26), dp(c, 22));
        return l;
    }

    /** Botão grande: quando o controle está nele fica com borda dourada grossa e um pouco maior. */
    static Button button(final Context c, String label, final int bg) {
        final Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(19);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setPadding(dp(c, 26), dp(c, 16), dp(c, 26), dp(c, 16));
        b.setStateListAnimator(null);
        b.setFocusable(true);
        b.setTag(bg);
        paint(c, b, false);
        b.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean f) {
                paint(c, (Button) v, f);
                v.animate().scaleX(f ? 1.06f : 1f).scaleY(f ? 1.06f : 1f).setDuration(120).start();
            }
        });
        return b;
    }
    static void setColor(Context c, Button b, int bg) { b.setTag(bg); paint(c, b, b.isFocused()); }
    private static void paint(Context c, Button b, boolean focused) {
        int bg = b.getTag() instanceof Integer ? (Integer) b.getTag() : CARD2;
        GradientDrawable g = round(c, bg, 14);
        if (focused) g.setStroke(dp(c, 5), GOLD);
        else g.setStroke(dp(c, 2), Color.argb(50, 255, 255, 255));
        b.setBackground(g);
    }

    static LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }
    static LinearLayout.LayoutParams wrap() { return lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); }
    static LinearLayout.LayoutParams match() { return lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); }
    static LinearLayout.LayoutParams weight(float w) { return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w); }
    static LinearLayout.LayoutParams margins(LinearLayout.LayoutParams p, Context c, int l, int t, int r, int b) {
        p.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b)); return p;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }
}
