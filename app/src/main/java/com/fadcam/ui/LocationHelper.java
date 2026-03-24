package com.fadcam.ui;

import com.fadcam.Log;
import com.fadcam.FLog;
import static android.content.ContentValues.TAG;

import android.content.Context;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.IMyLocationConsumer;
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider;

import java.util.concurrent.atomic.AtomicBoolean;

public class LocationHelper {

    private final GpsMyLocationProvider provider;
    private GeoPoint currentLocation;
    private final AtomicBoolean isInitializing = new AtomicBoolean(false);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private long lastLocationUpdateTime = 0;

    public LocationHelper(Context context) {
        FLog.d(TAG, "LOCATION_HELPER: Initializing LocationHelper");
        provider = new GpsMyLocationProvider(context);

        // Configure for very frequent updates to ensure we have location data when needed
        provider.setLocationUpdateMinTime(300); // 300 milliseconds (0.3 seconds for more frequent updates)
        provider.setLocationUpdateMinDistance(1); // 1 meter (very sensitive to movement)
        FLog.d(TAG, "LOCATION_HELPER: Set up location provider with high-frequency updates");

        // Start location updates immediately
        startLocationUpdates();
    }

    public void startLocationUpdates() {
        if (provider != null && !isInitializing.getAndSet(true)) {
            FLog.d(TAG, "LOCATION_HELPER: Starting location updates");
            provider.startLocationProvider(new IMyLocationConsumer() {
                @Override
                public void onLocationChanged(Location location, IMyLocationProvider source) {
                    if (location != null) {
                        lastLocationUpdateTime = System.currentTimeMillis();
                        currentLocation = new GeoPoint(location.getLatitude(), location.getLongitude());
                        FLog.d(TAG, "LOCATION_HELPER: Location updated: " + 
                            currentLocation.getLatitude() + ", " + 
                            currentLocation.getLongitude() + 
                            " (accuracy: " + location.getAccuracy() + "m)");
                    } else {
                        FLog.w(TAG, "LOCATION_HELPER: Received null location update");
                    }
                    isInitializing.set(false);
                }
            });

            // If we don't get a location update in 5 seconds, log a warning
            mainHandler.postDelayed(() -> {
                if (currentLocation == null) {
                    FLog.w(TAG, "LOCATION_HELPER: No location updates received after 5 seconds");
                    isInitializing.set(false);
                }
            }, 5000);
        } else {
            FLog.w(TAG, "LOCATION_HELPER: Location provider is null or already initializing");
        }
    }

    public void stopLocationUpdates() {
        if (provider != null) {
            FLog.d(TAG, "LOCATION_HELPER: Stopping location updates");
            provider.stopLocationProvider();
        }
    }

    /**
     * Gets the current location. If no location is available, tries to start
     * updates again in case there was an issue.
     * 
     * @return The current GeoPoint, or null if location not available
     */
    public org.osmdroid.util.GeoPoint getCurrentLocation() {
        if (currentLocation == null) {
            FLog.w(TAG, "LOCATION_HELPER: getCurrentLocation requested but no location available");
            
            // Check if a recent location update was received
            boolean staleLocation = lastLocationUpdateTime == 0 || 
                System.currentTimeMillis() - lastLocationUpdateTime > 10000; // 10 seconds
                
            if (staleLocation && !isInitializing.get()) {
                FLog.d(TAG, "LOCATION_HELPER: Trying to restart location updates due to stale data");
                startLocationUpdates();
            }
            return null;
        }
        
        FLog.d(TAG, "LOCATION_HELPER: Providing location: " + 
            currentLocation.getLatitude() + ", " + currentLocation.getLongitude());
        return currentLocation;
    }

    public String getLocationData() {
        FLog.d(TAG, "LOCATION_HELPER: getLocationData called");
        if (currentLocation != null) {
            FLog.d(TAG, "LOCATION_HELPER: Location data found");
            return "\nLat= " + currentLocation.getLatitude() + ", Lon= " + currentLocation.getLongitude();
        }
        FLog.d(TAG, "LOCATION_HELPER: Location data not found");
        return "\nLocation not available";
    }
}
