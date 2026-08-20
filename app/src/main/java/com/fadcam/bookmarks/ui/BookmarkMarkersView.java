package com.fadcam.bookmarks.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.bookmarks.Bookmark;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws bookmark markers on top of the player's time slider.
 *
 * <p>The view is laid out over the slider and mirrors its track geometry, so a
 * marker sits exactly above the position it refers to. It never handles touches:
 * scrubbing must keep working through it, so it stays non-clickable and lets
 * every event fall through to the slider underneath.</p>
 */
public class BookmarkMarkersView extends View {

    /** Marker colour — the amber used for quick-action value badges. */
    private static final int COLOR_MARKER = 0xFFFFD54F;

    private static final float MARKER_WIDTH_DP = 2f;
    private static final float MARKER_DOT_RADIUS_DP = 3f;

    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Bookmark> bookmarks = new ArrayList<>();

    private float markerWidthPx;
    private float dotRadiusPx;

    private long durationMs = 0L;
    private int trackStartPx = 0;
    private int trackWidthPx = 0;

    public BookmarkMarkersView(Context context) {
        super(context);
        init();
    }

    public BookmarkMarkersView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        float density = getResources().getDisplayMetrics().density;
        markerWidthPx = MARKER_WIDTH_DP * density;
        dotRadiusPx = MARKER_DOT_RADIUS_DP * density;
        markerPaint.setColor(COLOR_MARKER);
        markerPaint.setStyle(Paint.Style.FILL);
        setClickable(false);
        setFocusable(false);
    }

    /**
     * Replaces the markers shown.
     *
     * @param bookmarks the bookmarks to draw; {@code null} clears the view
     */
    public void setBookmarks(@Nullable List<Bookmark> bookmarks) {
        this.bookmarks.clear();
        if (bookmarks != null) {
            this.bookmarks.addAll(bookmarks);
        }
        invalidate();
    }

    /**
     * Sets the media duration the marker positions are mapped against.
     *
     * @param durationMs total media duration in milliseconds
     */
    public void setDurationMs(long durationMs) {
        if (this.durationMs == durationMs) {
            return;
        }
        this.durationMs = durationMs;
        invalidate();
    }

    /**
     * Mirrors the slider's track geometry so markers line up with the thumb.
     *
     * @param trackStartPx x offset of the track inside this view, in pixels
     * @param trackWidthPx drawn track width, in pixels
     */
    public void setTrackGeometry(int trackStartPx, int trackWidthPx) {
        if (this.trackStartPx == trackStartPx && this.trackWidthPx == trackWidthPx) {
            return;
        }
        this.trackStartPx = trackStartPx;
        this.trackWidthPx = trackWidthPx;
        invalidate();
    }

    /** @return {@code true} when there is at least one marker to draw. */
    public boolean hasBookmarks() {
        return !bookmarks.isEmpty();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (bookmarks.isEmpty() || durationMs <= 0L || trackWidthPx <= 0) {
            return;
        }
        int height = getHeight();
        if (height <= 0) {
            return;
        }
        for (Bookmark bookmark : bookmarks) {
            float fraction = Math.min(1f, Math.max(0f, bookmark.getPositionMs() / (float) durationMs));
            float centerX = trackStartPx + fraction * trackWidthPx;
            canvas.drawCircle(centerX, dotRadiusPx, dotRadiusPx, markerPaint);
            canvas.drawRect(
                    centerX - markerWidthPx / 2f,
                    dotRadiusPx,
                    centerX + markerWidthPx / 2f,
                    height,
                    markerPaint);
        }
    }
}
