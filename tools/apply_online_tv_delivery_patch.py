from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
REMOTE = ROOT / "app/src/main/java/com/fadcam/streaming/RemoteStreamManager.java"
UPLOADER = ROOT / "app/src/main/java/com/fadcam/streaming/CloudStreamUploader.java"


def replace_once(text, pattern, replacement, label, flags=0):
    new_text, count = re.subn(pattern, replacement, text, count=1, flags=flags)
    if count != 1:
        raise SystemExit(f"PATCH FAILED: {label} (matches={count})")
    return new_text


def patch_remote_manager():
    text = REMOTE.read_text(encoding="utf-8")
    if "ONLINE_TV_RELAY_DELIVERY_V1" in text:
        print("RemoteStreamManager: already patched")
        return

    text = replace_once(
        text,
        r'import java\.util\.HashMap;\nimport java\.util\.List;\nimport java\.util\.Map;',
        'import java.util.HashMap;\nimport java.util.HashSet;\nimport java.util.List;\nimport java.util.Map;\nimport java.util.Set;',
        "RemoteStreamManager imports",
    )

    text = replace_once(
        text,
        r'(private volatile long lastRelayUploadMs = 0;)',
        r'''\1

    // ONLINE_TV_RELAY_DELIVERY_V1: only publish HLS playlists containing
    // fragments that the relay has positively acknowledged. A session id
    // prevents late callbacks from an old recording from contaminating a
    // newly-started online TV stream.
    private final Set<Integer> relayUploadedSequences = new HashSet<>();
    private long relayStreamSessionId = 0;''',
        "relay delivery state",
    )

    text = replace_once(
        text,
        r'(fragmentSequence = 0;\n            oldestSequence = 1;\n            bufferHead = 0;)',
        r'''\1
            relayStreamSessionId++;
            relayUploadedSequences.clear();''',
        "relay session reset",
    )

    old_upload = '''if (uploader.isEnabled() && uploader.isReady()) {
                    // Capture playlist now (while we have the lock) but upload after segment succeeds
                    final String playlist = generateCloudPlaylist();
                    
                    // Upload segment with callback - playlist uploaded only after segment succeeds
                    uploader.uploadSegment(sequenceNumber, fragmentData, new CloudStreamUploader.UploadCallback() {
                        @Override
                        public void onSuccess() {
                            // Segment uploaded successfully - update relay freshness timestamp
                            lastRelayUploadMs = System.currentTimeMillis();
                            // NOW upload the playlist
                            if (playlist != null) {
                                uploader.uploadPlaylist(playlist, null);
                            }
                        }
                        
                        @Override
                        public void onError(String error) {
                            // Segment failed - don't update playlist (viewers won't see missing segment)
                            FLog.w(TAG, "⚠️ Segment " + sequenceNumber + " upload failed, skipping playlist update: " + error);
                        }
                    });
                }'''
    new_upload = '''if (uploader.isEnabled() && uploader.isReady()) {
                    final long uploadSessionId = relayStreamSessionId;
                    // Never publish a playlist before the referenced segment has been
                    // acknowledged by the relay. The playlist is generated AFTER the
                    // acknowledgement so it can also exclude older failed fragments.
                    uploader.uploadSegment(sequenceNumber, fragmentData, new CloudStreamUploader.UploadCallback() {
                        @Override
                        public void onSuccess() {
                            if (uploadSessionId != relayStreamSessionId) {
                                FLog.w(TAG, "Ignoring late relay ACK for old stream session: " + sequenceNumber);
                                return;
                            }
                            bufferLock.writeLock().lock();
                            try {
                                relayUploadedSequences.add(sequenceNumber);
                                relayUploadedSequences.removeIf(seq -> seq < oldestSequence);
                                lastRelayUploadMs = System.currentTimeMillis();
                                String playlist = generateCloudPlaylist();
                                if (playlist != null) {
                                    uploader.uploadPlaylist(playlist, null);
                                }
                            } finally {
                                bufferLock.writeLock().unlock();
                            }
                        }

                        @Override
                        public void onError(String error) {
                            FLog.w(TAG, "⚠️ Relay delivery failed for segment " + sequenceNumber
                                    + "; playlist will not advertise it: " + error);
                        }
                    });
                }'''
    text = replace_once(text, re.escape(old_upload), new_upload, "relay upload callback", flags=re.DOTALL)

    start = text.index('    private String generateCloudPlaylist() {')
    end = text.index('    public static int getTargetDurationSeconds', start)
    replacement = '''    private String generateCloudPlaylist() {
        bufferLock.readLock().lock();
        try {
            List<FragmentData> fragments = new ArrayList<>();
            int validRangeStart = Math.max(1, fragmentSequence - BUFFER_SIZE + 1);

            for (FragmentData fragment : fragmentBuffer) {
                if (fragment != null
                        && fragment.sequenceNumber >= validRangeStart
                        && fragment.sequenceNumber <= fragmentSequence
                        && relayUploadedSequences.contains(fragment.sequenceNumber)) {
                    fragments.add(fragment);
                }
            }

            fragments.sort((a, b) -> Integer.compare(a.sequenceNumber, b.sequenceNumber));
            if (fragments.size() < 2 || initializationSegment == null) {
                return null;
            }

            // Only publish a contiguous acknowledged suffix. If segment N-1 is
            // missing at the relay, segment N must not be advertised yet.
            List<FragmentData> contiguous = new ArrayList<>();
            int expected = fragments.get(fragments.size() - 1).sequenceNumber;
            for (int i = fragments.size() - 1; i >= 0; i--) {
                FragmentData fragment = fragments.get(i);
                if (fragment.sequenceNumber != expected) {
                    break;
                }
                contiguous.add(0, fragment);
                expected--;
            }
            if (contiguous.size() < 2) {
                return null;
            }

            int startIdx = Math.max(0, contiguous.size() - 8);
            List<FragmentData> liveEdge = new ArrayList<>(contiguous.subList(startIdx, contiguous.size()));

            StringBuilder m3u8 = new StringBuilder();
            m3u8.append("#EXTM3U\\n");
            m3u8.append("#EXT-X-VERSION:7\\n");
            m3u8.append("#EXT-X-INDEPENDENT-SEGMENTS\\n");
            m3u8.append("#EXT-X-TARGETDURATION:").append(getTargetDurationSeconds(liveEdge)).append("\\n");
            m3u8.append("#EXT-X-MEDIA-SEQUENCE:").append(liveEdge.get(0).sequenceNumber).append("\\n");
            m3u8.append("#EXT-X-MAP:URI=\\"init.mp4\\"\\n");
            for (FragmentData fragment : liveEdge) {
                m3u8.append("#EXTINF:")
                    .append(String.format(java.util.Locale.US, "%.3f", fragment.getDurationSeconds()))
                    .append(",\\n");
                m3u8.append("seg-").append(fragment.sequenceNumber).append(".m4s\\n");
            }
            return m3u8.toString();
        } finally {
            bufferLock.readLock().unlock();
        }
    }

'''
    text = text[:start] + replacement + text[end:]
    REMOTE.write_text(text, encoding="utf-8")
    print("RemoteStreamManager: patched")


def patch_uploader():
    text = UPLOADER.read_text(encoding="utf-8")
    if "ONLINE_TV_RELAY_RETRY_V1" in text:
        print("CloudStreamUploader: already patched")
        return

    text = replace_once(
        text,
        r'(private static final long BACKOFF_AFTER_AUTH_FAILURE_MS = 30000;[^\n]*\n)',
        r'''\1
    // ONLINE_TV_RELAY_RETRY_V1: transient mobile-network failures must not
    // silently drop live HLS segments. Retry init, media and playlist uploads.
    private static final int MAX_UPLOAD_ATTEMPTS = 4;
    private static final long UPLOAD_RETRY_BASE_DELAY_MS = 750;
''',
        "uploader retry constants",
    )

    start = text.index('    private void uploadBytes(')
    end = text.index('    /**\n     * Actually perform the HTTP upload', start)
    replacement = '''    private void uploadBytes(String url, byte[] data, MediaType mediaType, @Nullable UploadCallback callback) {
        uploadBytesAttempt(url, data, mediaType, callback, 0);
    }

    private void uploadBytesAttempt(String url, byte[] data, MediaType mediaType,
                                    @Nullable UploadCallback callback, int attempt) {
        if (!isEnabled) {
            return;
        }

        String streamToken = authManager.getStreamToken();
        if (streamToken == null || authManager.isStreamTokenNearExpiry()) {
            FLog.i(TAG, "Stream token missing or near expiry, fetching...");
            authManager.getValidStreamTokenAsync(new CloudAuthManager.StreamTokenListener() {
                @Override
                public void onSuccess(String newStreamToken) {
                    doUploadWithToken(url, data, mediaType, newStreamToken,
                            retryAwareCallback(url, data, mediaType, callback, attempt));
                }

                @Override
                public void onError(String error) {
                    retryOrFail(url, data, mediaType, callback, attempt, "Stream token error: " + error);
                }
            });
        } else {
            doUploadWithToken(url, data, mediaType, streamToken,
                    retryAwareCallback(url, data, mediaType, callback, attempt));
        }
    }

    private UploadCallback retryAwareCallback(String url, byte[] data, MediaType mediaType,
                                               @Nullable UploadCallback finalCallback, int attempt) {
        return new UploadCallback() {
            @Override
            public void onSuccess() {
                if (finalCallback != null) finalCallback.onSuccess();
            }

            @Override
            public void onError(String error) {
                retryOrFail(url, data, mediaType, finalCallback, attempt, error);
            }
        };
    }

    private void retryOrFail(String url, byte[] data, MediaType mediaType,
                             @Nullable UploadCallback callback, int attempt, String error) {
        if (!isEnabled) return;
        if (attempt + 1 >= MAX_UPLOAD_ATTEMPTS) {
            FLog.e(TAG, "❌ Relay upload exhausted retries: " + error);
            if (callback != null) callback.onError(error);
            return;
        }
        final int nextAttempt = attempt + 1;
        final long delay = UPLOAD_RETRY_BASE_DELAY_MS << attempt;
        FLog.w(TAG, "⚠️ Relay upload retry " + nextAttempt + "/" + MAX_UPLOAD_ATTEMPTS
                + " in " + delay + "ms: " + error);
        uploadExecutor.execute(() -> {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (callback != null) callback.onError("Upload retry interrupted");
                return;
            }
            uploadBytesAttempt(url, data, mediaType, callback, nextAttempt);
        });
    }

'''
    text = text[:start] + replacement + text[end:]

    marker = '    /**\n     * Get upload statistics\n     */'
    public_methods = '''    /**
     * Public online-TV base URL used by the FadSec web player.
     * The dashboard exposes the same /stream/{deviceId}/ route.
     */
    @Nullable
    public String getPublicStreamUrl() {
        String deviceId = authManager.getDeviceId();
        if (deviceId == null || deviceId.trim().isEmpty()) return null;
        return "https://fadcam.fadseclab.com/stream/" + deviceId + "/";
    }

    @Nullable
    public String getPublicHlsUrl() {
        String base = getPublicStreamUrl();
        return base == null ? null : base + "live.m3u8";
    }

'''
    text = replace_once(text, re.escape(marker), public_methods + marker, "public online-TV URL methods")
    UPLOADER.write_text(text, encoding="utf-8")
    print("CloudStreamUploader: patched")


patch_remote_manager()
patch_uploader()
print("ONLINE_TV_RELAY_DELIVERY_V1 + ONLINE_TV_RELAY_RETRY_V1 applied")
