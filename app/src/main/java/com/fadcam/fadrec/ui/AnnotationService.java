package com.fadcam.fadrec.ui;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.fadcam.R;

/**
 * Service that provides floating annotation tools for drawing on screen during recording.
 * Manages the annotation canvas and toolbar overlay.
 */
public class AnnotationService extends Service {
    private static final String TAG = "AnnotationService";
    private static final String CHANNEL_ID = "AnnotationServiceChannel";
    private static final int NOTIFICATION_ID = 3003;
    
    private WindowManager windowManager;
    private AnnotationView annotationView;
    private View toolbarView;
    
    // Toolbar controls
    private TextView btnExpandCollapse;
    private TextView btnCloseAnnotation;
    private View expandableToolsSection;
    private View btnPenTool, btnEraserTool;
    private TextView iconPenTool, iconEraserTool;
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
        
        setupAnnotationCanvas();
        setupToolbar();
    }
    
    private void setupAnnotationCanvas() {
        annotationView = new AnnotationView(this);
        
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
        Log.d(TAG, "Annotation canvas added to window");
    }
    
    private void setupToolbar() {
        LayoutInflater inflater = LayoutInflater.from(this);
        toolbarView = inflater.inflate(R.layout.annotation_toolbar, null);
        
        // Initialize toolbar controls
        btnExpandCollapse = toolbarView.findViewById(R.id.btnExpandCollapse);
        btnCloseAnnotation = toolbarView.findViewById(R.id.btnCloseAnnotation);
        expandableToolsSection = toolbarView.findViewById(R.id.expandableToolsSection);
        
        btnPenTool = toolbarView.findViewById(R.id.btnPenTool);
        btnEraserTool = toolbarView.findViewById(R.id.btnEraserTool);
        iconPenTool = toolbarView.findViewById(R.id.iconPenTool);
        iconEraserTool = toolbarView.findViewById(R.id.iconEraserTool);
        
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
        btnPenTool.setOnClickListener(v -> {
            annotationView.setPenMode();
            updateToolSelection(true);
        });
        
        btnEraserTool.setOnClickListener(v -> {
            annotationView.setEraserMode();
            updateToolSelection(false);
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
        
        if (annotationView != null) {
            windowManager.removeView(annotationView);
        }
        
        if (toolbarView != null) {
            windowManager.removeView(toolbarView);
        }
    }
}
