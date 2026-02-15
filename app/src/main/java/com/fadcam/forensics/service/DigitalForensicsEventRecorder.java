package com.fadcam.forensics.service;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;

import com.fadcam.SharedPreferencesManager;
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

/**
 * Real-time forensics event writer: multi-event, snapshot-first, no stop-only dependency.
 */
public class DigitalForensicsEventRecorder {

    private static final String TAG = "ForensicsEventRecorder";
    private static final long EVENT_STALE_GAP_MS = 1800L;
    private static final long SNAPSHOT_MIN_INTERVAL_MS = 900L;

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
            @Nullable byte[] snapshotJpeg,
            boolean frontCamera
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
        ioExecutor.submit(() -> handleDetections(mediaUri, Math.max(0L, timelineMs), stable, snapshotJpeg, frontCamera));
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
            boolean frontCamera
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

            if (snapshotJpeg != null && snapshotJpeg.length > 0
                    && (event.lastSnapshotMs < 0 || (timelineMs - event.lastSnapshotMs) >= SNAPSHOT_MIN_INTERVAL_MS)) {
                byte[] detectionSnapshot = buildDetectionSnapshot(snapshotJpeg, detection, frontCamera, timelineMs, nowEpoch);
                String snapshotUid = UUID.randomUUID().toString();
                String imageUri = persistSnapshotFile(mediaUid, event.eventUid, snapshotUid, timelineMs, detectionSnapshot);
                if (imageUri != null && detectionSnapshot != null) {
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
                    snapshot.sha256 = sha256(detectionSnapshot);
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
    private byte[] buildDetectionSnapshot(
            @NonNull byte[] fullJpeg,
            @NonNull EfficientDetLite1Detector.DetectionResult detection,
            boolean frontCamera,
            long timelineMs,
            long captureEpochMs
    ) {
        try {
            Bitmap full = BitmapFactory.decodeByteArray(fullJpeg, 0, fullJpeg.length);
            if (full == null) {
                return fullJpeg;
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
            if (frontCamera) {
                Matrix matrix = new Matrix();
                matrix.postRotate(90f);
                finalBitmap = Bitmap.createBitmap(cropped, 0, 0, cropped.getWidth(), cropped.getHeight(), matrix, true);
            }
            drawSnapshotWatermark(finalBitmap, timelineMs, captureEpochMs);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, 84, out);
            return out.toByteArray();
        } catch (Throwable t) {
            return fullJpeg;
        }
    }

    private void drawSnapshotWatermark(@NonNull Bitmap bitmap, long timelineMs, long captureEpochMs) {
        try {
            Canvas canvas = new Canvas(bitmap);
            String stamp = formatWatermark(captureEpochMs, timelineMs);
            float textSize = Math.max(18f, Math.min(24f, bitmap.getWidth() * 0.006f));
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.WHITE);
            paint.setTextSize(textSize);
            paint.setShadowLayer(2.5f, 0f, 1.5f, Color.BLACK);
            paint.setFakeBoldText(true);
            try {
                Typeface ubuntu = Typeface.createFromAsset(appContext.getAssets(), "ubuntu_regular.ttf");
                paint.setTypeface(ubuntu);
            } catch (Exception ignored) {
                paint.setTypeface(Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD));
            }

            int padding = Math.max(14, Math.round(bitmap.getWidth() * 0.012f));
            String[] lines = stamp.split("\n");
            Paint.FontMetrics fm = paint.getFontMetrics();
            float lineHeight = (fm.descent - fm.ascent) + 4f;
            float y = padding - fm.ascent;
            for (String line : lines) {
                canvas.drawText(line, padding, y, paint);
                y += lineHeight;
            }
        } catch (Throwable ignored) {
        }
    }

    @NonNull
    private String formatWatermark(long captureEpochMs, long timelineMs) {
        String date = new SimpleDateFormat("dd/MMM/yyyy hh:mm:ss a", Locale.ENGLISH).format(new Date(Math.max(0L, captureEpochMs)));
        String custom = prefs.getWatermarkCustomText();
        String customLine = (custom != null && !custom.trim().isEmpty()) ? ("\n" + custom.trim()) : "";
        return "Captured by FadCam - " + date + customLine;
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
