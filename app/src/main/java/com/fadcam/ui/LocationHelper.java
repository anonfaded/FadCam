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
            FLog.d(TAG, "🗺️ LOCATION_HELPER: Starting location updates with 300ms polling");
            provider.startLocationProvider(new IMyLocationConsumer() {
                @Override
                public void onLocationChanged(Location location, IMyLocationProvider source) {
                    if (location != null) {
                        lastLocationUpdateTime = System.currentTimeMillis();
                        currentLocation = new GeoPoint(location.getLatitude(), location.getLongitude());
                        FLog.d(TAG, "✅ LOCATION_HELPER: GPS updated to " + 
                            String.format("%.4f", currentLocation.getLatitude()) + ", " + 
                            String.format("%.4f", currentLocation.getLongitude()) + 
                            " (accuracy: " + String.format("%.0f", location.getAccuracy()) + "m)");
                    } else {
                        FLog.w(TAG, "❌ LOCATION_HELPER: Received null location update");
                    }
                    isInitializing.set(false);
                }
            });

            // If we don't get a location update in 30 seconds, log a warning.
            // Cold GPS start can take 30-60s, so 5s was a false alarm on many devices.
            mainHandler.postDelayed(() -> {
                if (currentLocation == null) {
                    FLog.w(TAG, "⚠️ LOCATION_HELPER: No GPS fix after 30 seconds (check permission / GPS enabled)");
                    isInitializing.set(false);
                }
            }, 30000);
        } else {
            FLog.w(TAG, "❌ LOCATION_HELPER: Provider null or already initializing");
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
        
        // Redact to ~1km precision for log privacy
        FLog.d(TAG, "LOCATION_HELPER: Providing location: ~" +
            String.format(java.util.Locale.US, "%.2f", currentLocation.getLatitude()) + ", ~" +
            String.format(java.util.Locale.US, "%.2f", currentLocation.getLongitude()));
        return currentLocation;
    }

    public String getLocationData() {
        FLog.d(TAG, "LOCATION_HELPER: getLocationData called");
        if (currentLocation != null) {
            FLog.d(TAG, "LOCATION_HELPER: Location data found (~" +
                String.format(java.util.Locale.US, "%.2f", currentLocation.getLatitude()) + ", ~" +
                String.format(java.util.Locale.US, "%.2f", currentLocation.getLongitude()) + ")");
            // Return full precision for the actual watermark text
            return "\nLat= " + currentLocation.getLatitude() + ", Lon= " + currentLocation.getLongitude();
        }
        FLog.d(TAG, "LOCATION_HELPER: Location data not found");
        return "\nLocation not available";
    }
}
