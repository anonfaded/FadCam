package com.fadcam.ui; // Or your appropriate package

import android.net.Uri;

/**
 * Listener interface for actions performed on records (like Save to Gallery)
 * allowing the Fragment to show progress and results.
 */
public interface RecordActionListener {
    /**
     * Called when the "Save to Gallery" operation starts.
     * @param fileName The display name of the file being saved.
     */
    void onSaveToGalleryStarted(String fileName);

    /**
     * Called when the "Save to Gallery" operation finishes.
     * @param success True if the save was successful, false otherwise.
     * @param message A status message (e.g., success or failure reason).
     * @param outputUri The Content URI of the saved file if successful, null otherwise.
     */
    void onSaveToGalleryFinished(boolean success, String message, Uri outputUri);

    /**
     * Called by the adapter after a deletion has occurred, allowing the
     * listener (Fragment) to check if the list is now empty and update UI.
     */
    void onDeletionFinishedCheckEmptyState();
    // Add more methods here for other actions if needed (e.g., onDeleteStarted/Finished)
}