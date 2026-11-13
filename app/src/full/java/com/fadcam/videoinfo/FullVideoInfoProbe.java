package com.fadcam.videoinfo;

import android.net.Uri;

import androidx.annotation.Nullable;

import com.arthenica.ffmpegkit.FFprobeKit;
import com.arthenica.ffmpegkit.MediaInformation;
import com.arthenica.ffmpegkit.MediaInformationSession;
import com.arthenica.ffmpegkit.StreamInformation;
import com.fadcam.FLog;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * FFprobe-backed metadata provider for Full builds. Reached through the
 * VideoInfoProbe seam so the shared sheet never references ffmpeg classes.
 */
public class FullVideoInfoProbe implements VideoInfoProbe {

    private static final String TAG = "FullVideoInfoProbe";

    @Nullable
    @Override
    public FfprobeData probe(Uri videoUri) {
        if (videoUri == null) return null;
        FfprobeData data = new FfprobeData();
        try {
            String filePath = getFFprobePath(videoUri);
            MediaInformationSession session = FFprobeKit.getMediaInformation(filePath);
            MediaInformation info = session.getMediaInformation();
            if (info == null) {
                FLog.w(TAG, "FFprobeKit returned null MediaInformation");
                return null;
            }

            String durationStr = info.getDuration();
            if (durationStr != null) {
                try {
                    data.durationMs = (long) (Double.parseDouble(durationStr) * 1000);
                } catch (NumberFormatException e) {
                    FLog.w(TAG, "Failed to parse duration: " + durationStr);
                }
            }

            String bitrateStr = info.getBitrate();
            if (bitrateStr != null) {
                try {
                    data.bitrate = Long.parseLong(bitrateStr);
                } catch (NumberFormatException e) {
                    FLog.w(TAG, "Failed to parse bitrate: " + bitrateStr);
                }
            }

            List<StreamInformation> streams = info.getStreams();
            if (streams != null) {
                for (StreamInformation stream : streams) {
                    if (!"video".equals(stream.getType())) continue;

                    Long width = stream.getWidth();
                    Long height = stream.getHeight();
                    if (width != null && height != null && width > 0 && height > 0) {
                        data.width = width.intValue();
                        data.height = height.intValue();
                    }

                    String codecName = stream.getCodec();
                    if (codecName != null) {
                        data.codec = mapCodec(codecName);
                    }

                    String fpsStr = stream.getAverageFrameRate();
                    if (fpsStr == null || fpsStr.isEmpty()) fpsStr = stream.getRealFrameRate();
                    if (fpsStr != null && !fpsStr.isEmpty()) {
                        data.frameRate = parseFps(fpsStr);
                    }
                    break; // Only first video stream
                }
            }
        } catch (Exception e) {
            FLog.w(TAG, "FFprobe failed for " + videoUri, e);
            return null;
        }
        return data;
    }

    private String mapCodec(String codecName) {
        String c = codecName.toLowerCase(Locale.US);
        if (c.contains("h264") || c.contains("avc")) return "H.264 (AVC)";
        if (c.contains("hevc") || c.contains("h265")) return "H.265 (HEVC)";
        if (c.contains("vp8")) return "VP8";
        if (c.contains("vp9")) return "VP9";
        if (c.contains("av1")) return "AV1";
        return codecName.toUpperCase(Locale.US);
    }

    private String parseFps(String fpsStr) {
        try {
            if (fpsStr.contains("/")) {
                String[] parts = fpsStr.split("/");
                double num = Double.parseDouble(parts[0]);
                double den = Double.parseDouble(parts[1]);
                if (den > 0) return String.format(Locale.US, "%.2f fps", num / den);
            } else {
                return String.format(Locale.US, "%.2f fps", Double.parseDouble(fpsStr));
            }
        } catch (NumberFormatException e) {
            FLog.w(TAG, "Failed to parse frame rate: " + fpsStr);
        }
        return null;
    }

    /**
     * Gets the path for FFprobeKit. For content:// URIs: reconstructed file
     * path when possible, otherwise the SAF protocol prefix (saf:).
     */
    private String getFFprobePath(Uri videoUri) {
        if ("file".equals(videoUri.getScheme()) && videoUri.getPath() != null) {
            return videoUri.getPath();
        }
        String reconstructedPath = tryGetActualFilePath(videoUri);
        if (reconstructedPath != null) {
            File file = new File(reconstructedPath);
            if (file.exists() && file.canRead()) return reconstructedPath;
        }
        return "saf:" + videoUri;
    }

    @Nullable
    private String tryGetActualFilePath(Uri videoUri) {
        String path = videoUri.getPath();
        if (path == null || !path.contains(":")) return null;
        // SAF URIs: /tree/primary:FadCam/document/primary:FadCam/file.mp4
        int lastColonIndex = path.lastIndexOf(':');
        if (lastColonIndex >= 0 && lastColonIndex < path.length() - 1) {
            String relativePath = path.substring(lastColonIndex + 1);
            return "/storage/emulated/0/" + relativePath;
        }
        return null;
    }
}
