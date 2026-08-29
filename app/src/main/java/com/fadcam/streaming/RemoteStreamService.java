package com.fadcam.streaming;

import com.fadcam.FLog;
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
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import com.fadcam.MainActivity;
import com.fadcam.R;
import java.io.IOException;
import java.net.ServerSocket;

/** Foreground owner for FadCam's local HTTP server and streaming runtime. */
public class RemoteStreamService extends Service {
    private static final String TAG = "RemoteStreamService";
    private static final String CHANNEL_ID = "remote_streaming_channel";
    private static final int NOTIFICATION_ID = 2001;
    private static final int DEFAULT_PORT = 8080;
    private static final int PORT_SCAN_RANGE = 10;

    private HardenedLiveM3U8Server httpServer;
    private int activePort = -1;
    private Handler notificationHandler;
    private Runnable notificationUpdateRunnable;
    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        public RemoteStreamService getService() { return RemoteStreamService.this; }
    }

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        RemoteStreamManager.getInstance().setContext(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "com.fadcam.COPY_STREAM_URL".equals(intent.getAction())) {
            String streamUrl = intent.getStringExtra("stream_url");
            if (streamUrl != null) {
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Stream URL", streamUrl));
            }
            return START_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification("Starting local server…", "http://..."));

        // The built-in Local Server is the canonical HTTP/control surface. Keep it
        // running in BOTH local and cloud modes. Cloud mode is an additional delivery
        // path; it must never disable the existing server or its Remote Control API.
        if (!startHttpServer()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        getSharedPreferences("FadCamPrefs", MODE_PRIVATE).edit().putInt("stream_server_port", activePort).apply();

        RemoteStreamManager.getInstance().setStreamingEnabled(true);
        CloudStatusManager.getInstance(this).start();
        updateNotification();
        startNotificationUpdates();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        CloudStatusManager.getInstance(this).stop();
        stopNotificationUpdates();
        stopHttpServer();
        RemoteStreamManager.getInstance().setStreamingEnabled(false);
        stopForeground(true);
        super.onDestroy();
    }

    /**
     * Reconcile the server with the selected delivery mode.
     * The local server remains available regardless of whether cloud delivery is enabled.
     */
    public void updateStreamingMode() {
        if (!isServerRunning()) {
            if (startHttpServer()) {
                getSharedPreferences("FadCamPrefs", MODE_PRIVATE).edit().putInt("stream_server_port", activePort).apply();
                updateNotification();
            }
        }
    }

    public boolean isServerRunning() { return httpServer != null && httpServer.isAlive(); }

    private boolean startHttpServer() {
        int port = findFreePort(DEFAULT_PORT);
        if (port == -1) return false;
        try {
            httpServer = new HardenedLiveM3U8Server(this, port);
            httpServer.start();
            activePort = port;
            FLog.i(TAG, "HTTP streaming/control server started on port " + port + "; remote controls require authentication");
            return true;
        } catch (IOException e) {
            FLog.e(TAG, "Failed to start HTTP server", e);
            return false;
        }
    }

    private void stopHttpServer() {
        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
        }
        activePort = -1;
    }

    private int findFreePort(int startPort) {
        for (int port = startPort; port < startPort + PORT_SCAN_RANGE; port++) {
            if (isPortAvailable(port)) return port;
        }
        return -1;
    }

    private boolean isPortAvailable(int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            socket.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private String getLocalIpAddress() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface networkInterface = interfaces.nextElement();
                if (networkInterface.isLoopback() || !networkInterface.isUp()) continue;
                java.util.Enumeration<java.net.InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress address = addresses.nextElement();
                    if (!address.isLoopbackAddress() && address instanceof java.net.Inet4Address) return address.getHostAddress();
                }
            }
        } catch (Exception e) {
            FLog.e(TAG, "Failed to detect local IP", e);
        }
        return "N/A";
    }

    private Notification buildNotification(String contentText, String streamUrl) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent copyIntent = new Intent(this, RemoteStreamService.class).setAction("com.fadcam.COPY_STREAM_URL");
        copyIntent.putExtra("stream_url", streamUrl);
        PendingIntent copyPendingIntent = PendingIntent.getService(this, 1, copyIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Remote Streaming Active")
                .setContentText(contentText)
                .setSmallIcon(R.drawable.ic_broadcast_on_personal_24)
                .setContentIntent(pendingIntent)
                .addAction(android.R.drawable.ic_menu_view, "Copy Link", copyPendingIntent)
                .setOngoing(true).setShowWhen(false).setPriority(NotificationCompat.PRIORITY_LOW).build();
    }

    private void updateNotification() {
        if (activePort == -1) return;
        String dashboardUrl = "http://" + getLocalIpAddress() + ":" + activePort + "/";
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(dashboardUrl, dashboardUrl));
    }

    private void startNotificationUpdates() {
        notificationHandler = new Handler(Looper.getMainLooper());
        notificationUpdateRunnable = () -> {
            updateNotification();
            if (notificationHandler != null) notificationHandler.postDelayed(notificationUpdateRunnable, 30000);
        };
        notificationHandler.postDelayed(notificationUpdateRunnable, 30000);
    }

    private void stopNotificationUpdates() {
        if (notificationHandler != null && notificationUpdateRunnable != null) notificationHandler.removeCallbacks(notificationUpdateRunnable);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Remote Streaming", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shows when remote streaming is active");
            channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    public String getStreamUrl() {
        return activePort == -1 ? null : "http://" + getLocalIpAddress() + ":" + activePort + "/";
    }

    public String getDeviceIpWithPort() { return activePort == -1 ? null : getLocalIpAddress() + ":" + activePort; }
    public int getActivePort() { return activePort; }
    @Nullable @Override public IBinder onBind(Intent intent) { return binder; }
}
