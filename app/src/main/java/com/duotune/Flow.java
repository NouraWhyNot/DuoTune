package com.duotune;

/** The guided flow, shared by both screens: Connect → Tune → Save, then Saved on later launches. */
final class Flow {
    static final int CONNECT = 0, TUNE = 1, SAVE = 2, SAVED = 3;

    /** The main window handles A / B / X for the step it shows. */
    interface Buttons { boolean onButton(int keyCode); }

    private static int step = CONNECT;   // which step both windows show
    private static boolean help;         // "?" panel open
    static Buttons buttons;              // set by MainActivity; Gamepad forwards A/B/X to it

    private Flow() {}

    static int step() { return step; }
    static boolean help() { return help; }

    /** Moves both windows to step s (every window redraws through Pcc's watchers). */
    static void go(int s) {
        if (step == s) return;
        step = s;
        Pcc.notifyAllWatchers();
    }

    static void setHelp(boolean h) {
        if (help == h) return;
        help = h;
        Pcc.notifyAllWatchers();
    }

    /** Wheels and fine controls are live only while tuning. */
    static boolean tuning() { return step == TUNE && !help; }
    /** Toggle white is allowed on the wheel screen and on the Saved home (to check your colours after the reboot). */
    static boolean canWhite() { return (step == TUNE || step == SAVED) && !help; }
}
