package com.fadcam.fadrec.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import com.fadcam.R;
import com.fadcam.fadrec.ui.annotation.ProjectFileManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Transparent dialog activity for project naming.
 * Used from Service context to show proper Material dialog with EditText inputs.
 * Allows users to name their project and add an optional description.
 */
public class ProjectNamingDialogActivity extends Activity {
    
    public static final String ACTION_PROJECT_RENAMED = "com.fadcam.fadrec.PROJECT_RENAMED";
    public static final String EXTRA_PROJECT_NAME = "project_name";
    public static final String EXTRA_PROJECT_DESCRIPTION = "project_description";
    
    private String currentProjectName;
    private String currentDescription;
    
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Get current project name and description from intent
        Intent intent = getIntent();
        currentProjectName = intent.getStringExtra("currentProjectName");
        currentDescription = intent.getStringExtra("currentDescription");
        
        if (currentProjectName == null || currentProjectName.isEmpty()) {
            currentProjectName = "Untitled Project";
        }
        
        if (currentDescription == null) {
            currentDescription = "";
        }
        
        // Show naming dialog immediately
        showProjectNamingDialog();
    }
    
    /**
     * Shows Material dialog with project name and description input fields.
     */
    private void showProjectNamingDialog() {
        // Inflate custom dialog layout
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_project_name, null);
        
        EditText etProjectName = dialogView.findViewById(R.id.etProjectName);
        EditText etProjectDescription = dialogView.findViewById(R.id.etProjectDescription);
        TextView tvSanitizedPreview = dialogView.findViewById(R.id.tvSanitizedPreview);
        
        // Load Ubuntu font for EditTexts
        Typeface ubuntuFont = ResourcesCompat.getFont(this, R.font.ubuntu_regular);
        if (ubuntuFont != null) {
            etProjectName.setTypeface(ubuntuFont);
            etProjectDescription.setTypeface(ubuntuFont);
            if (tvSanitizedPreview != null) {
                tvSanitizedPreview.setTypeface(ubuntuFont);
            }
        }
        
        // Pre-fill with current values
        etProjectName.setText(currentProjectName);
        etProjectDescription.setText(currentDescription);
        
        // Select all text for easy replacement
        etProjectName.selectAll();
        
        // Show real-time sanitized preview
        if (tvSanitizedPreview != null) {
            tvSanitizedPreview.setText("Will be saved as: " + 
                ProjectFileManager.sanitizeProjectName(currentProjectName));
            
            etProjectName.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                
                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    String input = s.toString().trim();
                    String sanitized = ProjectFileManager.sanitizeProjectName(
                        input.isEmpty() ? "Untitled" : input);
                    tvSanitizedPreview.setText("Will be saved as: " + sanitized);
                }
                
                @Override
                public void afterTextChanged(Editable s) {}
            });
        }
        
        // Create Material dialog
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle(R.string.name_your_project);
        builder.setMessage(R.string.project_naming_message);
        builder.setView(dialogView);
        
        builder.setPositiveButton("Save", (dialog, which) -> {
            String newName = etProjectName.getText().toString().trim();
            String newDescription = etProjectDescription.getText().toString().trim();
            
            // Validate name (must not be empty)
            if (newName.isEmpty()) {
                newName = "Untitled Project";
            }
            
            // Send broadcast with new name and description (will be sanitized by receiver)
            Intent resultIntent = new Intent(ACTION_PROJECT_RENAMED);
            resultIntent.putExtra(EXTRA_PROJECT_NAME, newName);
            resultIntent.putExtra(EXTRA_PROJECT_DESCRIPTION, newDescription);
            sendBroadcast(resultIntent);
            
            finish();
        });
        
        builder.setNegativeButton("Cancel", (dialog, which) -> finish());
        builder.setOnCancelListener(dialog -> finish());
        
        androidx.appcompat.app.AlertDialog alertDialog = builder.create();
        
        // IMPORTANT: Set window type to appear above other overlays
        Window window = alertDialog.getWindow();
        if (window != null) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
            } else {
                window.setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
            }
            
            // Adjust window to keyboard - don't resize, just pan
            window.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN |
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
            );
        }
        
        alertDialog.show();
    }
}
