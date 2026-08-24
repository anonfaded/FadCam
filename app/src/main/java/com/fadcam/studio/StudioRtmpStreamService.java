package com.fadcam.studio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;
import com.fadcam.Constants;
import com.fadcam.FLog;
import com.fadcam.R;
import com.fadcam.streaming.RemoteStreamService;

/**
 * Bridges FadCam's real local HLS program output to a platform RTMP/RTMPS
 * destination. The video is not a second camera pipeline: it is the same
 * encoded program stream produced by FadCam's recording/GL pipeline.
 */
public class StudioRtmpStreamService extends Service {
    public static final String ACTION_START = "com.fadcam.studio.RTMP_START";
    public static final String ACTION_STOP = "com.fadcam.studio.RTMP_STOP";
    public static final String EXTRA_SERVER = "server";
    public static final String EXTRA_PLATFORM = "platform";

    private static final String TAG = "StudioRtmpService";
    private static final int NOTIFICATION_ID = 2402;
    private static final String CHANNEL_ID = "studio_rtmp_channel";
    private static final int MAX_WAIT_MS = 12000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private FFmpegSession ffmpegSession;
    private boolean startedRemoteHls;
    private int waitElapsedMs;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopRtmp();
            return START_NOT_STICKY;
        }
        if (intent == null || !ACTION_START.equals(intent.getAction())) {
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, notification("Preparing RTMP output…"));
        String server = intent.getStringExtra(EXTRA_SERVER);
        String platform = intent.getStringExtra(EXTRA_PLATFORM);
        if (server == null || server.trim().isEmpty()) {
            fail("RTMP server URL is empty");
            return START_NOT_STICKY;
        }

        try {
            String streamKey = StudioRtmpSecretStore.load(this);
            if (streamKey == null || streamKey.trim().isEmpty()) {
                fail("RTMP stream key is not configured");
                return START_NOT_STICKY;
            }

            getSharedPreferences("FadCamPrefs", MODE_PRIVATE).edit()
                    .putString(StudioRtmpConfig.PREF_SERVER, server.trim())
                    .putString(StudioRtmpConfig.PREF_PLATFORM, platform == null ? StudioRtmpConfig.PLATFORM_CUSTOM : platform)
                    .putBoolean(StudioRtmpConfig.PREF_ACTIVE, true)
                    .apply();

            boolean recording = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
                    .getBoolean(Constants.PREF_IS_RECORDING_IN_PROGRESS, false);
            if (!recording) {
                fail("Start RECORD before starting an RTMP output");
                return START_NOT_STICKY;
            }

            startLocalHlsAndWait();
        } catch (Exception e) {
            FLog.e(TAG, "Unable to prepare RTMP output", e);
            fail("RTMP setup failed");
        }
        return START_STICKY;
    }

    private void startLocalHlsAndWait() {
        startedRemoteHls = true;
        try {
            ContextCompat.startForegroundService(this, new Intent(this, RemoteStreamService.class));
        } catch (Exception e) {
            fail("Could not start local HLS engine");
            return;
        }
        waitElapsedMs = 0;
        handler.post(waitForHlsRunnable);
    }

    private final Runnable waitForHlsRunnable = new Runnable() {
        @Override
        public void run() {
            int port = getSharedPreferences("FadCamPrefs", MODE_PRIVATE)
                    .getInt("stream_server_port", -1);
            if (port > 0) {
                launchFfmpeg(port);
                return;
            }
            waitElapsedMs += 250;
            if (waitElapsedMs >= MAX_WAIT_MS) {
                fail("HLS engine did not become ready");
                return;
            }
            handler.postDelayed(this, 250);
        }
    };

    private void launchFfmpeg(int port) {
        String server = getSharedPreferences("FadCamPrefs", MODE_PRIVATE)
                .getString(StudioRtmpConfig.PREF_SERVER, "");
        try {
            String key = StudioRtmpSecretStore.load(this);
            String output = server.endsWith("/") ? server + key : server + "/" + key;
            String input = "http://127.0.0.1:" + port + "/live.m3u8";

            // FadCam's GL pipeline produces the program video; keep that encoded
            // video stream and encode audio as AAC for broad RTMP compatibility.
            String command = "-hide_banner -loglevel warning "
                    + "-reconnect 1 -reconnect_streamed 1 -reconnect_delay_max 2 "
                    + "-i " + quote(input) + " "
                    + "-c:v copy -c:a aac -b:a 128k -ar 44100 "
                    + "-f flv " + quote(output);

            updateNotification("Connecting to RTMP destination…");
            FLog.i(TAG, "Starting RTMP output for platform="
                    + getSharedPreferences("FadCamPrefs", MODE_PRIVATE)
                    .getString(StudioRtmpConfig.PREF_PLATFORM, StudioRtmpConfig.PLATFORM_CUSTOM));

            ffmpegSession = FFmpegKit.executeAsync(command, session -> {
                if (ReturnCode.isSuccess(session.getReturnCode())) {
                    FLog.i(TAG, "RTMP session completed normally");
                } else if (!ReturnCode.isCancel(session.getReturnCode())) {
                    FLog.e(TAG, "RTMP session failed: " + safeFailure(session));
                }
                if (!isFinishingService()) {
                    stopRtmp();
                }
            });
            updateNotification("RTMP output LIVE");
        } catch (Exception e) {
            FLog.e(TAG, "RTMP encoder start failed", e);
            fail("RTMP encoder failed to start");
        }
    }

    private String safeFailure(FFmpegSession session) {
        String stack = session.getFailStackTrace();
        if (stack != null && !stack.isEmpty()) return stack.replaceAll("(?i)(key=)[^&\\s]+", "$1[redacted]");
        return "returnCode=" + session.getReturnCode();
    }

    private String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private void fail(String message) {
        FLog.e(TAG, message);
        updateNotification(message);
        getSharedPreferences("FadCamPrefs", MODE_PRIVATE).edit()
                .putBoolean(StudioRtmpConfig.PREF_ACTIVE, false).apply();
        handler.postDelayed(this::stopRtmp, 1200);
    }

    private void stopRtmp() {
        handler.removeCallbacks(waitForHlsRunnable);
        if (ffmpegSession != null) {
            try {
                FFmpegKit.cancel(ffmpegSession.getSessionId());
            } catch (Exception ignored) {
            }
            ffmpegSession = null;
        }
        getSharedPreferences("FadCamPrefs", MODE_PRIVATE).edit()
                .putBoolean(StudioRtmpConfig.PREF_ACTIVE, false).apply();
        if (startedRemoteHls) {
            try {
                stopService(new Intent(this, RemoteStreamService.class));
            } catch (Exception ignored) {
            }
            startedRemoteHls = false;
        }
        stopForeground(true);
        stopSelf();
    }

    private boolean isFinishingService() {
        return !getSharedPreferences("FadCamPrefs", MODE_PRIVATE)
                .getBoolean(StudioRtmpConfig.PREF_ACTIVE, false);
    }

    private Notification notification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("FadCam Studio RTMP")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, notification(text));
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(
                    new NotificationChannel(CHANNEL_ID, "Studio RTMP", NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
