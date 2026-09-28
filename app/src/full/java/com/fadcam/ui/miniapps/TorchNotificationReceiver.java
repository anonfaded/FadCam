package com.fadcam.ui.miniapps;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.fadcam.FLog;
import com.fadcam.Constants;

/**
 * BroadcastReceiver for handling torch notification actions
 * When user taps the "Torch is On" notification to turn it off
 */
public class TorchNotificationReceiver extends BroadcastReceiver {
    private static final int TORCH_NOTIFICATION_ID = 9001;
    
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        
        String action = intent.getAction();
        if ("com.fadcam.TORCH_OFF".equals(action)) {
            try {
                // Use singleton TorchManager instance - ensures all parts of app access same state
                TorchManager torchManager = TorchManager.getInstance(context);
                torchManager.setTorchEnabled(false);
                
                // Dismiss the notification
                NotificationManager notificationManager = 
                        (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                if (notificationManager != null) {
                    notificationManager.cancel(TORCH_NOTIFICATION_ID);
                    FLog.d("TorchNotificationReceiver", "Torch notification dismissed");
                }
                
                FLog.d("TorchNotificationReceiver", "Torch turned off from notification");
            } catch (Exception e) {
                FLog.e("TorchNotificationReceiver", "Failed to turn off torch", e);
            }
        }
    }
}
