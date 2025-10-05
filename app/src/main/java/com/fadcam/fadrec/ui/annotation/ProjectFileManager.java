package com.fadcam.fadrec.ui.annotation;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Manages .fadrec project files for annotation state persistence.
 * Format: JSON-based project file (like .psd for Photoshop).
 * 
 * File Structure:
 * {
 *   "version": "1.0",
 *   "metadata": {
 *     "name": "Recording_2025-10-05",
 *     "created": 1728123456789,
 *     "modified": 1728123456789
 *   },
 *   "canvasSettings": {
 *     "width": 1080,
 *     "height": 1920,
 *     "backgroundColor": "#000000"
 *   },
 *   "pages": [...]
 * }
 */
public class ProjectFileManager {
    private static final String TAG = "ProjectFileManager";
    private static final String FILE_EXTENSION = ".fadrec";
    private static final String PROJECT_VERSION = "1.0";
    
    private final Context context;
    private final File projectsDir;
    
    public ProjectFileManager(Context context) {
        this.context = context;
        
        // Create projects directory: ~/Documents/FadRec/Projects/
        File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
        File fadrecDir = new File(documentsDir, "FadRec");
        this.projectsDir = new File(fadrecDir, "Projects");
        
        if (!projectsDir.exists()) {
            projectsDir.mkdirs();
            Log.i(TAG, "Created FadRec projects directory: " + projectsDir.getAbsolutePath());
        }
    }
    
    /**
     * Save annotation state to .fadrec file
     */
    public boolean saveProject(AnnotationState state, String projectName) {
        try {
            JSONObject project = new JSONObject();
            
            // Version
            project.put("version", PROJECT_VERSION);
            
            // Metadata
            JSONObject metadata = new JSONObject();
            metadata.put("name", projectName);
            metadata.put("created", state.getCreatedAt());
            metadata.put("modified", System.currentTimeMillis());
            project.put("metadata", metadata);
            
            // Canvas settings (placeholder for future)
            JSONObject canvasSettings = new JSONObject();
            canvasSettings.put("width", 1080);
            canvasSettings.put("height", 1920);
            canvasSettings.put("backgroundColor", "#000000");
            project.put("canvasSettings", canvasSettings);
            
            // Pages
            JSONArray pagesArray = new JSONArray();
            for (AnnotationPage page : state.getPages()) {
                pagesArray.put(page.toJSON());
            }
            project.put("pages", pagesArray);
            project.put("currentPageIndex", state.getActivePageIndex());
            
            // Write to file
            File projectFile = new File(projectsDir, projectName + FILE_EXTENSION);
            FileWriter writer = new FileWriter(projectFile);
            writer.write(project.toString(2)); // Pretty print with indent
            writer.close();
            
            Log.i(TAG, "Project saved: " + projectFile.getAbsolutePath());
            return true;
            
        } catch (JSONException | IOException e) {
            Log.e(TAG, "Failed to save project", e);
            return false;
        }
    }
    
    /**
     * Load annotation state from .fadrec file
     */
    public AnnotationState loadProject(String projectName) {
        try {
            File projectFile = new File(projectsDir, projectName + FILE_EXTENSION);
            if (!projectFile.exists()) {
                Log.e(TAG, "Project file not found: " + projectFile.getAbsolutePath());
                return null;
            }
            
            // Read file
            FileReader reader = new FileReader(projectFile);
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[1024];
            int length;
            while ((length = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, length);
            }
            reader.close();
            
            // Parse JSON
            JSONObject project = new JSONObject(sb.toString());
            
            // Verify version
            String version = project.getString("version");
            if (!version.equals(PROJECT_VERSION)) {
                Log.w(TAG, "Project version mismatch: " + version);
            }
            
            // Create state
            AnnotationState state = new AnnotationState();
            
            // Load pages
            JSONArray pagesArray = project.getJSONArray("pages");
            for (int i = 0; i < pagesArray.length(); i++) {
                JSONObject pageJson = pagesArray.getJSONObject(i);
                AnnotationPage page = AnnotationPage.fromJSON(pageJson);
                
                // Remove default page if loading first page
                if (i == 0 && state.getPages().size() == 1) {
                    state.getPages().clear();
                }
                
                state.getPages().add(page);
            }
            
            // Set current page
            if (project.has("currentPageIndex")) {
                int currentIndex = project.getInt("currentPageIndex");
                if (currentIndex >= 0 && currentIndex < state.getPages().size()) {
                    state.setActivePageIndex(currentIndex);
                }
            }
            
            Log.i(TAG, "Project loaded: " + projectFile.getAbsolutePath());
            return state;
            
        } catch (JSONException | IOException e) {
            Log.e(TAG, "Failed to load project", e);
            return null;
        }
    }
    
    /**
     * Auto-save with timestamp-based name
     */
    public boolean autoSave(AnnotationState state) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
                .format(new Date());
        String projectName = "Recording_" + timestamp;
        return saveProject(state, projectName);
    }
    
    /**
     * Get all project files
     */
    public File[] listProjects() {
        return projectsDir.listFiles((dir, name) -> name.endsWith(FILE_EXTENSION));
    }
    
    /**
     * Delete project file
     */
    public boolean deleteProject(String projectName) {
        File projectFile = new File(projectsDir, projectName + FILE_EXTENSION);
        if (projectFile.exists()) {
            boolean deleted = projectFile.delete();
            Log.i(TAG, "Project deleted: " + projectFile.getAbsolutePath());
            return deleted;
        }
        return false;
    }
    
    /**
     * Get projects directory path
     */
    public String getProjectsPath() {
        return projectsDir.getAbsolutePath();
    }
    
    /**
     * Check if project exists
     */
    public boolean projectExists(String projectName) {
        File projectFile = new File(projectsDir, projectName + FILE_EXTENSION);
        return projectFile.exists();
    }
}
