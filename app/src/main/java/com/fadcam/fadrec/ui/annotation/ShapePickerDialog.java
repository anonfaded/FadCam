package com.fadcam.fadrec.ui.annotation;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.Switch;

import androidx.annotation.NonNull;

import com.fadcam.R;
import com.fadcam.fadrec.ui.annotation.objects.ShapeObject;

/**
 * Cute and user-friendly dialog for selecting shape type and properties.
 */
public class ShapePickerDialog extends Dialog {
    
    public interface OnShapeSelectedListener {
        void onShapeSelected(ShapeObject.ShapeType shapeType, int color, boolean filled);
    }
    
    private OnShapeSelectedListener listener;
    
    private ShapeObject.ShapeType selectedShape = ShapeObject.ShapeType.RECTANGLE;
    private int shapeColor = 0xFFFF5722; // Orange default
    private boolean filled = true;
    
    private LinearLayout[] shapeButtons;
    private View[] colorViews;
    private Switch switchFillShape;
    
    public ShapePickerDialog(@NonNull Context context) {
        super(context);
    }
    
    public void setOnShapeSelectedListener(OnShapeSelectedListener listener) {
        this.listener = listener;
    }
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_shape_picker);
        
        // Configure as system alert window for Service context
        if (getWindow() != null) {
            getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            
            // Set window type for showing from Service
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
            } else {
                getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
            }
            
            // Add flags for proper behavior
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH);
            
            // Set dialog width to 90% of screen width for better visibility
            WindowManager.LayoutParams params = getWindow().getAttributes();
            params.width = (int) (getContext().getResources().getDisplayMetrics().widthPixels * 0.9);
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            getWindow().setAttributes(params);
        }
        
        findViews();
        setupListeners();
    }
    
    private void findViews() {
        shapeButtons = new LinearLayout[]{
            findViewById(R.id.btnShapeRectangle),
            findViewById(R.id.btnShapeCircle),
            findViewById(R.id.btnShapeArrow),
            findViewById(R.id.btnShapeTriangle),
            findViewById(R.id.btnShapeStar),
            findViewById(R.id.btnShapeLine)
        };
        
        colorViews = new View[]{
            findViewById(R.id.shapeColorRed),
            findViewById(R.id.shapeColorOrange),
            findViewById(R.id.shapeColorYellow),
            findViewById(R.id.shapeColorGreen),
            findViewById(R.id.shapeColorBlue),
            findViewById(R.id.shapeColorPurple)
        };
        
        switchFillShape = findViewById(R.id.switchFillShape);
    }
    
    private void setupListeners() {
        // Shape selection
        setupShapeButton(shapeButtons[0], ShapeObject.ShapeType.RECTANGLE);
        setupShapeButton(shapeButtons[1], ShapeObject.ShapeType.CIRCLE);
        setupShapeButton(shapeButtons[2], ShapeObject.ShapeType.ARROW);
        setupShapeButton(shapeButtons[3], ShapeObject.ShapeType.TRIANGLE);
        setupShapeButton(shapeButtons[4], ShapeObject.ShapeType.STAR);
        setupShapeButton(shapeButtons[5], ShapeObject.ShapeType.LINE);
        
        // Color pickers
        setupColorPicker(colorViews[0], 0xFFF44336);
        setupColorPicker(colorViews[1], 0xFFFF5722);
        setupColorPicker(colorViews[2], 0xFFFFEB3B);
        setupColorPicker(colorViews[3], 0xFF4CAF50);
        setupColorPicker(colorViews[4], 0xFF2196F3);
        setupColorPicker(colorViews[5], 0xFF9C27B0);
        
        // Fill toggle
        switchFillShape.setOnCheckedChangeListener((buttonView, isChecked) -> {
            filled = isChecked;
        });
        
        // Dismiss button (also confirms selection)
        findViewById(R.id.btnDismiss).setOnClickListener(v -> {
            if (listener != null) {
                listener.onShapeSelected(selectedShape, shapeColor, filled);
            }
            dismiss();
        });
        
        // Highlight default shape
        updateShapeSelection(shapeButtons[0]);
        
        // Highlight default color (orange)
        colorViews[1].setAlpha(1.0f);
        for (int i = 0; i < colorViews.length; i++) {
            if (i != 1) colorViews[i].setAlpha(0.5f);
        }
    }
    
    private void setupShapeButton(LinearLayout button, ShapeObject.ShapeType shapeType) {
        button.setOnClickListener(v -> {
            selectedShape = shapeType;
            updateShapeSelection(button);
        });
    }
    
    private void updateShapeSelection(LinearLayout selectedButton) {
        for (LinearLayout button : shapeButtons) {
            if (button == selectedButton) {
                button.setBackgroundResource(R.drawable.annotation_layer_selected);
            } else {
                button.setBackgroundResource(R.drawable.settings_home_row_bg);
            }
        }
    }
    
    private void setupColorPicker(View colorView, int color) {
        colorView.setOnClickListener(v -> {
            shapeColor = color;
            // Highlight selected color
            for (View view : colorViews) {
                view.setAlpha(view == colorView ? 1.0f : 0.5f);
            }
        });
    }
}
