package com.duotune;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Locale;

/**
 * The tuning controls for ONE screen, in two pieces the window places itself:
 *  - wheelPart:  the colour wheel (drag the dot away from the tint you see)
 *  - valuesPart: one line "R 0.930  G 0.960  B 0.960  ☀ 1.000   Edit  Reset";
 *                Edit opens four small fields to type exact numbers.
 * Everything reads from and writes to the shared state in Pcc, so both windows always show the same values.
 */
final class ScreenPanel {
    private static final int[] CH_COLORS = {0xFFD9443F, 0xFF2E9E46, 0xFF3B6FD9};   // label colours for R, G, B
    private static final String[] CH_NAMES = {"R", "G", "B"};
    private static final float MIN = 0.50f;   // lowest gain allowed (same limit as the wheel and brightness)

    private final Activity a;
    private final int screen;                 // Pcc.TOP or Pcc.BOTTOM
    private ColorWheelView wheelView;
    private TextView chip;                    // the one-line summary
    private TextView brightVal;               // ☀ field
    private final TextView[] vals = new TextView[3];   // R, G, B fields
    final View wheelPart, valuesPart;

    /** Called whenever the shared state changes (wheel, controller, other window): redraw from it. */
    private final Runnable watcher = new Runnable() {
        public void run() {
            a.runOnUiThread(new Runnable() { public void run() { refresh(); } });
        }
    };

    ScreenPanel(Activity activity, int screen) {
        this.a = activity;
        this.screen = screen;
        Pcc.state(a);                         // make sure saved values are loaded
        wheelPart = buildWheel();
        valuesPart = buildValues();
        refresh();
        Pcc.watch(watcher);
    }

    /** Stop listening when the window closes. */
    void detach() { Pcc.unwatch(watcher); }

    private int dp(float v) { return Ui.dp(a, v); }

    static boolean isLandscape(Activity a) {
        return a.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    // ------------------------------------------------------------------ wheel

    private View buildWheel() {
        wheelView = new ColorWheelView(a);
        wheelView.setOnChange(new ColorWheelView.OnChange() {
            public void onWheel(float hue, float radius) {
                Pcc.setActive(screen);        // touching a wheel also points the controller at this screen
                Pcc.setWheel(a, screen, hue, radius, Pcc.wheel(a, screen)[2]);   // keep the current brightness
            }
        });
        return wheelView;
    }

    // ------------------------------------------------------------------ values line + editor

    private View buildValues() {
        // Collapsed: the numbers on one line, with Edit and Reset links.
        chip = Ui.text(a, "", 13, Ui.FG, false);
        chip.setTypeface(Typeface.MONOSPACE);
        chip.setSingleLine(true);
        final LinearLayout chipRow = new LinearLayout(a);
        chipRow.setGravity(Gravity.CENTER_VERTICAL);
        chipRow.setPadding(dp(12), dp(8), dp(12), dp(8));
        chipRow.setBackground(Ui.rounded(0xFFF7F8FA, 10, 0xFFE3E6EB, a));
        chipRow.addView(chip, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        chipRow.addView(Ui.text(a, "Edit", 13, 0xFF3B5BDB, true));
        TextView resetLink = Ui.text(a, "Reset", 13, Ui.MUTED, true);
        resetLink.setPadding(dp(14), 0, 0, 0);
        resetLink.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Pcc.setScreen(a, screen, 1f, 1f, 1f);   // back to "no correction"
                Pcc.applyStateNow(a);
            }
        });
        chipRow.addView(resetLink);
        chipRow.setClickable(true);
        chipRow.setFocusable(true);

        // Expanded: R G on the first row, B ☀ on the second, Done underneath.
        final LinearLayout editor = new LinearLayout(a);
        editor.setOrientation(LinearLayout.VERTICAL);
        editor.setPadding(dp(12), dp(8), dp(12), dp(8));
        editor.setBackground(Ui.rounded(0xFFF7F8FA, 10, 0xFFE3E6EB, a));
        LinearLayout r1 = new LinearLayout(a), r2 = new LinearLayout(a);
        for (int ch = 0; ch < 3; ch++) {
            final int c = ch;
            vals[ch] = field(CH_NAMES[ch], CH_COLORS[ch], new Change() {
                public void on(float v) {       // change one channel, keep the other two
                    float[] g = Pcc.state(a);
                    float[] rgb = {g[screen * 3], g[screen * 3 + 1], g[screen * 3 + 2]};
                    rgb[c] = v;
                    Pcc.setActive(screen);
                    Pcc.setScreen(a, screen, rgb[0], rgb[1], rgb[2]);
                }
            }, ch < 2 ? r1 : r2);
        }
        brightVal = field("☀", 0xFF8A6D1E, new Change() {
            public void on(float v) {           // brightness scales all three channels via the wheel state
                float[] w = Pcc.wheel(a, screen);
                Pcc.setActive(screen);
                Pcc.setWheel(a, screen, w[0], w[1], v);
            }
        }, r2);
        editor.addView(r1);
        LinearLayout.LayoutParams r2l = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r2l.topMargin = dp(8);
        editor.addView(r2, r2l);
        TextView done = Ui.text(a, "Done", 13, 0xFF3B5BDB, true);
        done.setPadding(0, dp(8), 0, 0);
        editor.addView(done);
        editor.setVisibility(View.GONE);

        // Edit: swap the line for the fields and put the cursor in R.
        chipRow.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) {
                chipRow.setVisibility(View.GONE);
                editor.setVisibility(View.VISIBLE);
                vals[0].requestFocus();
            }
        });
        // Done: commit the field being typed in (via focus loss), hide the keyboard, fold back to one line.
        done.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) {
                View f = a.getCurrentFocus();
                if (f != null) {
                    f.clearFocus();
                    hideKeyboard(f);
                }
                editor.setVisibility(View.GONE);
                chipRow.setVisibility(View.VISIBLE);
                refresh();
            }
        });

        LinearLayout values = new LinearLayout(a);
        values.setOrientation(LinearLayout.VERTICAL);
        values.setPadding(0, dp(8), 0, 0);
        values.addView(chipRow);
        values.addView(editor);
        return values;
    }

    private void hideKeyboard(View v) {
        InputMethodManager im = (InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    // ------------------------------------------------------------------ sync from shared state

    /** Puts the shared values on screen. Also dims the wheel when the controller is on the other screen. */
    private void refresh() {
        float[] g = Pcc.state(a);
        float[] w = Pcc.wheel(a, screen);
        wheelView.setPosition(w[0], w[1]);
        setVal(brightVal, w[2]);
        for (int ch = 0; ch < 3; ch++) setVal(vals[ch], g[screen * 3 + ch]);
        chip.setText(String.format(Locale.US, "R %.3f  G %.3f  B %.3f  ☀ %.3f",
                g[screen * 3], g[screen * 3 + 1], g[screen * 3 + 2], w[2]));
        wheelView.setAlpha(Pcc.active() == screen ? 1f : 0.55f);
    }

    private void setVal(TextView v, float value) {
        if (!v.hasFocus()) v.setText(String.format(Locale.US, "%.3f", value));   // don't overwrite while someone types
    }

    private interface Change { void on(float value); }

    /**
     * One small typeable field with its coloured label, added to row. Accepts 0.500 – 1.000 (a comma works too),
     * rounds to 3 decimals and applies on Next/Done or when the field loses focus. Anything else is refused with a message.
     */
    private TextView field(String label, int color, final Change change, LinearLayout row) {
        TextView l = Ui.text(a, label, 14, color, true);
        l.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(dp(22), ViewGroup.LayoutParams.WRAP_CONTENT);
        if (row.getChildCount() > 0) ll.leftMargin = dp(16);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(l, ll);

        final EditText v = new EditText(a);
        v.setText("1.000");
        v.setTextSize(14);
        v.setTextColor(Ui.FG);
        v.setTypeface(Typeface.MONOSPACE);
        v.setGravity(Gravity.CENTER);
        v.setSingleLine(true);
        v.setSelectAllOnFocus(true);          // tap = select all, so typing replaces the old number
        v.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        v.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        v.setContentDescription(label + " value");
        v.setPadding(dp(4), dp(6), dp(4), dp(6));
        v.setBackground(Ui.rounded(Ui.BG, 8, 0xFFDADDE2, a));
        row.addView(v, new LinearLayout.LayoutParams(dp(78), ViewGroup.LayoutParams.WRAP_CONTENT));

        final boolean[] busy = {false};      // clearFocus() below re-triggers commit; ignore that second call
        final Runnable commit = new Runnable() {
            public void run() {
                if (busy[0]) return;
                String txt = v.getText().toString().trim().replace(',', '.');
                float value;
                try { value = Float.parseFloat(txt); } catch (Exception e) { value = Float.NaN; }
                if (Float.isNaN(value) || value < MIN || value > 1f) {
                    Toast.makeText(a, "Enter a value between 0.500 and 1.000", Toast.LENGTH_SHORT).show();
                    busy[0] = true;
                    v.clearFocus();
                    busy[0] = false;
                    refresh();                // put the current value back
                    return;
                }
                value = Math.round(value * 1000f) / 1000f;
                v.setText(String.format(Locale.US, "%.3f", value));
                change.on(value);
            }
        };
        v.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView t, int action, android.view.KeyEvent e) {
                commit.run();
                return false;                 // let Next move on to the following field
            }
        });
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View x, boolean has) { if (!has) commit.run(); }
        });
        return v;
    }
}
