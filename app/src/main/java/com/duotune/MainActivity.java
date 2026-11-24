package com.duotune;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Main window (on the screen you launch from). A guided flow: Connect → Tune → Save, and a Saved home
 * on later launches. The other screen follows along (ScreenActivity). Root work only runs through
 * Download/duotune.sh, which you start yourself via the Duo's "Run script as root".
 */
public class MainActivity extends Activity {
    private final Handler h = new Handler(Looper.getMainLooper());
    private int ownScreen;
    private String launcher = Scripts.LAUNCHER;
    private static final boolean DEBUG_START = false;  // true: shows why DuoTune picked its first screen (for testing)
    private String pending;            // what the helper is armed for: "live" | "save" | "remove" | null (kept across restarts)
    private int lostTicks;             // seconds without a helper heartbeat while tuning

    // header
    private LinearLayout stepper, savedHeader;
    private final TextView[] stepDots = new TextView[3];
    private final TextView[] stepLabels = new TextView[3];
    private final View[] stepLines = new View[2];
    // steps
    private View connectView, tuneView, saveView, savedView, helpView;
    private TextView connectStatus, saveRunStatus, removeRunStatus;
    private View saveActions, saveRunCard, savedActions, removeRunCard;
    private LinearLayout saveCards, savedCards;
    private ScreenPanel own;
    private View frame;

    private int dp(float v) { return Ui.dp(this, v); }

    private final Runnable poll = new Runnable() { public void run() { tick(); h.postDelayed(this, 1000); } };
    private final Runnable watcher = new Runnable() { public void run() { runOnUiThread(new Runnable() { public void run() { render(); } }); } };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Pcc.state(this);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        ownScreen = Pcc.screenFor(getWindowManager().getDefaultDisplay());
        Pcc.setActive(ownScreen);
        pending = prefs().getString("pending", null);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(dp(18), dp(12), dp(18), 0);
        root.addView(buildHeader());

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(12), 0, dp(20));
        scroll.addView(content);
        content.addView(connectView = buildConnect());
        content.addView(tuneView = buildTune());
        content.addView(saveView = buildSave());
        content.addView(savedView = buildSaved());
        content.addView(helpView = buildHelp());
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        frame = WhiteMode.wrap(this, root);
        setContentView(frame);

        String name = Scripts.ensureLauncher(this);
        if (name != null) launcher = name;
        else toast("Couldn't create Download/" + Scripts.LAUNCHER + ". Create it with this line: " + Scripts.launcherLine(this).trim(), true);

        Flow.buttons = new Flow.Buttons() { public boolean onButton(int k) { return onControllerButton(k); } };
        Pcc.watch(watcher);

        // A save or remove that ran right before a reboot: the app never saw its result, so settle it now.
        String last = Scripts.readResult(this);
        if (last != null && (("save".equals(pending) && last.startsWith("saved")) || ("remove".equals(pending) && last.startsWith("removed"))))
            setPending(null);

        if (DEBUG_START) toast("start: pending=" + pending + " result=" + (last == null ? "-" : last.trim())
                + " saved=" + Scripts.savedCount() + " alive=" + Pcc.helperAlive(this), true);

        // where to start: saved colours → Saved home; connected → Tune; otherwise → Connect
        if (Pcc.helperAlive(this)) Flow.go(Flow.TUNE);
        else if (Scripts.savedCount() > 0 && !"save".equals(pending)) Flow.go(Flow.SAVED);
        else enterConnect();
        render();
    }

    private android.content.SharedPreferences prefs() { return getSharedPreferences("gains", MODE_PRIVATE); }

    // ---------------------------------------------------------------- header
    private View buildHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);

        stepper = new LinearLayout(this);
        stepper.setGravity(Gravity.CENTER_VERTICAL);
        String[] names = {"Connect", "Tune", "Save"};
        for (int i = 0; i < 3; i++) {
            LinearLayout item = new LinearLayout(this);
            item.setGravity(Gravity.CENTER_VERTICAL);
            TextView dot = Ui.text(this, String.valueOf(i + 1), 12, Ui.BG, true);
            dot.setGravity(Gravity.CENTER);
            item.addView(dot, new LinearLayout.LayoutParams(dp(22), dp(22)));
            TextView label = Ui.text(this, names[i], 13, Ui.FG, true);
            label.setPadding(dp(6), 0, 0, 0);
            item.addView(label);
            stepper.addView(item);
            stepDots[i] = dot; stepLabels[i] = label;
            if (i < 2) {
                View line = new View(this);
                LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(0, dp(2), 1);
                ll.leftMargin = dp(10); ll.rightMargin = dp(10);
                stepper.addView(line, ll);
                stepLines[i] = line;
            }
        }
        row.addView(stepper, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        savedHeader = new LinearLayout(this);
        savedHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView check = Ui.text(this, "✓", 12, Ui.BG, true);
        check.setGravity(Gravity.CENTER);
        check.setBackground(circle(Ui.OK, 0));
        savedHeader.addView(check, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView sl = Ui.text(this, "Saved colours are active", 13, Ui.OK_TEXT, true);
        sl.setPadding(dp(8), 0, 0, 0);
        savedHeader.addView(sl);
        row.addView(savedHeader, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView help = Ui.text(this, "?", 14, Ui.FG, true);
        help.setGravity(Gravity.CENTER);
        help.setBackground(circle(Ui.BG, Ui.STEP_OFF));
        help.setClickable(true);
        help.setFocusable(true);
        help.setContentDescription("Controls and help");
        help.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { Flow.setHelp(!Flow.help()); } });
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(dp(32), dp(32));
        hl.leftMargin = dp(12);
        row.addView(help, hl);
        return row;
    }

    private GradientDrawable circle(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(fill);
        if (stroke != 0) g.setStroke(dp(2), stroke);
        return g;
    }

    private void renderStepper(int step) {
        for (int i = 0; i < 3; i++) {
            boolean done = i < step, current = i == step;
            stepDots[i].setText(done ? "✓" : String.valueOf(i + 1));
            stepDots[i].setTextColor(done || current ? Ui.BG : Ui.MUTED);
            stepDots[i].setBackground(done ? circle(Ui.OK, 0) : current ? circle(Ui.FG, 0) : circle(Ui.BG, Ui.STEP_OFF));
            stepLabels[i].setTextColor(done ? Ui.OK_TEXT : current ? Ui.FG : Ui.MUTED);
            if (i < 2) stepLines[i].setBackgroundColor(i < step ? Ui.OK : Ui.LINE_OFF);
        }
    }

    // ---------------------------------------------------------------- steps
    private LinearLayout column() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        return c;
    }

    private LinearLayout.LayoutParams gapTop(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(top);
        return lp;
    }

    private LinearLayout actionRow(View... buttons) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < buttons.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.leftMargin = dp(8);
            row.addView(buttons[i], lp);
        }
        return row;
    }

    private View.OnClickListener openSettings() {
        return new View.OnClickListener() {
            public void onClick(View v) {
                try { startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)); } catch (Exception ignored) {}
            }
        };
    }

    /** Step 1: how to run the script, with an A button that opens Settings. */
    private View buildConnect() {
        LinearLayout c = column();
        c.addView(Ui.title(this, "Connect DuoTune"));
        TextView sub = Ui.text(this, "DuoTune has no root of its own. Run its script once with the Duo's built-in root option, "
                + "and the colour wheels appear.", 13, Ui.MUTED, false);
        c.addView(sub, gapTop(6));
        c.addView(Ui.numbered(this, 1, "Open Settings → Handheld settings → Advanced"), gapTop(14));
        c.addView(Ui.numbered(this, 2, "Tap Run script as root"), gapTop(8));
        c.addView(Ui.numbered(this, 3, "Choose " + launcher + " in Download. DuoTune comes back by itself"), gapTop(8));
        connectStatus = Ui.text(this, "Waiting for connection…", 13, Ui.WAIT, true);
        LinearLayout row = actionRow(Ui.action(this, "A", "Open Settings", true, openSettings()), connectStatus);
        ((LinearLayout.LayoutParams) connectStatus.getLayoutParams()).leftMargin = dp(14);
        c.addView(row, gapTop(16));
        return c;
    }

    /** Step 2: wheel on the left; title, hint, values line and buttons on the right. */
    private View buildTune() {
        boolean landscape = ScreenPanel.isLandscape(this);
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_VERTICAL);
        own = new ScreenPanel(this, ownScreen);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(dp(220), dp(220));
        if (!landscape) wl.gravity = Gravity.CENTER_HORIZONTAL;
        t.addView(own.wheelPart, wl);

        LinearLayout side = column();
        side.setPadding(landscape ? dp(26) : 0, landscape ? 0 : dp(12), 0, 0);
        side.addView(Ui.title(this, ownScreen == Pcc.TOP ? "Tune the top screen" : "Tune the bottom screen"));
        side.addView(Ui.text(this, "Looks pink? Drag toward green. Looks blue? Drag toward yellow.", 13, Ui.MUTED, false), gapTop(6));
        side.addView(own.valuesPart, gapTop(4));
        View next = Ui.action(this, "A", "Next: save", true, new View.OnClickListener() { public void onClick(View v) { Flow.go(Flow.SAVE); } });
        View white = Ui.action(this, "Y", "Toggle white", false, new View.OnClickListener() { public void onClick(View v) { WhiteMode.set(true); } });
        side.addView(actionRow(next, white), gapTop(12));
        t.addView(side, landscape ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    /** Step 3: both screens' values, Save / Back, then the "run the script once more" card. */
    private View buildSave() {
        LinearLayout c = column();
        c.addView(Ui.title(this, "Save your colours"));
        c.addView(Ui.text(this, "They'll load from the first frame after every boot and sleep. Nothing keeps running in the background.",
                13, Ui.MUTED, false), gapTop(6));
        saveCards = new LinearLayout(this);
        c.addView(saveCards, gapTop(12));
        // The display service only reads the saved files at boot, so Save always reboots (tested: without it, colours reset after sleep).
        c.addView(Ui.text(this, "The Duo reboots once to load them.", 13, Ui.MUTED, false), gapTop(8));
        saveActions = actionRow(
                Ui.action(this, "A", "Save permanently", true, new View.OnClickListener() { public void onClick(View v) { startSave(); } }),
                Ui.action(this, "B", "Back to tuning", false, new View.OnClickListener() { public void onClick(View v) { backToTuning(); } }));
        c.addView(saveActions, gapTop(10));
        saveRunStatus = Ui.text(this, "", 13, Ui.WAIT, true);
        saveRunCard = runCard("Now run " + launcher + " once more", saveRunStatus);
        c.addView(saveRunCard, gapTop(10));
        return c;
    }

    /** Home on later launches: saved values, Adjust again, Remove. */
    private View buildSaved() {
        LinearLayout c = column();
        c.addView(Ui.title(this, "Your screens are matched"));
        c.addView(Ui.text(this, "These colours load at every boot and after sleep. Nothing is running in the background.",
                13, Ui.MUTED, false), gapTop(6));
        savedCards = new LinearLayout(this);
        c.addView(savedCards, gapTop(12));
        savedActions = actionRow(
                Ui.action(this, "A", "Adjust again", true, new View.OnClickListener() { public void onClick(View v) { adjustAgain(); } }),
                Ui.action(this, "X", "Remove saved colours", false, new View.OnClickListener() { public void onClick(View v) { startRemove(); } }),
                Ui.action(this, "Y", "Toggle white", false, new View.OnClickListener() { public void onClick(View v) { WhiteMode.set(true); } }));
        c.addView(savedActions, gapTop(14));
        removeRunStatus = Ui.text(this, "", 13, Ui.WAIT, true);
        removeRunCard = runCard("Now run " + launcher + " to remove them", removeRunStatus);
        c.addView(removeRunCard, gapTop(10));
        return c;
    }

    /** The "run the script now" box used by Save and Remove. */
    private View runCard(String heading, TextView status) {
        LinearLayout card = column();
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(Ui.rounded(0xFFEEF4FF, 12, 0xFFC7D7FE, this));
        card.addView(Ui.text(this, heading, 15, Ui.FG, true));
        card.addView(Ui.text(this, Ui.SETTINGS_PATH + " → " + launcher, 13, Ui.FG, false), gapTop(4));
        LinearLayout row = actionRow(Ui.action(this, "A", "Open Settings", true, openSettings()), status);
        ((LinearLayout.LayoutParams) status.getLayoutParams()).leftMargin = dp(14);
        card.addView(row, gapTop(10));
        return card;
    }

    /** The "?" panel: controller map on the left, tips on the right. */
    private View buildHelp() {
        boolean landscape = ScreenPanel.isLandscape(this);
        LinearLayout c = column();
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(Ui.title(this, "Controls and help"), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        top.addView(Ui.action(this, "B", "Close", false, new View.OnClickListener() { public void onClick(View v) { Flow.setHelp(false); } }));
        c.addView(top);

        LinearLayout cols = new LinearLayout(this);
        cols.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        LinearLayout controls = column();
        controls.addView(Ui.text(this, "CONTROLS", 12, Ui.MUTED, true));
        String[][] keys = {{"L1 / R1", "Switch screen"}, {"L-stick", "Move the dot"}, {"D-pad", "Nudge one small step"},
                {"R-stick", "Brightness (or L2 / R2)"}, {"Y", "Toggle white"}, {"X", "Reset this screen"}, {"A / B", "Next / back"}};
        for (String[] k : keys) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView chip = Ui.text(this, k[0], 11, Ui.FG, true);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(6), dp(3), dp(6), dp(3));
            chip.setBackground(Ui.rounded(Ui.BTN, 6, 0, this));
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT);
            cl.rightMargin = dp(10);
            row.addView(chip, cl);
            row.addView(Ui.text(this, k[1], 13, Ui.FG, false));
            controls.addView(row, gapTop(8));
        }
        LinearLayout helpCol = column();
        helpCol.addView(Ui.text(this, "HELP", 12, Ui.MUTED, true));
        helpCol.addView(Ui.text(this, "Tip: drag away from the tint you see. A blue screen goes toward yellow, a pink screen toward green.",
                13, Ui.FG, false), gapTop(8));
        helpCol.addView(Ui.text(this, "Compare whites: on the wheel screen or the Saved screen, press Y (Toggle white) to turn both screens fully white. Tap a screen or press B to return.",
                13, Ui.FG, false), gapTop(8));
        helpCol.addView(Ui.action(this, null, "Show the connect steps again", false, new View.OnClickListener() {
            public void onClick(View v) { Flow.setHelp(false); enterConnect(); }
        }), gapTop(12));
        helpCol.addView(Ui.text(this, "No permissions, no internet, nothing in the background.", 12, Ui.MUTED, false), gapTop(12));
        if (landscape) {
            cols.addView(controls, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            hl.leftMargin = dp(22);
            cols.addView(helpCol, hl);
        } else {
            cols.addView(controls);
            cols.addView(helpCol, gapTop(16));
        }
        c.addView(cols, gapTop(14));
        return c;
    }

    // ---------------------------------------------------------------- behaviour
    private void setPending(String p) {
        pending = p;
        if (p == null) prefs().edit().remove("pending").apply(); else prefs().edit().putString("pending", p).apply();
    }

    /** Always re-arms the helper for "live", so a save or remove that was prepared is never run by accident. */
    private void enterConnect() {
        WhiteMode.set(false);                                   // never leave the white screens up outside Tune
        try { Scripts.armLive(this, Pcc.state(this)); }
        catch (Exception e) { toast("Couldn't prepare the connection: " + e.getMessage(), true); }
        if (Pcc.helperAlive(this)) { setPending(null); Flow.go(Flow.TUNE); render(); return; }
        setPending("live");
        Flow.go(Flow.CONNECT);
        render();
    }

    private void backToTuning() { enterConnect(); }

    private void adjustAgain() { enterConnect(); }

    /** Writes the calibration files in the background, then arms the helper for "save". */
    private void startSave() {
        Pcc.applyStateNow(this);
        saveRunStatus.setText("Preparing…");
        Scripts.armSave(this, Pcc.state(this).clone(), new Scripts.Done() {
            public void done(boolean ok, String msg) {
                if (!ok) { toast(msg, true); return; }
                setPending("save");
                render();
            }
        });
    }

    /** Arms the helper for "remove". */
    private void startRemove() {
        try { Scripts.armRemove(this); setPending("remove"); render(); }
        catch (Exception e) { toast("Couldn't prepare removal: " + e.getMessage(), true); }
    }

    /** A / B / X per step (see the "?" panel). Y, L1/R1, sticks and D-pad are handled in Gamepad. */
    private boolean onControllerButton(int k) {
        int step = Flow.step();
        if (Flow.help()) return false;
        if (k == KeyEvent.KEYCODE_BUTTON_A) {
            if (step == Flow.CONNECT) openSettings().onClick(null);
            else if (step == Flow.TUNE) Flow.go(Flow.SAVE);
            else if (step == Flow.SAVE) { if ("save".equals(pending)) openSettings().onClick(null); else startSave(); }
            else if (step == Flow.SAVED) { if ("remove".equals(pending)) openSettings().onClick(null); else adjustAgain(); }
            return true;
        }
        if (k == KeyEvent.KEYCODE_BUTTON_B) {
            if (step == Flow.SAVE) { backToTuning(); return true; }
            return false;                                  // B = normal back elsewhere
        }
        if (k == KeyEvent.KEYCODE_BUTTON_X) {
            if (step == Flow.TUNE) { Pcc.setScreen(this, Pcc.active(), 1f, 1f, 1f); Pcc.applyStateNow(this); return true; }
            if (step == Flow.SAVED) { startRemove(); return true; }
        }
        return false;
    }

    /** Once a second: connection state and whether a script run finished. */
    private void tick() {
        boolean alive = Pcc.helperAlive(this);
        int step = Flow.step();
        if (step == Flow.CONNECT) {
            if (alive) { Pcc.applyStateNow(this); setPending(null); Flow.go(Flow.TUNE); toast("Connected. The wheels are live.", false); }
            else {
                String r = Scripts.readResult(this);
                connectStatus.setText(r != null && r.startsWith("live") ? "Script ran, starting…" : "Waiting for connection…");
            }
        } else if (step == Flow.TUNE) {
            lostTicks = alive ? 0 : lostTicks + 1;
            if (lostTicks >= 3) { lostTicks = 0; toast("Live tuning stopped. Run " + launcher + " again to continue.", true); enterConnect(); }
        }
        if (pending != null && (pending.equals("save") || pending.equals("remove"))) {
            String r = Scripts.readResult(this);
            if (pending.equals("save") && r != null && r.startsWith("saved")) {
                setPending(null);
                toast("Saved. If the Duo didn't reboot, reboot it to load your colours.", true);
                Flow.go(Flow.SAVED);
            } else if (pending.equals("remove") && r != null && r.startsWith("removed")) {
                setPending(null);
                toast("Removed. If the Duo didn't reboot, reboot it to return to the factory colours.", true);
                enterConnect();
            }
        }
        render();
    }

    /** Shows the right step and header; both windows call their own render on every state change. */
    private void render() {
        if (connectView == null) return;
        int step = Flow.step();
        boolean help = Flow.help();
        connectView.setVisibility(!help && step == Flow.CONNECT ? View.VISIBLE : View.GONE);
        tuneView.setVisibility(!help && step == Flow.TUNE ? View.VISIBLE : View.GONE);
        saveView.setVisibility(!help && step == Flow.SAVE ? View.VISIBLE : View.GONE);
        savedView.setVisibility(!help && step == Flow.SAVED ? View.VISIBLE : View.GONE);
        helpView.setVisibility(help ? View.VISIBLE : View.GONE);

        stepper.setVisibility(step == Flow.SAVED ? View.GONE : View.VISIBLE);
        savedHeader.setVisibility(step == Flow.SAVED ? View.VISIBLE : View.GONE);
        renderStepper(Math.min(step, 2));

        boolean saving = "save".equals(pending), removing = "remove".equals(pending);
        saveActions.setVisibility(saving ? View.GONE : View.VISIBLE);
        saveRunCard.setVisibility(saving ? View.VISIBLE : View.GONE);
        if (saving) saveRunStatus.setText("Waiting for the script…");
        savedActions.setVisibility(removing ? View.GONE : View.VISIBLE);
        removeRunCard.setVisibility(removing ? View.VISIBLE : View.GONE);
        if (removing) removeRunStatus.setText("Waiting for the script…");

        if (step == Flow.SAVE) fillCards(saveCards);
        if (step == Flow.SAVED) fillCards(savedCards);
    }

    /** The two "Top screen / Bottom screen" value cards on Save and Saved. */
    private void fillCards(LinearLayout row) {
        float[] g = Pcc.state(this);
        row.removeAllViews();
        row.setOrientation(ScreenPanel.isLandscape(this) ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        for (int s = 0; s < 2; s++) {
            View card = Ui.valueCard(this, s == Pcc.TOP ? "Top screen" : "Bottom screen", g[s * 3], g[s * 3 + 1], g[s * 3 + 2]);
            LinearLayout.LayoutParams lp = ScreenPanel.isLandscape(this)
                    ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1)
                    : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (s > 0) { if (ScreenPanel.isLandscape(this)) lp.leftMargin = dp(10); else lp.topMargin = dp(8); }
            row.addView(card, lp);
        }
    }

    private void toast(String msg, boolean longer) {
        Toast.makeText(this, msg, longer ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- lifecycle
    @Override protected void onResume() {
        super.onResume();
        openOtherScreen();
        h.removeCallbacks(poll);
        h.post(poll);
    }

    @Override protected void onPause() {
        super.onPause();
        h.removeCallbacks(poll);
    }

    @Override protected void onStop() {
        super.onStop();
        if (!isChangingConfigurations()) ScreenActivity.close();
    }

    /** Closing DuoTune for real also tells the helper to stop, so nothing keeps running. */
    @Override protected void onDestroy() {
        Pcc.unwatch(watcher);
        if (own != null) own.detach();
        WhiteMode.unwrap(frame);
        if (isFinishing()) { WhiteMode.set(false); Flow.setHelp(false); Flow.buttons = null; Pcc.quitHelper(this); }
        ScreenActivity.close();
        super.onDestroy();
    }

    /** Opens the companion window on the other screen. */
    private void openOtherScreen() {
        if (ScreenActivity.isOpen()) return;
        int here = getWindowManager().getDefaultDisplay().getDisplayId();
        android.hardware.display.DisplayManager dm =
                (android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE);
        for (android.view.Display d : dm.getDisplays()) {
            if (d.getDisplayId() == here) continue;
            try {
                android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
                o.setLaunchDisplayId(d.getDisplayId());
                Intent i = new Intent(this, ScreenActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                startActivity(i, o.toBundle());
            } catch (Exception ignored) {}
            return;
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        return Gamepad.onKey(this, e) || super.dispatchKeyEvent(e);
    }

    @Override public boolean dispatchGenericMotionEvent(android.view.MotionEvent e) {
        return Gamepad.onMotion(this, e) || super.dispatchGenericMotionEvent(e);
    }
}
