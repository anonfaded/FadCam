package com.fadcam;

import android.content.Context;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.widget.Toast;

import androidx.annotation.StringRes;

public class Utils {
    public static int estimateBitrate(Size resolution, int frameRate) {
        // Estimate bitrate based on resolution and frame rate
        int width = resolution.getWidth();
        int height = resolution.getHeight();
        
        // Base bitrate calculation (you can adjust these values)
        return width * height * frameRate / 8;
    }

    public static boolean isCodecSupported(String mimeType) {
        MediaCodecList codecList = new MediaCodecList(MediaCodecList.ALL_CODECS);
        MediaCodecInfo[] codecs = codecList.getCodecInfos();

        for (MediaCodecInfo codecInfo : codecs) {
            if (codecInfo.isEncoder()) {
                String[] supportedTypes = codecInfo.getSupportedTypes();
                for (String type : supportedTypes) {
                    if (type.equalsIgnoreCase(mimeType)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
    /**
     * Shows a toast message for 0.5 second duration (shorter than Android's default SHORT duration).
     * Example usage:  Utils.showQuickToast(this, R.string.video_recording_started);
     * @param context The context in which to show the toast
     * @param messageResId Resource ID of the string message to display
     */
    public static void showQuickToast(Context context, @StringRes int messageResId) {
        Toast toast = Toast.makeText(context, messageResId, Toast.LENGTH_SHORT);
        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(toast::cancel, 500); // 500ms = half second
        toast.show();
    }
}
