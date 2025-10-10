package com.fadcam.fadrec.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;

import androidx.annotation.Nullable;

import com.fadcam.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Transparent dialog activity for color picker.
 * Used from Service context to show proper Material dialog.
 */
public class ColorPickerDialogActivity extends Activity {
    
    public static final String EXTRA_SELECTED_COLOR = "selected_color";
    public static final String ACTION_COLOR_SELECTED = "com.fadcam.fadrec.COLOR_SELECTED";
    
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Show color picker dialog immediately
        showColorPickerDialog();
    }
    
    private void showColorPickerDialog() {
        // Custom colors (matching toolbar selection)
        int[] colors = {
            // Row 1
            0xFFE91E63, 0xFF9C27B0, 0xFF673AB7, 0xFF3F51B5,
            // Row 2
            0xFF1976D2, 0xFF0288D1, 0xFF0097A7, 0xFF00796B,
            // Row 3
            0xFF388E3C, 0xFF689F38, 0xFF9E9D24, 0xFFF9A825,
            // Row 4
            0xFFF57C00, 0xFFE64A19, 0xFFD84315, 0xFF5D4037,
            // Row 5
            0xFF616161, 0xFF455A64, 0xFFAD1457, 0xFF6A1B9A,
            // Row 6
            0xFF4527A0, 0xFF283593, 0xFF1565C0, 0xFF01579B
        };
        
        // Create ScrollView wrapper for scrolling with max height
        android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
        ViewGroup.LayoutParams scrollParams = new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dpToPx(480) // Max height for scrollability
        );
        scrollView.setLayoutParams(scrollParams);
        
        // Create grid layout for color swatches
        GridLayout colorGrid = new GridLayout(this);
        colorGrid.setColumnCount(4);
        int padding = dpToPx(16);
        colorGrid.setPadding(padding, padding, padding, padding);
        
        // Add color swatches - 3x smaller
        int swatchSize = dpToPx(48);
        int swatchMargin = dpToPx(6);
        
        for (int color : colors) {
            View colorSwatch = new View(this);
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = swatchSize;
            params.height = swatchSize;
            params.setMargins(swatchMargin, swatchMargin, swatchMargin, swatchMargin);
            colorSwatch.setLayoutParams(params);
            
            // Use circular drawable with color tint
            colorSwatch.setBackgroundResource(R.drawable.color_picker_swatch_circle);
            colorSwatch.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));
            colorSwatch.setElevation(dpToPx(4));
            
            colorGrid.addView(colorSwatch);
        }
        
        scrollView.addView(colorGrid);
        
        // Create Material dialog
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle("Pick a Color");
        builder.setView(scrollView);
        builder.setNegativeButton("Cancel", (dialog, which) -> finish());
        builder.setOnCancelListener(dialog -> finish());
        
        androidx.appcompat.app.AlertDialog dialog = builder.create();
        
        // IMPORTANT: Set window type to appear above other overlays
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                window.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
            } else {
                window.setType(android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
            }
        }
        
        dialog.show();
        
        // Set click listeners AFTER dialog is shown so we can dismiss it
        for (int i = 0; i < colorGrid.getChildCount(); i++) {
            View colorSwatch = colorGrid.getChildAt(i);
            int finalColor = colors[i];
            colorSwatch.setOnClickListener(v -> {
                // Send result back to service
                Intent resultIntent = new Intent(ACTION_COLOR_SELECTED);
                resultIntent.putExtra(EXTRA_SELECTED_COLOR, finalColor);
                sendBroadcast(resultIntent);
                
                // Dismiss dialog and close activity
                dialog.dismiss();
                finish();
            });
        }
    }
    
    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
