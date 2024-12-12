package com.fadcam;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.util.Size;

public class Utils {
    public static int estimateBitrate(Size size, int fps) {
        long width = size.getWidth();
        long height = size.getHeight();
        return (int) (width * height * fps * Constants.RECORDING_COMPRESSION_FACTOR);
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
}
