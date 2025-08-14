package com.fadcam.utils;

import android.content.Context;
import android.os.Build;
import android.util.Log;

/**
 * Helper class for device-specific detection and capabilities
 */
public class DeviceHelper {
    private static final String TAG = "DeviceHelper";

    /**
     * Check if the device is made by Samsung
     * 
     * @return true if the device is a Samsung device
     */
    public static boolean isSamsung() {
        return Build.MANUFACTURER.toLowerCase().contains("samsung");
    }
    
    /**
     * Check if the device is made by Google
     * 
     * @return true if the device is a Google device
     */
    public static boolean isGoogle() {
        return Build.MANUFACTURER.toLowerCase().contains("google");
    }
    
    /**
     * Check if the device is likely a high-end device based on model and specs
     * 
     * @return true if the device is likely a high-end device
     */
    public static boolean isHighEndDevice() {
        // -------------- Fix Start for this method(isHighEndDevice)-----------
        String model = Build.MODEL.toLowerCase();
        String device = Build.DEVICE.toLowerCase();
        String manufacturer = Build.MANUFACTURER.toLowerCase();
        
        // Check for common high-end device indicators
        if (isSamsung()) {
            // Samsung S and Note series are high end
            return model.contains("sm-g9") || // S series (S9-S30)
                   model.contains("sm-s9") || // S21+ and up new naming
                   model.contains("sm-n9") || // Note series
                   model.contains("sm-f9");   // Fold series
        }
        
    if (isGoogle()) {
            // Google Pixel 3 and above are considered high-end
            return model.contains("pixel 3") || 
                   model.contains("pixel 4") || 
                   model.contains("pixel 5") || 
                   model.contains("pixel 6") || 
                   model.contains("pixel 7") ||
                   model.contains("pixel 8");
        }
        
        // Generic check for other manufacturers
        // (Just some common indicators, not exhaustive)
        return model.contains("pro") || 
               model.contains("premium") ||
               model.contains("flagship") || 
               model.contains("elite");
    // -------------- Fix Ended for this method(isHighEndDevice)-----------
    }
    
    /**
     * Log device information for debugging
     */
    public static void logDeviceInfo() {
        Log.d(TAG, "Device Information:");
        Log.d(TAG, "  Manufacturer: " + Build.MANUFACTURER);
        Log.d(TAG, "  Model: " + Build.MODEL);
        Log.d(TAG, "  Device: " + Build.DEVICE);
        Log.d(TAG, "  Product: " + Build.PRODUCT);
        Log.d(TAG, "  Android Version: " + Build.VERSION.RELEASE);
        Log.d(TAG, "  SDK Level: " + Build.VERSION.SDK_INT);
    }

    /**
     * Check if the device has an active internet connection
     * @param context Context
     * @return true if internet is available, false otherwise
     */
    public static boolean isInternetAvailable(Context context) {
        if (context == null) return false;
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                android.net.NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
                return activeNetwork != null && activeNetwork.isConnected();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking internet connectivity", e);
        }
        return false;
    }
} 