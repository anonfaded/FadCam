package com.fadcam.forensics.service;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;

import com.fadcam.SharedPreferencesManager;
import com.fadcam.Constants;
import com.fadcam.forensics.data.local.ForensicsDatabase;
import com.fadcam.forensics.data.local.dao.AiEventDao;
import com.fadcam.forensics.data.local.dao.AiEventSnapshotDao;
import com.fadcam.forensics.data.local.dao.MediaAssetDao;
import com.fadcam.forensics.data.local.entity.AiEventEntity;
import com.fadcam.forensics.data.local.entity.AiEventSnapshotEntity;
import com.fadcam.forensics.data.local.entity.MediaAssetEntity;
import com.fadcam.forensics.domain.fingerprint.ForensicsMetadataUtils;
import com.fadcam.motion.domain.detector.EfficientDetLite1Detector;

import java.io.File;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
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
import java.util.concurrent.ConcurrentHashMap;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;

/**
 * Real-time forensics event writer: multi-event, snapshot-first, no stop-only dependency.
 */
public class DigitalForensicsEventRecorder {

    private static final String TAG = "ForensicsEventRecorder";
    private static final long EVENT_STALE_GAP_MS = 1800L;
    private static final long SNAPSHOT_FAST_INTERVAL_MS = 900L;
    private static final long SNAPSHOT_STEADY_INTERVAL_MS = 6000L;
    private static final long SNAPSHOT_HEARTBEAT_INTERVAL_MS = 12000L;
    private static final int SNAPSHOT_FAST_COUNT = 3;
    private static final float SNAPSHOT_CENTER_DELTA_THRESHOLD = 0.04f;
    private static final float SNAPSHOT_SIZE_DELTA_THRESHOLD = 0.06f;
    private static final float SNAPSHOT_CONF_DELTA_THRESHOLD = 0.10f;
    private static final int SNAPSHOT_PHASH_HAMMING_THRESHOLD = 4;

    private final SharedPreferencesManager prefs;
    private final Context appContext;
    private final MediaAssetDao mediaAssetDao;
    private final AiEventDao aiEventDao;
    private final AiEventSnapshotDao snapshotDao;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Map<String, ActiveEvent> activeEvents = new HashMap<>();
    private volatile boolean snapshotRootLogged;
    private long snapshotPersistCount;
    private long filteredByScopeCount;
    private long filteredByCategoryCount;
    private long filteredByAntiFaceCount;
    private long filterSeenCount;
    private long lastFilterLogMs;
    private final Map<String, Long> lastMediaSnapshotCount = new ConcurrentHashMap<>();

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
        long lastSnapshotPHash;
        float lastSnapshotConfidence;
        float lastSnapshotCenterX;
        float lastSnapshotCenterY;
        float lastSnapshotWidth;
        float lastSnapshotHeight;
        int snapshotCount;
        float peakConfidence;
        float centerX;
        float centerY;
        float boxWidth;
        float boxHeight;
        int sampleCount;
    }

    private static final class SnapshotPayload {
        final byte[] jpeg;
        final long pHash;

        SnapshotPayload(byte[] jpeg, long pHash) {
            this.jpeg = jpeg;
            this.pHash = pHash;
        }
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
            @Nullable byte[] snapshotJpeg,
            boolean frontCamera,
            int sensorOrientationDegrees,
            @Nullable String recordingOrientation,
            boolean mirrorHorizontally
    ) {
        if (!prefs.isDigitalForensicsEnabled() || !prefs.isDfEvidenceCollectionEnabled()) {
            return;
        }
        if (mediaUri == null || mediaUri.isEmpty() || detections == null || detections.isEmpty()) {
            return;
        }
        String captureScope = prefs.getDfCaptureScope();
        float frameBestPerson = 0f;
        for (EfficientDetLite1Detector.DetectionResult detection : detections) {
            if (detection == null) {
                continue;
            }
            if ("PERSON".equals(normalizeEventType(detection.coarseType))) {
                frameBestPerson = Math.max(frameBestPerson, detection.confidence);
            }
        }
        List<EfficientDetLite1Detector.DetectionResult> stable = new ArrayList<>();
        for (EfficientDetLite1Detector.DetectionResult detection : detections) {
            filterSeenCount++;
            if (detection == null || detection.confidence < 0.28f) {
                continue;
            }
            String eventType = normalizeEventType(detection.coarseType);
            boolean isPerson = "PERSON".equals(eventType);

            if (!isEventTypeEnabled(eventType)) {
                filteredByCategoryCount++;
                continue;
            }

            if ("people".equals(captureScope) && !isPerson) {
                filteredByScopeCount++;
                continue;
            }
            if ("objects".equals(captureScope) && isPerson) {
                filteredByScopeCount++;
                continue;
            }
            if ("objects".equals(captureScope)
                    && shouldSuppressLikelyFacePet(eventType, detection, frameBestPerson)) {
                filteredByAntiFaceCount++;
                continue;
            }
            stable.add(detection);
        }
        maybeLogFilterStats();
        if (stable.isEmpty()) {
            return;
        }
        ioExecutor.submit(() -> handleDetections(
                mediaUri,
                Math.max(0L, timelineMs),
                stable,
                snapshotJpeg,
                frontCamera,
                sensorOrientationDegrees,
                recordingOrientation,
                mirrorHorizontally
        ));
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
            @Nullable byte[] snapshotJpeg,
            boolean frontCamera,
            int sensorOrientationDegrees,
            @Nullable String recordingOrientation,
            boolean mirrorHorizontally
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
            String key = buildActiveKey(
                    mediaUid,
                    eventType,
                    className,
                    detection.centerX,
                    detection.centerY,
                    detection.width,
                    detection.height
            );
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

            if (snapshotJpeg != null && snapshotJpeg.length > 0) {
                SnapshotPayload payload = buildDetectionSnapshot(
                        snapshotJpeg,
                        detection,
                        frontCamera,
                        sensorOrientationDegrees,
                        recordingOrientation,
                        mirrorHorizontally,
                        timelineMs,
                        nowEpoch
                );
                if (payload == null || payload.jpeg == null || payload.jpeg.length == 0) {
                    continue;
                }
                if (!shouldPersistSnapshot(event, detection, payload.pHash, timelineMs)) {
                    continue;
                }
                String snapshotUid = UUID.randomUUID().toString();
                String imageUri = persistSnapshotFile(mediaUid, event.eventUid, snapshotUid, timelineMs, payload.jpeg);
                if (imageUri != null) {
                    AiEventSnapshotEntity snapshot = new AiEventSnapshotEntity();
                    snapshot.snapshotUid = snapshotUid;
                    snapshot.eventUid = event.eventUid;
                    snapshot.mediaUid = mediaUid;
                    snapshot.capturedEpochMs = nowEpoch;
                    snapshot.timelineMs = timelineMs;
                    snapshot.eventType = event.eventType;
                    snapshot.className = event.className;
                    snapshot.confidence = detection.confidence;
                    snapshot.bboxNorm = toBbox(event.centerX, event.centerY, event.boxWidth, event.boxHeight);
                    snapshot.imageUri = imageUri;
                    snapshot.sha256 = sha256(payload.jpeg);
                    snapshotDao.upsert(snapshot);
                    snapshotPersistCount++;
                    long mediaCount = snapshotDao.countByMediaUid(mediaUid);
                    long totalCount = snapshotDao.countAllSnapshots();
                    long prev = lastMediaSnapshotCount.containsKey(mediaUid) ? lastMediaSnapshotCount.get(mediaUid) : 0L;
                    if (mediaCount < prev) {
                        Log.e(TAG, "ForensicsPersist regression: mediaUid=" + mediaUid
                                + ", previousMediaSnapshots=" + prev
                                + ", currentMediaSnapshots=" + mediaCount
                                + ", totalSnapshots=" + totalCount
                                + ", lastSnapshotUid=" + snapshotUid);
                    }
                    lastMediaSnapshotCount.put(mediaUid, mediaCount);
                    Log.i(TAG, "ForensicsPersist: mediaUid=" + mediaUid
                            + ", mediaSnapshots=" + mediaCount
                            + ", totalSnapshots=" + totalCount
                            + ", lastSnapshotUid=" + snapshotUid);
                    Intent persisted = new Intent(Constants.ACTION_FORENSICS_SNAPSHOT_PERSISTED);
                    persisted.putExtra("snapshot_uid", snapshotUid);
                    persisted.putExtra("media_uid", mediaUid);
                    appContext.sendBroadcast(persisted);
                }
                event.lastSnapshotMs = timelineMs;
                event.lastSnapshotPHash = payload.pHash;
                event.lastSnapshotConfidence = detection.confidence;
                event.lastSnapshotCenterX = event.centerX;
                event.lastSnapshotCenterY = event.centerY;
                event.lastSnapshotWidth = event.boxWidth;
                event.lastSnapshotHeight = event.boxHeight;
                event.snapshotCount += 1;
            }
        }
    }

    private boolean shouldPersistSnapshot(
            @NonNull ActiveEvent event,
            @NonNull EfficientDetLite1Detector.DetectionResult detection,
            long newPHash,
            long timelineMs
    ) {
        if (event.lastSnapshotMs < 0L) {
            return true;
        }
        long elapsed = Math.max(0L, timelineMs - event.lastSnapshotMs);
        long minInterval = event.snapshotCount < SNAPSHOT_FAST_COUNT
                ? SNAPSHOT_FAST_INTERVAL_MS
                : SNAPSHOT_STEADY_INTERVAL_MS;

        float centerDx = Math.abs(event.centerX - event.lastSnapshotCenterX);
        float centerDy = Math.abs(event.centerY - event.lastSnapshotCenterY);
        float sizeDw = Math.abs(event.boxWidth - event.lastSnapshotWidth);
        float sizeDh = Math.abs(event.boxHeight - event.lastSnapshotHeight);
        float confDelta = Math.abs(detection.confidence - event.lastSnapshotConfidence);
        boolean motionChanged = (centerDx + centerDy) > SNAPSHOT_CENTER_DELTA_THRESHOLD
                || (sizeDw + sizeDh) > SNAPSHOT_SIZE_DELTA_THRESHOLD
                || confDelta > SNAPSHOT_CONF_DELTA_THRESHOLD;
        boolean visualChanged = hammingDistance(event.lastSnapshotPHash, newPHash) > SNAPSHOT_PHASH_HAMMING_THRESHOLD;
        boolean heartbeatDue = elapsed >= SNAPSHOT_HEARTBEAT_INTERVAL_MS;

        if (heartbeatDue) {
            return true;
        }
        if (elapsed < minInterval) {
            return motionChanged || visualChanged;
        }
        return motionChanged || visualChanged;
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
        long inserted = aiEventDao.insertIgnore(event);
        if (inserted == -1L) {
            aiEventDao.update(event);
        }
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
    private String persistSnapshotFile(String mediaUid, String eventUid, String snapshotUid, long timelineMs, @Nullable byte[] jpeg) {
        if (jpeg == null || jpeg.length == 0) {
            return null;
        }
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
            File out = new File(dir, eventUid + "_" + timelineMs + "_" + snapshotUid + ".jpg");
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

    private String buildActiveKey(String mediaUid, String eventType, String className, float centerX, float centerY, float width, float height) {
        int bucketX = Math.max(0, Math.min(15, Math.round(clamp01(centerX) * 15f)));
        int bucketY = Math.max(0, Math.min(15, Math.round(clamp01(centerY) * 15f)));
        int bucketW = Math.max(0, Math.min(12, Math.round(clampBox(width) * 12f)));
        int bucketH = Math.max(0, Math.min(12, Math.round(clampBox(height) * 12f)));
        return mediaUid + "|" + eventType + "|" + className + "|" + bucketX + "|" + bucketY + "|" + bucketW + "|" + bucketH;
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
                mediaAssetDao.update(existing);
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
            long inserted = mediaAssetDao.insertIgnore(asset);
            if (inserted == -1L) {
                mediaAssetDao.update(asset);
            }
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

    @Nullable
    private SnapshotPayload buildDetectionSnapshot(
            @NonNull byte[] fullJpeg,
            @NonNull EfficientDetLite1Detector.DetectionResult detection,
            boolean frontCamera,
            int sensorOrientationDegrees,
            @Nullable String recordingOrientation,
            boolean mirrorHorizontally,
            long timelineMs,
            long captureEpochMs
    ) {
        try {
            Bitmap full = BitmapFactory.decodeByteArray(fullJpeg, 0, fullJpeg.length);
            if (full == null) {
                return new SnapshotPayload(fullJpeg, 0L);
            }
            int width = full.getWidth();
            int height = full.getHeight();
            int left = Math.max(0, Math.round((detection.centerX - (detection.width * 0.5f)) * width));
            int top = Math.max(0, Math.round((detection.centerY - (detection.height * 0.5f)) * height));
            int right = Math.min(width, Math.round((detection.centerX + (detection.width * 0.5f)) * width));
            int bottom = Math.min(height, Math.round((detection.centerY + (detection.height * 0.5f)) * height));
            int cropW = Math.max(1, right - left);
            int cropH = Math.max(1, bottom - top);
            Bitmap cropped = Bitmap.createBitmap(full, left, top, cropW, cropH);
            Bitmap finalBitmap = cropped;
            FrameOrientationTransform transform = resolveTransform(
                    frontCamera,
                    sensorOrientationDegrees,
                    recordingOrientation,
                    mirrorHorizontally
            );
            if (transform.rotationDegrees != 0 || transform.mirrorHorizontally || transform.mirrorVertically) {
                Matrix matrix = transform.toMatrix(cropped.getWidth(), cropped.getHeight());
                finalBitmap = Bitmap.createBitmap(
                        cropped,
                        0,
                        0,
                        cropped.getWidth(),
                        cropped.getHeight(),
                        matrix,
                        true
                );
            }
            drawSnapshotWatermark(finalBitmap, timelineMs, captureEpochMs);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, 84, out);
            return new SnapshotPayload(out.toByteArray(), computeDHash64(finalBitmap));
        } catch (Throwable t) {
            return new SnapshotPayload(fullJpeg, 0L);
        }
    }

    private void drawSnapshotWatermark(@NonNull Bitmap bitmap, long timelineMs, long captureEpochMs) {
        try {
            Canvas canvas = new Canvas(bitmap);
            String stamp = formatWatermark(captureEpochMs, timelineMs).replace('\r', ' ').replace('\n', ' ');
            float maxTextSize = Math.max(8f, Math.min(13f, bitmap.getWidth() * 0.026f));
            float minTextSize = Math.max(7f, maxTextSize * 0.75f);
            TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.WHITE);
            paint.setTextSize(maxTextSize);
            paint.setShadowLayer(1.4f, 0f, 0.6f, Color.BLACK);
            paint.setFakeBoldText(false);
            try {
                Typeface ubuntu = Typeface.createFromAsset(appContext.getAssets(), "ubuntu_regular.ttf");
                paint.setTypeface(ubuntu);
            } catch (Exception ignored) {
                paint.setTypeface(Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD));
            }

            int padding = Math.max(1, Math.round(Math.min(bitmap.getWidth(), bitmap.getHeight()) * 0.0035f));
            int maxWidth = Math.max(20, bitmap.getWidth() - (padding * 2));
            CharSequence line1 = stamp;
            CharSequence line2 = null;

            while (paint.measureText(line1, 0, line1.length()) > maxWidth && paint.getTextSize() > minTextSize) {
                paint.setTextSize(Math.max(minTextSize, paint.getTextSize() - 1f));
            }

            if (paint.measureText(line1, 0, line1.length()) > maxWidth) {
                int splitAt = findSplitIndex(line1, maxWidth, paint);
                if (splitAt > 0 && splitAt < line1.length()) {
                    CharSequence first = line1.subSequence(0, splitAt).toString().trim();
                    CharSequence second = line1.subSequence(splitAt, line1.length()).toString().trim();
                    line1 = TextUtils.ellipsize(first, paint, maxWidth, TextUtils.TruncateAt.END);
                    line2 = TextUtils.ellipsize(second, paint, maxWidth, TextUtils.TruncateAt.END);
                } else {
                    line1 = TextUtils.ellipsize(line1, paint, maxWidth, TextUtils.TruncateAt.END);
                }
            }
            // Keep forensic snapshots compact by default (single-line first).
            line2 = null;

            Paint.FontMetrics fm = paint.getFontMetrics();
            float lineHeight = (fm.descent - fm.ascent) + 3f;
            float yTop = padding - fm.ascent;
            float yBottomBase = bitmap.getHeight() - padding - (line2 == null ? 0f : lineHeight);
            float y = (yTop + ((line2 == null ? yTop : (yTop + lineHeight)) > yBottomBase ? yBottomBase : yTop));

            canvas.drawText(line1, 0, line1.length(), padding, y, paint);
            if (line2 != null) {
                canvas.drawText(line2, 0, line2.length(), padding, y + lineHeight, paint);
            }
        } catch (Throwable ignored) {
        }
    }

    private int findSplitIndex(@NonNull CharSequence value, int maxWidth, @NonNull TextPaint paint) {
        int best = -1;
        for (int i = 8; i < value.length() - 8; i++) {
            if (value.charAt(i) == ' ') {
                if (paint.measureText(value, 0, i) <= maxWidth) {
                    best = i;
                } else {
                    break;
                }
            }
        }
        return best;
    }

    @NonNull
    private String formatWatermark(long captureEpochMs, long timelineMs) {
        String date = new SimpleDateFormat("dd/MMM/yyyy hh:mm:ss a", Locale.ENGLISH).format(new Date(Math.max(0L, captureEpochMs)));
        return "Captured by FadCam - " + date;
    }

    @NonNull
    private FrameOrientationTransform resolveTransform(
            boolean frontCamera,
            int sensorOrientationDegrees,
            @Nullable String recordingOrientation,
            boolean mirrorHorizontally
    ) {
        String orientation = recordingOrientation == null ? "" : recordingOrientation.toLowerCase(Locale.US);
        int sensor = ((sensorOrientationDegrees % 360) + 360) % 360;
        int rotation;
        if (orientation.contains("portrait")) {
            rotation = sensor == 0 ? (frontCamera ? 270 : 90) : sensor;
        } else if (orientation.contains("reverse_landscape")) {
            rotation = 180;
        } else if (orientation.contains("reverse_portrait")) {
            rotation = (sensor + 180) % 360;
        } else {
            rotation = 0;
        }
        return new FrameOrientationTransform(rotation, mirrorHorizontally, false);
    }

    private long computeDHash64(@NonNull Bitmap source) {
        try {
            Bitmap scaled = Bitmap.createScaledBitmap(source, 9, 8, true);
            long hash = 0L;
            int bit = 0;
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    int a = scaled.getPixel(x, y);
                    int b = scaled.getPixel(x + 1, y);
                    int ga = (((a >> 16) & 0xFF) * 3 + ((a >> 8) & 0xFF) * 6 + (a & 0xFF)) / 10;
                    int gb = (((b >> 16) & 0xFF) * 3 + ((b >> 8) & 0xFF) * 6 + (b & 0xFF)) / 10;
                    if (ga > gb) {
                        hash |= (1L << bit);
                    }
                    bit++;
                }
            }
            return hash;
        } catch (Throwable t) {
            return 0L;
        }
    }

    private int hammingDistance(long a, long b) {
        return Long.bitCount(a ^ b);
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

    private boolean isEventTypeEnabled(@NonNull String eventType) {
        switch (eventType) {
            case "PERSON":
                return prefs.isDfEventPersonEnabled();
            case "VEHICLE":
                return prefs.isDfEventVehicleEnabled();
            case "PET":
                return prefs.isDfEventPetEnabled();
            case "OBJECT":
            default:
                return prefs.isDfDangerousObjectEnabled();
        }
    }

    private boolean shouldSuppressLikelyFacePet(
            @NonNull String eventType,
            @NonNull EfficientDetLite1Detector.DetectionResult detection,
            float frameBestPerson
    ) {
        if (!"PET".equals(eventType)) {
            return false;
        }
        if (frameBestPerson < 0.30f) {
            return false;
        }
        if (detection.confidence >= 0.90f) {
            return false;
        }
        String label = detection.className == null ? "" : detection.className.toLowerCase(Locale.US);
        return "cat".equals(label) || "dog".equals(label) || "bird".equals(label);
    }

    private void maybeLogFilterStats() {
        long now = android.os.SystemClock.elapsedRealtime();
        if ((now - lastFilterLogMs) < 10000L) {
            return;
        }
        lastFilterLogMs = now;
        Log.i(TAG, "ForensicsFilter stats: seen=" + filterSeenCount
                + ", scopeDrop=" + filteredByScopeCount
                + ", categoryDrop=" + filteredByCategoryCount
                + ", antiFaceDrop=" + filteredByAntiFaceCount
                + ", snapshots=" + snapshotPersistCount);
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
            // Skip heavy fingerprint generation in live recording path to reduce
            // capture stutter and GC pressure. Evidence snapshots remain the
            // canonical artifacts for forensics workflows.
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
