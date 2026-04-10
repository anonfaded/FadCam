package com.fadcam.fadrec.ui;

import android.content.Context;
import android.os.Bundle;
import android.util.Size;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.fadcam.Constants;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.ui.OverlayNavUtil;
import com.fadcam.ui.picker.NumberInputBottomSheetFragment;
import com.fadcam.ui.picker.OptionItem;
import com.fadcam.ui.picker.PickerBottomSheetFragment;
import com.fadcam.FLog;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Screen Recording Settings — production-quality screen recording configuration
 * using dynamically queried device display capabilities, not hardcoded values.
 *
 * Resolution: derived from the device's native display resolution with standard
 * downscale factors, plus common target resolutions that fit within the native size.
 *
 * FPS: standard encoder frame rates (60/30/24) plus any additional display
 * refresh rates the device advertises (e.g. 90/120 on high-refresh screens).
 * MediaCodec can encode at these rates regardless of the display's physical refresh rate.
 *
 * Bitrate: VBR (Variable Bit Rate) / Auto mode as default, with optional manual
 * fixed bitrate. VBR lets MediaCodec adapt to content complexity for optimal
 * quality-to-file-size ratio — the industry standard for screen recording.
 *
 * Orientation: isolated from FadCam camera recording. Each mode remembers its own.
 */
public class ScreenRecordingSettingsFragment extends Fragment {

    private static final String TAG = "ScreenRecordingSettings";

    private static final String BITRATE_AUTO = "auto";
    private static final String BITRATE_MANUAL = "manual";

    private SharedPreferencesManager prefs;
    private TextView valueResolution;
    private TextView valueFrameRate;
    private TextView valueBitrate;
    private TextView valueOrientation;
    private TextView valueAudioSource;
    private TextView valueSplitting;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings_screen_recording, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        prefs = SharedPreferencesManager.getInstance(requireContext());

        valueResolution = view.findViewById(R.id.value_resolution);
        valueFrameRate = view.findViewById(R.id.value_framerate);
        valueBitrate = view.findViewById(R.id.value_bitrate);
        valueOrientation = view.findViewById(R.id.value_orientation);
        valueAudioSource = view.findViewById(R.id.value_audio_source);
        valueSplitting = view.findViewById(R.id.value_splitting);

        ImageView backBtn = view.findViewById(R.id.back_button);
        if (backBtn != null) {
            backBtn.setOnClickListener(v -> {
                if (getActivity() != null) {
                    OverlayNavUtil.dismiss(getActivity());
                }
            });
        }

        bindRowHandlers(view);
        refreshValues();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshValues();
    }

    private void bindRowHandlers(View root) {
        root.findViewById(R.id.row_resolution).setOnClickListener(v -> showResolutionPicker());
        root.findViewById(R.id.row_framerate).setOnClickListener(v -> showFrameRatePicker());
        root.findViewById(R.id.row_bitrate).setOnClickListener(v -> showBitrateModePicker());
        root.findViewById(R.id.row_orientation).setOnClickListener(v -> showOrientationPicker());
        root.findViewById(R.id.row_audio_source).setOnClickListener(v -> showAudioSourcePicker());
        root.findViewById(R.id.row_video_splitting).setOnClickListener(v -> showVideoSplittingPicker());
    }

    private void refreshValues() {
        if (!isAdded() || valueResolution == null) return;

        Size res = prefs.getScreenRecordingResolution();
        valueResolution.setText(res.getWidth() + "\u00d7" + res.getHeight());

        int fps = prefs.getScreenRecordingFrameRate();
        valueFrameRate.setText(fps + " fps");

        if (valueBitrate != null) {
            int br = prefs.getScreenRecordingBitrate();
            if (br <= 0) {
                valueBitrate.setText(getString(R.string.screen_rec_bitrate_mode_auto));
            } else {
                valueBitrate.setText((br / 1_000_000) + " Mbps");
            }
        }

        if (valueOrientation != null) {
            String orient = prefs.getScreenRecordingOrientation();
            boolean isLandscape = SharedPreferencesManager.ORIENTATION_LANDSCAPE.equals(orient);
            valueOrientation.setText(isLandscape
                    ? getString(R.string.screen_rec_orientation_landscape)
                    : getString(R.string.screen_rec_orientation_portrait));
        }

        if (valueAudioSource != null) {
            String audio = prefs.getScreenRecordingAudioSource();
            if (Constants.AUDIO_SOURCE_NONE.equals(audio)) {
                valueAudioSource.setText(getString(R.string.fadrec_audio_source_none));
            } else if (Constants.AUDIO_SOURCE_INTERNAL.equals(audio)) {
                valueAudioSource.setText(getString(R.string.fadrec_audio_source_internal));
            } else {
                valueAudioSource.setText(getString(R.string.fadrec_audio_source_mic));
            }
        }

        // Video splitting — uses shared prefs (same as FadCam)
        if (valueSplitting != null) {
            boolean enabled = prefs.isVideoSplittingEnabled();
            int mb = prefs.getVideoSplitSizeMb();
            if (!enabled) {
                valueSplitting.setText("Disabled");
            } else {
                String sizeLabel;
                if (mb == 500) sizeLabel = "500 MB";
                else if (mb == 1024) sizeLabel = "1 GB";
                else if (mb == 2048) sizeLabel = "2 GB";
                else if (mb == 4096) sizeLabel = "4 GB";
                else sizeLabel = "Custom (" + mb + " MB)";
                valueSplitting.setText(sizeLabel + " (FadCam + FadRec)");
            }
        }
    }

    // ── Resolution: dynamically queried from device display ──

    private void showResolutionPicker() {
        if (!isAdded() || getActivity() == null) return;

        List<Size> resolutions = getSupportedResolutions();
        Size current = prefs.getScreenRecordingResolution();
        String currentId = current.getWidth() + "x" + current.getHeight();

        ArrayList<OptionItem> items = new ArrayList<>();
        for (Size s : resolutions) {
            String id = s.getWidth() + "x" + s.getHeight();
            items.add(new OptionItem(id, buildResolutionLabel(s)));
        }

        final String resultKey = "picker_result_screen_resolution";
        getParentFragmentManager().setFragmentResultListener(resultKey, getViewLifecycleOwner(), (key, bundle) -> {
            String sel = bundle.getString(PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if (sel != null && !sel.equals(currentId)) {
                try {
                    String[] parts = sel.split("x");
                    int w = Integer.parseInt(parts[0]);
                    int h = Integer.parseInt(parts[1]);
                    prefs.setScreenRecordingResolution(w, h);
                    refreshValues();
                } catch (Exception ignored) {
                }
            }
        });

        PickerBottomSheetFragment sheet = PickerBottomSheetFragment.newInstance(
                getString(R.string.screen_rec_resolution), items, currentId, resultKey,
                "Resolutions supported by the device's display and encoder");
        sheet.show(getParentFragmentManager(), "screen_resolution_picker");
    }

    // ── FPS: standard encoder rates + device display rates ──

    private void showFrameRatePicker() {
        if (!isAdded() || getActivity() == null) return;

        List<Integer> frameRates = getSupportedFrameRates();
        int current = prefs.getScreenRecordingFrameRate();
        String currentId = String.valueOf(current);

        ArrayList<OptionItem> items = new ArrayList<>();
        for (int fps : frameRates) {
            items.add(new OptionItem(String.valueOf(fps), fps + " fps"));
        }

        final String resultKey = "picker_result_screen_framerate";
        getParentFragmentManager().setFragmentResultListener(resultKey, getViewLifecycleOwner(), (key, bundle) -> {
            String sel = bundle.getString(PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if (sel != null && !sel.equals(currentId)) {
                try {
                    int fps = Integer.parseInt(sel);
                    prefs.setScreenRecordingFrameRate(fps);
                    refreshValues();
                } catch (Exception ignored) {
                }
            }
        });

        PickerBottomSheetFragment sheet = PickerBottomSheetFragment.newInstance(
                getString(R.string.screen_rec_framerate), items, currentId, resultKey,
                getString(R.string.note_framerate, Constants.DEFAULT_SCREEN_RECORDING_FPS));
        sheet.show(getParentFragmentManager(), "screen_framerate_picker");
    }

    // ── Bitrate: VBR (Auto) default, with manual CBR option ──

    private void showBitrateModePicker() {
        if (!isAdded() || getActivity() == null) return;

        ArrayList<OptionItem> items = new ArrayList<>();
        items.add(new OptionItem(BITRATE_AUTO,
                getString(R.string.screen_rec_bitrate_mode_auto),
                "Let the encoder adapt bitrate dynamically for best quality"));
        items.add(new OptionItem(BITRATE_MANUAL,
                getString(R.string.screen_rec_bitrate_mode_manual),
                "Fixed bitrate: " + (prefs.getScreenRecordingBitrate() > 0
                        ? (prefs.getScreenRecordingBitrate() / 1_000_000) + " Mbps"
                        : "8 Mbps (default)")));

        String currentId = prefs.getScreenRecordingBitrate() > 0 ? BITRATE_MANUAL : BITRATE_AUTO;

        final String resultKey = "picker_result_screen_bitrate_mode";
        getParentFragmentManager().setFragmentResultListener(resultKey, getViewLifecycleOwner(), (key, bundle) -> {
            String sel = bundle.getString(PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if (sel == null) return;
            if (BITRATE_AUTO.equals(sel)) {
                prefs.setScreenRecordingBitrate(0); // 0 = auto/VBR
                refreshValues();
            } else if (BITRATE_MANUAL.equals(sel)) {
                showBitrateManualInput();
            }
        });

        PickerBottomSheetFragment sheet = PickerBottomSheetFragment.newInstance(
                getString(R.string.screen_rec_bitrate), items, currentId, resultKey,
                getString(R.string.screen_rec_bitrate_helper));
        sheet.show(getParentFragmentManager(), "screen_bitrate_mode_picker");
    }

    private void showBitrateManualInput() {
        if (!isAdded() || getActivity() == null) return;

        int currentMbps = prefs.getScreenRecordingBitrate() / 1_000_000;
        if (currentMbps <= 0) currentMbps = 8;

        final String resultKey = "picker_result_screen_bitrate_value";
        getParentFragmentManager().setFragmentResultListener(resultKey, getViewLifecycleOwner(), (key, bundle) -> {
            if (bundle.containsKey(NumberInputBottomSheetFragment.RESULT_NUMBER)) {
                int val = bundle.getInt(NumberInputBottomSheetFragment.RESULT_NUMBER);
                prefs.setScreenRecordingBitrate(val * 1_000_000);
                refreshValues();
            }
        });

        NumberInputBottomSheetFragment sheet = NumberInputBottomSheetFragment.newInstance(
                getString(R.string.screen_rec_bitrate), 1, 50, currentMbps,
                getString(R.string.screen_rec_bitrate_range),
                3, 20,
                getString(R.string.screen_rec_bitrate_low_hint),
                getString(R.string.screen_rec_bitrate_high_hint),
                resultKey);
        sheet.show(getParentFragmentManager(), "screen_bitrate_input");
    }

    // ── Orientation: isolated from camera recording ──

    private void showOrientationPicker() {
        if (!isAdded() || getActivity() == null) return;

        String current = prefs.getScreenRecordingOrientation();

        ArrayList<OptionItem> items = new ArrayList<>();
        items.add(new OptionItem(SharedPreferencesManager.ORIENTATION_PORTRAIT,
                getString(R.string.screen_rec_orientation_portrait)));
        items.add(new OptionItem(SharedPreferencesManager.ORIENTATION_LANDSCAPE,
                getString(R.string.screen_rec_orientation_landscape)));

        final String resultKey = "picker_result_screen_orientation";
        getParentFragmentManager().setFragmentResultListener(resultKey, getViewLifecycleOwner(), (key, bundle) -> {
            String sel = bundle.getString(PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if (sel != null && !sel.equals(current)) {
                prefs.setScreenRecordingOrientation(sel);
                refreshValues();
            }
        });

        PickerBottomSheetFragment sheet = PickerBottomSheetFragment.newInstance(
                getString(R.string.screen_rec_orientation), items, current, resultKey,
                "Applies to the next screen recording session");
        sheet.show(getParentFragmentManager(), "screen_orientation_picker");
    }

    // ── Audio Source ──

    private void showAudioSourcePicker() {
        if (!isAdded() || getActivity() == null) return;

        String current = prefs.getScreenRecordingAudioSource();

        ArrayList<OptionItem> items = new ArrayList<>();
        items.add(new OptionItem(Constants.AUDIO_SOURCE_MIC,
                getString(R.string.fadrec_audio_source_mic),
                "Record audio from the device microphone"));
        items.add(new OptionItem(Constants.AUDIO_SOURCE_INTERNAL,
                getString(R.string.fadrec_audio_source_internal),
                "Record internal device audio (Android 10+)"));
        items.add(new OptionItem(Constants.AUDIO_SOURCE_NONE,
                getString(R.string.fadrec_audio_source_none),
                "No audio will be recorded"));

        final String resultKey = "picker_result_screen_audio_source";
        getParentFragmentManager().setFragmentResultListener(resultKey, getViewLifecycleOwner(), (key, bundle) -> {
            String sel = bundle.getString(PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if (sel != null && !sel.equals(current)) {
                prefs.setScreenRecordingAudioSource(sel);
                refreshValues();
            }
        });

        PickerBottomSheetFragment sheet = PickerBottomSheetFragment.newInstance(
                getString(R.string.fadrec_audio_source_title), items, current, resultKey,
                getString(R.string.fadrec_audio_source_choose));
        sheet.show(getParentFragmentManager(), "screen_audio_source_picker");
    }

    // ── Video Splitting (reuses exact same logic & prefs as FadCam) ──

    private void showVideoSplittingPicker() {
        if (!isAdded() || getActivity() == null) return;

        boolean enabled = prefs.isVideoSplittingEnabled();
        int mb = prefs.getVideoSplitSizeMb();
        String sizeLabel;
        if (mb == 500) sizeLabel = "500 MB";
        else if (mb == 1024) sizeLabel = "1 GB";
        else if (mb == 2048) sizeLabel = "2 GB";
        else if (mb == 4096) sizeLabel = "4 GB";
        else sizeLabel = "Custom (" + mb + " MB)";

        ArrayList<OptionItem> items = new ArrayList<>();
        items.add(new OptionItem("size", "Change Split Size (Current: " + sizeLabel + ")"));

        final String resultKey = "picker_result_screen_split";
        getParentFragmentManager().setFragmentResultListener(resultKey, getViewLifecycleOwner(), (key, bundle) -> {
            String sel = bundle.getString(PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if (sel == null) return;

            if ("size".equals(sel)) {
                showVideoSplitSizePicker();
            }
        });

        PickerBottomSheetFragment sheet = PickerBottomSheetFragment.newInstanceWithSwitchDependencies(
                "Video Splitting", items, null, resultKey,
                getString(R.string.video_splitting_description),
                "Video Splitting",
                enabled, new java.util.ArrayList<String>(java.util.Arrays.asList("size")));
        sheet.show(getParentFragmentManager(), "screen_video_splitting_picker");
    }

    private void showVideoSplitSizePicker() {
        if (!isAdded() || getActivity() == null) return;
        if (!prefs.isVideoSplittingEnabled()) return;

        final int[] presetMb = {500, 1024, 2048, 4096, -1};
        ArrayList<OptionItem> items = new ArrayList<>();
        for (int mb : presetMb) {
            if (mb == -1) {
                items.add(new OptionItem("custom", "Custom..."));
            } else {
                items.add(new OptionItem(String.valueOf(mb),
                        (mb == 1024 ? "1 GB" : mb == 2048 ? "2 GB" : mb == 4096 ? "4 GB" : mb + " MB")));
            }
        }

        int current = prefs.getVideoSplitSizeMb();
        String currentId = null;
        for (int mb : presetMb) {
            if (mb == current) {
                currentId = String.valueOf(mb);
                break;
            }
        }

        final String resultKey = "picker_result_screen_split_size";
        getParentFragmentManager().setFragmentResultListener(resultKey, getViewLifecycleOwner(), (key, bundle) -> {
            String sel = bundle.getString(PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if (sel == null) return;
            if ("custom".equals(sel)) {
                showCustomSplitSizeInput();
            } else {
                try {
                    int mbVal = Integer.parseInt(sel);
                    prefs.setVideoSplitSizeMb(mbVal);
                    refreshValues();
                } catch (Exception ignored) {}
            }
        });

        PickerBottomSheetFragment sheet = PickerBottomSheetFragment.newInstance(
                getString(R.string.video_splitting_title), items, currentId, resultKey,
                getString(R.string.video_splitting_description));
        sheet.show(getParentFragmentManager(), "screen_split_size_picker");
    }

    private void showCustomSplitSizeInput() {
        if (!isAdded() || getActivity() == null) return;

        int current = prefs.getVideoSplitSizeMb();
        if (current == 500 || current == 1024 || current == 2048 || current == 4096) current = 2048;

        final String resultKey = "picker_result_screen_split_custom";
        getParentFragmentManager().setFragmentResultListener(resultKey, getViewLifecycleOwner(), (key, bundle) -> {
            if (bundle.containsKey(NumberInputBottomSheetFragment.RESULT_NUMBER)) {
                int mb = bundle.getInt(NumberInputBottomSheetFragment.RESULT_NUMBER);
                prefs.setVideoSplitSizeMb(mb);
                refreshValues();
            }
        });

        NumberInputBottomSheetFragment sheet = NumberInputBottomSheetFragment.newInstance(
                "Custom Split Size (MB)", 10, 102400, current, "10 - 102400", 0, 0,
                null, null, resultKey);
        sheet.show(getParentFragmentManager(), "screen_split_custom_input");
    }

    // ── Hardware query helpers ──

    /**
     * Returns resolutions the device display supports for screen recording.
     * Starts from the native display resolution, adds standard downscale factors,
     * and standard target resolutions that fit within the native size.
     * This mirrors how professional screen recorders (e.g. AZ Screen Recorder,
     * Mobizen) handle resolution selection.
     */
    private List<Size> getSupportedResolutions() {
        List<Size> result = new ArrayList<>();
        Context ctx = getContext();
        if (ctx == null) return result;

        android.util.DisplayMetrics metrics = ctx.getResources().getDisplayMetrics();
        int nativeW = metrics.widthPixels;
        int nativeH = metrics.heightPixels;

        // Fallback: query WindowManager if resource metrics are too small
        if (nativeW < 400 || nativeH < 400) {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                Display display = wm.getDefaultDisplay();
                if (display != null) {
                    android.util.DisplayMetrics wmMetrics = new android.util.DisplayMetrics();
                    display.getRealMetrics(wmMetrics);
                    if (wmMetrics.widthPixels > 0 && wmMetrics.heightPixels > 0) {
                        nativeW = wmMetrics.widthPixels;
                        nativeH = wmMetrics.heightPixels;
                    }
                }
            }
        }

        if (nativeW < 400 || nativeH < 400) {
            nativeW = 1920;
            nativeH = 1080;
        }

        // Landscape-ordered (longer side first) for consistency
        int longest = Math.max(nativeW, nativeH);
        int shortest = Math.min(nativeW, nativeH);

        Set<String> seen = new LinkedHashSet<>();

        // 1. Native display resolution
        addIfUnique(result, seen, longest, shortest);

        // 2. Downscale factors from native (aligned to multiples of 8 for codec)
        double[] factors = {0.75, 0.5, 0.35};
        for (double f : factors) {
            int w = (Math.round(longest * (float) f) / 8) * 8;
            int h = (Math.round(shortest * (float) f) / 8) * 8;
            if (w >= 640 && h >= 360) {
                addIfUnique(result, seen, w, h);
            }
        }

        // 3. Standard target resolutions that fit within the native size
        int[][] standards = {
                {3840, 2160}, // 4K
                {2560, 1440}, // 2K / QHD
                {1920, 1080}, // FHD
                {1280, 720},  // HD
                {854, 480},   // 480p
        };
        for (int[] s : standards) {
            if (s[0] <= longest && s[1] <= shortest) {
                addIfUnique(result, seen, s[0], s[1]);
            }
        }

        if (result.isEmpty()) {
            result.add(new Size(nativeW, nativeH));
        }

        FLog.d(TAG, "Supported screen recording resolutions: " + result.size() + " options (native: " + longest + "x" + shortest + ")");
        return result;
    }

    /**
     * Returns frame rates suitable for screen recording.
     * Standard encoder rates (60, 30, 24) are always available because MediaCodec
     * can encode at any rate regardless of display refresh rate. Additionally,
     * we surface any device-specific display rates (90, 120) for completeness.
     */
    private List<Integer> getSupportedFrameRates() {
        Set<Integer> result = new LinkedHashSet<>();

        // Standard encoder rates — always available via MediaCodec
        result.add(60);
        result.add(30);
        result.add(24);

        // Device display rates (optional, for high-refresh screens)
        Context ctx = getContext();
        if (ctx != null) {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                Display display = wm.getDefaultDisplay();
                if (display != null) {
                    int rounded = Math.round(display.getRefreshRate());
                    if (rounded >= 24 && rounded <= 120) {
                        result.add(rounded);
                    }
                }
            }
        }

        List<Integer> sorted = new ArrayList<>(result);
        java.util.Collections.sort(sorted, java.util.Collections.reverseOrder());
        return sorted;
    }

    private void addIfUnique(List<Size> list, Set<String> seen, int w, int h) {
        String key = w + "x" + h;
        if (!seen.contains(key)) {
            seen.add(key);
            list.add(new Size(w, h));
        }
    }

    private String buildResolutionLabel(Size s) {
        int w = s.getWidth();
        if (w >= 3840) return "4K \u00b7 " + Math.max(w, s.getHeight()) + "p";
        if (w >= 2560) return "2K \u00b7 " + Math.max(w, s.getHeight()) + "p";
        if (w >= 1920) return "FHD \u00b7 " + Math.max(w, s.getHeight()) + "p";
        if (w >= 1280) return "HD \u00b7 " + Math.max(w, s.getHeight()) + "p";
        return Math.max(w, s.getHeight()) + "p";
    }
}
