package com.fadcam.ui.faditor.components;

import android.animation.ValueAnimator;
import android.content.Context;
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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Professional timeline component with zoom, scrubbing, frame-accurate editing, and waveform visualization.
 * Implements requirements 11.3 and 11.7 for professional video editing interface.
 */
public class TimelineComponent extends View {

    private static final String TAG = "TimelineComponent";

    // Constants for professional timeline
    private static final float MIN_ZOOM = 0.1f;
    private static final float MAX_ZOOM = 50.0f;
    private static final int TIMELINE_HEIGHT_DP = 120;
    private static final int WAVEFORM_HEIGHT_DP = 40;
    private static final int TRACK_HEIGHT_DP = 60;
    private static final int HANDLE_WIDTH_DP = 12;
    private static final int PLAYHEAD_WIDTH_DP = 2;
    private static final int FRAME_SNAP_THRESHOLD_DP = 8;
    private static final int ZOOM_CONTROL_SIZE_DP = 40;
    private static final float FRAME_RATE = 30.0f; // Default frame rate

    // -------------- Fix Start (debouncing constants) --------------
    private static final int SEEK_DEBOUNCE_DELAY_MS = 50; // 50ms debounce for smooth scrubbing
    private static final int SCRUB_UPDATE_INTERVAL_MS = 16; // ~60fps for smooth preview updates
    // -------------- Fix Ended (debouncing constants) --------------

    // Colors
    private static final int COLOR_TIMELINE_BG = 0xFF1E1E1E;
    private static final int COLOR_TRACK_BG = 0xFF2D2D2D;
    private static final int COLOR_TRIM_RANGE = 0xFF4CAF50;
    private static final int COLOR_TRIM_HANDLE = 0xFF66BB6A;
    private static final int COLOR_PLAYHEAD = 0xFFFF5722;
    private static final int COLOR_WAVEFORM = 0xFF03DAC6;
    private static final int COLOR_FRAME_MARKER = 0xFF757575;
    private static final int COLOR_TIME_TEXT = 0xFFFFFFFF;
    private static final int COLOR_ZOOM_CONTROL = 0xFF424242;

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
    private int selectedTrackIndex = 0;

    // Viewport and scrolling
    private long viewportStart;
    private long viewportEnd;
    private float scrollX = 0f;

    // Interaction state
    private boolean isDraggingStartHandle = false;
    private boolean isDraggingEndHandle = false;
    private boolean isDraggingPlayhead = false;
    private boolean isScrubbing = false;
    private boolean frameSnappingEnabled = true;
    private float lastTouchX;
    private float lastTouchY;

    // Waveform data
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
    private long pendingSeekPosition = -1;
    private boolean isDebouncing = false;

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

        // Initialize executors and handlers
        waveformExecutor = Executors.newSingleThreadExecutor();
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
        trackPaint.setColor(COLOR_TRACK_BG);

        trimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        trimPaint.setColor(COLOR_TRIM_RANGE);
        trimPaint.setAlpha(128);

        handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        handlePaint.setColor(COLOR_TRIM_HANDLE);

        playheadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        playheadPaint.setColor(COLOR_PLAYHEAD);
        playheadPaint.setStrokeWidth(dpToPx(PLAYHEAD_WIDTH_DP));

        waveformPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        waveformPaint.setColor(COLOR_WAVEFORM);
        waveformPaint.setStrokeWidth(1f);

        framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        framePaint.setColor(COLOR_FRAME_MARKER);
        framePaint.setStrokeWidth(1f);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(COLOR_TIME_TEXT);
        textPaint.setTextSize(dpToPx(12));
        textPaint.setTextAlign(Paint.Align.CENTER);

        zoomControlPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        zoomControlPaint.setColor(COLOR_ZOOM_CONTROL);
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
                    if (
                        !isDraggingStartHandle &&
                        !isDraggingEndHandle &&
                        !isDraggingPlayhead
                    ) {
                        // Scroll timeline viewport
                        scrollTimeline(distanceX);
                        return true;
                    }
                    return false;
                }

                @Override
                public boolean onSingleTapUp(MotionEvent e) {
                    return handleSingleTap(e.getX(), e.getY());
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
        loadWaveformData();
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

        if (frameSnappingEnabled) {
            newPosition = snapToFrame(newPosition);
        }

        this.currentPosition = newPosition;

        // Ensure playhead is visible in viewport
        ensurePlayheadVisible();

        invalidate();

        // -------------- Fix Start (debounced position change) --------------
        if (listener != null) {
            // If we're actively scrubbing, use debounced seeking
            if (isScrubbing) {
                scheduleDebounceSeek(this.currentPosition);
            } else {
                // Immediate update for programmatic position changes
                listener.onTimelinePositionChanged(this.currentPosition);
            }
        }
        // -------------- Fix Ended (debounced position change) --------------
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

        // Draw tracks
        drawTracks(canvas);

        // Draw time markers and frame indicators
        drawTimeMarkers(canvas);

        // Draw waveform if available
        drawWaveform(canvas);

        // Draw trim range
        drawTrimRange(canvas);

        // Draw playhead
        drawPlayhead(canvas);

        // Draw zoom controls
        drawZoomControls(canvas);
    }

    private void drawEmptyState(Canvas canvas) {
        canvas.drawRect(0, 0, getWidth(), getHeight(), timelinePaint);

        String text = "No video loaded";
        float textX = getWidth() / 2f;
        float textY = getHeight() / 2f;
        canvas.drawText(text, textX, textY, textPaint);
    }

    private void drawTracks(Canvas canvas) {
        int trackHeight = dpToPx(TRACK_HEIGHT_DP);
        int currentY = 0;

        for (int i = 0; i < tracks.size(); i++) {
            Track track = tracks.get(i);

            // Track background
            trackPaint.setColor(
                i == selectedTrackIndex
                    ? COLOR_TRACK_BG
                    : (COLOR_TRACK_BG & 0x80FFFFFF)
            );
            canvas.drawRect(
                0,
                currentY,
                getWidth(),
                currentY + trackHeight,
                trackPaint
            );

            // Track label
            textPaint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(
                track.getName(),
                dpToPx(8),
                currentY + trackHeight / 2f,
                textPaint
            );

            currentY += trackHeight;
        }
    }

    private void drawTimeMarkers(Canvas canvas) {
        if (videoDuration <= 0) return;

        float pixelsPerMs = getPixelsPerMs();
        long markerInterval = calculateMarkerInterval();

        // Draw major time markers
        for (long time = 0; time <= videoDuration; time += markerInterval) {
            float x = timeToPixel(time);

            if (x >= 0 && x <= getWidth()) {
                // Major marker line
                canvas.drawLine(x, 0, x, getHeight(), framePaint);

                // Time text
                String timeText = formatTime(time);
                canvas.drawText(timeText, x, dpToPx(15), textPaint);
            }
        }

        // Draw frame markers if zoomed in enough
        if (frameSnappingEnabled && zoomLevel > 5.0f) {
            drawFrameMarkers(canvas, pixelsPerMs);
        }
    }

    private void drawFrameMarkers(Canvas canvas, float pixelsPerMs) {
        long frameDurationMs = (long) (1000f / frameRate);
        float framePixelWidth = frameDurationMs * pixelsPerMs;

        if (framePixelWidth > 2f) {
            // Only draw if frames are visible
            framePaint.setAlpha(64);

            long startFrame = viewportStart / frameDurationMs;
            long endFrame = viewportEnd / frameDurationMs + 1;

            for (long frame = startFrame; frame <= endFrame; frame++) {
                long frameTime = frame * frameDurationMs;
                float x = timeToPixel(frameTime);

                if (x >= 0 && x <= getWidth()) {
                    canvas.drawLine(
                        x,
                        getHeight() - dpToPx(20),
                        x,
                        getHeight(),
                        framePaint
                    );
                }
            }

            framePaint.setAlpha(255);
        }
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

        // Check trim handles
        float startHandleX = timeToPixel(trimStart);
        float endHandleX = timeToPixel(trimEnd);
        int handleWidth = dpToPx(HANDLE_WIDTH_DP);

        if (Math.abs(x - startHandleX) <= handleWidth) {
            isDraggingStartHandle = true;
            return true;
        }

        if (Math.abs(x - endHandleX) <= handleWidth) {
            isDraggingEndHandle = true;
            return true;
        }

        // Check playhead
        float playheadX = timeToPixel(currentPosition);
        if (Math.abs(x - playheadX) <= dpToPx(FRAME_SNAP_THRESHOLD_DP)) {
            isDraggingPlayhead = true;
            startScrubbing();
            return true;
        }

        return false;
    }

    private boolean handleTouchMove(float x, float y) {
        if (isDraggingStartHandle) {
            long newStart = pixelToTime(x);
            if (frameSnappingEnabled) {
                newStart = snapToFrame(newStart);
            }
            setTrimRange(newStart, trimEnd);
            return true;
        }

        if (isDraggingEndHandle) {
            long newEnd = pixelToTime(x);
            if (frameSnappingEnabled) {
                newEnd = snapToFrame(newEnd);
            }
            setTrimRange(trimStart, newEnd);
            return true;
        }

        if (isDraggingPlayhead) {
            long newPosition = pixelToTime(x);
            setCurrentPosition(newPosition);

            if (listener != null) {
                listener.onScrubbing(true, newPosition);
            }
            return true;
        }

        return false;
    }

    private boolean handleTouchUp(float x, float y) {
        boolean wasHandled =
            isDraggingStartHandle || isDraggingEndHandle || isDraggingPlayhead;

        isDraggingStartHandle = false;
        isDraggingEndHandle = false;

        if (isDraggingPlayhead) {
            isDraggingPlayhead = false;
            stopScrubbing();
        }

        return wasHandled;
    }

    private boolean handleSingleTap(float x, float y) {
        // Tap to seek
        long seekTime = pixelToTime(x);
        setCurrentPosition(seekTime);
        return true;
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

    private void startScrubbing() {
        isScrubbing = true;
        if (listener != null) {
            listener.onScrubbing(true, currentPosition);
        }
    }

    private void stopScrubbing() {
        isScrubbing = false;
        if (listener != null) {
            listener.onScrubbing(false, currentPosition);
        }
    }

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
        viewportStart = Math.max(0, center - viewportDuration / 2);
        viewportEnd = Math.min(videoDuration, viewportStart + viewportDuration);

        // Adjust if we hit the end
        if (viewportEnd == videoDuration) {
            viewportStart = Math.max(0, viewportEnd - viewportDuration);
        }

        if (state != null) {
            state.setViewportStart(viewportStart);
            state.setViewportEnd(viewportEnd);
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

        return (timeMs - viewportStart) * getPixelsPerMs();
    }

    private long pixelToTime(float pixel) {
        if (videoDuration <= 0) return 0;

        return viewportStart + (long) (pixel / getPixelsPerMs());
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

        if (scrubAnimator != null) {
            scrubAnimator.cancel();
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
        return isScrubbing;
    }

    public List<Track> getTracks() {
        return new ArrayList<>(tracks);
    }

    public int getSelectedTrackIndex() {
        return selectedTrackIndex;
    }
}
