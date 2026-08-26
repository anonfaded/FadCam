from pathlib import Path
import re


def replace_once(path: str, old: str, new: str, label: str):
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected one anchor, found {count}')
    p.write_text(text.replace(old, new, 1))


controller = Path('app/src/main/java/com/fadcam/streaming/PublicStreamController.java')
controller.parent.mkdir(parents=True, exist_ok=True)
controller.write_text('''package com.fadcam.streaming;

import android.content.Context;
import android.content.SharedPreferences;

/** Producer-controlled Internet visibility for the FadCam stream. */
public final class PublicStreamController {
    private static final String PREFS = "FadCamCloudPrefs";
    private static final String KEY_PUBLIC_ONLINE = "public_stream_online";

    private PublicStreamController() {}

    public static boolean isOnline(Context context) {
        if (context == null) return false;
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_PUBLIC_ONLINE, false);
    }

    public static void setOnline(Context context, boolean online) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_PUBLIC_ONLINE, online).apply();
        CloudStreamUploader.getInstance(app).setPublicOnline(online);
        RemoteStreamManager.getInstance().invalidateStatusCache();
    }
}
''')

uploader = 'app/src/main/java/com/fadcam/streaming/CloudStreamUploader.java'
replace_once(
    uploader,
    '    private boolean isEnabled = false;\n',
    '    private boolean isEnabled = false;\n    private volatile boolean publicOnline = false;\n',
    'uploader state')
replace_once(
    uploader,
    '''    public boolean isEnabled() {
        return isEnabled;
    }
''',
    '''    public boolean isEnabled() {
        return isEnabled;
    }

    /** Producer-controlled Internet publication gate. */
    public synchronized void setPublicOnline(boolean online) {
        this.publicOnline = online;
        if (online) {
            this.initSegmentUploaded = false;
            byte[] init = RemoteStreamManager.getInstance().getInitializationSegment();
            if (init != null && isEnabled) uploadInitSegment(init, null);
        }
        FLog.i(TAG, "Public Internet stream " + (online ? "ON" : "OFF"));
    }

    public boolean isPublicOnline() {
        return publicOnline;
    }
''',
    'uploader public gate')
replace_once(
    uploader,
    '    public void uploadInitSegment(byte[] initData, @Nullable UploadCallback callback) {\n        if (!isEnabled) {',
    '    public void uploadInitSegment(byte[] initData, @Nullable UploadCallback callback) {\n        if (!isEnabled || !publicOnline) {',
    'init upload gate')
replace_once(
    uploader,
    '    public void uploadSegment(int sequenceNumber, byte[] segmentData, @Nullable UploadCallback callback) {\n        if (!isEnabled) {',
    '    public void uploadSegment(int sequenceNumber, byte[] segmentData, @Nullable UploadCallback callback) {\n        if (!isEnabled || !publicOnline) {',
    'segment upload gate')
replace_once(
    uploader,
    '    public void uploadPlaylist(String playlistContent, @Nullable UploadCallback callback) {\n        if (!isEnabled) {',
    '    public void uploadPlaylist(String playlistContent, @Nullable UploadCallback callback) {\n        if (!isEnabled || !publicOnline) {',
    'playlist upload gate')

manager = 'app/src/main/java/com/fadcam/streaming/RemoteStreamManager.java'
replace_once(
    manager,
    '    public void setStreamingEnabled(boolean enabled) {\n',
    '''    /** Producer-controlled Internet publication state. */
    public boolean isPublicStreamOnline() {
        return context != null && PublicStreamController.isOnline(context);
    }

    public void setPublicStreamOnline(boolean online) {
        if (context != null) PublicStreamController.setOnline(context, online);
    }

    public void setStreamingEnabled(boolean enabled) {
''',
    'manager public API')
replace_once(
    manager,
    '''            if (!enabled) {
                clearBuffer();
            }''',
    '''            if (!enabled) {
                clearBuffer();
                if (context != null) PublicStreamController.setOnline(context, false);
            }''',
    'manager stop gate')
replace_once(
    manager,
    '''            cachedStatusJson = result;
            lastStatusJsonTime = currentTime;''',
    '''            try {
                JSONObject statusObject = new JSONObject(result);
                statusObject.put("publicOnline", isPublicStreamOnline());
                result = statusObject.toString();
            } catch (Exception statusPatchError) {
                FLog.w(TAG, "Could not append publicOnline status: " + statusPatchError.getMessage());
            }
            cachedStatusJson = result;
            lastStatusJsonTime = currentTime;''',
    'manager status payload')

remote = Path('app/src/main/java/com/fadcam/ui/RemoteFragment.java')
s = remote.read_text()
if 'import com.fadcam.streaming.PublicStreamController;' not in s:
    s = s.replace(
        'import com.fadcam.streaming.RemoteStreamService;\n',
        'import com.fadcam.streaming.RemoteStreamService;\nimport com.fadcam.streaming.PublicStreamController;\n', 1)
pattern = re.compile(r'    private void setupCloudStreaming\(\) \{.*?\n    \}', re.S)
match = pattern.search(s)
if not match:
    raise SystemExit('RemoteFragment setupCloudStreaming not found')
method = '''    private void setupCloudStreaming() {
        if (cloudStreamingRow == null || cloudStreamingToggle == null) return;
        cloudStreamingRow.setVisibility(View.VISIBLE);
        cloudStreamingRow.setOnClickListener(v -> cloudStreamingToggle.performClick());
        if (cloudStreamingStatus != null) cloudStreamingStatus.setText("Internet publication");

        cloudStreamingToggle.setChecked(PublicStreamController.isOnline(requireContext()));
        updatePublicOnlineStatus();
        cloudStreamingToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                boolean cloudAvailable = cloudAuthManager.isLinked()
                        && (cloudAuthManager.hasValidToken() || cloudAuthManager.getRefreshToken() != null);
                if (!cloudAvailable) {
                    buttonView.setChecked(false);
                    Toast.makeText(requireContext(),
                            "Link FadSec ID first to publish the stream worldwide",
                            Toast.LENGTH_LONG).show();
                    onCloudAccountClick();
                    return;
                }
                setStreamingMode(MODE_CLOUD);
                PublicStreamController.setOnline(requireContext(), true);
                if (!RemoteStreamManager.getInstance().isStreamingEnabled()) startStreaming();
                Toast.makeText(requireContext(),
                        "Stream is ONLINE — public link enabled", Toast.LENGTH_LONG).show();
            } else {
                PublicStreamController.setOnline(requireContext(), false);
                Toast.makeText(requireContext(),
                        "Public stream OFF — local server can remain available", Toast.LENGTH_SHORT).show();
            }
            updatePublicOnlineStatus();
            updateUI();
        });
    }

    private void updatePublicOnlineStatus() {
        if (cloudStreamingStatus == null || getContext() == null) return;
        boolean online = PublicStreamController.isOnline(requireContext());
        cloudStreamingStatus.setText(online
                ? "ONLINE • Public Internet link enabled"
                : "OFF • Local server only");
        cloudStreamingStatus.setTextColor(online ? 0xFF4CAF50 : 0xFFAAAAAA);
    }'''
s = s[:match.start()] + method + s[match.end():]
replace_marker = '''        RemoteStreamManager.getInstance().setStreamingEnabled(false);
        
        streamStartTime = 0;'''
replace_value = '''        RemoteStreamManager.getInstance().setStreamingEnabled(false);
        PublicStreamController.setOnline(requireContext(), false);
        if (cloudStreamingToggle != null) cloudStreamingToggle.setChecked(false);
        updatePublicOnlineStatus();
        
        streamStartTime = 0;'''
if s.count(replace_marker) != 1:
    raise SystemExit('RemoteFragment stop marker missing')
s = s.replace(replace_marker, replace_value, 1)
remote.write_text(s)

server = 'app/src/main/java/com/fadcam/streaming/LiveM3U8Server.java'
replace_once(
    server,
    '''            } else if ("/status".equals(uri)) {
                FLog.d(TAG, "🌐 [/status] Dashboard request from " + clientIP + " User-Agent: " + userAgent);
                response = serveStatus();''',
    '''            } else if ("/status".equals(uri)) {
                FLog.d(TAG, "🌐 [/status] Dashboard request from " + clientIP + " User-Agent: " + userAgent);
                response = serveStatus();
            } else if ("/health".equals(uri)) {
                response = jsonResponse(Response.Status.OK, "{\\"ok\\":true,\\"server\\":\\"FadCam\\",\\"streaming\\":"
                        + streamManager.isStreamingEnabled() + ",\\"recording\\":"
                        + streamManager.isRecording() + ",\\"publicOnline\\":"
                        + streamManager.isPublicStreamOnline() + "}");
            } else if ("/api/stream/info".equals(uri)) {
                response = jsonResponse(Response.Status.OK, streamManager.getStatusJson());''',
    'HLS health endpoints')

# Test source-level persistence policy without requiring an emulator.
test = Path('app/src/test/java/com/fadcam/streaming/PublicStreamControllerTest.java')
test.parent.mkdir(parents=True, exist_ok=True)
test.write_text('''package com.fadcam.streaming;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import org.junit.Test;

public class PublicStreamControllerTest {
    @Test
    public void statePersists() {
        Context context = ApplicationProvider.getApplicationContext();
        PublicStreamController.setOnline(context, false);
        assertFalse(PublicStreamController.isOnline(context));
        PublicStreamController.setOnline(context, true);
        assertTrue(PublicStreamController.isOnline(context));
        PublicStreamController.setOnline(context, false);
        assertFalse(PublicStreamController.isOnline(context));
    }
}
''')
print('Public streaming patch source generation: PASS')
