package com.fadcam.fadrec.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
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
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.ContextThemeWrapper;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

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
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

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
    private View btnExpandCollapseContainer;
    private TextView btnExpandCollapse;
    private View expandableContent;
    
    // Recording controls
    private View recordingControlsContainer;
    private View btnRecordingCollapsed;
    private View recordingControlsExpanded;
    private View btnStartStopRec, btnPauseResumeRec;
    private TextView iconStartStop, labelStartStop;
    private TextView iconPauseResume, labelPauseResume;
    private com.fadcam.fadrec.ScreenRecordingState recordingState = com.fadcam.fadrec.ScreenRecordingState.NONE;
    private boolean isRecordingControlsExpanded = false;
    
    // Project section
    private View quickAccessHeader;
    private TextView quickAccessExpandIcon;
    private View quickAccessContent;
    private boolean isQuickAccessExpanded = true; // Default open
    
    private View projectHeader;
    private TextView projectExpandIcon;
    private View projectContent;
    private boolean isProjectExpanded = false;
    
    // Annotation tools section
    private View annotationsHeader;
    private TextView annotationsExpandIcon;
    private View annotationsContent;
    private TextView iconSnapGuides, labelSnapGuides;
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
    private boolean isAnimating = false; // Track if menu is currently animating
    private boolean overlayVisible = true;      // Controls if arrow overlay is shown
    private boolean annotationEnabled = false;  // Controls if drawing is active (DEFAULT: OFF)
    private boolean canvasHidden = false;       // Controls if canvas drawings are hidden (except pinned layers)
    private long lastArrowHintTimestamp = 0L;
    private Runnable pendingArrowHintRunnable;
    private AnimatorSet arrowFlipAnimator;
    private final java.util.Map<TextView, AnimatorSet> sectionChevronAnimators = new java.util.WeakHashMap<>();
    private static final long ARROW_HINT_COOLDOWN_MS = 2000L;
    private static final long ARROW_HINT_START_DELAY_MS = 180L;
    private static final long ARROW_HINT_OUT_DURATION_MS = 260L;
    private static final long ARROW_HINT_RETURN_DURATION_MS = 340L;
    private static final long ARROW_HINT_PAUSE_MS = 110L;
    private static final long ARROW_ROTATION_DURATION_MS = 260L;
    private static final long ARROW_TOGGLE_DELAY_MS = 200L;
    private static final FastOutSlowInInterpolator ARROW_HINT_INTERPOLATOR = new FastOutSlowInInterpolator();
    
    // Separate window params for arrow and menu
    private View arrowOverlay;  // Separate overlay for arrow button only
    private WindowManager.LayoutParams arrowParams;
    private WindowManager.LayoutParams menuParams;
    
    // Annotation control buttons
    private View btnToggleAnnotation;
    private View btnToggleCanvasVisibility;
    
    // Window params for annotation canvas (need to update flags dynamically)
    private WindowManager.LayoutParams annotationCanvasParams;
    
    // Broadcast receiver for floating menu actions
    private BroadcastReceiver menuActionReceiver;
    private BroadcastReceiver recordingStateReceiver;
    private BroadcastReceiver colorPickerReceiver;
    private BroadcastReceiver projectNamingReceiver;
    private BroadcastReceiver projectSelectionReceiver;
    private BroadcastReceiver layerRenameReceiver;
    private BroadcastReceiver pageRenameReceiver;
    
    // Toolbar dragging
    private int toolbarInitialX, toolbarInitialY;
    private float toolbarInitialTouchX, toolbarInitialTouchY;
    
    // Edge position tracking
    private enum EdgePosition { LEFT, RIGHT, TOP, BOTTOM, CENTER }
    private EdgePosition currentEdge = EdgePosition.RIGHT; // Start on right
    
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
        View btnDeleteAll = dialogView.findViewById(R.id.btnDeleteAll);
        View btnClose = dialogView.findViewById(R.id.btnCloseDialog);
        TextView txtCurrentProject = dialogView.findViewById(R.id.txtCurrentProject);
        TextView txtProjectsPath = dialogView.findViewById(R.id.txtProjectsPath);
        
        // Display current project - always show sanitized folder name
        if (currentProjectName != null) {
            // Always display the sanitized folder name (which is what's actually used)
            String sanitizedName = ProjectFileManager.sanitizeProjectName(currentProjectName);
            txtCurrentProject.setText("Current: " + sanitizedName);
        } else {
            txtCurrentProject.setText("Current: None");
        }
        
        // Display projects path
        String projectsPath = projectFileManager.getProjectsPath();
        // Convert to user-friendly path
        String userFriendlyPath = projectsPath.replace(android.os.Environment.getExternalStorageDirectory().getPath(), "~");
        txtProjectsPath.setText("Projects saved at: " + userFriendlyPath);
        
        // Load all project folders
        File[] projectFolders = projectFileManager.listProjects();
        
        java.util.List<ProjectInfo> projects = new java.util.ArrayList<>();
        if (projectFolders != null && projectFolders.length > 0) {
            for (File folder : projectFolders) {
                String projectName = folder.getName();
                ProjectInfo info = new ProjectInfo();
                info.folderName = projectName;
                info.thumbnailFile = projectFileManager.getThumbnailFile(projectName);

                ProjectFileManager.ProjectSummary summary = projectFileManager.getProjectSummary(projectName);
                if (summary != null) {
                    info.projectFile = summary.projectFile;
                    info.displayName = !TextUtils.isEmpty(summary.displayName)
                        ? summary.displayName
                        : projectName;
                    info.description = summary.description;
                    info.fileSizeBytes = summary.fileSizeBytes;
                    info.createdAt = summary.createdAt;
                    info.modifiedAt = summary.modifiedAt;
                } else {
                    info.displayName = projectName;
                    info.description = "";
                    File dataFile = projectFileManager.getProjectDataFile(projectName);
                    if (dataFile != null && dataFile.exists()) {
                        info.projectFile = dataFile;
                        info.fileSizeBytes = dataFile.length();
                        info.modifiedAt = dataFile.lastModified();
                    } else {
                        info.modifiedAt = folder.lastModified();
                    }
                    info.createdAt = info.modifiedAt;
                }

                projects.add(info);
            }
            
            java.util.Collections.sort(projects, (a, b) -> Long.compare(b.modifiedAt, a.modifiedAt));
        }
        
        if (projects.isEmpty()) {
            ProjectInfo emptyInfo = new ProjectInfo();
            emptyInfo.displayName = getString(R.string.project_list_empty_title);
            emptyInfo.description = getString(R.string.project_list_empty_description);
            emptyInfo.fileSizeBytes = 0L;
            emptyInfo.createdAt = 0L;
            emptyInfo.modifiedAt = 0L;
            projects.add(emptyInfo);
        }
        
        // Custom adapter with thumbnails and delete buttons
        ProjectListAdapter adapter = new ProjectListAdapter(this, projects, projectFileManager, dialog);
        projectList.setAdapter(adapter);
        
        // Handle project selection
        projectList.setOnItemClickListener((parent, view, position, id) -> {
            ProjectInfo selectedProject = projects.get(position);
            if (selectedProject.folderName != null) {
                loadProject(selectedProject.folderName);
                dialog.dismiss();
            }
        });
        
        // New project button
        btnNewProject.setOnClickListener(v -> {
            createNewProject();
            dialog.dismiss();
        });
        
        // Delete all button
        btnDeleteAll.setOnClickListener(v -> {
            showDeleteAllConfirmation(dialog);
        });
        
        btnClose.setOnClickListener(v -> dialog.dismiss());
        
        dialog.show();
    }
    
    /**
     * Show confirmation dialog for deleting all projects
     */
    private void showDeleteAllConfirmation(android.app.AlertDialog parentDialog) {
        Context themedContext = new ContextThemeWrapper(this, R.style.Base_Theme_FadCam);
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(themedContext);
        
        androidx.appcompat.app.AlertDialog deleteAllDialog = builder
            .setTitle("Delete All Projects?")
            .setMessage("This will permanently delete ALL projects. This action cannot be undone!")
            .setPositiveButton("Delete All", (dialog, which) -> {
                File[] projects = projectFileManager.listProjects();
                if (projects != null) {
                    for (File project : projects) {
                        projectFileManager.deleteProject(project.getName());
                    }
                    Toast.makeText(this, "All projects deleted", Toast.LENGTH_SHORT).show();
                    parentDialog.dismiss();
                    // Create a new project
                    createNewProject();
                }
            })
            .setNegativeButton("Cancel", null)
            .create();
        
        // Set dialog window type for overlay
        if (deleteAllDialog.getWindow() != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                deleteAllDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
            } else {
                deleteAllDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
            }
        }
        
        deleteAllDialog.show();
    }
    
    /**
     * Helper class to hold project information for the list
     */
    private static class ProjectInfo {
        String folderName;
        String displayName;
        String description;
        File thumbnailFile;
        File projectFile;
        long fileSizeBytes;
        long createdAt;
        long modifiedAt;
    }
    
    /**
     * Custom adapter for project list with thumbnails and delete buttons
     */
    private class ProjectListAdapter extends android.widget.ArrayAdapter<ProjectInfo> {
        private final ProjectFileManager projectFileManager;
        private final android.app.AlertDialog parentDialog;
        private final java.text.DateFormat dateFormat = java.text.DateFormat.getDateTimeInstance(
            java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT);
        
        public ProjectListAdapter(Context context, java.util.List<ProjectInfo> projects, 
                                 ProjectFileManager fileManager, android.app.AlertDialog dialog) {
            super(context, 0, projects);
            this.projectFileManager = fileManager;
            this.parentDialog = dialog;
        }
        
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ProjectInfo project = getItem(position);
            
            if (convertView == null) {
                convertView = LayoutInflater.from(getContext()).inflate(
                    R.layout.item_project_list, parent, false);
            }
            
            // Find views
            android.widget.ImageView imgThumbnail = convertView.findViewById(R.id.imgProjectThumbnail);
            TextView txtName = convertView.findViewById(R.id.txtProjectName);
            TextView txtDesc = convertView.findViewById(R.id.txtProjectDescription);
            TextView txtMeta = convertView.findViewById(R.id.txtProjectMeta);
            View btnDelete = convertView.findViewById(R.id.btnDeleteProject);
            
            // Set data
            txtName.setText(project.displayName);
            
            if (project.description != null && !project.description.isEmpty()) {
                txtDesc.setText(project.description);
                txtDesc.setVisibility(View.VISIBLE);
                txtDesc.setAlpha(1f);
            } else {
                    txtDesc.setText(R.string.project_description_placeholder);
                txtDesc.setAlpha(0.5f);
                txtDesc.setVisibility(View.VISIBLE);
            }
            
            // Load thumbnail if exists
            if (project.thumbnailFile != null && project.thumbnailFile.exists()) {
                android.graphics.Bitmap thumbnail = android.graphics.BitmapFactory.decodeFile(
                    project.thumbnailFile.getAbsolutePath());
                imgThumbnail.setImageBitmap(thumbnail);
            } else {
                imgThumbnail.setImageResource(R.drawable.screen_recorder);
            }
            
            // Hide delete button for empty placeholder
            if (project.folderName == null) {
                btnDelete.setVisibility(View.GONE);
                txtMeta.setVisibility(View.GONE);
            } else {
                btnDelete.setVisibility(View.VISIBLE);
                btnDelete.setOnClickListener(v -> {
                    showDeleteProjectConfirmation(project.displayName, project.folderName);
                });

                String sizeText = project.fileSizeBytes > 0
                    ? android.text.format.Formatter.formatFileSize(getContext(), project.fileSizeBytes)
                    : getContext().getString(R.string.project_meta_unknown_size);
                String dateText = project.modifiedAt > 0
                    ? dateFormat.format(new java.util.Date(project.modifiedAt))
                    : getContext().getString(R.string.project_meta_unknown_date);
                String metaText = getContext().getString(R.string.project_meta_format, sizeText, dateText);
                txtMeta.setText(metaText);
                txtMeta.setVisibility(View.VISIBLE);
            }
            
            return convertView;
        }
        
        private void showDeleteProjectConfirmation(String displayName, String folderName) {
            Context themedContext = new ContextThemeWrapper(AnnotationService.this, R.style.Base_Theme_FadCam);
            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(themedContext);
            
            androidx.appcompat.app.AlertDialog deleteDialog = builder
                .setTitle("Delete Project?")
                .setMessage("Delete \"" + displayName + "\"? This cannot be undone.")
                .setPositiveButton("Delete", (dialog, which) -> {
                    boolean deleted = projectFileManager.deleteProject(folderName);
                    if (deleted) {
                        Toast.makeText(AnnotationService.this, "Project deleted", Toast.LENGTH_SHORT).show();
                        parentDialog.dismiss();
                        // Reopen dialog to refresh list
                        showProjectManagementDialog();
                    } else {
                        Toast.makeText(AnnotationService.this, "Failed to delete project", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .create();
            
            // Set dialog window type for overlay
            if (deleteDialog.getWindow() != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    deleteDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
                } else {
                    deleteDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
                }
            }
            
            deleteDialog.show();
        }
    }
    
    private void loadProject(String projectName) {
        Log.i(TAG, "========== LOADING PROJECT: " + projectName + " ==========");
        
        // Only save current project if it's different from the one we're loading
        // This prevents overwriting the project we're about to load with stale data
        if (!projectName.equals(currentProjectName) && currentProjectName != null) {
            Log.d(TAG, "Saving current project before switching: " + currentProjectName);
            saveCurrentState();
        } else {
            Log.d(TAG, "Skipping save - loading same project or no current project");
        }
        
        // Update current project name and save to preferences
        currentProjectName = projectName;
        android.content.SharedPreferences prefs = getSharedPreferences("fadrec_prefs", MODE_PRIVATE);
        prefs.edit().putString("current_project", currentProjectName).apply();
        Log.d(TAG, "Updated current project preference: " + currentProjectName);
        
        // Load new project
        AnnotationState loadedState = projectFileManager.loadProject(projectName);
        
        if (loadedState != null && annotationView != null) {
            Log.i(TAG, "✅ Project loaded successfully from file");
            Log.d(TAG, "  - Total pages: " + loadedState.getPages().size());
            Log.d(TAG, "  - Active page index: " + loadedState.getActivePageIndex());
            
            // Log each page details
            for (int i = 0; i < loadedState.getPages().size(); i++) {
                AnnotationPage page = loadedState.getPages().get(i);
                Log.d(TAG, "  - Page " + (i+1) + ": " + page.getLayers().size() + " layers, " + 
                      "Total objects across all layers: " + getTotalObjectsInPage(page));
            }
            
            // Apply state to view
            annotationView.setState(loadedState);
            
            // Force complete redraw with post
            annotationView.post(() -> {
                annotationView.invalidate();
                annotationView.requestLayout();
                Log.d(TAG, "Post-load redraw completed");
            });
            
            updateUndoRedoButtons();
            updatePageLayerInfo();
            updateProjectNameDisplay();
            updateNotification();
            
            Toast.makeText(this, "✅ Loaded: " + projectName, Toast.LENGTH_SHORT).show();
            Log.i(TAG, "========== PROJECT LOADED AND APPLIED ==========");
        } else {
            Log.e(TAG, "❌ Failed to load project: " + projectName);
            Log.e(TAG, "  - loadedState is null: " + (loadedState == null));
            Log.e(TAG, "  - annotationView is null: " + (annotationView == null));
            Toast.makeText(this, "❌ Failed to load project", Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * Helper to count total objects in a page across all layers
     */
    private int getTotalObjectsInPage(AnnotationPage page) {
        int count = 0;
        for (AnnotationLayer layer : page.getLayers()) {
            count += layer.getObjects().size();
        }
        return count;
    }
    
    private void createNewProject() {
        // Save current project before creating new one (if exists)
        if (currentProjectName != null) {
            Log.d(TAG, "Saving current project before creating new: " + currentProjectName);
            saveCurrentState();
        }
        
        // Generate new project name with timestamp - using FadRec prefix
        String newProjectName = "FadRec_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(new java.util.Date());
        currentProjectName = newProjectName;
        
        // Update preference
        android.content.SharedPreferences prefs = getSharedPreferences("fadrec_prefs", MODE_PRIVATE);
        prefs.edit().putString("current_project", currentProjectName).apply();
        
        // Create fresh state
        AnnotationState newState = new AnnotationState();
        if (annotationView != null) {
            annotationView.setState(newState);
            updateUndoRedoButtons();
            updatePageLayerInfo();
            updateProjectNameDisplay();
        }
        
        // Save the new project
        projectFileManager.saveProject(newState, currentProjectName);
        
        Toast.makeText(this, "📝 New Project: " + newProjectName, Toast.LENGTH_SHORT).show();
        Log.d(TAG, "Created new project: " + newProjectName);
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "============ AnnotationService onCreate START ============");
        
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());
        
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        
        // Initialize project file manager
        projectFileManager = new ProjectFileManager(this);
        Log.d(TAG, "ProjectFileManager initialized");
        
        // Move project scanning to background thread to avoid UI lag
        new Thread(() -> {
            // Get or create current project name (file scanning happens here)
            currentProjectName = projectFileManager.getOrCreateCurrentProject();
            Log.i(TAG, "Current project name: " + currentProjectName);
            
            // Switch back to main thread for UI operations
            new Handler(Looper.getMainLooper()).post(() -> {
                setupAnnotationCanvas();
                setupToolbar();
                
                // Load last saved project automatically
                loadLastProject();
                
                startAutoSave();
                registerMenuActionReceiver();
                registerRecordingStateReceiver();
                registerColorPickerReceiver();
                registerProjectNamingReceiver();
                registerProjectSelectionReceiver();
                
                // Broadcast that service is ready (dismiss loading dialog)
                Intent readyIntent = new Intent("com.fadcam.fadrec.ANNOTATION_SERVICE_READY");
                sendBroadcast(readyIntent);
                
                Log.d(TAG, "============ AnnotationService onCreate COMPLETE ============");
            });
        }).start();
    }
    
    /**
     * Load the last saved project on service start
     */
    private void loadLastProject() {
        if (currentProjectName != null && projectFileManager != null) {
            // Check if project exists
            if (projectFileManager.projectExists(currentProjectName)) {
                Log.i(TAG, "Found existing project: " + currentProjectName);
                
                // Show startup dialog: Continue or Create New
                showStartupDialog(currentProjectName);
            } else {
                Log.w(TAG, "Saved project doesn't exist, starting fresh");
                startFreshProject();
            }
        } else {
            Log.w(TAG, "No current project name or ProjectFileManager not initialized");
            startFreshProject();
        }
    }
    
    /**
     * Show dialog asking user to continue with last project or create new
     */
    private void showStartupDialog(String existingProjectName) {
        String sanitizedName = ProjectFileManager.sanitizeProjectName(existingProjectName);
        
        Context themedContext = new ContextThemeWrapper(this, R.style.Base_Theme_FadCam);
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(themedContext);
        
        androidx.appcompat.app.AlertDialog startupDialog = builder
            .setTitle("Welcome Back!")
            .setMessage("Continue with your last saved project \"" + sanitizedName + "\" or start a new one?")
            .setPositiveButton("Continue", (dialog, which) -> {
                Log.i(TAG, "User chose to continue with: " + existingProjectName);
                loadExistingProject(existingProjectName);
            })
            .setNegativeButton("New Project", (dialog, which) -> {
                Log.i(TAG, "User chose to create new project");
                createNewProject();
            })
            .setCancelable(false)
            .create();
        
        // Set dialog window type for overlay
        if (startupDialog.getWindow() != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startupDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
            } else {
                startupDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
            }
        }
        
        startupDialog.show();
    }
    
    /**
     * Load an existing project
     */
    private void loadExistingProject(String projectName) {
        Log.i(TAG, "Attempting to load last project: " + projectName);
        
        AnnotationState loadedState = projectFileManager.loadProject(projectName);
        
        if (loadedState != null && annotationView != null) {
            Log.i(TAG, "✅ Successfully loaded project: " + projectName);
            Log.d(TAG, "  - Pages: " + loadedState.getPages().size());
            Log.d(TAG, "  - Current page index: " + loadedState.getActivePageIndex());
            
            // Apply the loaded state to annotation view
            annotationView.setState(loadedState);
            
            // Force complete redraw with post to ensure view is ready
            annotationView.post(() -> {
                annotationView.invalidate();
                annotationView.requestLayout();
                Log.d(TAG, "Post-load redraw triggered");
            });
            
            updateUndoRedoButtons();
            updatePageLayerInfo();
            updateProjectNameDisplay();
            
            Log.i(TAG, "Project state applied to AnnotationView");
        } else {
            Log.w(TAG, "⚠️ Failed to load project: " + projectName);
            Log.i(TAG, "Starting with fresh state");
            startFreshProject();
        }
    }
    
    /**
     * Start with a fresh project (no existing data)
     */
    private void startFreshProject() {
        Log.i(TAG, "Starting with fresh state");
        updateUndoRedoButtons();
        updatePageLayerInfo();
        updateProjectNameDisplay();
    }
    
    private void setupAnnotationCanvas() {
        Log.d(TAG, "Setting up annotation canvas...");
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
        
        // ================ CREATE TWO SEPARATE OVERLAYS ================
        // 1. ARROW BUTTON OVERLAY (stays fixed at edge)
        // 2. MENU CONTENT OVERLAY (fades in/out beside arrow)
        
        int layoutType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        
        // ===== ARROW OVERLAY SETUP =====
    arrowOverlay = inflater.inflate(R.layout.annotation_arrow_button, null);
    btnExpandCollapseContainer = arrowOverlay.findViewById(R.id.btnExpandCollapseContainer);
    btnExpandCollapse = arrowOverlay.findViewById(R.id.btnExpandCollapse);
        
        arrowParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        arrowParams.windowAnimations = 0; // Disable animations
        arrowParams.gravity = Gravity.TOP | Gravity.END;
        arrowParams.x = 0;
        
        // Position arrow at ~60% down screen for easy thumb reach
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(metrics);
        int screenHeight = metrics.heightPixels;
        arrowParams.y = (int) (screenHeight * 0.5); // Center vertically
        
        windowManager.addView(arrowOverlay, arrowParams);
        Log.d(TAG, "Arrow overlay added at x=0, y=" + arrowParams.y + " (RIGHT edge, 60% down) - ALWAYS ON TOP");
        
        // Set initial arrow size for RIGHT edge (vertical layout)
        currentEdge = EdgePosition.RIGHT;
        adaptLayoutForEdge(EdgePosition.RIGHT);
        
        // ===== MENU OVERLAY SETUP =====
        // Use the full unified layout as the menu overlay
        toolbarView = inflater.inflate(R.layout.annotation_toolbar_unified, null);
        expandableContent = toolbarView.findViewById(R.id.expandableContent);
        
        // Initialize Quick Access buttons
        btnToggleAnnotation = toolbarView.findViewById(R.id.btnToggleAnnotation);
        btnToggleCanvasVisibility = toolbarView.findViewById(R.id.btnToggleCanvasVisibility);
        
        // Initialize Project Manager
        LinearLayout btnCurrentProject = toolbarView.findViewById(R.id.btnCurrentProject);
        LinearLayout btnManageProjects = toolbarView.findViewById(R.id.btnManageProjects);
        TextView txtCurrentProjectName = toolbarView.findViewById(R.id.txtCurrentProjectName);
        TextView txtCurrentProjectDesc = toolbarView.findViewById(R.id.txtCurrentProjectDesc);
        
        // Update current project display
        if (txtCurrentProjectName != null) {
            txtCurrentProjectName.setText(currentProjectName != null && !currentProjectName.isEmpty() 
                ? currentProjectName : "Untitled Project");
        }
        
        // Setup project manager listeners
        if (btnCurrentProject != null) {
            btnCurrentProject.setOnClickListener(v -> showProjectNamingDialog());
        }
        
        if (btnManageProjects != null) {
            btnManageProjects.setOnClickListener(v -> showProjectsDialog());
        }
        
        // Initialize recording controls
        recordingControlsContainer = toolbarView.findViewById(R.id.recordingControlsContainer);
        btnRecordingCollapsed = toolbarView.findViewById(R.id.btnRecordingCollapsed);
        recordingControlsExpanded = toolbarView.findViewById(R.id.recordingControlsExpanded);
        btnStartStopRec = toolbarView.findViewById(R.id.btnStartStopRec);
        btnPauseResumeRec = toolbarView.findViewById(R.id.btnPauseResumeRec);
        iconStartStop = toolbarView.findViewById(R.id.iconStartStop);
        labelStartStop = toolbarView.findViewById(R.id.labelStartStop);
        iconPauseResume = toolbarView.findViewById(R.id.iconPauseResume);
        labelPauseResume = toolbarView.findViewById(R.id.labelPauseResume);
        
        // Initialize quick access section
        quickAccessHeader = toolbarView.findViewById(R.id.quickAccessHeader);
        quickAccessExpandIcon = toolbarView.findViewById(R.id.quickAccessExpandIcon);
        quickAccessContent = toolbarView.findViewById(R.id.quickAccessContent);
        
        // Initialize project section
        projectHeader = toolbarView.findViewById(R.id.projectHeader);
        projectExpandIcon = toolbarView.findViewById(R.id.projectExpandIcon);
        projectContent = toolbarView.findViewById(R.id.projectContent);
        
        // Initialize annotations section
        annotationsHeader = toolbarView.findViewById(R.id.annotationsHeader);
        annotationsExpandIcon = toolbarView.findViewById(R.id.annotationsExpandIcon);
        annotationsContent = toolbarView.findViewById(R.id.annotationsContent);
        iconSnapGuides = toolbarView.findViewById(R.id.iconSnapGuides);
        labelSnapGuides = toolbarView.findViewById(R.id.labelSnapGuides);
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
        
        // Hide the arrow button in the unified layout since we have separate arrow overlay
        menuParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        menuParams.windowAnimations = 0; // Disable animations
        menuParams.gravity = Gravity.TOP | Gravity.END;
        menuParams.x = dpToPx(24); // 24dp offset from arrow
        menuParams.y = arrowParams.y; // Match arrow Y position
        
        // Keep toolbarView visible but content hidden - FrameLayout is always visible as container
        toolbarView.setVisibility(View.VISIBLE);
        expandableContent.setVisibility(View.GONE); // Only the content inside starts hidden
        windowManager.addView(toolbarView, menuParams);
        Log.d(TAG, "Menu overlay added at x=24dp, y=" + menuParams.y + " (offset from arrow)");
        
        setupToolbarListeners();
        setupToolbarDragging();
        
        // Set initial arrow direction based on starting position (right edge)
        updateArrowDirection(arrowParams);
        
        // Set initial state: Annotation DISABLED by default so user can use phone normally
        setAnnotationEnabled(false);
        
        Log.d(TAG, "Two separate overlays created - arrow and menu are now independent");
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
        
        // Main Expand/Collapse button (toggles menu overlay visibility with fade)
        btnExpandCollapseContainer.setOnClickListener(v -> {
            if (isAnimating) {
                Log.d(TAG, "Click ignored - animation in progress");
                return;
            }

            if (toolbarView == null || toolbarView.getWindowToken() == null) {
                Log.w(TAG, "Toggle skipped - toolbar not attached");
                return;
            }

            performMenuToggle();
        });
        
        // Enable/Disable Annotation button
        btnToggleAnnotation.setOnClickListener(v -> {
            toggleAnnotation();
        });
        
        // Hide/Show Canvas button
        btnToggleCanvasVisibility.setOnClickListener(v -> {
            toggleCanvasVisibility();
        });
        
        // Quick Access section header (toggles quick access content)
        if (quickAccessHeader != null) {
            quickAccessHeader.setOnClickListener(v -> {
                isQuickAccessExpanded = !isQuickAccessExpanded;
                if (isQuickAccessExpanded) {
                    quickAccessContent.setVisibility(View.VISIBLE);
                    applySectionChevronGlyph(quickAccessExpandIcon, "expand_less");
                } else {
                    quickAccessContent.setVisibility(View.GONE);
                    applySectionChevronGlyph(quickAccessExpandIcon, "expand_more");
                }
            });
        }
        
        // Project section header (toggles project content)
        if (projectHeader != null) {
            projectHeader.setOnClickListener(v -> {
                isProjectExpanded = !isProjectExpanded;
                if (isProjectExpanded) {
                    projectContent.setVisibility(View.VISIBLE);
                    applySectionChevronGlyph(projectExpandIcon, "expand_less");
                } else {
                    projectContent.setVisibility(View.GONE);
                    applySectionChevronGlyph(projectExpandIcon, "expand_more");
                }
            });
        }
        
        // Annotations section header (toggles annotation tools)
        annotationsHeader.setOnClickListener(v -> {
            isAnnotationsExpanded = !isAnnotationsExpanded;
            if (isAnnotationsExpanded) {
                annotationsContent.setVisibility(View.VISIBLE);
                applySectionChevronGlyph(annotationsExpandIcon, "expand_less");
            } else {
                annotationsContent.setVisibility(View.GONE);
                applySectionChevronGlyph(annotationsExpandIcon, "expand_more");
            }
        });
        
        // Snap guides switch
        snapGuidesSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (annotationView != null) {
                annotationView.setSnapGuidesEnabled(isChecked);
                Log.d(TAG, "Snap guides " + (isChecked ? "enabled" : "disabled"));
            }
        });
        
        // Recording controls - Collapsed button click to START recording
        btnRecordingCollapsed.setOnClickListener(v -> {
            if (recordingState == com.fadcam.fadrec.ScreenRecordingState.NONE) {
                // Start recording - this will trigger state change which will expand the UI
                sendBroadcast(new Intent(com.fadcam.Constants.ACTION_START_SCREEN_RECORDING_FROM_OVERLAY));
            }
        });
        
        // Recording controls - Start/Stop button (in expanded state)
        btnStartStopRec.setOnClickListener(v -> {
            if (btnStartStopRec.isEnabled()) {
                // This button is only for STOP when recording is in progress
                sendBroadcast(new Intent(com.fadcam.Constants.ACTION_STOP_SCREEN_RECORDING));
            }
        });
        
        // Recording controls - Pause/Resume button (in expanded state)
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

    private void performMenuToggle() {
        isExpanded = !isExpanded;
        isAnimating = true;

        Log.d(TAG, "=== MENU OVERLAY TOGGLE ===");
        Log.d(TAG, "Action: " + (isExpanded ? "EXPANDING" : "COLLAPSING"));
        Log.d(TAG, "Current Edge: " + currentEdge);
        Log.d(TAG, "Arrow Params - x: " + arrowParams.x + ", y: " + arrowParams.y);
        Log.d(TAG, "Menu Params - x: " + menuParams.x + ", y: " + menuParams.y);

        if (isExpanded) {
            Log.d(TAG, "Starting EXPAND - showing menu overlay");
            expandableContent.setVisibility(View.VISIBLE);
            expandableContent.setAlpha(0f);
            expandableContent.animate()
                .alpha(1f)
                .setDuration(200)
                .withEndAction(() -> {
                    isAnimating = false;
                    Log.d(TAG, "EXPAND completed - menu visible");
                })
                .start();
            updateArrowDirection(arrowParams, true);
        } else {
            Log.d(TAG, "Starting COLLAPSE - hiding menu overlay");
            expandableContent.animate()
                .alpha(0f)
                .setDuration(150)
                .withEndAction(() -> {
                    expandableContent.setVisibility(View.GONE);
                    expandableContent.setAlpha(1f);
                    isAnimating = false;
                    Log.d(TAG, "COLLAPSE completed - menu hidden");
                })
                .start();
            updateArrowDirection(arrowParams, true);
        }
    }
    
    private void setupToolbarDragging() {
        // Make both overlays draggable together
        View.OnTouchListener dragListener = new View.OnTouchListener() {
            private boolean isDragging = false;
            private static final int CLICK_THRESHOLD = 10; // pixels
            private float totalDragDistance = 0;
            
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        toolbarInitialX = arrowParams.x;
                        toolbarInitialY = arrowParams.y;
                        toolbarInitialTouchX = event.getRawX();
                        toolbarInitialTouchY = event.getRawY();
                        isDragging = false;
                        totalDragDistance = 0;
                        return true;
                        
                    case MotionEvent.ACTION_MOVE:
                        float deltaX = event.getRawX() - toolbarInitialTouchX;
                        float deltaY = event.getRawY() - toolbarInitialTouchY;
                        totalDragDistance += Math.abs(deltaX) + Math.abs(deltaY);
                        
                        if (totalDragDistance > CLICK_THRESHOLD) {
                            isDragging = true;
                        }
                        
                        if (isDragging) {
                            // Update both overlays together
                            arrowParams.x = toolbarInitialX - (int) deltaX;
                            arrowParams.y = toolbarInitialY + (int) deltaY;
                            windowManager.updateViewLayout(arrowOverlay, arrowParams);
                            
                            // Menu follows with appropriate offset based on edge
                            // During drag, maintain relative position (menu to the right for simplicity)
                            menuParams.x = arrowParams.x + dpToPx(24);
                            menuParams.y = arrowParams.y;
                            windowManager.updateViewLayout(toolbarView, menuParams);
                        }
                        return true;
                        
                    case MotionEvent.ACTION_UP:
                        if (isDragging) {
                            // Cancel any ongoing animations before snapping
                            expandableContent.animate().cancel();
                            isAnimating = false;
                            
                            // Smart snap-to-edge behavior
                            snapToEdgeIfNeeded();
                            isDragging = false;
                            return true;
                        } else if (v.getId() == R.id.btnExpandCollapseContainer) {
                            // Only trigger click for arrow button
                            v.performClick();
                            return false;
                        }
                        return true;
                }
                return false;
            }
        };
        
        // Set touch listener on arrow button (always draggable)
    btnExpandCollapseContainer.setOnTouchListener(dragListener);
        // Set touch listener on menu overlay (draggable when visible)
        toolbarView.setOnTouchListener(dragListener);
    }
    
    /**
     * Snap toolbar to nearest screen edge if close enough
     * Updates both arrow and menu overlays together
     */
    private void snapToEdgeIfNeeded() {
        Log.d(TAG, ">>> snapToEdgeIfNeeded() called");
        Log.d(TAG, "Arrow params - x: " + arrowParams.x + ", y: " + arrowParams.y);
        
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(metrics);
        
        int screenWidth = metrics.widthPixels;
        int screenHeight = metrics.heightPixels;
        int snapThreshold = dpToPx(100); // Snap if within 100dp of edge
        
        // Calculate actual position
        int actualX = screenWidth - arrowParams.x;
        int actualY = arrowParams.y;
        
        // Determine closest edge
        int distanceToLeft = actualX;
        int distanceToRight = arrowParams.x;
        int distanceToTop = actualY;
        int distanceToBottom = screenHeight - actualY;
        
        int minDistance = Math.min(Math.min(distanceToLeft, distanceToRight), 
                                   Math.min(distanceToTop, distanceToBottom));
        
        // Only snap if within threshold
        if (minDistance > snapThreshold) {
            Log.d(TAG, "Not snapping - distance " + minDistance + " > threshold " + snapThreshold);
            currentEdge = EdgePosition.CENTER;
            windowManager.updateViewLayout(arrowOverlay, arrowParams);
            windowManager.updateViewLayout(toolbarView, menuParams);
            Log.d(TAG, "<<< snapToEdgeIfNeeded() - NO SNAP");
            return;
        }
        
        // Snap to nearest edge and update both overlays
        if (minDistance == distanceToLeft) {
            // Snap to LEFT edge
            Log.d(TAG, "Snapping to LEFT edge");
            currentEdge = EdgePosition.LEFT;
            arrowParams.x = screenWidth; // Fully touching left edge
            // Menu should be to the RIGHT of arrow (arrow is 20dp + 4dp gap)
            menuParams.x = screenWidth - dpToPx(304); // 280dp menu + 24dp for arrow+gap
            adaptLayoutForEdge(EdgePosition.LEFT);
            
        } else if (minDistance == distanceToRight) {
            // Snap to RIGHT edge
            Log.d(TAG, "Snapping to RIGHT edge");
            currentEdge = EdgePosition.RIGHT;
            arrowParams.x = 0; // Fully touching right edge
            menuParams.x = dpToPx(24); // Menu offset to left (20dp arrow + 4dp gap)
            adaptLayoutForEdge(EdgePosition.RIGHT);
            
        } else if (minDistance == distanceToTop) {
            // Snap to TOP edge
            Log.d(TAG, "Snapping to TOP edge");
            currentEdge = EdgePosition.TOP;
            arrowParams.y = 0; // Fully touching top edge
            // Menu should be BELOW arrow (arrow is 20dp + 4dp gap)
            menuParams.y = dpToPx(24);
            // Keep menu x centered with arrow
            menuParams.x = arrowParams.x;
            adaptLayoutForEdge(EdgePosition.TOP);
            
        } else {
            // Snap to BOTTOM edge
            Log.d(TAG, "Snapping to BOTTOM edge");
            currentEdge = EdgePosition.BOTTOM;
            arrowParams.y = screenHeight; // Fully touching bottom edge
            // Menu should be ABOVE arrow (need to account for menu height)
            // Use negative offset from bottom
            menuParams.y = screenHeight - dpToPx(500); // Menu max height + gap
            // Keep menu x centered with arrow
            menuParams.x = arrowParams.x;
            adaptLayoutForEdge(EdgePosition.BOTTOM);
        }
        
        Log.d(TAG, "Updating both overlays after snap");
        Log.d(TAG, "Arrow final - x: " + arrowParams.x + ", y: " + arrowParams.y);
        Log.d(TAG, "Menu final - x: " + menuParams.x + ", y: " + menuParams.y);
        
        windowManager.updateViewLayout(arrowOverlay, arrowParams);
        windowManager.updateViewLayout(toolbarView, menuParams);
        updateArrowDirection(arrowParams);
        
        Log.d(TAG, "<<< snapToEdgeIfNeeded() - SNAPPED to " + currentEdge);
    }
    
    /**
     * Adapt arrow button size based on edge position
     * Top/Bottom: horizontal layout (wider, shorter)
     * Left/Right: vertical layout (narrower, taller)
     */
    private void adaptLayoutForEdge(EdgePosition edge) {
        if (btnExpandCollapseContainer == null) {
            return;
        }

        ViewGroup.LayoutParams layoutParams = btnExpandCollapseContainer.getLayoutParams();

        int targetWidth;
        int targetHeight;

        switch (edge) {
            case TOP:
            case BOTTOM:
                targetWidth = dpToPx(48);
                targetHeight = dpToPx(20);
                break;

            case LEFT:
            case RIGHT:
            default:
                targetWidth = dpToPx(20);
                targetHeight = dpToPx(48);
                break;
        }

        if (layoutParams.width != targetWidth || layoutParams.height != targetHeight) {
            layoutParams.width = targetWidth;
            layoutParams.height = targetHeight;
            btnExpandCollapseContainer.setLayoutParams(layoutParams);
        }

        if (arrowParams != null && arrowOverlay != null) {
            arrowParams.width = targetWidth;
            arrowParams.height = targetHeight;
            try {
                windowManager.updateViewLayout(arrowOverlay, arrowParams);
            } catch (IllegalArgumentException ignored) {
                // View might not be attached yet; will be updated on next layout pass.
            }
        }
    }
    
    /**
     * Update arrow direction based on edge position
     * Also sets appropriate corner radius for edge
     */
    
    
    private void updateArrowDirection(WindowManager.LayoutParams params) {
        updateArrowDirection(params, false);
    }

    private void updateArrowDirection(WindowManager.LayoutParams params, boolean forceHint) {
        Log.d(TAG, ">>> updateArrowDirection() called");
        Log.d(TAG, "WindowManager params IN - x: " + params.x + ", y: " + params.y + ", gravity: " + params.gravity);
        
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(metrics);
        
        int screenWidth = metrics.widthPixels;
        int screenHeight = metrics.heightPixels;
        int actualX = screenWidth - params.x;
        int actualY = params.y;
        
        // Determine current edge if not explicitly set
        if (currentEdge == EdgePosition.CENTER) {
            int distanceToLeft = actualX;
            int distanceToRight = params.x;
            int distanceToTop = actualY;
            int distanceToBottom = screenHeight - actualY;
            
            int minDistance = Math.min(Math.min(distanceToLeft, distanceToRight), 
                                       Math.min(distanceToTop, distanceToBottom));
            
            if (minDistance == distanceToLeft) currentEdge = EdgePosition.LEFT;
            else if (minDistance == distanceToRight) currentEdge = EdgePosition.RIGHT;
            else if (minDistance == distanceToTop) currentEdge = EdgePosition.TOP;
            else currentEdge = EdgePosition.BOTTOM;
        }
        
        String newGlyph;
        int backgroundRes;

        if (!isExpanded) {
            switch (currentEdge) {
                case LEFT:
                    newGlyph = "chevron_right";
                    backgroundRes = R.drawable.compact_arrow_bg_left;
                    break;
                case RIGHT:
                    newGlyph = "chevron_left";
                    backgroundRes = R.drawable.compact_arrow_bg_right;
                    break;
                case TOP:
                    newGlyph = "expand_more";
                    backgroundRes = R.drawable.compact_arrow_bg_top;
                    break;
                case BOTTOM:
                    newGlyph = "expand_less";
                    backgroundRes = R.drawable.compact_arrow_bg_bottom;
                    break;
                case CENTER:
                    if (actualX < screenWidth / 2) {
                        newGlyph = "chevron_right";
                        backgroundRes = R.drawable.compact_arrow_bg_left;
                    } else {
                        newGlyph = "chevron_left";
                        backgroundRes = R.drawable.compact_arrow_bg_right;
                    }
                    break;
                default:
                    newGlyph = "chevron_left";
                    backgroundRes = R.drawable.compact_arrow_bg;
                    break;
            }
        } else {
            switch (currentEdge) {
                case LEFT:
                    newGlyph = "chevron_left";
                    backgroundRes = R.drawable.compact_arrow_bg_left;
                    break;
                case RIGHT:
                    newGlyph = "chevron_right";
                    backgroundRes = R.drawable.compact_arrow_bg_right;
                    break;
                case TOP:
                    newGlyph = "expand_less";
                    backgroundRes = R.drawable.compact_arrow_bg_top;
                    break;
                case BOTTOM:
                    newGlyph = "expand_more";
                    backgroundRes = R.drawable.compact_arrow_bg_bottom;
                    break;
                default:
                    newGlyph = "chevron_right";
                    backgroundRes = R.drawable.compact_arrow_bg;
                    break;
            }
        }

    btnExpandCollapseContainer.setBackgroundResource(backgroundRes);
    applyArrowGlyphWithRotation(newGlyph, forceHint);

    playArrowHintAnimation(newGlyph, forceHint);

        Log.d(TAG, "Arrow direction updated - text: " + btnExpandCollapse.getText() +
              ", isExpanded: " + isExpanded + ", edge: " + currentEdge);
        Log.d(TAG, "<<< updateArrowDirection() completed");
    }

    /**
     * Provide a quick directional hint by nudging the arrow slightly toward the
     * direction it will move. Runs on a cooldown to avoid constant animation spam.
     */
    private void applyArrowGlyphWithRotation(CharSequence newGlyph, boolean delayRotation) {
        if (btnExpandCollapse == null) {
            return;
        }

        if (TextUtils.isEmpty(newGlyph)) {
            return;
        }

        CharSequence currentGlyph = btnExpandCollapse.getText();
        if (TextUtils.equals(currentGlyph, newGlyph)) {
            resetArrowRotation();
            return;
        }

        if (arrowFlipAnimator != null) {
            arrowFlipAnimator.cancel();
        }

        if (btnExpandCollapse.getWidth() == 0 || btnExpandCollapse.getHeight() == 0) {
            btnExpandCollapse.setText(newGlyph);
            resetArrowRotation();
            return;
        }

        btnExpandCollapse.setPivotX(btnExpandCollapse.getWidth() / 2f);
        btnExpandCollapse.setPivotY(btnExpandCollapse.getHeight() / 2f);

        boolean verticalFlip = isVerticalGlyph(newGlyph);
        String rotationProperty = verticalFlip ? "rotationX" : "rotationY";

        float cameraDistance = 8000f * getResources().getDisplayMetrics().density;
        btnExpandCollapse.setCameraDistance(cameraDistance);

        ObjectAnimator collapseAnimator = ObjectAnimator.ofFloat(btnExpandCollapse, rotationProperty, 0f, 90f);
        collapseAnimator.setDuration(ARROW_ROTATION_DURATION_MS / 2);
        collapseAnimator.setInterpolator(ARROW_HINT_INTERPOLATOR);
        collapseAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                btnExpandCollapse.setText(newGlyph);
                if (verticalFlip) {
                    btnExpandCollapse.setRotationX(-90f);
                } else {
                    btnExpandCollapse.setRotationY(-90f);
                }
            }
        });

        ObjectAnimator expandAnimator = ObjectAnimator.ofFloat(btnExpandCollapse, rotationProperty, -90f, 0f);
        expandAnimator.setDuration(ARROW_ROTATION_DURATION_MS / 2);
        expandAnimator.setInterpolator(ARROW_HINT_INTERPOLATOR);

        arrowFlipAnimator = new AnimatorSet();
        arrowFlipAnimator.playSequentially(collapseAnimator, expandAnimator);
        arrowFlipAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                resetArrowRotation();
                arrowFlipAnimator = null;
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                btnExpandCollapse.setText(newGlyph);
                resetArrowRotation();
                arrowFlipAnimator = null;
            }
        });

        arrowFlipAnimator.setStartDelay(delayRotation ? ARROW_TOGGLE_DELAY_MS : 0L);
        arrowFlipAnimator.start();
    }

    private boolean isVerticalGlyph(CharSequence glyph) {
        return TextUtils.equals(glyph, "expand_more") || TextUtils.equals(glyph, "expand_less");
    }

    private void resetArrowRotation() {
        resetGlyphRotation(btnExpandCollapse);
    }

    private void resetGlyphRotation(TextView targetView) {
        if (targetView == null) {
            return;
        }
        targetView.setRotationX(0f);
        targetView.setRotationY(0f);
    }

    private void applySectionChevronGlyph(TextView iconView, CharSequence newGlyph) {
        if (iconView == null) {
            return;
        }

        if (TextUtils.isEmpty(newGlyph)) {
            return;
        }

        CharSequence currentGlyph = iconView.getText();
        if (TextUtils.equals(currentGlyph, newGlyph)) {
            resetGlyphRotation(iconView);
            return;
        }

        AnimatorSet existingAnimator = sectionChevronAnimators.remove(iconView);
        if (existingAnimator != null) {
            existingAnimator.cancel();
        }

        if (iconView.getWidth() == 0 || iconView.getHeight() == 0) {
            iconView.setText(newGlyph);
            resetGlyphRotation(iconView);
            return;
        }

        iconView.setPivotX(iconView.getWidth() / 2f);
        iconView.setPivotY(iconView.getHeight() / 2f);

        boolean verticalFlip = isVerticalGlyph(newGlyph);
        String rotationProperty = verticalFlip ? "rotationX" : "rotationY";

        float cameraDistance = 8000f * getResources().getDisplayMetrics().density;
        iconView.setCameraDistance(cameraDistance);

        ObjectAnimator collapseAnimator = ObjectAnimator.ofFloat(iconView, rotationProperty, 0f, 90f);
        collapseAnimator.setDuration(ARROW_ROTATION_DURATION_MS / 2);
        collapseAnimator.setInterpolator(ARROW_HINT_INTERPOLATOR);
        collapseAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                iconView.setText(newGlyph);
                if (verticalFlip) {
                    iconView.setRotationX(-90f);
                } else {
                    iconView.setRotationY(-90f);
                }
            }
        });

        ObjectAnimator expandAnimator = ObjectAnimator.ofFloat(iconView, rotationProperty, -90f, 0f);
        expandAnimator.setDuration(ARROW_ROTATION_DURATION_MS / 2);
        expandAnimator.setInterpolator(ARROW_HINT_INTERPOLATOR);

        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.playSequentially(collapseAnimator, expandAnimator);
        animatorSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                resetGlyphRotation(iconView);
                sectionChevronAnimators.remove(iconView);
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                iconView.setText(newGlyph);
                resetGlyphRotation(iconView);
                sectionChevronAnimators.remove(iconView);
            }
        });

        sectionChevronAnimators.put(iconView, animatorSet);
        animatorSet.start();
    }

    private void playArrowHintAnimation(CharSequence glyph, boolean force) {
        if (btnExpandCollapse == null) {
            return;
        }

        CharSequence appliedGlyph = glyph;
        if (TextUtils.isEmpty(appliedGlyph)) {
            appliedGlyph = btnExpandCollapse.getText();
        }

        if (pendingArrowHintRunnable != null) {
            btnExpandCollapse.removeCallbacks(pendingArrowHintRunnable);
            pendingArrowHintRunnable = null;
        }

        btnExpandCollapse.animate().cancel();
        btnExpandCollapse.setTranslationX(0f);
        btnExpandCollapse.setTranslationY(0f);

        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastArrowHintTimestamp < ARROW_HINT_COOLDOWN_MS) {
            return;
        }

        final float offset = dpToPx(5);
        float endX = 0f;
        float endY = 0f;

        if (TextUtils.equals(appliedGlyph, "chevron_left")) {
            endX = -offset;
        } else if (TextUtils.equals(appliedGlyph, "chevron_right")) {
            endX = offset;
        } else if (TextUtils.equals(appliedGlyph, "expand_more")) {
            endY = offset;
        } else if (TextUtils.equals(appliedGlyph, "expand_less")) {
            endY = -offset;
        }

        if (endX == 0f && endY == 0f) {
            switch (currentEdge) {
                case LEFT:
                    endX = isExpanded ? -offset : offset;
                    break;
                case RIGHT:
                    endX = isExpanded ? offset : -offset;
                    break;
                case TOP:
                    endY = isExpanded ? -offset : offset;
                    break;
                case BOTTOM:
                    endY = isExpanded ? offset : -offset;
                    break;
                default:
                    endX = isExpanded ? offset : -offset;
                    break;
            }
        }

        final float targetX = endX;
        final float targetY = endY;

        pendingArrowHintRunnable = () -> {
            lastArrowHintTimestamp = SystemClock.elapsedRealtime();
            btnExpandCollapse.animate()
                .translationX(targetX)
                .translationY(targetY)
                .setDuration(ARROW_HINT_OUT_DURATION_MS)
                .setInterpolator(ARROW_HINT_INTERPOLATOR)
                .withEndAction(() -> btnExpandCollapse.animate()
                    .translationX(0f)
                    .translationY(0f)
                    .setStartDelay(ARROW_HINT_PAUSE_MS)
                    .setDuration(ARROW_HINT_RETURN_DURATION_MS)
                    .setInterpolator(ARROW_HINT_INTERPOLATOR)
                    .withEndAction(() -> pendingArrowHintRunnable = null)
                    .start())
                .start();
        };

    long startDelay = ARROW_HINT_START_DELAY_MS + (force ? ARROW_TOGGLE_DELAY_MS : 0L);
    btnExpandCollapse.postDelayed(pendingArrowHintRunnable, startDelay);
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
    
    /**
     * Shows dialog to name/rename the current project and add description.
     */
    private void showProjectNamingDialog() {
        Intent intent = new Intent(this, ProjectNamingDialogActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("currentProjectName", currentProjectName);
        
        // Load current description if it exists
        AnnotationState currentState = annotationView.getState();
        if (currentState != null && currentState.getMetadata() != null) {
            String description = currentState.getMetadata().optString("description", "");
            intent.putExtra("currentDescription", description);
        }
        
        startActivity(intent);
    }
    
    /**
     * Shows dialog to browse and manage all projects.
     * Uses the existing project management dialog from notification.
     */
    private void showProjectsDialog() {
        showProjectManagementDialog();
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
                    Log.d(TAG, "=== PAGE ADD STARTED ===");
                    Log.d(TAG, "Current page count: " + state.getPages().size());
                    Log.d(TAG, "New page name: " + pageName);
                    
                    annotationView.addPage(pageName);
                    annotationView.switchToPage(state.getPages().size() - 1);
                    
                    Log.d(TAG, "Page added. New page count: " + state.getPages().size());
                    Log.d(TAG, "Undo count: " + annotationView.getUndoCount());
                    Log.d(TAG, "Redo count: " + annotationView.getRedoCount());
                    
                    updateUndoRedoButtons();
                    Toast.makeText(AnnotationService.this, "✨ Created: " + pageName, Toast.LENGTH_SHORT).show();
                    Log.d(TAG, "=== PAGE ADD COMPLETED ===");
                }
                
                @Override
                public void onPageDeleted(int index) {
                    if (state.getPages().size() > 1) {
                        String pageName = state.getPages().get(index).getName();
                        Log.d(TAG, "=== PAGE DELETE STARTED ===");
                        Log.d(TAG, "Deleting page: " + pageName + " at index: " + index);
                        Log.d(TAG, "Current page count: " + state.getPages().size());
                        
                        state.removePage(index);
                        annotationView.invalidate();
                        annotationView.notifyStateChanged(); // Trigger undo/redo snapshot
                        
                        Log.d(TAG, "Page deleted. New page count: " + state.getPages().size());
                        Log.d(TAG, "Undo count: " + annotationView.getUndoCount());
                        Log.d(TAG, "Redo count: " + annotationView.getRedoCount());
                        
                        updateUndoRedoButtons();
                        pageTabBarOverlay.refresh();
                        Toast.makeText(AnnotationService.this, "🗑️ Deleted: " + pageName, Toast.LENGTH_SHORT).show();
                        Log.d(TAG, "=== PAGE DELETE COMPLETED ===");
                    }
                }

                @Override
                public void onPageReordered(int fromIndex, int toIndex) {
                    Log.d(TAG, "=== PAGE REORDER STARTED ===");
                    Log.d(TAG, "From index: " + fromIndex + ", To index: " + toIndex);
                    AnnotationPage movedPage = null;
                    if (fromIndex >= 0 && fromIndex < state.getPages().size()) {
                        movedPage = state.getPages().get(fromIndex);
                        Log.d(TAG, "Page being moved: " + movedPage.getName());
                    }

                    state.movePage(fromIndex, toIndex);
                    annotationView.invalidate();
                    annotationView.notifyStateChanged();
                    updateUndoRedoButtons();
                    updatePageLayerInfo();
                    pageTabBarOverlay.refresh();
                    if (layerPanelOverlay != null && layerPanelOverlay.isShowing()) {
                        layerPanelOverlay.refresh();
                    }

                    if (movedPage != null) {
                        Toast.makeText(AnnotationService.this,
                            "↕️ Reordered: " + movedPage.getName(), Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(AnnotationService.this,
                            "Pages reordered", Toast.LENGTH_SHORT).show();
                    }

                    Log.d(TAG, "Active page index is now: " + state.getActivePageIndex());
                    Log.d(TAG, "=== PAGE REORDER COMPLETED ===");
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
                        Log.d(TAG, "Layer opacity changed: index=" + index + ", opacity=" + opacity);
                        currentPage.getLayers().get(index).setOpacity(opacity);
                        annotationView.invalidate();
                    }
                    
                    @Override
                    public void onLayerAdded() {
                        int newLayerNumber = currentPage.getLayers().size() + 1;
                        String layerName = "Layer " + newLayerNumber;
                        Log.d(TAG, "=== LAYER ADD STARTED ===");
                        Log.d(TAG, "Current layer count: " + currentPage.getLayers().size());
                        Log.d(TAG, "New layer name: " + layerName);
                        
                        // Use command pattern to enable undo/redo
                        com.fadcam.fadrec.ui.annotation.AddLayerCommand command = 
                            new com.fadcam.fadrec.ui.annotation.AddLayerCommand(currentPage, layerName);
                        currentPage.executeCommand(command);
                        annotationView.invalidate();
                        annotationView.notifyStateChanged();
                        
                        Log.d(TAG, "Layer added. New layer count: " + currentPage.getLayers().size());
                        Log.d(TAG, "Undo count: " + annotationView.getUndoCount());
                        Log.d(TAG, "Redo count: " + annotationView.getRedoCount());
                        
                        updatePageLayerInfo();
                        layerPanelOverlay.refresh();
                        Toast.makeText(AnnotationService.this, "✨ Created: " + layerName, Toast.LENGTH_SHORT).show();
                        Log.d(TAG, "=== LAYER ADD COMPLETED ===");
                    }
                    
                    @Override
                    public void onLayerDeleted(int index) {
                        if (currentPage.getLayers().size() > 1) {
                            String layerName = currentPage.getLayers().get(index).getName();
                            Log.d(TAG, "=== LAYER DELETE STARTED ===");
                            Log.d(TAG, "Deleting layer: " + layerName + " at index: " + index);
                            Log.d(TAG, "Current layer count: " + currentPage.getLayers().size());
                            
                            // Use command pattern to enable undo/redo
                            com.fadcam.fadrec.ui.annotation.DeleteLayerCommand command = 
                                new com.fadcam.fadrec.ui.annotation.DeleteLayerCommand(currentPage, index);
                            currentPage.executeCommand(command);
                            annotationView.invalidate();
                            annotationView.notifyStateChanged();
                            
                            Log.d(TAG, "Layer deleted. New layer count: " + currentPage.getLayers().size());
                            Log.d(TAG, "Undo count: " + annotationView.getUndoCount());
                            Log.d(TAG, "Redo count: " + annotationView.getRedoCount());
                            
                            updatePageLayerInfo();
                            layerPanelOverlay.refresh();
                            Toast.makeText(AnnotationService.this, "🗑️ Deleted: " + layerName, Toast.LENGTH_SHORT).show();
                            Log.d(TAG, "=== LAYER DELETE COMPLETED ===");
                        }
                    }

                    @Override
                    public void onLayersReordered(int fromIndex, int toIndex) {
                        Log.d(TAG, "=== LAYER REORDER STARTED ===");
                        Log.d(TAG, "From index: " + fromIndex + ", To index: " + toIndex);
                        AnnotationLayer layer = null;
                        if (fromIndex >= 0 && fromIndex < currentPage.getLayers().size()) {
                            layer = currentPage.getLayers().get(fromIndex);
                            Log.d(TAG, "Layer being moved: " + layer.getName());
                        }

                        currentPage.moveLayer(fromIndex, toIndex);
                        annotationView.invalidate();
                        annotationView.notifyStateChanged();
                        updatePageLayerInfo();
                        layerPanelOverlay.refresh();

                        if (layer != null) {
                            Toast.makeText(AnnotationService.this,
                                "↕️ Reordered: " + layer.getName(), Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(AnnotationService.this,
                                "Layers reordered", Toast.LENGTH_SHORT).show();
                        }

                        Log.d(TAG, "Active layer index is now: " + currentPage.getActiveLayerIndex());
                        Log.d(TAG, "=== LAYER REORDER COMPLETED ===");
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
        // Open app intent - tap notification to open FadCam app
        Intent openAppIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (openAppIntent == null) {
            openAppIntent = new Intent(this, com.fadcam.MainActivity.class);
        }
        openAppIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        android.app.PendingIntent openAppPendingIntent = android.app.PendingIntent.getActivity(
                this, 0, openAppIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE
        );
        
    // Toggle menu action
    Intent toggleIntent = new Intent(this, AnnotationService.class);
    toggleIntent.setAction("ACTION_TOGGLE_MENU");
    android.app.PendingIntent togglePendingIntent = android.app.PendingIntent.getService(
        this, 1, toggleIntent,
        android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE
    );

        String annotationStatus = annotationEnabled ? " | ✏️ Enabled" : " | 📱 Disabled";
    String toggleLabel = overlayVisible
        ? getString(R.string.annotation_notification_hide_menu)
        : getString(R.string.annotation_notification_show_menu);
        
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("FadRec Annotation" + annotationStatus)
                .setContentText("Tap to open FadCam • Project: " + (currentProjectName != null ? currentProjectName : "None"))
                .setSmallIcon(R.drawable.ic_draw_edit)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setContentIntent(openAppPendingIntent)
        .addAction(R.drawable.ic_draw_edit, toggleLabel, togglePendingIntent)
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
    
    /**
     * Registers broadcast receiver to handle project rename events.
     */
    private void registerProjectNamingReceiver() {
        projectNamingReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String newName = intent.getStringExtra(ProjectNamingDialogActivity.EXTRA_PROJECT_NAME);
                String newDescription = intent.getStringExtra(ProjectNamingDialogActivity.EXTRA_PROJECT_DESCRIPTION);
                
                AnnotationState state = annotationView != null ? annotationView.getState() : null;
                
                if (newName != null && !newName.isEmpty() && !newName.equals(currentProjectName)) {
                    // User is renaming the project
                    String oldName = currentProjectName;
                    String sanitizedNewName = ProjectFileManager.sanitizeProjectName(newName);
                    
                    Log.i(TAG, "========== PROJECT RENAME ==========");
                    Log.i(TAG, "Old name: '" + oldName + "'");
                    Log.i(TAG, "New name (user input): '" + newName + "'");
                    Log.i(TAG, "New name (sanitized): '" + sanitizedNewName + "'");
                    
                    // Update metadata with sanitized name
                    if (state != null && state.getMetadata() != null) {
                        try {
                            state.getMetadata().put("name", sanitizedNewName);
                            if (newDescription != null) {
                                state.getMetadata().put("description", newDescription);
                            }
                            Log.d(TAG, "Metadata updated");
                        } catch (org.json.JSONException e) {
                            Log.e(TAG, "Failed to update metadata", e);
                        }
                    }
                    
                    // Save current state to old file first
                    projectFileManager.saveProject(state, oldName);
                    Log.d(TAG, "Saved state to old file: " + oldName);
                    
                    // Rename the project folder with sanitized name
                    boolean renamed = projectFileManager.renameProject(oldName, sanitizedNewName);
                    
                    if (renamed) {
                        // Update current project name AFTER successful rename
                        currentProjectName = sanitizedNewName;
                        Log.i(TAG, "✅ Project renamed successfully");
                        
                        // Update preferences with new name
                        android.content.SharedPreferences prefs = getSharedPreferences("fadrec_prefs", MODE_PRIVATE);
                        prefs.edit().putString("current_project", currentProjectName).apply();
                        
                        // Update UI immediately
                        updateProjectNameDisplay();
                        updateNotification();
                    } else {
                        Log.e(TAG, "❌ Failed to rename project file, keeping old name");
                        // Revert metadata if rename failed
                        try {
                            if (state != null && state.getMetadata() != null) {
                                state.getMetadata().put("name", oldName);
                            }
                        } catch (org.json.JSONException e) {
                            Log.e(TAG, "Failed to revert metadata", e);
                        }
                    }
                    
                    Log.i(TAG, "====================================");
                } else {
                    // Just updating description (or keeping same name)
                    if (state != null && state.getMetadata() != null) {
                        try {
                            if (newDescription != null) {
                                state.getMetadata().put("description", newDescription);
                                Log.d(TAG, "Description updated to: " + newDescription);
                            }
                            
                            // Save the project with updated description
                            projectFileManager.saveProject(state, currentProjectName);
                            Log.d(TAG, "Project saved with updated metadata");
                            
                            // Update UI immediately
                            updateProjectNameDisplay();
                        } catch (org.json.JSONException e) {
                            Log.e(TAG, "Failed to update description", e);
                        }
                    }
                }
            }
        };
        
        IntentFilter filter = new IntentFilter(ProjectNamingDialogActivity.ACTION_PROJECT_RENAMED);
        registerReceiver(projectNamingReceiver, filter);
        Log.d(TAG, "Project naming receiver registered");
    }
    
    /**
     * Registers broadcast receiver to handle project selection from project manager.
     */
    private void registerProjectSelectionReceiver() {
        projectSelectionReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String selectedProject = intent.getStringExtra(ProjectSelectionDialogActivity.EXTRA_PROJECT_NAME);
                
                if (selectedProject != null && !selectedProject.isEmpty()) {
                    Log.i(TAG, "========== PROJECT SWITCH ==========");
                    Log.i(TAG, "Current project: " + currentProjectName);
                    Log.i(TAG, "Loading project: " + selectedProject);
                    
                    // Save current project before switching
                    if (annotationView != null && currentProjectName != null) {
                        AnnotationState currentState = annotationView.getState();
                        if (currentState != null) {
                            projectFileManager.saveProject(currentState, currentProjectName);
                            Log.d(TAG, "Saved current project: " + currentProjectName);
                        }
                    }
                    
                    // Load the selected project
                    loadProject(selectedProject);
                    
                    Log.i(TAG, "====================================");
                }
            }
        };
        
        IntentFilter filter = new IntentFilter(ProjectSelectionDialogActivity.ACTION_PROJECT_SELECTED);
        registerReceiver(projectSelectionReceiver, filter);
        Log.d(TAG, "Project selection receiver registered");
        
        // Register layer rename receiver
        layerRenameReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int layerIndex = intent.hasExtra("layer_index")
                    ? intent.getIntExtra("layer_index", -1)
                    : intent.getIntExtra("layerIndex", -1);
                String currentName = intent.hasExtra("layer_name")
                    ? intent.getStringExtra("layer_name")
                    : intent.getStringExtra("currentName");
                if (layerIndex >= 0) {
                    showLayerRenameDialog(layerIndex, currentName);
                }
            }
        };
        IntentFilter layerRenameFilter = new IntentFilter("com.fadcam.fadrec.RENAME_LAYER");
        registerReceiver(layerRenameReceiver, layerRenameFilter);
        
        // Register page rename receiver
        pageRenameReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int pageIndex = intent.getIntExtra("page_index", -1);
                String currentName = intent.getStringExtra("page_name");
                if (pageIndex >= 0) {
                    showPageRenameDialog(pageIndex, currentName);
                }
            }
        };
        IntentFilter pageRenameFilter = new IntentFilter("com.fadcam.fadrec.RENAME_PAGE");
        registerReceiver(pageRenameReceiver, pageRenameFilter);
    }
    
    @FunctionalInterface
    private interface RenameConfirmListener {
        void onRenameConfirmed(@NonNull String newName);
    }

    private void showRenameDialog(@StringRes int titleRes,
                                  @StringRes int messageRes,
                                  String currentName,
                                  RenameConfirmListener confirmListener) {
        Context themedContext = new ContextThemeWrapper(this, R.style.Base_Theme_FadCam);
        View dialogView = LayoutInflater.from(themedContext).inflate(R.layout.dialog_material_rename, null);

        TextInputLayout inputLayout = dialogView.findViewById(R.id.inputLayout);
        TextInputEditText inputName = dialogView.findViewById(R.id.inputName);

        if (inputName != null) {
            if (!TextUtils.isEmpty(currentName)) {
                inputName.setText(currentName);
                inputName.setSelection(currentName.length());
            }
            inputName.setSelectAllOnFocus(true);
        }

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(themedContext)
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setView(dialogView)
            .setNegativeButton(R.string.rename_dialog_negative, (dialog, which) -> dialog.dismiss())
            .setPositiveButton(R.string.rename_dialog_positive, null);

        AlertDialog dialog = builder.create();

        if (dialog.getWindow() != null) {
            int layoutType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
            dialog.getWindow().setType(layoutType);
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }

        dialog.setOnShowListener(d -> {
            Button positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (positiveButton != null) {
                positiveButton.setOnClickListener(v -> {
                    if (inputName == null) {
                        dialog.dismiss();
                        return;
                    }
                    String newName = inputName.getText() != null
                        ? inputName.getText().toString().trim()
                        : "";
                    if (TextUtils.isEmpty(newName)) {
                        if (inputLayout != null) {
                            inputLayout.setError(getString(R.string.rename_dialog_error_empty));
                        }
                        return;
                    }
                    if (inputLayout != null) {
                        inputLayout.setError(null);
                    }
                    confirmListener.onRenameConfirmed(newName);
                    dialog.dismiss();
                });
            }

            if (inputName != null) {
                inputName.requestFocus();
                final Button confirmButton = positiveButton;
                inputName.setOnEditorActionListener((v, actionId, event) -> {
                    if (actionId == EditorInfo.IME_ACTION_DONE && confirmButton != null) {
                        confirmButton.performClick();
                        return true;
                    }
                    return false;
                });
                inputName.post(() -> {
                    InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.showSoftInput(inputName, InputMethodManager.SHOW_IMPLICIT);
                    }
                });
            }
        });

        dialog.setOnDismissListener(d -> {
            if (inputName != null) {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.hideSoftInputFromWindow(inputName.getWindowToken(), 0);
                }
            }
        });

        dialog.show();
    }

    /**
     * Show rename dialog for layer (Material Design)
     */
    private void showLayerRenameDialog(int layerIndex, String currentName) {
        Log.d(TAG, "showLayerRenameDialog called for index: " + layerIndex + ", name: " + currentName);
        if (annotationView == null) {
            Log.w(TAG, "AnnotationView is null, cannot show rename dialog");
            return;
        }
        AnnotationState currentState = annotationView.getState();

        try {
            showRenameDialog(
                R.string.rename_layer_title,
                R.string.rename_layer_message,
                currentName,
                newName -> {
                    AnnotationPage currentPage = currentState.getActivePage();
                    if (currentPage != null && layerIndex < currentPage.getLayers().size()) {
                        currentPage.getLayers().get(layerIndex).setName(newName);
                        if (layerPanelOverlay != null) {
                            layerPanelOverlay.refresh();
                        }
                        Log.d(TAG, "Layer renamed to: " + newName);
                        Toast.makeText(this, getString(R.string.rename_dialog_toast_success, newName), Toast.LENGTH_SHORT).show();
                    }
                }
            );
            Log.d(TAG, "Rename layer dialog shown");
        } catch (Exception e) {
            Log.e(TAG, "Error showing rename layer dialog", e);
            Toast.makeText(this, "Error showing rename dialog", Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * Show rename dialog for page (Material Design)
     */
    private void showPageRenameDialog(int pageIndex, String currentName) {
        Log.d(TAG, "showPageRenameDialog called for index: " + pageIndex + ", name: " + currentName);
        if (annotationView == null) {
            Log.w(TAG, "AnnotationView is null, cannot show rename dialog");
            return;
        }
        AnnotationState currentState = annotationView.getState();

        try {
            showRenameDialog(
                R.string.rename_page_title,
                R.string.rename_page_message,
                currentName,
                newName -> {
                    if (pageIndex < currentState.getPages().size()) {
                        currentState.getPages().get(pageIndex).setName(newName);
                        if (pageTabBarOverlay != null) {
                            pageTabBarOverlay.refresh();
                        }
                        updatePageLayerInfo();
                        Log.d(TAG, "Page renamed to: " + newName);
                        Toast.makeText(this, getString(R.string.rename_dialog_toast_success, newName), Toast.LENGTH_SHORT).show();
                    }
                }
            );
            Log.d(TAG, "Rename page dialog shown");
        } catch (Exception e) {
            Log.e(TAG, "Error showing rename page dialog", e);
            Toast.makeText(this, "Error showing rename dialog", Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * Create EditText for input dialogs
     */
    private android.widget.EditText createInputEditText(String currentValue) {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setId(android.R.id.edit);
        input.setText(currentValue);
        input.setSelectAllOnFocus(true);
        input.setSingleLine(true);
        input.setPadding(50, 40, 50, 40);
        return input;
    }
    
    /**
     * Updates the project name and description display in the toolbar.
     */
    private void updateProjectNameDisplay() {
        if (toolbarView == null) return;
        
        android.widget.TextView txtCurrentProjectName = toolbarView.findViewById(R.id.txtCurrentProjectName);
        android.widget.TextView txtCurrentProjectDesc = toolbarView.findViewById(R.id.txtCurrentProjectDesc);
        
        if (txtCurrentProjectName != null) {
            // Always show the sanitized folder name (what's actually used on disk)
            String sanitizedName = currentProjectName != null && !currentProjectName.isEmpty()
                ? ProjectFileManager.sanitizeProjectName(currentProjectName)
                : getString(R.string.untitled_project);
            txtCurrentProjectName.setText(sanitizedName);
        }
        
        if (txtCurrentProjectDesc != null) {
            AnnotationState state = annotationView != null ? annotationView.getState() : null;
            String description = "";
            if (state != null && state.getMetadata() != null) {
                description = state.getMetadata().optString("description", "");
            }
            
            txtCurrentProjectDesc.setText(description.isEmpty() 
                ? getString(R.string.tap_to_add_description) 
                : description);
            
            // Dim text if no description
            txtCurrentProjectDesc.setAlpha(description.isEmpty() ? 0.5f : 1.0f);
        }
    }
    
    private void updateRecordingButtons() {
        if (btnStartStopRec == null) return;
        
        switch (recordingState) {
            case NONE:
                // Collapsed state - show green "Ready to record" button
                if (isRecordingControlsExpanded) {
                    toggleRecordingControlsExpansion(); // Collapse to single button
                }
                break;
                
            case IN_PROGRESS:
                // Expanded state - show Stop (red) and Pause (orange) buttons
                if (!isRecordingControlsExpanded) {
                    toggleRecordingControlsExpansion(); // Expand to 2 buttons
                }
                
                // Stop button - enabled with red background
                btnStartStopRec.setEnabled(true);
                btnStartStopRec.setAlpha(1.0f);
                btnStartStopRec.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    getResources().getColor(android.R.color.holo_red_light)));
                iconStartStop.setText("stop");
                iconStartStop.setTextColor(getResources().getColor(android.R.color.white));
                labelStartStop.setText(R.string.floating_menu_stop_short);
                labelStartStop.setTextColor(getResources().getColor(android.R.color.white));
                
                // Pause button - enabled with darker orange background for better contrast
                btnPauseResumeRec.setEnabled(true);
                btnPauseResumeRec.setAlpha(1.0f);
                btnPauseResumeRec.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.parseColor("#EF6C00"))); // Darker orange (Material Orange 800)
                iconPauseResume.setText("pause");
                iconPauseResume.setTextColor(getResources().getColor(android.R.color.white));
                labelPauseResume.setText(R.string.floating_menu_pause);
                labelPauseResume.setTextColor(getResources().getColor(android.R.color.white));
                break;
                
            case PAUSED:
                // Expanded state - show Stop (red) and Resume (green) buttons
                if (!isRecordingControlsExpanded) {
                    toggleRecordingControlsExpansion(); // Expand to 2 buttons
                }
                
                // Stop button - enabled with red background
                btnStartStopRec.setEnabled(true);
                btnStartStopRec.setAlpha(1.0f);
                btnStartStopRec.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    getResources().getColor(android.R.color.holo_red_light)));
                iconStartStop.setText("stop");
                iconStartStop.setTextColor(getResources().getColor(android.R.color.white));
                labelStartStop.setText(R.string.floating_menu_stop_short);
                labelStartStop.setTextColor(getResources().getColor(android.R.color.white));
                
                // Resume button - enabled with same green as main button (#4CAF50)
                btnPauseResumeRec.setEnabled(true);
                btnPauseResumeRec.setAlpha(1.0f);
                btnPauseResumeRec.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.parseColor("#4CAF50"))); // Same green as "Ready to record" button
                iconPauseResume.setText("play_arrow");
                iconPauseResume.setTextColor(getResources().getColor(android.R.color.white));
                labelPauseResume.setText(R.string.floating_menu_resume);
                labelPauseResume.setTextColor(getResources().getColor(android.R.color.white));
                break;
        }
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
        
        // Update button appearance and state
        btnToggleAnnotation.setSelected(enabled); // Set selected state for green border
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
        
        // Update button appearance and state
        btnToggleCanvasVisibility.setSelected(!visible); // Set selected state when canvas is hidden
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
     * Toggle recording controls expansion (collapsed single button <-> expanded 2 buttons)
     */
    private void toggleRecordingControlsExpansion() {
        isRecordingControlsExpanded = !isRecordingControlsExpanded;
        
        if (isRecordingControlsExpanded) {
            // Animate collapse to expand
            btnRecordingCollapsed.setVisibility(View.GONE);
            recordingControlsExpanded.setVisibility(View.VISIBLE);
            
            // Optionally add fade animation
            recordingControlsExpanded.setAlpha(0f);
            recordingControlsExpanded.animate()
                .alpha(1f)
                .setDuration(200)
                .start();
        } else {
            // Animate expand to collapse
            recordingControlsExpanded.animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction(() -> {
                    recordingControlsExpanded.setVisibility(View.GONE);
                    btnRecordingCollapsed.setVisibility(View.VISIBLE);
                    btnRecordingCollapsed.setAlpha(0f);
                    btnRecordingCollapsed.animate()
                        .alpha(1f)
                        .setDuration(200)
                        .start();
                })
                .start();
        }
        
        Log.d(TAG, "Recording controls expanded: " + isRecordingControlsExpanded);
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
        if (btnWidthExtraThick != null) {
            btnWidthExtraThick.setAlpha(alpha);
            btnWidthExtraThick.setEnabled(enabled);
            btnWidthExtraThick.setClickable(enabled);
        }
        
        // Color picker (custom color button)
        if (btnColorPicker != null) {
            btnColorPicker.setAlpha(alpha);
            btnColorPicker.setEnabled(enabled);
            btnColorPicker.setClickable(enabled);
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
        
        // Snap guides icon, label, and switch
        if (iconSnapGuides != null) iconSnapGuides.setAlpha(alpha);
        if (labelSnapGuides != null) labelSnapGuides.setAlpha(alpha);
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
        if (arrowOverlay != null) {
            arrowOverlay.setVisibility(View.GONE);
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
        if (arrowOverlay != null) {
            arrowOverlay.setVisibility(View.VISIBLE);
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
        if (projectNamingReceiver != null) {
            unregisterReceiver(projectNamingReceiver);
        }
        if (projectSelectionReceiver != null) {
            unregisterReceiver(projectSelectionReceiver);
        }
        if (layerRenameReceiver != null) {
            unregisterReceiver(layerRenameReceiver);
        }
        if (pageRenameReceiver != null) {
            unregisterReceiver(pageRenameReceiver);
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
        
        // Remove both overlays
        if (arrowOverlay != null) {
            windowManager.removeView(arrowOverlay);
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
