package com.fadcam.services;

import android.os.Handler;
import android.os.Looper;

import com.fadcam.FLog;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

import org.json.JSONObject;

/**
 * LocationGeocoder provides async reverse geocoding using Nominatim (OpenStreetMap).
 * Converts latitude/longitude to human-readable location names.
 * 
 * Implementation: Uses Nominatim (https://nominatim.org) - fully free, open-source
 * No API keys required, respects open-source community standards.
 * 
 * Best Practices Implemented:
 * - Non-blocking async operations (background executor)
 * - Request deduplication via spatial caching (clusters nearby coordinates)
 * - Rate limiting to respect Nominatim's guidelines (1 req/sec)
 * - Proper User-Agent header for community service
 * - Graceful error handling with fallback
 * - Thread-safe concurrent operations
 * - Main thread callbacks for safe UI updates
 * - Connection timeout protection
 */
public class LocationGeocoder {
    private static final String TAG = "LocationGeocoder";
    
    // zoom=18 → building-level detail; accept-language=en → always English names
    private static final String NOMINATIM_URL = "https://nominatim.openstreetmap.org/reverse?format=json&lat=%f&lon=%f&zoom=18&addressdetails=1&accept-language=en";
    private static final String USER_AGENT = "FadCam/1.0 (Open Source Video Recorder)";
    // 0.01° bucket ≈ 1 km cache granularity — precise enough for accurate geocoding
    private static final double SPATIAL_BUCKET = 0.01;
    private static final int CONNECTION_TIMEOUT_MS = 8000;
    private static final long RATE_LIMIT_MS = 1100;
    
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ConcurrentHashMap<String, GeocodeResult> cache = new ConcurrentHashMap<>();
    private final AtomicLong lastRequestTime = new AtomicLong(0);
    private final AtomicReference<GeocodeResult> currentResult = new AtomicReference<>(GeocodeResult.EMPTY);
    private final ConcurrentHashMap<String, Boolean> pendingRequests = new ConcurrentHashMap<>();
    
    public LocationGeocoder() {
        // No context needed - uses free HTTP API
    }
    
    /**
     * Request async reverse geocoding for coordinates.
     * Uses spatial caching and rate limiting.
     */
    public void geocodeAsync(double latitude, double longitude, GeocodeCallback callback) {
        String spatialKey = getSpatialKey(latitude, longitude);
        
        // Check cache first
        if (cache.containsKey(spatialKey)) {
            GeocodeResult cached = cache.get(spatialKey);
            mainHandler.post(() -> callback.onGeocodeResult(cached));
            currentResult.set(cached);
            FLog.d(TAG, "Returned cached geocode result");
            return;
        }
        
        // Check if request already pending
        if (pendingRequests.getOrDefault(spatialKey, false)) {
            FLog.d(TAG, "Geocode request already pending");
            return;
        }
        
        // Check rate limiting
        long timeSinceLastRequest = System.currentTimeMillis() - lastRequestTime.get();
        if (timeSinceLastRequest < RATE_LIMIT_MS) {
            long delayMs = RATE_LIMIT_MS - timeSinceLastRequest;
            mainHandler.postDelayed(() -> geocodeAsync(latitude, longitude, callback), delayMs);
            return;
        }
        
        // Mark pending and record time
        pendingRequests.put(spatialKey, true);
        lastRequestTime.set(System.currentTimeMillis());
        
        // Fetch in background
        executor.execute(() -> {
            try {
                GeocodeResult result = fetchFromNominatim(latitude, longitude);
                cache.put(spatialKey, result);
                currentResult.set(result);
                
                mainHandler.post(() -> {
                    callback.onGeocodeResult(result);
                    pendingRequests.put(spatialKey, false);
                    FLog.d(TAG, "Geocoded: " + result.formatted);
                });
            } catch (Exception e) {
                FLog.e(TAG, "Geocoding error", e);
                mainHandler.post(() -> {
                    callback.onGeocodeResult(GeocodeResult.EMPTY);
                    pendingRequests.put(spatialKey, false);
                });
            }
        });
    }
    
    /**
     * Fetch reverse geocoding result from Nominatim API.
     * Uses zoom=18 (building-level, maximum precision) with accept-language=en.
     * Returns Nominatim's canonical display_name — no custom fallback logic needed.
     */
    private GeocodeResult fetchFromNominatim(double latitude, double longitude) throws Exception {
        String urlString = String.format(Locale.US, NOMINATIM_URL, latitude, longitude);
        FLog.d(TAG, "🌐 Querying Nominatim (~" + String.format(Locale.US, "%.2f", latitude) + ", ~" + String.format(Locale.US, "%.2f", longitude) + ")");
        
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept-Language", "en");
        conn.setConnectTimeout(CONNECTION_TIMEOUT_MS);
        conn.setReadTimeout(CONNECTION_TIMEOUT_MS);
        conn.setRequestMethod("GET");
        
        try {
            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                FLog.w(TAG, "🌐 Nominatim error code: " + responseCode);
                return GeocodeResult.EMPTY;
            }
            
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();
            
            FLog.d(TAG, "🌐 Nominatim response received (" + response.length() + " chars)");
            
            JSONObject json = new JSONObject(response.toString());

            // display_name is Nominatim's canonical pre-formatted address at the requested
            // zoom level (zoom=18 = building). No custom fallback chain needed — Nominatim
            // internally handles all country/region admin hierarchies.
            String displayName = json.optString("display_name", "");
            if (displayName.isEmpty()) {
                FLog.w(TAG, "🌐 Empty display_name in Nominatim response");
                return GeocodeResult.EMPTY;
            }

            // Strip postal code segment (4-6 digits) for a cleaner watermark
            String formatted = displayName.replaceAll(",?\\s*\\d{4,6}(?=,|$)", "").trim();
            if (formatted.endsWith(",")) formatted = formatted.substring(0, formatted.length() - 1).trim();

            FLog.d(TAG, "🌐 Geocoding result ready");
            return new GeocodeResult(formatted);
            
        } finally {
            conn.disconnect();
        }
    }
    
    /**
     * Get spatial cache key for coordinates.
     * Bucket size = SPATIAL_BUCKET degrees (~1 km) to avoid redundant requests
     * while being granular enough for accurate geocoding.
     */
    private String getSpatialKey(double latitude, double longitude) {
        int latBucket = (int) Math.floor(latitude / SPATIAL_BUCKET);
        int lonBucket = (int) Math.floor(longitude / SPATIAL_BUCKET);
        return latBucket + "," + lonBucket;
    }
    
    /**
     * Get last geocoded result synchronously.
     */
    public GeocodeResult getCurrentResult() {
        return currentResult.get();
    }
    
    /**
     * Clear all caches.
     */
    public void clearCache() {
        cache.clear();
        pendingRequests.clear();
        currentResult.set(GeocodeResult.EMPTY);
        FLog.d(TAG, "Cleared geocoding cache");
    }
    
    /**
     * Shutdown executor service.
     */
    public void shutdown() {
        executor.shutdownNow();
        FLog.d(TAG, "LocationGeocoder shutdown");
    }
    
    /**
     * Result holder for reverse geocoding.
     */
    public static class GeocodeResult {
        public static final GeocodeResult EMPTY = new GeocodeResult("");
        
        /** Nominatim's canonical formatted address (display_name, postcode stripped). */
        public final String formatted;
        
        public GeocodeResult(String formatted) {
            this.formatted = formatted != null ? formatted : "";
        }
        
        public boolean isEmpty() {
            return formatted.isEmpty();
        }
    }
    
    /**
     * Callback for geocode results (called on main thread).
     */
    @FunctionalInterface
    public interface GeocodeCallback {
        void onGeocodeResult(GeocodeResult result);
    }
}
