package com.fadcam.fadrec.ui;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
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
                return false;
            case MotionEvent.ACTION_MOVE:
                int deltaX = Math.round(event.getRawX() - overlayInitialTouchX);
                int deltaY = Math.round(event.getRawY() - overlayInitialTouchY);
                if (!isDraggingOverlay) {
                    if (Math.abs(deltaX) < dragTouchSlop && Math.abs(deltaY) < dragTouchSlop) {
                        return false;
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
                return false;
            default:
                return false;
        }
    };
    
    public interface OnPageActionListener {
        void onPageSelected(int index);
        void onPageAdded();
        void onPageDeleted(int index);
        void onPageReordered(int fromIndex, int toIndex);
    }
    
    public PageTabBarOverlay(Context context, AnnotationState state) {
        this.context = context;
        this.state = state;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.dragTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
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
        View headerView = overlayView.findViewById(R.id.pageTabHeader);
        if (headerView != null) {
            headerView.setOnTouchListener(overlayDragTouchListener);
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
    layoutParams.y = 0;

    windowManager.addView(overlayView, layoutParams);
    overlayView.post(this::adjustInitialPosition);
    }
    
    public void hide() {
        if (overlayView != null && windowManager != null) {
            windowManager.removeView(overlayView);
            overlayView = null;
            layoutParams = null;
            isDraggingOverlay = false;
        }
    }
    
    public boolean isShowing() {
        return overlayView != null;
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
        DragShadowBuilder shadowBuilder = new DragShadowBuilder(tabView);
        draggingTabView = tabView;
        draggingPageIndex = index;
        pageDragPerformed = false;
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
