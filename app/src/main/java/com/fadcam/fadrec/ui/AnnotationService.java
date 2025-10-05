package com.fadcam.fadrec.ui;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.fadcam.R;
import com.fadcam.fadrec.ui.annotation.AnnotationState;
import com.fadcam.fadrec.ui.annotation.ProjectFileManager;
import com.fadcam.fadrec.ui.annotation.AnnotationPage;
import com.fadcam.fadrec.ui.annotation.AnnotationLayer;
import com.fadcam.fadrec.ui.annotation.TextEditorDialog;
import com.fadcam.fadrec.ui.annotation.ShapePickerDialog;
import com.fadcam.fadrec.ui.annotation.objects.TextObject;
import com.fadcam.fadrec.ui.annotation.objects.ShapeObject;

/**
 * Service that provides floating annotation tools for drawing on screen during recording.
 * Manages the annotation canvas, toolbar overlay, and state persistence with auto-save.
 */
public class AnnotationService extends Service {
    private static final String TAG = "AnnotationService";
    private static final String CHANNEL_ID = "AnnotationServiceChannel";
    private static final int NOTIFICATION_ID = 3003;
    private static final long AUTO_SAVE_INTERVAL = 5000; // 5 seconds backup save
    
    private WindowManager windowManager;
    private AnnotationView annotationView;
    private View toolbarView;
    
    // State management
    private ProjectFileManager projectFileManager;
    private Handler autoSaveHandler;
    private Runnable autoSaveRunnable;
    
    // Professional overlays
    private PageTabBarOverlay pageTabBarOverlay;
    private LayerPanelOverlay layerPanelOverlay;
    
    // Toolbar controls
    private TextView btnUndo, btnRedo;
    private TextView txtUndoCount, txtRedoCount;
    private TextView btnPages, btnLayers;
    private TextView txtPageInfo, txtLayerInfo;
    private TextView btnExpandCollapse;
    private TextView btnCloseAnnotation;
    private View expandableToolsSection;
    private View btnSelectTool, btnPenTool, btnEraserTool, btnTextTool, btnShapeTool;
    private TextView iconSelectTool, iconPenTool, iconEraserTool, iconTextTool, iconShapeTool;
    private View btnColorRed, btnColorBlue, btnColorGreen, btnColorYellow, btnColorWhite, btnColorBlack;
    private View btnWidthThin, btnWidthMedium, btnWidthThick;
    private View btnClearAll;
    private View btnBlackboardToggle;
    private TextView iconBlackboardToggle, labelBlackboardToggle;
    
    // State
    private boolean isExpanded = false;
    
    // Toolbar dragging
    private int toolbarInitialX, toolbarInitialY;
    private float toolbarInitialTouchX, toolbarInitialTouchY;
    
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "AnnotationService created");
        
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());
        
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        
        // Initialize project file manager
        projectFileManager = new ProjectFileManager(this);
        
        setupAnnotationCanvas();
        setupToolbar();
        startAutoSave();
    }
    
    private void setupAnnotationCanvas() {
        annotationView = new AnnotationView(this);
        
        // Create new state (no legacy loading)
        AnnotationState state = new AnnotationState();
        annotationView.setState(state);
        
        // Listen for state changes to update UI
        annotationView.setOnStateChangeListener(() -> {
            updateUndoRedoButtons();
            saveCurrentState(); // Save immediately on every change
        });
        
        int layoutType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        
        params.gravity = Gravity.TOP | Gravity.START;
        
        windowManager.addView(annotationView, params);
        Log.d(TAG, "Annotation canvas added to window with saved state");
    }
    
    private void setupToolbar() {
        LayoutInflater inflater = LayoutInflater.from(this);
        toolbarView = inflater.inflate(R.layout.annotation_toolbar, null);
        
        // Initialize toolbar controls
        btnUndo = toolbarView.findViewById(R.id.btnUndo);
        btnRedo = toolbarView.findViewById(R.id.btnRedo);
        txtUndoCount = toolbarView.findViewById(R.id.txtUndoCount);
        txtRedoCount = toolbarView.findViewById(R.id.txtRedoCount);
        btnPages = toolbarView.findViewById(R.id.btnPages);
        btnLayers = toolbarView.findViewById(R.id.btnLayers);
        txtPageInfo = toolbarView.findViewById(R.id.txtPageInfo);
        txtLayerInfo = toolbarView.findViewById(R.id.txtLayerInfo);
        btnExpandCollapse = toolbarView.findViewById(R.id.btnExpandCollapse);
        btnCloseAnnotation = toolbarView.findViewById(R.id.btnCloseAnnotation);
        expandableToolsSection = toolbarView.findViewById(R.id.expandableToolsSection);
        
        btnSelectTool = toolbarView.findViewById(R.id.btnSelectTool);
        btnPenTool = toolbarView.findViewById(R.id.btnPenTool);
        btnEraserTool = toolbarView.findViewById(R.id.btnEraserTool);
        btnTextTool = toolbarView.findViewById(R.id.btnTextTool);
        btnShapeTool = toolbarView.findViewById(R.id.btnShapeTool);
        iconSelectTool = toolbarView.findViewById(R.id.iconSelectTool);
        iconPenTool = toolbarView.findViewById(R.id.iconPenTool);
        iconEraserTool = toolbarView.findViewById(R.id.iconEraserTool);
        iconTextTool = toolbarView.findViewById(R.id.iconTextTool);
        iconShapeTool = toolbarView.findViewById(R.id.iconShapeTool);
        
        btnColorRed = toolbarView.findViewById(R.id.btnColorRed);
        btnColorBlue = toolbarView.findViewById(R.id.btnColorBlue);
        btnColorGreen = toolbarView.findViewById(R.id.btnColorGreen);
        btnColorYellow = toolbarView.findViewById(R.id.btnColorYellow);
        btnColorWhite = toolbarView.findViewById(R.id.btnColorWhite);
        btnColorBlack = toolbarView.findViewById(R.id.btnColorBlack);
        
        btnWidthThin = toolbarView.findViewById(R.id.btnWidthThin);
        btnWidthMedium = toolbarView.findViewById(R.id.btnWidthMedium);
        btnWidthThick = toolbarView.findViewById(R.id.btnWidthThick);
        
        btnClearAll = toolbarView.findViewById(R.id.btnClearAll);
        btnBlackboardToggle = toolbarView.findViewById(R.id.btnBlackboardToggle);
        iconBlackboardToggle = toolbarView.findViewById(R.id.iconBlackboardToggle);
        labelBlackboardToggle = toolbarView.findViewById(R.id.labelBlackboardToggle);
        
        setupToolbarListeners();
        setupToolbarDragging();
        
        int layoutType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        
        params.gravity = Gravity.TOP | Gravity.END;
        params.x = 20;
        params.y = 100;
        
        windowManager.addView(toolbarView, params);
        Log.d(TAG, "Annotation toolbar added to window");
    }
    
    private void setupToolbarListeners() {
        // Undo button
        btnUndo.setOnClickListener(v -> {
            if (annotationView.canUndo()) {
                annotationView.undo();
                updateUndoRedoButtons();
            }
        });
        
        // Redo button
        btnRedo.setOnClickListener(v -> {
            if (annotationView.canRedo()) {
                annotationView.redo();
                updateUndoRedoButtons();
            }
        });
        
        // Pages button - show page tab bar overlay
        btnPages.setOnClickListener(v -> {
            showPageTabBar();
        });
        
        // Layers button - show layer panel overlay
        btnLayers.setOnClickListener(v -> {
            showLayerPanel();
        });
        
        // Expand/Collapse button
        btnExpandCollapse.setOnClickListener(v -> {
            isExpanded = !isExpanded;
            if (isExpanded) {
                expandableToolsSection.setVisibility(View.VISIBLE);
                btnExpandCollapse.setText("keyboard_arrow_up");
            } else {
                expandableToolsSection.setVisibility(View.GONE);
                btnExpandCollapse.setText("keyboard_arrow_down");
            }
        });
        
        // Close button
        btnCloseAnnotation.setOnClickListener(v -> stopSelf());
        
        // Tool selection
        btnSelectTool.setOnClickListener(v -> {
            boolean newSelectionMode = !annotationView.isSelectionMode();
            annotationView.setSelectionMode(newSelectionMode);
            updateSelectToolHighlight(newSelectionMode);
            if (newSelectionMode) {
                Toast.makeText(this, "👆 Selection mode: Tap to select, drag to move", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "🖊️ Draw mode", Toast.LENGTH_SHORT).show();
            }
        });
        
        btnPenTool.setOnClickListener(v -> {
            annotationView.setPenMode();
            annotationView.setSelectionMode(false); // Exit selection mode
            updateToolSelection(true);
            updateSelectToolHighlight(false);
        });
        
        btnEraserTool.setOnClickListener(v -> {
            annotationView.setEraserMode();
            annotationView.setSelectionMode(false); // Exit selection mode
            updateToolSelection(false);
            updateSelectToolHighlight(false);
        });
        
        btnTextTool.setOnClickListener(v -> {
            showTextEditorDialog();
        });
        
        btnShapeTool.setOnClickListener(v -> {
            showShapePickerDialog();
        });
        
        // Color selection
        btnColorRed.setOnClickListener(v -> {
            annotationView.setColor(0xFFF44336);
            annotationView.setPenMode(); // Switch to pen when selecting color
            updateColorSelection(btnColorRed);
            updateToolSelection(true);
        });
        
        btnColorBlue.setOnClickListener(v -> {
            annotationView.setColor(0xFF2196F3);
            annotationView.setPenMode();
            updateColorSelection(btnColorBlue);
            updateToolSelection(true);
        });
        
        btnColorGreen.setOnClickListener(v -> {
            annotationView.setColor(0xFF4CAF50);
            annotationView.setPenMode();
            updateColorSelection(btnColorGreen);
            updateToolSelection(true);
        });
        
        btnColorYellow.setOnClickListener(v -> {
            annotationView.setColor(0xFFFFEB3B);
            annotationView.setPenMode();
            updateColorSelection(btnColorYellow);
            updateToolSelection(true);
        });
        
        btnColorWhite.setOnClickListener(v -> {
            annotationView.setColor(0xFFFFFFFF);
            annotationView.setPenMode();
            updateColorSelection(btnColorWhite);
            updateToolSelection(true);
        });
        
        btnColorBlack.setOnClickListener(v -> {
            annotationView.setColor(0xFF000000);
            annotationView.setPenMode();
            updateColorSelection(btnColorBlack);
            updateToolSelection(true);
        });
        
        // Stroke width selection
        btnWidthThin.setOnClickListener(v -> {
            annotationView.setStrokeWidth(0);
            updateWidthSelection(btnWidthThin);
        });
        
        btnWidthMedium.setOnClickListener(v -> {
            annotationView.setStrokeWidth(1);
            updateWidthSelection(btnWidthMedium);
        });
        
        btnWidthThick.setOnClickListener(v -> {
            annotationView.setStrokeWidth(2);
            updateWidthSelection(btnWidthThick);
        });
        
        // Clear all
        btnClearAll.setOnClickListener(v -> annotationView.clearAll());
        
        // Blackboard toggle
        btnBlackboardToggle.setOnClickListener(v -> {
            boolean newMode = !annotationView.isBlackboardMode();
            annotationView.setBlackboardMode(newMode);
            updateBlackboardToggle(newMode);
        });
        
        // Set default selections
        updateToolSelection(true); // Pen selected by default
        updateColorSelection(btnColorRed); // Red selected by default
        updateWidthSelection(btnWidthMedium); // Medium width by default
    }
    
    private void setupToolbarDragging() {
        toolbarView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                WindowManager.LayoutParams params = (WindowManager.LayoutParams) toolbarView.getLayoutParams();
                
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        toolbarInitialX = params.x;
                        toolbarInitialY = params.y;
                        toolbarInitialTouchX = event.getRawX();
                        toolbarInitialTouchY = event.getRawY();
                        return true;
                        
                    case MotionEvent.ACTION_MOVE:
                        // Fix inverted horizontal movement (x coordinate)
                        params.x = toolbarInitialX - (int) (event.getRawX() - toolbarInitialTouchX);
                        params.y = toolbarInitialY + (int) (event.getRawY() - toolbarInitialTouchY);
                        windowManager.updateViewLayout(toolbarView, params);
                        return true;
                }
                
                return false;
            }
        });
    }
    
    private void updateToolSelection(boolean isPen) {
        if (isPen) {
            iconPenTool.setTextColor(0xFF4CAF50);
            iconPenTool.setBackgroundResource(R.drawable.annotation_tool_selected_bg);
            iconEraserTool.setTextColor(0xFFFFFFFF);
            iconEraserTool.setBackgroundResource(R.drawable.annotation_tool_bg);
        } else {
            iconPenTool.setTextColor(0xFFFFFFFF);
            iconPenTool.setBackgroundResource(R.drawable.annotation_tool_bg);
            iconEraserTool.setTextColor(0xFFFF9800);
            iconEraserTool.setBackgroundResource(R.drawable.annotation_tool_selected_bg);
        }
    }
    
    private void updateSelectToolHighlight(boolean isSelected) {
        if (isSelected) {
            iconSelectTool.setTextColor(0xFF4CAF50); // Green when active
            iconSelectTool.setBackgroundResource(R.drawable.annotation_tool_selected_bg);
        } else {
            iconSelectTool.setTextColor(0xFF4CAF50); // Green default
            iconSelectTool.setBackgroundResource(R.drawable.annotation_tool_bg);
        }
    }
    
    private void updateColorSelection(View selectedColor) {
        // Reset all color borders
        resetColorBorder(btnColorRed);
        resetColorBorder(btnColorBlue);
        resetColorBorder(btnColorGreen);
        resetColorBorder(btnColorYellow);
        resetColorBorder(btnColorWhite);
        resetColorBorder(btnColorBlack);
        
        // Add thick white border to selected color
        selectedColor.setBackgroundResource(R.drawable.annotation_color_selected);
    }
    
    private void resetColorBorder(View colorView) {
        colorView.setBackgroundResource(R.drawable.annotation_color_circle);
    }
    
    private void updateWidthSelection(View selectedWidth) {
        // Reset all widths
        btnWidthThin.setBackgroundResource(R.drawable.annotation_width_circle);
        btnWidthMedium.setBackgroundResource(R.drawable.annotation_width_circle);
        btnWidthThick.setBackgroundResource(R.drawable.annotation_width_circle);
        
        // Highlight selected width with colored background
        selectedWidth.setBackgroundResource(R.drawable.annotation_width_selected);
    }
    
    private void updateBlackboardToggle(boolean enabled) {
        if (enabled) {
            iconBlackboardToggle.setTextColor(0xFF000000);
            iconBlackboardToggle.setBackgroundColor(0xFFFFFFFF);
            labelBlackboardToggle.setText("Board ON");
        } else {
            iconBlackboardToggle.setTextColor(0xFF9E9E9E);
            iconBlackboardToggle.setBackgroundResource(R.drawable.floating_button_item_bg);
            labelBlackboardToggle.setText("Board");
        }
    }
    
    /**
     * Updates the undo/redo button states based on availability.
     * Enabled buttons have full opacity, disabled buttons are dimmed.
     * Also updates the counters showing available operations.
     */
    private void updateUndoRedoButtons() {
        if (annotationView != null) {
            // Update button states
            boolean canUndo = annotationView.canUndo();
            boolean canRedo = annotationView.canRedo();
            
            btnUndo.setAlpha(canUndo ? 1.0f : 0.5f);
            btnRedo.setAlpha(canRedo ? 1.0f : 0.5f);
            
            // Update counters
            int undoCount = annotationView.getUndoCount();
            int redoCount = annotationView.getRedoCount();
            
            txtUndoCount.setText(String.valueOf(undoCount));
            txtRedoCount.setText(String.valueOf(redoCount));
            
            // Dim counter text when no operations available
            txtUndoCount.setAlpha(canUndo ? 1.0f : 0.5f);
            txtRedoCount.setAlpha(canRedo ? 1.0f : 0.5f);
            
            // Update page and layer info
            updatePageLayerInfo();
        }
    }
    
    /**
     * Updates the page and layer info labels.
     */
    private void updatePageLayerInfo() {
        if (annotationView != null) {
            AnnotationState state = annotationView.getState();
            if (state != null) {
                // Update page info (e.g., "2/3" means page 2 of 3 total)
                int currentPageIndex = state.getActivePageIndex();
                int totalPages = state.getPages().size();
                txtPageInfo.setText((currentPageIndex + 1) + "/" + totalPages);
                
                // Update layer info
                AnnotationPage currentPage = state.getActivePage();
                if (currentPage != null) {
                    int currentLayerIndex = currentPage.getActiveLayerIndex();
                    AnnotationLayer currentLayer = currentPage.getActiveLayer();
                    
                    // Show layer number and lock status
                    String layerText = "L" + (currentLayerIndex + 1);
                    if (currentLayer != null && currentLayer.isLocked()) {
                        layerText += "🔒"; // Add lock emoji when locked
                    }
                    txtLayerInfo.setText(layerText);
                    
                    // Change color based on lock state
                    txtLayerInfo.setTextColor(currentLayer != null && currentLayer.isLocked() ? 
                            0xFFFF5252 : 0xFF2196F3); // Red when locked, blue when unlocked
                }
            }
        }
    }
    
    /**
     * Starts the auto-save timer as a backup (primary save is immediate on changes).
     */
    private void startAutoSave() {
        autoSaveHandler = new Handler(Looper.getMainLooper());
        autoSaveRunnable = new Runnable() {
            @Override
            public void run() {
                saveCurrentState();
                autoSaveHandler.postDelayed(this, AUTO_SAVE_INTERVAL);
            }
        };
        autoSaveHandler.postDelayed(autoSaveRunnable, AUTO_SAVE_INTERVAL);
        Log.d(TAG, "Auto-save started (interval: " + AUTO_SAVE_INTERVAL + "ms)");
    }
    
    /**
     * Saves the current annotation state to persistent storage.
     */
    private void saveCurrentState() {
        if (annotationView != null && projectFileManager != null) {
            AnnotationState state = annotationView.getState();
            if (state != null) {
                boolean success = projectFileManager.autoSave(state);
                if (success) {
                    Log.d(TAG, "State auto-saved to .fadrec format");
                }
            }
        }
    }
    
    /**
     * Show professional page tab bar overlay.
     */
    private void showPageTabBar() {
        if (pageTabBarOverlay != null && pageTabBarOverlay.isShowing()) {
            pageTabBarOverlay.hide();
            return;
        }
        
        // Close layer panel if open
        if (layerPanelOverlay != null && layerPanelOverlay.isShowing()) {
            layerPanelOverlay.hide();
        }
        
        AnnotationState state = annotationView.getState();
        if (state != null) {
            pageTabBarOverlay = new PageTabBarOverlay(this, state);
            pageTabBarOverlay.setOnPageActionListener(new PageTabBarOverlay.OnPageActionListener() {
                @Override
                public void onPageSelected(int index) {
                    annotationView.switchToPage(index);
                    updateUndoRedoButtons();
                    pageTabBarOverlay.refresh();
                    Toast.makeText(AnnotationService.this, "📄 " + state.getPages().get(index).getName(), Toast.LENGTH_SHORT).show();
                }
                
                @Override
                public void onPageAdded() {
                    int newPageNumber = state.getPages().size() + 1;
                    String pageName = "Page " + newPageNumber;
                    annotationView.addPage(pageName);
                    annotationView.switchToPage(state.getPages().size() - 1);
                    updateUndoRedoButtons();
                    Toast.makeText(AnnotationService.this, "✨ Created: " + pageName, Toast.LENGTH_SHORT).show();
                }
                
                @Override
                public void onPageDeleted(int index) {
                    if (state.getPages().size() > 1) {
                        String pageName = state.getPages().get(index).getName();
                        state.removePage(index);
                        annotationView.invalidate();
                        updateUndoRedoButtons();
                        pageTabBarOverlay.refresh();
                        Toast.makeText(AnnotationService.this, "🗑️ Deleted: " + pageName, Toast.LENGTH_SHORT).show();
                    }
                }
            });
            pageTabBarOverlay.show();
        }
    }
    
    /**
     * Show professional layer panel overlay.
     */
    private void showLayerPanel() {
        if (layerPanelOverlay != null && layerPanelOverlay.isShowing()) {
            layerPanelOverlay.hide();
            return;
        }
        
        // Close page tab bar if open
        if (pageTabBarOverlay != null && pageTabBarOverlay.isShowing()) {
            pageTabBarOverlay.hide();
        }
        
        AnnotationState state = annotationView.getState();
        if (state != null) {
            AnnotationPage currentPage = state.getActivePage();
            if (currentPage != null) {
                layerPanelOverlay = new LayerPanelOverlay(this, currentPage);
                layerPanelOverlay.setOnLayerActionListener(new LayerPanelOverlay.OnLayerActionListener() {
                    @Override
                    public void onLayerSelected(int index) {
                        currentPage.setActiveLayerIndex(index);
                        annotationView.invalidate();
                        updatePageLayerInfo();
                        layerPanelOverlay.refresh();
                        Toast.makeText(AnnotationService.this, "🎨 " + currentPage.getLayers().get(index).getName(), Toast.LENGTH_SHORT).show();
                    }
                    
                    @Override
                    public void onLayerVisibilityChanged(int index, boolean visible) {
                        currentPage.getLayers().get(index).setVisible(visible);
                        annotationView.invalidate();
                        String msg = visible ? "👁️ Visible" : "🚫 Hidden";
                        Toast.makeText(AnnotationService.this, msg, Toast.LENGTH_SHORT).show();
                    }
                    
                    @Override
                    public void onLayerLockChanged(int index, boolean locked) {
                        currentPage.getLayers().get(index).setLocked(locked);
                        updatePageLayerInfo();
                        String msg = locked ? "🔒 Locked" : "🔓 Unlocked";
                        Toast.makeText(AnnotationService.this, msg, Toast.LENGTH_SHORT).show();
                    }
                    
                    @Override
                    public void onLayerOpacityChanged(int index, float opacity) {
                        currentPage.getLayers().get(index).setOpacity(opacity);
                        annotationView.invalidate();
                    }
                    
                    @Override
                    public void onLayerAdded() {
                        int newLayerNumber = currentPage.getLayers().size() + 1;
                        String layerName = "Layer " + newLayerNumber;
                        annotationView.addLayer(layerName);
                        updatePageLayerInfo();
                        layerPanelOverlay.refresh();
                        Toast.makeText(AnnotationService.this, "✨ Created: " + layerName, Toast.LENGTH_SHORT).show();
                    }
                    
                    @Override
                    public void onLayerDeleted(int index) {
                        if (currentPage.getLayers().size() > 1) {
                            String layerName = currentPage.getLayers().get(index).getName();
                            currentPage.removeLayer(index);
                            annotationView.invalidate();
                            updatePageLayerInfo();
                            layerPanelOverlay.refresh();
                            Toast.makeText(AnnotationService.this, "🗑️ Deleted: " + layerName, Toast.LENGTH_SHORT).show();
                        }
                    }
                });
                layerPanelOverlay.show();
            }
        }
    }
    
    /**
     * Cycle to the next page (wraps around).
     * @deprecated Use showPageTabBar() for professional UI
     */
    private void cycleToNextPage() {
        if (annotationView != null) {
            AnnotationState state = annotationView.getState();
            if (state != null) {
                int currentIndex = state.getActivePageIndex();
                int totalPages = state.getPages().size();
                int nextIndex = (currentIndex + 1) % totalPages;
                
                annotationView.switchToPage(nextIndex);
                updateUndoRedoButtons();
                
                AnnotationPage page = state.getPages().get(nextIndex);
                String message = "📄 " + page.getName() + " (" + (nextIndex + 1) + "/" + totalPages + ")";
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
                Log.d(TAG, "Switched to page: " + page.getName() + " (" + (nextIndex + 1) + "/" + totalPages + ")");
            }
        }
    }
    
    /**
     * Toggle active layer lock state.
     */
    private void toggleLayerLock() {
        if (annotationView != null) {
            AnnotationState state = annotationView.getState();
            if (state != null) {
                AnnotationPage currentPage = state.getActivePage();
                if (currentPage != null) {
                    AnnotationLayer currentLayer = currentPage.getActiveLayer();
                    if (currentLayer != null) {
                        boolean newLockState = !currentLayer.isLocked();
                        currentLayer.setLocked(newLockState);
                        annotationView.invalidate();
                        updatePageLayerInfo(); // Update the lock indicator
                        
                        String lockIcon = newLockState ? "🔒" : "🔓";
                        String message = lockIcon + " " + currentLayer.getName() + " " + (newLockState ? "Locked" : "Unlocked");
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
                        Log.d(TAG, "Layer " + currentLayer.getName() + " locked: " + newLockState);
                    }
                }
            }
        }
    }
    
    /**
     * Add a new page.
     */
    private void addNewPage() {
        if (annotationView != null) {
            AnnotationState state = annotationView.getState();
            if (state != null) {
                int newPageNumber = state.getPages().size() + 1;
                String pageName = "Page " + newPageNumber;
                annotationView.addPage(pageName);
                
                // Switch to the new page
                annotationView.switchToPage(state.getPages().size() - 1);
                updateUndoRedoButtons();
                
                String message = "✨ Created: " + pageName;
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
                Log.d(TAG, "Added new page: " + pageName);
            }
        }
    }
    
    /**
     * Add a new layer to the current page.
     */
    private void addNewLayer() {
        if (annotationView != null) {
            AnnotationState state = annotationView.getState();
            if (state != null) {
                AnnotationPage currentPage = state.getActivePage();
                if (currentPage != null) {
                    int newLayerNumber = currentPage.getLayers().size() + 1;
                    String layerName = "Layer " + newLayerNumber;
                    annotationView.addLayer(layerName);
                    updatePageLayerInfo(); // Update the layer counter
                    
                    String message = "✨ Created: " + layerName;
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
                    Log.d(TAG, "Added new layer: " + layerName);
                }
            }
        }
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Annotation Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows annotation controls are active");
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
    
    private Notification createNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Annotations Active")
                .setContentText("Draw on screen during recording")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "AnnotationService destroyed");
        
        // Stop auto-save timer
        if (autoSaveHandler != null && autoSaveRunnable != null) {
            autoSaveHandler.removeCallbacks(autoSaveRunnable);
            Log.d(TAG, "Auto-save stopped");
        }
        
        // Final save before shutdown
        saveCurrentState();
        
        // Clean up overlays
        if (pageTabBarOverlay != null) {
            pageTabBarOverlay.hide();
        }
        if (layerPanelOverlay != null) {
            layerPanelOverlay.hide();
        }
        
        // Clean up views
        if (annotationView != null) {
            windowManager.removeView(annotationView);
        }
        
        if (toolbarView != null) {
            windowManager.removeView(toolbarView);
        }
    }
    
    /**
     * Show text editor dialog to add text objects
     */
    private void showTextEditorDialog() {
        TextEditorDialog dialog = new TextEditorDialog(this);
        dialog.setOnTextConfirmedListener((text, fontSize, color, bold, italic, alignment) -> {
            // Create text object at center of screen
            float centerX = annotationView.getWidth() / 2f;
            float centerY = annotationView.getHeight() / 2f;
            
            TextObject textObject = new TextObject(text, centerX, centerY);
            textObject.setFontSize(fontSize);
            textObject.setTextColor(color);
            textObject.setBold(bold);
            textObject.setItalic(italic);
            textObject.setAlignment(alignment);
            
            // Add to active layer
            AnnotationPage currentPage = annotationView.getState().getActivePage();
            if (currentPage != null) {
                AnnotationLayer activeLayer = currentPage.getActiveLayer();
                if (activeLayer != null && !activeLayer.isLocked()) {
                    activeLayer.addObject(textObject);
                    annotationView.invalidate();
                    saveCurrentState();
                    Toast.makeText(this, "✏️ Text added!", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Layer is locked", Toast.LENGTH_SHORT).show();
                }
            }
        });
        dialog.show();
    }
    
    /**
     * Show shape picker dialog to select shape type
     */
    private void showShapePickerDialog() {
        ShapePickerDialog dialog = new ShapePickerDialog(this);
        dialog.setOnShapeSelectedListener((shapeType, color, filled) -> {
            // Create shape object at center of screen with default size
            float centerX = annotationView.getWidth() / 2f;
            float centerY = annotationView.getHeight() / 2f;
            float size = 200f;
            
            ShapeObject shapeObject = new ShapeObject(
                shapeType,
                centerX - size/2,
                centerY - size/2,
                centerX + size/2,
                centerY + size/2
            );
            
            shapeObject.setFillColor(filled ? (color & 0x00FFFFFF) | 0x80000000 : 0x00000000); // Semi-transparent fill
            shapeObject.setStrokeColor(color);
            shapeObject.setStrokeWidth(4f);
            shapeObject.setFilled(filled);
            
            // Add to active layer
            AnnotationPage currentPage = annotationView.getState().getActivePage();
            if (currentPage != null) {
                AnnotationLayer activeLayer = currentPage.getActiveLayer();
                if (activeLayer != null && !activeLayer.isLocked()) {
                    activeLayer.addObject(shapeObject);
                    annotationView.invalidate();
                    saveCurrentState();
                    Toast.makeText(this, "📐 Shape added!", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Layer is locked", Toast.LENGTH_SHORT).show();
                }
            }
        });
        dialog.show();
    }
}
