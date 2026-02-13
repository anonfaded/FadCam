package com.fadcam.forensics.service;

import android.content.Context;
import android.util.Log;

import com.fadcam.SharedPreferencesManager;
import com.fadcam.forensics.data.local.ForensicsDatabase;
import com.fadcam.forensics.data.local.dao.AiEventDao;
import com.fadcam.forensics.data.local.dao.MediaAssetDao;
import com.fadcam.forensics.data.local.entity.AiEventEntity;
import com.fadcam.forensics.data.local.entity.MediaAssetEntity;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Writes motion-driven forensic events without touching recording control flow.
 */
public class DigitalForensicsEventRecorder {

    private static final String TAG = "ForensicsEventRecorder";

    private final SharedPreferencesManager prefs;
    private final MediaAssetDao mediaAssetDao;
    private final AiEventDao aiEventDao;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    private final Object lock = new Object();
    private String activeMediaUid;
    private String activeUri;
    private long activeStartMs = -1L;
    private float activeMaxConfidence = 0f;
    private float activeMaxScore = 0f;
    private float activeChangedArea = 0f;
    private float activeStrongArea = 0f;

    public DigitalForensicsEventRecorder(Context context) {
        Context appContext = context.getApplicationContext();
        this.prefs = SharedPreferencesManager.getInstance(appContext);
        ForensicsDatabase db = ForensicsDatabase.getInstance(appContext);
        this.mediaAssetDao = db.mediaAssetDao();
        this.aiEventDao = db.aiEventDao();
    }

    public void onMotionStart(String mediaUri, long timelineMs, boolean personLikely, float personConfidence,
                              float motionScore, float changedArea, float strongArea) {
        if (!shouldRecord(personLikely)) {
            return;
        }
        if (mediaUri == null || mediaUri.isEmpty()) {
            return;
        }
        ioExecutor.submit(() -> handleStart(mediaUri, timelineMs, personConfidence, motionScore, changedArea, strongArea));
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

    private boolean shouldRecord(boolean personLikely) {
        if (!prefs.isDigitalForensicsEnabled()) {
            return false;
        }
        // Baseline pipeline currently emits person-oriented motion events.
        if (!prefs.isDfEventPersonEnabled()) {
            return false;
        }
        // If model is uncertain, allow event only when person class is enabled and there is motion trigger.
        return personLikely || prefs.isDfEventPersonEnabled();
    }

    private void handleStart(String mediaUri, long timelineMs, float personConfidence, float motionScore,
                             float changedArea, float strongArea) {
        synchronized (lock) {
            if (activeMediaUid != null) {
                if (mediaUri.equals(activeUri)) {
                    activeMaxConfidence = Math.max(activeMaxConfidence, personConfidence);
                    activeMaxScore = Math.max(activeMaxScore, motionScore);
                    return;
                }
                // Segment/media switched while event was active.
                persistEventLocked(Math.max(activeStartMs, timelineMs));
            }

            String mediaUid = ensureMediaAsset(mediaUri, timelineMs);
            if (mediaUid == null) {
                return;
            }
            activeMediaUid = mediaUid;
            activeUri = mediaUri;
            activeStartMs = Math.max(0L, timelineMs);
            activeMaxConfidence = personConfidence;
            activeMaxScore = motionScore;
            activeChangedArea = Math.max(0f, changedArea);
            activeStrongArea = Math.max(0f, strongArea);
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
        AiEventEntity event = new AiEventEntity();
        event.eventUid = UUID.randomUUID().toString();
        event.mediaUid = activeMediaUid;
        event.eventType = "PERSON";
        event.startMs = activeStartMs;
        event.endMs = endMs;
        event.confidence = activeMaxConfidence;
        event.bboxNorm = toSyntheticBbox(activeChangedArea, activeStrongArea);
        event.trackId = null;
        event.priority = toPriority(activeMaxConfidence, activeMaxScore);
        event.thumbnailRef = activeUri + "#t=" + (activeStartMs / 1000f);
        event.detectedAtEpochMs = System.currentTimeMillis();
        aiEventDao.upsert(event);

        activeMediaUid = null;
        activeUri = null;
        activeStartMs = -1L;
        activeMaxConfidence = 0f;
        activeMaxScore = 0f;
        activeChangedArea = 0f;
        activeStrongArea = 0f;
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

    private String toSyntheticBbox(float changedArea, float strongArea) {
        float area = Math.max(changedArea, strongArea);
        if (area <= 0f) {
            return "0.5,0.5,0.1,0.1";
        }
        float side = (float) Math.sqrt(Math.min(0.5f, Math.max(0.02f, area)));
        float cx = 0.5f;
        float cy = 0.5f;
        return cx + "," + cy + "," + side + "," + side;
    }

    private String ensureMediaAsset(String mediaUri, long now) {
        try {
            MediaAssetEntity existing = mediaAssetDao.findByCurrentUri(mediaUri);
            if (existing != null) {
                return existing.mediaUid;
            }

            MediaAssetEntity asset = new MediaAssetEntity();
            asset.mediaUid = UUID.randomUUID().toString();
            asset.currentUri = mediaUri;
            asset.displayName = mediaUri;
            asset.categorySubtype = "UNKNOWN/UNKNOWN";
            asset.sizeBytes = 0L;
            asset.durationMs = 0L;
            asset.codecInfo = null;
            asset.exactFingerprint = null;
            asset.visualFingerprint = null;
            asset.firstSeenAt = now;
            asset.lastSeenAt = now;
            asset.linkStatus = "NEW";
            mediaAssetDao.upsert(asset);
            return asset.mediaUid;
        } catch (Exception e) {
            Log.w(TAG, "ensureMediaAsset failed for uri=" + mediaUri, e);
            return null;
        }
    }
}
