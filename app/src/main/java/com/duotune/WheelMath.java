package com.duotune;

/** Converts between (wheel hue, wheel radius, brightness) and per-channel gains. Gains never exceed 1.0. */
public final class WheelMath {
    /** Strength at the wheel edge: the weakest channel goes down to 1 - MAX_SAT. */
    public static final float MAX_SAT = 0.5f;
    private WheelMath() {}

    /** radius is squared so small corrections near the centre are easy to place precisely. */
    public static float[] toGains(float hueDeg, float radius, float bright) {
        float s = MAX_SAT * radius * radius;           // how much the weakest channel is lowered
        // Standard HSV-style sector maths: which channels stay high depends on the hue (60° sectors).
        float h = ((hueDeg % 360f) + 360f) % 360f / 60f;
        float x = s * (1f - Math.abs(h % 2f - 1f));
        float r, g, b;
        if (h < 1) { r = s; g = x; b = 0; }
        else if (h < 2) { r = x; g = s; b = 0; }
        else if (h < 3) { r = 0; g = s; b = x; }
        else if (h < 4) { r = 0; g = x; b = s; }
        else if (h < 5) { r = x; g = 0; b = s; }
        else { r = s; g = 0; b = x; }
        float m = 1f - s;                              // lift so the strongest channel ends at 1.0, then apply brightness
        return new float[]{(r + m) * bright, (g + m) * bright, (b + m) * bright};
    }

    /** @return {hueDeg, radius 0..1, brightness} */
    public static float[] fromGains(float r, float g, float b) {
        float max = Math.max(r, Math.max(g, b));
        if (max <= 0f) return new float[]{0f, 0f, 1f};
        r /= max; g /= max; b /= max;                  // brightness = the strongest channel; normalise it away
        float min = Math.min(r, Math.min(g, b));
        float s = 1f - min;
        if (s < 1e-4f) return new float[]{0f, 0f, max};
        float h;
        if (r >= g && r >= b) h = 60f * (((g - b) / s) % 6f);
        else if (g >= b) h = 60f * ((b - r) / s + 2f);
        else h = 60f * ((r - g) / s + 4f);
        if (h < 0) h += 360f;
        float rad = (float) Math.sqrt(Math.min(1f, s / MAX_SAT));
        return new float[]{h, rad, max};
    }
}
