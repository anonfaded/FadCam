package com.fadcam.ui.faditor.components;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import com.fadcam.R;

/**
 * Dialog for handling crash recovery scenarios
 * Requirement 12.4: Recover last saved state when app crashes
 */
public class CrashRecoveryDialog {
    
    public interface CrashRecoveryListener {
        void onRecoverRequested(String projectId);
        void onDiscardRequested(String projectId);
    }
    
    private final Context context;
    private CrashRecoveryListener listener;
    
    public CrashRecoveryDialog(Context context) {
        this.context = context;
    }
    
    public void setListener(CrashRecoveryListener listener) {
        this.listener = listener;
    }
    
    /**
     * Shows the crash recovery dialog for a specific project
     */
    public void showRecoveryDialog(String projectId, String projectName) {
        if (context == null || listener == null) {
            return;
        }
        
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(context.getString(R.string.faditor_crash_recovery_title));
        
        String message = context.getString(R.string.faditor_crash_recovery_message);
        if (projectName != null && !projectName.isEmpty()) {
            message = "Project \"" + projectName + "\" was interrupted. " + message;
        }
        builder.setMessage(message);
        
        // Recover button
        builder.setPositiveButton(
            context.getString(R.string.faditor_crash_recovery_recover),
            new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    if (listener != null) {
                        listener.onRecoverRequested(projectId);
                    }
                    dialog.dismiss();
                }
            }
        );
        
        // Start fresh button
        builder.setNegativeButton(
            context.getString(R.string.faditor_crash_recovery_discard),
            new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    if (listener != null) {
                        listener.onDiscardRequested(projectId);
                    }
                    dialog.dismiss();
                }
            }
        );
        
        // Make dialog non-cancelable to ensure user makes a choice
        builder.setCancelable(false);
        
        AlertDialog dialog = builder.create();
        dialog.show();
    }
}