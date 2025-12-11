package com.fadcam.streaming.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.fadcam.streaming.model.NetworkHealth;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Utility class for monitoring network health with actual speed measurements.
 * Performs lightweight speed tests using HTTP downloads.
 * Follows singleton pattern with thread-safe operations.
 */
public class NetworkMonitor {
    private static final String TAG = "NetworkMonitor";
    private static NetworkMonitor instance;
    
    // Test URLs (small files for quick testing)
    private static final String[] TEST_URLS = {
        "https://www.google.com/favicon.ico",
        "https://www.github.com/favicon.ico"
    };
    
    private final ExecutorService executorService;
    private final NetworkHealth networkHealth;
    private final Handler mainHandler;
    private Context appContext;
    private boolean isMonitoring;
    
    private NetworkMonitor() {
        this.executorService = Executors.newSingleThreadExecutor();
        this.networkHealth = new NetworkHealth();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.isMonitoring = false;
    }
    
    public static synchronized NetworkMonitor getInstance() {
        if (instance == null) {
            instance = new NetworkMonitor();
        }
        return instance;
    }
    
    /**
     * Initialize with application context.
     */
    public void initialize(Context context) {
        this.appContext = context.getApplicationContext();
    }
    
    /**
     * Start periodic network monitoring.
     */
    public void startMonitoring() {
        if (isMonitoring) return;
        isMonitoring = true;
        scheduleNextTest(0); // Start immediately
    }
    
    /**
     * Stop network monitoring.
     */
    public void stopMonitoring() {
        isMonitoring = false;
        mainHandler.removeCallbacksAndMessages(null);
    }
    
    /**
     * Schedule next speed test.
     */
    private void scheduleNextTest(long delayMs) {
        if (!isMonitoring) return;
        
        mainHandler.postDelayed(() -> {
            performSpeedTest();
            // Test every 2 minutes
            scheduleNextTest(120000);
        }, delayMs);
    }
    
    /**
     * Perform network speed test.
     */
    private void performSpeedTest() {
        if (appContext == null || !isNetworkAvailable()) {
            networkHealth.updateMeasurements(0, 0, -1);
            return;
        }
        
        executorService.execute(() -> {
            try {
                // Measure download speed and latency
                long startTime = System.currentTimeMillis();
                long bytesDownloaded = downloadTestFile();
                long endTime = System.currentTimeMillis();
                
                if (bytesDownloaded > 0) {
                    long durationMs = endTime - startTime;
                    int latency = (int) Math.min(durationMs, Integer.MAX_VALUE);
                    
                    // Calculate speed (bytes/ms -> Mbps)
                    double bytesPerMs = (double) bytesDownloaded / durationMs;
                    double bitsPerSecond = bytesPerMs * 8 * 1000;
                    double mbps = bitsPerSecond / (1024 * 1024);
                    
                    // For streaming server, upload matters more
                    // Estimate upload as 60% of download (typical asymmetric ratio)
                    double uploadMbps = mbps * 0.6;
                    
                    networkHealth.updateMeasurements(mbps, uploadMbps, latency);
                    
                    Log.d(TAG, String.format("Speed test: ↓%.2f Mbps ↑%.2f Mbps (Latency: %dms) - Status: %s",
                        mbps, uploadMbps, latency, networkHealth.getStatusString()));
                } else {
                    networkHealth.updateMeasurements(0, 0, -1);
                }
            } catch (Exception e) {
                Log.e(TAG, "Speed test failed", e);
                networkHealth.updateMeasurements(0, 0, -1);
            }
        });
    }
    
    /**
     * Download test file and return bytes downloaded.
     */
    private long downloadTestFile() {
        for (String testUrl : TEST_URLS) {
            try {
                URL url = new URL(testUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.connect();
                
                if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    InputStream input = connection.getInputStream();
                    byte[] buffer = new byte[4096];
                    long totalBytes = 0;
                    int bytesRead;
                    
                    while ((bytesRead = input.read(buffer)) != -1) {
                        totalBytes += bytesRead;
                    }
                    
                    input.close();
                    connection.disconnect();
                    
                    return totalBytes;
                }
                connection.disconnect();
            } catch (Exception e) {
                Log.w(TAG, "Failed to test with " + testUrl, e);
            }
        }
        return 0;
    }
    
    /**
     * Check if network is available.
     */
    private boolean isNetworkAvailable() {
        if (appContext == null) return false;
        
        ConnectivityManager cm = (ConnectivityManager) 
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.isConnected();
    }
    
    /**
     * Get current network health.
     */
    public NetworkHealth getNetworkHealth() {
        return networkHealth;
    }
    
    /**
     * Trigger immediate speed test.
     */
    public void testNow() {
        if (appContext != null) {
            performSpeedTest();
        }
    }
    
    /**
     * Cleanup resources.
     */
    public void shutdown() {
        stopMonitoring();
        executorService.shutdown();
    }
}
