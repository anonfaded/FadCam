package com.fadcam.utils;

import com.fadcam.BuildConfig;
import com.fadcam.R;

/**
 * Brand artwork that differs between the two product lines.
 *
 * <p>Both logos already exist as drawables; this only decides which one a given build should
 * use. Keep the resource lookup here so the watermark, the photo stamp and the settings
 * previews cannot drift apart — they previously each hardcoded the Full logo, which meant a
 * Lite recording was stamped with the Full brand.
 */
public final class BrandAssets {

    private BrandAssets() {
    }

    /**
     * The FadCam wordmark for this build: the Lite artwork (the same one the Lite home header
     * shows) or the Full one.
     */
    public static int logo() {
        return BuildConfig.LITE_EDITION
                ? R.drawable.fadcam_lite_header_icon
                : R.drawable.menu_icon_unknown;
    }
}
