package com.fadcam.forensics.service;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import com.fadcam.SharedPreferencesManager;
import com.fadcam.forensics.data.local.ForensicsDatabase;
import com.fadcam.forensics.data.local.dao.AiEventDao;
import com.fadcam.forensics.data.local.dao.MediaAssetDao;
import com.fadcam.forensics.data.local.entity.AiEventEntity;
import com.fadcam.forensics.data.local.entity.MediaAssetEntity;
import com.fadcam.forensics.domain.fingerprint.ExactFingerprintGenerator;
import com.fadcam.forensics.domain.fingerprint.ForensicsMetadataUtils;
import com.fadcam.forensics.domain.fingerprint.VisualFingerprintGenerator;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Writes motion-driven forensic events without touching recording control flow.
 */
public class DigitalForensicsEventRecorder {

    private static final String TAG = "ForensicsEventRecorder";

    private final SharedPreferencesManager prefs;
    private final Context appContext;
    private final MediaAssetDao mediaAssetDao;
    private final AiEventDao aiEventDao;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    private final Object lock = new Object();
    private String activeMediaUid;
    private String activeUri;
    private String activeEventType = "OBJECT";
    private String activeClassName = "object";
    private long activeStartMs = -1L;
    private float activeMaxConfidence = 0f;
    private float activeMaxScore = 0f;
    private float activeChangedArea = 0f;
    private float activeStrongArea = 0f;
    private float activeCenterX = 0.5f;
    private float activeCenterY = 0.5f;
    private float activeBoxWidth = 0.10f;
    private float activeBoxHeight = 0.10f;

    public DigitalForensicsEventRecorder(Context context) {
        Context appContext = context.getApplicationContext();
        this.appContext = appContext;
        this.prefs = SharedPreferencesManager.getInstance(appContext);
        ForensicsDatabase db = ForensicsDatabase.getInstance(appContext);
        this.mediaAssetDao = db.mediaAssetDao();
        this.aiEventDao = db.aiEventDao();
    }

    public void onMotionStart(String mediaUri, long timelineMs, String eventType, String className, float confidence,
                              float motionScore, float changedArea, float strongArea,
                              float centerX, float centerY, float boxWidth, float boxHeight) {
        if (!shouldRecord()) {
            Log.d(TAG, "DF skip start: digital forensics disabled or all event classes off");
            return;
        }
        if (mediaUri == null || mediaUri.isEmpty()) {
            Log.w(TAG, "DF skip start: mediaUri is empty");
            return;
        }
        ioExecutor.submit(() -> handleStart(mediaUri, timelineMs, eventType, className, confidence, motionScore, changedArea, strongArea, centerX, centerY, boxWidth, boxHeight));
    }

    public void onMotionStop(long timelineMs) {
        if (!prefs.isDigitalForensicsEnabled()) {
            return;
        }
        ioExecutor.submit(() -> handleStop(timelineMs));
    }

    public void flush(long timelineMs) {
        ioExecutor.submit(() -> handleStop(timelineMs));
    }

    private boolean shouldRecord() {
        return prefs.isDigitalForensicsEnabled();
    }

    private void handleStart(String mediaUri, long timelineMs, String eventTypeRaw, String classNameRaw, float confidence, float motionScore,
                             float changedArea, float strongArea, float centerX, float centerY,
                             float boxWidth, float boxHeight) {
        synchronized (lock) {
            final String eventType = normalizeEventType(eventTypeRaw);
            final String className = normalizeClassName(classNameRaw);
            if (!isEventTypeEnabled(eventType)) {
                return;
            }
            if (activeMediaUid != null) {
                if (mediaUri.equals(activeUri)) {
                    activeMaxConfidence = Math.max(activeMaxConfidence, confidence);
                    activeMaxScore = Math.max(activeMaxScore, motionScore);
                    activeChangedArea = Math.max(activeChangedArea, Math.max(0f, changedArea));
                    activeStrongArea = Math.max(activeStrongArea, Math.max(0f, strongArea));
                    activeCenterX = clamp01(centerX);
                    activeCenterY = clamp01(centerY);
                    activeBoxWidth = clampBox(boxWidth);
                    activeBoxHeight = clampBox(boxHeight);
                    if (eventPriority(eventType) >= eventPriority(activeEventType)) {
                        if (!eventType.equals(activeEventType)) {
                            Log.d(TAG, "DF promote: active event upgraded to " + eventType + ", conf=" + confidence);
                        }
                        activeEventType = eventType;
                        activeClassName = className;
                    } else if (confidence >= activeMaxConfidence) {
                        activeClassName = className;
                    }
                    return;
                }
                // Segment/media switched while event was active.
                persistEventLocked(Math.max(activeStartMs, timelineMs));
            }

            String mediaUid = ensureMediaAsset(mediaUri);
            if (mediaUid == null) {
                return;
            }
            activeMediaUid = mediaUid;
            activeUri = mediaUri;
            activeEventType = eventType;
            activeClassName = className;
            activeStartMs = Math.max(0L, timelineMs);
            activeMaxConfidence = confidence;
            activeMaxScore = motionScore;
            activeChangedArea = Math.max(0f, changedArea);
            activeStrongArea = Math.max(0f, strongArea);
            activeCenterX = clamp01(centerX);
            activeCenterY = clamp01(centerY);
            activeBoxWidth = clampBox(boxWidth);
            activeBoxHeight = clampBox(boxHeight);
        }
    }

    private void handleStop(long timelineMs) {
        synchronized (lock) {
            if (activeMediaUid == null) {
                return;
            }
            persistEventLocked(timelineMs);
        }
    }

    private void persistEventLocked(long endMsRaw) {
        long endMs = Math.max(activeStartMs, endMsRaw);
        enrichActiveAssetIfNeeded(activeMediaUid, activeUri);
        AiEventEntity event = new AiEventEntity();
        event.eventUid = UUID.randomUUID().toString();
        event.mediaUid = activeMediaUid;
        event.eventType = activeEventType != null ? activeEventType : "OBJECT";
        event.className = activeClassName != null ? activeClassName : "object";
        event.startMs = activeStartMs;
        event.endMs = endMs;
        event.confidence = activeMaxConfidence;
        event.bboxNorm = toBbox(activeCenterX, activeCenterY, activeBoxWidth, activeBoxHeight, activeChangedArea, activeStrongArea);
        event.trackId = null;
        event.priority = toPriority(activeMaxConfidence, activeMaxScore);
        event.thumbnailRef = activeUri + "#t=" + (activeStartMs / 1000f);
        event.detectedAtEpochMs = System.currentTimeMillis();
        aiEventDao.upsert(event);
        Log.d(TAG, "DF persisted event: eventUid=" + event.eventUid
                + ", type=" + event.eventType
                + ", startMs=" + event.startMs
                + ", endMs=" + event.endMs
                + ", confidence=" + event.confidence
                + ", mediaUid=" + event.mediaUid);

        activeMediaUid = null;
        activeUri = null;
        activeEventType = "OBJECT";
        activeClassName = "object";
        activeStartMs = -1L;
        activeMaxConfidence = 0f;
        activeMaxScore = 0f;
        activeChangedArea = 0f;
        activeStrongArea = 0f;
        activeCenterX = 0.5f;
        activeCenterY = 0.5f;
        activeBoxWidth = 0.10f;
        activeBoxHeight = 0.10f;
    }

    private int toPriority(float confidence, float score) {
        float composite = Math.max(confidence, score);
        if (composite >= 0.85f) {
            return 3;
        }
        if (composite >= 0.65f) {
            return 2;
        }
        if (composite >= 0.45f) {
            return 1;
        }
        return 0;
    }

    private String toBbox(float centerX, float centerY, float boxW, float boxH, float changedArea, float strongArea) {
        float area = Math.max(changedArea, strongArea);
        float fallbackSide = (float) Math.sqrt(Math.min(0.18f, Math.max(0.02f, area * 0.35f)));
        float width = boxW > 0.001f ? boxW : fallbackSide;
        float height = boxH > 0.001f ? boxH : fallbackSide;
        float cx = clamp01(centerX);
        float cy = clamp01(centerY);
        return cx + "," + cy + "," + clampBox(width) + "," + clampBox(height);
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

    private float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private float clampBox(float value) {
        return Math.max(0.02f, Math.min(0.90f, value));
    }

    private String normalizeEventType(String raw) {
        if (raw == null) {
            return "OBJECT";
        }
        String normalized = raw.trim().toUpperCase();
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
        String normalized = raw.trim().toLowerCase();
        return normalized.isEmpty() ? "object" : normalized;
    }

    private boolean isEventTypeEnabled(String eventType) {
        return "PERSON".equals(eventType)
                || "VEHICLE".equals(eventType)
                || "PET".equals(eventType)
                || "OBJECT".equals(eventType);
    }

    private int eventPriority(String eventType) {
        switch (eventType) {
            case "PERSON":
                return 4;
            case "VEHICLE":
                return 3;
            case "PET":
                return 2;
            default:
                return 1;
        }
    }

    private void enrichActiveAssetIfNeeded(String mediaUid, String mediaUri) {
        if (mediaUid == null || mediaUid.isEmpty() || mediaUri == null || mediaUri.isEmpty()) {
            return;
        }
        try {
            MediaAssetEntity asset = mediaAssetDao.findByMediaUid(mediaUid);
            if (asset == null) {
                return;
            }
            boolean needsFingerprint = asset.exactFingerprint == null || asset.exactFingerprint.isEmpty()
                    || asset.visualFingerprint == null || asset.visualFingerprint.isEmpty();
            boolean needsDisplayName = asset.displayName == null || asset.displayName.isEmpty() || asset.displayName.startsWith("content://");
            if (!needsFingerprint && !needsDisplayName) {
                return;
            }
            if (needsDisplayName) {
                asset.displayName = deriveDisplayName(mediaUri);
            }
            enrichAssetMetadata(asset, mediaUri);
            mediaAssetDao.upsert(asset);
        } catch (Exception e) {
            Log.w(TAG, "Failed to enrich active media asset for uid=" + mediaUid, e);
        }
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
            cursor = appContext.getContentResolver().query(uri, new String[] { android.provider.OpenableColumns.SIZE },
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
