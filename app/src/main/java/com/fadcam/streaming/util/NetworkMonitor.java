package com.fadcam.streaming.util;

import com.fadcam.Log;
import com.fadcam.FLog;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiInfo;
import android.net.NetworkCapabilities;
import android.net.Network;
import android.os.Build;
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
                    
                    // NEW: Get actual signal level if available
                    int signalLevel = getSignalLevel();
                    
                    networkHealth.updateMeasurements(mbps, uploadMbps, latency, signalLevel);
                    
                    FLog.d(TAG, String.format(java.util.Locale.US, "Speed test: ↓%.2f Mbps ↑%.2f Mbps (Latency: %dms) - Status: %s (Signal: %d/4)",
                        mbps, uploadMbps, latency, networkHealth.getStatusString(), signalLevel));
                } else {
                    int signalLevel = getSignalLevel();
                    networkHealth.updateMeasurements(0, 0, -1, signalLevel);
                }
            } catch (Exception e) {
                FLog.e(TAG, "Speed test failed", e);
                networkHealth.updateMeasurements(0, 0, -1, getSignalLevel());
            }
        });
    }
    
    /**
     * Get WiFi or Mobile signal level (0-4).
     * Returns -1 if unknown or permission missing.
     */
    public int getSignalLevel() {
        if (appContext == null) {
            FLog.w(TAG, "getSignalLevel: appContext is null");
            return -1;
        }
        
        try {
            ConnectivityManager cm = (ConnectivityManager) 
                appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return -1;
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network activeNetwork = cm.getActiveNetwork();
                if (activeNetwork != null) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(activeNetwork);
                    if (caps != null) {
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                            WifiManager wm = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
                            if (wm != null) {
                                WifiInfo info = wm.getConnectionInfo();
                                // RSSI of -127 means no signal or error
                                if (info != null && info.getRssi() != -127) {
                                    return WifiManager.calculateSignalLevel(info.getRssi(), 5); // 0-4
                                }
                            }
                        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                            // Try to get cellular signal level via TelephonyManager
                            android.telephony.TelephonyManager tm = (android.telephony.TelephonyManager) 
                                appContext.getSystemService(Context.TELEPHONY_SERVICE);
                            if (tm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                android.telephony.SignalStrength ss = tm.getSignalStrength();
                                if (ss != null) {
                                    return ss.getLevel(); // Returns 0-4
                                }
                            }
                            return 2; // Fallback to moderate
                        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                            return 4; // Ethernet is always "full bars"
                        }
                    }
                }
            } else {
                // Fallback for older devices
                NetworkInfo ni = cm.getActiveNetworkInfo();
                if (ni != null && ni.isConnected()) {
                    if (ni.getType() == ConnectivityManager.TYPE_WIFI) {
                        WifiManager wm = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
                        if (wm != null) {
                            WifiInfo info = wm.getConnectionInfo();
                            if (info != null && info.getRssi() != -127) {
                                return WifiManager.calculateSignalLevel(info.getRssi(), 5);
                            }
                        }
                    } else if (ni.getType() == ConnectivityManager.TYPE_MOBILE) {
                        return 2;
                    }
                }
            }
        } catch (Exception e) {
            FLog.w(TAG, "Failed to get signal level: " + e.getMessage());
        }
        
        return -1;
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
                FLog.w(TAG, "Failed to test with " + testUrl, e);
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
