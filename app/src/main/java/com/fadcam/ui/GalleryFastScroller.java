package com.fadcam.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Gallery-style fast scroller with a draggable thumb and date bubble popup.
 * Attach to any RecyclerView via {@link #attachTo(RecyclerView)}.
 * Provide section text via {@link SectionIndexer}.
 *
 * <p>Features:
 * <ul>
 *     <li>Thumb grows on touch/hold</li>
 *     <li>Date bubble appears while dragging</li>
 *     <li>Auto-hides after inactivity</li>
 *     <li>Silky smooth, lag-free scrolling</li>
 * </ul>
 */
public class GalleryFastScroller extends View {

    /** Provides the section/date text for a given adapter position. */
    public interface SectionIndexer {
        @NonNull
        String getSectionText(int position);
    }

    // ── Dimensions (dp → px at init) ──
    private float thumbWidth;
    private float thumbWidthExpanded;
    private float thumbHeight;
    private float thumbRadius;
    private float trackWidth;

    private float bubblePaddingH;
    private float bubblePaddingV;
    private float bubbleRadius;
    private float bubbleMarginEnd;
    private float bubbleTextSize;
    private float thumbMarginEnd;

    // ── Paints ──
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubbleTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // ── State ──
    private float thumbCenterY;
    private float currentThumbWidth;
    private boolean isDragging;
    private boolean isVisible;
    private String currentSectionText = "";

    private final RectF thumbRect = new RectF();
    private final RectF bubbleRect = new RectF();

    private RecyclerView recyclerView;
    private SectionIndexer sectionIndexer;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final long AUTO_HIDE_DELAY_MS = 1500L;
    private ValueAnimator thumbWidthAnimator;

    private final Runnable hideRunnable = () -> animateVisibility(false);

    // ── Scroll listener ──
    private final RecyclerView.OnScrollListener scrollListener = new RecyclerView.OnScrollListener() {
        @Override
        public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
            if (!isDragging) {
                updateThumbPositionFromRecyclerView();
                showAndScheduleHide();
            }
        }
    };

    public GalleryFastScroller(Context context) {
        super(context);
        init();
    }

    public GalleryFastScroller(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public GalleryFastScroller(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        float density = getResources().getDisplayMetrics().density;

        thumbWidth = 6 * density;
        thumbWidthExpanded = 14 * density;
        thumbHeight = 52 * density;
        thumbRadius = 8 * density;
        trackWidth = 4 * density;
        thumbMarginEnd = 4 * density;

        bubblePaddingH = 16 * density;
        bubblePaddingV = 10 * density;
        bubbleRadius = 12 * density;
        bubbleMarginEnd = 24 * density;
        bubbleTextSize = 14 * density;

        currentThumbWidth = thumbWidth;

        trackPaint.setColor(0x1AFFFFFF);
        thumbPaint.setColor(0xCCFFFFFF);
        bubblePaint.setColor(0xE6333333);

        bubbleTextPaint.setColor(0xFFFFFFFF);
        bubbleTextPaint.setTextSize(bubbleTextSize);
        bubbleTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        bubbleTextPaint.setTextAlign(Paint.Align.RIGHT);

        setAlpha(0f);
        isVisible = false;
    }

    /**
     * Attaches this scroller to the given RecyclerView.
     * Call this from the fragment's onViewCreated.
     */
    public void attachTo(@NonNull RecyclerView rv) {
        if (recyclerView != null) {
            recyclerView.removeOnScrollListener(scrollListener);
        }
        recyclerView = rv;
        recyclerView.addOnScrollListener(scrollListener);
    }

    /**
     * Sets the section indexer that provides date text for positions.
     */
    public void setSectionIndexer(@NonNull SectionIndexer indexer) {
        this.sectionIndexer = indexer;
    }

    /**
     * Detaches from the RecyclerView. Call from onDestroyView.
     */
    public void detach() {
        handler.removeCallbacks(hideRunnable);
        if (thumbWidthAnimator != null) {
            thumbWidthAnimator.cancel();
            thumbWidthAnimator = null;
        }
        if (recyclerView != null) {
            recyclerView.removeOnScrollListener(scrollListener);
            recyclerView = null;
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (recyclerView == null) return;

        int w = getWidth();
        int h = getHeight();
        if (h <= 0) return;

        // ── Track ──
        float trackX = w - thumbMarginEnd - currentThumbWidth / 2f;
        float trackTop = thumbHeight / 2f;
        float trackBottom = h - thumbHeight / 2f;
        canvas.drawRoundRect(
                trackX - trackWidth / 2f, trackTop,
                trackX + trackWidth / 2f, trackBottom,
                trackWidth / 2f, trackWidth / 2f,
                trackPaint);

        // ── Thumb ──
        float clampedY = Math.max(trackTop, Math.min(thumbCenterY, trackBottom));
        thumbRect.set(
                trackX - currentThumbWidth / 2f,
                clampedY - thumbHeight / 2f,
                trackX + currentThumbWidth / 2f,
                clampedY + thumbHeight / 2f);
        canvas.drawRoundRect(thumbRect, thumbRadius, thumbRadius, thumbPaint);

        // ── Bubble (only while dragging) ──
        if (isDragging && currentSectionText != null && !currentSectionText.isEmpty()) {
            float textWidth = bubbleTextPaint.measureText(currentSectionText);
            Paint.FontMetrics fm = bubbleTextPaint.getFontMetrics();
            float textHeight = fm.descent - fm.ascent;
            float bubbleWidth = textWidth + bubblePaddingH * 2;
            float bubbleHeight = textHeight + bubblePaddingV * 2;

            float bubbleRight = thumbRect.left - bubbleMarginEnd;
            float bubbleLeft = bubbleRight - bubbleWidth;
            float bubbleTop = clampedY - bubbleHeight / 2f;
            float bubbleBottom = clampedY + bubbleHeight / 2f;

            // Clamp to view bounds
            if (bubbleTop < 0) {
                bubbleTop = 0;
                bubbleBottom = bubbleHeight;
            }
            if (bubbleBottom > h) {
                bubbleBottom = h;
                bubbleTop = h - bubbleHeight;
            }

            bubbleRect.set(bubbleLeft, bubbleTop, bubbleRight, bubbleBottom);
            canvas.drawRoundRect(bubbleRect, bubbleRadius, bubbleRadius, bubblePaint);

            float textX = bubbleRight - bubblePaddingH;
            float textY = bubbleTop + bubblePaddingV - fm.ascent;
            canvas.drawText(currentSectionText, textX, textY, bubbleTextPaint);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (recyclerView == null) return false;
        // ── FIX: Don't intercept touches when the scroller is invisible ──
        if (getAlpha() < 0.1f) return false;

        int h = getHeight();
        float trackTop = thumbHeight / 2f;
        float trackBottom = h - thumbHeight / 2f;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float x = event.getX();
                float y = event.getY();
                // Expand touch target area for easier grabbing
                float touchLeft = thumbRect.left - 24 * getResources().getDisplayMetrics().density;
                if (x < touchLeft) return false;

                isDragging = true;
                handler.removeCallbacks(hideRunnable);
                animateThumbWidth(thumbWidthExpanded);
                thumbCenterY = Math.max(trackTop, Math.min(y, trackBottom));
                scrollRecyclerViewToThumb(trackTop, trackBottom);
                updateSectionText();
                invalidate();
                // Request parent not to intercept touch (e.g., SwipeRefreshLayout)
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!isDragging) return false;
                thumbCenterY = Math.max(trackTop, Math.min(event.getY(), trackBottom));
                scrollRecyclerViewToThumb(trackTop, trackBottom);
                updateSectionText();
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (!isDragging) return false;
                isDragging = false;
                animateThumbWidth(thumbWidth);
                scheduleHide();
                invalidate();
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            }
        }
        return false;
    }

    // ── Scroll ↔ Thumb mapping ──

    private void updateThumbPositionFromRecyclerView() {
        if (recyclerView == null || getHeight() <= 0) return;

        int h = getHeight();
        float trackTop = thumbHeight / 2f;
        float trackBottom = h - thumbHeight / 2f;
        float trackRange = trackBottom - trackTop;
        if (trackRange <= 0) return;

        float scrollFraction = computeScrollFraction();
        thumbCenterY = trackTop + scrollFraction * trackRange;
        updateSectionText();
        invalidate();
    }

    private float computeScrollFraction() {
        if (recyclerView == null) return 0f;
        int offset = recyclerView.computeVerticalScrollOffset();
        int range = recyclerView.computeVerticalScrollRange() - recyclerView.computeVerticalScrollExtent();
        return range > 0 ? Math.max(0f, Math.min(1f, (float) offset / range)) : 0f;
    }

    private void scrollRecyclerViewToThumb(float trackTop, float trackBottom) {
        if (recyclerView == null) return;
        float trackRange = trackBottom - trackTop;
        if (trackRange <= 0) return;

        float fraction = (thumbCenterY - trackTop) / trackRange;
        fraction = Math.max(0f, Math.min(1f, fraction));

        RecyclerView.Adapter<?> adapter = recyclerView.getAdapter();
        if (adapter == null) return;

        int itemCount = adapter.getItemCount();
        if (itemCount <= 0) return;

        int targetPos = Math.round(fraction * (itemCount - 1));
        targetPos = Math.max(0, Math.min(targetPos, itemCount - 1));

        RecyclerView.LayoutManager lm = recyclerView.getLayoutManager();
        if (lm instanceof LinearLayoutManager) {
            ((LinearLayoutManager) lm).scrollToPositionWithOffset(targetPos, 0);
        } else {
            recyclerView.scrollToPosition(targetPos);
        }
    }

    private void updateSectionText() {
        if (sectionIndexer == null || recyclerView == null) {
            currentSectionText = "";
            return;
        }
        RecyclerView.LayoutManager lm = recyclerView.getLayoutManager();
        if (lm instanceof LinearLayoutManager) {
            int firstVisible = ((LinearLayoutManager) lm).findFirstVisibleItemPosition();
            if (firstVisible >= 0) {
                currentSectionText = sectionIndexer.getSectionText(firstVisible);
                return;
            }
        }
        currentSectionText = "";
    }

    // ── Animations ──

    private void animateThumbWidth(float targetWidth) {
        if (Math.abs(currentThumbWidth - targetWidth) < 0.5f) return;
        if (thumbWidthAnimator != null) {
            thumbWidthAnimator.cancel();
        }
        thumbWidthAnimator = ValueAnimator.ofFloat(currentThumbWidth, targetWidth);
        thumbWidthAnimator.setDuration(150);
        thumbWidthAnimator.addUpdateListener(animation -> {
            currentThumbWidth = (float) animation.getAnimatedValue();
            invalidate();
        });
        thumbWidthAnimator.start();
    }

    private void animateVisibility(boolean show) {
        if (show == isVisible) return;
        isVisible = show;
        animate().cancel();
        animate()
                .alpha(show ? 1f : 0f)
                .setDuration(show ? 150 : 300)
                .setListener(null)
                .start();
    }

    private void showAndScheduleHide() {
        handler.removeCallbacks(hideRunnable);
        if (!isVisible) {
            animateVisibility(true);
        }
        scheduleHide();
    }

    private void scheduleHide() {
        handler.removeCallbacks(hideRunnable);
        handler.postDelayed(hideRunnable, AUTO_HIDE_DELAY_MS);
    }
}
