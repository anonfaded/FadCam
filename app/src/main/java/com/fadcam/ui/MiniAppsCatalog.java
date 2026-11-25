package com.fadcam.ui;

import android.content.Context;

import com.fadcam.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared catalog of the Mini Apps advertised in Settings and in the onboarding
 * "Coming Soon" marquee.
 *
 * <p>The implemented flags mirror the badges wired in
 * {@link SettingsHomeFragment#setupMiniAppCards}: Torch and QR Scanner are live,
 * every other entry still carries the "Soon" badge and has no implementation
 * class in the app.</p>
 */
public final class MiniAppsCatalog {

    public static final class Entry {
        public final String id;
        public final int titleRes;
        public final boolean implemented;

        private Entry(String id, int titleRes, boolean implemented) {
            this.id = id;
            this.titleRes = titleRes;
            this.implemented = implemented;
        }
    }

    private static final Entry[] ENTRIES = {
            new Entry("torch", R.string.mini_app_torch_title, true),
            new Entry("qr_scanner", R.string.mini_app_qr_scanner_title, true),
            new Entry("compass", R.string.mini_app_compass_title, false),
            new Entry("sound_meter", R.string.mini_app_sound_meter_title, false),
            new Entry("sensor_dashboard", R.string.mini_app_sensor_dashboard_title, false),
            new Entry("speedometer", R.string.mini_app_speedometer_title, false),
            new Entry("clinometer", R.string.mini_app_clinometer_title, false),
            new Entry("pedometer", R.string.mini_app_pedometer_title, false),
            new Entry("metal_detector", R.string.mini_app_metal_detector_title, false),
            new Entry("parking_marker", R.string.mini_app_parking_marker_title, false),
            new Entry("qr_generator", R.string.mini_app_qr_generator_title, false)
    };

    private MiniAppsCatalog() {
    }

    /** Localized display names of every Mini App still marked Coming Soon. */
    public static List<String> comingSoonTitles(Context context) {
        List<String> titles = new ArrayList<>();
        for (Entry entry : ENTRIES) {
            if (!entry.implemented) {
                titles.add(context.getString(entry.titleRes));
            }
        }
        return titles;
    }
}
