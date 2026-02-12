package com.fadcam.service;

import android.app.Service;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.FFprobeKit;
import com.arthenica.ffmpegkit.MediaInformation;
import com.arthenica.ffmpegkit.MediaInformationSession;
import com.arthenica.ffmpegkit.ReturnCode;
import com.arthenica.ffmpegkit.StreamInformation;
import com.fadcam.Constants;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.Utils;
import com.fadcam.utils.RecordingStoragePaths;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BatchMediaActionService extends Service {

    private static final String TAG = "BatchMediaActionSvc";
    private static final int FOREGROUND_ID = 1204;

    private ExecutorService executor;
    private BatchMediaNotificationManager notificationManager;
    private SharedPreferencesManager sharedPreferencesManager;

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadExecutor();
        notificationManager = new BatchMediaNotificationManager(this);
        sharedPreferencesManager = SharedPreferencesManager.getInstance(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final BatchMediaActionTask task = BatchMediaActionTask.fromIntent(intent);
        if (task == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        String title = task.actionType == BatchMediaActionTask.ActionType.EXPORT_STANDARD_MP4
                ? getString(R.string.records_batch_faditor_export_standard)
                : getString(R.string.records_batch_faditor_merge);

        startForeground(
                FOREGROUND_ID,
                notificationManager.createForegroundNotification(
                        title,
                        getString(R.string.records_batch_starting)
                )
        );

        executor.submit(() -> runTask(task, startId));
        return START_NOT_STICKY;
    }

    private void runTask(@NonNull BatchMediaActionTask task, int startId) {
        long startedAtMs = System.currentTimeMillis();
        int completed = 0;
        int failed = 0;
        int skipped = 0;
        int total = task.actionType == BatchMediaActionTask.ActionType.MERGE_VIDEOS ? 1 : task.inputUris.size();

        try {
            if (task.actionType == BatchMediaActionTask.ActionType.EXPORT_STANDARD_MP4) {
                int done = 0;
                for (Uri inputUri : task.inputUris) {
                    done++;
                    String eta = buildEtaText(startedAtMs, done - 1, task.inputUris.size());
                    notificationManager.updateProgress(
                            getString(R.string.records_batch_faditor_export_standard),
                            getString(R.string.records_batch_progress_item, done, task.inputUris.size()) + eta,
                            done - 1,
                            task.inputUris.size());

                    ProcessResult result = exportStandardMp4(inputUri, done, task);
                    if (result.success) completed++;
                    else if (result.skipped) skipped++;
                    else failed++;

                    notificationManager.updateProgress(
                            getString(R.string.records_batch_faditor_export_standard),
                            getString(R.string.records_batch_progress_counts, completed, skipped, failed),
                            done,
                            task.inputUris.size());
                }
            } else {
                notificationManager.updateProgress(
                        getString(R.string.records_batch_faditor_merge),
                        getString(R.string.records_batch_starting),
                        0,
                        task.inputUris.size() + 2);

                MergeResult merge = mergeVideos(task.inputUris, task, (copied, totalInputs) -> {
                    String eta = buildEtaText(startedAtMs, copied, totalInputs + 2);
                    notificationManager.updateProgress(
                            getString(R.string.records_batch_faditor_merge),
                            "Preparing merge " + copied + "/" + totalInputs + eta,
                            copied,
                            totalInputs + 2
                    );
                });
                if (merge.success) completed = 1;
                else failed = 1;
                skipped = merge.skippedCount;

                notificationManager.updateProgress(
                        getString(R.string.records_batch_faditor_merge),
                        getString(R.string.records_batch_progress_counts, completed, skipped, failed),
                        task.inputUris.size() + 2,
                        task.inputUris.size() + 2);
            }
        } catch (Exception e) {
            Log.e(TAG, "Batch action failed", e);
            failed = Math.max(failed, 1);
        }

        final String summary = getString(
                R.string.records_batch_summary,
                completed,
                skipped,
                failed
        );

        String doneTitle = task.actionType == BatchMediaActionTask.ActionType.EXPORT_STANDARD_MP4
                ? getString(R.string.records_batch_export_done_title)
                : getString(R.string.records_batch_merge_done_title);
        notificationManager.showCompletion(doneTitle, summary, failed == 0);

        Intent completedIntent = new Intent(Constants.ACTION_BATCH_MEDIA_COMPLETED);
        completedIntent.putExtra(Constants.EXTRA_BATCH_COMPLETED_MESSAGE, summary);
        sendBroadcast(completedIntent);

        stopForeground(STOP_FOREGROUND_REMOVE);
        notificationManager.cancelProgress();
        stopSelf(startId);
    }

    @NonNull
    private String buildEtaText(long startedAtMs, int done, int total) {
        if (done <= 0 || total <= 1) return "";
        long elapsed = Math.max(1L, System.currentTimeMillis() - startedAtMs);
        double perUnit = elapsed / (double) done;
        long remainingMs = (long) (perUnit * Math.max(0, total - done));
        long sec = Math.max(0, remainingMs / 1000L);
        long min = sec / 60;
        long remSec = sec % 60;
        if (min > 0) {
            return " • ETA " + min + "m " + remSec + "s";
        }
        return " • ETA " + remSec + "s";
    }

    @NonNull
    private ProcessResult exportStandardMp4(@NonNull Uri inputUri, int index, @NonNull BatchMediaActionTask task) {
        InputPathHolder input = null;
        OutputTarget outputTarget = null;
        try {
            input = resolveInputPath(inputUri);
            if (input == null) return ProcessResult.failed();

            String displayName = getDisplayName(inputUri);
            String baseName = stripExtension(displayName == null ? ("video_" + index + ".mp4") : displayName);
            String outputName = Constants.RECORDING_FILE_PREFIX_FADITOR_STANDARD
                    + baseName
                    + "_"
                    + timestampSuffix()
                    + ".mp4";

            outputTarget = resolveOutputTarget(task, outputName);
            if (outputTarget == null) return ProcessResult.failed();

            String ffmpegCmd = String.format(
                    Locale.US,
                    "-i \"%s\" -c copy -movflags +faststart -y \"%s\"",
                    input.path,
                    outputTarget.processingPath
            );
            FFmpegSession session = FFmpegKit.execute(ffmpegCmd);
            if (!ReturnCode.isSuccess(session.getReturnCode())) {
                Log.w(TAG, "Export FFmpeg failed: " + session.getOutput());
                return ProcessResult.failed();
            }

            if (!finalizeOutput(outputTarget)) return ProcessResult.failed();
            return ProcessResult.success();
        } catch (Exception e) {
            Log.e(TAG, "Export failed for uri: " + inputUri, e);
            return ProcessResult.failed();
        } finally {
            closeQuietly(input);
            cleanupOutputTarget(outputTarget);
        }
    }

    @NonNull
    private MergeResult mergeVideos(
            @NonNull List<Uri> inputUris,
            @NonNull BatchMediaActionTask task,
            @Nullable MergeProgressListener listener
    ) {
        if (inputUris.size() < 2) {
            return new MergeResult(false, inputUris.size());
        }

        ArrayList<File> tempInputs = new ArrayList<>();
        File concatFile = null;
        OutputTarget outputTarget = null;
        try {
            int skippedCount = 0;
            ArrayList<String> mergePaths = new ArrayList<>();
            int sourceIndex = 0;
            for (Uri uri : inputUris) {
                sourceIndex++;
                File tmp = copyUriToTempMergeFile(uri, sourceIndex);
                if (tmp == null) {
                    skippedCount++;
                    continue;
                }
                tempInputs.add(tmp);
                mergePaths.add(tmp.getAbsolutePath());
                if (listener != null) listener.onInputsCopied(mergePaths.size(), inputUris.size());
            }

            if (mergePaths.size() < 2) {
                return new MergeResult(false, skippedCount);
            }

            String outputName = Constants.RECORDING_FILE_PREFIX_FADITOR_MERGE
                    + timestampSuffix()
                    + ".mp4";
            outputTarget = resolveOutputTarget(task, outputName);
            if (outputTarget == null) return new MergeResult(false, skippedCount);

            concatFile = new File(getCacheDir(), "faditor_batch_concat_" + System.currentTimeMillis() + ".txt");
            try (FileOutputStream fos = new FileOutputStream(concatFile)) {
                for (String path : mergePaths) {
                    String escapedPath = path.replace("'", "'\\''");
                    fos.write(("file '" + escapedPath + "'\n").getBytes());
                }
            }

            String concatCopyCmd = String.format(
                    Locale.US,
                    "-f concat -safe 0 -i \"%s\" -c copy -movflags +faststart -y \"%s\"",
                    concatFile.getAbsolutePath(),
                    outputTarget.processingPath
            );
            FFmpegSession session = FFmpegKit.execute(concatCopyCmd);
            if (!ReturnCode.isSuccess(session.getReturnCode())) {
                Log.w(TAG, "Merge copy mode failed, trying safe re-encode fallback");
                StringBuilder inputArgs = new StringBuilder();
                StringBuilder concatFilter = new StringBuilder();
                int count = mergePaths.size();
                for (int i = 0; i < count; i++) {
                    inputArgs.append(" -i \"").append(mergePaths.get(i)).append("\"");
                    concatFilter.append("[").append(i).append(":v:0]")
                            .append("[").append(i).append(":a:0]");
                }
                String concatReencodeCmd = String.format(
                        Locale.US,
                        "%s -filter_complex \"%sconcat=n=%d:v=1:a=1[outv][outa]\" -map \"[outv]\" -map \"[outa]\" -c:v libx264 -preset veryfast -crf 20 -c:a aac -movflags +faststart -y \"%s\"",
                        inputArgs.toString(),
                        concatFilter.toString(),
                        count,
                        outputTarget.processingPath
                );
                FFmpegSession fallbackSession = FFmpegKit.execute(concatReencodeCmd);
                if (!ReturnCode.isSuccess(fallbackSession.getReturnCode())) {
                    Log.w(TAG, "Merge fallback failed: " + fallbackSession.getOutput());
                    return new MergeResult(false, skippedCount);
                }
            }

            if (!finalizeOutput(outputTarget)) {
                return new MergeResult(false, skippedCount);
            }
            return new MergeResult(true, skippedCount);
        } catch (Exception e) {
            Log.e(TAG, "Merge failed", e);
            return new MergeResult(false, inputUris.size());
        } finally {
            if (concatFile != null && concatFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                concatFile.delete();
            }
            for (File temp : tempInputs) {
                if (temp != null && temp.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    temp.delete();
                }
            }
            cleanupOutputTarget(outputTarget);
        }
    }

    @Nullable
    private File copyUriToTempMergeFile(@NonNull Uri uri, int index) {
        File mergeDir = new File(getCacheDir(), "batch_merge_inputs");
        if (!mergeDir.exists() && !mergeDir.mkdirs()) {
            return null;
        }
        String name = getDisplayName(uri);
        if (name == null || name.trim().isEmpty()) {
            name = "merge_source_" + index + ".mp4";
        }
        File target = createUniqueFile(mergeDir, name);
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(target)) {
            if (in == null) return null;
            byte[] buffer = new byte[16 * 1024];
            int len;
            while ((len = in.read(buffer)) > 0) {
                out.write(buffer, 0, len);
            }
            out.flush();
            return target;
        } catch (Exception e) {
            if (target.exists()) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
            }
            return null;
        }
    }

    @Nullable
    private InputPathHolder resolveInputPath(@NonNull Uri uri) {
        try {
            if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
                File f = new File(uri.getPath());
                if (f.exists() && f.canRead()) {
                    return new InputPathHolder(uri, f.getAbsolutePath(), null);
                }
            }

            String reconstructed = reconstructFilePath(uri);
            if (reconstructed != null) {
                return new InputPathHolder(uri, reconstructed, null);
            }

            ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r");
            if (pfd != null) {
                return new InputPathHolder(uri, "/proc/self/fd/" + pfd.getFd(), pfd);
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not resolve input path: " + uri, e);
        }
        return null;
    }

    @Nullable
    private String reconstructFilePath(@NonNull Uri uri) {
        try {
            String path = uri.getPath();
            if (path == null || !path.contains(":")) return null;
            int lastColon = path.lastIndexOf(':');
            if (lastColon < 0 || lastColon >= path.length() - 1) return null;
            String relativePath = path.substring(lastColon + 1);

            File[] externalDirs = getExternalFilesDirs(null);
            if (externalDirs == null) return null;
            for (File dir : externalDirs) {
                if (dir == null) continue;
                String dirPath = dir.getAbsolutePath();
                int androidIndex = dirPath.indexOf("/Android/");
                if (androidIndex <= 0) continue;
                String volumeRoot = dirPath.substring(0, androidIndex + 1);
                File candidate = new File(volumeRoot + relativePath);
                if (candidate.exists() && candidate.canRead()) {
                    return candidate.getAbsolutePath();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @Nullable
    private OutputTarget resolveOutputTarget(@NonNull BatchMediaActionTask task, @NonNull String fileName) {
        try {
            if (task.outputMode == BatchMediaActionTask.OutputMode.CUSTOM_TREE_URI && task.customTreeUri != null) {
                DocumentFile picked = DocumentFile.fromTreeUri(this, task.customTreeUri);
                if (picked != null && picked.exists() && picked.canWrite()) {
                    DocumentFile outFile = createUniqueSafFile(picked, fileName);
                    if (outFile != null) {
                        File temp = new File(getCacheDir(), "batch_tmp_" + System.currentTimeMillis() + "_" + fileName);
                        return new OutputTarget(temp.getAbsolutePath(), temp, outFile, true);
                    }
                }
            }

            if (task.outputMode == BatchMediaActionTask.OutputMode.DEFAULT_FADITOR) {
                RecordingStoragePaths.FaditorOutputType outputType =
                        task.actionType == BatchMediaActionTask.ActionType.MERGE_VIDEOS
                                ? RecordingStoragePaths.FaditorOutputType.MERGE
                                : RecordingStoragePaths.FaditorOutputType.CONVERTED;
                String customUri = sharedPreferencesManager.getCustomStorageUri();
                DocumentFile safDir = RecordingStoragePaths.getSafFaditorOutputDir(
                        this,
                        customUri,
                        outputType,
                        true
                );
                if (safDir != null && safDir.canWrite()) {
                    DocumentFile safFile = createUniqueSafFile(safDir, fileName);
                    if (safFile != null) {
                        File temp = new File(getCacheDir(), "batch_tmp_" + System.currentTimeMillis() + "_" + fileName);
                        return new OutputTarget(temp.getAbsolutePath(), temp, safFile, true);
                    }
                }

                File outDir = RecordingStoragePaths.getInternalFaditorOutputDir(
                        this,
                        outputType,
                        true
                );
                if (outDir != null) {
                    File finalFile = createUniqueFile(outDir, fileName);
                    return new OutputTarget(finalFile.getAbsolutePath(), finalFile, null, false);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "resolveOutputTarget failed", e);
        }
        return null;
    }

    private boolean finalizeOutput(@NonNull OutputTarget target) {
        if (!target.requiresSafCopy) {
            Utils.scanFileWithMediaStore(this, target.processingPath);
            return true;
        }

        if (target.safDestination == null) return false;
        ContentResolver resolver = getContentResolver();
        try (InputStream in = new java.io.FileInputStream(target.processingPath);
             OutputStream out = resolver.openOutputStream(target.safDestination.getUri(), "w")) {
            if (out == null) return false;
            byte[] buffer = new byte[16 * 1024];
            int len;
            while ((len = in.read(buffer)) > 0) {
                out.write(buffer, 0, len);
            }
            out.flush();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed final SAF copy", e);
            return false;
        }
    }

    private void cleanupOutputTarget(@Nullable OutputTarget target) {
        if (target == null) return;
        if (target.requiresSafCopy && target.tempFile != null && target.tempFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            target.tempFile.delete();
        }
    }

    @Nullable
    private MediaProfile extractProfile(@NonNull String inputPath) {
        try {
            MediaInformationSession session = FFprobeKit.getMediaInformation(inputPath);
            MediaInformation info = session.getMediaInformation();
            if (info == null || info.getStreams() == null) return null;

            MediaProfile profile = new MediaProfile();
            for (StreamInformation stream : info.getStreams()) {
                if (stream == null) continue;
                String type = stream.getType();
                if ("video".equalsIgnoreCase(type) && !profile.hasVideo) {
                    profile.hasVideo = true;
                    profile.videoCodec = safe(stream.getCodec());
                    profile.videoWidth = safe(stream.getWidth());
                    profile.videoHeight = safe(stream.getHeight());
                    profile.videoFps = safe(stream.getAverageFrameRate());
                } else if ("audio".equalsIgnoreCase(type) && !profile.hasAudio) {
                    profile.hasAudio = true;
                    profile.audioCodec = safe(stream.getCodec());
                    profile.audioChannels = "";
                    profile.audioSampleRate = safe(stream.getSampleRate());
                }
            }
            return profile;
        } catch (Exception e) {
            Log.w(TAG, "Profile extraction failed for: " + inputPath, e);
            return null;
        }
    }

    @Nullable
    private DocumentFile createUniqueSafFile(@NonNull DocumentFile parent, @NonNull String originalName) {
        String base = stripExtension(originalName);
        String ext = extensionWithDot(originalName);
        String candidate = originalName;
        int idx = 1;
        while (parent.findFile(candidate) != null) {
            candidate = base + " (" + idx + ")" + ext;
            idx++;
        }
        return parent.createFile("video/mp4", candidate);
    }

    @NonNull
    private File createUniqueFile(@NonNull File parent, @NonNull String originalName) {
        String base = stripExtension(originalName);
        String ext = extensionWithDot(originalName);
        File candidate = new File(parent, originalName);
        int idx = 1;
        while (candidate.exists()) {
            candidate = new File(parent, base + " (" + idx + ")" + ext);
            idx++;
        }
        return candidate;
    }

    @Nullable
    private String getDisplayName(@NonNull Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return cursor.getString(idx);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @NonNull
    private String stripExtension(@NonNull String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) return fileName.substring(0, dot);
        return fileName;
    }

    @NonNull
    private String extensionWithDot(@NonNull String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot > 0 && dot < fileName.length() - 1) return fileName.substring(dot);
        return ".mp4";
    }

    @NonNull
    private String timestampSuffix() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    private void closeQuietly(@Nullable InputPathHolder holder) {
        if (holder == null || holder.pfd == null) return;
        try {
            holder.pfd.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void onDestroy() {
        if (executor != null) {
            executor.shutdownNow();
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static String safe(@Nullable Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static final class ProcessResult {
        final boolean success;
        final boolean skipped;

        private ProcessResult(boolean success, boolean skipped) {
            this.success = success;
            this.skipped = skipped;
        }

        static ProcessResult success() { return new ProcessResult(true, false); }
        static ProcessResult failed() { return new ProcessResult(false, false); }
    }

    private static final class MergeResult {
        final boolean success;
        final int skippedCount;

        MergeResult(boolean success, int skippedCount) {
            this.success = success;
            this.skippedCount = Math.max(0, skippedCount);
        }
    }

    private static final class InputPathHolder {
        final Uri uri;
        final String path;
        final ParcelFileDescriptor pfd;

        InputPathHolder(Uri uri, String path, ParcelFileDescriptor pfd) {
            this.uri = uri;
            this.path = path;
            this.pfd = pfd;
        }
    }

    private static final class OutputTarget {
        final String processingPath;
        final File tempFile;
        final DocumentFile safDestination;
        final boolean requiresSafCopy;

        OutputTarget(String processingPath, File tempFile, DocumentFile safDestination, boolean requiresSafCopy) {
            this.processingPath = processingPath;
            this.tempFile = tempFile;
            this.safDestination = safDestination;
            this.requiresSafCopy = requiresSafCopy;
        }
    }

    private static final class MediaProfile {
        boolean hasVideo;
        boolean hasAudio;
        String videoCodec = "";
        String videoWidth = "";
        String videoHeight = "";
        String videoFps = "";
        String audioCodec = "";
        String audioChannels = "";
        String audioSampleRate = "";

        boolean isCompatibleWith(@NonNull MediaProfile other) {
            if (!hasVideo || !other.hasVideo) return false;
            if (!videoCodec.equalsIgnoreCase(other.videoCodec)) return false;
            if (!videoWidth.equals(other.videoWidth)) return false;
            if (!videoHeight.equals(other.videoHeight)) return false;
            if (!videoFps.equals(other.videoFps)) return false;

            if (hasAudio != other.hasAudio) return false;
            if (hasAudio) {
                if (!audioCodec.equalsIgnoreCase(other.audioCodec)) return false;
                if (!audioChannels.equals(other.audioChannels)) return false;
                if (!audioSampleRate.equals(other.audioSampleRate)) return false;
            }
            return true;
        }
    }

    private interface MergeProgressListener {
        void onInputsCopied(int copied, int totalInputs);
    }
}
