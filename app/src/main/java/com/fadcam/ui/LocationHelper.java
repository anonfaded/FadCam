package com.fadcam.ui;

import static android.content.ContentValues.TAG;

import android.content.Context;
import android.location.Location;
import android.util.Log;

import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.IMyLocationConsumer;
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider;

public class LocationHelper {

    private final GpsMyLocationProvider provider;
    private GeoPoint currentLocation;

    public LocationHelper(Context context) {
        provider = new GpsMyLocationProvider(context);

        // Set a location update handler
        provider.setLocationUpdateMinTime(1000); // 1 second
        provider.setLocationUpdateMinDistance(10); // 10 meters

        provider.startLocationProvider(new IMyLocationConsumer() {
            @Override
            public void onLocationChanged(Location location, IMyLocationProvider source) {
                if (location != null) {
                    currentLocation = new GeoPoint(location.getLatitude(), location.getLongitude());
                }
            }
        });
    }

    public void startLocationUpdates() {
        if (provider != null) {
            provider.startLocationProvider(new IMyLocationConsumer() {
                @Override
                public void onLocationChanged(Location location, IMyLocationProvider source) {
                    if (location != null) {
                        currentLocation = new GeoPoint(location.getLatitude(), location.getLongitude());
                    }
                }
            });
        }
    }

    public void stopLocationUpdates() {
        if (provider != null) {
            provider.stopLocationProvider();
        }
    }

    public String getLocationData() {
        Log.d(TAG, "getLocationData: getting location data");
        if (currentLocation != null) {
            Log.d(TAG, "getLocationData: location data found");
            return "\nLat= " + currentLocation.getLatitude() + ", Lon= " + currentLocation.getLongitude();
        }
        Log.d(TAG, "getLocationData: location data not found");
        return "\nLocation not available";
    }
}
