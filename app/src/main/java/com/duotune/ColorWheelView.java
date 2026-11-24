package com.duotune;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.view.MotionEvent;
import android.view.View;

/**
 * Hue/strength wheel. Angle = direction of the tint (clockwise from 3 o'clock: red, yellow, green, cyan, blue, magenta),
 * distance from centre = strength. Centre = no correction.
 */
public class ColorWheelView extends View {
    public interface OnChange { void onWheel(float hueDeg, float radius); }

    private final Paint sweep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cross = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float hue = 0f, radius = 0f; // radius 0..1
    private float cx, cy, R;
    private OnChange listener;
    private final float density;

    public ColorWheelView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(1.5f * density);
        ring.setColor(0xFFD5D9E0);
        cross.setStrokeWidth(1f * density);
        cross.setColor(0x33000000);
        dotRing.setStyle(Paint.Style.STROKE);
        dotRing.setStrokeWidth(2.5f * density);
        dotRing.setColor(0xFF1B1E24);
    }

    public void setOnChange(OnChange l) { listener = l; }

    public void setPosition(float hueDeg, float r) {
        hue = hueDeg; radius = Math.max(0f, Math.min(1f, r));
        invalidate();
    }

    /** Square, at most 260 dp, and never taller than the space it gets. */
    @Override protected void onMeasure(int w, int h) {
        int size = MeasureSpec.getSize(w);
        int hs = MeasureSpec.getSize(h);
        if (MeasureSpec.getMode(h) != MeasureSpec.UNSPECIFIED && hs > 0) size = Math.min(size, hs);
        size = Math.min(size, Math.round(260 * density));
        setMeasuredDimension(MeasureSpec.getSize(w), size);
    }

    /** Builds the gradients once the size is known: rainbow around, faded to white toward the centre. */
    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        cx = w / 2f; cy = h / 2f; R = Math.min(w, h) / 2f - 14 * density;
        int[] hues = {0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000};
        sweep.setShader(new SweepGradient(cx, cy, hues, null));
        fade.setShader(new RadialGradient(cx, cy, Math.max(R, 1), new int[]{0xFFFFFFFF, 0x99FFFFFF, 0x33FFFFFF},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
    }

    /** Wheel, ring, centre cross, then the dot (filled with the tint it represents). */
    @Override protected void onDraw(Canvas c) {
        c.drawCircle(cx, cy, R, sweep);
        c.drawCircle(cx, cy, R, fade);
        c.drawCircle(cx, cy, R, ring);
        float t = 8 * density;
        c.drawLine(cx - t, cy, cx + t, cy, cross);
        c.drawLine(cx, cy - t, cx, cy + t, cross);
        double a = Math.toRadians(hue);
        float x = cx + (float) (Math.cos(a) * radius * R);
        float y = cy + (float) (Math.sin(a) * radius * R);
        dotFill.setColor(Color.HSVToColor(new float[]{hue, Math.min(1f, radius), 1f}));
        float d = 11 * density;
        c.drawCircle(x, y, d, dotFill);
        c.drawCircle(x, y, d, dotRing);
    }

    /** Touch anywhere: angle = hue, distance = strength. Keeps the scroll view from stealing the drag. */
    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN && getParent() != null)
            getParent().requestDisallowInterceptTouchEvent(true);
        float dx = e.getX() - cx, dy = e.getY() - cy;
        float r = (float) Math.sqrt(dx * dx + dy * dy) / R;
        float h = (float) Math.toDegrees(Math.atan2(dy, dx));
        if (h < 0) h += 360f;
        if (r < 0.04f) r = 0f; // snap to centre
        setPosition(h, Math.min(1f, r));
        if (listener != null) listener.onWheel(hue, radius);
        if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        }
        return true;
    }
}
