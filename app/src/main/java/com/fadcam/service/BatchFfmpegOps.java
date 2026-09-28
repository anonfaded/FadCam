package com.fadcam.service;

import android.net.Uri;

import java.util.List;

/**
 * ffmpeg-backed batch media operations (export-to-standard + merge). Full builds
 * provide BatchFfmpegOpsImpl (src/full); Lite uses BatchFfmpegOpsDefault, which
 * reports failure so callers degrade without touching ffmpeg code.
 */
public interface BatchFfmpegOps {

    BatchMediaActionService.ProcessResult exportStandardMp4(
            BatchMediaActionService svc, Uri inputUri, int index, BatchMediaActionTask task);

    BatchMediaActionService.MergeResult mergeVideos(
            BatchMediaActionService svc, List<Uri> inputUris, BatchMediaActionTask task,
            BatchMediaActionService.MergeProgressListener listener);
}
