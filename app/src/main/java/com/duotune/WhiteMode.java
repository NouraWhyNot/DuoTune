package com.duotune;

import android.app.Activity;
import android.graphics.Color;
import android.view.View;
import android.widget.FrameLayout;

/**
 * "White" comparison mode: both screens turn completely white (system bars hidden) so the two whites can be
 * compared with nothing in the way. Tap either screen, or press B, to return. Shared across both windows.
 */
final class WhiteMode {
    private static boolean on;
    private WhiteMode() {}

    static boolean isOn() { return on; }

    static void set(boolean value) {
        if (on == value) return;
        on = value;
        Pcc.notifyAllWatchers();
    }

    /** Wraps an activity's content in a frame with a white overlay that follows the shared mode. Returns the frame. */
    static FrameLayout wrap(final Activity a, View content) {
        FrameLayout frame = new FrameLayout(a);
        frame.addView(content);
        final View white = new View(a);
        white.setBackgroundColor(Color.WHITE);
        white.setClickable(true);
        white.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { set(false); } });
        white.setVisibility(View.GONE);
        frame.addView(white, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        // White mode hides the status and navigation bars too, so nothing but white is on screen.
        final int normalUi = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        final Runnable sync = new Runnable() {
            public void run() {
                a.runOnUiThread(new Runnable() { public void run() {
                    white.setVisibility(on ? View.VISIBLE : View.GONE);
                    a.getWindow().getDecorView().setSystemUiVisibility(on
                            ? View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                              | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            : normalUi);
                } });
            }
        };
        frame.setTag(sync);   // the activity unregisters it on destroy
        Pcc.watch(sync);
        sync.run();
        return frame;
    }

    static void unwrap(View frame) {
        if (frame != null && frame.getTag() instanceof Runnable) Pcc.unwatch((Runnable) frame.getTag());
    }
}
