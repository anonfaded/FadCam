package com.fadcam.fadrec.ui;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.PointF;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.DragShadowBuilder;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;

import com.fadcam.R;
import com.fadcam.fadrec.ui.annotation.AnnotationState;
import com.fadcam.fadrec.ui.annotation.AnnotationPage;

/**
 * Overlay showing horizontal page tabs at the top of the screen.
 * Professional UI for page management with visual feedback.
 */
public class PageTabBarOverlay {
    private static final String TAG = "PageTabBarOverlay";
    
    private Context context;
    private WindowManager windowManager;
    private View overlayView;
    private LinearLayout tabContainer;
    private AnnotationState state;
    private OnPageActionListener listener;
    private View draggingTabView;
    private int draggingPageIndex = -1;
    private boolean pageDragPerformed = false;
    private WindowManager.LayoutParams layoutParams;
    private int dragTouchSlop;
    private float overlayInitialTouchX;
    private float overlayInitialTouchY;
    private int overlayInitialX;
    private int overlayInitialY;
    private boolean isDraggingOverlay = false;
    private float tabDragDownRawX;
    private float tabDragDownRawY;
    private boolean overlayDimActive = false;
    private int dimRequestCount = 0;
    private int activeDragPageIndex = -1;
    private MinimizableOverlayButton minimizeButton;
    private final View.OnTouchListener overlayDragTouchListener = (view, event) -> {
        if (layoutParams == null || overlayView == null || windowManager == null) {
            return false;
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                overlayInitialX = layoutParams.x;
                overlayInitialY = layoutParams.y;
                overlayInitialTouchX = event.getRawX();
                overlayInitialTouchY = event.getRawY();
                isDraggingOverlay = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                int deltaX = Math.round(event.getRawX() - overlayInitialTouchX);
                int deltaY = Math.round(event.getRawY() - overlayInitialTouchY);
                if (!isDraggingOverlay) {
                    if (Math.abs(deltaX) < dragTouchSlop && Math.abs(deltaY) < dragTouchSlop) {
                        return true;
                    }
                    isDraggingOverlay = true;
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                }

                layoutParams.x = overlayInitialX + deltaX;
                layoutParams.y = overlayInitialY + deltaY;
                clampPosition(layoutParams, getOverlayWidth(), getOverlayHeight());
                windowManager.updateViewLayout(overlayView, layoutParams);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (isDraggingOverlay) {
                    isDraggingOverlay = false;
                    return true;
                }
                return true;
            default:
                return false;
        }
    };
    
    public interface OnPageActionListener {
        void onPageSelected(int index);
        void onPageAdded();
        void onPageDeleted(int index);
        void onPageReordered(int fromIndex, int toIndex);
        default void onPageDragGestureStarted(int index) {}
        default void onPageDragGestureEnded(int index) {}
    }
    
    public PageTabBarOverlay(Context context, AnnotationState state) {
        this.context = context;
        this.state = state;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.dragTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        this.minimizeButton = new MinimizableOverlayButton(context, "Pages");
        this.minimizeButton.setOnButtonActionListener(new MinimizableOverlayButton.OnButtonActionListener() {
            @Override
            public void onExpandRequested() {
                expand();
            }

            @Override
            public void onMinimizeRequested() {
                // Not used - we minimize via button click
            }
        });
    }
    
    public void setOnPageActionListener(OnPageActionListener listener) {
        this.listener = listener;
    }
    
    public void show() {
        if (overlayView != null) {
            hide(); // Remove existing if any
        }
        
        // Inflate layout
        LayoutInflater inflater = LayoutInflater.from(context);
        overlayView = inflater.inflate(R.layout.overlay_page_tab_bar, null);
        
        HorizontalScrollView scrollView = overlayView.findViewById(R.id.scrollViewTabs);
        tabContainer = overlayView.findViewById(R.id.tabContainer);
        TextView btnAddPage = overlayView.findViewById(R.id.btnAddPage);
        TextView btnClose = overlayView.findViewById(R.id.btnCloseTabBar);
        TextView btnMinimize = overlayView.findViewById(R.id.btnMinimizeTabBar);
        View headerDragHandle = overlayView.findViewById(R.id.pageTabDragHandle);
        if (headerDragHandle != null) {
            headerDragHandle.setOnTouchListener(overlayDragTouchListener);
        } else {
            View headerView = overlayView.findViewById(R.id.pageTabHeader);
            if (headerView != null) {
                headerView.setOnTouchListener(overlayDragTouchListener);
            }
        }
        
        // Populate tabs
        updateTabs();
        
        // Add page button
        btnAddPage.setOnClickListener(v -> {
            if (listener != null) {
                listener.onPageAdded();
                updateTabs();
            }
        });
        
        // Minimize button
        if (btnMinimize != null) {
            btnMinimize.setOnClickListener(v -> minimize());
        }
        
        // Close button
        btnClose.setOnClickListener(v -> hide());
        
        // Add to window
        int layoutType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        
    layoutParams = new WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );

    layoutParams.gravity = Gravity.TOP | Gravity.START;
    layoutParams.x = 0;
    // Add status bar height offset so overlay appears below status bar
    layoutParams.y = getStatusBarHeight();

    windowManager.addView(overlayView, layoutParams);
    overlayView.post(this::adjustInitialPosition);
    }
    
    /**
     * Get status bar height to account for safe area
     */
    private int getStatusBarHeight() {
        int statusBarHeight = 0;
        int resourceId = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            statusBarHeight = context.getResources().getDimensionPixelSize(resourceId);
        }
        return statusBarHeight;
    }
    
    public void hide() {
        if (overlayView != null && windowManager != null) {
            if (listener != null && activeDragPageIndex != -1) {
                listener.onPageDragGestureEnded(activeDragPageIndex);
            }
            windowManager.removeView(overlayView);
            overlayView = null;
            layoutParams = null;
            isDraggingOverlay = false;
            overlayDimActive = false;
            dimRequestCount = 0;
            activeDragPageIndex = -1;
            tabDragDownRawX = 0f;
            tabDragDownRawY = 0f;
        }
        
        // Also hide minimize button
        if (minimizeButton != null) {
            minimizeButton.hide();
        }
    }
    
    public boolean isShowing() {
        return overlayView != null;
    }
    
    public void minimize() {
        if (overlayView == null || layoutParams == null || windowManager == null) {
            return;
        }
        
        if (minimizeButton != null && minimizeButton.isShowing()) {
            return; // Already minimized
        }
        
        // Hide the full overlay
        overlayView.setVisibility(View.GONE);
        
        // Show the minimize button at its last remembered position
        if (minimizeButton != null) {
            minimizeButton.show(); // No parameters - uses last position
            minimizeButton.setExpanded(false);
        }
    }
    
    public void expand() {
        if (overlayView == null || layoutParams == null || windowManager == null) {
            return;
        }
        
        if (minimizeButton != null && !minimizeButton.isShowing()) {
            return; // Already expanded
        }
        
        // Get button position and edge before hiding
        int buttonX = 0, buttonY = 0;
        int buttonWidth = 0, buttonHeight = 0;
        MinimizableOverlayButton.EdgePosition edge = MinimizableOverlayButton.EdgePosition.RIGHT;
        
        if (minimizeButton != null && minimizeButton.isShowing()) {
            buttonX = minimizeButton.getCurrentX();
            buttonY = minimizeButton.getCurrentY();
            buttonWidth = minimizeButton.getButtonWidth();
            buttonHeight = minimizeButton.getButtonHeight();
            edge = minimizeButton.getCurrentEdge();
            minimizeButton.hide();
        }
        
        // Show the full overlay
        overlayView.setVisibility(View.VISIBLE);
        
        // Position overlay next to where button was, with gap
        int margin = (int) dpToPx(8f);
        switch (edge) {
            case LEFT:
                layoutParams.x = buttonX + buttonWidth + margin;
                layoutParams.y = buttonY;
                break;
            case RIGHT:
                layoutParams.x = buttonX - getOverlayWidth() - margin;
                layoutParams.y = buttonY;
                break;
            case TOP:
                layoutParams.x = buttonX;
                layoutParams.y = buttonY + buttonHeight + margin;
                break;
            case BOTTOM:
                layoutParams.x = buttonX;
                layoutParams.y = buttonY - getOverlayHeight() - margin;
                break;
            default:
                layoutParams.x = buttonX;
                layoutParams.y = buttonY;
                break;
        }
        
        clampPosition(layoutParams, getOverlayWidth(), getOverlayHeight());
        windowManager.updateViewLayout(overlayView, layoutParams);
    }
    
    public boolean isMinimized() {
        return minimizeButton != null && minimizeButton.isShowing();
    }
    
    public void hideMinimizeButton() {
        if (minimizeButton != null && minimizeButton.isShowing()) {
            minimizeButton.hide();
        }
    }
    
    public void showMinimizeButtonIfMinimized() {
        if (overlayView != null && overlayView.getVisibility() == View.GONE) {
            // Overlay is hidden, so button should be showing
            if (minimizeButton != null && !minimizeButton.isShowing() && layoutParams != null) {
                minimizeButton.show(layoutParams.x, layoutParams.y);
                minimizeButton.setExpanded(false);
            }
        }
    }
    
    private void updateTabs() {
        tabContainer.removeAllViews();
        
        int activeIndex = state.getActivePageIndex();
        
        for (int i = 0; i < state.getPages().size(); i++) {
            AnnotationPage page = state.getPages().get(i);
            boolean isActive = (i == activeIndex);
            
            View tabView = createTabView(page, i, isActive);
            tabContainer.addView(tabView);
        }
    }
    
    private View createTabView(AnnotationPage page, int index, boolean isActive) {
        LayoutInflater inflater = LayoutInflater.from(context);
        View tabView = inflater.inflate(R.layout.item_page_tab, tabContainer, false);
        
        TextView txtPageName = tabView.findViewById(R.id.txtPageName);
        TextView txtPageInfo = tabView.findViewById(R.id.txtPageInfo);
        TextView btnDeletePage = tabView.findViewById(R.id.btnDeletePage);
        TextView btnDragHandle = tabView.findViewById(R.id.btnDragHandle);
        TextView btnRenamePage = tabView.findViewById(R.id.btnRenamePage);
        
        txtPageName.setText(page.getName());
        txtPageInfo.setText(page.getLayers().size() + " layers");
        
        // Highlight active tab
        tabView.setTag(R.id.tag_page_index, index);
        tabView.setTag(R.id.tag_page_active, isActive);
        applyTabBackground(tabView, txtPageName, isActive);
        tabView.setOnDragListener(this::handlePageDragEvent);
        
        // Click to select page
        tabView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onPageSelected(index);
                updateTabs();
            }
        });
        
        // Drag handle
        if (btnDragHandle != null) {
            btnDragHandle.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN || event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    tabDragDownRawX = event.getRawX();
                    tabDragDownRawY = event.getRawY();
                }
                return false;
            });
            btnDragHandle.setOnLongClickListener(v -> {
                startPageDrag(tabView, index);
                return true;
            });
            btnDragHandle.setOnDragListener(this::handlePageDragEvent);
        }
        
        // Rename button
        if (btnRenamePage != null) {
            btnRenamePage.setOnClickListener(v -> {
                // Broadcast rename intent
                Intent intent = new Intent("com.fadcam.fadrec.RENAME_PAGE");
                intent.putExtra("page_index", index);
                intent.putExtra("page_name", page.getName());
                context.sendBroadcast(intent);
            });
        }
        
        // Delete button (only if more than one page)
        if (state.getPages().size() > 1) {
            btnDeletePage.setVisibility(View.VISIBLE);
            btnDeletePage.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onPageDeleted(index);
                    updateTabs();
                }
            });
        } else {
            btnDeletePage.setVisibility(View.GONE);
        }
        
        return tabView;
    }
    
    private void startPageDrag(View tabView, int index) {
        ClipData dragData = ClipData.newPlainText("page_index", String.valueOf(index));
        PointF touchPoint = computeLocalTouchPoint(tabView, tabDragDownRawX, tabDragDownRawY);
        DragShadowBuilder shadowBuilder = new OffsetDragShadowBuilder(tabView, touchPoint);
        draggingTabView = tabView;
        draggingPageIndex = index;
        pageDragPerformed = false;
        activeDragPageIndex = index;
        setOverlayDimActive(true);
        if (listener != null) {
            listener.onPageDragGestureStarted(index);
        }
        tabView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        tabView.animate().scaleX(1.04f).scaleY(1.04f).alpha(0.85f).setDuration(150).start();
        ViewCompat.setElevation(tabView, dpToPx(8f));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            tabView.startDragAndDrop(dragData, shadowBuilder, tabView, 0);
        } else {
            tabView.startDrag(dragData, shadowBuilder, tabView, 0);
        }
    }

    private boolean handlePageDragEvent(View targetView, DragEvent event) {
        View tabView = findTabView(targetView);
        if (tabView == null) {
            return false;
        }

        ClipDescription description = event.getClipDescription();
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                return description != null && description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN);
            case DragEvent.ACTION_DRAG_ENTERED:
                if (tabView != draggingTabView) {
                    applyPageDragTargetState(tabView, true);
                }
                return true;
            case DragEvent.ACTION_DRAG_LOCATION:
                return true;
            case DragEvent.ACTION_DRAG_EXITED:
                if (tabView != draggingTabView) {
                    applyPageDragTargetState(tabView, false);
                }
                return true;
            case DragEvent.ACTION_DROP:
                applyPageDragTargetState(tabView, false);
                int fromIndex = parsePageDragIndex(event.getClipData());
                Object targetTag = tabView.getTag(R.id.tag_page_index);
                if (fromIndex == -1 || !(targetTag instanceof Integer)) {
                    return false;
                }
                int toIndex = (Integer) targetTag;
                if (fromIndex != toIndex) {
                    pageDragPerformed = true;
                    handlePageReorder(fromIndex, toIndex);
                }
                endPageReorderGesture();
                return true;
            case DragEvent.ACTION_DRAG_ENDED:
                applyPageDragTargetState(tabView, false);
                if (event.getLocalState() instanceof View && event.getLocalState() == tabView) {
                    tabView.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(150).start();
                    ViewCompat.setElevation(tabView, 0f);
                    if (!pageDragPerformed) {
                        refresh();
                    }
                    draggingTabView = null;
                    draggingPageIndex = -1;
                    pageDragPerformed = false;
                }
                endPageReorderGesture();
                return true;
            default:
                return false;
        }
    }

    private void handlePageReorder(int fromIndex, int toIndex) {
        if (listener != null) {
            listener.onPageReordered(fromIndex, toIndex);
        } else {
            state.movePage(fromIndex, toIndex);
            refresh();
        }
    }

    private void applyTabBackground(View tabView, TextView nameView, boolean isActive) {
        tabView.setBackgroundResource(isActive ? R.drawable.annotation_page_selected : R.drawable.settings_home_row_bg);
        if (nameView != null) {
            nameView.setTextColor(isActive ? 0xFF4CAF50 : 0xFFFFFFFF);
        }
    }

    private void applyTabBackground(View tabView, boolean isActive) {
        TextView nameView = tabView.findViewById(R.id.txtPageName);
        applyTabBackground(tabView, nameView, isActive);
    }

    private void applyPageDragTargetState(View tabView, boolean isTarget) {
        if (isTarget) {
            tabView.setBackgroundResource(R.drawable.annotation_drag_target_bg);
            TextView nameView = tabView.findViewById(R.id.txtPageName);
            if (nameView != null) {
                nameView.setTextColor(ContextCompat.getColor(context, R.color.annotation_heading_accent));
            }
            ViewCompat.setElevation(tabView, dpToPx(10f));
        } else {
            Object activeTag = tabView.getTag(R.id.tag_page_active);
            boolean isActive = activeTag instanceof Boolean && (Boolean) activeTag;
            applyTabBackground(tabView, isActive);
            if (tabView != draggingTabView) {
                ViewCompat.setElevation(tabView, 0f);
            }
        }
    }

    private View findTabView(View view) {
        View current = view;
        while (current != null && current.getTag(R.id.tag_page_index) == null) {
            if (!(current.getParent() instanceof View)) {
                return null;
            }
            current = (View) current.getParent();
        }
        return current;
    }

    private int parsePageDragIndex(ClipData clipData) {
        if (clipData == null || clipData.getItemCount() == 0) {
            return -1;
        }
        CharSequence text = clipData.getItemAt(0).getText();
        if (text == null) {
            return -1;
        }
        try {
            return Integer.parseInt(text.toString());
        } catch (NumberFormatException ex) {
            return draggingPageIndex;
        }
    }

    private void endPageReorderGesture() {
        if (activeDragPageIndex == -1) {
            return;
        }
        setOverlayDimActive(false);
        if (listener != null) {
            listener.onPageDragGestureEnded(activeDragPageIndex);
        }
        activeDragPageIndex = -1;
        tabDragDownRawX = 0f;
        tabDragDownRawY = 0f;
    }

    private PointF computeLocalTouchPoint(View view, float rawX, float rawY) {
        PointF point = new PointF();
        if (view == null) {
            point.set(0f, 0f);
            return point;
        }

        int width = view.getWidth() > 0 ? view.getWidth() : view.getMeasuredWidth();
        int height = view.getHeight() > 0 ? view.getHeight() : view.getMeasuredHeight();
        if (width <= 0) {
            width = (int) dpToPx(260f);
        }
        if (height <= 0) {
            height = (int) dpToPx(64f);
        }

        if (rawX == 0f && rawY == 0f) {
            point.set(width / 2f, height / 2f);
            return point;
        }

        int[] location = new int[2];
        view.getLocationOnScreen(location);
        float localX = rawX - location[0];
        float localY = rawY - location[1];

        localX = Math.max(0f, Math.min(localX, width));
        localY = Math.max(0f, Math.min(localY, height));
        point.set(localX, localY);
        return point;
    }

    private void setOverlayDimActive(boolean requestActive) {
        if (requestActive) {
            dimRequestCount++;
        } else if (dimRequestCount > 0) {
            dimRequestCount--;
        }

        boolean shouldDim = dimRequestCount > 0;
        if (overlayView == null) {
            overlayDimActive = shouldDim;
            return;
        }

        if (overlayDimActive == shouldDim) {
            return;
        }

        overlayDimActive = shouldDim;
        overlayView.animate().cancel();
        float targetAlpha = shouldDim ? 0.55f : 1f;
        long duration = shouldDim ? 120L : 160L;
        overlayView.animate()
            .alpha(targetAlpha)
            .setDuration(duration)
            .start();
    }

    private static class OffsetDragShadowBuilder extends DragShadowBuilder {
        private final Point touchPoint = new Point();

        OffsetDragShadowBuilder(View view, PointF localTouchPoint) {
            super(view);
            if (localTouchPoint != null) {
                touchPoint.set(Math.round(localTouchPoint.x), Math.round(localTouchPoint.y));
            }
        }

        @Override
        public void onProvideShadowMetrics(Point outShadowSize, Point outShadowTouchPoint) {
            View view = getView();
            if (view == null) {
                outShadowSize.set(0, 0);
                outShadowTouchPoint.set(0, 0);
                return;
            }

            int width = Math.max(1, view.getWidth());
            int height = Math.max(1, view.getHeight());
            outShadowSize.set(width, height);

            int clampedX = Math.max(0, Math.min(touchPoint.x, width));
            int clampedY = Math.max(0, Math.min(touchPoint.y, height));
            outShadowTouchPoint.set(clampedX, clampedY);
        }
    }

    private void adjustInitialPosition() {
        if (overlayView == null || layoutParams == null || windowManager == null) {
            return;
        }

        int overlayWidth = getOverlayWidth();
        int overlayHeight = getOverlayHeight();
        if (overlayWidth == 0 || overlayHeight == 0) {
            overlayView.post(this::adjustInitialPosition);
            return;
        }

        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int screenWidth = metrics.widthPixels;
        int margin = (int) dpToPx(16f);

        layoutParams.x = Math.max(margin, (screenWidth - overlayWidth) / 2);
        layoutParams.y = margin;
        clampPosition(layoutParams, overlayWidth, overlayHeight);
        windowManager.updateViewLayout(overlayView, layoutParams);
    }

    private void clampPosition(WindowManager.LayoutParams params, int overlayWidth, int overlayHeight) {
        if (params == null) {
            return;
        }

        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int screenWidth = metrics.widthPixels;
        int screenHeight = metrics.heightPixels;
        int margin = (int) dpToPx(12f);

        int minX = margin;
        int maxX = screenWidth - overlayWidth - margin;
        if (maxX < minX) {
            maxX = minX;
        }

        int minY = margin;
        int maxY = screenHeight - overlayHeight - margin;
        if (maxY < minY) {
            maxY = minY;
        }

        params.x = Math.max(minX, Math.min(params.x, maxX));
        params.y = Math.max(minY, Math.min(params.y, maxY));
    }

    private int getOverlayWidth() {
        if (overlayView == null) {
            return 0;
        }
        int width = overlayView.getWidth();
        if (width == 0) {
            width = overlayView.getMeasuredWidth();
        }
        if (width == 0) {
            width = (int) dpToPx(320f);
        }
        return width;
    }

    private int getOverlayHeight() {
        if (overlayView == null) {
            return 0;
        }
        int height = overlayView.getHeight();
        if (height == 0) {
            height = overlayView.getMeasuredHeight();
        }
        if (height == 0) {
            height = (int) dpToPx(120f);
        }
        return height;
    }

    private float dpToPx(float dp) {
        return dp * context.getResources().getDisplayMetrics().density;
    }

    public void refresh() {
        if (overlayView != null && tabContainer != null) {
            tabContainer.post(() -> {
                if (overlayView != null) {
                    updateTabs();
                    if (layoutParams != null && windowManager != null) {
                        clampPosition(layoutParams, getOverlayWidth(), getOverlayHeight());
                        windowManager.updateViewLayout(overlayView, layoutParams);
                    }
                }
            });
        }
    }
}
