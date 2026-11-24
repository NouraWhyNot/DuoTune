package com.duotune;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.Gravity;
import android.view.View;
import java.util.Locale;

/** Shared look: white ground (it is the white reference), quiet controls. */
final class Ui {
    static final int BG = 0xFFFFFFFF, FG = 0xFF1B1E24, MUTED = 0xFF5B616B, BTN = 0xFFEEF0F3, LINE = 0xFFE3E6EB, ACCENT = 0xFF3B6FD9,
            OK = 0xFF1F8A3B, OK_TEXT = 0xFF1F7A38, WAIT = 0xFF9A6700, CHIP_DARK = 0xFF3A3E47, CHIP_LIGHT = 0xFFD8DBE0, NOTE_BG = 0xFFF4F5F7,
            STEP_OFF = 0xFFC5C9D0, LINE_OFF = 0xFFDADDE2;
    private Ui() {}

    /** Density-independent pixels to real pixels. */
    static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    /** A plain label. */
    static TextView text(Context c, String s, int sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** Rounded background with optional 1 dp outline (strokeColor 0 = none). */
    static GradientDrawable rounded(int fill, float radiusDp, int strokeColor, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        if (strokeColor != 0) g.setStroke(dp(c, 1), strokeColor);
        return g;
    }

    /** A big button showing the controller key that does the same thing, e.g. [A] Next: save. */
    static LinearLayout action(Context c, String key, String label, boolean primary, View.OnClickListener click) {
        LinearLayout b = new LinearLayout(c);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(dp(c, 14), 0, dp(c, 16), 0);
        b.setMinimumHeight(dp(c, 46));
        b.setBackground(rounded(primary ? FG : BTN, 12, 0, c));
        b.setClickable(true);
        b.setFocusable(true);
        b.setContentDescription(label);
        b.setOnClickListener(click);
        if (key != null) {
            TextView k = text(c, key, 11, primary ? BG : FG, true);
            k.setPadding(dp(c, 6), dp(c, 2), dp(c, 6), dp(c, 2));
            k.setBackground(rounded(primary ? CHIP_DARK : CHIP_LIGHT, 6, 0, c));
            LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            kl.rightMargin = dp(c, 8);
            b.addView(k, kl);
        }
        b.addView(text(c, label, 14, primary ? BG : FG, true));
        return b;
    }

    /** Big step title. */
    static TextView title(Context c, String s) {
        TextView t = text(c, s, 22, FG, true);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        return t;
    }

    /** One screen's saved values as a small card: colour chip + name + R G B. */
    static LinearLayout valueCard(Context c, String name, float r, float g, float b) {
        LinearLayout card = new LinearLayout(c);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
        card.setBackground(rounded(BG, 12, LINE, c));
        View chip = new View(c);
        int tint = 0xFF000000 | (Math.round(255 * r) << 16) | (Math.round(255 * g) << 8) | Math.round(255 * b);
        chip.setBackground(rounded(tint, 8, LINE_OFF, c));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(dp(c, 34), dp(c, 34));
        cl.rightMargin = dp(c, 12);
        card.addView(chip, cl);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text(c, name, 12, FG, true));
        TextView v = text(c, String.format(Locale.US, "R %.3f  G %.3f  B %.3f", r, g, b), 12, MUTED, false);
        v.setTypeface(Typeface.MONOSPACE);
        col.addView(v);
        card.addView(col);
        return card;
    }

    /** A numbered instruction row: [1] text */
    static LinearLayout numbered(Context c, int n, String s) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView num = text(c, String.valueOf(n), 12, FG, true);
        num.setGravity(Gravity.CENTER);
        num.setBackground(rounded(BTN, 8, 0, c));
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(dp(c, 24), dp(c, 24));
        nl.rightMargin = dp(c, 10);
        row.addView(num, nl);
        TextView t = text(c, s, 14, FG, false);
        row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    static final String SETTINGS_PATH = "Settings → Handheld settings → Advanced → Run script as root";
}
