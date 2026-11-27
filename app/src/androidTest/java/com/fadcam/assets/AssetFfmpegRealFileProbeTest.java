package com.fadcam.assets;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class AssetFfmpegRealFileProbeTest {
    private static final String TAG = "ASSETSPIKE";
    @Test
    public void probesARealPhoneRecording() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AssetFfmpeg.ensureLoaded(ctx);
        // A file the device actually recorded, not a generated one.
        String[] candidates = {
            "/sdcard/DCIM/Camera/PXL_20260727_105145135.mp4",
            "/sdcard/DCIM/Camera/PXL_20260725_143730399.mp4",
        };
        for (String path : candidates) {
            java.io.File f = new java.io.File(path);
            if (!f.canRead()) { continue; }
            com.arthenica.ffmpegkit.MediaInformationSession s =
                com.arthenica.ffmpegkit.FFprobeKit.getMediaInformation(path);
            com.arthenica.ffmpegkit.MediaInformation info = s.getMediaInformation();
            System.out.println(TAG + " REALPROBE " + f.getName()
                + " readable=true duration=" + (info != null ? info.getDuration() : "NULL")
                + " parsed=" + (info != null));
            return;
        }
        System.out.println(TAG + " REALPROBE no readable recording found");
    }
}
