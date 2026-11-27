package com.fadcam.assets;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.fadcam.FLog;
import com.fadcam.MainActivity;
import com.fadcam.R;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;

/**
 * Downloads assets and installs them, surviving the user leaving the app.
 *
 * <p>Modelled on the existing {@link com.fadcam.service.FileOperationService} rather
 * than introducing a new pattern. A directly-managed {@code dataSync} foreground
 * service is used instead of WorkManager because from Android 16 background jobs
 * started from a foreground service are subject to job quotas, and long-running
 * workers can exhaust them outright. A download the user just asked for must not be
 * silently queued behind a quota.
 *
 * <p>Downloads run one at a time. A second request is queued rather than run in
 * parallel, so a slow connection cannot starve the notification of meaning and the
 * user sees a predictable "1 of 2" progression.
 *
 * <p>Full builds only.
 */
public class AssetDownloadService extends Service {

    private static final String TAG = "AssetDownloadService";
    private static final String CHANNEL_ID = "asset_downloads";
    private static final int FOREGROUND_ID = 1002;

    private static final String ACTION_DOWNLOAD = "com.fadcam.assets.DOWNLOAD";
    private static final String ACTION_CANCEL = "com.fadcam.assets.CANCEL";
    private static final String EXTRA_ASSET = "asset";

    /** Receives state changes and progress. */
    public interface Listener {
        void onAssetStateChanged(@NonNull Asset asset);

        /** Live download percentage for this asset, so a visible row can show it. */
        default void onAssetProgress(@NonNull Asset asset, int percent) {
            // Optional: most listeners only care that the state changed.
        }
    }

    private static volatile Listener listener;

    private ExecutorService executor;
    private OkHttpClient client;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<Asset> queue = new ArrayList<>();
    private volatile Asset current;

    /** Sets a process-wide listener; pass null to clear. */
    public static void setListener(@Nullable Listener l) {
        listener = l;
    }

    /** Starts a download of this asset. Safe to call repeatedly; duplicates are ignored. */
    public static void start(@NonNull Context context, @NonNull Asset asset) {
        Intent intent = new Intent(context, AssetDownloadService.class);
        intent.setAction(ACTION_DOWNLOAD);
        intent.putExtra(EXTRA_ASSET, asset.id);
        context.startForegroundService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadExecutor();
        client = new OkHttpClient.Builder()
                // A stalled socket must not hold the service forever.
                .callTimeout(java.time.Duration.ofMinutes(5))
                .build();
        createChannel();
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            FLog.i(TAG, "cancel requested");
            queue.clear();
            stopSelf();
            return START_NOT_STICKY;
        }

        // A sticky restart arrives with a null Intent, so the queue is rebuilt from
        // whatever is not installed rather than from the original request.
        if (intent == null) {
            FLog.i(TAG, "restarted by the system; nothing to resume without a request");
            stopSelf();
            return START_NOT_STICKY;
        }

        String id = intent.getStringExtra(EXTRA_ASSET);
        Asset asset = Asset.fromId(id);
        if (asset == null) {
            FLog.e(TAG, "unknown asset id: " + id);
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(FOREGROUND_ID, buildNotification(
                getString(R.string.asset_download_preparing), null, 0, 0));

        synchronized (queue) {
            if (!queue.contains(asset)) {
                queue.add(asset);
            }
        }
        pump();
        return START_NOT_STICKY;
    }

    /** Runs the next queued download, if any. */
    private void pump() {
        synchronized (queue) {
            if (queue.isEmpty()) {
                stopSelf();
                return;
            }
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        final Asset asset;
        synchronized (queue) {
            asset = queue.remove(0);
        }
        current = asset;
        executor.execute(() -> {
            try {
                downloadAndInstall(asset);
            } catch (AssetException e) {
                FLog.e(TAG, "install failed for " + asset.id + ": " + e.getMessage());
                notifyFailure(asset, e.getMessage());
            } catch (Exception e) {
                FLog.e(TAG, "download failed for " + asset.id, e);
                notifyFailure(asset, e.getMessage());
            } finally {
                current = null;
                running.set(false);
                AssetRegistry.get(this).onDownloadFinished(asset);
                notifyListener(asset);
                pump();
            }
        });
    }

    private void downloadAndInstall(@NonNull Asset asset) throws Exception {
        AssetRegistry registry = AssetRegistry.get(this);
        AssetStore store = registry.store();
        List<AssetManifest.Entry> entries = registry.entriesFor(asset);
        if (entries == null || entries.isEmpty()) {
            throw new AssetException(asset, "no manifest entry for this device");
        }
        AssetManifest manifest = registry.manifest();
        if (manifest == null) {
            throw new AssetException(asset, "manifest missing from the app");
        }

        lastPercent = -1;
        registry.onDownloadStarted(asset);
        notifyListener(asset);

        // Download every file first, then install the whole set in one pass. Installing per
        // entry would clear the asset directory between them and delete the files just
        // written, which is what broke the two-graph model asset.
        List<File> completed = new ArrayList<>();
        for (AssetManifest.Entry entry : entries) {
            String url = manifest.baseUrl() + entry.file;
            File partial = store.partialFileFor(entry);
            FLog.i(TAG, "fetching " + url + " -> " + partial.getName());

            new AssetDownloader(client).download(url, partial, (done, total) -> {
                updateProgress(asset, done, total);
            });
            completed.add(partial);
        }

        updateNotification(getString(R.string.asset_download_verifying), asset, 0, 0);
        AssetInstaller.installAll(asset, store, entries, completed);

        notifyListener(asset);
        FLog.i(TAG, "asset ready: " + asset.id);
    }

    /** Last percentage pushed to listeners, so the UI is not rebuilt for every packet. */
    private volatile int lastPercent = -1;

    private void updateProgress(@NonNull Asset asset, long done, long total) {
        int pct = total > 0 ? (int) Math.min(100L, (done * 100L) / total) : 0;
        updateNotification(getString(R.string.asset_download_in_progress), asset, pct, total);
        // Tell the visible screen the number changed, at whole-percent granularity only.
        if (pct != lastPercent) {
            lastPercent = pct;
            Listener l = listener;
            if (l != null) {
                l.onAssetProgress(asset, pct);
            }
        }
    }

    // ---------------------------------------------------------- notification

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.asset_download_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.asset_download_channel_desc));
            channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void updateNotification(@NonNull String title, @Nullable Asset asset,
                                    int percent, long total) {
        try {
            NotificationManagerCompat.from(this).notify(FOREGROUND_ID,
                    buildNotification(title, asset, percent, total));
        } catch (SecurityException e) {
            // Notification permission can be denied on Android 13+. The download still
            // runs and the in-app UI still shows progress, so this is not fatal.
            FLog.w(TAG, "no notification permission: " + e.getMessage());
        }
    }

    private Notification buildNotification(@NonNull String title, @Nullable Asset asset,
                                           int percent, long total) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent cancel = new Intent(this, AssetDownloadService.class);
        cancel.setAction(ACTION_CANCEL);
        PendingIntent cancelIntent = PendingIntent.getService(this, 1, cancel,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_notification)
                .setContentTitle(title)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setAutoCancel(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(0, getString(R.string.asset_download_cancel), cancelIntent);

        if (asset != null) {
            b.setContentText(asset.title);
        }
        if (total > 0) {
            b.setProgress(100, percent, false);
            b.setSubText(percent + "%");
        } else if (asset != null) {
            b.setProgress(0, 0, true);
        }
        return b.build();
    }

    private void notifyFailure(@NonNull Asset asset, @Nullable String reason) {
        try {
            NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_error)
                    .setContentTitle(getString(R.string.asset_download_failed))
                    .setContentText(asset.title + (reason != null ? ": " + reason : ""))
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT);
            NotificationManagerCompat.from(this).notify(FOREGROUND_ID + 1, b.build());
        } catch (SecurityException e) {
            FLog.w(TAG, "no notification permission: " + e.getMessage());
        }
    }

    private void notifyListener(@NonNull Asset asset) {
        Listener l = listener;
        if (l != null) {
            l.onAssetStateChanged(asset);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (executor != null) {
            executor.shutdownNow();
        }
        if (current != null) {
            AssetRegistry.get(this).onDownloadFinished(current);
        }
        super.onDestroy();
    }

    /**
     * Android 15+ gives a {@code dataSync} service a 6 hour budget per 24 hours and
     * calls this if it overruns. A 12 MB fetch cannot reach that, but the callback must
     * be implemented so the system never reports "did not stop within its timeout".
     */
    @Override
    public void onTimeout(int startId, int fgsType) {
        FLog.w(TAG, "foreground service timed out; stopping");
        stopSelf();
    }
}
