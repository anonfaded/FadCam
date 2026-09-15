package com.fadcam.service;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;
import com.fadcam.Constants;
import com.fadcam.FLog;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Full-only ffmpeg batch operations (export-to-standard + merge). Moved verbatim
 * from BatchMediaActionService; orchestration/notifications stay in the service.
 */
public final class BatchFfmpegOpsImpl implements BatchFfmpegOps {

    private static final String TAG = "BatchFfmpegOpsImpl";

    @Override
    public BatchMediaActionService.ProcessResult exportStandardMp4(BatchMediaActionService svc,
            @NonNull Uri inputUri, int index, @NonNull BatchMediaActionTask task) {
        BatchMediaActionService.InputPathHolder input = null;
        BatchMediaActionService.OutputTarget outputTarget = null;
        try {
            input = svc.resolveInputPath(inputUri);
            if (input == null) return BatchMediaActionService.ProcessResult.failed();

            String displayName = svc.getDisplayName(inputUri);
            String baseName = svc.stripExtension(displayName == null ? ("video_" + index + ".mp4") : displayName);
            String outputName = Constants.RECORDING_FILE_PREFIX_FADITOR_STANDARD
                    + baseName
                    + "_"
                    + svc.timestampSuffix()
                    + ".mp4";

            outputTarget = svc.resolveOutputTarget(task, outputName);
            if (outputTarget == null) return BatchMediaActionService.ProcessResult.failed();

            String ffmpegCmd = String.format(
                    Locale.US,
                    "-i \"%s\" -c copy -movflags +faststart -y \"%s\"",
                    input.path,
                    outputTarget.processingPath
            );
            FFmpegSession session = FFmpegKit.execute(ffmpegCmd);
            if (!ReturnCode.isSuccess(session.getReturnCode())) {
                FLog.w(TAG, "Export FFmpeg failed: " + session.getOutput());
                return BatchMediaActionService.ProcessResult.failed();
            }

            if (!svc.finalizeOutput(outputTarget)) return BatchMediaActionService.ProcessResult.failed();
            return BatchMediaActionService.ProcessResult.success();
        } catch (Exception e) {
            FLog.e(TAG, "Export failed for uri: " + inputUri, e);
            return BatchMediaActionService.ProcessResult.failed();
        } finally {
            svc.closeQuietly(input);
            svc.cleanupOutputTarget(outputTarget);
        }
    }

    @NonNull
    @Override
    public BatchMediaActionService.MergeResult mergeVideos(BatchMediaActionService svc,
            @NonNull List<Uri> inputUris,
            @NonNull BatchMediaActionTask task,
            @Nullable BatchMediaActionService.MergeProgressListener listener
    ) {
        if (inputUris.size() < 2) {
            return new BatchMediaActionService.MergeResult(false, inputUris.size());
        }

        ArrayList<File> tempInputs = new ArrayList<>();
        File concatFile = null;
        BatchMediaActionService.OutputTarget outputTarget = null;
        try {
            int skippedCount = 0;
            ArrayList<String> mergePaths = new ArrayList<>();
            int sourceIndex = 0;
            for (Uri uri : inputUris) {
                sourceIndex++;
                File tmp = svc.copyUriToTempMergeFile(uri, sourceIndex);
                if (tmp == null) {
                    skippedCount++;
                    continue;
                }
                tempInputs.add(tmp);
                mergePaths.add(tmp.getAbsolutePath());
                if (listener != null) listener.onInputsCopied(mergePaths.size(), inputUris.size());
            }

            if (mergePaths.size() < 2) {
                return new BatchMediaActionService.MergeResult(false, skippedCount);
            }

            String outputName = Constants.RECORDING_FILE_PREFIX_FADITOR_MERGE
                    + svc.timestampSuffix()
                    + ".mp4";
            outputTarget = svc.resolveOutputTarget(task, outputName);
            if (outputTarget == null) return new BatchMediaActionService.MergeResult(false, skippedCount);

            concatFile = new File(svc.getCacheDir(), "faditor_batch_concat_" + System.currentTimeMillis() + ".txt");
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
                FLog.w(TAG, "Merge copy mode failed, trying safe re-encode fallback");
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
                    FLog.w(TAG, "Merge fallback failed: " + fallbackSession.getOutput());
                    return new BatchMediaActionService.MergeResult(false, skippedCount);
                }
            }

            if (!svc.finalizeOutput(outputTarget)) {
                return new BatchMediaActionService.MergeResult(false, skippedCount);
            }
            return new BatchMediaActionService.MergeResult(true, skippedCount);
        } catch (Exception e) {
            FLog.e(TAG, "Merge failed", e);
            return new BatchMediaActionService.MergeResult(false, inputUris.size());
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
            svc.cleanupOutputTarget(outputTarget);
        }
    }

}
