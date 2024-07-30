package com.fadcam.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.util.Log;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;

public class LocationHelper {

    private static final String TAG = "LocationHelper";
    private final Context context;
    private final FusedLocationProviderClient fusedLocationClient;
    private Location currentLocation;

    public LocationHelper(Context context) {
        this.context = context;
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(context);
        Log.d(TAG, "LocationHelper initialized.");
    }

    @SuppressLint("MissingPermission")
    public void startLocationUpdates() {
        Log.d(TAG, "Starting location updates.");

        LocationRequest locationRequest = LocationRequest.create()
                .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
                .setInterval(10000) // 10 seconds
                .setFastestInterval(5000); // 5 seconds

        Log.d(TAG, "Requesting location updates with interval: " + locationRequest.getInterval() +
                " and fastest interval: " + locationRequest.getFastestInterval());

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, null);
    }

    public void stopLocationUpdates() {
        Log.d(TAG, "Stopping location updates.");
        fusedLocationClient.removeLocationUpdates(locationCallback);
    }

    private final LocationCallback locationCallback = new LocationCallback() {
        @Override
        public void onLocationResult(LocationResult locationResult) {
            if (locationResult == null) {
                Log.d(TAG, "Location result is null.");
                return;
            }
            for (Location location : locationResult.getLocations()) {
                currentLocation = location;
                Log.d(TAG, "Location received: " + location.toString());
            }
        }
    };

    public String getLocationData() {
        if (currentLocation != null) {
            Log.d(TAG, "Returning location data: Lat: " + currentLocation.getLatitude() + ", Lon: " + currentLocation.getLongitude());
            return "\nLat= " + currentLocation.getLatitude() + ", Lon= " + currentLocation.getLongitude();
        } else {
            Log.d(TAG, "Location not available.");
            return "\nLocation not available";
        }
    }
}
