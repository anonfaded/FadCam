package com.fadcam.forensics.domain.relink;

import com.fadcam.forensics.data.local.dao.MediaAssetDao;
import com.fadcam.forensics.data.local.entity.MediaAssetEntity;
import com.fadcam.forensics.domain.model.MediaFingerprintProfile;
import com.fadcam.forensics.domain.model.RelinkMatchResult;

import java.util.List;

public class MediaRelinkEngine {

    private static final long SIZE_TOLERANCE_BYTES = 1_500_000L;
    private static final long DURATION_TOLERANCE_MS = 2_500L;
    private static final float PROBABLE_THRESHOLD = 0.92f;

    private final MediaAssetDao mediaAssetDao;

    public MediaRelinkEngine(MediaAssetDao mediaAssetDao) {
        this.mediaAssetDao = mediaAssetDao;
    }

    public RelinkMatchResult match(MediaFingerprintProfile profile) {
        if (profile == null) {
            return RelinkMatchResult.fresh();
        }

        if (profile.exactFingerprint != null && !profile.exactFingerprint.isEmpty()) {
            MediaAssetEntity exact = mediaAssetDao.findExactMatch(profile.exactFingerprint);
            if (exact != null) {
                return RelinkMatchResult.exact(exact.mediaUid);
            }
        }

        long minSize = Math.max(0L, profile.sizeBytes - SIZE_TOLERANCE_BYTES);
        long maxSize = profile.sizeBytes + SIZE_TOLERANCE_BYTES;
        long minDuration = Math.max(0L, profile.durationMs - DURATION_TOLERANCE_MS);
        long maxDuration = profile.durationMs + DURATION_TOLERANCE_MS;

        List<MediaAssetEntity> candidates = mediaAssetDao.findProbableCandidates(minSize, maxSize, minDuration, maxDuration);
        MediaAssetEntity best = null;
        float bestScore = 0f;

        for (MediaAssetEntity candidate : candidates) {
            float score = score(profile, candidate);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }

        if (best != null && bestScore >= PROBABLE_THRESHOLD) {
            return RelinkMatchResult.probable(best.mediaUid, bestScore);
        }
        return RelinkMatchResult.fresh();
    }

    private float score(MediaFingerprintProfile profile, MediaAssetEntity candidate) {
        float visual = visualScore(profile.visualFingerprint, candidate.visualFingerprint);
        float size = ratioScore(profile.sizeBytes, candidate.sizeBytes);
        float duration = ratioScore(profile.durationMs, candidate.durationMs);
        return (visual * 0.7f) + (size * 0.2f) + (duration * 0.1f);
    }

    private float visualScore(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return 0f;
        }
        try {
            long aa = Long.parseUnsignedLong(a, 16);
            long bb = Long.parseUnsignedLong(b, 16);
            int hamming = Long.bitCount(aa ^ bb);
            return 1f - (hamming / 64f);
        } catch (Exception ignored) {
            return 0f;
        }
    }

    private float ratioScore(long a, long b) {
        if (a <= 0L || b <= 0L) {
            return 0f;
        }
        long min = Math.min(a, b);
        long max = Math.max(a, b);
        return min / (float) max;
    }
}
