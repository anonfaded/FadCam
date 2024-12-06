package com.fadcam;

import android.util.Size;

public class Utils {
    public static int estimateBitrate(Size size, int fps) {
        long width = size.getWidth();
        long height = size.getHeight();
        return (int) (width * height * fps * Constants.RECORDING_COMPRESSION_FACTOR);
    }
}
