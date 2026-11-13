package com.fadcam.ui;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFprobeKit;
import com.arthenica.ffmpegkit.MediaInformation;
import com.arthenica.ffmpegkit.MediaInformationSession;
import com.arthenica.ffmpegkit.ReturnCode;
import com.fadcam.FLog;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Full-only ffmpeg-backed helpers (src/full). Loaded reflectively through
 * FeatureRegistry so shared (main) code never references ffmpeg-kit.
 * Moved verbatim from RecordsAdapter — behavior must stay identical.
 */
public final class RecordsFfmpegOps {

    private static final String TAG = "RecordsFfmpegOps";

    private RecordsFfmpegOps() {
    }

    /** FFprobe duration in ms, or -1 when unavailable/unreadable. */
    public static long probeDurationMs(Context context, Uri videoUri) {
        if (context == null || videoUri == null) return -1;
        try {
            if ("file".equals(videoUri.getScheme()) && videoUri.getPath() != null) {
                return probePath(context, videoUri.getPath());
            }
            if ("content".equals(videoUri.getScheme())) {
                try (ParcelFileDescriptor pfd = context.getContentResolver()
                        .openFileDescriptor(videoUri, "r")) {
                    if (pfd != null) {
                        return probePath(context, "/proc/self/fd/" + pfd.getFd());
                    }
                }
            }
        } catch (Throwable e) {
            FLog.w(TAG, "FFprobe duration failed for: " + videoUri, e);
        }
        return -1;
    }

    private static long probePath(Context context, String filePath) {
        try {
            MediaInformationSession session = FFprobeKit.getMediaInformation(filePath);
            MediaInformation info = session.getMediaInformation();
            if (info != null && info.getDuration() != null) {
                double durationSec = Double.parseDouble(info.getDuration());
                long durationMs = (long) (durationSec * 1000);
                FLog.d(TAG, "Duration from FFprobe: " + durationMs + "ms path=" + filePath);
                return durationMs;
            }
        } catch (Throwable e) {
            FLog.w(TAG, "FFprobe failed for path: " + filePath, e);
        }
        return -1;
    }

}
