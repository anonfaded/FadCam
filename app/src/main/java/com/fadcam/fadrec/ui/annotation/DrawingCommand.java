package com.fadcam.fadrec.ui.annotation;

/**
 * Command interface for undo/redo operations.
 * Implements Command pattern for version control.
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
}
