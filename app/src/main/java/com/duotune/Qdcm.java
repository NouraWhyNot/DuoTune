package com.duotune;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Writes gains into Qualcomm QDCM calibration files (PostBlendPCC = byte-pair-swapped, hex-encoded JSON). */
final class Qdcm {
    // Finds every  "PostBlendPCC" : "<hex>"  entry in a calibration file (one per display mode).
    private static final Pattern PCC = Pattern.compile("(\"PostBlendPCC\"\\s*:\\s*\")([0-9A-Fa-f]+)(\")");
    // Coefficient names per output channel, in the order Qualcomm's own files use. Only the diagonal (r for R, g for G,
    // b for B) is set; the cross terms stay 0, so each channel is just scaled by its gain.
    private static final String[] KEYS = {"b", "bb", "c", "g", "gb", "gg", "r", "rb", "rg", "rgb", "rr"};
    private Qdcm() {}

    /** Python-style float: 1 -> "1.0", 0.930 -> "0.93". */
    static String num(float v) {
        BigDecimal d = new BigDecimal(String.valueOf(Math.round(v * 1000f) / 1000.0)).stripTrailingZeros();
        String s = d.toPlainString();
        return s.indexOf('.') < 0 ? s + ".0" : s;
    }

    /** One channel's coefficient object, e.g. {"b":0.0,...,"r":0.93,...}. */
    private static String block(float diag, String diagKey) {
        StringBuilder b = new StringBuilder("{");
        for (int i = 0; i < KEYS.length; i++) {
            if (i > 0) b.append(',');
            b.append('"').append(KEYS[i]).append("\":").append(KEYS[i].equals(diagKey) ? num(diag) : "0.0");
        }
        return b.append('}').toString();
    }

    /** The full PCC entry for one screen. enable=false when all gains are 1.0 (no correction). */
    static String pccJson(float r, float g, float b) {
        boolean enable = !(num(r).equals("1.0") && num(g).equals("1.0") && num(b).equals("1.0"));
        String json = "{\"B\":" + block(b, "b") + ",\"G\":" + block(g, "g") + ",\"R\":" + block(r, "r")
                + ",\"disableBeforeUpload\":false,\"displayID\":0,\"enable\":" + enable + "}";
        // Qualcomm swaps characters in pairs; an odd length leaves the last one unpaired and the decoder rejects the entry.
        // Pad by writing one zero coefficient as 0.00 (same value, one character longer).
        if (json.length() % 2 == 1) json = json.replaceFirst(":0\\.0,", ":0.00,");
        return json;
    }

    /** Qualcomm's storage format: swap every pair of characters, then write as uppercase hex. */
    static String encode(String json) {
        byte[] raw = json.getBytes();
        byte[] sw = new byte[raw.length];
        for (int i = 0; i + 1 < raw.length; i += 2) { sw[i] = raw[i + 1]; sw[i + 1] = raw[i]; }
        if (raw.length % 2 == 1) sw[raw.length - 1] = raw[raw.length - 1];
        StringBuilder hex = new StringBuilder(sw.length * 2);
        for (byte x : sw) hex.append(String.format("%02X", x & 0xFF));
        return hex.toString();
    }

    /** Replaces every PostBlendPCC entry. Returns null if the file has none. out[0] receives the count. */
    static byte[] rewrite(byte[] file, float r, float g, float b, int[] out) {
        String text;
        try { text = new String(file, "ISO-8859-1"); } catch (Exception e) { return null; }
        String hex = encode(pccJson(r, g, b));
        Matcher m = PCC.matcher(text);
        StringBuffer sb = new StringBuffer();
        int n = 0;
        while (m.find()) { m.appendReplacement(sb, "$1" + hex + "$3"); n++; }
        m.appendTail(sb);
        out[0] = n;
        if (n == 0) return null;
        try { return sb.toString().getBytes("ISO-8859-1"); } catch (Exception e) { return null; }
    }
}
