package com.fadcam.fadrec.ui;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.HorizontalScrollView;

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
    
    public interface OnPageActionListener {
        void onPageSelected(int index);
        void onPageAdded();
        void onPageDeleted(int index);
    }
    
    public PageTabBarOverlay(Context context, AnnotationState state) {
        this.context = context;
        this.state = state;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
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
        
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = 20; // Small margin from top
        
        windowManager.addView(overlayView, params);
    }
    
    public void hide() {
        if (overlayView != null && windowManager != null) {
            windowManager.removeView(overlayView);
            overlayView = null;
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
        
        txtPageName.setText(page.getName());
        txtPageInfo.setText(page.getLayers().size() + " layers");
        
        // Highlight active tab
        if (isActive) {
            tabView.setBackgroundResource(R.drawable.annotation_page_selected);
            txtPageName.setTextColor(0xFF4CAF50);
        } else {
            tabView.setBackgroundResource(R.drawable.settings_home_row_bg);
            txtPageName.setTextColor(0xFFFFFFFF);
        }
        
        // Click to select page
        tabView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onPageSelected(index);
                updateTabs();
            }
        });
        
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
    
    public void refresh() {
        if (overlayView != null) {
            updateTabs();
        }
    }
}
