package com.fadcam;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Durable diagnostics for process death — the only thing that survives a native crash, an
 * ANR, or a low-memory kill.
 *
 * <p>Everything here writes to {@code filesDir}, never {@code cacheDir}, and never goes
 * through {@link Log}/{@link FLog}: both depend on {@link Log#init(Context)} having run,
 * which happens only when {@code HomeFragment} is created. The whole point of this class is
 * to capture data <em>before</em> that, so it must not depend on it.
 *
 * <p>Flow:
 * <ol>
 *   <li>{@link #captureExitReasons(Context)} runs first thing in {@code Application.onCreate}
 *       and persists the platform's {@link ActivityManager#getHistoricalProcessExitReasons}
 *       for the PREVIOUS process.</li>
 *   <li>{@link #recordCrash} persists a fatal Java exception from the uncaught handler.</li>
 *   <li>{@link #consumeForDossier(Context)} is called once logging is actually live, moves
 *       both into the HTML debug log, and deletes the files so they are not repeated on
 *       every launch.</li>
 * </ol>
 */
public final class DiagnosticsStore {

    private static final String TAG = "DiagnosticsStore";
    private static final String DIR = "diagnostics";
    private static final String FILE_EXIT_REASONS = "exit_reasons.txt";
    private static final String FILE_CRASH = "last_crash.txt";
    private static final String FILE_CRASH_PREV = "prev_crash.txt";

    /**
     * The last recording-start attempt, written synchronously (fsync) before every risky step.
     * This is what survives a native crash or an ANR during recorder setup, where the async HTML
     * log loses the final 250 ms — the exact blind spot that made issue #176 unreadable.
     */
    private static final String FILE_RECORDING = "recording_start.txt";
    private static final int MAX_RECORDING_BYTES = 96 * 1024;
    private static final int MAX_RECORDING_LINES = 500;
    /** True between beginRecordingJournal() and endRecordingJournal() in this process. */
    private static volatile boolean recordingJournalActive = false;

    /** Separate prefs file — must not depend on SharedPreferencesManager being usable yet. */
    private static final String PREFS = "fadcam_diagnostics_v1";
    private static final String KEY_LAST_EXIT_TS = "last_exit_ts";

    /** Caps so a runaway trace can never bloat the debug log. */
    private static final int MAX_TRACE_LINES = 120;
    private static final int MAX_FILE_BYTES = 256 * 1024;

    private DiagnosticsStore() {
    }

    // ------------------------------------------------------------------ exit reasons

    /**
     * Reads the platform's record of how the PREVIOUS process died and persists anything not
     * already reported in an earlier session.
     *
     * <p>Safe to call on every start; it is a no-op below API 30 and is fully guarded so a
     * broken OEM implementation can never take down {@code Application.onCreate}.
     */
    public static void captureExitReasons(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        try {
            ActivityManager am =
                    (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                return;
            }
            List<ApplicationExitInfo> history =
                    am.getHistoricalProcessExitReasons(context.getPackageName(), 0, 5);
            if (history == null || history.isEmpty()) {
                return;
            }

            SharedPreferences prefs =
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            long lastSeenTs = prefs.getLong(KEY_LAST_EXIT_TS, 0L);
            long newestTs = lastSeenTs;
            StringBuilder report = new StringBuilder();

            for (ApplicationExitInfo info : history) {
                long ts = info.getTimestamp();
                if (ts <= lastSeenTs) {
                    continue; // already reported in a previous session
                }
                if (ts > newestTs) {
                    newestTs = ts;
                }
                int reason = info.getReason();
                // Normal, user-initiated termination — not interesting.
                if (reason == ApplicationExitInfo.REASON_EXIT_SELF
                        || reason == ApplicationExitInfo.REASON_USER_REQUESTED
                        || reason == ApplicationExitInfo.REASON_USER_STOPPED) {
                    continue;
                }
                report.append("[EXIT-INFO] reason=").append(reason)
                        .append(" (").append(exitReasonName(reason)).append(")")
                        .append(" | status=").append(info.getStatus())
                        .append(" | at=").append(formatTimestamp(ts));
                String description = info.getDescription();
                if (description != null && !description.isEmpty()) {
                    report.append(" | description=").append(description);
                }
                report.append('\n');

                try (java.io.InputStream trace = info.getTraceInputStream()) {
                    if (trace != null) {
                        String text = readStream(trace);
                        if (!text.isEmpty()) {
                            report.append("---- trace (").append(exitReasonName(reason))
                                    .append(") ----\n").append(text).append('\n');
                        }
                    }
                } catch (Exception ignored) {
                    // Trace is best-effort.
                }
            }

            // Advance the watermark even when everything was filtered, so normal exits are
            // not re-examined forever.
            prefs.edit().putLong(KEY_LAST_EXIT_TS, newestTs).apply();

            if (report.length() > 0) {
                writeFile(new File(diagnosticsDir(context), FILE_EXIT_REASONS),
                        report.toString());
            }
        } catch (Throwable t) {
            // Never let diagnostics break startup.
            android.util.Log.w(TAG, "captureExitReasons failed", t);
        }
    }

    // ------------------------------------------------------------------ crash capture

    /**
     * Persists a fatal exception. Called from the uncaught exception handler, i.e. while the
     * process is already dying — so: no logging framework, no allocation-heavy work, no
     * throwing.
     */
    public static void recordCrash(Context context, String threadName, String stackTrace) {
        try {
            File dir = diagnosticsDir(context);
            File current = new File(dir, FILE_CRASH);
            File previous = new File(dir, FILE_CRASH_PREV);
            if (current.exists()) {
                // Rotate so two crashes between launches are both preserved.
                if (previous.exists() && !previous.delete()) {
                    android.util.Log.w(TAG, "Could not clear previous crash file");
                }
                if (!current.renameTo(previous)) {
                    android.util.Log.w(TAG, "Could not rotate crash file");
                }
            }
            String text = "thread=" + threadName + "\n"
                    + "at=" + formatTimestamp(System.currentTimeMillis()) + "\n\n"
                    + stackTrace;
            writeFile(current, text);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "recordCrash failed", t);
        }
    }

    // ------------------------------------------------------------------ dossier ingest

    /**
     * Returns the captured diagnostics formatted for the in-app debug log and then removes
     * them, so each death is shown exactly once.
     *
     * @return a block ready to be appended as log lines, or {@code null} when there is nothing
     *     to report.
     */
    public static String consumeForDossier(Context context) {
        try {
            File dir = diagnosticsDir(context);
            StringBuilder out = new StringBuilder();
            boolean any = false;

            File exits = new File(dir, FILE_EXIT_REASONS);
            String exitText = readAndDelete(exits);
            if (exitText != null) {
                any = true;
                out.append("SYSTEM: ===== PREVIOUS PROCESS EXIT =====\n");
                out.append(exitText);
                out.append("SYSTEM: ===== END PREVIOUS PROCESS EXIT =====\n");
            }

            File prevCrash = new File(dir, FILE_CRASH_PREV);
            String prevText = readAndDelete(prevCrash);
            if (prevText != null) {
                any = true;
                out.append("ERROR: ===== PREVIOUS CRASH (older) =====\n");
                out.append(prevText);
                out.append('\n');
            }

            File crash = new File(dir, FILE_CRASH);
            String crashText = readAndDelete(crash);
            if (crashText != null) {
                any = true;
                out.append("ERROR: ===== PREVIOUS CRASH (fatal) =====\n");
                out.append(crashText);
                out.append('\n');
            }

            return any ? out.toString() : null;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "consumeForDossier failed", t);
            return null;
        }
    }

    // ------------------------------------------------------------------ recording journal

    /**
     * Starts a fresh recording-start journal. Called at the top of
     * {@code RecordingService.startRecording()} before any encoder work, so a process death later
     * still leaves the device/settings snapshot on disk.
     */
    public static void beginRecordingJournal(Context context, String header) {
        if (!isDebugLoggingEnabled(context)) {
            // The journal is a troubleshooting tool, not a production tax: no file, no fsync,
            // no device report when the debug switch is off.
            recordingJournalActive = false;
            return;
        }
        recordingJournalActive = true;
        try {
            File dir = diagnosticsDir(context);
            String text = "at=" + formatTimestamp(System.currentTimeMillis()) + "\n"
                    + "thread=" + Thread.currentThread().getName() + "\n"
                    + buildDeviceReport(context)
                    + (header == null ? "" : header + "\n");
            writeFile(new File(dir, FILE_RECORDING), text);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "beginRecordingJournal failed", t);
        }
    }

    /**
     * Appends one line to the current recording journal and fsyncs it. Cheap enough for the ~10
     * steps of recorder setup, fatal to skip when the next step can kill the process.
     */
    public static void appendRecordingJournal(Context context, String line) {
        if (!recordingJournalActive) {
            return;
        }
        if (context == null || line == null || line.isEmpty()) {
            return;
        }
        try {
            File dir = diagnosticsDir(context);
            File file = new File(dir, FILE_RECORDING);
            if (file.length() > MAX_RECORDING_BYTES) {
                // One marker, then stop growing. Losing the tail is better than an unbounded file.
                if (file.length() < MAX_RECORDING_BYTES + 256) {
                    appendLine(file, "[journal full — further lines omitted]");
                }
                return;
            }
            appendLine(file, line);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "appendRecordingJournal failed", t);
        }
    }

    /** Appends the final result and stops accepting journal lines for this attempt. */
    public static void endRecordingJournal(Context context, String line) {
        try {
            if (line != null) {
                appendRecordingJournal(context, line);
            }
        } finally {
            recordingJournalActive = false;
        }
    }

    /** Reads the last recording-start journal, or {@code null} when none exists. */
    public static String readRecordingJournal(Context context) {
        if (!isDebugLoggingEnabled(context)) {
            return null;
        }
        try {
            File file = new File(diagnosticsDir(context), FILE_RECORDING);
            if (!file.exists() || file.length() == 0) {
                return null;
            }
            try (FileInputStream fis = new FileInputStream(file)) {
                return readStream(fis, MAX_RECORDING_LINES);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static void appendLine(File file, String line) {
        try (FileOutputStream fos = new FileOutputStream(file, true);
                OutputStreamWriter writer =
                        new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            writer.write(formatTimestamp(System.currentTimeMillis()));
            writer.write(' ');
            writer.write(line);
            writer.write('\n');
            writer.flush();
            try {
                fos.getFD().sync();
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "appendLine failed: " + file.getName(), t);
        }
    }

    // ------------------------------------------------------------------ device report

    /**
     * A compact, privacy-safe device/build report. This is what makes an OEM bug identifiable
     * without adb: EMUI builds carry their version in {@code Build.DISPLAY}/{@code INCREMENTAL}.
     */
    public static String buildDeviceReport(Context context) {
        StringBuilder sb = new StringBuilder(512);
        try {
            sb.append("brand=").append(safe(Build.BRAND))
                    .append(" manufacturer=").append(safe(Build.MANUFACTURER))
                    .append(" model=").append(safe(Build.MODEL))
                    .append(" device=").append(safe(Build.DEVICE))
                    .append('\n');
            sb.append("android=").append(safe(Build.VERSION.RELEASE))
                    .append(" api=").append(Build.VERSION.SDK_INT)
                    .append(" buildId=").append(safe(Build.ID))
                    .append(" display=").append(safe(Build.DISPLAY))
                    .append(" incremental=").append(safe(Build.VERSION.INCREMENTAL))
                    .append('\n');
            sb.append("fingerprint=").append(safe(Build.FINGERPRINT)).append('\n');
            sb.append("hardware=").append(safe(Build.HARDWARE))
                    .append(" board=").append(safe(Build.BOARD))
                    .append(" product=").append(safe(Build.PRODUCT))
                    .append('\n');
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                sb.append("soc=").append(safe(Build.SOC_MANUFACTURER))
                        .append(' ').append(safe(Build.SOC_MODEL)).append('\n');
            }
            sb.append("abi=").append(join(Build.SUPPORTED_ABIS)).append('\n');
            sb.append("app=").append(BuildConfig.VERSION_NAME)
                    .append(" (").append(BuildConfig.VERSION_CODE).append(')')
                    .append(" pkg=").append(context.getPackageName())
                    .append('\n');
            ActivityManager am =
                    (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mem = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mem);
                sb.append("memory=").append(mem.totalMem / (1024 * 1024)).append("MB")
                        .append(" avail=").append(mem.availMem / (1024 * 1024)).append("MB")
                        .append(" lowRam=").append(am.isLowRamDevice())
                        .append(" class=").append(am.getMemoryClass()).append("MB")
                        .append(" largeClass=").append(am.getLargeMemoryClass()).append("MB")
                        .append(" cpus=").append(Runtime.getRuntime().availableProcessors())
                        .append('\n');
            }
        } catch (Throwable t) {
            sb.append("device-report-error=").append(t.getClass().getSimpleName()).append('\n');
        }
        return sb.toString();
    }

    private static String safe(String value) {
        return value == null || value.isEmpty() ? "?" : value;
    }

    /** Reads the user's debug switch directly; DiagnosticsStore must not depend on Log. */
    private static boolean isDebugLoggingEnabled(Context context) {
        if (context == null) {
            return false;
        }
        try {
            return SharedPreferencesManager.getInstance(context).isDebugLoggingEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    private static String join(String[] values) {
        if (values == null || values.length == 0) {
            return "?";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(values[i]);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ helpers

    private static File diagnosticsDir(Context context) {
        File base = context.getFilesDir();
        File dir = new File(base, DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            android.util.Log.w(TAG, "Could not create diagnostics directory");
        }
        return dir;
    }

    private static void writeFile(File file, String text) {
        try (FileOutputStream fos = new FileOutputStream(file, false);
                OutputStreamWriter writer =
                        new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            if (text.length() > MAX_FILE_BYTES) {
                text = text.substring(0, MAX_FILE_BYTES);
            }
            writer.write(text);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "writeFile failed: " + file.getName(), t);
        }
    }

    /** Reads a file, deletes it, and returns its content (or {@code null} if empty/missing). */
    private static String readAndDelete(File file) {
        if (file == null || !file.exists() || file.length() == 0) {
            if (file != null && file.exists()) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
            return null;
        }
        String text = null;
        try (FileInputStream fis = new FileInputStream(file)) {
            text = readStream(fis);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "readAndDelete failed: " + file.getName(), t);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
        return (text == null || text.trim().isEmpty()) ? null : text;
    }

    private static String readStream(java.io.InputStream stream) throws Exception {
        return readStream(stream, MAX_TRACE_LINES);
    }

    private static String readStream(java.io.InputStream stream, int maxLines) throws Exception {
        StringBuilder sb = new StringBuilder();
        BufferedReader reader =
                new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        String line;
        int lines = 0;
        while ((line = reader.readLine()) != null && lines < maxLines) {
            sb.append(line).append('\n');
            lines++;
        }
        return sb.toString();
    }

    private static String formatTimestamp(long millis) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    .format(new Date(millis));
        } catch (Exception e) {
            return String.valueOf(millis);
        }
    }

    private static String exitReasonName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_CRASH:
                return "JAVA_CRASH";
            case ApplicationExitInfo.REASON_CRASH_NATIVE:
                return "NATIVE_CRASH";
            case ApplicationExitInfo.REASON_ANR:
                return "ANR";
            case ApplicationExitInfo.REASON_LOW_MEMORY:
                return "LOW_MEMORY_KILL";
            case ApplicationExitInfo.REASON_SIGNALED:
                return "SIGNALED";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE:
                return "EXCESSIVE_RESOURCE_USAGE";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE:
                return "INIT_FAILURE";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED:
                return "DEPENDENCY_DIED";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE:
                return "PERMISSION_CHANGE";
            case ApplicationExitInfo.REASON_OTHER:
                return "OTHER";
            default:
                return "UNKNOWN";
        }
    }
}
