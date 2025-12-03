package com.fadcam.fadrec.ui.annotation;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Command interface for undo/redo operations.
 * Implements Command pattern for version control.
 * Now supports serialization for complete version control persistence.
 */
public interface DrawingCommand {
    /**
     * Execute the command
     */
    void execute();
    
    /**
     * Undo the command
     */
    void undo();
    
    /**
     * Get command description for history display
     */
    String getDescription();
    
    /**
     * Get command type identifier for serialization
     */
    String getCommandType();
    
    /**
     * Serialize command to JSON for persistence
     */
    JSONObject toJSON() throws JSONException;
}
