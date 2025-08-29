package com.fadcam.ui.faditor.processors;

import com.fadcam.ui.faditor.models.EditOperation;
import com.fadcam.ui.faditor.models.VideoMetadata;

import java.io.File;

/**
 * Base interface for video processing operations.
 * Defines the contract for video processing implementations.
 */
public interface VideoProcessor {
    
    /**
     * Callback interface for processing operations
     */
    interface ProcessingCallback {
        void onProgress(int percentage);
        void onSuccess(File outputFile);
        void onError(String errorMessage);
    }
    
    /**
     * Process a video with the given operation
     */
    void processVideo(File inputFile, EditOperation operation, File outputFile, ProcessingCallback callback);
    
    /**
     * Check if the processor can handle the given operation losslessly
     */
    boolean canProcessLossless(VideoMetadata metadata, EditOperation operation);
    
    /**
     * Cancel any ongoing processing operation
     */
    void cancelProcessing();
    
    /**
     * Release any resources held by the processor
     */
    void release();
}