package com.fadcam.forensics.service;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.fadcam.SharedPreferencesManager;
import com.fadcam.forensics.data.local.ForensicsDatabase;
import com.fadcam.forensics.data.local.dao.IntegrityLinkLogDao;
import com.fadcam.forensics.data.local.dao.MediaAssetDao;
import com.fadcam.forensics.data.local.entity.IntegrityLinkLogEntity;
import com.fadcam.forensics.data.local.entity.MediaAssetEntity;
import com.fadcam.forensics.domain.fingerprint.ExactFingerprintGenerator;
import com.fadcam.forensics.domain.fingerprint.ForensicsMetadataUtils;
import com.fadcam.forensics.domain.fingerprint.VisualFingerprintGenerator;
import com.fadcam.forensics.domain.model.MediaFingerprintProfile;
import com.fadcam.forensics.domain.model.RelinkMatchResult;
import com.fadcam.forensics.domain.relink.MediaRelinkEngine;
import com.fadcam.ui.VideoItem;

import java.util.List;
import java.util.UUID;

public class DigitalForensicsIndexer {

    private static final String TAG = "DigitalForensicsIndexer";

    private final Context appContext;
    private final SharedPreferencesManager prefs;
    private final MediaAssetDao mediaAssetDao;
    private final IntegrityLinkLogDao linkLogDao;
    private final MediaRelinkEngine relinkEngine;

    public DigitalForensicsIndexer(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = SharedPreferencesManager.getInstance(appContext);
        ForensicsDatabase db = ForensicsDatabase.getInstance(appContext);
        this.mediaAssetDao = db.mediaAssetDao();
        this.linkLogDao = db.integrityLinkLogDao();
        this.relinkEngine = new MediaRelinkEngine(mediaAssetDao);
    }

    public void index(List<VideoItem> videoItems) {
        if (!prefs.isDigitalForensicsEnabled() || videoItems == null || videoItems.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        for (VideoItem item : videoItems) {
            if (item == null || item.uri == null || item.mediaType != VideoItem.MediaType.VIDEO) {
                continue;
            }
            try {
                processVideoItem(item, now);
            } catch (Exception e) {
                Log.w(TAG, "Skipping item after index failure: " + item.uri, e);
            }
        }
    }

    private void processVideoItem(VideoItem item, long now) {
        String uriString = item.uri.toString();
        MediaAssetEntity existingByUri = mediaAssetDao.findByCurrentUri(uriString);

        long durationMs = ForensicsMetadataUtils.extractDurationMs(appContext, item.uri);
        String codecInfo = ForensicsMetadataUtils.extractCodecInfo(appContext, item.uri);
        String exact = ExactFingerprintGenerator.compute(appContext.getContentResolver(), item.uri, item.size);
        String visual = VisualFingerprintGenerator.compute(appContext, item.uri);

        if (existingByUri != null) {
            mediaAssetDao.updateLinkAndMetadata(
                existingByUri.mediaUid,
                uriString,
                item.displayName,
                item.size,
                durationMs,
                codecInfo,
                exact,
                visual,
                now,
                existingByUri.linkStatus == null ? "EXACT" : existingByUri.linkStatus
            );
            return;
        }

        MediaFingerprintProfile profile = new MediaFingerprintProfile(exact, visual, item.size, durationMs);
        RelinkMatchResult match = relinkEngine.match(profile);

        if (match.status == RelinkMatchResult.Status.EXACT || match.status == RelinkMatchResult.Status.PROBABLE) {
            mediaAssetDao.updateLinkAndMetadata(
                match.mediaUid,
                uriString,
                item.displayName,
                item.size,
                durationMs,
                codecInfo,
                exact,
                visual,
                now,
                match.status == RelinkMatchResult.Status.EXACT ? "EXACT" : "PROBABLE"
            );
            logLink(match.mediaUid, match.status == RelinkMatchResult.Status.EXACT ? "LINKED_EXACT" : "LINKED_PROBABLE", match.score, now);
            return;
        }

        MediaAssetEntity fresh = new MediaAssetEntity();
        fresh.mediaUid = UUID.randomUUID().toString();
        fresh.currentUri = uriString;
        fresh.displayName = item.displayName;
        fresh.categorySubtype = item.category + "/" + item.cameraSubtype;
        fresh.sizeBytes = item.size;
        fresh.durationMs = durationMs;
        fresh.codecInfo = codecInfo;
        fresh.exactFingerprint = exact;
        fresh.visualFingerprint = visual;
        fresh.firstSeenAt = now;
        fresh.lastSeenAt = now;
        fresh.linkStatus = "NEW";
        mediaAssetDao.upsert(fresh);
    }

    private void logLink(String mediaUid, String action, float score, long now) {
        IntegrityLinkLogEntity log = new IntegrityLinkLogEntity();
        log.logUid = UUID.randomUUID().toString();
        log.mediaUid = mediaUid;
        log.action = action;
        log.score = score;
        log.timestamp = now;
        linkLogDao.upsert(log);
    }
}
