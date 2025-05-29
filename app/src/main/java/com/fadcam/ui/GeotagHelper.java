package com.fadcam.ui;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Helper class to manage location services specifically for embedding geotags in video metadata.
 * This is separate from LocationHelper which is used for watermark text.
 */
public class GeotagHelper {
    private static final String TAG = "GeotagHelper";
    private final LocationManager locationManager;
    private final Context context;
    private Location bestLocation;
    private long lastLocationUpdateTime = 0;
    private boolean isActive = false;
    
    // Location listeners
    private LocationListener gpsListener;
    private LocationListener networkListener;
    
    // Handler for delayed operations
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    /**
     * Creates a new GeotagHelper
     * @param context Application context
     */
    public GeotagHelper(Context context) {
        this.context = context;
        this.locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        Log.d(TAG, "GeotagHelper initialized");
    }
    
    /**
     * Starts location updates to prepare for metadata embedding
     * @return true if location providers are available and updates started
     */
    public boolean startUpdates() {
        if (isActive) {
            Log.d(TAG, "Location updates already active");
            return true;
        }
        
        try {
            // Check if providers are enabled
            boolean gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            boolean networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
            
            if (!gpsEnabled && !networkEnabled) {
                Log.w(TAG, "No location providers enabled");
                return false;
            }
            
            // Get last known location as initial position
            if (gpsEnabled) {
                try {
                    Location lastKnown = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                    if (lastKnown != null) {
                        Log.d(TAG, "Using last known GPS location: " + lastKnown.getLatitude() + ", " + lastKnown.getLongitude());
                        updateBestLocation(lastKnown);
                    }
                } catch (SecurityException se) {
                    Log.e(TAG, "Security exception getting last GPS location", se);
                }
            }
            
            if (networkEnabled) {
                try {
                    Location lastKnown = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                    if (lastKnown != null) {
                        Log.d(TAG, "Using last known network location: " + lastKnown.getLatitude() + ", " + lastKnown.getLongitude());
                        updateBestLocation(lastKnown);
                    }
                } catch (SecurityException se) {
                    Log.e(TAG, "Security exception getting last network location", se);
                }
            }
            
            // Create listeners
            gpsListener = createLocationListener("GPS");
            networkListener = createLocationListener("Network");
            
            // Request updates from both providers
            if (gpsEnabled) {
                try {
                    locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER, 
                        1000, // 1 second
                        1,    // 1 meter
                        gpsListener,
                        Looper.getMainLooper()
                    );
                    Log.d(TAG, "GPS location updates requested");
                } catch (SecurityException se) {
                    Log.e(TAG, "Security exception requesting GPS updates", se);
                }
            }
            
            if (networkEnabled) {
                try {
                    locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        1000, // 1 second
                        1,    // 1 meter
                        networkListener,
                        Looper.getMainLooper()
                    );
                    Log.d(TAG, "Network location updates requested");
                } catch (SecurityException se) {
                    Log.e(TAG, "Security exception requesting network updates", se);
                }
            }
            
            isActive = true;
            Log.d(TAG, "Location updates started successfully");
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Error starting location updates", e);
            return false;
        }
    }
    
    /**
     * Stops location updates to save battery
     */
    public void stopUpdates() {
        if (!isActive) {
            return;
        }
        
        try {
            if (gpsListener != null) {
                locationManager.removeUpdates(gpsListener);
            }
            if (networkListener != null) {
                locationManager.removeUpdates(networkListener);
            }
            isActive = false;
            Log.d(TAG, "Location updates stopped");
        } catch (SecurityException se) {
            Log.e(TAG, "Security exception stopping location updates", se);
        } catch (Exception e) {
            Log.e(TAG, "Error stopping location updates", e);
        }
    }
    
    /**
     * Creates a location listener with appropriate logging
     * @param providerName Name of the provider for logging
     * @return A configured LocationListener
     */
    private LocationListener createLocationListener(final String providerName) {
        return new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                if (location != null) {
                    Log.d(TAG, providerName + " location update: " + 
                         location.getLatitude() + ", " + location.getLongitude() + 
                         " (accuracy: " + location.getAccuracy() + "m)");
                    updateBestLocation(location);
                }
            }
            
            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
                Log.d(TAG, providerName + " provider status changed: " + status);
            }
            
            @Override
            public void onProviderEnabled(String provider) {
                Log.d(TAG, providerName + " provider enabled");
            }
            
            @Override
            public void onProviderDisabled(String provider) {
                Log.d(TAG, providerName + " provider disabled");
            }
        };
    }
    
    /**
     * Updates the best location based on accuracy and recency
     * @param location New location to evaluate
     */
    private synchronized void updateBestLocation(Location location) {
        if (location == null) return;
        
        lastLocationUpdateTime = System.currentTimeMillis();
        
        if (bestLocation == null) {
            bestLocation = location;
            return;
        }
        
        // Check if new location is more accurate
        float accuracy1 = bestLocation.getAccuracy();
        float accuracy2 = location.getAccuracy();
        long time1 = bestLocation.getTime();
        long time2 = location.getTime();
        
        // If the new location is significantly more accurate, use it
        if (accuracy2 < accuracy1 * 0.8) {
            bestLocation = location;
            return;
        }
        
        // If the new location is fresher by at least 1 minute and not substantially worse, use it
        if (time2 - time1 > 60000 && accuracy2 < accuracy1 * 1.5) {
            bestLocation = location;
        }
    }
    
    /**
     * Applies the best available location to a MediaRecorder instance
     * @param recorder The MediaRecorder to configure
     * @return true if location was applied, false otherwise
     */
    public boolean applyLocationToRecorder(MediaRecorder recorder) {
        if (recorder == null) {
            Log.e(TAG, "Cannot apply location to null MediaRecorder");
            return false;
        }
        
        if (bestLocation == null) {
            Log.w(TAG, "No location available to apply to MediaRecorder");
            return false;
        }
        
        try {
            float latitude = (float) bestLocation.getLatitude();
            float longitude = (float) bestLocation.getLongitude();
            recorder.setLocation(latitude, longitude);
            Log.d(TAG, "Applied location to MediaRecorder: " + latitude + ", " + longitude);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to set location metadata in MediaRecorder", e);
            return false;
        }
    }
    
    /**
     * Gets the best current location
     * @return The most accurate recent location, or null if unavailable
     */
    public Location getCurrentLocation() {
        // Check if location is stale (older than 2 minutes)
        if (bestLocation != null) {
            long locationAge = System.currentTimeMillis() - bestLocation.getTime();
            if (locationAge > 120000) { // 2 minutes
                Log.w(TAG, "Current location is stale (" + (locationAge/1000) + " seconds old)");
                // Don't return null, still return the stale location as it's better than nothing
                // Just log a warning
            }
        }
        return bestLocation;
    }
    
    /**
     * Checks if we have a valid location
     * @return true if we have a location, false otherwise
     */
    public boolean hasLocation() {
        return bestLocation != null;
    }
    
    /**
     * Gets the location as a formatted string for logging
     * @return String representing the current location data
     */
    public String getLocationString() {
        if (bestLocation != null) {
            return String.format("%.6f, %.6f (accuracy: %.1fm)", 
                bestLocation.getLatitude(), 
                bestLocation.getLongitude(),
                bestLocation.getAccuracy());
        }
        return "No location available";
    }
} 