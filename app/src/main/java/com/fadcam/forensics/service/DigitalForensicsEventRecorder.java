package com.fadcam.forensics.service;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;

import com.fadcam.SharedPreferencesManager;
import com.fadcam.forensics.data.local.ForensicsDatabase;
import com.fadcam.forensics.data.local.dao.AiEventDao;
import com.fadcam.forensics.data.local.dao.AiEventSnapshotDao;
import com.fadcam.forensics.data.local.dao.MediaAssetDao;
import com.fadcam.forensics.data.local.entity.AiEventEntity;
import com.fadcam.forensics.data.local.entity.AiEventSnapshotEntity;
import com.fadcam.forensics.data.local.entity.MediaAssetEntity;
import com.fadcam.forensics.domain.fingerprint.ExactFingerprintGenerator;
import com.fadcam.forensics.domain.fingerprint.ForensicsMetadataUtils;
import com.fadcam.forensics.domain.fingerprint.VisualFingerprintGenerator;
import com.fadcam.motion.domain.detector.EfficientDetLite1Detector;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Real-time forensics event writer: multi-event, snapshot-first, no stop-only dependency.
 */
public class DigitalForensicsEventRecorder {

    private static final String TAG = "ForensicsEventRecorder";
    private static final long EVENT_STALE_GAP_MS = 1800L;
    private static final long SNAPSHOT_MIN_INTERVAL_MS = 1200L;

    private final SharedPreferencesManager prefs;
    private final Context appContext;
    private final MediaAssetDao mediaAssetDao;
    private final AiEventDao aiEventDao;
    private final AiEventSnapshotDao snapshotDao;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Map<String, ActiveEvent> activeEvents = new HashMap<>();
    private volatile boolean snapshotRootLogged;

    private static final class ActiveEvent {
        String eventUid;
        String mediaUid;
        String mediaUri;
        String eventType;
        String className;
        long startMs;
        long lastSeenMs;
        long firstSeenEpochMs;
        long lastSeenEpochMs;
        long lastSnapshotMs;
        float peakConfidence;
        float centerX;
        float centerY;
        float boxWidth;
        float boxHeight;
        int sampleCount;
    }

    public DigitalForensicsEventRecorder(Context context) {
        Context appCtx = context.getApplicationContext();
        this.appContext = appCtx;
        this.prefs = SharedPreferencesManager.getInstance(appCtx);
        ForensicsDatabase db = ForensicsDatabase.getInstance(appCtx);
        this.mediaAssetDao = db.mediaAssetDao();
        this.aiEventDao = db.aiEventDao();
        this.snapshotDao = db.aiEventSnapshotDao();
    }

    public void onDetections(
            String mediaUri,
            long timelineMs,
            @Nullable List<EfficientDetLite1Detector.DetectionResult> detections,
            @Nullable byte[] snapshotJpeg
    ) {
        if (!prefs.isDigitalForensicsEnabled() || !prefs.isDfEvidenceCollectionEnabled()) {
            return;
        }
        if (mediaUri == null || mediaUri.isEmpty() || detections == null || detections.isEmpty()) {
            return;
        }
        String captureScope = prefs.getDfCaptureScope();
        List<EfficientDetLite1Detector.DetectionResult> stable = new ArrayList<>();
        for (EfficientDetLite1Detector.DetectionResult detection : detections) {
            if (detection == null || detection.confidence < 0.28f) {
                continue;
            }
            String coarse = detection.coarseType == null ? "" : detection.coarseType.toUpperCase(Locale.US);
            boolean isPerson = "PERSON".equals(coarse);
            if ("people".equals(captureScope) && !isPerson) {
                continue;
            }
            if ("objects".equals(captureScope) && isPerson) {
                continue;
            }
            stable.add(detection);
        }
        if (stable.isEmpty()) {
            return;
        }
        ioExecutor.submit(() -> handleDetections(mediaUri, Math.max(0L, timelineMs), stable, snapshotJpeg));
    }

    public void onMotionStop(long timelineMs) {
        ioExecutor.submit(() -> closeStaleEvents(Math.max(0L, timelineMs), true));
    }

    public void flush(long timelineMs) {
        ioExecutor.submit(() -> closeStaleEvents(Math.max(0L, timelineMs), true));
    }

    private void handleDetections(
            String mediaUri,
            long timelineMs,
            List<EfficientDetLite1Detector.DetectionResult> detections,
            @Nullable byte[] snapshotJpeg
    ) {
        String mediaUid = ensureMediaAsset(mediaUri);
        if (mediaUid == null) {
            return;
        }

        long nowEpoch = System.currentTimeMillis();
        closeStaleEvents(timelineMs, false);

        for (EfficientDetLite1Detector.DetectionResult detection : detections) {
            String eventType = normalizeEventType(detection.coarseType);
            String className = normalizeClassName(detection.className);
            String key = buildActiveKey(mediaUid, eventType, className);
            ActiveEvent event = activeEvents.get(key);
            if (event == null) {
                event = new ActiveEvent();
                event.eventUid = UUID.randomUUID().toString();
                event.mediaUid = mediaUid;
                event.mediaUri = mediaUri;
                event.eventType = eventType;
                event.className = className;
                event.startMs = timelineMs;
                event.firstSeenEpochMs = nowEpoch;
                event.lastSnapshotMs = -1L;
                activeEvents.put(key, event);
            }

            event.lastSeenMs = timelineMs;
            event.lastSeenEpochMs = nowEpoch;
            event.sampleCount += 1;
            event.peakConfidence = Math.max(event.peakConfidence, detection.confidence);
            event.centerX = clamp01(detection.centerX);
            event.centerY = clamp01(detection.centerY);
            event.boxWidth = clampBox(detection.width);
            event.boxHeight = clampBox(detection.height);

            upsertEvent(event, false);

            if (snapshotJpeg != null && snapshotJpeg.length > 0
                    && (event.lastSnapshotMs < 0 || (timelineMs - event.lastSnapshotMs) >= SNAPSHOT_MIN_INTERVAL_MS)) {
                String imageUri = persistSnapshotFile(mediaUid, event.eventUid, timelineMs, snapshotJpeg);
                if (imageUri != null) {
                    AiEventSnapshotEntity snapshot = new AiEventSnapshotEntity();
                    snapshot.snapshotUid = UUID.randomUUID().toString();
                    snapshot.eventUid = event.eventUid;
                    snapshot.mediaUid = mediaUid;
                    snapshot.capturedEpochMs = nowEpoch;
                    snapshot.timelineMs = timelineMs;
                    snapshot.eventType = event.eventType;
                    snapshot.className = event.className;
                    snapshot.confidence = detection.confidence;
                    snapshot.bboxNorm = toBbox(event.centerX, event.centerY, event.boxWidth, event.boxHeight);
                    snapshot.imageUri = imageUri;
                    snapshot.sha256 = sha256(snapshotJpeg);
                    snapshotDao.upsert(snapshot);
                }
                event.lastSnapshotMs = timelineMs;
            }
        }
    }

    private void closeStaleEvents(long timelineMs, boolean forceAll) {
        if (activeEvents.isEmpty()) {
            return;
        }
        List<String> keysToClose = new ArrayList<>();
        for (Map.Entry<String, ActiveEvent> entry : activeEvents.entrySet()) {
            ActiveEvent event = entry.getValue();
            if (forceAll || (timelineMs - event.lastSeenMs) > EVENT_STALE_GAP_MS) {
                upsertEvent(event, true);
                keysToClose.add(entry.getKey());
            }
        }
        for (String key : keysToClose) {
            activeEvents.remove(key);
        }
    }

    private void upsertEvent(ActiveEvent active, boolean close) {
        AiEventEntity event = new AiEventEntity();
        event.eventUid = active.eventUid;
        event.mediaUid = active.mediaUid;
        event.eventType = active.eventType;
        event.className = active.className;
        event.startMs = active.startMs;
        event.endMs = Math.max(active.startMs, active.lastSeenMs);
        event.confidence = active.peakConfidence;
        event.bboxNorm = toBbox(active.centerX, active.centerY, active.boxWidth, active.boxHeight);
        event.trackId = null;
        event.priority = toPriority(active.peakConfidence);
        event.thumbnailRef = active.mediaUri + "#t=" + (event.startMs / 1000f);
        event.detectedAtEpochMs = active.lastSeenEpochMs;
        event.status = close ? "CLOSED" : "OPEN";
        event.firstSeenEpochMs = active.firstSeenEpochMs;
        event.lastSeenEpochMs = active.lastSeenEpochMs;
        event.sampleCount = active.sampleCount;
        event.peakConfidence = active.peakConfidence;
        event.mediaMissing = false;
        event.alertState = "PENDING";
        event.alertChannel = null;
        aiEventDao.upsert(event);
    }

    private int toPriority(float confidence) {
        if (confidence >= 0.85f) return 3;
        if (confidence >= 0.65f) return 2;
        if (confidence >= 0.45f) return 1;
        return 0;
    }

    private String toBbox(float centerX, float centerY, float boxW, float boxH) {
        return clamp01(centerX) + "," + clamp01(centerY) + "," + clampBox(boxW) + "," + clampBox(boxH);
    }

    @Nullable
    private String persistSnapshotFile(String mediaUid, String eventUid, long timelineMs, byte[] jpeg) {
        try {
            File externalRoot = appContext.getExternalFilesDir(null);
            if (externalRoot == null) {
                Log.e(TAG, "Snapshot persistence failed: external files dir unavailable");
                return null;
            }
            File root = new File(externalRoot, "FadCam/Forensics/Snapshots");
            String day = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
            File dir = new File(root, day + "/" + mediaUid);
            if (!dir.exists() && !dir.mkdirs()) {
                Log.e(TAG, "Snapshot persistence failed: cannot create dir " + dir.getAbsolutePath());
                return null;
            }
            File out = new File(dir, eventUid + "_" + timelineMs + ".jpg");
            try (FileOutputStream fos = new FileOutputStream(out, false)) {
                fos.write(jpeg);
                fos.flush();
            }
            if (!snapshotRootLogged) {
                snapshotRootLogged = true;
                Log.i(TAG, "Forensics snapshots root: " + root.getAbsolutePath());
            }
            return Uri.fromFile(out).toString();
        } catch (Exception e) {
            Log.w(TAG, "Snapshot persistence failed", e);
            return null;
        }
    }

    private String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format(Locale.US, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String buildActiveKey(String mediaUid, String eventType, String className) {
        return mediaUid + "|" + eventType + "|" + className;
    }

    private String ensureMediaAsset(String mediaUri) {
        long nowEpoch = System.currentTimeMillis();
        try {
            MediaAssetEntity existing = mediaAssetDao.findByCurrentUri(mediaUri);
            if (existing != null) {
                if (existing.displayName == null || existing.displayName.isEmpty() || existing.displayName.startsWith("content://")) {
                    existing.displayName = deriveDisplayName(mediaUri);
                }
                existing.lastSeenAt = nowEpoch;
                mediaAssetDao.upsert(existing);
                return existing.mediaUid;
            }

            MediaAssetEntity asset = new MediaAssetEntity();
            asset.mediaUid = UUID.randomUUID().toString();
            asset.currentUri = mediaUri;
            asset.displayName = deriveDisplayName(mediaUri);
            asset.categorySubtype = "UNKNOWN/UNKNOWN";
            asset.sizeBytes = 0L;
            asset.durationMs = 0L;
            asset.codecInfo = null;
            asset.exactFingerprint = null;
            asset.visualFingerprint = null;
            asset.firstSeenAt = nowEpoch;
            asset.lastSeenAt = nowEpoch;
            asset.linkStatus = "NEW";
            enrichAssetMetadata(asset, mediaUri);
            mediaAssetDao.upsert(asset);
            return asset.mediaUid;
        } catch (Exception e) {
            Log.w(TAG, "ensureMediaAsset failed for uri=" + mediaUri, e);
            return null;
        }
    }

    private String normalizeEventType(String raw) {
        if (raw == null) {
            return "OBJECT";
        }
        String normalized = raw.trim().toUpperCase(Locale.US);
        switch (normalized) {
            case "PERSON":
            case "VEHICLE":
            case "PET":
            case "OBJECT":
                return normalized;
            default:
                return "OBJECT";
        }
    }

    private String normalizeClassName(String raw) {
        if (raw == null) {
            return "object";
        }
        String normalized = raw.trim().toLowerCase(Locale.US);
        return normalized.isEmpty() ? "object" : normalized;
    }

    private float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private float clampBox(float value) {
        return Math.max(0.02f, Math.min(0.90f, value));
    }

    private void enrichAssetMetadata(MediaAssetEntity asset, String mediaUri) {
        try {
            Uri uri = Uri.parse(mediaUri);
            if (asset.durationMs <= 0L) {
                asset.durationMs = ForensicsMetadataUtils.extractDurationMs(appContext, uri);
            }
            if (asset.codecInfo == null || asset.codecInfo.isEmpty()) {
                asset.codecInfo = ForensicsMetadataUtils.extractCodecInfo(appContext, uri);
            }
            if (asset.sizeBytes <= 0L) {
                long size = extractSizeBytes(uri);
                if (size > 0L) {
                    asset.sizeBytes = size;
                }
            }
            if (asset.exactFingerprint == null || asset.exactFingerprint.isEmpty()) {
                asset.exactFingerprint = ExactFingerprintGenerator.compute(appContext.getContentResolver(), uri, asset.sizeBytes);
            }
            if (asset.visualFingerprint == null || asset.visualFingerprint.isEmpty()) {
                asset.visualFingerprint = VisualFingerprintGenerator.compute(appContext, uri);
            }
        } catch (Exception e) {
            Log.w(TAG, "Metadata enrichment skipped for uri=" + mediaUri, e);
        }
    }

    private String deriveDisplayName(String mediaUri) {
        try {
            String decoded = Uri.decode(mediaUri);
            int slash = decoded.lastIndexOf('/');
            String tail = slash >= 0 ? decoded.substring(slash + 1) : decoded;
            int colon = tail.lastIndexOf(':');
            if (colon >= 0 && colon < tail.length() - 1) {
                tail = tail.substring(colon + 1);
            }
            return (tail == null || tail.isEmpty()) ? mediaUri : tail;
        } catch (Exception ignored) {
            return mediaUri;
        }
    }

    private long extractSizeBytes(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = appContext.getContentResolver().query(uri, new String[]{android.provider.OpenableColumns.SIZE},
                    null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE);
                if (index >= 0) {
                    return cursor.getLong(index);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return 0L;
    }
}
