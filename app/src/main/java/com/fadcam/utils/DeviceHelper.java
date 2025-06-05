package com.fadcam.utils;

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
     * Check if the device is made by Huawei or Honor
     * 
     * @return true if the device is a Huawei or Honor device
     */
    public static boolean isHuawei() {
        String manufacturer = Build.MANUFACTURER.toLowerCase();
        return manufacturer.contains("huawei") || manufacturer.contains("honor");
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
        
        if (isHuawei()) {
            // Huawei P and Mate series are high end
            return model.contains("p20") || 
                   model.contains("p30") || 
                   model.contains("p40") || 
                   model.contains("mate");
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
} 