package com.duotune;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * Built-in controller: A/B/X follow the screen, Y white screens, L1/R1 switch screen, left stick / D-pad move the dot, right stick or L2/R2 brightness, X resets.
 * Both windows forward their input here, so it works whichever screen has focus.
 */
final class Gamepad {
    private static final float DEAD = 0.12f;
    private static final float STICK_SPEED = 0.45f;  // wheel radii per second at full tilt
    private static final float HAT_SPEED = 0.10f;    // D-pad held (when reported as a hat axis)
    private static final float NUDGE = 0.01f;        // one D-pad press
    private static final float BRIGHT_SPEED = 0.15f; // brightness per second at full tilt
    private static final float BRIGHT_STEP = 0.005f;

    private static float lx, ly, ry, lt, rt, hx, hy;   // latest stick / trigger / hat positions
    private static long lastTick;
    private static Activity ctx;
    private static final Handler h = new Handler(Looper.getMainLooper());
    // While a stick or trigger is held, move the dot about 60 times a second (speed scales with tilt).
    private static final Runnable tick = new Runnable() {
        public void run() {
            long now = SystemClock.uptimeMillis();
            float dt = Math.min(0.05f, (now - lastTick) / 1000f);
            lastTick = now;
            float mx = curve(lx) * STICK_SPEED + hx * HAT_SPEED;
            float my = curve(ly) * STICK_SPEED + hy * HAT_SPEED;
            float mb = (-curve(ry) + rt - lt) * BRIGHT_SPEED;
            if (mx != 0 || my != 0 || mb != 0) {
                move(mx * dt, my * dt, mb * dt);
                h.postDelayed(this, 16);
            }
        }
    };

    private Gamepad() {}

    /** Dead zone, then a squared curve: small tilts move slowly for precise placement. */
    private static float curve(float v) { return Math.abs(v) < DEAD ? 0f : v * Math.abs(v); }

    /** Moves the active screen's dot by (dx, dy) in wheel units and brightness by db. */
    private static void move(float dx, float dy, float db) {
        if (ctx == null) return;
        int s = Pcc.active();
        float[] w = Pcc.wheel(ctx, s);
        double a = Math.toRadians(w[0]);
        float x = (float) (Math.cos(a) * w[1]) + dx;
        float y = (float) (Math.sin(a) * w[1]) + dy;
        float r = (float) Math.sqrt(x * x + y * y);
        if (r > 1f) { x /= r; y /= r; r = 1f; }
        float hue = r < 1e-4f ? w[0] : (float) Math.toDegrees(Math.atan2(y, x));
        if (hue < 0) hue += 360f;
        Pcc.setWheel(ctx, s, hue, r < 1e-4f ? 0f : r, w[2] + db);
    }

    private static void startTicking() {
        h.removeCallbacks(tick);
        lastTick = SystemClock.uptimeMillis();
        h.postDelayed(tick, 16);
    }

    /** Buttons. Returns true when DuoTune used the key. Order matters: white mode and help catch B first. */
    static boolean onKey(Activity a, KeyEvent e) {
        ctx = a;
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (WhiteMode.isOn() && (k == KeyEvent.KEYCODE_BUTTON_B || k == KeyEvent.KEYCODE_BACK)) {
            if (down) WhiteMode.set(false);
            return true;
        }
        if (k == KeyEvent.KEYCODE_BUTTON_Y) {               // Toggle white: on the wheel screen and the Saved home
            if (down && e.getRepeatCount() == 0 && (Flow.canWhite() || WhiteMode.isOn())) WhiteMode.set(!WhiteMode.isOn());
            return true;
        }
        if (WhiteMode.isOn() && (k == KeyEvent.KEYCODE_BUTTON_A || k == KeyEvent.KEYCODE_BUTTON_X)) return true;   // no Next / Remove while comparing whites
        if (Flow.help() && (k == KeyEvent.KEYCODE_BUTTON_B || k == KeyEvent.KEYCODE_BACK)) {
            if (down) Flow.setHelp(false);
            return true;
        }
        if (k == KeyEvent.KEYCODE_BUTTON_A || k == KeyEvent.KEYCODE_BUTTON_B || k == KeyEvent.KEYCODE_BUTTON_X) {
            Flow.Buttons b = Flow.buttons;
            if (b == null) return false;
            if (!down) return true;
            if (e.getRepeatCount() > 0) return true;
            return b.onButton(k);
        }
        if (!Flow.tuning()) return false;      // sticks, D-pad and shoulders only while tuning
        switch (k) {
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_BUTTON_R1:
                if (down && e.getRepeatCount() == 0) Pcc.setActive(Pcc.active() == Pcc.TOP ? Pcc.BOTTOM : Pcc.TOP);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:    if (down) move(0, -NUDGE, 0); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:  if (down) move(0, NUDGE, 0); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:  if (down) move(-NUDGE, 0, 0); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: if (down) move(NUDGE, 0, 0); return true;
            case KeyEvent.KEYCODE_BUTTON_L2:  if (down) move(0, 0, -BRIGHT_STEP); return true;
            case KeyEvent.KEYCODE_BUTTON_R2:  if (down) move(0, 0, BRIGHT_STEP); return true;
            default:
                return false;
        }
    }

    /** Sticks, analog triggers and the D-pad when it reports as a hat axis. */
    static boolean onMotion(Activity a, MotionEvent e) {
        if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK
                || e.getAction() != MotionEvent.ACTION_MOVE) return false;
        if (!Flow.tuning()) return false;
        ctx = a;
        lx = e.getAxisValue(MotionEvent.AXIS_X);
        ly = e.getAxisValue(MotionEvent.AXIS_Y);
        ry = e.getAxisValue(MotionEvent.AXIS_RZ);
        lt = Math.max(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE));
        rt = Math.max(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS));
        float nhx = Math.round(e.getAxisValue(MotionEvent.AXIS_HAT_X));
        float nhy = Math.round(e.getAxisValue(MotionEvent.AXIS_HAT_Y));
        if ((nhx != 0 && hx == 0) || (nhy != 0 && hy == 0)) move(nhx * NUDGE, nhy * NUDGE, 0); // first press = one nudge
        hx = nhx; hy = nhy;
        if (lt < DEAD) lt = 0; if (rt < DEAD) rt = 0;
        startTicking();
        return true;
    }
}
