package com.fadcam.service;

import android.net.Uri;

import java.util.List;

/** Lite no-op: no ffmpeg available, all batch ffmpeg operations fail cleanly. */
public final class BatchFfmpegOpsDefault implements BatchFfmpegOps {

    @Override
    public BatchMediaActionService.ProcessResult exportStandardMp4(
            BatchMediaActionService svc, Uri inputUri, int index, BatchMediaActionTask task) {
        return BatchMediaActionService.ProcessResult.failed();
    }

    @Override
    public BatchMediaActionService.MergeResult mergeVideos(
            BatchMediaActionService svc, List<Uri> inputUris, BatchMediaActionTask task,
            BatchMediaActionService.MergeProgressListener listener) {
        return new BatchMediaActionService.MergeResult(false, inputUris.size());
    }
}
