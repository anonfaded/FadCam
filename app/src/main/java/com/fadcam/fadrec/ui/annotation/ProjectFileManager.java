package com.fadcam.fadrec.ui.annotation;

import android.content.Context;
import android.content.SharedPreferences;
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
    private static final String PREFS_NAME = "FadRecProjects";
    private static final String KEY_CURRENT_PROJECT = "current_project";
    
    private final Context context;
    private final File projectsDir;
    private final SharedPreferences prefs;
    
    public ProjectFileManager(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        
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
        Log.i(TAG, "========== SAVING PROJECT: " + projectName + " ==========");
        try {
            JSONObject project = new JSONObject();
            
            // Version
            project.put("version", PROJECT_VERSION);
            
            // Metadata
            JSONObject metadata = state.getMetadata();
            if (metadata == null) {
                metadata = new JSONObject();
            }
            
            // Ensure core metadata fields are present
            if (!metadata.has("name")) {
                metadata.put("name", projectName);
            }
            if (!metadata.has("created")) {
                metadata.put("created", state.getCreatedAt());
            }
            metadata.put("modified", System.currentTimeMillis());
            
            // Description field preserved if exists
            project.put("metadata", metadata);
            
            // Canvas settings (placeholder for future)
            JSONObject canvasSettings = new JSONObject();
            canvasSettings.put("width", 1080);
            canvasSettings.put("height", 1920);
            canvasSettings.put("backgroundColor", "#000000");
            project.put("canvasSettings", canvasSettings);
            
            // Pages
            JSONArray pagesArray = new JSONArray();
            int totalObjects = 0;
            for (int i = 0; i < state.getPages().size(); i++) {
                AnnotationPage page = state.getPages().get(i);
                pagesArray.put(page.toJSON());
                
                // Count objects
                for (AnnotationLayer layer : page.getLayers()) {
                    totalObjects += layer.getObjects().size();
                }
                Log.d(TAG, "  Page " + (i+1) + ": " + page.getLayers().size() + " layers");
            }
            project.put("pages", pagesArray);
            project.put("currentPageIndex", state.getActivePageIndex());
            
            Log.d(TAG, "  Total pages: " + state.getPages().size());
            Log.d(TAG, "  Total objects: " + totalObjects);
            Log.d(TAG, "  Active page: " + (state.getActivePageIndex() + 1));
            
            // Write to file
            File projectFile = new File(projectsDir, projectName + FILE_EXTENSION);
            FileWriter writer = new FileWriter(projectFile);
            writer.write(project.toString(2)); // Pretty print with indent
            writer.close();
            
            Log.i(TAG, "✅ Project saved successfully: " + projectFile.getAbsolutePath());
            Log.d(TAG, "  File size: " + (projectFile.length() / 1024) + " KB");
            return true;
            
        } catch (JSONException | IOException e) {
            Log.e(TAG, "❌ Failed to save project: " + projectName, e);
            return false;
        }
    }
    
    /**
     * Load annotation state from .fadrec file
     */
    public AnnotationState loadProject(String projectName) {
        Log.i(TAG, "========== LOADING PROJECT FROM FILE: " + projectName + " ==========");
        try {
            File projectFile = new File(projectsDir, projectName + FILE_EXTENSION);
            if (!projectFile.exists()) {
                Log.e(TAG, "❌ Project file not found: " + projectFile.getAbsolutePath());
                return null;
            }
            
            Log.d(TAG, "  File found: " + projectFile.getAbsolutePath());
            Log.d(TAG, "  File size: " + (projectFile.length() / 1024) + " KB");
            
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
            Log.d(TAG, "  Project version: " + version);
            if (!version.equals(PROJECT_VERSION)) {
                Log.w(TAG, "⚠️ Project version mismatch: expected " + PROJECT_VERSION + ", got " + version);
            }
            
            // Create state
            AnnotationState state = new AnnotationState();
            
            // Load metadata if exists
            if (project.has("metadata")) {
                JSONObject metadata = project.getJSONObject("metadata");
                state.setMetadata(metadata);
                Log.d(TAG, "  Metadata loaded: " + metadata.toString());
            }
            
            // Load pages
            JSONArray pagesArray = project.getJSONArray("pages");
            Log.d(TAG, "  Loading " + pagesArray.length() + " pages...");
            
            int totalObjects = 0;
            for (int i = 0; i < pagesArray.length(); i++) {
                JSONObject pageJson = pagesArray.getJSONObject(i);
                AnnotationPage page = AnnotationPage.fromJSON(pageJson);
                
                // Count objects in this page
                int pageObjects = 0;
                for (AnnotationLayer layer : page.getLayers()) {
                    pageObjects += layer.getObjects().size();
                }
                totalObjects += pageObjects;
                
                Log.d(TAG, "    Page " + (i+1) + ": " + page.getLayers().size() + " layers, " + pageObjects + " objects");
                
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
                    Log.d(TAG, "  Active page set to: " + (currentIndex + 1));
                }
            }
            
            Log.i(TAG, "✅ Project loaded successfully");
            Log.d(TAG, "  Total pages loaded: " + state.getPages().size());
            Log.d(TAG, "  Total objects: " + totalObjects);
            return state;
            
        } catch (JSONException | IOException e) {
            Log.e(TAG, "❌ Failed to load project: " + projectName, e);
            e.printStackTrace();
            return null;
        }
    }
    
    /**
     * Get or create current project name (persistent across app restarts)
     */
    public String getOrCreateCurrentProject() {
        // Check if we have a saved current project
        String savedProject = prefs.getString(KEY_CURRENT_PROJECT, null);
        
        if (savedProject != null && projectExists(savedProject)) {
            Log.i(TAG, "Resuming project: " + savedProject);
            return savedProject;
        }
        
        // Create new project with timestamp
        String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
                .format(new Date());
        String projectName = "Recording_" + timestamp;
        
        // Save as current project
        prefs.edit().putString(KEY_CURRENT_PROJECT, projectName).apply();
        Log.i(TAG, "Created new project: " + projectName);
        
        return projectName;
    }
    
    /**
     * Create a brand new project (user action)
     */
    public String createNewProject() {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
                .format(new Date());
        String projectName = "Recording_" + timestamp;
        
        // Save as current project
        prefs.edit().putString(KEY_CURRENT_PROJECT, projectName).apply();
        Log.i(TAG, "User created new project: " + projectName);
        
        return projectName;
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
