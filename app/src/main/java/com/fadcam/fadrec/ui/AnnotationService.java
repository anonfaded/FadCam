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
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.GridLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.io.File;
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
    private View btnColorRed, btnColorBlue, btnColorGreen, btnColorYellow, btnColorWhite, btnColorBlack, btnColorPicker;
    private View btnWidthThin, btnWidthMedium, btnWidthThick, btnWidthExtraThick;
    private View btnClearAll;
    private View btnBoardNone, btnBoardBlack, btnBoardWhite;
    private TextView iconBoardNone, iconBoardBlack, iconBoardWhite;
    
    // State management
    private boolean isExpanded = false;
    private boolean overlayVisible = true;      // Controls if arrow overlay is shown
    private boolean annotationEnabled = false;  // Controls if drawing is active (DEFAULT: OFF)
    private boolean canvasHidden = false;       // Controls if canvas drawings are hidden (except pinned layers)
    
    // Annotation control buttons
    private View btnToggleAnnotation;
    private View btnToggleCanvasVisibility;
    
    // Window params for annotation canvas (need to update flags dynamically)
    private WindowManager.LayoutParams annotationCanvasParams;
    
    // Broadcast receiver for floating menu actions
    private BroadcastReceiver menuActionReceiver;
    private BroadcastReceiver recordingStateReceiver;
    private BroadcastReceiver colorPickerReceiver;
    
    // Toolbar dragging
    private int toolbarInitialX, toolbarInitialY;
    private float toolbarInitialTouchX, toolbarInitialTouchY;
    
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            switch (action) {
                case "ACTION_TOGGLE_MENU":
                    toggleMenu();
                    break;
                case "ACTION_OPEN_PROJECTS":
                    showProjectManagementDialog();
                    break;
            }
        }
        return START_STICKY;
    }
    
    private void toggleMenu() {
        // Toggle overlay visibility (show/hide everything)
        if (overlayVisible) {
            hideOverlay();
        } else {
            showOverlay();
        }
    }
    
    /**
     * Show project management dialog
     */
    private void showProjectManagementDialog() {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert);
        
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_project_management, null);
        builder.setView(dialogView);
        
        android.app.AlertDialog dialog = builder.create();
        
        // Set dialog window type for overlay
        if (dialog.getWindow() != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
            } else {
                dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
            }
        }
        
        // Setup project list and buttons
        android.widget.ListView projectList = dialogView.findViewById(R.id.projectList);
        View btnNewProject = dialogView.findViewById(R.id.btnNewProject);
        View btnClose = dialogView.findViewById(R.id.btnCloseDialog);
        TextView txtCurrentProject = dialogView.findViewById(R.id.txtCurrentProject);
        
        // Display current project
        txtCurrentProject.setText("Current: " + (currentProjectName != null ? currentProjectName : "Untitled"));
        
        // Get projects directory from ProjectFileManager
        File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
        File fadrecDir = new File(documentsDir, "FadRec");
        File projectsDir = new File(fadrecDir, "Projects");
        
        // Ensure directory exists
        if (!projectsDir.exists()) {
            projectsDir.mkdirs();
        }
        
        // Load all .fadrec projects
        File[] projectFiles = projectsDir.listFiles((dir, name) -> name.endsWith(".fadrec"));
        
        java.util.List<String> projectNames = new java.util.ArrayList<>();
        if (projectFiles != null && projectFiles.length > 0) {
            for (File file : projectFiles) {
                projectNames.add(file.getName().replace(".fadrec", ""));
            }
            // Sort by name
            java.util.Collections.sort(projectNames, java.util.Collections.reverseOrder());
        } else {
            projectNames.add("(No saved projects yet)");
        }
        
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1, projectNames
        );
        projectList.setAdapter(adapter);
        
        // Handle project selection
        projectList.setOnItemClickListener((parent, view, position, id) -> {
            String selectedProject = projectNames.get(position);
            if (!selectedProject.equals("(No saved projects yet)")) {
                loadProject(selectedProject);
                Toast.makeText(this, "Loaded: " + selectedProject, Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            }
        });
        
        // New project button
        btnNewProject.setOnClickListener(v -> {
            createNewProject();
            dialog.dismiss();
        });
        
        btnClose.setOnClickListener(v -> dialog.dismiss());
        
        dialog.show();
    }
    
    private void loadProject(String projectName) {
        // Save current project first
        saveCurrentState();
        
        // Load new project
        currentProjectName = projectName;
        AnnotationState loadedState = projectFileManager.loadProject(projectName);
        
        if (loadedState != null && annotationView != null) {
            annotationView.setState(loadedState);
            updateUndoRedoButtons();
            updatePageLayerInfo();
            Toast.makeText(this, "✅ Loaded: " + projectName, Toast.LENGTH_SHORT).show();
        }
    }
    
    private void createNewProject() {
        // Save current first
        saveCurrentState();
        
        // Generate new project name with timestamp
        String newProjectName = "Project_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(new java.util.Date());
        currentProjectName = newProjectName;
        
        // Create fresh state
        AnnotationState newState = new AnnotationState();
        if (annotationView != null) {
            annotationView.setState(newState);
            updateUndoRedoButtons();
            updatePageLayerInfo();
        }
        
        // Save the new project (state first, then name)
        projectFileManager.saveProject(newState, currentProjectName);
        
        Toast.makeText(this, "📝 New Project: " + newProjectName, Toast.LENGTH_SHORT).show();
        Log.d(TAG, "Created new project: " + newProjectName);
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
        registerColorPickerReceiver();
    }
    
    private void setupAnnotationCanvas() {
        annotationView = new AnnotationView(this);
        
        // IMPORTANT: Disable annotation by default so user doesn't see accidental drawings
        annotationView.setEnabled(false);
        
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
        
        annotationCanvasParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, // Start with NOT_TOUCHABLE since annotation starts disabled
                PixelFormat.TRANSLUCENT
        );
        
        annotationCanvasParams.gravity = Gravity.TOP | Gravity.START;
        
        windowManager.addView(annotationView, annotationCanvasParams);
        Log.d(TAG, "Annotation canvas added to window with saved state");
    }
    
    private void setupToolbar() {
        LayoutInflater inflater = LayoutInflater.from(this);
        toolbarView = inflater.inflate(R.layout.annotation_toolbar_unified, null);
        
        // Initialize main expand/collapse and close buttons
        btnExpandCollapse = toolbarView.findViewById(R.id.btnExpandCollapse);
        btnCloseAnnotation = toolbarView.findViewById(R.id.btnCloseAnnotation);
        expandableContent = toolbarView.findViewById(R.id.expandableContent);
        
        // Initialize Quick Access buttons
        btnToggleAnnotation = toolbarView.findViewById(R.id.btnToggleAnnotation);
        btnToggleCanvasVisibility = toolbarView.findViewById(R.id.btnToggleCanvasVisibility);
        
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
        btnColorPicker = toolbarView.findViewById(R.id.btnColorPicker);
        
        btnWidthThin = toolbarView.findViewById(R.id.btnWidthThin);
        btnWidthMedium = toolbarView.findViewById(R.id.btnWidthMedium);
        btnWidthThick = toolbarView.findViewById(R.id.btnWidthThick);
        btnWidthExtraThick = toolbarView.findViewById(R.id.btnWidthExtraThick);
        
        btnClearAll = toolbarView.findViewById(R.id.btnClearAll);
        
        // Board tool icons (new design)
        btnBoardNone = toolbarView.findViewById(R.id.btnBoardNone);
        btnBoardBlack = toolbarView.findViewById(R.id.btnBoardBlack);
        btnBoardWhite = toolbarView.findViewById(R.id.btnBoardWhite);
        iconBoardNone = toolbarView.findViewById(R.id.iconBoardNone);
        iconBoardBlack = toolbarView.findViewById(R.id.iconBoardBlack);
        iconBoardWhite = toolbarView.findViewById(R.id.iconBoardWhite);
        
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
        
        // Set initial state: Annotation DISABLED by default so user can use phone normally
        setAnnotationEnabled(false);
        
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
            
            WindowManager.LayoutParams wmParams = (WindowManager.LayoutParams) toolbarView.getLayoutParams();
            Log.d(TAG, "=== TOOLBAR WIDTH CHANGE ===");
            Log.d(TAG, "Current WindowManager width: " + wmParams.width);
            
            if (isExpanded) {
                expandableContent.setVisibility(View.VISIBLE);
                btnExpandCollapse.setText("chevron_left");
                btnCloseAnnotation.setVisibility(View.VISIBLE);
                
                // Set WindowManager params to 240dp when expanded (increased for color picker icon)
                int widthPx = (int) (240 * getResources().getDisplayMetrics().density);
                wmParams.width = widthPx;
                Log.d(TAG, "EXPANDED: Setting WindowManager width to 240dp (" + widthPx + "px)");
            } else {
                expandableContent.setVisibility(View.GONE);
                btnExpandCollapse.setText("chevron_right");
                btnCloseAnnotation.setVisibility(View.GONE);
                
                // Set WindowManager params to wrap_content when collapsed
                wmParams.width = WindowManager.LayoutParams.WRAP_CONTENT;
                Log.d(TAG, "COLLAPSED: Setting WindowManager width to WRAP_CONTENT");
            }
            
            windowManager.updateViewLayout(toolbarView, wmParams);
            Log.d(TAG, "WindowManager params updated");
            Log.d(TAG, "=========================");
            updateNotification();
        });
        
        // Close button - shows confirmation dialog before closing
        btnCloseAnnotation.setOnClickListener(v -> {
            showCloseConfirmationDialog();
        });
        
        // Enable/Disable Annotation button
        btnToggleAnnotation.setOnClickListener(v -> {
            toggleAnnotation();
        });
        
        // Hide/Show Canvas button
        btnToggleCanvasVisibility.setOnClickListener(v -> {
            toggleCanvasVisibility();
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
        
        btnWidthExtraThick.setOnClickListener(v -> {
            annotationView.setStrokeWidth(3); // Extra thick stroke
            updateWidthSelection(btnWidthExtraThick);
        });
        
        // Color picker - shows Material Design color picker dialog
        btnColorPicker.setOnClickListener(v -> {
            showColorPickerDialog();
        });
        
        // Clear all
        btnClearAll.setOnClickListener(v -> annotationView.clearAll());
        
        // Board tool icons (new design - replaces toggle)
        btnBoardNone.setOnClickListener(v -> {
            annotationView.setBlackboardMode(false); // None = transparent
            annotationView.setWhiteboardMode(false);
            updateBoardSelection(btnBoardNone);
        });
        
        btnBoardBlack.setOnClickListener(v -> {
            annotationView.setBlackboardMode(true);
            annotationView.setWhiteboardMode(false);
            updateBoardSelection(btnBoardBlack);
        });
        
        btnBoardWhite.setOnClickListener(v -> {
            annotationView.setBlackboardMode(false);
            annotationView.setWhiteboardMode(true);
            updateBoardSelection(btnBoardWhite);
        });
        
        // Set default selections
        updateToolSelection(true); // Pen selected by default
        updateColorSelection(btnColorRed); // Red selected by default
        updateWidthSelection(btnWidthMedium); // Medium width by default
        updateBoardSelection(btnBoardNone); // None board by default
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
        
        // Color picker uses special icon style, reset it
        if (btnColorPicker != null) {
            btnColorPicker.setBackgroundResource(R.drawable.annotation_color_circle);
        }
        
        // Add green border to selected color
        if (selectedColor != null && selectedColor != btnColorPicker) {
            // Use foreground for the green border overlay
            selectedColor.setForeground(getResources().getDrawable(R.drawable.annotation_color_selected, null));
        }
    }
    
    private void resetColorBorder(View colorView) {
        // Clear foreground (green border)
        colorView.setForeground(null);
        
        // Restore proper background
        if (colorView == btnColorBlack) {
            colorView.setBackgroundResource(R.drawable.annotation_color_circle_black);
        } else {
            colorView.setBackgroundResource(R.drawable.annotation_color_circle);
        }
    }
    
    private void updateWidthSelection(View selectedWidth) {
        // Reset all widths
        btnWidthThin.setBackgroundResource(R.drawable.annotation_width_circle);
        btnWidthMedium.setBackgroundResource(R.drawable.annotation_width_circle);
        btnWidthThick.setBackgroundResource(R.drawable.annotation_width_circle);
        btnWidthExtraThick.setBackgroundResource(R.drawable.annotation_width_circle);
        
        // Highlight selected width with colored background
        selectedWidth.setBackgroundResource(R.drawable.annotation_width_selected);
    }
    
    private void updateBoardSelection(View selectedBoard) {
        // Reset all board tools
        iconBoardNone.setBackgroundResource(R.drawable.annotation_tool_bg);
        iconBoardBlack.setBackgroundResource(R.drawable.annotation_tool_bg);
        iconBoardWhite.setBackgroundResource(R.drawable.annotation_tool_bg);
        
        // Highlight selected board
        if (selectedBoard == btnBoardNone) {
            iconBoardNone.setBackgroundResource(R.drawable.annotation_tool_selected_bg);
        } else if (selectedBoard == btnBoardBlack) {
            iconBoardBlack.setBackgroundResource(R.drawable.annotation_tool_selected_bg);
        } else if (selectedBoard == btnBoardWhite) {
            iconBoardWhite.setBackgroundResource(R.drawable.annotation_tool_selected_bg);
        }
    }
    
    private void showColorPickerDialog() {
        // Launch transparent dialog activity instead of trying to show dialog from service
        Intent intent = new Intent(this, ColorPickerDialogActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }
    
    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
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
                    public void onLayerPinnedChanged(int index, boolean pinned) {
                        currentPage.getLayers().get(index).setPinned(pinned);
                        annotationView.invalidate();
                        String msg = pinned ? "📌 Pinned (stays visible when canvas hidden)" : "📌 Unpinned";
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
        // Toggle menu action
        Intent toggleIntent = new Intent(this, AnnotationService.class);
        toggleIntent.setAction("ACTION_TOGGLE_MENU");
        android.app.PendingIntent togglePendingIntent = android.app.PendingIntent.getService(
                this, 0, toggleIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE
        );
        
        // Project management action
        Intent projectIntent = new Intent(this, AnnotationService.class);
        projectIntent.setAction("ACTION_OPEN_PROJECTS");
        android.app.PendingIntent projectPendingIntent = android.app.PendingIntent.getService(
                this, 1, projectIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE
        );
        
        String contentText = overlayVisible 
            ? "Overlay visible - Tap to hide" 
            : "Overlay hidden - Tap to show";
        
        String annotationStatus = annotationEnabled ? " | ✏️ Enabled" : " | 📱 Disabled";
        
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Annotation" + annotationStatus)
                .setContentText(contentText)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .addAction(R.drawable.ic_launcher_foreground, overlayVisible ? "Hide" : "Show", togglePendingIntent)
                .addAction(R.drawable.ic_launcher_foreground, "Projects", projectPendingIntent)
                .build();
    }
    
    /**
     * Update notification text based on expanded state
     */
    private void updateNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, createNotification());
        }
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
    
    private void registerColorPickerReceiver() {
        colorPickerReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int selectedColor = intent.getIntExtra(ColorPickerDialogActivity.EXTRA_SELECTED_COLOR, 0);
                if (selectedColor != 0) {
                    annotationView.setColor(selectedColor);
                    annotationView.setPenMode();
                    updateToolSelection(true);
                    updateColorSelection(null);
                    Log.d(TAG, "Color selected from picker: " + Integer.toHexString(selectedColor));
                }
            }
        };
        
        IntentFilter filter = new IntentFilter(ColorPickerDialogActivity.ACTION_COLOR_SELECTED);
        registerReceiver(colorPickerReceiver, filter);
        Log.d(TAG, "Color picker receiver registered");
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
    
    /**
     * Show confirmation dialog before closing annotations
     */
    private void showCloseConfirmationDialog() {
        // Since we're in a service, we need to create a dialog with TYPE_APPLICATION_OVERLAY
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert);
        
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_annotation_close_confirm, null);
        builder.setView(dialogView);
        
        android.app.AlertDialog dialog = builder.create();
        
        // Set dialog window type for overlay
        if (dialog.getWindow() != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
            } else {
                dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
            }
        }
        
        // Find buttons in custom layout
        View btnHideOverlayDialog = dialogView.findViewById(R.id.btnHideOverlay);
        View btnStopService = dialogView.findViewById(R.id.btnStopService);
        View btnCancel = dialogView.findViewById(R.id.btnCancel);
        
        btnHideOverlayDialog.setOnClickListener(v -> {
            // Save current state
            saveCurrentState();
            
            // Hide the entire overlay
            hideOverlay();
            
            Toast.makeText(this, "Overlay hidden. Use notification to show again.", Toast.LENGTH_LONG).show();
            dialog.dismiss();
        });
        
        btnStopService.setOnClickListener(v -> {
            // Save before stopping
            saveCurrentState();
            
            // Send broadcast to turn off menu switch
            Intent intent = new Intent("com.fadcam.fadrec.ANNOTATION_SERVICE_STOPPED");
            sendBroadcast(intent);
            
            // Stop the service completely
            stopSelf();
            Toast.makeText(this, "Annotation service stopped", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });
        
        btnCancel.setOnClickListener(v -> {
            dialog.dismiss();
        });
        
        dialog.show();
    }
    
    /**
     * Toggle annotation enable/disable state (controls if drawing is active)
     */
    private void toggleAnnotation() {
        annotationEnabled = !annotationEnabled;
        setAnnotationEnabled(annotationEnabled);
    }
    
    /**
     * Set annotation enabled/disabled state with proper UI feedback
     */
    private void setAnnotationEnabled(boolean enabled) {
        annotationEnabled = enabled;
        
        if (annotationView != null) {
            annotationView.setEnabled(enabled);
            
            // CRITICAL: Update window flags so touches pass through when disabled
            if (annotationCanvasParams != null) {
                if (enabled) {
                    // Remove FLAG_NOT_TOUCHABLE so annotation receives touches
                    annotationCanvasParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                } else {
                    // Add FLAG_NOT_TOUCHABLE so touches pass through to phone
                    annotationCanvasParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                }
                windowManager.updateViewLayout(annotationView, annotationCanvasParams);
                Log.d(TAG, "Canvas window flags updated: touchable=" + enabled);
            }
        }
        
        // Update button appearance
        TextView iconAnnotation = btnToggleAnnotation.findViewById(R.id.iconToggleAnnotation);
        TextView labelAnnotation = btnToggleAnnotation.findViewById(R.id.labelToggleAnnotation);
        TextView descAnnotation = btnToggleAnnotation.findViewById(R.id.descToggleAnnotation);
        
        if (enabled) {
            // Annotation ENABLED - ready to draw
            iconAnnotation.setText("edit");
            iconAnnotation.setTextColor(getResources().getColor(android.R.color.holo_green_light));
            labelAnnotation.setText("Disable Annotation");
            descAnnotation.setText("Disable to use screen normally");
            Toast.makeText(this, "✏️ Annotation Enabled - Draw freely", Toast.LENGTH_SHORT).show();
        } else {
            // Annotation DISABLED - can use phone normally
            iconAnnotation.setText("edit_off");
            iconAnnotation.setTextColor(getResources().getColor(android.R.color.darker_gray));
            labelAnnotation.setText("Enable Annotation");
            descAnnotation.setText("Enable to use annotation tools");
            Toast.makeText(this, "📱 Annotation Disabled - Use phone normally", Toast.LENGTH_SHORT).show();
        }
        
        // Gray out or enable all annotation tools based on state
        updateAnnotationToolsState(enabled);
        
        updateNotification();
        Log.d(TAG, "Annotation enabled: " + enabled);
    }
    
    /**
     * Toggle canvas visibility (hide/show drawings, respecting pinned layers)
     */
    private void toggleCanvasVisibility() {
        canvasHidden = !canvasHidden;
        setCanvasVisibility(!canvasHidden);
    }
    
    /**
     * Set canvas visibility with proper UI feedback
     */
    private void setCanvasVisibility(boolean visible) {
        canvasHidden = !visible;
        
        // Update AnnotationView to hide unpinned layers
        if (annotationView != null) {
            annotationView.setCanvasHidden(canvasHidden);
        }
        
        // Update button appearance
        TextView iconCanvas = btnToggleCanvasVisibility.findViewById(R.id.iconToggleCanvasVisibility);
        TextView labelCanvas = btnToggleCanvasVisibility.findViewById(R.id.labelToggleCanvasVisibility);
        TextView descCanvas = btnToggleCanvasVisibility.findViewById(R.id.descToggleCanvasVisibility);
        
        if (visible) {
            // Canvas VISIBLE - all layers shown
            iconCanvas.setText("visibility");
            iconCanvas.setTextColor(getResources().getColor(android.R.color.holo_blue_light));
            labelCanvas.setText("Hide Canvas");
            descCanvas.setText("Hide all drawings (pinned layers stay)");
            Toast.makeText(this, "👁️ Canvas Visible", Toast.LENGTH_SHORT).show();
        } else {
            // Canvas HIDDEN - only pinned layers shown
            iconCanvas.setText("visibility_off");
            iconCanvas.setTextColor(getResources().getColor(android.R.color.darker_gray));
            labelCanvas.setText("Show Canvas");
            descCanvas.setText("Pinned layers still visible");
            Toast.makeText(this, "Canvas Hidden - Pinned layers still visible", Toast.LENGTH_SHORT).show();
        }
        
        Log.d(TAG, "Canvas visible: " + visible);
    }
    
    /**
     * Update all annotation tools to be enabled or grayed out
     */
    private void updateAnnotationToolsState(boolean enabled) {
        float alpha = enabled ? 1.0f : 0.3f;
        
        // Undo/Redo buttons - visual and functional disable
        if (btnUndo != null) {
            btnUndo.setAlpha(alpha);
            btnUndo.setEnabled(enabled);
            btnUndo.setClickable(enabled);
        }
        if (btnRedo != null) {
            btnRedo.setAlpha(alpha);
            btnRedo.setEnabled(enabled);
            btnRedo.setClickable(enabled);
        }
        if (txtUndoCount != null) txtUndoCount.setAlpha(alpha);
        if (txtRedoCount != null) txtRedoCount.setAlpha(alpha);
        
        // Pages and Layers
        if (btnPages != null) {
            btnPages.setAlpha(alpha);
            btnPages.setEnabled(enabled);
            btnPages.setClickable(enabled);
        }
        if (btnLayers != null) {
            btnLayers.setAlpha(alpha);
            btnLayers.setEnabled(enabled);
            btnLayers.setClickable(enabled);
        }
        if (txtPageInfo != null) txtPageInfo.setAlpha(alpha);
        if (txtLayerInfo != null) txtLayerInfo.setAlpha(alpha);
        
        // Clear All
        if (btnClearAll != null) {
            btnClearAll.setAlpha(alpha);
            btnClearAll.setEnabled(enabled);
            btnClearAll.setClickable(enabled);
        }
        
        // Tool icons
        if (iconSelectTool != null) iconSelectTool.setAlpha(alpha);
        if (iconPenTool != null) iconPenTool.setAlpha(alpha);
        if (iconEraserTool != null) iconEraserTool.setAlpha(alpha);
        if (iconTextTool != null) iconTextTool.setAlpha(alpha);
        if (iconShapeTool != null) iconShapeTool.setAlpha(alpha);
        
        // Tool buttons (disable clicks completely)
        if (btnSelectTool != null) {
            btnSelectTool.setEnabled(enabled);
            btnSelectTool.setClickable(enabled);
            btnSelectTool.setAlpha(alpha);
        }
        if (btnPenTool != null) {
            btnPenTool.setEnabled(enabled);
            btnPenTool.setClickable(enabled);
            btnPenTool.setAlpha(alpha);
        }
        if (btnEraserTool != null) {
            btnEraserTool.setEnabled(enabled);
            btnEraserTool.setClickable(enabled);
            btnEraserTool.setAlpha(alpha);
        }
        if (btnTextTool != null) {
            btnTextTool.setEnabled(enabled);
            btnTextTool.setClickable(enabled);
            btnTextTool.setAlpha(alpha);
        }
        if (btnShapeTool != null) {
            btnShapeTool.setEnabled(enabled);
            btnShapeTool.setClickable(enabled);
            btnShapeTool.setAlpha(alpha);
        }
        
        // Color buttons
        if (btnColorRed != null) {
            btnColorRed.setAlpha(alpha);
            btnColorRed.setEnabled(enabled);
            btnColorRed.setClickable(enabled);
        }
        if (btnColorBlue != null) {
            btnColorBlue.setAlpha(alpha);
            btnColorBlue.setEnabled(enabled);
            btnColorBlue.setClickable(enabled);
        }
        if (btnColorGreen != null) {
            btnColorGreen.setAlpha(alpha);
            btnColorGreen.setEnabled(enabled);
            btnColorGreen.setClickable(enabled);
        }
        if (btnColorYellow != null) {
            btnColorYellow.setAlpha(alpha);
            btnColorYellow.setEnabled(enabled);
            btnColorYellow.setClickable(enabled);
        }
        if (btnColorWhite != null) {
            btnColorWhite.setAlpha(alpha);
            btnColorWhite.setEnabled(enabled);
            btnColorWhite.setClickable(enabled);
        }
        if (btnColorBlack != null) {
            btnColorBlack.setAlpha(alpha);
            btnColorBlack.setEnabled(enabled);
            btnColorBlack.setClickable(enabled);
        }
        
        // Width buttons
        if (btnWidthThin != null) {
            btnWidthThin.setAlpha(alpha);
            btnWidthThin.setEnabled(enabled);
            btnWidthThin.setClickable(enabled);
        }
        if (btnWidthMedium != null) {
            btnWidthMedium.setAlpha(alpha);
            btnWidthMedium.setEnabled(enabled);
            btnWidthMedium.setClickable(enabled);
        }
        if (btnWidthThick != null) {
            btnWidthThick.setAlpha(alpha);
            btnWidthThick.setEnabled(enabled);
            btnWidthThick.setClickable(enabled);
        }
        
        // Background board tools
        if (btnBoardNone != null) {
            btnBoardNone.setAlpha(alpha);
            btnBoardNone.setEnabled(enabled);
            btnBoardNone.setClickable(enabled);
        }
        if (btnBoardBlack != null) {
            btnBoardBlack.setAlpha(alpha);
            btnBoardBlack.setEnabled(enabled);
            btnBoardBlack.setClickable(enabled);
        }
        if (btnBoardWhite != null) {
            btnBoardWhite.setAlpha(alpha);
            btnBoardWhite.setEnabled(enabled);
            btnBoardWhite.setClickable(enabled);
        }
        
        // Snap guides switch
        if (snapGuidesSwitch != null) {
            snapGuidesSwitch.setAlpha(alpha);
            snapGuidesSwitch.setEnabled(enabled);
            snapGuidesSwitch.setClickable(enabled);
        }
        
        Log.d(TAG, "Annotation tools state updated: " + (enabled ? "enabled" : "disabled"));
    }
    
    /**
     * Hide the entire overlay (arrow and expandable content)
     */
    private void hideOverlay() {
        overlayVisible = false;
        
        if (toolbarView != null) {
            toolbarView.setVisibility(View.GONE);
        }
        if (annotationView != null) {
            annotationView.setVisibility(View.GONE);
        }
        
        updateNotification();
        Log.d(TAG, "Overlay hidden");
    }
    
    /**
     * Show the overlay (toolbar and canvas)
     */
    private void showOverlay() {
        overlayVisible = true;
        
        if (toolbarView != null) {
            toolbarView.setVisibility(View.VISIBLE);
        }
        if (annotationView != null) {
            annotationView.setVisibility(View.VISIBLE);
        }
        
        updateNotification();
        Log.d(TAG, "Overlay shown");
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "AnnotationService destroyed");
        
        // Send broadcast to turn off menu switch in app
        Intent intent = new Intent("com.fadcam.fadrec.ANNOTATION_SERVICE_STOPPED");
        sendBroadcast(intent);
        
        // Unregister broadcast receivers
        if (menuActionReceiver != null) {
            unregisterReceiver(menuActionReceiver);
        }
        if (recordingStateReceiver != null) {
            unregisterReceiver(recordingStateReceiver);
        }
        if (colorPickerReceiver != null) {
            unregisterReceiver(colorPickerReceiver);
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
