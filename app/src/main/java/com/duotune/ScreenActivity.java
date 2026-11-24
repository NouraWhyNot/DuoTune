package com.duotune;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.lang.ref.WeakReference;

/**
 * The other screen. Follows the guided flow: where to find the root option (Connect), its own wheel (Tune),
 * what happens next (Save), and plain white (Saved). Closes when the main window goes away.
 */
public class ScreenActivity extends Activity {
    private static WeakReference<ScreenActivity> current = new WeakReference<ScreenActivity>(null);
    private ScreenPanel panel;
    private View frame, connectView, tuneView, saveView, savedView;
    private final Runnable watcher = new Runnable() { public void run() { runOnUiThread(new Runnable() { public void run() { render(); } }); } };

    private int dp(float v) { return Ui.dp(this, v); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        current = new WeakReference<ScreenActivity>(this);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);

        int screen = Pcc.screenFor(getWindowManager().getDefaultDisplay());
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, pad);

        root.addView(connectView = buildWhere());
        panel = new ScreenPanel(this, screen);
        root.addView(tuneView = buildTune(screen));
        root.addView(saveView = buildAfterSave());
        root.addView(savedView = buildSavedNote(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scroll.addView(root);
        frame = WhiteMode.wrap(this, scroll);
        setContentView(frame);
        Pcc.watch(watcher);
        render();
    }

    private LinearLayout.LayoutParams gapTop(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(top);
        return lp;
    }

    /** Tune: name + L1/R1 hint, the wheel centred, the values line under it. */
    private View buildTune(int screen) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.text(this, screen == Pcc.TOP ? "Top screen" : "Bottom screen", 14, Ui.FG, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView pill = Ui.text(this, "L1 / R1 to switch", 11, Ui.MUTED, true);
        pill.setPadding(dp(8), dp(4), dp(8), dp(4));
        pill.setBackground(Ui.rounded(Ui.BTN, 999, 0, this));
        head.addView(pill);
        c.addView(head);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(dp(210), dp(210));
        wl.gravity = Gravity.CENTER_HORIZONTAL;
        wl.topMargin = dp(8);
        c.addView(panel.wheelPart, wl);
        LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vl.gravity = Gravity.CENTER_HORIZONTAL;
        c.addView(panel.valuesPart, vl);
        return c;
    }

    /** Connect: the settings path drawn as the rows you will tap. */
    private View buildWhere() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.addView(Ui.text(this, "Where to find it", 14, Ui.FG, true));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackground(Ui.rounded(Ui.BG, 12, Ui.LINE, this));
        String[] rows = {"Settings", "Handheld settings", "Advanced", "Run script as root", "Download / " + Scripts.LAUNCHER};
        for (int i = 0; i < rows.length; i++) {
            boolean target = i >= 3;
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(14), dp(11), dp(14), dp(11));
            if (target) r.setBackgroundColor(0xFFEEF4FF);
            TextView name = Ui.text(this, rows[i], 14, Ui.FG, target);
            if (i == 4) name.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
            r.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            r.addView(Ui.text(this, i == 4 ? "pick this" : "›", i == 4 ? 12 : 16, target ? 0xFF3B5BDB : Ui.MUTED, true));
            list.addView(r);
            if (i < rows.length - 1) {
                View line = new View(this);
                line.setBackgroundColor(Ui.LINE);
                list.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
            }
        }
        c.addView(list, gapTop(10));
        c.addView(Ui.text(this, "The colour wheels appear on both screens as soon as DuoTune is connected.", 12, Ui.MUTED, false), gapTop(10));
        return c;
    }

    /** Save: what happens next, and how to change your mind. */
    private View buildAfterSave() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.addView(Ui.text(this, "After you tap Save", 14, Ui.FG, true));
        c.addView(Ui.numbered(this, 1, "Run " + Scripts.LAUNCHER + " once more: " + Ui.SETTINGS_PATH), gapTop(10));
        c.addView(Ui.numbered(this, 2, "The Duo reboots with your colours"), gapTop(8));
        TextView note = Ui.text(this, "Changed your mind? Next time you open DuoTune, it shows your saved colours with a Remove button. "
                + "Your system files are never changed.", 12, Ui.MUTED, false);
        note.setPadding(dp(14), dp(12), dp(14), dp(12));
        note.setBackground(Ui.rounded(Ui.NOTE_BG, 12, 0, this));
        c.addView(note, gapTop(16));
        return c;
    }

    /** Saved: plain white to look at, one quiet line at the bottom. */
    private View buildSavedNote() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.BOTTOM);
        c.addView(Ui.text(this, "Remove brings back the factory colours after one more run of " + Scripts.LAUNCHER + " and a reboot.",
                12, Ui.MUTED, false));
        return c;
    }

    /** Shows the part that belongs to the current step. */
    private void render() {
        if (connectView == null) return;
        int step = Flow.step();
        connectView.setVisibility(step == Flow.CONNECT ? View.VISIBLE : View.GONE);
        tuneView.setVisibility(step == Flow.TUNE ? View.VISIBLE : View.GONE);
        tuneView.setAlpha(Flow.help() ? 0.35f : 1f);
        saveView.setVisibility(step == Flow.SAVE ? View.VISIBLE : View.GONE);
        savedView.setVisibility(step == Flow.SAVED ? View.VISIBLE : View.GONE);
    }

    @Override protected void onDestroy() {
        Pcc.unwatch(watcher);
        if (panel != null) panel.detach();
        WhiteMode.unwrap(frame);
        if (current.get() == this) current.clear();
        super.onDestroy();
    }

    public static void close() {
        ScreenActivity a = current.get();
        if (a != null && !a.isFinishing()) a.finish();
    }

    public static boolean isOpen() {
        ScreenActivity a = current.get();
        return a != null && !a.isFinishing();
    }

    @Override public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        return Gamepad.onKey(this, e) || super.dispatchKeyEvent(e);
    }

    @Override public boolean dispatchGenericMotionEvent(android.view.MotionEvent e) {
        return Gamepad.onMotion(this, e) || super.dispatchGenericMotionEvent(e);
    }
}
