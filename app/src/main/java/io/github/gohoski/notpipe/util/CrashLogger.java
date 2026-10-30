package io.github.gohoski.notpipe.util;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import android.content.Context;
import android.os.Environment;

import io.github.gohoski.notpipe.NotPipe;

/**
 * Writes every uncaught crash to a small log file so the exact stack trace
 * survives without adb (logcat's FATAL block carries no package name).
 * Internal file plus a best-effort copy at /sdcard/notPipePlus/crash.log.
 * Android 1.5+ compatible.
 */
public final class CrashLogger implements Thread.UncaughtExceptionHandler {

    private static final String FILE_NAME = "crash.log";
    /** Bounded history: the newest ~24 KB of crashes are kept. */
    private static final int MAX_BYTES = 24 * 1024;

    private final Thread.UncaughtExceptionHandler previous;

    private CrashLogger(Thread.UncaughtExceptionHandler previous) {
        this.previous = previous;
    }

    /** Install once from Application.onCreate. */
    public static void install() {
        try {
            Thread.UncaughtExceptionHandler current =
                    Thread.getDefaultUncaughtExceptionHandler();
            if (current instanceof CrashLogger) return; // idempotent
            Thread.setDefaultUncaughtExceptionHandler(new CrashLogger(current));
        } catch (Throwable ignored) {
        }
    }

    public void uncaughtException(Thread thread, Throwable t) {
        try {
            write(format(thread, t));
        } catch (Throwable ignored) {
        }
        // Chain on: the system still shows its dialog and kills the process.
        if (previous != null) {
            previous.uncaughtException(thread, t);
        }
    }

    private static String format(Thread thread, Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        return "\n---- crash " + stamp + " thread=" + thread.getName()
                + " app=" + BuildInfo.NAME + " ----\n" + sw.toString() + "\n";
    }

    private static final class BuildInfo {
        static final String NAME;
        static {
            String n = "unknown";
            try {
                n = io.github.gohoski.notpipe.BuildConfig.VERSION_NAME;
            } catch (Throwable ignored) {
            }
            NAME = n;
        }
    }

    /** Appends the report to the internal file (trimmed) + sdcard copy. */
    static void write(String report) {
        byte[] add = report.getBytes();
        Context ctx = NotPipe.getAppContext();
        if (ctx != null) {
            try {
                byte[] merged = merge(readInternal(ctx), add);
                FileOutputStream out = ctx.openFileOutput(FILE_NAME, Context.MODE_PRIVATE);
                try {
                    out.write(merged);
                } finally {
                    try {
                        out.close();
                    } catch (Exception ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            File root = Environment.getExternalStorageDirectory();
            if (root != null) {
                File dir = new File(root, "notPipePlus");
                if (dir.isDirectory() || dir.mkdirs()) {
                    File f = new File(dir, FILE_NAME);
                    if (f.exists() && f.length() > MAX_BYTES) {
                        f.delete();
                    }
                    FileOutputStream out = new FileOutputStream(f, true);
                    try {
                        out.write(add);
                    } finally {
                        try {
                            out.close();
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static byte[] readInternal(Context ctx) {
        try {
            FileInputStream in = ctx.openFileInput(FILE_NAME);
            try {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream(2048);
                byte[] chunk = new byte[2048];
                int n;
                while ((n = in.read(chunk)) != -1) {
                    buffer.write(chunk, 0, n);
                    if (buffer.size() > MAX_BYTES * 4) break;
                }
                return buffer.toByteArray();
            } finally {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        } catch (Throwable ignored) {
            return new byte[0];
        }
    }

    /** old + add, trimmed to the newest MAX_BYTES. */
    private static byte[] merge(byte[] old, byte[] add) {
        ByteArrayOutputStream all = new ByteArrayOutputStream(old.length + add.length);
        all.write(old, 0, old.length);
        all.write(add, 0, add.length);
        byte[] merged = all.toByteArray();
        if (merged.length <= MAX_BYTES) return merged;
        byte[] trimmed = new byte[MAX_BYTES];
        System.arraycopy(merged, merged.length - MAX_BYTES, trimmed, 0, MAX_BYTES);
        return trimmed;
    }
}
