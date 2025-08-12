package com.fadcam.ui;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.fadcam.CameraType;
import com.fadcam.Constants;
import com.fadcam.MainActivity;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.VideoCodec;
import com.fadcam.ui.OverlayNavUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Collections;
import android.hardware.camera2.params.StreamConfigurationMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import com.fadcam.Utils;

/**
 * VideoSettingsFragment
 * Row based extraction of video recording related settings from monolithic
 * SettingsFragment.
 * Keeps preference keys and semantics identical; only UI container changes.
 */
public class VideoSettingsFragment extends Fragment {

    private static final String TAG = "VideoSettingsFragment";

    private SharedPreferencesManager prefs;

    private TextView valueCamera;
    private TextView valueLens;
    private TextView valueResolution;
    private TextView valueFrameRate;
    private TextView valueCodec;
    private TextView valueBitrate;
    private TextView valueOrientation;
    private TextView valueLocationEmbed;
    // Newly migrated rows
    private TextView valueZoomRatio;
    private TextView valueVideoSplitEnabled;
    private TextView valueVideoSplitSize; // removed from layout (merged)
    private TextView valueBitrateHelper; // now null since helper removed
    // Optional helper text not in current layout

    // Cached dynamic data
    private List<Size> cachedResolutionsFront = new ArrayList<>();
    private List<Size> cachedResolutionsBack = new ArrayList<>();
    private List<Integer> cachedFpsFront = new ArrayList<>();
    private List<Integer> cachedFpsBack = new ArrayList<>();
    private List<VideoCodec> cachedCodecs = new ArrayList<>();
    private List<CameraIdInfo> availableBackCameras = new ArrayList<>();

    // Bitrate preference keys (mirroring legacy helper logic)
    private static final String PREF_BITRATE_MODE_CUSTOM = "bitrate_mode_custom"; // false default
    private static final String PREF_BITRATE_CUSTOM_VALUE = "bitrate_custom_value"; // stored in kbps

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings_video, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // -------------- Fix Start for this method(onViewCreated)-----------
        prefs = SharedPreferencesManager.getInstance(requireContext());
        bindViews(view);
        bindRowHandlers(view);
        // Detect back cameras early
        detectAvailableBackCameras();
        // Preload codecs
        cachedCodecs = getCompatiblesVideoCodecs();
        refreshAllValues();
        View back = view.findViewById(R.id.back_button);
        if (back != null) {
            back.setOnClickListener(v -> OverlayNavUtil.dismiss(requireActivity()));
        }
        // -------------- Fix Ended for this method(onViewCreated)-----------
    }

    private void bindViews(View root) {
        valueCamera = root.findViewById(R.id.value_camera_type);
        valueLens = root.findViewById(R.id.value_lens);
        valueResolution = root.findViewById(R.id.value_resolution);
        valueFrameRate = root.findViewById(R.id.value_framerate);
        valueCodec = root.findViewById(R.id.value_codec);
        valueBitrate = root.findViewById(R.id.value_bitrate);
        valueOrientation = root.findViewById(R.id.value_orientation);
        valueLocationEmbed = root.findViewById(R.id.value_location_embed);
        valueZoomRatio = root.findViewById(R.id.value_zoom_ratio);
        valueVideoSplitEnabled = root.findViewById(R.id.value_video_split_enabled);
        valueVideoSplitSize = null; // merged design
        valueBitrateHelper = null; // helper removed from layout
    }

    private void bindRowHandlers(View root) {
        root.findViewById(R.id.row_camera_type).setOnClickListener(v -> showCameraBottomSheet());
        root.findViewById(R.id.row_lens).setOnClickListener(v -> showLensBottomSheet());
        root.findViewById(R.id.row_resolution).setOnClickListener(v -> showResolutionBottomSheet());
        root.findViewById(R.id.row_framerate).setOnClickListener(v -> showFrameRateBottomSheet());
        root.findViewById(R.id.row_codec).setOnClickListener(v -> showCodecBottomSheet());
        root.findViewById(R.id.row_bitrate).setOnClickListener(v -> showBitrateBottomSheet());
        root.findViewById(R.id.row_orientation).setOnClickListener(v -> showOrientationBottomSheet());
        View zr = root.findViewById(R.id.row_zoom_ratio);
        if (zr != null)
            zr.setOnClickListener(v -> showZoomRatioBottomSheet());
        View splitRow = root.findViewById(R.id.row_video_splitting);
        if (splitRow != null)
            splitRow.setOnClickListener(v -> showVideoSplittingBottomSheet());
        View locRow = root.findViewById(R.id.row_location_embed);
        if (locRow != null)
            locRow.setOnClickListener(v -> showLocationEmbedSheet());
    }

    private void refreshAllValues() {
        // -------------- Fix Start for this method(refreshAllValues)-----------
        CameraType cam = prefs.getCameraSelection();
        valueCamera.setText(cam == CameraType.FRONT ? getString(R.string.button_settings_cam_front)
                : getString(R.string.button_settings_cam_back));

        // Lens
        if (cam == CameraType.BACK) {
            if (availableBackCameras.isEmpty()) {
                detectAvailableBackCameras();
            }
            String savedId = prefs.getSelectedBackCameraId();
            String display = null;
            if (savedId != null) {
                for (CameraIdInfo info : availableBackCameras) {
                    if (info.id.equals(savedId)) {
                        display = info.displayName;
                        break;
                    }
                }
            }
            valueLens.setText(display != null ? display : getString(R.string.setting_back_lens_title));
        } else {
            valueLens.setText("-");
        }

        // Resolution (use friendly label like in picker)
        Size res = prefs.getCameraResolution();
        valueResolution.setText(buildResolutionLabel(res));

        // Frame rate (stored per camera)
        int fps = prefs.getSpecificVideoFrameRate(cam);
        valueFrameRate.setText(fps + " fps");

        // Codec
        valueCodec.setText(prefs.getVideoCodec().toString());

        // Bitrate (custom or default)
        int bitrate = getCurrentBitrate();
        boolean isCustom = getBitrateMode();
        valueBitrate.setText(
                String.format(Locale.getDefault(), "%d Mbps %s", bitrate / 1000, isCustom ? "(Custom)" : "(Auto)"));
        // Bitrate helper removed from persistent UI

        // Zoom Ratio
        if (valueZoomRatio != null) {
            float zoom = prefs.getSpecificZoomRatio(cam);
            valueZoomRatio.setText(String.format(Locale.getDefault(), "%.1fx", zoom));
            View zrRow = requireView().findViewById(R.id.row_zoom_ratio);
            if (zrRow != null)
                zrRow.setVisibility(View.VISIBLE);
        }

        // Video Splitting
        if (valueVideoSplitEnabled != null) {
            boolean enabled = prefs.isVideoSplittingEnabled();
            if (enabled) {
                int mb = prefs.getVideoSplitSizeMb();
                String label;
                if (mb == 500)
                    label = "Enabled (500 MB)";
                else if (mb == 1024)
                    label = "Enabled (1 GB)";
                else if (mb == 2048)
                    label = "Enabled (2 GB)";
                else if (mb == 4096)
                    label = "Enabled (4 GB)";
                else
                    label = "Enabled (Custom " + mb + " MB)";
                valueVideoSplitEnabled.setText(label);
            } else {
                valueVideoSplitEnabled.setText("Disabled");
            }
        }

        // Orientation
        String orient = prefs.getVideoOrientation();
        valueOrientation.setText(SharedPreferencesManager.ORIENTATION_LANDSCAPE.equals(orient)
                ? getString(R.string.video_orientation_landscape)
                : getString(R.string.video_orientation_portrait));
        if (valueLocationEmbed != null) {
            valueLocationEmbed.setText(
                    prefs.isLocationEmbeddingEnabled() ? getString(R.string.video_setting_location_embed_enabled)
                            : getString(R.string.video_setting_location_embed_disabled));
        }

        // Lens row visibility
        View lensRow = requireView().findViewById(R.id.row_lens);
        if (lensRow != null) {
            lensRow.setVisibility(cam == CameraType.BACK && availableBackCameras.size() > 0 ? View.VISIBLE : View.GONE);
        }
        // -------------- Fix Ended for this method(refreshAllValues)-----------
    }

    private Runnable pendingLocationGrantedAction;

    private void showLocationEmbedSheet() {
        // -------------- Fix Start for this method(showLocationEmbedSheet)-----------
        final String rk = "picker_result_location_embed";
        getParentFragmentManager().setFragmentResultListener(rk, this, (k, b) -> {
            if (b.containsKey(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SWITCH_STATE)) {
                boolean enabled = b.getBoolean(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SWITCH_STATE);
                if (enabled) {
                    ensureLocationPermissionThen(() -> {
                        prefs.sharedPreferences.edit().putBoolean(Constants.PREF_EMBED_LOCATION_DATA, true).apply();
                        refreshAllValues();
                    });
                } else {
                    prefs.sharedPreferences.edit().putBoolean(Constants.PREF_EMBED_LOCATION_DATA, false).apply();
                    refreshAllValues();
                }
            }
        });
        ArrayList<com.fadcam.ui.picker.OptionItem> items = new ArrayList<>(); // no options, switch only
        String helper = getString(R.string.helper_location_embed) + "\n"
                + getString(R.string.location_permission_message);
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet = com.fadcam.ui.picker.PickerBottomSheetFragment
                .newInstanceWithSwitch(
                        getString(R.string.video_setting_location_embed_title), items, null, rk, helper,
                        getString(R.string.video_setting_location_embed_title), prefs.isLocationEmbeddingEnabled());
        sheet.show(getParentFragmentManager(), "location_embed_sheet");
        // -------------- Fix Ended for this method(showLocationEmbedSheet)-----------
    }

    private void ensureLocationPermissionThen(Runnable onGranted) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(requireContext(),
                android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            onGranted.run();
        } else {
            requestPermissions(new String[] { android.Manifest.permission.ACCESS_FINE_LOCATION }, 9123);
            pendingLocationGrantedAction = onGranted;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 9123) {
            if (grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                if (pendingLocationGrantedAction != null) {
                    pendingLocationGrantedAction.run();
                    pendingLocationGrantedAction = null;
                }
            } else {
                pendingLocationGrantedAction = null;
            }
        }
    }
    // -------------- Additional methods for location embedding end -----------

    private void showCameraBottomSheet() {
        // -------------- Fix Start for this method(showCameraBottomSheet)-----------
        CameraType current = prefs.getCameraSelection();
        java.util.ArrayList<com.fadcam.ui.picker.OptionItem> items = new java.util.ArrayList<>();
        items.add(new com.fadcam.ui.picker.OptionItem(CameraType.FRONT.toString(),
                getString(R.string.button_settings_cam_front)));
        items.add(new com.fadcam.ui.picker.OptionItem(CameraType.BACK.toString(),
                getString(R.string.button_settings_cam_back)));
        final String resultKey = "picker_result_camera";
        getParentFragmentManager().setFragmentResultListener(resultKey, this, (k, b) -> {
            String sel = b.getString(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if (sel != null && !sel.equals(current.toString())) {
                prefs.sharedPreferences.edit().putString(Constants.PREF_CAMERA_SELECTION, sel).apply();
                refreshAllValues();
            }
        });
        String helper = getString(R.string.note_cam_sele);
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet = com.fadcam.ui.picker.PickerBottomSheetFragment
                .newInstance(
                        getString(R.string.setting_cam_title), items, current.toString(), resultKey, helper);
        sheet.show(getParentFragmentManager(), "camera_picker");
        // -------------- Fix Ended for this method(showCameraBottomSheet)-----------
    }

    private void showLensBottomSheet() {
        // -------------- Fix Start for this method(showLensBottomSheet)-----------
        if (prefs.getCameraSelection() == CameraType.FRONT) {
            return;
        }
        if (availableBackCameras.isEmpty())
            detectAvailableBackCameras();
        String saved = prefs.getSelectedBackCameraId();
        java.util.ArrayList<com.fadcam.ui.picker.OptionItem> items = new java.util.ArrayList<>();
        for (CameraIdInfo info : availableBackCameras) {
            items.add(new com.fadcam.ui.picker.OptionItem(info.id, info.displayName));
        }
        final String resultKey = "picker_result_lens";
        getParentFragmentManager().setFragmentResultListener(resultKey, this, (k, b) -> {
            String sel = b.getString(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if (sel != null && !sel.equals(saved)) {
                prefs.setSelectedBackCameraId(sel);
                refreshAllValues();
            }
        });
        String helper = getString(R.string.setting_back_lens_info_single_v2);
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet = com.fadcam.ui.picker.PickerBottomSheetFragment
                .newInstance(
                        getString(R.string.setting_back_lens_title), items, saved, resultKey, helper);
        sheet.show(getParentFragmentManager(), "lens_picker");
        // -------------- Fix Ended for this method(showLensBottomSheet)-----------
    }

    private void showResolutionBottomSheet() {
        // -------------- Fix Start for this
        // method(showResolutionBottomSheet)-----------
        CameraType cam = prefs.getCameraSelection();
        java.util.List<Size> resolutions = getCompatiblesVideoResolutions(cam);
        Size current = prefs.getCameraResolution();
        java.util.ArrayList<com.fadcam.ui.picker.OptionItem> items = new java.util.ArrayList<>();
        String currentId = current.getWidth() + "x" + current.getHeight();
        for (Size s : resolutions) {
            String id = s.getWidth() + "x" + s.getHeight();
            String title = buildResolutionLabel(s);
            items.add(new com.fadcam.ui.picker.OptionItem(id, title));
        }
        final String resultKey = "picker_result_resolution";
        getParentFragmentManager().setFragmentResultListener(resultKey, this, (key, bundle) -> {
            String sel = bundle.getString(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if (sel != null && !sel.equals(currentId)) {
                try {
                    String[] parts = sel.split("x");
                    int w = Integer.parseInt(parts[0]);
                    int h = Integer.parseInt(parts[1]);
                    if (w != current.getWidth() || h != current.getHeight()) {
                        prefs.sharedPreferences.edit()
                                .putInt(Constants.PREF_VIDEO_RESOLUTION_WIDTH, w)
                                .putInt(Constants.PREF_VIDEO_RESOLUTION_HEIGHT, h)
                                .apply();
                        onResolutionOrFramerateChanged();
                    }
                } catch (Exception ignored) {
                }
                refreshAllValues();
            }
        });
        String helper = getString(R.string.setting_quailty_title); // reuse title as we have no separate summary here
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet = com.fadcam.ui.picker.PickerBottomSheetFragment
                .newInstance(
                        getString(R.string.setting_quailty_title), items, currentId, resultKey, helper);
        sheet.show(getParentFragmentManager(), "resolution_picker");
        // -------------- Fix Ended for this
        // method(showResolutionBottomSheet)-----------
    }

    private void showFrameRateBottomSheet() {
        // -------------- Fix Start for this method(showFrameRateBottomSheet)-----------
        CameraType cam = prefs.getCameraSelection();
        java.util.List<Integer> rates = getCompatiblesVideoFrameRates(cam);
        int current = prefs.getSpecificVideoFrameRate(cam);
        java.util.ArrayList<com.fadcam.ui.picker.OptionItem> items = new java.util.ArrayList<>();
        String currentId = String.valueOf(current);
        for (Integer r : rates) {
            items.add(new com.fadcam.ui.picker.OptionItem(String.valueOf(r), r + " fps"));
        }
        final String resultKey = "picker_result_framerate";
        getParentFragmentManager().setFragmentResultListener(resultKey, this, (key, bundle) -> {
            String sel = bundle.getString(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if (sel != null && !sel.equals(currentId)) {
                try {
                    int fps = Integer.parseInt(sel);
                    if (fps != current) {
                        prefs.setSpecificVideoFrameRate(cam, fps);
                        onResolutionOrFramerateChanged();
                    }
                } catch (Exception ignored) {
                }
                refreshAllValues();
            }
        });
        String helper = getString(R.string.note_framerate, prefs.getSpecificVideoFrameRate(cam));
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet = com.fadcam.ui.picker.PickerBottomSheetFragment
                .newInstance(
                        getString(R.string.setting_framerate_title), items, currentId, resultKey, helper);
        sheet.show(getParentFragmentManager(), "framerate_picker");
        // -------------- Fix Ended for this method(showFrameRateBottomSheet)-----------
    }

    private void showCodecBottomSheet() {
        // -------------- Fix Start for this method(showCodecBottomSheet)-----------
        VideoCodec saved = prefs.getVideoCodec();
        java.util.List<VideoCodec> codes = getCompatiblesVideoCodecs();
        java.util.ArrayList<com.fadcam.ui.picker.OptionItem> items = new java.util.ArrayList<>();
        String currentId = saved.toString();
        for (VideoCodec c : codes) {
            items.add(new com.fadcam.ui.picker.OptionItem(c.toString(), c.toString()));
        }
        final String resultKey = "picker_result_codec";
        getParentFragmentManager().setFragmentResultListener(resultKey, this, (key, bundle) -> {
            String sel = bundle.getString(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if (sel != null && !sel.equals(currentId)) {
                prefs.sharedPreferences.edit().putString(Constants.PREF_VIDEO_CODEC, sel).apply();
                refreshAllValues();
            }
        });
        String helper = getString(R.string.note_codec, saved.toString());
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet = com.fadcam.ui.picker.PickerBottomSheetFragment
                .newInstance(
                        getString(R.string.setting_codec_title), items, currentId, resultKey, helper);
        sheet.show(getParentFragmentManager(), "codec_picker");
        // -------------- Fix Ended for this method(showCodecBottomSheet)-----------
    }

    private void showBitrateBottomSheet() {
        // -------------- Fix Start for this method(showBitrateBottomSheet)-----------
        boolean custom = getBitrateMode();
        int currentValueMbps = getBitrateCustomValue() / 1000;
        java.util.ArrayList<com.fadcam.ui.picker.OptionItem> items = new java.util.ArrayList<>();
        items.add(new com.fadcam.ui.picker.OptionItem("auto", "Auto"));
        items.add(new com.fadcam.ui.picker.OptionItem("custom", "Custom (" + currentValueMbps + " Mbps)"));
        final String resultKey = "picker_result_bitrate_mode";
        getParentFragmentManager().setFragmentResultListener(resultKey, this, (k, b) -> {
            String sel = b.getString(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if (sel == null)
                return;
            if ("auto".equals(sel)) {
                setBitrateMode(false);
                setBitrateCustomValue(getDefaultBitrate());
                onResolutionOrFramerateChanged();
                refreshAllValues();
            } else if ("custom".equals(sel)) {
                showBitrateCustomInput();
            }
        });
        String helper = getString(R.string.bitrate_explanation_text);
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet = com.fadcam.ui.picker.PickerBottomSheetFragment
                .newInstance(
                        getString(R.string.setting_video_bitrate_title), items, custom ? "custom" : "auto", resultKey,
                        helper);
        sheet.show(getParentFragmentManager(), "bitrate_mode_picker");
        // -------------- Fix Ended for this method(showBitrateBottomSheet)-----------
    }

    private void showBitrateCustomInput() {
        // -------------- Fix Start for this method(showBitrateCustomInput)-----------
        int currentMbps = getBitrateCustomValue() / 1000;
        final String resultKey = "picker_result_bitrate_value";
        getParentFragmentManager().setFragmentResultListener(resultKey, this, (k, b) -> {
            if (b.containsKey(com.fadcam.ui.picker.NumberInputBottomSheetFragment.RESULT_NUMBER)) {
                int val = b.getInt(com.fadcam.ui.picker.NumberInputBottomSheetFragment.RESULT_NUMBER);
                setBitrateCustomValue(val * 1000);
                setBitrateMode(true);
                onResolutionOrFramerateChanged();
                refreshAllValues();
            }
        });
        com.fadcam.ui.picker.NumberInputBottomSheetFragment sheet = com.fadcam.ui.picker.NumberInputBottomSheetFragment
                .newInstance(
                        getString(R.string.setting_video_bitrate_title), 1, 200, currentMbps, "1 - 200 Mbps", 3, 100,
                        getString(R.string.bitrate_info_warning_low), getString(R.string.bitrate_info_warning_high),
                        resultKey);
        sheet.show(getParentFragmentManager(), "bitrate_value_input");
        // -------------- Fix Ended for this method(showBitrateCustomInput)-----------
    }

    // Removed legacy orientation dialog + feature flag wrapper; always show bottom
    // sheet picker

    private void showOrientationBottomSheet() {
        // -------------- Fix Start for this
        // method(showOrientationBottomSheet)-----------
        ArrayList<com.fadcam.ui.picker.OptionItem> items = new ArrayList<>();
        items.add(new com.fadcam.ui.picker.OptionItem(SharedPreferencesManager.ORIENTATION_PORTRAIT,
                getString(R.string.video_orientation_portrait)));
        items.add(new com.fadcam.ui.picker.OptionItem(SharedPreferencesManager.ORIENTATION_LANDSCAPE,
                getString(R.string.video_orientation_landscape)));
        String current = prefs.getVideoOrientation();
        final String resultKey = "picker_result_orientation";
        getParentFragmentManager().setFragmentResultListener(resultKey, this, (key, bundle) -> {
            String sel = bundle.getString(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if (sel != null && !sel.equals(current)) {
                prefs.setVideoOrientation(sel);
                refreshAllValues();
            }
        });
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet = com.fadcam.ui.picker.PickerBottomSheetFragment
                .newInstance(
                        getString(R.string.setting_video_orientation_title), items, current, resultKey);
        sheet.show(getParentFragmentManager(), "orientation_picker");
        // -------------- Fix Ended for this
        // method(showOrientationBottomSheet)-----------
    }

    // -------- Legacy logic helpers (migrated/adapted) --------

    private void detectAvailableBackCameras() {
        // -------------- Fix Start for this
        // method(detectAvailableBackCameras)-----------
        availableBackCameras.clear();
        Context ctx = getContext();
        if (ctx == null)
            return;
        CameraManager manager = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        try {
            String defaultId = Constants.DEFAULT_BACK_CAMERA_ID != null ? Constants.DEFAULT_BACK_CAMERA_ID : "0";
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics ch = manager.getCameraCharacteristics(id);
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    float[] focalLengths = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                    Float focal = null;
                    if (focalLengths != null && focalLengths.length > 0)
                        focal = focalLengths[0];
                    StringBuilder display = new StringBuilder();
                    boolean isDefault = id.equals(defaultId);
                    if (isDefault) {
                        display.append("Main");
                    } else if (focal != null) {
                        if (focal <= 4f)
                            display.append("Ultra-Wide");
                        else if (focal <= 8f)
                            display.append("Wide-Angle");
                        else if (focal >= 50f)
                            display.append("Telephoto");
                        else if (focal >= 35f)
                            display.append("Portrait");
                        else
                            display.append("Camera");
                    } else {
                        display.append("Camera");
                    }
                    display.append(" (").append(id).append(")");
                    if (focal != null)
                        display.append(" ").append(Math.round(focal)).append("mm");
                    availableBackCameras.add(new CameraIdInfo(id, display.toString()));
                }
            }
            // Sort: default first then numeric id
            java.util.Collections.sort(availableBackCameras, (a, b) -> {
                if (a.id.equals(defaultId))
                    return -1;
                if (b.id.equals(defaultId))
                    return 1;
                try {
                    return Integer.parseInt(a.id) - Integer.parseInt(b.id);
                } catch (Exception e) {
                    return a.id.compareTo(b.id);
                }
            });
            if (availableBackCameras.isEmpty()) {
                availableBackCameras.add(new CameraIdInfo(defaultId, "Default Camera (" + defaultId + ")"));
            }
            String saved = prefs.getSelectedBackCameraId();
            boolean found = false;
            for (CameraIdInfo info : availableBackCameras) {
                if (info.id.equals(saved)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                prefs.setSelectedBackCameraId(availableBackCameras.get(0).id);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error detecting back cameras", e);
        }
        // -------------- Fix Ended for this
        // method(detectAvailableBackCameras)-----------
    }

    private List<Size> getCompatiblesVideoResolutions(CameraType type) {
        // -------------- Fix Start for this
        // method(getCompatiblesVideoResolutions)-----------
        // First return cached list if available
        if (type == CameraType.FRONT && !cachedResolutionsFront.isEmpty())
            return cachedResolutionsFront;
        if (type == CameraType.BACK && !cachedResolutionsBack.isEmpty())
            return cachedResolutionsBack;

        // New approach: enumerate raw supported sizes from StreamConfigurationMap so
        // that
        // smaller legacy/sub-SD resolutions (e.g. 320x240, 352x288, 426x240) not tied
        // to a specific
        // CamcorderProfile constant are still exposed to the user. The previous
        // implementation only
        // surfaced sizes that exactly matched a CamcorderProfile, resulting in seeing
        // just SD/HD/FHD.
        List<Size> supported = new ArrayList<>();
        try {
            String cameraId = getActualCameraIdForType(type);
            if (cameraId != null) {
                supported = getSupportedVideoSizesForCamera(cameraId); // already filtered by isReasonableVideoSize
            }
        } catch (Exception e) {
            Log.w(TAG, "Direct supported size enumeration failed, falling back", e);
        }

        if (supported == null)
            supported = new ArrayList<>();

        // Curate: intersect with canonical list to avoid overwhelming user with every
        // sensor mode.
        // Canonical ordered list from highest to lowest + legacy "super low" requests.
        final String[] CANONICAL = {
                "7680x4320", // 8K
                "3840x2160", // 4K
                "2560x1440", // 2K
                "1920x1080", // FHD
                "1280x720", // HD
                "854x480", // 480p widescreen (may not have label)
                "720x480", // SD (widescreen-ish 3:2)
                "640x480", // SD 4:3
                "480x360", // legacy lower SD
                "426x240", // 240p widescreen
                "352x288", // CIF
                "320x240" // QVGA
        };
        Set<String> supportedSet = new HashSet<>();
        for (Size s : supported) {
            supportedSet.add(s.getWidth() + "x" + s.getHeight());
        }

        List<Size> curated = new ArrayList<>();
        // We will only add a size if it has a matching CamcorderProfile (recordable),
        // preventing phantom choices that cause recording failures.
        String actualCameraId = getActualCameraIdForType(type);
        for (String dim : CANONICAL) {
            if (!supportedSet.contains(dim))
                continue; // hardware doesn't advertise it
            try {
                String[] parts = dim.split("x");
                Size candidate = new Size(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
                if (actualCameraId != null && createProfileForSize(actualCameraId, candidate) != null) {
                    curated.add(candidate);
                }
            } catch (Exception ignored) {
            }
        }

        // Ensure current saved resolution (if supported) appears even if non-canonical
        try {
            Size current = prefs.getCameraResolution();
            String curKey = current.getWidth() + "x" + current.getHeight();
            if (supportedSet.contains(curKey) && curated.stream()
                    .noneMatch(s -> s.getWidth() == current.getWidth() && s.getHeight() == current.getHeight())) {
                curated.add(0, current); // put at top to keep visible
            }
        } catch (Exception ignored) {
        }

        if (!curated.isEmpty()) {
            // Sort by area desc (except we already placed current earlier if injected)
            try {
                Collections.sort(curated, (a, b) -> Long.compare((long) b.getWidth() * b.getHeight(),
                        (long) a.getWidth() * a.getHeight()));
            } catch (Exception ignored) {
            }
            // Auto-correct saved preference if it's no longer valid (e.g., user had 4K
            // selected but profile absent)
            try {
                Size saved = prefs.getCameraResolution();
                boolean found = false;
                for (Size s : curated) {
                    if (s.getWidth() == saved.getWidth() && s.getHeight() == saved.getHeight()) {
                        found = true;
                        break;
                    }
                }
                if (!found) { // downgrade to first (highest) valid size
                    Size fallback = curated.get(0);
                    prefs.sharedPreferences.edit()
                            .putInt(Constants.PREF_VIDEO_RESOLUTION_WIDTH, fallback.getWidth())
                            .putInt(Constants.PREF_VIDEO_RESOLUTION_HEIGHT, fallback.getHeight())
                            .apply();
                }
            } catch (Exception ignored) {
            }
            if (type == CameraType.FRONT)
                cachedResolutionsFront = curated;
            else
                cachedResolutionsBack = curated;
            return curated;
        }

        // Fallback: legacy profile-derived approach (should rarely be needed now and
        // will still be curated upstream)
        List<CamcorderProfile> profiles = getCamcorderProfilesForTypeInternal(type);
        List<Size> sizes = new ArrayList<>();
        if (profiles != null) {
            for (CamcorderProfile p : profiles) {
                if (p != null) {
                    sizes.add(new Size(p.videoFrameWidth, p.videoFrameHeight));
                }
            }
        }
        Set<Size> uniq = new HashSet<>(sizes);
        List<Size> list = new ArrayList<>(uniq);
        try {
            Collections.sort(list,
                    (a, b) -> Long.compare((long) b.getWidth() * b.getHeight(), (long) a.getWidth() * a.getHeight()));
        } catch (Exception ignored) {
        }
        if (type == CameraType.FRONT)
            cachedResolutionsFront = list;
        else
            cachedResolutionsBack = list;
        return list;
        // -------------- Fix Ended for this
        // method(getCompatiblesVideoResolutions)-----------
    }

    // Legacy internal helpers (copied/adapted from SettingsFragment to ensure
    // parity)
    private List<CamcorderProfile> getCamcorderProfilesForTypeInternal(CameraType cameraType) {
        Context ctx = getContext();
        if (ctx == null)
            return new ArrayList<>();
        String actualCameraId = getActualCameraIdForType(cameraType);
        if (actualCameraId == null)
            return new ArrayList<>();
        List<Size> supportedVideoSizes = getSupportedVideoSizesForCamera(actualCameraId);
        if (supportedVideoSizes.isEmpty())
            return getCamcorderProfilesFallback(cameraType);
        List<CamcorderProfile> profiles = new ArrayList<>();
        for (Size s : supportedVideoSizes) {
            CamcorderProfile p = createProfileForSize(actualCameraId, s);
            if (p != null)
                profiles.add(p);
        }
        profiles.removeIf(Objects::isNull);
        return profiles;
    }

    private List<Size> getSupportedVideoSizesForCamera(String cameraId) {
        List<Size> supportedSizes = new ArrayList<>();
        try {
            Context ctx = getContext();
            if (ctx == null)
                return supportedSizes;
            CameraManager cameraManager = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap configMap = characteristics
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (configMap != null) {
                Size[] videoSizes = configMap.getOutputSizes(MediaRecorder.class);
                if (videoSizes != null) {
                    Arrays.sort(videoSizes, (s1, s2) -> Long.compare((long) s2.getWidth() * s2.getHeight(),
                            (long) s1.getWidth() * s1.getHeight()));
                    for (Size size : videoSizes) {
                        if (isReasonableVideoSize(size))
                            supportedSizes.add(size);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error accessing camera " + cameraId + " for video sizes", e);
        }
        return supportedSizes;
    }

    // -------------- Fix Start for this method(isReasonableVideoSize lower
    // threshold)-----------
    private boolean isReasonableVideoSize(Size size) {
        int w = size.getWidth();
        int h = size.getHeight();
        // Allow lower legacy SD / sub-SD resolutions (e.g., 320x240, 352x288, 426x240)
        // requested by user.
        // Previous threshold filtered anything below 480x360 and removed "super low"
        // options.
        if (w < 320 || h < 240)
            return false; // new minimal floor
        double ar = (double) w / h;
        return ar >= 1.0 && ar <= 2.5;
    }
    // -------------- Fix Ended for this method(isReasonableVideoSize lower
    // threshold)-----------

    private CamcorderProfile createProfileForSize(String cameraId, Size size) {
        try {
            int[] qualities = { CamcorderProfile.QUALITY_2160P, CamcorderProfile.QUALITY_1080P,
                    CamcorderProfile.QUALITY_720P, CamcorderProfile.QUALITY_480P, CamcorderProfile.QUALITY_HIGH,
                    CamcorderProfile.QUALITY_LOW };
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                int[] ext = { CamcorderProfile.QUALITY_8KUHD, CamcorderProfile.QUALITY_2160P,
                        CamcorderProfile.QUALITY_1080P, CamcorderProfile.QUALITY_720P, CamcorderProfile.QUALITY_480P,
                        CamcorderProfile.QUALITY_HIGH, CamcorderProfile.QUALITY_LOW };
                qualities = ext;
            }
            int camIdInt = Integer.parseInt(cameraId);
            for (int q : qualities) {
                if (CamcorderProfile.hasProfile(camIdInt, q)) {
                    CamcorderProfile p = CamcorderProfile.get(camIdInt, q);
                    if (p != null && p.videoFrameWidth == size.getWidth() && p.videoFrameHeight == size.getHeight())
                        return p;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "createProfileForSize error", e);
        }
        return null;
    }

    private List<CamcorderProfile> getCamcorderProfilesFallback(CameraType cameraType) {
        List<CamcorderProfile> profiles = new ArrayList<>();
        int cameraId = cameraType.getCameraId();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && CamcorderProfile.hasProfile(cameraId, CamcorderProfile.QUALITY_8KUHD))
            profiles.add(CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_8KUHD));
        if (CamcorderProfile.hasProfile(cameraId, CamcorderProfile.QUALITY_2160P))
            profiles.add(CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_2160P));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && CamcorderProfile.hasProfile(cameraId, CamcorderProfile.QUALITY_2K))
            profiles.add(CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_2K));
        int[] qualities = { CamcorderProfile.QUALITY_1080P, CamcorderProfile.QUALITY_720P,
                CamcorderProfile.QUALITY_480P, CamcorderProfile.QUALITY_CIF, CamcorderProfile.QUALITY_QCIF,
                CamcorderProfile.QUALITY_LOW };
        Set<Integer> added = new HashSet<>();
        for (CamcorderProfile p : profiles) {
            if (p != null)
                added.add(p.quality);
        }
        for (int q : qualities) {
            if (!added.contains(q) && CamcorderProfile.hasProfile(cameraId, q)) {
                CamcorderProfile p = CamcorderProfile.get(cameraId, q);
                if (p != null) {
                    profiles.add(p);
                    added.add(q);
                }
            }
        }
        if (!added.contains(CamcorderProfile.QUALITY_HIGH)
                && CamcorderProfile.hasProfile(cameraId, CamcorderProfile.QUALITY_HIGH))
            profiles.add(CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_HIGH));
        profiles.removeIf(Objects::isNull);
        return profiles;
    }

    private String buildResolutionLabel(Size size) {
        String[] keys = getResources().getStringArray(R.array.video_resolutions_keys);
        String[] values = getResources().getStringArray(R.array.video_resolutions_values);
        String resKey = size.getWidth() + "x" + size.getHeight();
        String label = null;
        for (int i = 0; i < keys.length; i++) {
            if (keys[i].equals(resKey)) {
                label = values[i];
                break;
            }
        }
        if (label == null)
            return resKey; // fallback
        return label + " (" + resKey + ")";
    }

    private String getActualCameraIdForType(CameraType type) {
        Context ctx = getContext();
        if (ctx == null)
            return null;
        CameraManager manager = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics ch = manager.getCameraCharacteristics(id);
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                if (facing != null) {
                    if (type == CameraType.FRONT && facing == CameraCharacteristics.LENS_FACING_FRONT)
                        return id;
                    if (type == CameraType.BACK && facing == CameraCharacteristics.LENS_FACING_BACK) {
                        return id;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error resolving camera id", e);
        }
        return null;
    }

    private List<Integer> getHardwareSupportedFrameRates(CameraType cameraType) {
        // Derived from legacy method snippet
        Context ctx = getContext();
        if (ctx == null)
            return new ArrayList<>();
        List<Integer> defaultList = new ArrayList<>();
        defaultList.add(Constants.DEFAULT_VIDEO_FRAME_RATE);
        CameraManager manager = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        String targetCameraId = getActualCameraIdForType(cameraType);
        if (targetCameraId == null)
            return defaultList;
        Range<Integer>[] ranges = null;
        try {
            CameraCharacteristics ch = manager.getCameraCharacteristics(targetCameraId);
            ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        } catch (Exception e) {
            Log.e(TAG, "FPS ranges error", e);
            return defaultList;
        }
        Set<Integer> framerates = new TreeSet<>();
        int maxProfileFps = 30;
        try {
            int camIdInt = Integer.parseInt(targetCameraId);
            int[] qualities = { CamcorderProfile.QUALITY_HIGH, CamcorderProfile.QUALITY_2160P,
                    CamcorderProfile.QUALITY_1080P, CamcorderProfile.QUALITY_720P };
            for (int q : qualities) {
                if (CamcorderProfile.hasProfile(camIdInt, q)) {
                    CamcorderProfile p = CamcorderProfile.get(camIdInt, q);
                    if (p != null && p.videoFrameRate > maxProfileFps)
                        maxProfileFps = p.videoFrameRate;
                }
            }
            if (Build.VERSION.SDK_INT >= 29) {
                int[] high = { CamcorderProfile.QUALITY_HIGH_SPEED_HIGH, CamcorderProfile.QUALITY_HIGH_SPEED_1080P,
                        CamcorderProfile.QUALITY_HIGH_SPEED_720P };
                for (int h : high) {
                    if (CamcorderProfile.hasProfile(camIdInt, h)) {
                        CamcorderProfile p = CamcorderProfile.get(camIdInt, h);
                        if (p != null && p.videoFrameRate > maxProfileFps)
                            maxProfileFps = p.videoFrameRate;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (ranges == null || ranges.length == 0) {
            for (int fps = 10; fps <= maxProfileFps; fps += 5) {
                if (fps <= 30 || fps % 30 == 0)
                    framerates.add(fps);
            }
            int[] standard = { 24, 25, 30, 60, 90, 120 };
            for (int r : standard) {
                if (r <= maxProfileFps)
                    framerates.add(r);
            }
        } else {
            for (Range<Integer> r : ranges) {
                if (r != null) {
                    for (int fps = r.getLower(); fps <= r.getUpper(); fps++) {
                        framerates.add(fps);
                    }
                }
            }
            if (maxProfileFps > 30) {
                int[] highRates = { 60, 90, 120, 240 };
                for (int r : highRates) {
                    if (r <= maxProfileFps)
                        framerates.add(r);
                }
            }
        }
        if (framerates.isEmpty())
            framerates.add(Constants.DEFAULT_VIDEO_FRAME_RATE);
        List<Integer> list = new ArrayList<>(framerates);
        if (list.size() > 20) {
            Set<Integer> filtered = new TreeSet<>();
            int[] standard = { 24, 25, 30, 50, 60, 90, 120, 240 };
            for (int s : standard) {
                if (framerates.contains(s))
                    filtered.add(s);
            }
            for (int f : framerates) {
                if (f % 5 == 0 && f <= 60)
                    filtered.add(f);
                else if (f > 60)
                    filtered.add(f);
            }
            if (!filtered.contains(Constants.DEFAULT_VIDEO_FRAME_RATE)
                    && framerates.contains(Constants.DEFAULT_VIDEO_FRAME_RATE))
                filtered.add(Constants.DEFAULT_VIDEO_FRAME_RATE);
            list = new ArrayList<>(filtered);
        }
        return list;
    }

    // Legacy-style compatible frame rate list using selected profile constraints
    private List<Integer> getCompatiblesVideoFrameRates(CameraType cameraType) {
        CamcorderProfile profile = getCamcorderProfile(cameraType);
        if (profile == null) {
            List<Integer> single = new ArrayList<>();
            single.add(Constants.DEFAULT_VIDEO_FRAME_RATE);
            return single;
        }
        int max = profile.videoFrameRate;
        int[] options = getResources().getIntArray(R.array.video_framerate_options);
        List<Integer> list = new ArrayList<>();
        for (int o : options) {
            if (o <= max)
                list.add(o);
        }
        if (list.isEmpty())
            list.add(max);
        return list;
    }

    private List<VideoCodec> getCompatiblesVideoCodecs() {
        if (!cachedCodecs.isEmpty())
            return cachedCodecs;
        List<VideoCodec> codecs = new ArrayList<>();
        for (VideoCodec c : VideoCodec.values()) {
            if (Utils.isCodecSupported(c.getMimeType()))
                codecs.add(c);
        }
        if (codecs.isEmpty())
            codecs.add(Constants.DEFAULT_VIDEO_CODEC);
        cachedCodecs = codecs;
        return codecs;
    }

    // Bitrate helpers (mirroring legacy semantics)
    private boolean getBitrateMode() {
        return prefs.sharedPreferences.getBoolean(PREF_BITRATE_MODE_CUSTOM, false);
    }

    private void setBitrateMode(boolean custom) {
        prefs.sharedPreferences.edit().putBoolean(PREF_BITRATE_MODE_CUSTOM, custom).apply();
    }

    private int getBitrateCustomValue() {
        return prefs.sharedPreferences.getInt(PREF_BITRATE_CUSTOM_VALUE, getDefaultBitrate());
    }

    private void setBitrateCustomValue(int value) {
        prefs.sharedPreferences.edit().putInt(PREF_BITRATE_CUSTOM_VALUE, value).apply();
    }

    private int getDefaultBitrate() {
        Size resolution = prefs.getCameraResolution();
        CameraType camera = prefs.getCameraSelection();
        int framerate = prefs.getSpecificVideoFrameRate(camera);
        
        int width = resolution.getWidth();
        int height = resolution.getHeight();
        int pixels = width * height;
        
        // Base bitrate calculation based on resolution
        int baseBitrate;
        
        if (pixels >= 3840 * 2160) {
            // 4K and above
            baseBitrate = 45000; // 45 Mbps
        } else if (pixels >= 2560 * 1440) {
            // 2K/1440p
            baseBitrate = 16000; // 16 Mbps
        } else if (pixels >= 1920 * 1080) {
            // FHD/1080p
            baseBitrate = 12000; // 12 Mbps
        } else if (pixels >= 1280 * 720) {
            // HD/720p
            baseBitrate = 8000; // 8 Mbps
        } else if (pixels >= 854 * 480) {
            // 480p
            baseBitrate = 4000; // 4 Mbps
        } else {
            // SD and below
            baseBitrate = 2000; // 2 Mbps
        }
        
        // Adjust for framerate (higher framerate = higher bitrate)
        if (framerate > 30) {
            baseBitrate = (int) (baseBitrate * (framerate / 30.0f));
        }
        
        return baseBitrate;
    }

    private int getCurrentBitrate() {
        return getBitrateMode() ? getBitrateCustomValue() : getDefaultBitrate();
    }

    private void onResolutionOrFramerateChanged() {
        refreshAllValues();
    }

    // Bitrate helper text mirroring thresholds used in dialog (low<3, high>100) but
    // for current value
    private void updateBitrateHelper(int bitrateKbps, boolean custom) {
        /* removed */ }

    // ---- Zoom Ratio Migration ----
    private void showZoomRatioBottomSheet() {
        // -------------- Fix Start for this method(showZoomRatioBottomSheet)-----------
        CameraType cam = prefs.getCameraSelection();
        List<Float> ratios = buildZoomRatioOptions(cam);
        if (ratios.isEmpty())
            return;
        float current = prefs.getSpecificZoomRatio(cam);
        java.util.ArrayList<com.fadcam.ui.picker.OptionItem> items = new java.util.ArrayList<>();
        String currentId = String.format(Locale.getDefault(), "%.1f", current);
        for (Float r : ratios) {
            items.add(new com.fadcam.ui.picker.OptionItem(String.format(Locale.getDefault(), "%.1f", r),
                    String.format(Locale.getDefault(), "%.1fx", r)));
        }
        final String resultKey = "picker_result_zoom_ratio";
        getParentFragmentManager().setFragmentResultListener(resultKey, this, (k, b) -> {
            String sel = b.getString(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if (sel != null && !sel.equals(currentId)) {
                try {
                    float val = Float.parseFloat(sel);
                    if (Math.abs(val - current) > 0.001f) {
                        prefs.setSpecificZoomRatio(cam, val);
                        refreshAllValues();
                    }
                } catch (Exception ignored) {
                }
            }
        });
        String helper = getString(R.string.note_zoom_ratio, 1.0f);
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet = com.fadcam.ui.picker.PickerBottomSheetFragment
                .newInstance(
                        getString(R.string.setting_zoom_ratio_title), items, currentId, resultKey, helper);
        sheet.show(getParentFragmentManager(), "zoom_ratio_picker");
        // -------------- Fix Ended for this method(showZoomRatioBottomSheet)-----------
    }

    private List<Float> buildZoomRatioOptions(CameraType cam) {
        List<Float> list = new ArrayList<>();
        float max = getHardwareSupportedMaxZoomRatio(cam);
        // Add from 0.5x up to max in 0.5 increments; ensure 1.0 included
        for (float z = 0.5f; z <= max + 0.001f; z += 0.5f) {
            list.add(((float) Math.round(z * 10)) / 10f);
        }
        if (!list.contains(1.0f))
            list.add(1.0f);
        java.util.Collections.sort(list);
        return list;
    }

    private float getHardwareSupportedMaxZoomRatio(CameraType cam) {
        final float defaultMaxZoom = 5.0f; // legacy default
        Context ctx = getContext();
        if (ctx == null)
            return defaultMaxZoom;
        try {
            CameraManager manager = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            String id = getActualCameraIdForType(cam);
            if (id == null)
                return defaultMaxZoom;
            CameraCharacteristics ch = manager.getCameraCharacteristics(id);
            if (Build.VERSION.SDK_INT >= 30) {
                Range<Float> range = ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
                if (range != null) {
                    return range.getUpper();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Zoom ratio query failed", e);
        }
        return defaultMaxZoom;
    }

    // ---- Video Splitting Migration ----
    private void toggleVideoSplitting() {
        boolean enabled = prefs.isVideoSplittingEnabled();
        prefs.setVideoSplittingEnabled(!enabled);
    }

    private void showVideoSplittingBottomSheet() {
        // -------------- Fix Start for this
        // method(showVideoSplittingBottomSheet)-----------
        boolean enabled = prefs.isVideoSplittingEnabled();
        int mb = prefs.getVideoSplitSizeMb();
        String sizeLabel;
        if (mb == 500)
            sizeLabel = "500 MB";
        else if (mb == 1024)
            sizeLabel = "1 GB";
        else if (mb == 2048)
            sizeLabel = "2 GB";
        else if (mb == 4096)
            sizeLabel = "4 GB";
        else
            sizeLabel = "Custom (" + mb + " MB)";
        java.util.ArrayList<com.fadcam.ui.picker.OptionItem> items = new java.util.ArrayList<>();
        items.add(new com.fadcam.ui.picker.OptionItem("size", "Change Split Size (Current: " + sizeLabel + ")"));
        final String resultKey = "picker_result_video_split";
        getParentFragmentManager().setFragmentResultListener(resultKey, this, (k, b) -> {
            if (b.containsKey(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SWITCH_STATE)) {
                boolean state = b.getBoolean(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SWITCH_STATE);
                prefs.setVideoSplittingEnabled(state);
                refreshAllValues();
            }
            String sel = b.getString(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if (sel == null)
                return;
            if ("size".equals(sel)) {
                showVideoSplitSizeBottomSheet();
            }
        });
        String helper = getString(R.string.video_splitting_description);
        java.util.ArrayList<String> dep = new java.util.ArrayList<>();
        dep.add("size");
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet = com.fadcam.ui.picker.PickerBottomSheetFragment
                .newInstanceWithSwitchDependencies(
                        getString(R.string.video_splitting_title), items, null, resultKey, helper,
                        getString(R.string.video_splitting_title), enabled, dep);
        sheet.show(getParentFragmentManager(), "video_splitting_picker");
        // -------------- Fix Ended for this
        // method(showVideoSplittingBottomSheet)-----------
    }

    private void updateSplitSizeLabel(TextView label) {
        if (label == null)
            return;
        int mb = prefs.getVideoSplitSizeMb();
        String sizeLabel;
        if (mb == 500)
            sizeLabel = "500 MB";
        else if (mb == 1024)
            sizeLabel = "1 GB";
        else if (mb == 2048)
            sizeLabel = "2 GB";
        else if (mb == 4096)
            sizeLabel = "4 GB";
        else
            sizeLabel = "Custom (" + mb + " MB)";
        label.setText("Current Size: " + sizeLabel);
    }

    private void showVideoSplitSizeBottomSheet() {
        // -------------- Fix Start for this
        // method(showVideoSplitSizeBottomSheet)-----------
        if (!prefs.isVideoSplittingEnabled())
            return;
        final int[] presetMb = { 500, 1024, 2048, 4096, -1 };
        java.util.ArrayList<com.fadcam.ui.picker.OptionItem> items = new java.util.ArrayList<>();
        for (int mb : presetMb) {
            if (mb == -1)
                items.add(new com.fadcam.ui.picker.OptionItem("custom", "Custom..."));
            else
                items.add(new com.fadcam.ui.picker.OptionItem(String.valueOf(mb),
                        (mb == 1024 ? "1 GB" : mb == 2048 ? "2 GB" : mb == 4096 ? "4 GB" : mb + " MB")));
        }
        int current = prefs.getVideoSplitSizeMb();
        String currentId = null;
        for (int mb : presetMb) {
            if (mb == current) {
                currentId = mb == -1 ? null : String.valueOf(mb);
                break;
            }
        }
        final String resultKey = "picker_result_video_split_size";
        getParentFragmentManager().setFragmentResultListener(resultKey, this, (k, b) -> {
            String sel = b.getString(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if (sel == null)
                return;
            if ("custom".equals(sel)) {
                showCustomSplitSizeBottomSheet();
            } else {
                try {
                    int mbVal = Integer.parseInt(sel);
                    prefs.setVideoSplitSizeMb(mbVal);
                    refreshAllValues();
                } catch (Exception ignored) {
                }
            }
        });
        String helper = getString(R.string.video_splitting_description);
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet = com.fadcam.ui.picker.PickerBottomSheetFragment
                .newInstance(
                        getString(R.string.video_splitting_title), items, currentId, resultKey, helper);
        sheet.show(getParentFragmentManager(), "video_split_size_picker");
        // -------------- Fix Ended for this
        // method(showVideoSplitSizeBottomSheet)-----------
    }

    private void showCustomSplitSizeBottomSheet() {
        // -------------- Fix Start for this
        // method(showCustomSplitSizeBottomSheet)-----------
        int current = prefs.getVideoSplitSizeMb();
        if (current == 500 || current == 1024 || current == 2048 || current == 4096)
            current = 2048; // default for custom
        final String resultKey = "picker_result_video_split_custom";
        getParentFragmentManager().setFragmentResultListener(resultKey, this, (k, b) -> {
            if (b.containsKey(com.fadcam.ui.picker.NumberInputBottomSheetFragment.RESULT_NUMBER)) {
                int mb = b.getInt(com.fadcam.ui.picker.NumberInputBottomSheetFragment.RESULT_NUMBER);
                prefs.setVideoSplitSizeMb(mb);
                refreshAllValues();
            }
        });
        com.fadcam.ui.picker.NumberInputBottomSheetFragment sheet = com.fadcam.ui.picker.NumberInputBottomSheetFragment
                .newInstance(
                        "Custom Split Size (MB)", 10, 102400, current, "10 - 102400", 0, 0,
                        null, null, resultKey);
        sheet.show(getParentFragmentManager(), "video_split_custom_input");
        // -------------- Fix Ended for this
        // method(showCustomSplitSizeBottomSheet)-----------
    }

    private CamcorderProfile getCamcorderProfile(CameraType type) {
        try {
            String id = getActualCameraIdForType(type);
            if (id == null)
                return null;
            int camId = Integer.parseInt(id); // choose quality based on current resolution
            Size res = prefs.getCameraResolution();
            int quality = CamcorderProfile.QUALITY_HIGH;
            if (res.getWidth() >= 3840 || res.getHeight() >= 2160)
                quality = CamcorderProfile.QUALITY_2160P;
            else if (res.getWidth() >= 1920 || res.getHeight() >= 1080)
                quality = CamcorderProfile.QUALITY_1080P;
            else if (res.getWidth() >= 1280 || res.getHeight() >= 720)
                quality = CamcorderProfile.QUALITY_720P;
            if (CamcorderProfile.hasProfile(camId, quality))
                return CamcorderProfile.get(camId, quality);
        } catch (Exception ignored) {
        }
        return null;
    }

    // Simple holder mirroring legacy CameraIdInfo
    private static class CameraIdInfo {
        final String id;
        final String displayName;

        CameraIdInfo(String id, String display) {
            this.id = id;
            this.displayName = display;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty())
            return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }


}
