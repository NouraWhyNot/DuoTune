package com.duotune;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Everything that needs root goes through ONE launcher in Download, run via Retroid's "Run script as root".
 * Retroid runs scripts line by line, so the launcher is a single line that starts the bundled helper.
 * The app only picks which of the helper's three fixed actions to do (live, save, remove).
 */
final class Scripts {
    static final String LAUNCHER = "duotune.sh";
    static final String DISPLAY_DATA = "/data/vendor/display";
    private static final String VENDOR = "/vendor/etc/display";
    private static Handler main;
    // MediaStore values (written out because the MediaStore class ships as a separate module on Android 16)
    private static final String DOWNLOADS = "content://media/external/downloads";
    private static final String C_ID = "_id", C_NAME = "_display_name", C_MIME = "mime_type",
            C_PATH = "relative_path", C_PENDING = "is_pending";

    interface Done { void done(boolean ok, String message); }

    private Scripts() {}

    private static void post(final Done d, final boolean ok, final String msg) {
        synchronized (Scripts.class) { if (main == null) main = new Handler(Looper.getMainLooper()); }
        main.post(new Runnable() { public void run() { d.done(ok, msg); } });
    }

    static String files(Context c) { return c.getFilesDir().getAbsolutePath(); }
    static File result(Context c) { return new File(c.getFilesDir(), "result.txt"); }

    /**
     * Content of the one-line launcher: it starts the helper straight from DuoTune's install folder.
     * That folder has a random name per install, so after an uninstall this line points at nothing and does nothing.
     * DuoTune rewrites the line whenever the path changes (for example after an update).
     */
    static String launcherLine(Context c) { return helperPath(c) + " run\n"; }

    /** Creates or repairs Download/duotune.sh. Returns the file name actually used, or null. */
    static String ensureLauncher(Context c) {
        String want = launcherLine(c);
        ContentResolver cr = c.getContentResolver();
        Uri coll = Uri.parse(DOWNLOADS);
        try {
            Cursor q = cr.query(coll, new String[]{C_ID, C_NAME},
                    C_NAME + " LIKE ?", new String[]{"duotune%.sh"}, null);
            if (q != null) {
                try {
                    while (q.moveToNext()) {
                        Uri u = android.content.ContentUris.withAppendedId(coll, q.getLong(0));
                        String name = q.getString(1);
                        if (!want.equals(readUri(cr, u))) {      // missing or changed by someone else: rewrite it
                            OutputStream o = cr.openOutputStream(u, "wt");
                            o.write(want.getBytes("UTF-8")); o.close();
                        }
                        return name;
                    }
                } finally { q.close(); }
            }
            ContentValues v = new ContentValues();
            v.put(C_NAME, LAUNCHER);
            v.put(C_MIME, "application/x-sh");
            v.put(C_PATH, "Download/");
            v.put(C_PENDING, 1);
            Uri u = cr.insert(coll, v);
            if (u == null) return null;
            OutputStream o = cr.openOutputStream(u, "wt");
            o.write(want.getBytes("UTF-8")); o.close();
            ContentValues done = new ContentValues();
            done.put(C_PENDING, 0);
            cr.update(u, done, null, null);
            Cursor n = cr.query(u, new String[]{C_NAME}, null, null, null);
            String name = LAUNCHER;
            if (n != null) { if (n.moveToFirst()) name = n.getString(0); n.close(); }
            return name;
        } catch (Exception e) {
            return null;
        }
    }

    private static String readUri(ContentResolver cr, Uri u) {
        try {
            InputStream in = cr.openInputStream(u);
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[512]; int k;
            while ((k = in.read(buf)) > 0) b.write(buf, 0, k);
            in.close();
            return b.toString("UTF-8");
        } catch (Exception e) { return null; }
    }

    /**
     * Arms an action for the root helper: writes one word ("live", "save" or "remove") to files/action.
     * The helper (libpcctool.so, installed read-only from the signed APK) only knows these three fixed actions,
     * so nothing DuoTune writes can make it run anything else as root.
     */
    private static void writeAction(Context c, String action) throws Exception {
        result(c).delete();                                   // so the app can tell when this run finished
        new File(c.getFilesDir(), "run.sh").delete();         // left over from versions before 0.6
        File tmp = new File(c.getFilesDir(), "action.tmp");
        FileOutputStream o = new FileOutputStream(tmp);
        o.write((action + "\n").getBytes("UTF-8"));
        o.close();
        tmp.setReadable(true, false);
        if (!tmp.renameTo(new File(c.getFilesDir(), "action"))) throw new Exception("couldn't write the action");
    }

    /** The bundled native helper (pcctool), installed by Android next to the app. Its folder name changes on every install. */
    static String helperPath(Context c) { return c.getApplicationInfo().nativeLibraryDir + "/libpcctool.so"; }

    /** Connect: the helper starts live tuning and brings DuoTune back to the front. */
    static void armLive(Context c, float[] g) throws Exception {
        Pcc.writeLive(c, g);
        writeAction(c, "live");
    }

    /** Remove: the helper deletes DuoTune's calibration files and reboots (the Duo's own files in /vendor are never touched). */
    static void armRemove(Context c) throws Exception {
        writeAction(c, "remove");
    }

    /** Save: prepares calibration files with your values (no root needed: the originals are readable), then arms "save". */
    static void armSave(final Context c, final float[] g, final Done done) {
        new Thread(new Runnable() { public void run() {
            try {
                File out = new File(c.getFilesDir(), "out");
                if (out.exists()) for (File f : out.listFiles()) f.delete();
                out.mkdirs();
                String[] names = new File(VENDOR).list();
                if (names == null) { post(done, false, "Can't read " + VENDOR + ". Nothing was changed."); return; }
                int[] perScreen = new int[2];
                for (String name : names) {
                    if (!name.startsWith("qdcm_calib_data_") || !name.endsWith(".json")) continue;
                    int s;
                    // Panel names in the file names: il97680a = bottom screen, vtdr6130 = top screen (6 mode variants).
                    if (name.contains("il97680a")) s = Pcc.BOTTOM;
                    else if (name.contains("vtdr6130")) s = Pcc.TOP;
                    else continue;
                    byte[] src = readFile(new File(VENDOR, name));
                    if (src == null) continue;
                    int[] count = new int[1];
                    byte[] edited = Qdcm.rewrite(src, g[s * 3], g[s * 3 + 1], g[s * 3 + 2], count);
                    if (edited == null) continue;
                    FileOutputStream o = new FileOutputStream(new File(out, name));
                    o.write(edited); o.close();
                    new File(out, name).setReadable(true, false);
                    perScreen[s]++;
                }
                if (perScreen[Pcc.TOP] == 0 || perScreen[Pcc.BOTTOM] == 0) {
                    post(done, false, "Couldn't find the calibration files for both screens. Is this a Retroid Pocket Duo? Nothing was changed.");
                    return;
                }
                writeAction(c, "save");     // the helper copies these into /data/vendor/display and reboots
                post(done, true, "Prepared " + (perScreen[0] + perScreen[1]) + " calibration files (" + perScreen[0] + " top, " + perScreen[1] + " bottom).");
            } catch (Exception e) {
                post(done, false, "Preparing failed: " + e.getMessage());
            }
        }}).start();
    }

    /** Saved colours present in /data/vendor/display? */
    static int savedCount() {
        String[] l = new File(DISPLAY_DATA).list();
        if (l == null) return -1;
        int n = 0;
        for (String s : l) if (s.startsWith("qdcm_calib_data_")) n++;
        return n;
    }

    /** What the last script run wrote: "live", "saved N" or "removed" (null if it hasn't run yet). */
    static String readResult(Context c) {
        byte[] b = readFile(result(c));
        if (b == null) return null;
        try { return new String(b, "UTF-8").trim(); } catch (Exception e) { return null; }
    }

    private static byte[] readFile(File f) {
        try {
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[65536]; int k;
            while ((k = in.read(buf)) > 0) b.write(buf, 0, k);
            in.close();
            return b.toByteArray();
        } catch (Exception e) { return null; }
    }
}
