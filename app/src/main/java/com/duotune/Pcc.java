package com.duotune;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;

/**
 * Shared colour state for both screens. No root here: live values go into a private file
 * that the root helper (started via Retroid's "Run script as root") watches and applies.
 */
public final class Pcc {
    public static final int TOP = 0, BOTTOM = 1;
    private static final String PREFS = "gains";
    private static final String[] KEYS = {"t_r", "t_g", "t_b", "b_r", "b_g", "b_b"};

    private static float[] gains;             // {top R, G, B, bottom R, G, B}, each 0.5 .. 1.0
    private static float[][] wheels;          // per screen {hue, radius, brightness}
    private static int active = TOP;          // screen the controller adjusts
    private static boolean applyPending;
    private static final java.util.ArrayList<Runnable> watchers = new java.util.ArrayList<Runnable>();
    private static final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private static Context appCtx;
    // Saves the gains and hands them to the root helper (at most every 50 ms while you drag).
    private static final Runnable applyNow = new Runnable() {
        public void run() { applyPending = false; if (appCtx != null) { save(appCtx, gains); writeLive(appCtx, gains); } }
    };

    private Pcc() {}

    /** The six gains, loaded on first use. */
    public static synchronized float[] state(Context c) {
        if (gains == null) {
            appCtx = c.getApplicationContext();
            gains = load(c);
            wheels = new float[2][];
            for (int s = 0; s < 2; s++) wheels[s] = WheelMath.fromGains(gains[s * 3], gains[s * 3 + 1], gains[s * 3 + 2]);
        }
        return gains;
    }

    public static float[] wheel(Context c, int screen) { state(c); return wheels[screen]; }
    public static int active() { return active; }
    public static void setActive(int screen) { active = screen; notifyWatchers(); }

    /** Set a screen from the wheel (hue, strength) and brightness. */
    public static void setWheel(Context c, int screen, float hue, float radius, float bright) {
        state(c);
        wheels[screen] = new float[]{hue, Math.max(0f, Math.min(1f, radius)), Math.max(0.5f, Math.min(1f, bright))};
        float[] g = WheelMath.toGains(wheels[screen][0], wheels[screen][1], wheels[screen][2]);
        store(screen, g[0], g[1], g[2]);
    }

    /** Set a screen from exact R/G/B gains (typed values, Reset). */
    public static void setScreen(Context c, int screen, float r, float g, float b) {
        state(c);
        wheels[screen] = WheelMath.fromGains(clamp(r), clamp(g), clamp(b));
        store(screen, r, g, b);
    }

    private static void store(int screen, float r, float g, float b) {
        gains[screen * 3] = clamp(r); gains[screen * 3 + 1] = clamp(g); gains[screen * 3 + 2] = clamp(b);
        notifyWatchers();
        if (!applyPending) { applyPending = true; main.postDelayed(applyNow, 50); } // throttle: smooth while moving
    }

    /** Skip the 50 ms throttle (used before Save and on Reset). */
    public static void applyStateNow(Context c) {
        state(c);
        main.removeCallbacks(applyNow);
        applyNow.run();
    }

    private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }

    // Watchers: every view that shows state registers here and redraws when anything changes.
    public static void watch(Runnable r) { if (!watchers.contains(r)) watchers.add(r); }
    public static void unwatch(Runnable r) { watchers.remove(r); }
    static void notifyAllWatchers() { notifyWatchers(); }
    private static void notifyWatchers() { for (Runnable r : new java.util.ArrayList<Runnable>(watchers)) r.run(); }

    // ---- storage ----
    public static float[] load(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        float[] g = new float[6];
        for (int i = 0; i < 6; i++) g[i] = p.getFloat(KEYS[i], 1.0f);
        return g;
    }

    public static void save(Context c, float[] g) {
        SharedPreferences.Editor e = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        for (int i = 0; i < 6; i++) e.putFloat(KEYS[i], g[i]);
        e.apply();
    }

    /** Android's built-in display is the top panel (PCC display 0); the other built-in display is the bottom one (PCC display 3). */
    public static int screenFor(android.view.Display d) {
        return d.getDisplayId() == android.view.Display.DEFAULT_DISPLAY ? TOP : BOTTOM;
    }

    // ---- live helper link ----
    static File liveFile(Context c) { return new File(c.getFilesDir(), "live.txt"); }
    static File heartbeat(Context c) { return new File(c.getFilesDir(), "alive"); }

    /** Writes the six gains atomically; the helper picks them up within ~50 ms. */
    static void writeLive(Context c, float[] g) {
        writeAtomic(c, String.format(Locale.US, "%.3f %.3f %.3f %.3f %.3f %.3f\n", g[0], g[1], g[2], g[3], g[4], g[5]));
    }

    /** Asks the helper to stop. */
    static void quitHelper(Context c) { writeAtomic(c, "q\n"); }

    private static void writeAtomic(Context c, String text) {
        try {
            File tmp = new File(c.getFilesDir(), "live.tmp");
            FileOutputStream o = new FileOutputStream(tmp);
            o.write(text.getBytes("UTF-8"));
            o.close();
            tmp.renameTo(liveFile(c));
        } catch (Exception ignored) {}
    }

    /** True while the helper is running (it refreshes the heartbeat about once a second). */
    static boolean helperAlive(Context c) {
        long t = heartbeat(c).lastModified();
        long now = System.currentTimeMillis();
        long boot = now - android.os.SystemClock.elapsedRealtime();   // when the Duo started
        // A heartbeat from before this boot is stale (the clock can be off right after a reboot, so no Math.abs).
        return t > boot && now - t >= -1000 && now - t < 3000;
    }
}
