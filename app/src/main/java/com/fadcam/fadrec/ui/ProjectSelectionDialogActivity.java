package com.fadcam.fadrec.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import com.fadcam.R;
import com.fadcam.fadrec.ui.annotation.ProjectFileManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONObject;

import java.io.File;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * Dialog activity for browsing and managing all FadRec projects.
 * Shows list of projects with names, descriptions, and metadata.
 */
public class ProjectSelectionDialogActivity extends Activity {
    
    public static final String ACTION_PROJECT_SELECTED = "com.fadcam.fadrec.PROJECT_SELECTED";
    public static final String EXTRA_PROJECT_NAME = "project_name";
    
    private ProjectFileManager projectFileManager;
    private Typeface ubuntuFont;
    
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        projectFileManager = new ProjectFileManager(this);
        ubuntuFont = ResourcesCompat.getFont(this, R.font.ubuntu_regular);
        
        showProjectsDialog();
    }
    
    /**
     * Shows Material dialog with list of all projects.
     */
    private void showProjectsDialog() {
        File[] projectFiles = projectFileManager.listProjects();
        
        if (projectFiles == null || projectFiles.length == 0) {
            Toast.makeText(this, "No projects found", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        
        // Sort by last modified (newest first)
        Arrays.sort(projectFiles, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));
        
        // Create scrollable container
        android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8));
        
        // Add project items
        for (File projectFile : projectFiles) {
            String fileName = projectFile.getName().replace(".fadrec", "");
            View projectItem = createProjectItem(fileName, projectFile);
            container.addView(projectItem);
        }
        
        scrollView.addView(container);
        
        // Create dialog
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle("My Projects");
        builder.setView(scrollView);
        builder.setNegativeButton("Close", (dialog, which) -> finish());
        builder.setOnCancelListener(dialog -> finish());
        
        androidx.appcompat.app.AlertDialog alertDialog = builder.create();
        
        // Set window type for overlay
        Window window = alertDialog.getWindow();
        if (window != null) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
            } else {
                window.setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
            }
        }
        
        alertDialog.show();
    }
    
    /**
     * Creates a project list item view with name, description, and metadata.
     */
    private View createProjectItem(String fileName, File projectFile) {
        View itemView = LayoutInflater.from(this).inflate(R.layout.item_project, null);
        
        TextView txtProjectName = itemView.findViewById(R.id.txtProjectItemName);
        TextView txtProjectDesc = itemView.findViewById(R.id.txtProjectItemDesc);
        TextView txtProjectMeta = itemView.findViewById(R.id.txtProjectItemMeta);
        
        // Apply font
        if (ubuntuFont != null) {
            txtProjectName.setTypeface(ubuntuFont, Typeface.BOLD);
            txtProjectDesc.setTypeface(ubuntuFont);
            txtProjectMeta.setTypeface(ubuntuFont);
        }
        
        // Try to load project metadata
        String displayName = fileName;
        String description = "";
        
        try {
            FileReader reader = new FileReader(projectFile);
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[1024];
            int length;
            while ((length = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, length);
            }
            reader.close();
            
            JSONObject project = new JSONObject(sb.toString());
            if (project.has("metadata")) {
                JSONObject metadata = project.getJSONObject("metadata");
                displayName = metadata.optString("name", fileName);
                description = metadata.optString("description", "");
            }
        } catch (Exception e) {
            // Use filename as fallback
        }
        
        // Set data
        txtProjectName.setText(displayName);
        
        if (description.isEmpty()) {
            txtProjectDesc.setVisibility(View.GONE);
        } else {
            txtProjectDesc.setText(description);
            txtProjectDesc.setVisibility(View.VISIBLE);
        }
        
        // Metadata (last modified)
        String dateStr = new SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.US)
                .format(new Date(projectFile.lastModified()));
        String sizeStr = String.format(Locale.US, "%.1f KB", projectFile.length() / 1024.0);
        txtProjectMeta.setText("Modified: " + dateStr + " • " + sizeStr);
        
        // Click to select
        itemView.setOnClickListener(v -> {
            Intent resultIntent = new Intent(ACTION_PROJECT_SELECTED);
            resultIntent.putExtra(EXTRA_PROJECT_NAME, fileName);
            sendBroadcast(resultIntent);
            finish();
        });
        
        return itemView;
    }
    
    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
