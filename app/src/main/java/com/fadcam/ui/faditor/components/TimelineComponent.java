package com.fadcam.ui.faditor.components;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import com.fadcam.R;
import com.fadcam.ui.faditor.models.TimelineState;
import com.fadcam.ui.faditor.persistence.AutoSaveManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Professional timeline component with zoom, scrubbing, frame-accurate editing, and waveform visualization.
 * Implements requirements 11.3 and 11.7 for professional video editing interface.
 */
public class TimelineComponent extends View {

    private static final String TAG = "TimelineComponent";

    // Constants for professional CapCut-style timeline
    private static final float MIN_ZOOM = 0.1f;
    private static final float MAX_ZOOM = 50.0f;
    private static final int VIDEO_TRACK_HEIGHT_DP = 60;
    private static final int AUDIO_TRACK_HEIGHT_DP = 50;
    private static final int TRACK_MARGIN_DP = 8;
    private static final int THUMBNAIL_WIDTH_DP = 80;
    private static final int CENTER_PLAYHEAD_WIDTH_DP = 2;
    private static final int WAVEFORM_SAMPLE_COUNT = 200;
    private static final float FRAME_RATE = 30.0f;

    // Legacy constants for compatibility
    private static final int TIMELINE_HEIGHT_DP = 200;
    private static final int TRACK_HEIGHT_DP = 60;
    private static final int WAVEFORM_HEIGHT_DP = 50;
    private static final int HANDLE_WIDTH_DP = 12;
    private static final int FRAME_SNAP_THRESHOLD_DP = 8;
    private static final int ZOOM_CONTROL_SIZE_DP = 40;

    // Professional colors matching CapCut design - fixed visibility
    private static final int COLOR_TIMELINE_BG = 0xFF1E1E1E;
    private static final int COLOR_VIDEO_TRACK = 0xFF3A3A3A;
    private static final int COLOR_AUDIO_TRACK = 0xFF1A4A1A;
    private static final int COLOR_CENTER_PLAYHEAD = 0xFFFFFFFF;
    private static final int COLOR_WAVEFORM = 0xFF00E676;
    private static final int COLOR_VIDEO_FRAME_BORDER = 0xFF666666;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_TRACK_LABEL = 0xFFBBBBBB;
    private static final int COLOR_ADD_AUDIO_TEXT = 0xFF888888;
    private static final int COLOR_RULER_BG = 0xFF2A2A2A;
    private static final int COLOR_RULER_TICK = 0xFFFFFFFF;
    private static final int COLOR_RULER_MINOR_TICK = 0xFF999999;

    // Debouncing constants
    private static final int SEEK_DEBOUNCE_DELAY_MS = 50;
    private static final int SCRUB_UPDATE_INTERVAL_MS = 16;

    public interface TimelineListener {
        void onTrimRangeChanged(long startMs, long endMs);
        void onTimelinePositionChanged(long positionMs);
        void onTimelineStateChanged();
        void onScrubbing(boolean isScrubbing, long positionMs);
        void onZoomChanged(float zoomLevel);
        void onFrameSnapped(long framePositionMs);
    }

    // Core timeline data
    private TimelineListener listener;
    private AutoSaveManager autoSaveManager;
    private long videoDuration;
    private long trimStart;
    private long trimEnd;
    private long currentPosition;
    private float zoomLevel = 1.0f;
    private TimelineState state;
    private Uri videoUri;
    private float frameRate = FRAME_RATE;

    // Multi-track support
    private List<Track> tracks;
    // Professional timeline tracks
    private VideoTrack videoTrack;
    private AudioTrack audioTrack;
    private boolean hasVideo = false;
    private boolean hasAudio = false;

    // Viewport and scrolling
    private long viewportStart = 0;
    private long viewportEnd = 10000;
    private float scrollX = 0;

    // Touch and interaction
    private boolean isDragging = false;
    private boolean isDraggingStartHandle = false;
    private boolean isDraggingEndHandle = false;
    private boolean isDraggingPlayhead = false;
    private boolean frameSnappingEnabled = true;
    private float lastTouchX = 0;
    private float lastTouchY = 0;

    // Legacy compatibility
    private int selectedTrackIndex = 0;

    // Video thumbnails
    private Map<Long, Bitmap> videoThumbnails = new HashMap<>();
    private ExecutorService thumbnailExecutor;
    private boolean isLoadingThumbnails = false;

    // Audio waveform
    private float[] waveformData;
    private boolean isLoadingWaveform = false;
    private ExecutorService waveformExecutor;

    // Drawing objects
    private Paint timelinePaint;
    private Paint trackPaint;
    private Paint trimPaint;
    private Paint handlePaint;
    private Paint playheadPaint;
    private Paint waveformPaint;
    private Paint framePaint;
    private Paint textPaint;
    private Paint zoomControlPaint;
    private Rect tempRect;
    private RectF tempRectF;
    private Path tempPath;

    // Gesture detectors
    private GestureDetector gestureDetector;
    private ScaleGestureDetector scaleGestureDetector;

    // Animation
    private ValueAnimator scrubAnimator;
    private Handler mainHandler;

    // -------------- Fix Start (debouncing fields) --------------
    // Debouncing for smooth scrubbing
    private Handler seekDebounceHandler;
    private Runnable pendingSeekRunnable;

    // Professional scrubbing system
    private ExecutorService seekExecutor;
    private Future<?> currentSeekTask;
    private AtomicBoolean isActivelyScrubbing = new AtomicBoolean(false);
    private AtomicBoolean isScrubbing = new AtomicBoolean(false);
    private AtomicLong lastScrubbingPosition = new AtomicLong(0);
    private Handler scrubbingHandler;
    private long scrubStartTime;
    private long pendingSeekPosition = -1;
    private boolean isDebouncing = false;

    // Ultra-smooth scrubbing with prediction and interpolation
    private long[] recentPositions = new long[5]; // Track recent positions for prediction
    private long[] recentTimestamps = new long[5]; // Track timing for velocity calculation
    private int positionIndex = 0;
    private long predictedPosition = -1;
    private float scrubVelocity = 0.0f;
    private boolean useFrameInterpolation = true;

    // Feedback loop prevention
    private boolean isSilentUpdate = false;

    // -------------- Fix Ended (debouncing fields) --------------

    public TimelineComponent(Context context) {
        super(context);
        init();
    }

    public TimelineComponent(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public TimelineComponent(
        Context context,
        @Nullable AttributeSet attrs,
        int defStyleAttr
    ) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        // Initialize state
        state = new TimelineState();
        tracks = new ArrayList<>();
        tracks.add(new Track("Video Track", Track.Type.VIDEO));
        tracks.add(new Track("Audio Track", Track.Type.AUDIO));

        // Initialize professional tracks
        videoTrack = new VideoTrack();
        audioTrack = new AudioTrack();
        videoThumbnails = new HashMap<>();

        // Initialize executors and handlers
        waveformExecutor = Executors.newSingleThreadExecutor();
        thumbnailExecutor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());

        // -------------- Fix Start (initialize debounce handler) --------------
        seekDebounceHandler = new Handler(Looper.getMainLooper());
        pendingSeekRunnable = new Runnable() {
            @Override
            public void run() {
                if (pendingSeekPosition >= 0 && listener != null) {
                    long position = pendingSeekPosition;
                    pendingSeekPosition = -1;
                    isDebouncing = false;

                    // Notify listener of final position
                    listener.onTimelinePositionChanged(position);
                    listener.onScrubbing(false, position);

                    Log.d(TAG, "Debounced seek to: " + position + "ms");
                }
            }
        };
        // -------------- Fix Ended (initialize debounce handler) --------------

        // -------------- Fix Start (initialize professional scrubbing) --------------
        // Initialize professional scrubbing system
        seekExecutor = Executors.newSingleThreadExecutor();
        scrubbingHandler = new Handler(Looper.getMainLooper());
        // -------------- Fix Ended (initialize professional scrubbing) --------------

        // Initialize paint objects
        initializePaints();

        // Initialize gesture detectors
        initializeGestureDetectors();

        // Initialize drawing objects
        tempRect = new Rect();
        tempRectF = new RectF();
        tempPath = new Path();

        // Set minimum height
        setMinimumHeight(dpToPx(TIMELINE_HEIGHT_DP));
    }

    private void initializePaints() {
        timelinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        timelinePaint.setColor(COLOR_TIMELINE_BG);

        trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        trackPaint.setColor(COLOR_VIDEO_TRACK);

        trimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        trimPaint.setColor(COLOR_WAVEFORM);
        trimPaint.setAlpha(128);

        handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        handlePaint.setColor(COLOR_WAVEFORM);

        playheadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        playheadPaint.setColor(COLOR_CENTER_PLAYHEAD);
        playheadPaint.setStrokeWidth(dpToPx(CENTER_PLAYHEAD_WIDTH_DP));

        waveformPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        waveformPaint.setColor(COLOR_WAVEFORM);
        waveformPaint.setStrokeWidth(2f);

        framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        framePaint.setColor(COLOR_VIDEO_FRAME_BORDER);
        framePaint.setStrokeWidth(1f);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(COLOR_TEXT);
        textPaint.setTextSize(dpToPx(12));
        textPaint.setTextAlign(Paint.Align.CENTER);

        zoomControlPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        zoomControlPaint.setColor(COLOR_TRACK_LABEL);
    }

    private void initializeGestureDetectors() {
        gestureDetector = new GestureDetector(
            getContext(),
            new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onScroll(
                    MotionEvent e1,
                    MotionEvent e2,
                    float distanceX,
                    float distanceY
                ) {
                    if (!isDragging) {
                        float oldScrollX = scrollX;
                        // Scroll timeline horizontally
                        scrollX += distanceX;

                        // Constrain scroll limits
                        float maxScrollX = Math.max(
                            0,
                            videoDuration * getPixelsPerMs() - getWidth()
                        );
                        scrollX = Math.max(0, Math.min(scrollX, maxScrollX));

                        // Provide visual feedback about scroll position
                        if (scrollX != oldScrollX && listener != null) {
                            float centerX = getWidth() / 2f;
                            float pixelsPerMs = getPixelsPerMs();
                            long centerTimePosition = Math.round(
                                (scrollX + centerX) / pixelsPerMs
                            );
                            centerTimePosition = Math.max(
                                0,
                                Math.min(videoDuration, centerTimePosition)
                            );

                            // Update center line position display
                            listener.onFrameSnapped(centerTimePosition);
                        }

                        invalidate();
                        return true;
                    }
                    return false;
                }

                @Override
                public boolean onSingleTapUp(MotionEvent e) {
                    // Convert touch position to time, accounting for center playhead
                    float centerX = getWidth() / 2f;
                    float touchOffset = e.getX() - centerX;
                    long newPosition = Math.round(
                        (scrollX + centerX + touchOffset) / getPixelsPerMs()
                    );

                    // Clamp to valid range
                    newPosition = Math.max(
                        0,
                        Math.min(videoDuration, newPosition)
                    );

                    if (frameSnappingEnabled) {
                        newPosition = snapToFrame(newPosition);
                    }

                    // Update playback position and immediately sync timeline
                    setCurrentPosition(newPosition);

                    // Force update scroll position to keep centered
                    updateScrollForCenterPlayhead();

                    if (listener != null) {
                        listener.onTimelinePositionChanged(newPosition);
                    }

                    return true;
                }

                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    return handleDoubleTap(e.getX(), e.getY());
                }
            }
        );

        scaleGestureDetector = new ScaleGestureDetector(
            getContext(),
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector detector) {
                    float scaleFactor = detector.getScaleFactor();
                    setZoomLevel(zoomLevel * scaleFactor);
                    return true;
                }
            }
        );
    }

    public void setTimelineListener(TimelineListener listener) {
        this.listener = listener;
    }

    public void setAutoSaveManager(AutoSaveManager autoSaveManager) {
        this.autoSaveManager = autoSaveManager;
    }

    public void setVideoUri(Uri videoUri) {
        this.videoUri = videoUri;
        this.hasVideo = (videoUri != null);
        Log.d(TAG, "Set video URI, hasVideo: " + hasVideo);
        loadWaveformData();
        // Clear old thumbnails when new video is set
        if (videoThumbnails != null) {
            for (Bitmap bitmap : videoThumbnails.values()) {
                if (bitmap != null && !bitmap.isRecycled()) {
                    bitmap.recycle();
                }
            }
            videoThumbnails.clear();
        }
        invalidate();
    }

    public void setVideoDuration(long duration) {
        this.videoDuration = duration;
        this.trimEnd = duration; // Default to full video
        this.viewportEnd = duration;
        updateViewport();
        invalidate();
    }

    public void setTrimRange(long start, long end) {
        this.trimStart = Math.max(0, start);
        this.trimEnd = Math.min(videoDuration, end);

        // Trigger auto-save
        if (autoSaveManager != null) {
            autoSaveManager.onTimelineChanged();
        }

        invalidate();

        if (listener != null) {
            listener.onTrimRangeChanged(this.trimStart, this.trimEnd);
        }
    }

    // -------------- Fix Start (debounced seek methods) --------------
    /**
     * Schedule a debounced seek operation for smooth scrubbing
     */
    private void scheduleDebounceSeek(long position) {
        pendingSeekPosition = position;

        if (!isDebouncing) {
            isDebouncing = true;
            // Immediate feedback for scrubbing
            if (listener != null) {
                listener.onScrubbing(true, position);
            }
        }

        // Cancel any pending seek and schedule new one
        seekDebounceHandler.removeCallbacks(pendingSeekRunnable);
        seekDebounceHandler.postDelayed(
            pendingSeekRunnable,
            SEEK_DEBOUNCE_DELAY_MS
        );
    }

    /**
     * Cancel any pending debounced seek operations
     */
    private void cancelPendingSeek() {
        seekDebounceHandler.removeCallbacks(pendingSeekRunnable);
        pendingSeekPosition = -1;
        isDebouncing = false;
    }

    // -------------- Fix Ended (debounced seek methods) --------------

    public void setCurrentPosition(long position) {
        long newPosition = Math.max(0, Math.min(videoDuration, position));

        if (frameSnappingEnabled && !isActivelyScrubbing.get()) {
            // Disable frame snapping during active scrubbing for smoother motion
            newPosition = snapToFrame(newPosition);
        }

        // -------------- Fix Start (position validation to prevent jumps) --------------
        // Prevent unexpected position jumps unless explicitly allowed
        if (!isSilentUpdate && !isActivelyScrubbing.get()) {
            long positionDiff = Math.abs(newPosition - this.currentPosition);
            // Prevent jumps > 200ms unless it's a user-initiated seek or project loading
            if (positionDiff > 200 && this.currentPosition > 0) {
                // This might be a delayed/stale position update - ignore it
                return;
            }
        }
        // -------------- Fix Ended (position validation to prevent jumps) --------------

        // Only update if position actually changed (prevent feedback loops)
        if (this.currentPosition != newPosition) {
            this.currentPosition = newPosition;

            // Update scroll to keep playhead centered (CapCut style)
            updateScrollForCenterPlayhead();

            // Only update viewport if not actively scrubbing (prevent cascading updates)
            if (!isActivelyScrubbing.get()) {
                ensurePlayheadVisible();
            }

            invalidate();

            // -------------- Fix Start (professional position handling with progress sync) --------------
            if (listener != null && !isSilentUpdate) {
                // Always update timeline position to sync progress bar
                listener.onTimelinePositionChanged(this.currentPosition);

                // If we're actively scrubbing, notify scrubbing state
                if (isActivelyScrubbing.get()) {
                    listener.onScrubbing(true, this.currentPosition);
                }
            }
            // -------------- Fix Ended (professional position handling with progress sync) --------------
        }
    }

    /**
     * Set position silently without triggering listener callbacks
     * Used for video player position updates to prevent feedback loops
     */
    public void setCurrentPositionSilent(long position) {
        isSilentUpdate = true;
        try {
            setCurrentPosition(position);
        } finally {
            isSilentUpdate = false;
        }
    }

    /**
     * Set position for user-initiated seeks (allows large position changes)
     */
    public void setCurrentPositionUserSeek(long position) {
        // Temporarily disable validation for user-initiated seeks
        boolean wasActiveScrubbing = isActivelyScrubbing.get();
        isActivelyScrubbing.set(true);
        try {
            setCurrentPosition(position);
        } finally {
            isActivelyScrubbing.set(wasActiveScrubbing);
        }
    }

    public void setZoomLevel(float zoom) {
        float newZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom));

        if (Math.abs(newZoom - this.zoomLevel) > 0.01f) {
            this.zoomLevel = newZoom;
            updateViewport();

            // Update state
            if (state != null) {
                state.setZoomLevel(this.zoomLevel);
            }

            // Trigger auto-save
            if (autoSaveManager != null) {
                autoSaveManager.onTimelineChanged();
            }

            invalidate();

            if (listener != null) {
                listener.onZoomChanged(this.zoomLevel);
                listener.onTimelineStateChanged();
            }
        }
    }

    public void enableFrameSnapping(boolean enabled) {
        this.frameSnappingEnabled = enabled;

        if (state != null) {
            state.setFrameSnappingEnabled(enabled);
        }

        // Trigger auto-save
        if (autoSaveManager != null) {
            autoSaveManager.onTimelineChanged();
        }

        // If enabling and playhead is not on a frame, snap it
        if (enabled && currentPosition > 0) {
            long snappedPosition = snapToFrame(currentPosition);
            if (snappedPosition != currentPosition) {
                setCurrentPosition(snappedPosition);

                if (listener != null) {
                    listener.onFrameSnapped(snappedPosition);
                }
            }
        }
    }

    public void setFrameRate(float frameRate) {
        this.frameRate = Math.max(1.0f, Math.min(120.0f, frameRate));
        invalidate();
    }

    public void zoomIn() {
        setZoomLevel(zoomLevel * 1.5f);
    }

    public void zoomOut() {
        setZoomLevel(zoomLevel / 1.5f);
    }

    public void zoomToFit() {
        if (videoDuration > 0) {
            float availableWidth =
                getWidth() - getPaddingLeft() - getPaddingRight();
            float optimalZoom =
                (availableWidth / (float) videoDuration) * 1000f; // Convert to pixels per second
            setZoomLevel(Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, optimalZoom)));
        }
    }

    public void addTrack(Track track) {
        tracks.add(track);
        invalidate();
    }

    public void removeTrack(int index) {
        if (index >= 0 && index < tracks.size() && tracks.size() > 1) {
            tracks.remove(index);
            if (selectedTrackIndex >= tracks.size()) {
                selectedTrackIndex = tracks.size() - 1;
            }
            invalidate();
        }
    }

    public void selectTrack(int index) {
        if (index >= 0 && index < tracks.size()) {
            selectedTrackIndex = index;
            invalidate();
        }
    }

    public TimelineState getState() {
        if (state == null) {
            state = new TimelineState();
        }

        state.setZoomLevel(zoomLevel);
        state.setPlayheadPosition(currentPosition);
        state.setViewportStart(viewportStart);
        state.setViewportEnd(viewportEnd);
        state.setFrameSnappingEnabled(frameSnappingEnabled);

        return state;
    }

    public void restoreState(TimelineState state) {
        if (state == null) return;

        this.state = state;
        this.zoomLevel = state.getZoomLevel();
        this.currentPosition = state.getPlayheadPosition();
        this.viewportStart = state.getViewportStart();
        this.viewportEnd = state.getViewportEnd();
        this.frameSnappingEnabled = state.isFrameSnappingEnabled();

        updateViewport();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (videoDuration <= 0) {
            drawEmptyState(canvas);
            return;
        }

        // Draw timeline background
        canvas.drawRect(0, 0, getWidth(), getHeight(), timelinePaint);

        // Save canvas state for scrolling
        canvas.save();
        canvas.translate(-scrollX, 0);

        // Draw timeline ruler first (background)
        drawTimelineRuler(canvas);

        // Draw professional tracks
        drawVideoTrack(canvas);
        drawAudioTrack(canvas);

        // Restore canvas for fixed elements
        canvas.restore();

        // Draw fixed center playhead line (CapCut style) - always in center
        drawCenterPlayhead(canvas);
    }

    private void drawEmptyState(Canvas canvas) {
        canvas.drawRect(0, 0, getWidth(), getHeight(), timelinePaint);

        String text = "Import a video to start editing";
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(dpToPx(14));
        float textX = getWidth() / 2f;
        float textY = getHeight() / 2f;
        canvas.drawText(text, textX, textY, textPaint);
    }

    /**
     * Draw professional timeline ruler with time markers (CapCut style)
     */
    private void drawTimelineRuler(Canvas canvas) {
        if (videoDuration <= 0) return;

        int rulerHeight = dpToPx(25);
        int viewWidth = getWidth();

        // Draw ruler background only for visible area
        trackPaint.setColor(COLOR_RULER_BG);
        canvas.drawRect(0, 0, viewWidth, rulerHeight, trackPaint);

        // Draw time markers only for visible area
        float pixelsPerMs = getPixelsPerMs();
        long startTime = Math.max(0, (long) (scrollX / pixelsPerMs));
        long endTime = Math.min(
            videoDuration,
            (long) ((scrollX + viewWidth) / pixelsPerMs)
        );

        textPaint.setColor(COLOR_TEXT);
        textPaint.setTextSize(dpToPx(9));
        textPaint.setTextAlign(Paint.Align.CENTER);

        // Draw second markers
        long startSecond = startTime / 1000;
        long endSecond = (endTime / 1000) + 1;

        framePaint.setStrokeWidth(1f);

        for (long second = startSecond; second <= endSecond; second++) {
            long timeMs = second * 1000;
            float x = timeToPixel(timeMs);

            // Skip if outside visible area
            if (x < 0 || x > viewWidth) continue;

            boolean isMajorTick = (second % 5 == 0); // Every 5 seconds is major

            if (isMajorTick) {
                // Major tick - full height with time text
                framePaint.setColor(COLOR_RULER_TICK);
                canvas.drawLine(x, 0, x, rulerHeight * 0.7f, framePaint);

                // Draw time text
                String timeText = formatTime(timeMs);
                canvas.drawText(
                    timeText,
                    x,
                    rulerHeight - dpToPx(3),
                    textPaint
                );
            } else {
                // Minor tick - half height
                framePaint.setColor(COLOR_RULER_MINOR_TICK);
                canvas.drawLine(x, 0, x, rulerHeight * 0.4f, framePaint);

                // Show second number for better navigation
                textPaint.setTextSize(dpToPx(7));
                textPaint.setColor(COLOR_RULER_MINOR_TICK);
                canvas.drawText(
                    String.valueOf(second % 60),
                    x,
                    rulerHeight * 0.8f,
                    textPaint
                );
                textPaint.setTextSize(dpToPx(9));
                textPaint.setColor(COLOR_TEXT);
            }
        }
    }

    /**
     * Draw professional video track aligned under center playhead (CapCut style)
     */
    private void drawVideoTrack(Canvas canvas) {
        Log.d(
            TAG,
            "drawVideoTrack - hasVideo: " +
            hasVideo +
            ", duration: " +
            videoDuration
        );

        // Always draw something for debugging - either video track or placeholder
        if (videoDuration <= 0) {
            // Draw placeholder track
            drawVideoTrackPlaceholder(canvas);
            return;
        }

        int trackHeight = dpToPx(VIDEO_TRACK_HEIGHT_DP);
        int margin = dpToPx(TRACK_MARGIN_DP);
        int rulerHeight = dpToPx(25);
        int trackY = rulerHeight + margin;

        float pixelsPerMs = getPixelsPerMs();
        float videoTrackWidth = videoDuration * pixelsPerMs;

        // Calculate video track start position (aligned under center when position is 0)
        float centerX = getWidth() / 2f;
        float videoTrackStartX = centerX - (currentPosition * pixelsPerMs);

        // Video track background - only draw the actual video length
        trackPaint.setColor(COLOR_VIDEO_TRACK);
        tempRectF.set(
            videoTrackStartX,
            trackY,
            videoTrackStartX + videoTrackWidth,
            trackY + trackHeight
        );
        canvas.drawRoundRect(tempRectF, dpToPx(6), dpToPx(6), trackPaint);

        // Draw video thumbnails only for visible area
        drawVideoThumbnails(
            canvas,
            videoTrackStartX,
            trackY,
            videoTrackWidth,
            trackHeight,
            centerX
        );

        // Draw video track border for better visibility
        framePaint.setColor(COLOR_VIDEO_FRAME_BORDER);
        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setStrokeWidth(2f);
        canvas.drawRoundRect(tempRectF, dpToPx(6), dpToPx(6), framePaint);
        framePaint.setStyle(Paint.Style.FILL);
    }

    /**
     * Draw professional audio track aligned with video track (CapCut style)
     */
    private void drawAudioTrack(Canvas canvas) {
        int videoTrackHeight = dpToPx(VIDEO_TRACK_HEIGHT_DP);
        int audioTrackHeight = dpToPx(AUDIO_TRACK_HEIGHT_DP);
        int margin = dpToPx(TRACK_MARGIN_DP);
        int rulerHeight = dpToPx(25);
        int audioTrackY = rulerHeight + margin + videoTrackHeight + margin;

        if (hasAudio && videoDuration > 0) {
            float pixelsPerMs = getPixelsPerMs();
            float audioTrackWidth = videoDuration * pixelsPerMs;

            // Align with video track
            float centerX = getWidth() / 2f;
            float audioTrackStartX = centerX - (currentPosition * pixelsPerMs);

            // Audio track background
            trackPaint.setColor(COLOR_AUDIO_TRACK);
            tempRectF.set(
                audioTrackStartX,
                audioTrackY,
                audioTrackStartX + audioTrackWidth,
                audioTrackY + audioTrackHeight
            );
            canvas.drawRoundRect(tempRectF, dpToPx(6), dpToPx(6), trackPaint);

            // Draw audio waveform
            drawAudioWaveform(
                canvas,
                audioTrackStartX,
                audioTrackY,
                audioTrackWidth,
                audioTrackHeight
            );
        } else {
            // Draw "Add audio" placeholder - fixed position
            int viewWidth = getWidth();
            trackPaint.setColor(0xFF1A3A1A);
            tempRectF.set(
                dpToPx(TRACK_MARGIN_DP),
                audioTrackY,
                viewWidth - dpToPx(TRACK_MARGIN_DP),
                audioTrackY + audioTrackHeight
            );
            canvas.drawRoundRect(tempRectF, dpToPx(6), dpToPx(6), trackPaint);

            drawAddAudioPlaceholder(
                canvas,
                dpToPx(TRACK_MARGIN_DP),
                audioTrackY,
                viewWidth - 2 * dpToPx(TRACK_MARGIN_DP),
                audioTrackHeight
            );
        }
    }

    /**
     * Draw video thumbnails in the video track (optimized for visible area)
     */
    private void drawVideoThumbnails(
        Canvas canvas,
        float trackStartX,
        int y,
        float trackWidth,
        int height,
        float centerX
    ) {
        if (videoDuration <= 0) return;

        int thumbnailWidth = dpToPx(THUMBNAIL_WIDTH_DP);
        int viewWidth = getWidth();

        // Only draw thumbnails in visible area
        float visibleStartX = Math.max(trackStartX, 0);
        float visibleEndX = Math.min(trackStartX + trackWidth, viewWidth);

        if (visibleStartX >= visibleEndX) return; // Track not visible

        // Calculate which thumbnails to draw
        int startThumbIndex = Math.max(
            0,
            (int) ((visibleStartX - trackStartX) / thumbnailWidth)
        );
        int endThumbIndex = (int) Math.ceil(
            (visibleEndX - trackStartX) / thumbnailWidth
        );

        for (int i = startThumbIndex; i < endThumbIndex; i++) {
            float thumbX = trackStartX + (i * thumbnailWidth);
            float thumbEndX = Math.min(
                thumbX + thumbnailWidth,
                trackStartX + trackWidth
            );

            if (thumbX >= viewWidth || thumbEndX <= 0) continue; // Skip if outside view

            // Calculate time position for this thumbnail
            long timePosition = (long) ((i * thumbnailWidth) /
                getPixelsPerMs());

            // Constrain thumbnail to visible and track bounds
            float actualThumbStartX = Math.max(thumbX, 0);
            float actualThumbEndX = Math.min(thumbEndX, viewWidth);

            if (actualThumbStartX >= actualThumbEndX) continue;

            tempRectF.set(actualThumbStartX, y, actualThumbEndX, y + height);

            Bitmap thumbnail = videoThumbnails.get(timePosition);
            if (thumbnail != null && !thumbnail.isRecycled()) {
                canvas.drawBitmap(thumbnail, null, tempRectF, null);
            } else {
                // Draw placeholder with subtle pattern
                trackPaint.setColor(COLOR_VIDEO_TRACK);
                canvas.drawRoundRect(
                    tempRectF,
                    dpToPx(2),
                    dpToPx(2),
                    trackPaint
                );

                // Request thumbnail if not loading
                if (
                    !isLoadingThumbnails &&
                    timePosition >= 0 &&
                    timePosition <= videoDuration
                ) {
                    requestVideoThumbnail(timePosition);
                }
            }
        }
    }

    /**
     * Draw audio waveform in the audio track
     */
    private void drawAudioWaveform(
        Canvas canvas,
        float trackStartX,
        int y,
        float trackWidth,
        int height
    ) {
        if (waveformData == null || waveformData.length == 0) return;

        waveformPaint.setColor(COLOR_WAVEFORM);
        waveformPaint.setStrokeWidth(1.5f);

        int centerY = y + height / 2;
        int maxAmplitude = height / 3;
        int viewWidth = getWidth();

        // Only draw waveform for visible area
        float visibleStartX = Math.max(trackStartX, 0);
        float visibleEndX = Math.min(trackStartX + trackWidth, viewWidth);

        if (visibleStartX >= visibleEndX) return;

        float sampleWidth = trackWidth / waveformData.length;

        for (int i = 0; i < waveformData.length; i++) {
            float x1 = trackStartX + i * sampleWidth;

            // Skip if outside visible area
            if (x1 < visibleStartX - sampleWidth || x1 > visibleEndX) continue;

            float amplitude = waveformData[i] * maxAmplitude;

            // Draw waveform bar
            canvas.drawLine(
                x1,
                centerY - amplitude,
                x1,
                centerY + amplitude,
                waveformPaint
            );
        }
    }

    /**
     * Draw "Add audio" placeholder in audio track
     */
    private void drawAddAudioPlaceholder(
        Canvas canvas,
        float x,
        int y,
        float width,
        int height
    ) {
        // Draw "+" icon
        textPaint.setColor(COLOR_ADD_AUDIO_TEXT);
        textPaint.setTextSize(dpToPx(20));
        textPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(
            "+",
            x + width / 2,
            y + height / 2 - dpToPx(2),
            textPaint
        );

        // Draw "Add audio" text
        textPaint.setTextSize(dpToPx(10));
        canvas.drawText(
            "Add audio",
            x + width / 2,
            y + height / 2 + dpToPx(12),
            textPaint
        );
    }

    /**
     * Draw fixed center playhead line (CapCut style)
     */
    private void drawCenterPlayhead(Canvas canvas) {
        float centerX = getWidth() / 2f;

        playheadPaint.setColor(COLOR_CENTER_PLAYHEAD);
        playheadPaint.setStrokeWidth(dpToPx(CENTER_PLAYHEAD_WIDTH_DP));

        // Draw vertical line from top to bottom
        canvas.drawLine(centerX, 0, centerX, getHeight(), playheadPaint);

        // Draw small triangle at top
        tempPath.reset();
        tempPath.moveTo(centerX, dpToPx(5));
        tempPath.lineTo(centerX - dpToPx(4), dpToPx(15));
        tempPath.lineTo(centerX + dpToPx(4), dpToPx(15));
        tempPath.close();
        canvas.drawPath(tempPath, playheadPaint);
    }

    /**
     * Request video thumbnail for specific time position
     */
    private void requestVideoThumbnail(long timePositionMs) {
        if (videoUri == null || thumbnailExecutor == null) return;

        isLoadingThumbnails = true;
        thumbnailExecutor.execute(() -> {
            try {
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                retriever.setDataSource(getContext(), videoUri);

                Bitmap thumbnail = retriever.getFrameAtTime(
                    timePositionMs * 1000, // Convert to microseconds
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                );

                if (thumbnail != null) {
                    // Scale thumbnail to appropriate size
                    int targetWidth = dpToPx(THUMBNAIL_WIDTH_DP);
                    int targetHeight = dpToPx(VIDEO_TRACK_HEIGHT_DP);

                    Bitmap scaledThumbnail = Bitmap.createScaledBitmap(
                        thumbnail,
                        targetWidth,
                        targetHeight,
                        true
                    );

                    thumbnail.recycle();

                    mainHandler.post(() -> {
                        videoThumbnails.put(timePositionMs, scaledThumbnail);
                        invalidate();
                        isLoadingThumbnails = false;
                    });
                }

                retriever.release();
            } catch (Exception e) {
                Log.e(TAG, "Error loading video thumbnail", e);
                mainHandler.post(() -> isLoadingThumbnails = false);
            }
        });
    }

    private void drawWaveform(Canvas canvas) {
        if (waveformData == null || waveformData.length == 0) {
            if (!isLoadingWaveform && videoUri != null) {
                loadWaveformData();
            }
            return;
        }

        // Find audio track
        Track audioTrack = null;
        int audioTrackY = 0;
        int trackHeight = dpToPx(TRACK_HEIGHT_DP);

        for (int i = 0; i < tracks.size(); i++) {
            if (tracks.get(i).getType() == Track.Type.AUDIO) {
                audioTrack = tracks.get(i);
                audioTrackY = i * trackHeight;
                break;
            }
        }

        if (audioTrack == null) return;

        // Draw waveform
        int waveformHeight = dpToPx(WAVEFORM_HEIGHT_DP);
        int waveformCenterY = audioTrackY + trackHeight / 2;

        float pixelsPerSample = (float) getWidth() / waveformData.length;

        for (int i = 0; i < waveformData.length - 1; i++) {
            float x1 = i * pixelsPerSample;
            float x2 = (i + 1) * pixelsPerSample;

            float amplitude1 = (waveformData[i] * waveformHeight) / 2f;
            float amplitude2 = (waveformData[i + 1] * waveformHeight) / 2f;

            // Draw waveform line
            canvas.drawLine(
                x1,
                waveformCenterY - amplitude1,
                x2,
                waveformCenterY - amplitude2,
                waveformPaint
            );
            canvas.drawLine(
                x1,
                waveformCenterY + amplitude1,
                x2,
                waveformCenterY + amplitude2,
                waveformPaint
            );
        }
    }

    private void drawTrimRange(Canvas canvas) {
        if (trimStart >= trimEnd) return;

        float startX = timeToPixel(trimStart);
        float endX = timeToPixel(trimEnd);

        // Trim range overlay
        tempRectF.set(startX, 0, endX, getHeight());
        canvas.drawRect(tempRectF, trimPaint);

        // Trim handles
        int handleWidth = dpToPx(HANDLE_WIDTH_DP);

        // Start handle
        tempRectF.set(
            startX - handleWidth / 2f,
            0,
            startX + handleWidth / 2f,
            getHeight()
        );
        canvas.drawRect(tempRectF, handlePaint);

        // End handle
        tempRectF.set(
            endX - handleWidth / 2f,
            0,
            endX + handleWidth / 2f,
            getHeight()
        );
        canvas.drawRect(tempRectF, handlePaint);

        // Handle indicators
        drawHandleIndicator(canvas, startX, true);
        drawHandleIndicator(canvas, endX, false);
    }

    private void drawHandleIndicator(Canvas canvas, float x, boolean isStart) {
        int indicatorSize = dpToPx(6);
        float centerY = getHeight() / 2f;

        tempPath.reset();
        if (isStart) {
            tempPath.moveTo(x - indicatorSize, centerY - indicatorSize);
            tempPath.lineTo(x + indicatorSize, centerY);
            tempPath.lineTo(x - indicatorSize, centerY + indicatorSize);
        } else {
            tempPath.moveTo(x + indicatorSize, centerY - indicatorSize);
            tempPath.lineTo(x - indicatorSize, centerY);
            tempPath.lineTo(x + indicatorSize, centerY + indicatorSize);
        }
        tempPath.close();

        canvas.drawPath(tempPath, handlePaint);
    }

    private void drawPlayhead(Canvas canvas) {
        float x = timeToPixel(currentPosition);

        if (x >= 0 && x <= getWidth()) {
            // Playhead line
            canvas.drawLine(x, 0, x, getHeight(), playheadPaint);

            // Playhead indicator at top
            int indicatorSize = dpToPx(8);
            tempPath.reset();
            tempPath.moveTo(x, 0);
            tempPath.lineTo(x - indicatorSize, indicatorSize);
            tempPath.lineTo(x + indicatorSize, indicatorSize);
            tempPath.close();

            canvas.drawPath(tempPath, playheadPaint);

            // Current time text
            String timeText = formatTime(currentPosition);
            textPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(timeText, x, getHeight() - dpToPx(5), textPaint);
        }
    }

    private void drawZoomControls(Canvas canvas) {
        int controlSize = dpToPx(ZOOM_CONTROL_SIZE_DP);
        int margin = dpToPx(8);

        // Zoom out button
        tempRectF.set(
            getWidth() - margin - controlSize * 2 - dpToPx(4),
            margin,
            getWidth() - margin - controlSize - dpToPx(4),
            margin + controlSize
        );
        canvas.drawRoundRect(tempRectF, dpToPx(4), dpToPx(4), zoomControlPaint);
        canvas.drawText(
            "-",
            tempRectF.centerX(),
            tempRectF.centerY() + dpToPx(4),
            textPaint
        );

        // Zoom in button
        tempRectF.set(
            getWidth() - margin - controlSize,
            margin,
            getWidth() - margin,
            margin + controlSize
        );
        canvas.drawRoundRect(tempRectF, dpToPx(4), dpToPx(4), zoomControlPaint);
        canvas.drawText(
            "+",
            tempRectF.centerX(),
            tempRectF.centerY() + dpToPx(4),
            textPaint
        );
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean handled = scaleGestureDetector.onTouchEvent(event);

        if (!scaleGestureDetector.isInProgress()) {
            handled |= gestureDetector.onTouchEvent(event);
            handled |= handleTouchEvent(event);
        }

        return handled || super.onTouchEvent(event);
    }

    private boolean handleTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                return handleTouchDown(x, y);
            case MotionEvent.ACTION_MOVE:
                return handleTouchMove(x, y);
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                return handleTouchUp(x, y);
        }

        return false;
    }

    private boolean handleTouchDown(float x, float y) {
        lastTouchX = x;
        lastTouchY = y;

        // Check zoom controls first
        if (handleZoomControlTouch(x, y)) {
            return true;
        }

        // For center playhead interaction, check if touching center area
        float centerX = getWidth() / 2f;
        if (Math.abs(x - centerX) <= dpToPx(FRAME_SNAP_THRESHOLD_DP)) {
            isDragging = true;
            return true;
        }

        // Otherwise, allow timeline scrolling
        isDragging = true;
        return true;
    }

    private boolean handleTouchMove(float x, float y) {
        if (!isDragging) return false;

        float deltaX = x - lastTouchX;
        float deltaY = y - lastTouchY;

        // Check if this is a horizontal scroll gesture
        if (Math.abs(deltaX) > Math.abs(deltaY)) {
            float centerX = getWidth() / 2f;

            // If touching near center, scrub the video
            if (
                Math.abs(lastTouchX - centerX) <=
                dpToPx(FRAME_SNAP_THRESHOLD_DP * 2)
            ) {
                // Start scrubbing if not already
                if (!isScrubbing.get()) {
                    startScrubbing();
                }

                // Calculate new position based on touch offset from center
                float pixelOffset = x - centerX;
                long newPosition = Math.round(
                    (scrollX + centerX + pixelOffset) / getPixelsPerMs()
                );

                // Clamp to valid range
                newPosition = Math.max(0, Math.min(videoDuration, newPosition));

                if (frameSnappingEnabled) {
                    newPosition = snapToFrame(newPosition);
                }

                // Update position and notify listener immediately for responsive scrubbing
                currentPosition = newPosition;
                invalidate();

                if (listener != null) {
                    listener.onScrubbing(true, newPosition);
                    listener.onTimelinePositionChanged(newPosition);
                }
            } else {
                // Scroll timeline horizontally
                float oldScrollX = scrollX;
                scrollX -= deltaX;

                // Constrain scroll limits
                float maxScrollX = Math.max(
                    0,
                    videoDuration * getPixelsPerMs() - getWidth()
                );
                scrollX = Math.max(0, Math.min(scrollX, maxScrollX));

                // Update center position based on scroll and provide feedback
                if (scrollX != oldScrollX) {
                    float pixelsPerMs = getPixelsPerMs();
                    long centerTimePosition = Math.round(
                        (scrollX + centerX) / pixelsPerMs
                    );
                    centerTimePosition = Math.max(
                        0,
                        Math.min(videoDuration, centerTimePosition)
                    );

                    // Update timeline display but don't trigger video seek during scroll
                    if (listener != null) {
                        listener.onFrameSnapped(centerTimePosition);
                    }

                    // Don't reset currentPosition during manual scroll
                    // currentPosition should only change via setCurrentPosition calls
                }

                invalidate();
            }

            lastTouchX = x;
            lastTouchY = y;
            return true;
        }

        return false;
    }

    private boolean handleTouchUp(float x, float y) {
        boolean wasDragging = isDragging;

        if (isDragging) {
            isDragging = false;

            // If we were scrubbing, stop scrubbing
            if (isScrubbing.get()) {
                stopScrubbing();
            }
        }

        return wasDragging;
    }

    private boolean handleSingleTap(float x, float y) {
        // Tap to seek - use professional seeking for consistency
        long seekTime = pixelToTime(x);
        handleProfessionalSeek(seekTime);
        return true;
    }

    /**
     * Professional single-tap seeking with immediate visual feedback
     */
    private void handleProfessionalSeek(long seekTime) {
        // -------------- Fix Start (use user seek method) --------------
        // Immediate visual update using user seek to allow position jumps
        setCurrentPositionUserSeek(seekTime);

        // Async seek operation for smooth performance
        seekExecutor.submit(() -> {
            try {
                Thread.sleep(16); // ~60fps update rate

                scrubbingHandler.post(() -> {
                    if (listener != null) {
                        listener.onTimelinePositionChanged(seekTime);
                    }
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        // -------------- Fix Ended (use user seek method) --------------
    }

    private boolean handleDoubleTap(float x, float y) {
        // Double tap to zoom to fit
        zoomToFit();
        return true;
    }

    private boolean handleZoomControlTouch(float x, float y) {
        int controlSize = dpToPx(ZOOM_CONTROL_SIZE_DP);
        int margin = dpToPx(8);

        // Zoom out button
        if (
            x >= getWidth() - margin - controlSize * 2 - dpToPx(4) &&
            x <= getWidth() - margin - controlSize - dpToPx(4) &&
            y >= margin &&
            y <= margin + controlSize
        ) {
            zoomOut();
            return true;
        }

        // Zoom in button
        if (
            x >= getWidth() - margin - controlSize &&
            x <= getWidth() - margin &&
            y >= margin &&
            y <= margin + controlSize
        ) {
            zoomIn();
            return true;
        }

        return false;
    }

    // Utility methods

    // -------------- Fix Start (professional scrubbing methods) --------------
    private void startScrubbing() {
        isScrubbing.set(true);
        isActivelyScrubbing.set(true);
        scrubStartTime = System.currentTimeMillis();

        // Cancel any pending seek operations
        if (currentSeekTask != null && !currentSeekTask.isDone()) {
            currentSeekTask.cancel(true);
        }

        // Notify scrubbing start - pauses video and position updates
        if (listener != null) {
            listener.onScrubbing(true, currentPosition);
        }
    }

    private void stopScrubbing() {
        isActivelyScrubbing.set(false);
        long finalPosition = lastScrubbingPosition.get();

        // Cancel any pending operations
        if (currentSeekTask != null && !currentSeekTask.isDone()) {
            currentSeekTask.cancel(true);
        }

        // PROFESSIONAL EDITOR APPROACH: Single final seek after scrubbing ends
        // This is when the actual video seeking happens for exact frame accuracy
        scrubbingHandler.postDelayed(
            () -> {
                isScrubbing.set(false);

                if (listener != null) {
                    // First: Notify scrubbing ended (re-enables position updates)
                    listener.onScrubbing(false, finalPosition);

                    // Then: Single high-quality seek to exact final position
                    listener.onTimelinePositionChanged(finalPosition);
                }
            },
            50 // Small delay for smooth transition
        );
    }

    /**
     * Professional scrubbing handler with prediction and interpolation
     * Like DaVinci Resolve, Premiere Pro - ultra-smooth visual feedback
     */
    private void handleProfessionalScrubbing(long newPosition) {
        // PROFESSIONAL EDITOR APPROACH: Ultra-smooth scrubbing with prediction
        long currentTime = System.currentTimeMillis();

        // Update position tracking for prediction
        updatePositionHistory(newPosition, currentTime);

        // Calculate scrub velocity and predict next position
        calculateScrubVelocity();
        predictedPosition = calculatePredictedPosition(newPosition);

        // Professional frame interpolation for ultra-smooth feedback
        long displayPosition = useFrameInterpolation
            ? interpolateFramePosition(newPosition)
            : newPosition;

        // Immediately update visual position for responsive UI (120fps visual feedback)
        setCurrentPositionInternal(displayPosition);
        lastScrubbingPosition.set(newPosition);

        // Cancel any pending seek operations - we don't seek during active scrubbing
        if (currentSeekTask != null && !currentSeekTask.isDone()) {
            currentSeekTask.cancel(true);
        }

        // Professional predictive caching based on scrub direction
        requestPredictiveFrameCaching(newPosition, scrubVelocity);

        // CRITICAL: No video seeking during active scrubbing - visual only with interpolation
        // Professional editors use frame interpolation and prediction for ultra-smooth scrubbing
    }

    /**
     * Internal position update that doesn't trigger listener callbacks
     * Enhanced with interpolation for ultra-smooth visual feedback
     */
    private void setCurrentPositionInternal(long position) {
        if (frameSnappingEnabled && !isActivelyScrubbing.get()) {
            // Disable frame snapping during active scrubbing for smoother motion
            position = snapToFrame(position);
        }
        position = Math.max(0, Math.min(position, videoDuration));

        if (this.currentPosition != position) {
            this.currentPosition = position;
            invalidate(); // Simple invalidation for internal updates
        }
    }

    /**
     * Update position history for velocity calculation and prediction
     */
    private void updatePositionHistory(long position, long timestamp) {
        recentPositions[positionIndex] = position;
        recentTimestamps[positionIndex] = timestamp;
        positionIndex = (positionIndex + 1) % recentPositions.length;
    }

    /**
     * Calculate scrub velocity for smooth interpolation
     */
    private void calculateScrubVelocity() {
        if (recentTimestamps[0] == 0) return;

        // Calculate velocity over recent positions
        long deltaTime =
            recentTimestamps[(positionIndex - 1 + recentPositions.length) %
            recentPositions.length] -
            recentTimestamps[positionIndex];
        long deltaPos =
            recentPositions[(positionIndex - 1 + recentPositions.length) %
            recentPositions.length] -
            recentPositions[positionIndex];

        if (deltaTime > 0) {
            scrubVelocity = ((float) deltaPos / deltaTime) * 1000; // pixels per second
        }
    }

    /**
     * Calculate predicted position based on velocity
     */
    private long calculatePredictedPosition(long currentPos) {
        // Predict where user will scrub next based on velocity
        float predictionTimeMs = 16.67f; // One frame at 60fps
        return currentPos + (long) ((scrubVelocity * predictionTimeMs) / 1000);
    }

    /**
     * Interpolate frame position for ultra-smooth visual feedback
     */
    private long interpolateFramePosition(long targetPosition) {
        if (currentPosition == targetPosition) return targetPosition;

        // Smooth interpolation factor based on velocity
        float interpolationFactor = Math.min(
            0.8f,
            Math.abs(scrubVelocity) / 1000
        );

        // Interpolate between current and target position
        long interpolated = (long) (currentPosition +
            (targetPosition - currentPosition) * interpolationFactor);

        return interpolated;
    }

    /**
     * Request predictive frame caching based on scrub direction
     */
    private void requestPredictiveFrameCaching(long position, float velocity) {
        // Professional editors pre-cache frames in scrub direction
        if (Math.abs(velocity) > 50 && listener != null) {
            // Calculate frames to cache ahead/behind based on velocity direction
            int framesToCache = Math.min(10, (int) (Math.abs(velocity) / 100));
            long cacheDirection = velocity > 0 ? 1 : -1;

            // This would trigger frame pre-caching in the video system
            // (Implementation would be in the video system layer)
        }
    }

    // -------------- Fix Ended (professional scrubbing methods) --------------

    private void scrollTimeline(float distanceX) {
        long scrollAmount = (long) (distanceX / getPixelsPerMs());

        long newViewportStart = Math.max(0, viewportStart + scrollAmount);
        long newViewportEnd = Math.min(
            videoDuration,
            viewportEnd + scrollAmount
        );

        if (newViewportEnd - newViewportStart == viewportEnd - viewportStart) {
            viewportStart = newViewportStart;
            viewportEnd = newViewportEnd;

            if (state != null) {
                state.setViewportStart(viewportStart);
                state.setViewportEnd(viewportEnd);
            }

            invalidate();
        }
    }

    private void updateViewport() {
        if (videoDuration <= 0) return;

        long viewportDuration = (long) (videoDuration / zoomLevel);

        // Center viewport around current position
        long center = currentPosition;
        long newViewportStart = Math.max(0, center - viewportDuration / 2);
        long newViewportEnd = Math.min(
            videoDuration,
            newViewportStart + viewportDuration
        );

        // Adjust if we hit the end
        if (newViewportEnd == videoDuration) {
            newViewportStart = Math.max(0, newViewportEnd - viewportDuration);
        }

        // Only update if viewport actually changed (prevent feedback loops)
        if (
            viewportStart != newViewportStart || viewportEnd != newViewportEnd
        ) {
            viewportStart = newViewportStart;
            viewportEnd = newViewportEnd;

            if (state != null) {
                state.setViewportStart(viewportStart);
                state.setViewportEnd(viewportEnd);
            }
        }
    }

    private void ensurePlayheadVisible() {
        if (currentPosition < viewportStart || currentPosition > viewportEnd) {
            updateViewport();
        }
    }

    private float getPixelsPerMs() {
        if (videoDuration <= 0) return 1f;

        long viewportDuration = viewportEnd - viewportStart;
        if (viewportDuration <= 0) return 1f;

        return (float) getWidth() / viewportDuration;
    }

    private float timeToPixel(long timeMs) {
        if (videoDuration <= 0) return 0f;

        float pixelsPerMs = getPixelsPerMs();
        return timeMs * pixelsPerMs;
    }

    private long pixelToTime(float pixelX) {
        if (videoDuration <= 0) return 0L;

        float pixelsPerMs = getPixelsPerMs();
        // Add scroll offset for proper timeline position calculation
        return Math.round((pixelX + scrollX) / pixelsPerMs);
    }

    private long snapToFrame(long timeMs) {
        if (!frameSnappingEnabled || frameRate <= 0) return timeMs;

        long frameDurationMs = (long) (1000f / frameRate);
        long frameNumber = Math.round((float) timeMs / frameDurationMs);
        return frameNumber * frameDurationMs;
    }

    private long calculateMarkerInterval() {
        long viewportDuration = viewportEnd - viewportStart;

        if (viewportDuration <= 5000) return 500; // 0.5 seconds
        if (viewportDuration <= 30000) return 5000; // 5 seconds
        if (viewportDuration <= 300000) return 30000; // 30 seconds
        return 60000; // 1 minute
    }

    /**
     * Calculate appropriate time interval for timeline ruler based on video duration
     */
    private long calculateTimeInterval() {
        // Always use 1 second intervals for consistent, professional ruler
        return 1000; // 1 second intervals for all videos
    }

    private String formatTime(long timeMs) {
        long totalSeconds = timeMs / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        long milliseconds = timeMs % 1000;

        if (zoomLevel > 10.0f) {
            // Show milliseconds when zoomed in
            return String.format(
                "%d:%02d.%03d",
                minutes,
                seconds,
                milliseconds
            );
        } else {
            return String.format("%d:%02d", minutes, seconds);
        }
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            getContext().getResources().getDisplayMetrics()
        );
    }

    private void loadWaveformData() {
        if (videoUri == null || isLoadingWaveform) return;

        isLoadingWaveform = true;

        waveformExecutor.execute(() -> {
            try {
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                retriever.setDataSource(getContext(), videoUri);

                // Extract audio waveform data (simplified implementation)
                // In a real implementation, you would use FFmpeg or MediaExtractor
                float[] waveform = generateSampleWaveform(1000); // 1000 samples

                mainHandler.post(() -> {
                    waveformData = waveform;
                    isLoadingWaveform = false;
                    invalidate();
                });

                retriever.release();
            } catch (Exception e) {
                mainHandler.post(() -> {
                    isLoadingWaveform = false;
                });
            }
        });
    }

    private float[] generateSampleWaveform(int samples) {
        // Generate a sample waveform for demonstration
        // In a real implementation, this would extract actual audio data
        float[] waveform = new float[samples];
        for (int i = 0; i < samples; i++) {
            waveform[i] = (float) (Math.sin(i * 0.1) * Math.random() * 0.8);
        }
        return waveform;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        if (waveformExecutor != null && !waveformExecutor.isShutdown()) {
            waveformExecutor.shutdown();
        }

        // -------------- Fix Start (cleanup professional timeline) --------------
        // Cleanup thumbnail executor
        if (thumbnailExecutor != null && !thumbnailExecutor.isShutdown()) {
            thumbnailExecutor.shutdown();
        }

        // Cleanup video thumbnails
        if (videoThumbnails != null) {
            for (Bitmap bitmap : videoThumbnails.values()) {
                if (bitmap != null && !bitmap.isRecycled()) {
                    bitmap.recycle();
                }
            }
            videoThumbnails.clear();
        }

        // Cleanup professional scrubbing system
        if (seekExecutor != null && !seekExecutor.isShutdown()) {
            seekExecutor.shutdown();
        }

        if (scrubbingHandler != null) {
            scrubbingHandler.removeCallbacksAndMessages(null);
        }

        if (seekDebounceHandler != null) {
            seekDebounceHandler.removeCallbacksAndMessages(null);
        }

        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }

        if (scrubAnimator != null && scrubAnimator.isRunning()) {
            scrubAnimator.cancel();
        }
        // -------------- Fix Ended (cleanup professional timeline) --------------
    }

    /**
     * Draw video track placeholder when no video is loaded
     */
    private void drawVideoTrackPlaceholder(Canvas canvas) {
        int trackHeight = dpToPx(VIDEO_TRACK_HEIGHT_DP);
        int margin = dpToPx(TRACK_MARGIN_DP);
        int rulerHeight = dpToPx(25);
        int trackY = rulerHeight + margin;
        int viewWidth = getWidth();

        // Draw placeholder background
        trackPaint.setColor(0xFF444444);
        tempRectF.set(margin, trackY, viewWidth - margin, trackY + trackHeight);
        canvas.drawRoundRect(tempRectF, dpToPx(6), dpToPx(6), trackPaint);

        // Draw "No Video" text
        textPaint.setColor(COLOR_TRACK_LABEL);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(dpToPx(12));
        canvas.drawText(
            "Import Video to Start Editing",
            viewWidth / 2f,
            trackY + trackHeight / 2f + dpToPx(4),
            textPaint
        );
    }

    /**
     * Update scroll position to keep current playhead centered (CapCut style)
     */
    private void updateScrollForCenterPlayhead() {
        if (videoDuration <= 0) return;

        float pixelsPerMs = getPixelsPerMs();
        float centerX = getWidth() / 2f;
        float currentPositionPixel = currentPosition * pixelsPerMs;

        // Calculate target scroll position to center current position
        float targetScrollX = currentPositionPixel - centerX;

        // Ensure we don't scroll too far
        float maxScrollX = Math.max(
            0,
            videoDuration * pixelsPerMs - getWidth()
        );
        targetScrollX = Math.max(0, Math.min(targetScrollX, maxScrollX));

        // Update scroll position (don't animate during manual control)
        scrollX = targetScrollX;

        // Always notify about position updates for display sync
        if (listener != null) {
            listener.onFrameSnapped(currentPosition);
        }
    }

    // Track class for multi-track support
    public static class Track {

        public enum Type {
            VIDEO,
            AUDIO,
            SUBTITLE,
        }

        private String name;
        private Type type;
        private boolean enabled;
        private boolean locked;

        public Track(String name, Type type) {
            this.name = name;
            this.type = type;
            this.enabled = true;
            this.locked = false;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Type getType() {
            return type;
        }

        public void setType(Type type) {
            this.type = type;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isLocked() {
            return locked;
        }

        public void setLocked(boolean locked) {
            this.locked = locked;
        }
    }

    public class TrimRange {

        private final long startMs;
        private final long endMs;

        public TrimRange(long startMs, long endMs) {
            this.startMs = startMs;
            this.endMs = endMs;
        }

        public long getStartMs() {
            return startMs;
        }

        public long getEndMs() {
            return endMs;
        }
    }

    public TrimRange getTrimRange() {
        return new TrimRange(trimStart, trimEnd);
    }

    // Getters for current state
    public long getVideoDuration() {
        return videoDuration;
    }

    public long getCurrentPosition() {
        return currentPosition;
    }

    public float getZoomLevel() {
        return zoomLevel;
    }

    public boolean isFrameSnappingEnabled() {
        return frameSnappingEnabled;
    }

    public boolean isScrubbing() {
        return isScrubbing.get();
    }

    public List<Track> getTracks() {
        return new ArrayList<>(tracks);
    }

    public int getSelectedTrackIndex() {
        return 0; // Always return 0 for compatibility
    }

    // -------------- Fix Start (Professional Track Classes) --------------

    /**
     * Professional Video Track class
     */
    public static class VideoTrack {

        private boolean enabled = true;
        private boolean muted = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isMuted() {
            return muted;
        }

        public void setMuted(boolean muted) {
            this.muted = muted;
        }
    }

    /**
     * Professional Audio Track class
     */
    public static class AudioTrack {

        private boolean enabled = false;
        private boolean muted = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isMuted() {
            return muted;
        }

        public void setMuted(boolean muted) {
            this.muted = muted;
        }
    }

    // -------------- Fix Ended (Professional Track Classes) --------------
}
