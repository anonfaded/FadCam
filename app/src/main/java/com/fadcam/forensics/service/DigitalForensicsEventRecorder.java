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
    private String activeEventType = "MOTION";
    private long activeStartMs = -1L;
    private float activeMaxConfidence = 0f;
    private float activeMaxScore = 0f;
    private float activeChangedArea = 0f;
    private float activeStrongArea = 0f;
    private float activeCenterX = 0.5f;
    private float activeCenterY = 0.5f;

    public DigitalForensicsEventRecorder(Context context) {
        Context appContext = context.getApplicationContext();
        this.prefs = SharedPreferencesManager.getInstance(appContext);
        ForensicsDatabase db = ForensicsDatabase.getInstance(appContext);
        this.mediaAssetDao = db.mediaAssetDao();
        this.aiEventDao = db.aiEventDao();
    }

    public void onMotionStart(String mediaUri, long timelineMs, boolean personLikely, float personConfidence,
                              float motionScore, float changedArea, float strongArea,
                              float centerX, float centerY) {
        if (!shouldRecord()) {
            Log.d(TAG, "DF skip start: digital forensics disabled or all event classes off");
            return;
        }
        if (mediaUri == null || mediaUri.isEmpty()) {
            Log.w(TAG, "DF skip start: mediaUri is empty");
            return;
        }
        ioExecutor.submit(() -> handleStart(mediaUri, timelineMs, personLikely, personConfidence, motionScore, changedArea, strongArea, centerX, centerY));
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
        if (!prefs.isDigitalForensicsEnabled()) {
            return false;
        }
        // Current runtime detector support is motion + person; vehicle/pet/dangerous
        // tags are settings-level placeholders until dedicated models are integrated.
        return prefs.isDfEventPersonEnabled();
    }

    private void handleStart(String mediaUri, long timelineMs, boolean personLikely, float personConfidence, float motionScore,
                             float changedArea, float strongArea, float centerX, float centerY) {
        synchronized (lock) {
            final boolean personCandidate = personLikely || personConfidence >= 0.58f;
            final String eventType = personCandidate ? "PERSON" : "MOTION";
            if (activeMediaUid != null) {
                if (mediaUri.equals(activeUri)) {
                    activeMaxConfidence = Math.max(activeMaxConfidence, personConfidence);
                    activeMaxScore = Math.max(activeMaxScore, motionScore);
                    activeChangedArea = Math.max(activeChangedArea, Math.max(0f, changedArea));
                    activeStrongArea = Math.max(activeStrongArea, Math.max(0f, strongArea));
                    activeCenterX = clamp01(centerX);
                    activeCenterY = clamp01(centerY);
                    if ("PERSON".equals(eventType)) {
                        activeEventType = "PERSON";
                        Log.d(TAG, "DF promote: active event upgraded to PERSON, conf=" + personConfidence);
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
            activeStartMs = Math.max(0L, timelineMs);
            activeMaxConfidence = personConfidence;
            activeMaxScore = motionScore;
            activeChangedArea = Math.max(0f, changedArea);
            activeStrongArea = Math.max(0f, strongArea);
            activeCenterX = clamp01(centerX);
            activeCenterY = clamp01(centerY);
            Log.d(TAG, "DF start: type=" + eventType
                    + ", mediaUri=" + mediaUri
                    + ", timelineMs=" + activeStartMs
                    + ", personConf=" + personConfidence
                    + ", score=" + motionScore);
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
        event.eventType = activeEventType != null ? activeEventType : "MOTION";
        event.startMs = activeStartMs;
        event.endMs = endMs;
        event.confidence = activeMaxConfidence;
        event.bboxNorm = toSyntheticBbox(activeChangedArea, activeStrongArea, activeCenterX, activeCenterY);
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
        activeEventType = "MOTION";
        activeStartMs = -1L;
        activeMaxConfidence = 0f;
        activeMaxScore = 0f;
        activeChangedArea = 0f;
        activeStrongArea = 0f;
        activeCenterX = 0.5f;
        activeCenterY = 0.5f;
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

    private String toSyntheticBbox(float changedArea, float strongArea, float centerX, float centerY) {
        float area = Math.max(changedArea, strongArea);
        if (area <= 0f) {
            return "0.5,0.5,0.08,0.08";
        }
        float side = (float) Math.sqrt(Math.min(0.18f, Math.max(0.02f, area * 0.35f)));
        float cx = clamp01(centerX);
        float cy = clamp01(centerY);
        return cx + "," + cy + "," + side + "," + side;
    }

    private String ensureMediaAsset(String mediaUri) {
        long nowEpoch = System.currentTimeMillis();
        try {
            MediaAssetEntity existing = mediaAssetDao.findByCurrentUri(mediaUri);
            if (existing != null) {
                existing.lastSeenAt = nowEpoch;
                mediaAssetDao.upsert(existing);
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
            asset.firstSeenAt = nowEpoch;
            asset.lastSeenAt = nowEpoch;
            asset.linkStatus = "NEW";
            mediaAssetDao.upsert(asset);
            Log.d(TAG, "DF media asset upserted: mediaUid=" + asset.mediaUid + ", uri=" + mediaUri);
            return asset.mediaUid;
        } catch (Exception e) {
            Log.w(TAG, "ensureMediaAsset failed for uri=" + mediaUri, e);
            return null;
        }
    }

    private float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
