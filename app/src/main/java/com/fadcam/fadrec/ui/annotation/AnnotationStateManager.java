package com.fadcam.fadrec.ui.annotation;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

/**
 * Manager for persisting and loading annotation state.
 * Handles auto-save, crash recovery, and state serialization.
 */
public class AnnotationStateManager {
    private static final String TAG = "AnnotationStateManager";
    private static final String STATE_FILE = "annotation_state.dat";
    
    private Context context;
    private AnnotationState currentState;
    private File stateFile;
    
    public AnnotationStateManager(Context context) {
        this.context = context.getApplicationContext();
        this.stateFile = new File(context.getFilesDir(), STATE_FILE);
    }
    
    /**
     * Get current annotation state, creating new if doesn't exist
     */
    public AnnotationState getCurrentState() {
        if (currentState == null) {
            currentState = loadState();
            if (currentState == null) {
                currentState = new AnnotationState();
            }
        }
        return currentState;
    }
    
    /**
     * Set the current annotation state (used before saving)
     */
    public void setCurrentState(AnnotationState state) {
        this.currentState = state;
    }
    
    /**
     * Save current state to disk
     */
    public boolean saveState() {
        if (currentState == null) {
            return false;
        }
        
        try {
            FileOutputStream fos = new FileOutputStream(stateFile);
            ObjectOutputStream oos = new ObjectOutputStream(fos);
            oos.writeObject(currentState);
            oos.close();
            fos.close();
            
            Log.d(TAG, "State saved successfully to " + stateFile.getAbsolutePath());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to save state", e);
            return false;
        }
    }
    
    /**
     * Load state from disk
     */
    public AnnotationState loadState() {
        if (!stateFile.exists()) {
            Log.d(TAG, "No saved state file found");
            return null;
        }
        
        try {
            FileInputStream fis = new FileInputStream(stateFile);
            ObjectInputStream ois = new ObjectInputStream(fis);
            AnnotationState state = (AnnotationState) ois.readObject();
            ois.close();
            fis.close();
            
            // Reconstruct transient fields
            state.reconstruct();
            
            Log.d(TAG, "State loaded successfully from " + stateFile.getAbsolutePath());
            return state;
        } catch (Exception e) {
            Log.e(TAG, "Failed to load state", e);
            return null;
        }
    }
    
    /**
     * Clear saved state file
     */
    public boolean clearState() {
        if (stateFile.exists()) {
            boolean deleted = stateFile.delete();
            if (deleted) {
                currentState = new AnnotationState();
                Log.d(TAG, "State file deleted successfully");
            }
            return deleted;
        }
        return true;
    }
    
    /**
     * Check if saved state exists
     */
    public boolean hasSavedState() {
        return stateFile.exists();
    }
}
