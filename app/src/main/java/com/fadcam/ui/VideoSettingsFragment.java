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
import com.fadcam.utils.CameraXFrameRateUtil;
import com.fadcam.utils.DeviceHelper;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import java.util.Collections;
import java.util.Arrays;

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
    }

    private Runnable pendingLocationGrantedAction;

    private void showLocationEmbedSheet() {
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

                // Clear cached resolution lists when camera type changes to force refresh
                cachedResolutionsFront.clear();
                cachedResolutionsBack.clear();

                refreshAllValues();
            }
        });
        String helper = getString(R.string.note_cam_sele);
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet = com.fadcam.ui.picker.PickerBottomSheetFragment
                .newInstance(
                        getString(R.string.setting_cam_title), items, current.toString(), resultKey, helper);
        sheet.show(getParentFragmentManager(), "camera_picker");
    }

    private void showLensBottomSheet() {
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
                // Clear back camera cache when lens changes as different lenses may support different resolutions
                cachedResolutionsBack.clear();
                refreshAllValues();
            }
        });
        // Create dynamic helper text based on detected cameras
        String helper = createLensHelperText();
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet = com.fadcam.ui.picker.PickerBottomSheetFragment
                .newInstance(
                        getString(R.string.setting_back_lens_title), items, saved, resultKey, helper);
        sheet.show(getParentFragmentManager(), "lens_picker");
    }

    private void showResolutionBottomSheet() {
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
        // method(showResolutionBottomSheet)-----------
    }

    private void showFrameRateBottomSheet() {
        CameraType cam = prefs.getCameraSelection();
        java.util.List<Integer> rates = getHardwareSupportedFrameRates(cam);
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
        String helper = getString(R.string.note_framerate, Constants.DEFAULT_VIDEO_FRAME_RATE);
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet = com.fadcam.ui.picker.PickerBottomSheetFragment
                .newInstance(
                        getString(R.string.setting_framerate_title), items, currentId, resultKey, helper);
        sheet.show(getParentFragmentManager(), "framerate_picker");
    }

    private void showCodecBottomSheet() {
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
    }

    private void showBitrateBottomSheet() {
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
    }

    private void showBitrateCustomInput() {
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
    }

    // Removed legacy orientation dialog + feature flag wrapper; always show bottom
    // sheet picker

    private void showOrientationBottomSheet() {
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
        // method(showOrientationBottomSheet)-----------
    }

    // -------- Legacy logic helpers (migrated/adapted) --------

    private void detectAvailableBackCameras() {
        // method(detectAvailableBackCameras)-----------
        availableBackCameras.clear();
        Context ctx = getContext();
        if (ctx == null)
            return;
        CameraManager manager = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        try {
            String defaultId = Constants.DEFAULT_BACK_CAMERA_ID != null ? Constants.DEFAULT_BACK_CAMERA_ID : "0";

            // Track camera types to avoid duplicates
            java.util.Set<String> addedCameraTypes = new java.util.HashSet<>();

            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics ch = manager.getCameraCharacteristics(id);
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    float[] focalLengths = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                    Float focal = null;
                    if (focalLengths != null && focalLengths.length > 0)
                        focal = focalLengths[0];

                    StringBuilder display = new StringBuilder();
                    String cameraType;
                    boolean isDefault = id.equals(defaultId);

                    // Determine camera type based on focal length, but be conservative
                    // Only classify as wide-angle if there are multiple cameras with different
                    // focal lengths
                    String lensType = null;
                    if (focal != null && availableBackCameras.size() > 0) {
                        // Only classify as special lens types if we have multiple cameras
                        // This prevents single-camera phones from being mislabeled
                        boolean hasMultipleCameras = false;
                        try {
                            // Quick check if there are multiple back cameras
                            int backCameraCount = 0;
                            for (String checkId : manager.getCameraIdList()) {
                                CameraCharacteristics checkCh = manager.getCameraCharacteristics(checkId);
                                Integer checkFacing = checkCh.get(CameraCharacteristics.LENS_FACING);
                                if (checkFacing != null && checkFacing == CameraCharacteristics.LENS_FACING_BACK) {
                                    backCameraCount++;
                                }
                            }
                            hasMultipleCameras = backCameraCount > 1;
                        } catch (Exception e) {
                            // If we can't determine, assume single camera
                            hasMultipleCameras = false;
                        }

                        if (hasMultipleCameras) {
                            // Only classify lens types if there are multiple cameras
                            if (focal <= 3f) {
                                lensType = "Ultra-Wide";
                            } else if (focal <= 6f) {
                                lensType = "Wide-Angle";
                            } else if (focal >= 70f) {
                                lensType = "Telephoto";
                            } else if (focal >= 50f) {
                                lensType = "Portrait";
                            }
                            // For focal lengths between 6-50mm, don't classify (likely standard)
                        }
                        // For single camera phones, don't classify by focal length
                    }

                    if (isDefault) {
                        // For default camera, show both "Main" and lens type if it's not standard
                        if (lensType != null && !lensType.equals("Standard")) {
                            display.append("Main (").append(lensType).append(")");
                            cameraType = "Main_" + lensType;
                        } else {
                            display.append("Main");
                            cameraType = "Main";
                        }
                    } else if (lensType != null) {
                        display.append(lensType);
                        cameraType = lensType;
                    } else {
                        display.append("Camera");
                        cameraType = "Camera_" + id;
                    }

                    // Skip duplicate camera types (except Main camera which is always included)
                    if (!isDefault && addedCameraTypes.contains(cameraType)) {
                        Log.d(TAG, "Skipping duplicate camera type: " + cameraType + " (ID: " + id + ")");
                        continue;
                    }

                    addedCameraTypes.add(cameraType);
                    display.append(" (").append(id).append(")");
                    if (focal != null)
                        display.append(" ").append(Math.round(focal)).append("mm");
                    availableBackCameras.add(new CameraIdInfo(id, display.toString()));

                    Log.d(TAG, "Added camera: " + display.toString() + " with focal length: " + focal);
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
        // method(detectAvailableBackCameras)-----------
    }

    private List<Size> getCompatiblesVideoResolutions(CameraType type) {
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
        // FIX: Remove CamcorderProfile requirement - FadCam uses MediaCodec (GLRecordingPipeline)
        // directly, not MediaRecorder, so all hardware-supported resolutions should work.
        // The old approach filtered out valid resolutions (e.g., front camera 4K on Xiaomi 15)
        // because they lacked a matching CamcorderProfile even though the camera supports them.
        // See GitHub issues #239, #204 for user reports.
        for (String dim : CANONICAL) {
            if (!supportedSet.contains(dim))
                continue; // hardware doesn't advertise it
            try {
                String[] parts = dim.split("x");
                Size candidate = new Size(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
                curated.add(candidate);
                Log.d(TAG, "Resolution Validation: " + dim + " - SUPPORTED (hardware advertises it)");
            } catch (Exception ignored) {
            }
        }

        // Also add any non-canonical high-resolution sizes (>= 1080p) that the camera 
        // supports but aren't in our canonical list. This ensures we don't miss
        // device-specific resolutions like 3280x2464 on Pixel 6a or other unique sizes.
        Set<String> canonicalSet = new HashSet<>(java.util.Arrays.asList(CANONICAL));
        for (Size s : supported) {
            String key = s.getWidth() + "x" + s.getHeight();
            // Only add non-canonical sizes that are >= 1080p (to avoid cluttering with sensor-native weird sizes)
            if (!canonicalSet.contains(key) && s.getWidth() >= 1920 && s.getHeight() >= 1080) {
                boolean alreadyAdded = curated.stream().anyMatch(c -> c.getWidth() == s.getWidth() && c.getHeight() == s.getHeight());
                if (!alreadyAdded) {
                    curated.add(s);
                    Log.d(TAG, "Resolution Validation: " + key + " - SUPPORTED (non-canonical high-res)");
                }
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
                    
                    // Log all supported video sizes for diagnosis
                    Log.d(TAG, "Video Sizes: Camera " + cameraId + " supports " + videoSizes.length + " video resolutions:");
                    for (Size size : videoSizes) {
                        Log.d(TAG, "Video Sizes: " + size.getWidth() + "x" + size.getHeight() + 
                              " (reasonable: " + isReasonableVideoSize(size) + ")");
                    }
                    
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
    // threshold)-----------

    private CamcorderProfile createProfileForSize(String cameraId, Size size) {
        try {
            int[] qualities = { CamcorderProfile.QUALITY_2160P, CamcorderProfile.QUALITY_1080P,
                    CamcorderProfile.QUALITY_720P, CamcorderProfile.QUALITY_480P, CamcorderProfile.QUALITY_HIGH,
                    CamcorderProfile.QUALITY_LOW };
            // Add 8K support without unnecessary API checks - let hasProfile() handle availability
            int[] ext = { CamcorderProfile.QUALITY_8KUHD, CamcorderProfile.QUALITY_2160P,
                    CamcorderProfile.QUALITY_1080P, CamcorderProfile.QUALITY_720P, CamcorderProfile.QUALITY_480P,
                    CamcorderProfile.QUALITY_HIGH, CamcorderProfile.QUALITY_LOW };
            qualities = ext;
            int camIdInt = Integer.parseInt(cameraId);
            
            // Simplified diagnostic logging for 8K detection
            if (size.getWidth() == 7680 && size.getHeight() == 4320) {
                Log.d(TAG, "8K Detection: Checking 8K (7680x4320) for camera " + cameraId);
                boolean has8K = CamcorderProfile.hasProfile(camIdInt, CamcorderProfile.QUALITY_8KUHD);
                Log.d(TAG, "8K Detection: CamcorderProfile.hasProfile(QUALITY_8KUHD) = " + has8K);
                if (has8K) {
                    CamcorderProfile profile8K = CamcorderProfile.get(camIdInt, CamcorderProfile.QUALITY_8KUHD);
                    if (profile8K != null) {
                        Log.d(TAG, "8K Detection: 8K Profile dimensions = " + profile8K.videoFrameWidth + "x" + profile8K.videoFrameHeight);
                    } else {
                        Log.w(TAG, "8K Detection: 8K Profile is null despite hasProfile=true");
                    }
                }
            }
            
            for (int q : qualities) {
                if (CamcorderProfile.hasProfile(camIdInt, q)) {
                    CamcorderProfile p = CamcorderProfile.get(camIdInt, q);
                    if (p != null && p.videoFrameWidth == size.getWidth() && p.videoFrameHeight == size.getHeight())
                        return p;
                }
            }
            
            // Only allow specific fallbacks for exact resolution matches to prevent phantom options
            // 8K: Only if we have actual 8K profile
            if (size.getWidth() == 7680 && size.getHeight() == 4320) {
                if (CamcorderProfile.hasProfile(camIdInt, CamcorderProfile.QUALITY_8KUHD)) {
                    CamcorderProfile p8k = CamcorderProfile.get(camIdInt, CamcorderProfile.QUALITY_8KUHD);
                    if (p8k != null) {
                        Log.d(TAG, "8K Fallback: Found 8K profile with dimensions " + p8k.videoFrameWidth + "x" + p8k.videoFrameHeight);
                        return p8k;
                    }
                }
            }
            
            // 4K: Only if we have actual 4K profile  
            if (size.getWidth() == 3840 && size.getHeight() == 2160) {
                if (CamcorderProfile.hasProfile(camIdInt, CamcorderProfile.QUALITY_2160P)) {
                    CamcorderProfile p4k = CamcorderProfile.get(camIdInt, CamcorderProfile.QUALITY_2160P);
                    if (p4k != null) {
                        Log.d(TAG, "4K Fallback: Found 4K profile with dimensions " + p4k.videoFrameWidth + "x" + p4k.videoFrameHeight);
                        return p4k;
                    }
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
        // Simplified: treat 8K like 4K - just check if profile exists, no API level checks
        if (CamcorderProfile.hasProfile(cameraId, CamcorderProfile.QUALITY_8KUHD))
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



    // Compatible frame rate list - fixed to not be limited by CamcorderProfile default FPS
    private List<Integer> getCompatiblesVideoFrameRates(CameraType cameraType) {
        // The real issue: Don't limit by CamcorderProfile.videoFrameRate which might be just the default (30fps)
        // Instead, provide all available options and let the camera/encoder handle what's actually supported
        int[] options = getResources().getIntArray(R.array.video_framerate_options);
        List<Integer> list = new ArrayList<>();
        
        // Add all frame rate options from the array (includes 60fps, 90fps, 120fps)
        for (int fps : options) {
            list.add(fps);
        }
        
        // If no options defined in array, provide standard options including high frame rates
        if (list.isEmpty()) {
            list.add(24);
            list.add(30);
            list.add(60);
            list.add(90);
            list.add(120);
        }
        
        // Ensure default frame rate is included
        if (!list.contains(Constants.DEFAULT_VIDEO_FRAME_RATE)) {
            list.add(Constants.DEFAULT_VIDEO_FRAME_RATE);
        }
        
        // Sort the list
        java.util.Collections.sort(list);
        
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
        // Create dynamic helper text explaining auto-selection behavior
        String helper = createZoomHelperText(cam);
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet = com.fadcam.ui.picker.PickerBottomSheetFragment
                .newInstance(
                        getString(R.string.setting_zoom_ratio_title), items, currentId, resultKey, helper);
        sheet.show(getParentFragmentManager(), "zoom_ratio_picker");
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
        // method(showVideoSplitSizeBottomSheet)-----------
    }

    private void showCustomSplitSizeBottomSheet() {
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

    /**
     * Creates dynamic helper text for lens selection based on detected cameras
     */
    private String createLensHelperText() {
        if (availableBackCameras.isEmpty()) {
            return getString(R.string.setting_back_lens_info_single_v2); // Fallback to static text
        }
        
        int cameraCount = availableBackCameras.size();
        if (cameraCount == 1) {
            return "Only one back camera detected. No lens switching available.";
        } else {
            StringBuilder helper = new StringBuilder();
            helper.append("Detected ").append(cameraCount).append(" back cameras: ");
            for (int i = 0; i < availableBackCameras.size(); i++) {
                if (i > 0) helper.append(", ");
                CameraIdInfo info = availableBackCameras.get(i);
                // Extract just the lens type from display name (before the parentheses)
                String lensType = info.displayName.split(" \\(")[0];
                helper.append(lensType);
            }
            helper.append(". Select the lens you want to use for recording.");
            return helper.toString();
        }
    }

    /**
     * Creates dynamic helper text for zoom ratio explaining auto-selection behavior
     */
    private String createZoomHelperText(CameraType cameraType) {
        StringBuilder helper = new StringBuilder();
        
        // Get the default zoom ratio for this camera type
        float defaultZoom = prefs.getSpecificZoomRatio(cameraType);
        
        helper.append("Zoom ratio controls the field of view. ");
        helper.append("Default is ").append(String.format(Locale.getDefault(), "%.1fx", defaultZoom));
        
        // Add information about auto-selection for wide-angle cameras
        if (cameraType == CameraType.BACK) {
            String selectedCameraId = prefs.getSelectedBackCameraId();
            if (selectedCameraId != null && !selectedCameraId.equals(Constants.DEFAULT_BACK_CAMERA_ID)) {
                // Check if this is a wide-angle camera
                if (isWideAngleCamera(selectedCameraId)) {
                    helper.append(" (auto-selected 0.5x for wide-angle lens to show full field of view)");
                }
            }
        }
        
        helper.append(". Lower values = wider view, higher values = more zoomed in.");
        return helper.toString();
    }
    
    /**
     * Helper method to check if a camera ID is wide-angle (reused from SharedPreferencesManager logic)
     */
    private boolean isWideAngleCamera(String cameraId) {
        try {
            Context ctx = getContext();
            if (ctx == null) return false;
            
            CameraManager manager = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            if (manager != null) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                float[] focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                if (focalLengths != null && focalLengths.length > 0) {
                    float focal = focalLengths[0];
                    // Wide-angle cameras typically have focal lengths <= 8mm
                    return focal <= 8f;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error checking camera characteristics for ID: " + cameraId, e);
        }
        return false;
    }

    /**
     * Queries the Camera2 API for supported FPS ranges for the primary camera of
     * the specified type
     * and returns a list of all unique framerates supported by the hardware.
     *
     * @param cameraType The camera type (FRONT or BACK) to query.
     * @return A sorted List<Integer> of all supported frame rates. Returns default
     *         [30] on critical errors.
     */
    @ExperimentalCamera2Interop
    private List<Integer> getHardwareSupportedFrameRates(CameraType cameraType) {
        if (getContext() == null) {
            Log.e(TAG, "FPS Query: Context is null.");
            return Collections.singletonList(Constants.DEFAULT_VIDEO_FRAME_RATE);
        }

        // Use the new CameraX utility for framerate detection
        try {
            Log.i(TAG, "Using CameraX API for framerate detection");
            List<Integer> detectedRates = CameraXFrameRateUtil.getHardwareSupportedFrameRates(requireContext(),
                    cameraType);

            // Special handling for Samsung devices - explicitly add 60fps support if not
            // already present
            if (DeviceHelper.isSamsung() && !detectedRates.contains(60)) {
                Log.i(TAG, "Samsung device detected - Adding 60fps support explicitly");
                List<Integer> enhancedRates = new ArrayList<>(detectedRates);
                enhancedRates.add(60);
                Collections.sort(enhancedRates);
                return enhancedRates;
            }

            // Removed Huawei-specific handling to standardize behavior across devices

            return detectedRates;
        } catch (Exception e) {
            Log.e(TAG, "Error using CameraX for framerate detection, falling back to Camera2", e);
            // Fallback to Camera2 API implementation - retain original logic
            return getHardwareSupportedFrameRatesUsingCamera2(cameraType);
        }
    }

    /**
     * Original Camera2 API implementation for getting supported framerates.
     * Kept as a fallback method in case CameraX implementation fails.
     * 
     * @param cameraType The camera type (FRONT or BACK) to query.
     * @return A sorted List<Integer> of all supported frame rates.
     */
    private List<Integer> getHardwareSupportedFrameRatesUsingCamera2(CameraType cameraType) {
        Log.i(TAG,
                "=== Getting Hardware Supported FPS for CameraType: " + cameraType + " using Camera2 API ===");
        final List<Integer> defaultRateList = Collections.singletonList(Constants.DEFAULT_VIDEO_FRAME_RATE);

        if (getContext() == null) {
            Log.e(TAG, "FPS Query: Context is null.");
            return defaultRateList;
        }

        CameraManager manager = (CameraManager) requireContext().getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) {
            Log.e(TAG, "FPS Query: CameraManager is null.");
            return defaultRateList;
        }

        String targetCameraId = null;
        try {
            // Find the primary camera ID for the requested type (Prioritize ID "0" for
            // BACK)
            String firstBackIdFallback = null;
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null) {
                    if (cameraType == CameraType.FRONT && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                        targetCameraId = id;
                        Log.d(TAG, "FPS Query: Found FRONT camera ID: " + targetCameraId);
                        break;
                    }
                    if (cameraType == CameraType.BACK && facing == CameraCharacteristics.LENS_FACING_BACK) {
                        if (id.equals(Constants.DEFAULT_BACK_CAMERA_ID)) {
                            targetCameraId = id; // Found preferred default BACK ID "0"
                            Log.d(TAG, "FPS Query: Found Primary BACK camera ID: " + targetCameraId);
                            break;
                        } else if (firstBackIdFallback == null) {
                            firstBackIdFallback = id; // Store first BACK ID encountered as fallback
                        }
                    }
                }
            }
            // If default BACK "0" wasn't found, use the fallback if available
            if (cameraType == CameraType.BACK && targetCameraId == null && firstBackIdFallback != null) {
                targetCameraId = firstBackIdFallback;
                Log.w(TAG,
                        "FPS Query: Default Back ID '0' not found/back-facing. Using first available back ID: "
                                + targetCameraId);
            }
        } catch (CameraAccessException | IllegalArgumentException e) {
            Log.e(TAG, "FPS Query: Error accessing camera list/characteristics during ID selection", e);
            return defaultRateList;
        }

        if (targetCameraId == null) {
            Log.e(TAG, "FPS Query: Could not find a valid Camera ID for type: " + cameraType);
            return defaultRateList;
        }
        Log.d(TAG, "FPS Query: Using Camera ID: " + targetCameraId + " for characteristic lookup.");

        // Get the available AE FPS ranges for the target camera
        Range<Integer>[] hardwareFpsRanges = null;
        try {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(targetCameraId);
            hardwareFpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        } catch (CameraAccessException | IllegalArgumentException e) {
            Log.e(TAG, "FPS Query: Camera access/arg exception getting FPS ranges for ID " + targetCameraId,
                    e);
            // Return default [30] on error accessing ranges
            return defaultRateList;
        } catch (Exception e) {
            Log.e(TAG, "FPS Query: Unexpected error getting FPS ranges for ID " + targetCameraId, e);
            return defaultRateList;
        }

        // Create a set to store all possible framerates from the ranges
        Set<Integer> framerates = new TreeSet<>(); // TreeSet automatically sorts

        // First check for higher framerates in CamcorderProfiles
        // This is important because some devices don't report high FPS in Camera2 AE
        // ranges
        // but do support them in CamcorderProfile
        int maxProfileFps = 30; // Default assumption

        try {
            // Check all quality levels for max framerates
            int cameraId = Integer.parseInt(targetCameraId);
            int[] qualities = {
                    CamcorderProfile.QUALITY_HIGH, CamcorderProfile.QUALITY_2160P,
                    CamcorderProfile.QUALITY_1080P, CamcorderProfile.QUALITY_720P
            };

            for (int quality : qualities) {
                if (CamcorderProfile.hasProfile(cameraId, quality)) {
                    CamcorderProfile profile = CamcorderProfile.get(cameraId, quality);
                    if (profile != null && profile.videoFrameRate > maxProfileFps) {
                        maxProfileFps = profile.videoFrameRate;
                        Log.d(TAG, "FPS Query: Found higher framerate " + maxProfileFps +
                                " in CamcorderProfile quality " + quality);
                    }
                }
            }

            // Check for specific high-framerate profiles if they exist
            if (Build.VERSION.SDK_INT >= 29) { // Android 10+
                try {
                    // Some devices have specific high-FPS profiles for 60fps/120fps
                    if (CamcorderProfile.hasProfile(cameraId, CamcorderProfile.QUALITY_HIGH_SPEED_HIGH)) {
                        CamcorderProfile profile = CamcorderProfile.get(cameraId,
                                CamcorderProfile.QUALITY_HIGH_SPEED_HIGH);
                        if (profile != null && profile.videoFrameRate > maxProfileFps) {
                            maxProfileFps = profile.videoFrameRate;
                            Log.d(TAG, "FPS Query: Found high-speed framerate " +
                                    maxProfileFps + " in QUALITY_HIGH_SPEED_HIGH");
                        }
                    }

                    if (CamcorderProfile.hasProfile(cameraId, CamcorderProfile.QUALITY_HIGH_SPEED_1080P)) {
                        CamcorderProfile profile = CamcorderProfile.get(cameraId,
                                CamcorderProfile.QUALITY_HIGH_SPEED_1080P);
                        if (profile != null && profile.videoFrameRate > maxProfileFps) {
                            maxProfileFps = profile.videoFrameRate;
                            Log.d(TAG, "FPS Query: Found high-speed framerate " +
                                    maxProfileFps + " in QUALITY_HIGH_SPEED_1080P");
                        }
                    }

                    if (CamcorderProfile.hasProfile(cameraId, CamcorderProfile.QUALITY_HIGH_SPEED_720P)) {
                        CamcorderProfile profile = CamcorderProfile.get(cameraId,
                                CamcorderProfile.QUALITY_HIGH_SPEED_720P);
                        if (profile != null && profile.videoFrameRate > maxProfileFps) {
                            maxProfileFps = profile.videoFrameRate;
                            Log.d(TAG, "FPS Query: Found high-speed framerate " +
                                    maxProfileFps + " in QUALITY_HIGH_SPEED_720P");
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "FPS Query: Error checking high-speed profiles: " + e.getMessage());
                }
            }

            Log.d(TAG, "FPS Query: Maximum framerate found in CamcorderProfiles: " + maxProfileFps);

        } catch (NumberFormatException e) {
            Log.w(TAG, "FPS Query: Could not parse camera ID as integer: " + targetCameraId);
            // Continue with Camera2 API method only
        } catch (Exception e) {
            Log.w(TAG, "FPS Query: Error checking CamcorderProfiles: " + e.getMessage());
            // Continue with Camera2 API method only
        }

        if (hardwareFpsRanges == null || hardwareFpsRanges.length == 0) {
            Log.w(TAG, "FPS Query: No AE FPS ranges reported by hardware for camera " + targetCameraId
                    + ". Using CamcorderProfile.");
            // Create some basic framerates based on CamcorderProfile information
            for (int fps = 10; fps <= maxProfileFps; fps += 5) {
                if (fps <= 30 || fps % 30 == 0) { // Include all multiples of 30 over 30fps
                    framerates.add(fps);
                }
            }

            // Add standard framerates
            int[] standardRates = { 24, 25, 30, 60, 90, 120 };
            for (int rate : standardRates) {
                if (rate <= maxProfileFps) {
                    framerates.add(rate);
                }
            }

            if (framerates.isEmpty()) {
                framerates.add(Constants.DEFAULT_VIDEO_FRAME_RATE); // Default fallback
            }
        } else {
            Log.d(TAG, "FPS Query: Hardware reported AE ranges for ID " + targetCameraId + ": "
                    + Arrays.toString(hardwareFpsRanges));

            // Process each range to get ALL supported framerates
            for (Range<Integer> range : hardwareFpsRanges) {
                if (range != null) {
                    int lower = range.getLower();
                    int upper = range.getUpper();

                    Log.d(TAG, "FPS Query: Processing range " + lower + "-" + upper);

                    // For most devices, framerates are available at discrete steps (usually 1fps)
                    // Add ALL integer values within the range to ensure we catch values like 59fps
                    for (int fps = lower; fps <= upper; fps++) {
                        framerates.add(fps);
                    }
                }
            }

            // If CamcorderProfile reported higher framerates than Camera2 API, add those
            // too
            if (maxProfileFps > 30) {
                Log.d(TAG, "FPS Query: Adding higher framerates from CamcorderProfile");

                // Add standard high framerates if they're supported by the profile
                int[] highRates = { 60, 90, 120, 240 };
                for (int rate : highRates) {
                    if (rate <= maxProfileFps) {
                        framerates.add(rate);
                        Log.d(TAG, "FPS Query: Added " + rate + "fps from CamcorderProfile");
                    }
                }
            }
        }

        // Ensure we have at least one value (the default)
        if (framerates.isEmpty()) {
            Log.e(TAG, "FPS Query: No valid framerates found from hardware ranges. Adding default: "
                    + Constants.DEFAULT_VIDEO_FRAME_RATE);
            framerates.add(Constants.DEFAULT_VIDEO_FRAME_RATE);
        }

        // Convert to list and ensure the list is sorted (which TreeSet already does)
        List<Integer> finalSupportedRates = new ArrayList<>(framerates);

        // If the list is too large (some devices might report hundreds of values),
        // we could optionally filter to keep just common/useful values or step at
        // 5-10fps intervals
        if (finalSupportedRates.size() > 20) {
            Log.w(TAG, "FPS Query: Large number of framerates detected (" + finalSupportedRates.size() +
                    "), keeping only useful values for UI");

            // Filter to keep standard values + any higher FPS values
            Set<Integer> filteredRates = new TreeSet<>();

            // Important standard rates to always include if supported
            int[] standardRates = { 24, 25, 30, 50, 60, 90, 120, 240 };
            for (int rate : standardRates) {
                if (framerates.contains(rate)) {
                    filteredRates.add(rate);
                }
            }

            // Also include significant non-standard rates
            // This handles cases like 59.94fps (which is often rounded to 59 or 60)
            for (int fps : framerates) {
                // Include rates divisible by 5 (e.g., 5, 10, 15, 20, 25...)
                if (fps % 5 == 0 && fps <= 60) {
                    filteredRates.add(fps);
                }
                // Include all higher framerates (e.g., 72, 90, 120, etc.)
                else if (fps > 60) {
                    filteredRates.add(fps);
                }
            }

            // If we've excluded the default rate by accident, add it back
            if (!filteredRates.contains(Constants.DEFAULT_VIDEO_FRAME_RATE) &&
                    framerates.contains(Constants.DEFAULT_VIDEO_FRAME_RATE)) {
                filteredRates.add(Constants.DEFAULT_VIDEO_FRAME_RATE);
            }

            // Replace the full list with our filtered list
            finalSupportedRates = new ArrayList<>(filteredRates);
            Log.d(TAG, "FPS Query: Filtered to " + finalSupportedRates.size() + " useful framerates");
        }

        return finalSupportedRates;
    }

}
