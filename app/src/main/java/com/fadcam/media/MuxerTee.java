package com.fadcam.media;

import android.media.MediaCodec;
import android.media.MediaFormat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.HashMap;

/**
 * Fans one encoded sample stream into two fMP4 muxers:
 *
 * <ul>
 *   <li><b>file muxer</b> — the existing on-disk recording (unchanged), and</li>
 *   <li><b>stream muxer</b> — an in-memory sink that feeds the live HLS buffer.</li>
 * </ul>
 *
 * <p>This is what lets the live view keep running while recording to disk is
 * paused for motion detection. Every sample always reaches the stream muxer with
 * its real timeline. The file muxer only receives samples while file recording is
 * enabled, and receives them with a compressed timeline so idle periods leave no
 * gap in the resulting single file.
 *
 * <p>The tee is intentionally shaped like {@link FragmentedMp4MuxerWrapper} so the
 * recording pipeline keeps calling the same API. When no stream sink exists, every
 * call is a straight pass-through and behaviour is identical to before.
 */
public final class MuxerTee {

    private static final String TAG = "MuxerTee";

    private final Object lock = new Object();

    @Nullable private FragmentedMp4MuxerWrapper fileMuxer;
    @Nullable private FragmentedMp4MuxerWrapper streamMuxer;

    /** MIME -> track index inside the stream muxer (survives file rollovers). */
    private final HashMap<String, Integer> streamTrackByMime = new HashMap<>();
    /** Current file-muxer track index -> stream-muxer track index. */
    private final android.util.SparseIntArray fileToStream = new android.util.SparseIntArray();
    /** Current file-muxer track index -> MIME, to classify tracks. */
    private final HashMap<Integer, String> fileTrackMime = new HashMap<>();

    private int fileVideoTrack = -1;
    private int fileAudioTrack = -1;

    private boolean fileStarted = false;
    private boolean streamStarted = false;

    // ── File timeline compression ────────────────────────────────────────────
    /** False while recording-to-disk is suspended (motion idle / manual pause). */
    private boolean fileFeedEnabled = true;
    /** Wait for a video keyframe before resuming the file (avoids a broken first frame). */
    private boolean waitFileKeyframe = false;
    /** Wait for a video keyframe before the first stream sample after a late attach. */
    private boolean waitStreamKeyframe = false;
    /** Total idle time removed from the file timeline, in microseconds. */
    private long skippedUs = 0L;
    /** Raw PTS of the last sample actually written to the file. */
    private long lastFileRawPtsUs = -1L;
    /**
     * Compressed PTS of the last sample written to the file, PER TRACK.
     *
     * <p>These must not be shared: the encoder emits audio and video in bursts, so
     * while the video drain writes frame N the "last written PTS" is whatever the
     * audio track wrote a moment ago. Clamping video against that value forced
     * consecutive video samples onto the same timestamp (stts delta = 0), which
     * players render as one frame per burst — the recording looked like it was
     * dropping most of its frames.
     */
    private final android.util.SparseLongArray lastFilePtsByTrack = new android.util.SparseLongArray();
    /** Per-track count of samples that did not advance the file timeline. */
    private final android.util.SparseIntArray nonAdvancingSamplesByTrack = new android.util.SparseIntArray();
    /** First duplicate PTS seen per track, for the diagnostic log. */
    private final android.util.SparseLongArray firstDuplicatePtsByTrack = new android.util.SparseLongArray();
    /** Raw PTS captured when file feed was disabled. */
    private long idleAnchorRawUs = -1L;

    // ── Pre-roll ────────────────────────────────────────────────────────────
    /** Encoded samples kept while the file is idle, so a clip can start earlier. */
    private final ArrayDeque<PreRollSample> preRollSamples = new ArrayDeque<>();
    /** Pre-roll window in microseconds (0 disables buffering). */
    private long preRollWindowUs = 0L;
    /** Bounds the pre-roll buffer regardless of the window (memory safety). */
    private static final long PRE_ROLL_MAX_BYTES = 32L * 1024L * 1024L;
    private long preRollBytes = 0L;

    private static final class PreRollSample {
        final int trackIndex;
        final byte[] data;
        final long ptsUs;
        final int flags;

        PreRollSample(int trackIndex, byte[] data, long ptsUs, int flags) {
            this.trackIndex = trackIndex;
            this.data = data;
            this.ptsUs = ptsUs;
            this.flags = flags;
        }
    }

    /**
     * Sets how many seconds of already-encoded video are kept before a trigger,
     * so a motion clip starts just before the person appears.
     */
    public void setPreRollSeconds(int seconds) {
        synchronized (lock) {
            preRollWindowUs = Math.max(0, seconds) * 1_000_000L;
            if (preRollWindowUs == 0L) {
                clearPreRollBuffer();
            }
        }
    }

    public MuxerTee(@NonNull FragmentedMp4MuxerWrapper fileMuxer) {
        this.fileMuxer = fileMuxer;
    }

    // ── Stream sink lifecycle ────────────────────────────────────────────────

    /**
     * Creates the in-memory stream sink ahead of track registration. Tracks are
     * added through {@link #addTrack(MediaFormat)} just like the file muxer.
     * Used when streaming is already on when the recording session starts.
     */
    public void prepareStreamSink() {
        synchronized (lock) {
            if (streamMuxer != null) {
                return;
            }
            streamMuxer = FragmentedMp4MuxerWrapper.createStreamSink();
            streamStarted = false;
            waitStreamKeyframe = true;
            if (fileMuxer != null) {
                fileMuxer.setFeedsStreaming(false);
            }
            FLog.i(TAG, "[IDLE-STREAM] in-memory stream sink prepared");
        }
    }

    public boolean hasStreamSink() {
        synchronized (lock) {
            return streamMuxer != null;
        }
    }

    /**
     * Creates and starts the stream sink from already-known encoder formats.
     * Used when the remote server is enabled in the middle of a recording
     * session (the normal start path prepared the sink ahead of time instead).
     */
    public boolean ensureStreamSink(@Nullable MediaFormat videoFormat, @Nullable MediaFormat audioFormat) {
        synchronized (lock) {
            if (streamMuxer != null) {
                return true;
            }
            if (videoFormat == null) {
                return false;
            }
            try {
                FragmentedMp4MuxerWrapper sink = FragmentedMp4MuxerWrapper.createStreamSink();
                int streamVideo = sink.addTrack(videoFormat);
                streamTrackByMime.put(mimeOf(videoFormat), streamVideo);
                int streamAudio = -1;
                if (audioFormat != null) {
                    streamAudio = sink.addTrack(audioFormat);
                    streamTrackByMime.put(mimeOf(audioFormat), streamAudio);
                }
                sink.start();
                streamMuxer = sink;
                streamStarted = true;
                waitStreamKeyframe = true;
                if (fileVideoTrack >= 0) {
                    fileToStream.put(fileVideoTrack, streamVideo);
                }
                if (fileAudioTrack >= 0 && streamAudio >= 0) {
                    fileToStream.put(fileAudioTrack, streamAudio);
                }
                if (fileMuxer != null) {
                    fileMuxer.setFeedsStreaming(false);
                }
                FLog.i(TAG, "[IDLE-STREAM] stream sink attached mid-session; tracks v=" +
                        streamVideo + " a=" + streamAudio);
                return true;
            } catch (Exception e) {
                FLog.e(TAG, "[IDLE-STREAM] failed to attach stream sink", e);
                streamMuxer = null;
                streamStarted = false;
                return false;
            }
        }
    }

    // ── Muxer API (mirrors FragmentedMp4MuxerWrapper) ────────────────────────

    public int addTrack(@NonNull MediaFormat format) {
        synchronized (lock) {
            if (fileMuxer == null) {
                throw new IllegalStateException("File muxer is not available");
            }
            int fileIndex = fileMuxer.addTrack(format);
            String mime = mimeOf(format);
            fileTrackMime.put(fileIndex, mime);
            if (isVideo(mime)) {
                fileVideoTrack = fileIndex;
            } else if (isAudio(mime)) {
                fileAudioTrack = fileIndex;
            }

            if (streamMuxer != null) {
                Integer existing = streamTrackByMime.get(mime);
                int streamIndex;
                if (existing != null) {
                    streamIndex = existing;
                } else if (!streamStarted) {
                    streamIndex = streamMuxer.addTrack(format);
                    streamTrackByMime.put(mime, streamIndex);
                } else {
                    // The stream sink already started; a new track cannot be added.
                    FLog.w(TAG, "[IDLE-STREAM] cannot add track after stream started: " + mime);
                    streamIndex = -1;
                }
                if (streamIndex >= 0) {
                    fileToStream.put(fileIndex, streamIndex);
                }
            }
            return fileIndex;
        }
    }

    public void start() {
        synchronized (lock) {
            if (fileMuxer != null && !fileStarted) {
                fileMuxer.start();
                fileStarted = true;
            }
            if (streamMuxer != null && !streamStarted && !streamTrackByMime.isEmpty()) {
                streamMuxer.start();
                streamStarted = true;
            }
        }
    }

    public void writeSampleData(int trackIndex, @NonNull ByteBuffer byteBuf,
                                @NonNull MediaCodec.BufferInfo bufferInfo) {
        synchronized (lock) {
            final boolean keyframe =
                    (bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
            final boolean video = (trackIndex == fileVideoTrack);

            // ── Live stream: always the real timeline ────────────────────────
            FLog.i(TAG, "[TIMELINE] " + timelineHealthReport());
            if (streamMuxer != null && streamStarted) {
                int streamIndex = fileToStream.get(trackIndex, -1);
                if (streamIndex >= 0) {
                    if (waitStreamKeyframe) {
                        if (video && keyframe) {
                            waitStreamKeyframe = false;
                        }
                    }
                    if (!waitStreamKeyframe) {
                        position(byteBuf, bufferInfo);
                        streamMuxer.writeSampleData(streamIndex, byteBuf, bufferInfo);
                    }
                }
            }

            // ── Pre-roll: keep recent encoded samples while the file is idle ──
            if (fileMuxer != null && fileStarted && !fileFeedEnabled
                    && preRollWindowUs > 0L) {
                appendPreRoll(trackIndex, byteBuf, bufferInfo, video,
                        (bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0);
            }

            // ── File: only while recording, with idle time removed ───────────
            if (fileMuxer != null && fileStarted && fileFeedEnabled) {
                boolean write = true;
                if (waitFileKeyframe) {
                    if (video && keyframe) {
                        waitFileKeyframe = false;
                    } else {
                        // Do not start a file on a P/B frame — its references are
                        // in the idle part that was never written.
                        write = false;
                    }
                }
                if (write) {
                    long lastForTrack = lastFilePtsByTrack.get(trackIndex, -1L);
                    long ptsUs = Math.max(0L, bufferInfo.presentationTimeUs - skippedUs);
                    if (lastForTrack >= 0 && ptsUs <= lastForTrack) {
                        // A sample that does not advance its own track's clock becomes
                        // a zero-duration stts entry, which players render as a dropped
                        // frame. Record it so this can never regress silently, then keep
                        // the timeline strictly increasing.
                        nonAdvancingSamplesByTrack.put(trackIndex,
                                nonAdvancingSamplesByTrack.get(trackIndex, 0) + 1);
                        if (firstDuplicatePtsByTrack.get(trackIndex, -1L) < 0L) {
                            firstDuplicatePtsByTrack.put(trackIndex, ptsUs);
                        }
                        ptsUs = lastForTrack + 1L;
                    }
                    MediaCodec.BufferInfo fileInfo = new MediaCodec.BufferInfo();
                    fileInfo.set(bufferInfo.offset, bufferInfo.size, ptsUs, bufferInfo.flags);
                    position(byteBuf, bufferInfo);
                    fileMuxer.writeSampleData(trackIndex, byteBuf, fileInfo);
                    lastFileRawPtsUs = bufferInfo.presentationTimeUs;
                    lastFilePtsByTrack.put(trackIndex, ptsUs);
                }
            }
        }
    }

    /** Diagnostic: samples per track whose timeline had to be nudged forward. */
    private String timelineHealthReport() {
        StringBuilder sb = new StringBuilder();
        int total = 0;
        for (int i = 0; i < nonAdvancingSamplesByTrack.size(); i++) {
            int count = nonAdvancingSamplesByTrack.valueAt(i);
            total += count;
            sb.append(" track").append(nonAdvancingSamplesByTrack.keyAt(i))
              .append('=').append(count)
              .append(" firstDup@").append(firstDuplicatePtsByTrack.get(
                      nonAdvancingSamplesByTrack.keyAt(i), -1L)).append("us");
        }
        if (total > 0) {
            return "NON-ADVANCING samples total=" + total + sb;
        }
        return "OK (every sample advanced its track timeline)";
    }

    public void stop() {
        RuntimeException failure = null;
        synchronized (lock) {
            if (fileMuxer != null && fileStarted) {
                try {
                    fileMuxer.stop();
                    fileStarted = false;
                } catch (RuntimeException e) {
                    // Keep fileStarted=true so the pipeline's emergency retry can
                    // call stop() again, exactly like the single-muxer path.
                    failure = e;
                }
            }
            if (streamMuxer != null && streamStarted) {
                try {
                    streamMuxer.stop();
                } catch (Exception e) {
                    FLog.w(TAG, "[IDLE-STREAM] stream sink stop failed", e);
                } finally {
                    streamStarted = false;
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    public void release() {
        synchronized (lock) {
            if (fileMuxer != null) {
                try {
                    fileMuxer.release();
                } catch (Exception e) {
                    FLog.w(TAG, "file muxer release failed", e);
                }
                fileMuxer = null;
            }
            if (streamMuxer != null) {
                try {
                    streamMuxer.release();
                } catch (Exception e) {
                    FLog.w(TAG, "[IDLE-STREAM] stream muxer release failed", e);
                }
                streamMuxer = null;
            }
            fileStarted = false;
            streamStarted = false;
            clearPreRollBuffer();
            fileToStream.clear();
            fileTrackMime.clear();
            streamTrackByMime.clear();
            fileVideoTrack = -1;
            fileAudioTrack = -1;
        }
    }

    // ── Recording pause / resume (disk side only) ────────────────────────────

    /**
     * Suspends or resumes writes to the recording file. The encoder and the
     * stream sink keep running, so the live view is unaffected.
     *
     * @param rawNowUs current position on the recording clock, used to measure
     *                 the idle gap that must be removed from the file timeline.
     */
    public void setFileRecordingEnabled(boolean enabled, long rawNowUs) {
        synchronized (lock) {
            if (enabled == fileFeedEnabled) {
                return;
            }
            if (!enabled) {
                fileFeedEnabled = false;
                idleAnchorRawUs = lastFileRawPtsUs >= 0 ? lastFileRawPtsUs : rawNowUs;
                FLog.i(TAG, "[IDLE-STREAM] file recording suspended (anchor=" +
                        (idleAnchorRawUs / 1000L) + "ms)");
            } else {
                long flushed = flushPreRollToFile();
                if (idleAnchorRawUs >= 0) {
                    long gapUs = Math.max(0L, rawNowUs - idleAnchorRawUs);
                    skippedUs += gapUs;
                    FLog.i(TAG, "[IDLE-STREAM] file recording resumed; idle gap=" +
                            (gapUs / 1000L) + "ms, total skipped=" + (skippedUs / 1000L)
                            + "ms, preRollSamples=" + flushed);
                }
                idleAnchorRawUs = -1L;
                fileFeedEnabled = true;
                // A flushed pre-roll already begins on a keyframe.
                waitFileKeyframe = flushed == 0L;
            }
        }
    }

    public boolean isFileRecordingEnabled() {
        synchronized (lock) {
            return fileFeedEnabled;
        }
    }

    /**
     * True while samples are being fed to the file. Used by the pipeline's
     * byte counter so a long idle period cannot trigger a size rollover.
     */
    public boolean isFileFeedActive() {
        synchronized (lock) {
            return fileFeedEnabled && fileMuxer != null && fileStarted;
        }
    }

    /** Number of media fragments actually written to the current file. */
    public int getFileFragmentCount() {
        synchronized (lock) {
            return fileMuxer != null ? fileMuxer.getFragmentCount() : 0;
        }
    }

    // ── File rollover support (keep the stream sink alive) ───────────────────

    /**
     * Finalizes and releases only the file muxer. The stream sink keeps running,
     * so a size rollover never interrupts the live view. Call {@link #replaceFileMuxer}
     * before feeding new samples.
     */
    public void stopFileSinkForSwap() {
        synchronized (lock) {
            if (fileMuxer != null) {
                try {
                    if (fileStarted) {
                        fileMuxer.stop();
                    }
                } catch (Exception e) {
                    FLog.w(TAG, "rollover: file muxer stop failed", e);
                } finally {
                    try {
                        fileMuxer.release();
                    } catch (Exception e) {
                        FLog.w(TAG, "rollover: file muxer release failed", e);
                    }
                }
            }
            fileMuxer = null;
            fileStarted = false;
            fileToStream.clear();
            fileTrackMime.clear();
            fileVideoTrack = -1;
            fileAudioTrack = -1;
        }
    }

    public void replaceFileMuxer(@NonNull FragmentedMp4MuxerWrapper next) {
        synchronized (lock) {
            if (fileMuxer != null) {
                FLog.w(TAG, "replaceFileMuxer: previous file muxer was not stopped");
                try {
                    fileMuxer.release();
                } catch (Exception ignored) {
                }
            }
            fileMuxer = next;
            fileStarted = false;
            fileToStream.clear();
            fileTrackMime.clear();
            fileVideoTrack = -1;
            fileAudioTrack = -1;
        }
    }

    // ── Pre-roll helpers ────────────────────────────────────────────────────

    private void appendPreRoll(int trackIndex, ByteBuffer buffer, MediaCodec.BufferInfo info,
                               boolean video, boolean keyframe) {
        int size = info.size;
        if (size <= 0 || preRollBytes + size > PRE_ROLL_MAX_BYTES) {
            return;
        }
        byte[] copy = new byte[size];
        int oldPos = buffer.position();
        int oldLimit = buffer.limit();
        try {
            buffer.position(info.offset);
            buffer.limit(info.offset + size);
            buffer.get(copy);
        } finally {
            buffer.position(oldPos);
            buffer.limit(oldLimit);
        }
        preRollSamples.addLast(new PreRollSample(trackIndex, copy,
                info.presentationTimeUs, info.flags));
        preRollBytes += size;

        long newestUs = info.presentationTimeUs;
        // Drop samples older than the window. Never cut inside a GOP: keep the
        // newest video keyframe as the earliest writable boundary.
        while (!preRollSamples.isEmpty()) {
            PreRollSample first = preRollSamples.peekFirst();
            if (first == null || newestUs - first.ptsUs <= preRollWindowUs) {
                break;
            }
            PreRollSample removed = preRollSamples.pollFirst();
            if (removed != null) {
                preRollBytes -= removed.data.length;
            }
        }
        // Move the start forward to the first video keyframe in the window.
        boolean sawKeyframe = false;
        java.util.Iterator<PreRollSample> it = preRollSamples.iterator();
        while (it.hasNext()) {
            PreRollSample sample = it.next();
            if (sample.trackIndex == fileVideoTrack
                    && (sample.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                sawKeyframe = true;
                break;
            }
        }
        if (!sawKeyframe && video && keyframe) {
            return;
        }
    }

    /**
     * Writes the buffered pre-trigger samples into the file before the resumed
     * live samples. Timestamps continue from the last written sample, so the file
     * stays contiguous and the pre-roll plays before the trigger.
     */
    private long flushPreRollToFile() {
        if (preRollSamples.isEmpty() || fileMuxer == null || !fileStarted) {
            clearPreRollBuffer();
            return 0L;
        }
        // Find the first video keyframe in the buffer; everything before it is
        // undecodable and must not be written.
        PreRollSample start = null;
        for (PreRollSample sample : preRollSamples) {
            if (sample.trackIndex == fileVideoTrack
                    && (sample.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                start = sample;
                break;
            }
        }
        if (start == null) {
            clearPreRollBuffer();
            return 0L;
        }
        long basePtsUs = start.ptsUs;
        // Per-track anchors: pre-roll samples carry their own track index and the
        // tracks have independent timelines, so a single shared anchor is wrong.
        android.util.SparseLongArray anchors = new android.util.SparseLongArray();
        long written = 0L;
        boolean started = false;
        for (PreRollSample sample : preRollSamples) {
            if (!started) {
                if (sample != start) {
                    continue;
                }
                started = true;
            }
            long anchor = anchors.get(sample.trackIndex, -1L);
            if (anchor < 0L) {
                // First sample of this track in the flush: continue from whatever
                // that track last wrote, else start at its own timestamp.
                long previous = lastFilePtsByTrack.get(sample.trackIndex, -1L);
                anchor = previous >= 0 ? previous : basePtsUs;
            }
            long ptsUs = anchor + Math.max(0L, sample.ptsUs - basePtsUs);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            info.set(0, sample.data.length, ptsUs, sample.flags);
            ByteBuffer buf = ByteBuffer.wrap(sample.data);
            try {
                fileMuxer.writeSampleData(sample.trackIndex, buf, info);
                lastFilePtsByTrack.put(sample.trackIndex, ptsUs);
                anchors.put(sample.trackIndex, ptsUs);
                written++;
            } catch (Exception e) {
                FLog.w(TAG, "pre-roll write failed; stopping flush", e);
                break;
            }
        }
        // The compressed timeline must not re-add the flushed window.
        if (lastFileRawPtsUs >= 0 && preRollSamples.peekLast() != null) {
            lastFileRawPtsUs = Math.max(lastFileRawPtsUs, preRollSamples.peekLast().ptsUs);
        }
        clearPreRollBuffer();
        return written;
    }

    private void clearPreRollBuffer() {
        preRollSamples.clear();
        preRollBytes = 0L;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String mimeOf(MediaFormat format) {
        String mime = format.getString(MediaFormat.KEY_MIME);
        return mime != null ? mime : "";
    }

    private static boolean isVideo(String mime) {
        return mime != null && mime.startsWith("video/");
    }

    private static boolean isAudio(String mime) {
        return mime != null && mime.startsWith("audio/");
    }

    private static void position(ByteBuffer buffer, MediaCodec.BufferInfo info) {
        buffer.position(info.offset);
        buffer.limit(info.offset + info.size);
    }
}
