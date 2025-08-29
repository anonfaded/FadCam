package com.fadcam.ui.faditor.recovery;

import android.content.Context;
import android.content.SharedPreferences;

import com.fadcam.Log;
import com.fadcam.ui.faditor.exceptions.ErrorHandler;
import com.fadcam.ui.faditor.exceptions.FaditorException;
import com.fadcam.ui.faditor.models.EditorState;
import com.fadcam.ui.faditor.models.VideoProject;
import com.fadcam.ui.faditor.persistence.ProjectSerializer;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles crash recovery, auto-save failures, and project restoration.
 * Provides mechanisms to recover from various failure scenarios.
 */
public class RecoveryManager {
    
    private static final String TAG = "RecoveryManager";
    private static final String PREFS_NAME = "faditor_recovery";
    private static final String KEY_LAST_PROJECT_ID = "last_project_id";
    private static final String KEY_LAST_SAVE_TIME = "last_save_time";
    private static final String KEY_CRASH_DETECTED = "crash_detected";
    private static final String KEY_RECOVERY_ATTEMPTS = "recovery_attempts";
    
    private static final String RECOVERY_DIR = "faditor_recovery";
    private static final String BACKUP_SUFFIX = "_backup.json";
    private static final String TEMP_SUFFIX = "_temp.json";
    
    private final Context context;
    private final SharedPreferences prefs;
    private final File recoveryDir;
    
    public interface RecoveryCallback {
        void onRecoverySuccess(VideoProject project, EditorState editorState);
        void onRecoveryFailed(FaditorException exception);
        void onNoRecoveryNeeded();
    }
    
    public RecoveryManager(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.recoveryDir = new File(context.getFilesDir(), RECOVERY_DIR);
        
        if (!recoveryDir.exists()) {
            recoveryDir.mkdirs();
        }
    }
    
    /**
     * Check if crash recovery is needed
     */
    public boolean isCrashRecoveryNeeded() {
        return prefs.getBoolean(KEY_CRASH_DETECTED, false);
    }
    
    /**
     * Mark that the app started successfully
     */
    public void markAppStarted() {
        prefs.edit().putBoolean(KEY_CRASH_DETECTED, true).apply();
    }
    
    /**
     * Mark that the app closed gracefully
     */
    public void markAppClosedGracefully() {
        prefs.edit().putBoolean(KEY_CRASH_DETECTED, false).apply();
    }
    
    /**
     * Attempt to recover from crash
     */
    public void attemptCrashRecovery(RecoveryCallback callback) {
        String lastProjectId = prefs.getString(KEY_LAST_PROJECT_ID, null);
        
        if (lastProjectId == null) {
            callback.onNoRecoveryNeeded();
            return;
        }
        
        try {
            // Try to recover project and editor state
            VideoProject project = recoverProject(lastProjectId);
            EditorState editorState = recoverEditorState(lastProjectId);
            
            if (project != null) {
                Log.i(TAG, "Successfully recovered project: " + lastProjectId);
                callback.onRecoverySuccess(project, editorState);
                
                // Clear crash flag after successful recovery
                markAppClosedGracefully();
            } else {
                callback.onNoRecoveryNeeded();
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Crash recovery failed", e);
            FaditorException exception = ErrorHandler.createAutoSaveError(context, e);
            callback.onRecoveryFailed(exception);
        }
    }
    
    /**
     * Create backup before risky operations
     */
    public void createBackup(String projectId, VideoProject project, EditorState editorState) throws FaditorException {
        try {
            // Backup project
            if (project != null) {
                String projectJson = ProjectSerializer.serializeProject(project);
                File backupFile = new File(recoveryDir, projectId + "_project" + BACKUP_SUFFIX);
                writeToFile(backupFile, projectJson);
            }
            
            // Backup editor state
            if (editorState != null) {
                String stateJson = editorState.toJson().toString();
                File stateBackupFile = new File(recoveryDir, projectId + "_state" + BACKUP_SUFFIX);
                writeToFile(stateBackupFile, stateJson);
            }
            
            Log.d(TAG, "Created backup for project: " + projectId);
            
        } catch (Exception e) {
            throw new FaditorException(
                FaditorException.ErrorCode.BACKUP_CREATION_FAILED,
                "Failed to create backup before operation",
                e.getMessage(),
                FaditorException.RecoveryAction.RETRY,
                e
            );
        }
    }  
  
    /**
     * Save recovery checkpoint during editing
     */
    public void saveRecoveryCheckpoint(String projectId, VideoProject project, EditorState editorState) {
        try {
            // Update last project info
            prefs.edit()
                .putString(KEY_LAST_PROJECT_ID, projectId)
                .putLong(KEY_LAST_SAVE_TIME, System.currentTimeMillis())
                .apply();
            
            // Save temporary recovery files
            if (project != null) {
                String projectJson = ProjectSerializer.serializeProject(project);
                File tempFile = new File(recoveryDir, projectId + "_project" + TEMP_SUFFIX);
                writeToFile(tempFile, projectJson);
            }
            
            if (editorState != null) {
                String stateJson = editorState.toJson().toString();
                File tempStateFile = new File(recoveryDir, projectId + "_state" + TEMP_SUFFIX);
                writeToFile(tempStateFile, stateJson);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to save recovery checkpoint", e);
            // Don't throw exception here as this is a background operation
        }
    }
    
    /**
     * Recover project from backup or temp files
     */
    private VideoProject recoverProject(String projectId) {
        // Try temp file first (most recent)
        File tempFile = new File(recoveryDir, projectId + "_project" + TEMP_SUFFIX);
        VideoProject project = loadProjectFromFile(tempFile);
        
        if (project == null) {
            // Try backup file
            File backupFile = new File(recoveryDir, projectId + "_project" + BACKUP_SUFFIX);
            project = loadProjectFromFile(backupFile);
        }
        
        return project;
    }
    
    /**
     * Recover editor state from backup or temp files
     */
    private EditorState recoverEditorState(String projectId) {
        // Try temp file first (most recent)
        File tempFile = new File(recoveryDir, projectId + "_state" + TEMP_SUFFIX);
        EditorState state = loadEditorStateFromFile(tempFile);
        
        if (state == null) {
            // Try backup file
            File backupFile = new File(recoveryDir, projectId + "_state" + BACKUP_SUFFIX);
            state = loadEditorStateFromFile(backupFile);
        }
        
        return state;
    }
    
    /**
     * Load project from file
     */
    private VideoProject loadProjectFromFile(File file) {
        if (!file.exists()) {
            return null;
        }
        
        try {
            String json = readFromFile(file);
            return ProjectSerializer.deserializeProject(json);
        } catch (Exception e) {
            Log.e(TAG, "Failed to load project from file: " + file.getName(), e);
            return null;
        }
    }
    
    /**
     * Load editor state from file
     */
    private EditorState loadEditorStateFromFile(File file) {
        if (!file.exists()) {
            return null;
        }
        
        try {
            String json = readFromFile(file);
            JSONObject jsonObject = new JSONObject(json);
            return EditorState.fromJson(jsonObject);
        } catch (Exception e) {
            Log.e(TAG, "Failed to load editor state from file: " + file.getName(), e);
            return null;
        }
    }
    
    /**
     * Handle auto-save failure with recovery options
     */
    public void handleAutoSaveFailure(String projectId, VideoProject project, EditorState editorState, Exception cause) {
        Log.e(TAG, "Auto-save failed for project: " + projectId, cause);
        
        // Increment failure count
        int attempts = prefs.getInt(KEY_RECOVERY_ATTEMPTS, 0) + 1;
        prefs.edit().putInt(KEY_RECOVERY_ATTEMPTS, attempts).apply();
        
        // Try alternative save methods
        try {
            if (attempts < 3) {
                // Try saving to recovery directory
                saveRecoveryCheckpoint(projectId, project, editorState);
                Log.i(TAG, "Saved to recovery directory as fallback");
            } else {
                // Too many failures, create emergency backup
                createEmergencyBackup(projectId, project, editorState);
                Log.w(TAG, "Created emergency backup due to repeated failures");
            }
        } catch (Exception e) {
            Log.e(TAG, "All recovery methods failed", e);
        }
    }
    
    /**
     * Create emergency backup when all else fails
     */
    private void createEmergencyBackup(String projectId, VideoProject project, EditorState editorState) throws FaditorException {
        try {
            String timestamp = String.valueOf(System.currentTimeMillis());
            
            if (project != null) {
                String projectJson = ProjectSerializer.serializeProject(project);
                File emergencyFile = new File(recoveryDir, projectId + "_emergency_" + timestamp + ".json");
                writeToFile(emergencyFile, projectJson);
            }
            
            if (editorState != null) {
                String stateJson = editorState.toJson().toString();
                File emergencyStateFile = new File(recoveryDir, projectId + "_state_emergency_" + timestamp + ".json");
                writeToFile(emergencyStateFile, stateJson);
            }
            
        } catch (Exception e) {
            throw new FaditorException(
                FaditorException.ErrorCode.BACKUP_CREATION_FAILED,
                "Failed to create emergency backup",
                e.getMessage(),
                FaditorException.RecoveryAction.CONTACT_SUPPORT,
                e
            );
        }
    } 
   
    /**
     * Clean up old recovery files
     */
    public void cleanupOldRecoveryFiles() {
        try {
            File[] files = recoveryDir.listFiles();
            if (files == null) return;
            
            long cutoffTime = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000); // 7 days
            
            for (File file : files) {
                if (file.lastModified() < cutoffTime) {
                    boolean deleted = file.delete();
                    Log.d(TAG, "Cleaned up old recovery file: " + file.getName() + " (deleted: " + deleted + ")");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error cleaning up recovery files", e);
        }
    }
    
    /**
     * Get list of recoverable projects
     */
    public List<String> getRecoverableProjects() {
        List<String> projectIds = new ArrayList<>();
        
        try {
            File[] files = recoveryDir.listFiles();
            if (files == null) return projectIds;
            
            for (File file : files) {
                String name = file.getName();
                if (name.endsWith(TEMP_SUFFIX) || name.endsWith(BACKUP_SUFFIX)) {
                    // Extract project ID from filename
                    String projectId = name.substring(0, name.indexOf("_"));
                    if (!projectIds.contains(projectId)) {
                        projectIds.add(projectId);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting recoverable projects", e);
        }
        
        return projectIds;
    }
    
    /**
     * Clear recovery data for a project
     */
    public void clearRecoveryData(String projectId) {
        try {
            // Clear from preferences if this was the last project
            String lastProjectId = prefs.getString(KEY_LAST_PROJECT_ID, null);
            if (projectId.equals(lastProjectId)) {
                prefs.edit()
                    .remove(KEY_LAST_PROJECT_ID)
                    .remove(KEY_LAST_SAVE_TIME)
                    .remove(KEY_RECOVERY_ATTEMPTS)
                    .apply();
            }
            
            // Delete recovery files
            File[] files = recoveryDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.getName().startsWith(projectId + "_")) {
                        boolean deleted = file.delete();
                        Log.d(TAG, "Deleted recovery file: " + file.getName() + " (deleted: " + deleted + ")");
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error clearing recovery data for project: " + projectId, e);
        }
    }
    
    /**
     * Write string to file
     */
    private void writeToFile(File file, String content) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
            fos.flush();
        }
    }
    
    /**
     * Read string from file
     */
    private String readFromFile(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[(int) file.length()];
            fis.read(buffer);
            return new String(buffer, StandardCharsets.UTF_8);
        }
    }
    
    /**
     * Check if recovery is possible for a project
     */
    public boolean canRecoverProject(String projectId) {
        File tempFile = new File(recoveryDir, projectId + "_project" + TEMP_SUFFIX);
        File backupFile = new File(recoveryDir, projectId + "_project" + BACKUP_SUFFIX);
        
        return tempFile.exists() || backupFile.exists();
    }
    
    /**
     * Get recovery statistics
     */
    public RecoveryStats getRecoveryStats() {
        RecoveryStats stats = new RecoveryStats();
        stats.lastProjectId = prefs.getString(KEY_LAST_PROJECT_ID, null);
        stats.lastSaveTime = prefs.getLong(KEY_LAST_SAVE_TIME, 0);
        stats.recoveryAttempts = prefs.getInt(KEY_RECOVERY_ATTEMPTS, 0);
        stats.crashDetected = prefs.getBoolean(KEY_CRASH_DETECTED, false);
        stats.recoverableProjects = getRecoverableProjects();
        
        return stats;
    }
    
    /**
     * Recovery statistics class
     */
    public static class RecoveryStats {
        public String lastProjectId;
        public long lastSaveTime;
        public int recoveryAttempts;
        public boolean crashDetected;
        public List<String> recoverableProjects;
        
        @Override
        public String toString() {
            return "RecoveryStats{" +
                    "lastProjectId='" + lastProjectId + '\'' +
                    ", lastSaveTime=" + lastSaveTime +
                    ", recoveryAttempts=" + recoveryAttempts +
                    ", crashDetected=" + crashDetected +
                    ", recoverableProjects=" + recoverableProjects.size() +
                    '}';
        }
    }
}