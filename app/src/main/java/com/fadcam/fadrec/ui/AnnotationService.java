package com.fadcam.fadrec.ui;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
    private String currentProjectName; // Track current project to avoid creating new files
    
    // Professional overlays
    private PageTabBarOverlay pageTabBarOverlay;
    private LayerPanelOverlay layerPanelOverlay;
    
    // Unified toolbar controls
    private TextView btnExpandCollapse;
    private TextView btnCloseAnnotation;
    private View expandableContent;
    
    // Recording controls
    private View btnStartStopRec, btnPauseResumeRec;
    private TextView iconStartStop, labelStartStop;
    private TextView iconPauseResume, labelPauseResume;
    private com.fadcam.fadrec.ScreenRecordingState recordingState = com.fadcam.fadrec.ScreenRecordingState.NONE;
    
    // Annotation tools section
    private View annotationsHeader;
    private TextView annotationsExpandIcon;
    private View annotationsContent;
    private androidx.appcompat.widget.SwitchCompat snapGuidesSwitch;
    private boolean isAnnotationsExpanded = false;
    
    // Annotation toolbar controls
    private TextView btnUndo, btnRedo;
    private TextView txtUndoCount, txtRedoCount;
    private TextView btnPages, btnLayers;
    private TextView txtPageInfo, txtLayerInfo;
    private View btnSelectTool, btnPenTool, btnEraserTool, btnTextTool, btnShapeTool;
    private TextView iconSelectTool, iconPenTool, iconEraserTool, iconTextTool, iconShapeTool;
    private View btnColorRed, btnColorBlue, btnColorGreen, btnColorYellow, btnColorWhite, btnColorBlack;
    private View btnWidthThin, btnWidthMedium, btnWidthThick;
    private View btnClearAll;
    private View btnBlackboardToggle;
    private TextView iconBlackboardToggle, labelBlackboardToggle;
    
    // State
    private boolean isExpanded = false;
    
    // Broadcast receiver for floating menu actions
    private BroadcastReceiver menuActionReceiver;
    
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
        
        // Generate single project name for this session (or load last project)
        currentProjectName = projectFileManager.getOrCreateCurrentProject();
        
        setupAnnotationCanvas();
        setupToolbar();
        startAutoSave();
        registerMenuActionReceiver();
        registerRecordingStateReceiver();
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
        
        // Listen for selection mode changes to update toolbar
        annotationView.setOnSelectionModeChangeListener(isActive -> {
            updateSelectToolHighlight(isActive);
        });
        
        // Listen for text edit requests
        annotationView.setOnTextEditRequestListener(textObject -> {
            showTextEditorDialog(textObject);
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
        toolbarView = inflater.inflate(R.layout.annotation_toolbar_unified, null);
        
        // Initialize main expand/collapse and close buttons
        btnExpandCollapse = toolbarView.findViewById(R.id.btnExpandCollapse);
        btnCloseAnnotation = toolbarView.findViewById(R.id.btnCloseAnnotation);
        expandableContent = toolbarView.findViewById(R.id.expandableContent);
        
        // Initialize recording controls
        btnStartStopRec = toolbarView.findViewById(R.id.btnStartStopRec);
        btnPauseResumeRec = toolbarView.findViewById(R.id.btnPauseResumeRec);
        iconStartStop = toolbarView.findViewById(R.id.iconStartStop);
        labelStartStop = toolbarView.findViewById(R.id.labelStartStop);
        iconPauseResume = toolbarView.findViewById(R.id.iconPauseResume);
        labelPauseResume = toolbarView.findViewById(R.id.labelPauseResume);
        
        // Initialize annotations section
        annotationsHeader = toolbarView.findViewById(R.id.annotationsHeader);
        annotationsExpandIcon = toolbarView.findViewById(R.id.annotationsExpandIcon);
        annotationsContent = toolbarView.findViewById(R.id.annotationsContent);
        snapGuidesSwitch = toolbarView.findViewById(R.id.snapGuidesSwitch);
        
        // Initialize annotation toolbar controls
        btnUndo = toolbarView.findViewById(R.id.btnUndo);
        btnRedo = toolbarView.findViewById(R.id.btnRedo);
        txtUndoCount = toolbarView.findViewById(R.id.txtUndoCount);
        txtRedoCount = toolbarView.findViewById(R.id.txtRedoCount);
        btnPages = toolbarView.findViewById(R.id.btnPages);
        btnLayers = toolbarView.findViewById(R.id.btnLayers);
        txtPageInfo = toolbarView.findViewById(R.id.txtPageInfo);
        txtLayerInfo = toolbarView.findViewById(R.id.txtLayerInfo);
        
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
        
        // Main Expand/Collapse button (toggles entire expandable content)
        btnExpandCollapse.setOnClickListener(v -> {
            isExpanded = !isExpanded;
            if (isExpanded) {
                expandableContent.setVisibility(View.VISIBLE);
                btnExpandCollapse.setText("chevron_left");
            } else {
                expandableContent.setVisibility(View.GONE);
                btnExpandCollapse.setText("chevron_right");
            }
        });
        
        // Close button - stops annotation service completely
        btnCloseAnnotation.setOnClickListener(v -> {
            // Save state before closing
            saveCurrentState();
            
            // Stop the service (this will trigger onDestroy)
            stopSelf();
            
            Toast.makeText(this, "Annotations closed", Toast.LENGTH_SHORT).show();
        });
        
        // Annotations section header (toggles annotation tools)
        annotationsHeader.setOnClickListener(v -> {
            isAnnotationsExpanded = !isAnnotationsExpanded;
            if (isAnnotationsExpanded) {
                annotationsContent.setVisibility(View.VISIBLE);
                annotationsExpandIcon.setText("expand_less");
            } else {
                annotationsContent.setVisibility(View.GONE);
                annotationsExpandIcon.setText("expand_more");
            }
        });
        
        // Snap guides switch
        snapGuidesSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (annotationView != null) {
                annotationView.setSnapGuidesEnabled(isChecked);
                Log.d(TAG, "Snap guides " + (isChecked ? "enabled" : "disabled"));
            }
        });
        
        // Recording controls
        btnStartStopRec.setOnClickListener(v -> {
            if (btnStartStopRec.isEnabled()) {
                if (recordingState == com.fadcam.fadrec.ScreenRecordingState.NONE) {
                    sendBroadcast(new Intent(com.fadcam.Constants.ACTION_START_SCREEN_RECORDING_FROM_OVERLAY));
                } else {
                    sendBroadcast(new Intent(com.fadcam.Constants.ACTION_STOP_SCREEN_RECORDING));
                }
            }
        });
        
        btnPauseResumeRec.setOnClickListener(v -> {
            if (btnPauseResumeRec.isEnabled()) {
                if (recordingState == com.fadcam.fadrec.ScreenRecordingState.IN_PROGRESS) {
                    sendBroadcast(new Intent(com.fadcam.Constants.ACTION_PAUSE_SCREEN_RECORDING));
                } else if (recordingState == com.fadcam.fadrec.ScreenRecordingState.PAUSED) {
                    sendBroadcast(new Intent(com.fadcam.Constants.ACTION_RESUME_SCREEN_RECORDING));
                }
            }
        });
        
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
        if (annotationView != null && projectFileManager != null && currentProjectName != null) {
            AnnotationState state = annotationView.getState();
            if (state != null) {
                boolean success = projectFileManager.saveProject(state, currentProjectName);
                if (success) {
                    Log.d(TAG, "State saved to: " + currentProjectName + ".fadrec");
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
    
    /**
     * Register broadcast receiver for floating menu actions
     */
    private void registerMenuActionReceiver() {
        menuActionReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action == null) return;
                
                switch (action) {
                    case "com.fadcam.fadrec.TOGGLE_SNAP_GUIDES":
                        boolean enabled = intent.getBooleanExtra("enabled", true);
                        if (annotationView != null) {
                            annotationView.setSnapGuidesEnabled(enabled);
                            Log.d(TAG, "Snap guides " + (enabled ? "enabled" : "disabled"));
                        }
                        break;
                        
                    case "com.fadcam.fadrec.ADD_TEXT":
                        showTextEditorDialog();
                        break;
                        
                    case "com.fadcam.fadrec.ADD_SHAPE":
                        showShapePickerDialog();
                        break;
                }
            }
        };
        
        IntentFilter filter = new IntentFilter();
        filter.addAction("com.fadcam.fadrec.TOGGLE_SNAP_GUIDES");
        filter.addAction("com.fadcam.fadrec.ADD_TEXT");
        filter.addAction("com.fadcam.fadrec.ADD_SHAPE");
        registerReceiver(menuActionReceiver, filter);
        Log.d(TAG, "Menu action receiver registered");
    }
    
    /**
     * Register broadcast receiver for recording state updates
     */
    private BroadcastReceiver recordingStateReceiver;
    
    private void registerRecordingStateReceiver() {
        recordingStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String stateStr = intent.getStringExtra("recordingState");
                if (stateStr != null) {
                    try {
                        recordingState = com.fadcam.fadrec.ScreenRecordingState.valueOf(stateStr);
                        updateRecordingButtons();
                        Log.d(TAG, "Recording state updated: " + recordingState);
                    } catch (IllegalArgumentException e) {
                        Log.e(TAG, "Invalid state: " + stateStr, e);
                    }
                }
            }
        };
        
        IntentFilter filter = new IntentFilter(com.fadcam.Constants.BROADCAST_ON_SCREEN_RECORDING_STATE_CALLBACK);
        registerReceiver(recordingStateReceiver, filter);
        Log.d(TAG, "Recording state receiver registered");
    }
    
    private void updateRecordingButtons() {
        if (btnStartStopRec == null) return;
        
        switch (recordingState) {
            case NONE:
                btnStartStopRec.setEnabled(true);
                iconStartStop.setText("fiber_manual_record");
                iconStartStop.setTextColor(getResources().getColor(android.R.color.holo_green_light));
                labelStartStop.setText(R.string.floating_menu_start_short);
                
                btnPauseResumeRec.setEnabled(false);
                iconPauseResume.setText("pause");
                iconPauseResume.setTextColor(getResources().getColor(android.R.color.darker_gray));
                labelPauseResume.setText(R.string.floating_menu_pause);
                break;
                
            case IN_PROGRESS:
                btnStartStopRec.setEnabled(true);
                iconStartStop.setText("stop");
                iconStartStop.setTextColor(getResources().getColor(android.R.color.holo_red_light));
                labelStartStop.setText(R.string.floating_menu_stop_short);
                
                btnPauseResumeRec.setEnabled(true);
                iconPauseResume.setText("pause");
                iconPauseResume.setTextColor(getResources().getColor(android.R.color.holo_orange_light));
                labelPauseResume.setText(R.string.floating_menu_pause);
                break;
                
            case PAUSED:
                btnStartStopRec.setEnabled(true);
                iconStartStop.setText("stop");
                iconStartStop.setTextColor(getResources().getColor(android.R.color.holo_red_light));
                labelStartStop.setText(R.string.floating_menu_stop_short);
                
                btnPauseResumeRec.setEnabled(true);
                iconPauseResume.setText("play_arrow");
                iconPauseResume.setTextColor(getResources().getColor(android.R.color.holo_green_light));
                labelPauseResume.setText(R.string.floating_menu_resume);
                break;
        }
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "AnnotationService destroyed");
        
        // Unregister broadcast receivers
        if (menuActionReceiver != null) {
            unregisterReceiver(menuActionReceiver);
        }
        if (recordingStateReceiver != null) {
            unregisterReceiver(recordingStateReceiver);
        }
        
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
        showTextEditorDialog(null);
    }
    
    /**
     * Show text editor dialog to add or edit text objects
     */
    private void showTextEditorDialog(TextObject existingTextObject) {
        TextEditorDialog dialog = new TextEditorDialog(this);
        
        // Pre-fill dialog if editing existing text
        if (existingTextObject != null) {
            dialog.setText(existingTextObject.getText());
            dialog.setFontSize(existingTextObject.getFontSize());
            dialog.setColor(existingTextObject.getTextColor());
            dialog.setBold(existingTextObject.isBold());
            dialog.setItalic(existingTextObject.isItalic());
            dialog.setAlignment(existingTextObject.getAlignment());
        }
        
        dialog.setOnTextConfirmedListener((text, fontSize, color, bold, italic, alignment) -> {
            if (existingTextObject != null) {
                // Update existing text object
                existingTextObject.setText(text);
                existingTextObject.setFontSize(fontSize);
                existingTextObject.setTextColor(color);
                existingTextObject.setBold(bold);
                existingTextObject.setItalic(italic);
                existingTextObject.setAlignment(alignment);
                annotationView.invalidate();
                saveCurrentState();
                Toast.makeText(this, "✏️ Text updated!", Toast.LENGTH_SHORT).show();
            } else {
                // Create new text object at center of screen
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
