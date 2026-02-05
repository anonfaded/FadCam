package com.fadcam.streaming;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
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
        
        // Save port to preferences for CloudStatusManager
        getSharedPreferences("FadCamPrefs", MODE_PRIVATE)
            .edit()
            .putInt("stream_server_port", activePort)
            .apply();
        
        // Enable streaming in RemoteStreamManager
        RemoteStreamManager.getInstance().setStreamingEnabled(true);
        
        // Start cloud status manager (handles status push and command poll)
        // This works independently of video streaming - just needs server to be on
        CloudStatusManager.getInstance(this).start();
        
        // Update notification with stream URL
        updateNotification();
        
        // Start periodic notification updates
        startNotificationUpdates();
        
        return START_STICKY;
    }
    
    @Override
    public void onDestroy() {
        Log.i(TAG, "Service stopping");
        
        // Stop cloud status manager
        CloudStatusManager.getInstance(this).stop();
        
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
            Log.e(TAG, "❌ No free port available in range " + DEFAULT_PORT + "-" + (DEFAULT_PORT + PORT_SCAN_RANGE));
            return false;
        }
        
        try {
            httpServer = new LiveM3U8Server(this, port);  // Pass context for assets loading
            httpServer.start();
            activePort = port;
            
            // Log comprehensive server startup info
            String ipAddress = getLocalIpAddress();
            String serverUrl = "http://" + ipAddress + ":" + port;
            
            Log.i(TAG, "════════════════════════════════════════════════════════");
            Log.i(TAG, "🚀 HTTP SERVER STARTED");
            Log.i(TAG, "════════════════════════════════════════════════════════");
            Log.i(TAG, "   📍 Address: " + serverUrl);
            Log.i(TAG, "   🔌 Port: " + port);
            Log.i(TAG, "   🌐 IP: " + ipAddress);
            Log.i(TAG, "   📡 Endpoints:");
            Log.i(TAG, "      • Dashboard: " + serverUrl + "/");
            Log.i(TAG, "      • Status API: " + serverUrl + "/status");
            Log.i(TAG, "      • HLS Stream: " + serverUrl + "/live.m3u8");
            Log.i(TAG, "════════════════════════════════════════════════════════");
            Log.i(TAG, "   ⚠️  Open the URL above on a device on the SAME network");
            Log.i(TAG, "════════════════════════════════════════════════════════");
            
            return true;
        } catch (IOException e) {
            Log.e(TAG, "❌ Failed to start HTTP server", e);
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
                notificationHandler.postDelayed(this, 30000); // Update every 30 seconds (was 5s, too frequent)
            }
        };
        notificationHandler.postDelayed(notificationUpdateRunnable, 30000);
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
     * Get local IP address with intelligent prioritization based on IP ranges ONLY.
     * Removed interface name detection to avoid conflicts on different Android devices.
     * Uses pure IP address range detection for maximum compatibility.
     * Logs every call (not just first) to debug IP flipping issues.
     */
    private String getLocalIpAddress() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            String vpnIp = null;           // VPN IP (fallback)
            String hotspotIp = null;       // 192.168.x.x range (PREFERRED)
            String wifiLanIp = null;       // 10.x.x.x or 172.x.x.x (WiFi/LAN)
            String cellularIp = null;      // 100.x.x.x range (CGNAT cellular)
            String otherIp = null;         // Any other IP
            
            StringBuilder networkLog = new StringBuilder();
            networkLog.append("📡 [Network Detection]\n");
            
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface networkInterface = interfaces.nextElement();
                
                // Skip loopback and inactive interfaces
                if (networkInterface.isLoopback() || !networkInterface.isUp()) {
                    continue;
                }
                
                String interfaceName = networkInterface.getDisplayName().toLowerCase();
                
                // FIRST: Determine interface type by NAME (most reliable)
                String interfaceType = "OTHER";
                if (interfaceName.contains("seth") || interfaceName.contains("rmnet") || 
                    interfaceName.contains("ccmni") || interfaceName.contains("ndc")) {
                    // Cellular interfaces: seth_lte*, seth_5g*, rmnet*, ccmni*, ndc*
                    interfaceType = "CELLULAR";
                } else if (interfaceName.contains("tun") || interfaceName.contains("tap") || 
                           interfaceName.contains("wg") || interfaceName.contains("tailscale") ||
                           interfaceName.contains("vpn") || interfaceName.contains("ppp")) {
                    // VPN interfaces
                    interfaceType = "VPN";
                } else if (interfaceName.contains("wlan") || interfaceName.contains("ap0") ||
                           interfaceName.contains("softap") || interfaceName.contains("eth")) {
                    // WiFi/LAN/Hotspot interfaces: wlan*, ap0, softap*, eth*
                    interfaceType = "WIFI_LAN_HOTSPOT";
                }
                
                java.util.Enumeration<java.net.InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress address = addresses.nextElement();
                    
                    // Only consider IPv4 non-loopback addresses
                    if (!address.isLoopbackAddress() && address instanceof java.net.Inet4Address) {
                        String ip = address.getHostAddress();
                        String category = "OTHER";
                        
                        // SECOND: Refine by IP range based on interface type
                        if (interfaceType.equals("CELLULAR")) {
                            // Any IP on cellular interface is cellular (can be 10.x, 100.x, etc.)
                            category = "CELLULAR";
                            if (cellularIp == null) cellularIp = ip;
                        } else if (interfaceType.equals("VPN")) {
                            category = "VPN";
                            if (vpnIp == null) vpnIp = ip;
                        } else if (interfaceType.equals("WIFI_LAN_HOTSPOT")) {
                            // WiFi/LAN can be hotspot (192.168.x.x) or regular WiFi (10.x, 172.x)
                            if (ip.startsWith("192.168.")) {
                                category = "HOTSPOT [PREFERRED]";
                                if (hotspotIp == null) hotspotIp = ip;
                            } else if (ip.startsWith("10.") || ip.startsWith("172.")) {
                                category = "WiFi/LAN";
                                if (wifiLanIp == null) wifiLanIp = ip;
                            } else {
                                if (otherIp == null) otherIp = ip;
                            }
                        } else {
                            // Unknown interface with IP
                            if (ip.startsWith("192.168.")) {
                                category = "HOTSPOT [PREFERRED]";
                                if (hotspotIp == null) hotspotIp = ip;
                            } else if (ip.startsWith("10.") || ip.startsWith("172.")) {
                                category = "WiFi/LAN";
                                if (wifiLanIp == null) wifiLanIp = ip;
                            } else if (ip.startsWith("100.")) {
                                category = "CELLULAR";
                                if (cellularIp == null) cellularIp = ip;
                            } else {
                                if (otherIp == null) otherIp = ip;
                            }
                        }
                        
                        networkLog.append("   • ").append(networkInterface.getDisplayName()).append(": ").append(ip).append(" [").append(category).append("]\n");
                    }
                }
            }
            
            // Prioritization by IP range ONLY
            String selectedIp = hotspotIp != null ? hotspotIp :
                               wifiLanIp != null ? wifiLanIp :
                               cellularIp != null ? cellularIp :
                               vpnIp != null ? vpnIp : otherIp;
            
            networkLog.append("   ✅ Selected: ").append(selectedIp != null ? selectedIp : "N/A");
            if (hotspotIp != null) {
                networkLog.append(" [HOTSPOT - Recommended]");
            } else if (wifiLanIp != null) {
                networkLog.append(" [WiFi/LAN]");
            } else if (cellularIp != null) {
                networkLog.append(" [CELLULAR - No incoming connections]");
            }
            
            Log.i(TAG, networkLog.toString());
            
            return selectedIp != null ? selectedIp : "N/A";
            
        } catch (Exception e) {
            Log.e(TAG, "❌ [Network] Error detecting IP address", e);
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
            .setShowWhen(false) // Don't show "now" timestamp, it's a persistent notification
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }
    
    /**
     * Update notification with stream URL.
     * Shows cloud dashboard URL if in cloud mode, local dashboard URL if in local mode.
     */
    private void updateNotification() {
        if (activePort == -1) {
            return;
        }
        
        // Check if cloud mode is enabled
        android.content.SharedPreferences cloudPrefs = getSharedPreferences("FadCamCloudPrefs", Context.MODE_PRIVATE);
        int streamingMode = cloudPrefs.getInt("streaming_mode", 0); // 0 = local, 1 = cloud
        boolean isCloudMode = streamingMode == 1;
        
        String dashboardUrl;
        if (isCloudMode) {
            // Cloud mode - show cloud dashboard URL with device ID
            String deviceId = android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
            dashboardUrl = "https://fadcam.fadseclab.com/stream/" + deviceId + "/";
        } else {
            // Local mode - show local dashboard URL (root, not /live.m3u8)
            String ipAddress = getLocalIpAddress();
            dashboardUrl = "http://" + ipAddress + ":" + activePort + "/";
        }
        
        // Check if we have fragments
        int fragmentCount = RemoteStreamManager.getInstance().getBufferedCount();
        String contentText;
        if (fragmentCount > 0) {
            contentText = "Streaming: " + dashboardUrl + " (" + fragmentCount + " fragments)";
        } else {
            contentText = dashboardUrl + " • Start recording to stream";
        }
        
        Notification notification = buildNotification(contentText, dashboardUrl);
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
     * Get current dashboard URL for display in RemoteFragment.
     * Shows cloud URL if in cloud mode, local URL if in local mode.
     */
    public String getStreamUrl() {
        if (activePort == -1) {
            return null;
        }
        
        // Check if cloud mode is enabled
        android.content.SharedPreferences cloudPrefs = getSharedPreferences("FadCamCloudPrefs", Context.MODE_PRIVATE);
        int streamingMode = cloudPrefs.getInt("streaming_mode", 0); // 0 = local, 1 = cloud
        boolean isCloudMode = streamingMode == 1;
        
        if (isCloudMode) {
            // Cloud mode - return cloud dashboard URL with device ID
            String deviceId = android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
            return "https://fadcam.fadseclab.com/stream/" + deviceId + "/";
        } else {
            // Local mode - return local dashboard URL (root)
            String ipAddress = getLocalIpAddress();
            return "http://" + ipAddress + ":" + activePort + "/";
        }
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
