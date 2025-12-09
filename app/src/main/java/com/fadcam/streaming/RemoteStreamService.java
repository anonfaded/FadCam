package com.fadcam.streaming;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.fadcam.MainActivity;
import com.fadcam.R;

import java.io.IOException;
import java.net.ServerSocket;

/**
 * RemoteStreamService manages the HTTP streaming server as a foreground service.
 * 
 * Responsibilities:
 * - Start/stop LiveM3U8Server on a free port
 * - Display persistent notification with stream URL
 * - Auto-detect free port (8080-8090 range)
 * - Provide binder for RemoteFragment communication
 * - Manage RemoteStreamManager lifecycle
 * 
 * Lifecycle:
 * - Started when user enables streaming in RemoteFragment
 * - Runs as foreground service (survives app close)
 * - Stops when user disables streaming or recording stops
 */
public class RemoteStreamService extends Service {
    private static final String TAG = "RemoteStreamService";
    
    // Notification
    private static final String CHANNEL_ID = "remote_streaming_channel";
    private static final int NOTIFICATION_ID = 2001;
    
    // Port configuration
    private static final int DEFAULT_PORT = 8080;
    private static final int PORT_SCAN_RANGE = 10;
    
    // Server
    private LiveM3U8Server httpServer;
    private int activePort = -1;
    
    // Notification update
    private Handler notificationHandler;
    private Runnable notificationUpdateRunnable;
    
    // Binder for RemoteFragment
    private final IBinder binder = new LocalBinder();
    
    public class LocalBinder extends Binder {
        public RemoteStreamService getService() {
            return RemoteStreamService.this;
        }
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service created");
        createNotificationChannel();
        
        // Initialize RemoteStreamManager with context for status reporting
        RemoteStreamManager.getInstance().setContext(this);
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Service starting");
        
        // Handle copy stream URL action from notification
        if (intent != null && "com.fadcam.COPY_STREAM_URL".equals(intent.getAction())) {
            String streamUrl = intent.getStringExtra("stream_url");
            if (streamUrl != null) {
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                android.content.ClipData clip = android.content.ClipData.newPlainText("Stream URL", streamUrl);
                clipboard.setPrimaryClip(clip);
                Log.d(TAG, "Stream URL copied to clipboard: " + streamUrl);
            }
            return START_STICKY;
        }
        
        // Start foreground with notification
        startForeground(NOTIFICATION_ID, buildNotification("Ready. Start recording to begin streaming.", "http://..."));
        
        // Start HTTP server
        if (!startHttpServer()) {
            Log.e(TAG, "Failed to start HTTP server, stopping service");
            stopSelf();
            return START_NOT_STICKY;
        }
        
        // Enable streaming in RemoteStreamManager
        RemoteStreamManager.getInstance().setStreamingEnabled(true);
        
        // Update notification with stream URL
        updateNotification();
        
        // Start periodic notification updates
        startNotificationUpdates();
        
        return START_STICKY;
    }
    
    @Override
    public void onDestroy() {
        Log.i(TAG, "Service stopping");
        
        // Stop periodic updates
        stopNotificationUpdates();
        
        // Stop HTTP server
        stopHttpServer();
        
        // Disable streaming in RemoteStreamManager (clears buffer automatically)
        RemoteStreamManager.getInstance().setStreamingEnabled(false);
        
        super.onDestroy();
    }
    
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
    
    /**
     * Start HTTP server on a free port.
     */
    private boolean startHttpServer() {
        // Find free port
        int port = findFreePort(DEFAULT_PORT);
        if (port == -1) {
            Log.e(TAG, "No free port available in range " + DEFAULT_PORT + "-" + (DEFAULT_PORT + PORT_SCAN_RANGE));
            return false;
        }
        
        try {
            httpServer = new LiveM3U8Server(this, port);  // Pass context for assets loading
            httpServer.start();
            activePort = port;
            Log.i(TAG, "HTTP server started on port " + port);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to start HTTP server", e);
            return false;
        }
    }
    
    /**
     * Stop HTTP server.
     */
    private void stopHttpServer() {
        if (httpServer != null) {
            httpServer.stop();
            Log.i(TAG, "HTTP server stopped");
            httpServer = null;
            activePort = -1;
        }
    }
    
    /**
     * Start periodic notification updates.
     */
    private void startNotificationUpdates() {
        notificationHandler = new Handler(Looper.getMainLooper());
        notificationUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                updateNotification();
                notificationHandler.postDelayed(this, 5000); // Update every 5 seconds
            }
        };
        notificationHandler.postDelayed(notificationUpdateRunnable, 5000);
    }
    
    /**
     * Stop periodic notification updates.
     */
    private void stopNotificationUpdates() {
        if (notificationHandler != null && notificationUpdateRunnable != null) {
            notificationHandler.removeCallbacks(notificationUpdateRunnable);
        }
    }
    
    /**
     * Find a free port starting from the default port.
     */
    private int findFreePort(int startPort) {
        for (int port = startPort; port < startPort + PORT_SCAN_RANGE; port++) {
            if (isPortAvailable(port)) {
                Log.d(TAG, "Found free port: " + port);
                return port;
            }
        }
        return -1;
    }
    
    /**
     * Check if a port is available.
     */
    private boolean isPortAvailable(int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            socket.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
    
    /**
     * Get local IP address (WiFi).
     */
    private String getLocalIpAddress() {
        try {
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                int ipInt = wifiInfo.getIpAddress();
                
                if (ipInt != 0) {
                    return String.format("%d.%d.%d.%d",
                        (ipInt & 0xff),
                        (ipInt >> 8 & 0xff),
                        (ipInt >> 16 & 0xff),
                        (ipInt >> 24 & 0xff));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get IP address", e);
        }
        return "N/A";
    }
    
    /**
     * Build notification for foreground service.
     */
    private Notification buildNotification(String contentText, String streamUrl) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        
        // Create copy link action
        Intent copyIntent = new Intent(this, RemoteStreamService.class);
        copyIntent.setAction("com.fadcam.COPY_STREAM_URL");
        copyIntent.putExtra("stream_url", streamUrl);
        PendingIntent copyPendingIntent = PendingIntent.getService(
            this, 1, copyIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Remote Streaming Active")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_broadcast_on_personal_24)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_view, "Copy Link", copyPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }
    
    /**
     * Update notification with stream URL.
     */
    private void updateNotification() {
        if (activePort == -1) {
            return;
        }
        
        String ipAddress = getLocalIpAddress();
        String streamUrl = "http://" + ipAddress + ":" + activePort + "/live.m3u8";
        
        // Check if we have fragments
        int fragmentCount = RemoteStreamManager.getInstance().getBufferedCount();
        String contentText;
        if (fragmentCount > 0) {
            contentText = "Streaming: " + streamUrl + " (" + fragmentCount + " fragments)";
        } else {
            contentText = streamUrl + " • Start recording to stream";
        }
        
        Notification notification = buildNotification(contentText, streamUrl);
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, notification);
        }
    }
    
    /**
     * Create notification channel for Android O+.
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Remote Streaming",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows when remote streaming is active");
            channel.setShowBadge(false);
            
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }
    
    /**
     * Get current stream URL for display in RemoteFragment.
     */
    public String getStreamUrl() {
        if (activePort == -1) {
            return null;
        }
        String ipAddress = getLocalIpAddress();
        return "http://" + ipAddress + ":" + activePort + "/live.m3u8";
    }

    /**
     * Get device IP address with port (without /live.m3u8).
     */
    public String getDeviceIpWithPort() {
        if (activePort == -1) {
            return null;
        }
        String ipAddress = getLocalIpAddress();
        return ipAddress + ":" + activePort;
    }
    
    /**
     * Get active port.
     */
    public int getActivePort() {
        return activePort;
    }
    
    /**
     * Check if server is running.
     */
    public boolean isServerRunning() {
        return httpServer != null && httpServer.isAlive();
    }
}
