package com.fadcam.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.HorizontalScrollView;

/**
 * A HorizontalScrollView that prevents a parent ViewPager2 from stealing
 * horizontal swipe gestures. Tracks the initial touch position and only
 * requests the parent to disallow interception once the user drags far
 * enough horizontally — allowing vertical scrolls to pass through to the
 * parent NestedScrollView/ViewPager as normal.
 */
public class TouchSafeHorizontalScrollView extends HorizontalScrollView {

    private float startX, startY;
    private boolean horizontalDragLocked;
    private final int touchSlop;

    public TouchSafeHorizontalScrollView(Context context) {
        super(context);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public TouchSafeHorizontalScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public TouchSafeHorizontalScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN:
                startX = ev.getX();
                startY = ev.getY();
                horizontalDragLocked = false;
                // Don't claim the event yet — let the parent handle it initially
                getParent().requestDisallowInterceptTouchEvent(false);
                break;

            case MotionEvent.ACTION_MOVE:
                float dx = Math.abs(ev.getX() - startX);
                float dy = Math.abs(ev.getY() - startY);

                if (dx > touchSlop && dx > dy) {
                    // User is swiping horizontally — take control from the parent
                    getParent().requestDisallowInterceptTouchEvent(true);
                    horizontalDragLocked = true;
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                horizontalDragLocked = false;
                break;
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_UP ||
            ev.getAction() == MotionEvent.ACTION_CANCEL) {
            getParent().requestDisallowInterceptTouchEvent(false);
            horizontalDragLocked = false;
        }
        return super.onTouchEvent(ev);
    }
}
