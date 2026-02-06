package com.fadcam.dualcam.ui;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.dualcam.DualCameraCapability;
import com.fadcam.dualcam.DualCameraConfig;
import com.fadcam.ui.OverlayNavUtil;
import com.fadcam.ui.picker.OptionItem;
import com.fadcam.ui.picker.NumberInputBottomSheetFragment;
import com.fadcam.ui.picker.PickerBottomSheetFragment;

import java.util.ArrayList;
import java.util.Locale;

/**
 * DualCameraSettingsFragment
 * Full-screen overlay settings for Dual Camera (PiP) configuration.
 *
 * <p>Features:
 * <ul>
 *   <li>Compatibility banner showing device support status (tap for details)</li>
 *   <li>Live PiP preview that updates instantly when settings change</li>
 *   <li>Layout settings: PiP Position, PiP Size, Primary Camera</li>
 *   <li>Appearance settings: PiP Border, Rounded Corners, PiP Margin</li>
 * </ul>
 */
public class DualCameraSettingsFragment extends Fragment {

    private static final String TAG = "DualCamSettings";

    // Preview constants
    private static final int PIP_COLOR_DEFAULT = 0xFF333333;
    private static final int PIP_BORDER_COLOR = 0xFFFFFFFF;
    private static final int PIP_BORDER_WIDTH_PX = 3;
    private static final int PIP_CORNER_RADIUS_PX = 16;

    private SharedPreferencesManager prefs;
    private DualCameraConfig.Builder configBuilder;

    // Banner views
    private LinearLayout bannerCompatibility;
    private TextView textBannerMessage;

    // Preview views
    private FrameLayout previewContainer;
    private FrameLayout previewPip;
    private TextView previewPrimaryLabel;
    private TextView previewPipLabel;

    // Value TextViews
    private TextView valuePipPosition;
    private TextView valuePipSize;
    private TextView valuePrimaryCamera;
    private TextView valueShowBorder;
    private TextView valueRoundCorners;
    private TextView valuePipMargin;

    // Capability cached
    private DualCameraCapability dualCapability;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings_dual_camera, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        prefs = SharedPreferencesManager.getInstance(requireContext());
        configBuilder = new DualCameraConfig.Builder(prefs.getDualCameraConfig());
        dualCapability = new DualCameraCapability(requireContext());

        bindViews(view);
        bindRowHandlers(view);
        setupBanner();
        refreshAllValues();
        updatePreview();

        View back = view.findViewById(R.id.back_button);
        if (back != null) {
            back.setOnClickListener(v -> OverlayNavUtil.dismiss(requireActivity()));
        }
    }

    // ── View Binding ───────────────────────────────────────────────────

    private void bindViews(View root) {
        // Banner
        bannerCompatibility = root.findViewById(R.id.banner_compatibility);
        textBannerMessage = root.findViewById(R.id.text_banner_message);

        // Preview
        previewContainer = root.findViewById(R.id.preview_container);
        previewPip = root.findViewById(R.id.preview_pip);
        previewPrimaryLabel = root.findViewById(R.id.preview_primary_label);
        previewPipLabel = root.findViewById(R.id.preview_pip_label);

        // Values
        valuePipPosition = root.findViewById(R.id.value_pip_position);
        valuePipSize = root.findViewById(R.id.value_pip_size);
        valuePrimaryCamera = root.findViewById(R.id.value_primary_camera);
        valueShowBorder = root.findViewById(R.id.value_show_border);
        valueRoundCorners = root.findViewById(R.id.value_round_corners);
        valuePipMargin = root.findViewById(R.id.value_pip_margin);
    }

    private void bindRowHandlers(View root) {
        root.findViewById(R.id.row_pip_position).setOnClickListener(v -> showPipPositionPicker());
        root.findViewById(R.id.row_pip_size).setOnClickListener(v -> showPipSizePicker());
        root.findViewById(R.id.row_primary_camera).setOnClickListener(v -> showPrimaryCameraPicker());
        root.findViewById(R.id.row_show_border).setOnClickListener(v -> showBorderToggle());
        root.findViewById(R.id.row_round_corners).setOnClickListener(v -> showRoundCornersToggle());
        root.findViewById(R.id.row_pip_margin).setOnClickListener(v -> showPipMarginInput());
    }

    // ── Compatibility Banner ───────────────────────────────────────────

    private void setupBanner() {
        if (bannerCompatibility == null || textBannerMessage == null) return;

        boolean supported = dualCapability.isSupported();
        boolean confirmed = dualCapability.isConcurrentApiConfirmed();

        if (confirmed) {
            // Green info banner — fully confirmed
            bannerCompatibility.setBackgroundResource(R.drawable.dual_cam_info_banner_bg);
            View iconView = bannerCompatibility.getChildAt(0);
            if (iconView instanceof TextView) {
                ((TextView) iconView).setTextColor(0xFF4CAF50);
            }
            textBannerMessage.setText(R.string.dual_cam_banner_supported_confirmed);
            textBannerMessage.setTextColor(0xFFA5D6A7);
        } else if (supported) {
            // Amber/orange banner — has both cameras but NOT confirmed via concurrent API
            bannerCompatibility.setBackgroundResource(R.drawable.dual_cam_warning_banner_bg);
            textBannerMessage.setText(R.string.dual_cam_banner_supported);
            textBannerMessage.setTextColor(0xFFFFD180);
        } else {
            // Orange warning banner — unsupported
            bannerCompatibility.setBackgroundResource(R.drawable.dual_cam_warning_banner_bg);
            textBannerMessage.setText(R.string.dual_cam_banner_unsupported);
            textBannerMessage.setTextColor(0xFFFFD180);
        }

        // Tap for device info details — uses dedicated bottom sheet
        bannerCompatibility.setOnClickListener(v -> showDeviceInfoSheet());
    }

    /**
     * Shows the detailed device compatibility bottom sheet.
     */
    private void showDeviceInfoSheet() {
        DualCameraInfoBottomSheet sheet = DualCameraInfoBottomSheet.newInstance();
        sheet.show(getParentFragmentManager(), "device_info_sheet");
    }

    // ── Live Preview ───────────────────────────────────────────────────

    /**
     * Updates the live PiP preview to reflect current config values.
     * Adjusts PiP position, size, margin, border and corner rounding.
     */
    private void updatePreview() {
        if (previewContainer == null || previewPip == null) return;

        DualCameraConfig config = prefs.getDualCameraConfig();

        // 1. Position — map PipPosition to layout_gravity
        int gravity = mapPositionToGravity(config.getPipPosition());
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) previewPip.getLayoutParams();
        if (params == null) {
            params = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
        }
        params.gravity = gravity;

        // 2. Size — compute PiP dimensions as ratio of container
        // Measure container width (post-layout). Use fixed fallback if not yet laid out.
        int containerWidth = previewContainer.getWidth();
        int containerHeight = previewContainer.getHeight();
        if (containerWidth <= 0) containerWidth = 600; // fallback px
        if (containerHeight <= 0) containerHeight = 400;

        float ratio = config.getPipSize().ratio;
        int pipWidth = Math.round(containerWidth * ratio);
        int pipHeight = Math.round(containerHeight * ratio);
        params.width = pipWidth;
        params.height = pipHeight;

        // 3. Margin — convert dp to px
        float density = getResources().getDisplayMetrics().density;
        int marginPx = Math.round(config.getPipMarginDp() * density);
        params.setMargins(marginPx, marginPx, marginPx, marginPx);

        previewPip.setLayoutParams(params);

        // 4. Border and corners — use GradientDrawable
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(PIP_COLOR_DEFAULT);

        if (config.isShowPipBorder()) {
            bg.setStroke(PIP_BORDER_WIDTH_PX, PIP_BORDER_COLOR);
        }

        if (config.isRoundPipCorners()) {
            bg.setCornerRadius(PIP_CORNER_RADIUS_PX);
        }

        previewPip.setBackground(bg);
        // Clip to outline for rounded corners
        previewPip.setClipToOutline(config.isRoundPipCorners());

        // 5. Update labels
        if (previewPrimaryLabel != null) {
            boolean isPrimaryBack = config.getPrimaryCamera() == DualCameraConfig.PrimaryCamera.BACK;
            previewPrimaryLabel.setText(isPrimaryBack
                    ? getString(R.string.button_settings_cam_back)
                    : getString(R.string.button_settings_cam_front));
        }
        if (previewPipLabel != null) {
            boolean isPrimaryBack = config.getPrimaryCamera() == DualCameraConfig.PrimaryCamera.BACK;
            previewPipLabel.setText(isPrimaryBack
                    ? getString(R.string.button_settings_cam_front)
                    : getString(R.string.button_settings_cam_back));
        }
    }

    /**
     * Maps a {@link DualCameraConfig.PipPosition} to an Android {@link Gravity} constant.
     */
    private int mapPositionToGravity(@NonNull DualCameraConfig.PipPosition position) {
        switch (position) {
            case TOP_LEFT:
                return Gravity.TOP | Gravity.START;
            case TOP_RIGHT:
                return Gravity.TOP | Gravity.END;
            case BOTTOM_LEFT:
                return Gravity.BOTTOM | Gravity.START;
            case BOTTOM_RIGHT:
            default:
                return Gravity.BOTTOM | Gravity.END;
        }
    }

    // ── Refresh Values ─────────────────────────────────────────────────

    private void refreshAllValues() {
        DualCameraConfig config = prefs.getDualCameraConfig();
        configBuilder = new DualCameraConfig.Builder(config);

        if (valuePipPosition != null) {
            valuePipPosition.setText(formatEnum(config.getPipPosition().name()));
        }
        if (valuePipSize != null) {
            valuePipSize.setText(formatPipSize(config.getPipSize()));
        }
        if (valuePrimaryCamera != null) {
            valuePrimaryCamera.setText(formatEnum(config.getPrimaryCamera().name()));
        }
        if (valueShowBorder != null) {
            valueShowBorder.setText(config.isShowPipBorder()
                    ? getString(R.string.universal_enable)
                    : getString(R.string.universal_disable));
        }
        if (valueRoundCorners != null) {
            valueRoundCorners.setText(config.isRoundPipCorners()
                    ? getString(R.string.universal_enable)
                    : getString(R.string.universal_disable));
        }
        if (valuePipMargin != null) {
            valuePipMargin.setText(String.format(Locale.getDefault(), "%d dp", config.getPipMarginDp()));
        }
    }

    /**
     * Called after any setting change: saves config, refreshes values, updates preview.
     */
    private void onSettingChanged() {
        saveConfig();
        refreshAllValues();
        updatePreview();
    }

    // ── PiP Position Picker ────────────────────────────────────────────

    private void showPipPositionPicker() {
        DualCameraConfig config = prefs.getDualCameraConfig();
        ArrayList<OptionItem> items = new ArrayList<>();
        for (DualCameraConfig.PipPosition pos : DualCameraConfig.PipPosition.values()) {
            items.add(new OptionItem(pos.name(), formatEnum(pos.name())));
        }
        String currentId = config.getPipPosition().name();
        final String resultKey = "picker_result_pip_position";

        getParentFragmentManager().setFragmentResultListener(resultKey, this, (k, b) -> {
            String sel = b.getString(PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if (sel != null && !sel.equals(currentId)) {
                try {
                    DualCameraConfig.PipPosition pos = DualCameraConfig.PipPosition.valueOf(sel);
                    configBuilder.pipPosition(pos);
                    onSettingChanged();
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "Invalid PiP position: " + sel, e);
                }
            }
        });

        PickerBottomSheetFragment sheet = PickerBottomSheetFragment.newInstance(
                getString(R.string.dual_cam_pip_position_title), items, currentId, resultKey,
                getString(R.string.dual_cam_pip_position_helper));
        sheet.show(getParentFragmentManager(), "pip_position_picker");
    }

    // ── PiP Size Picker ────────────────────────────────────────────────

    private void showPipSizePicker() {
        DualCameraConfig config = prefs.getDualCameraConfig();
        ArrayList<OptionItem> items = new ArrayList<>();
        for (DualCameraConfig.PipSize size : DualCameraConfig.PipSize.values()) {
            items.add(new OptionItem(size.name(), formatPipSize(size)));
        }
        String currentId = config.getPipSize().name();
        final String resultKey = "picker_result_pip_size";

        getParentFragmentManager().setFragmentResultListener(resultKey, this, (k, b) -> {
            String sel = b.getString(PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if (sel != null && !sel.equals(currentId)) {
                try {
                    DualCameraConfig.PipSize size = DualCameraConfig.PipSize.valueOf(sel);
                    configBuilder.pipSize(size);
                    onSettingChanged();
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "Invalid PiP size: " + sel, e);
                }
            }
        });

        PickerBottomSheetFragment sheet = PickerBottomSheetFragment.newInstance(
                getString(R.string.dual_cam_pip_size_title), items, currentId, resultKey,
                getString(R.string.dual_cam_pip_size_helper));
        sheet.show(getParentFragmentManager(), "pip_size_picker");
    }

    // ── Primary Camera Picker ──────────────────────────────────────────

    private void showPrimaryCameraPicker() {
        DualCameraConfig config = prefs.getDualCameraConfig();
        ArrayList<OptionItem> items = new ArrayList<>();
        for (DualCameraConfig.PrimaryCamera cam : DualCameraConfig.PrimaryCamera.values()) {
            items.add(new OptionItem(cam.name(), formatEnum(cam.name())));
        }
        String currentId = config.getPrimaryCamera().name();
        final String resultKey = "picker_result_primary_camera";

        getParentFragmentManager().setFragmentResultListener(resultKey, this, (k, b) -> {
            String sel = b.getString(PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if (sel != null && !sel.equals(currentId)) {
                try {
                    DualCameraConfig.PrimaryCamera cam = DualCameraConfig.PrimaryCamera.valueOf(sel);
                    configBuilder.primaryCamera(cam);
                    onSettingChanged();
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "Invalid primary camera: " + sel, e);
                }
            }
        });

        PickerBottomSheetFragment sheet = PickerBottomSheetFragment.newInstance(
                getString(R.string.dual_cam_primary_camera_title), items, currentId, resultKey,
                getString(R.string.dual_cam_primary_camera_helper));
        sheet.show(getParentFragmentManager(), "primary_camera_picker");
    }

    // ── Show Border Toggle ─────────────────────────────────────────────

    private void showBorderToggle() {
        DualCameraConfig config = prefs.getDualCameraConfig();
        boolean enabled = config.isShowPipBorder();
        final String resultKey = "picker_result_show_border";

        getParentFragmentManager().setFragmentResultListener(resultKey, this, (k, b) -> {
            if (b.containsKey(PickerBottomSheetFragment.BUNDLE_SWITCH_STATE)) {
                boolean state = b.getBoolean(PickerBottomSheetFragment.BUNDLE_SWITCH_STATE);
                configBuilder.showPipBorder(state);
                onSettingChanged();
            }
        });

        PickerBottomSheetFragment sheet = PickerBottomSheetFragment.newInstanceWithSwitch(
                getString(R.string.dual_cam_show_border_title),
                new ArrayList<>(), null, resultKey,
                getString(R.string.dual_cam_show_border_helper),
                getString(R.string.dual_cam_show_border_title),
                enabled);
        sheet.show(getParentFragmentManager(), "border_toggle_sheet");
    }

    // ── Round Corners Toggle ───────────────────────────────────────────

    private void showRoundCornersToggle() {
        DualCameraConfig config = prefs.getDualCameraConfig();
        boolean enabled = config.isRoundPipCorners();
        final String resultKey = "picker_result_round_corners";

        getParentFragmentManager().setFragmentResultListener(resultKey, this, (k, b) -> {
            if (b.containsKey(PickerBottomSheetFragment.BUNDLE_SWITCH_STATE)) {
                boolean state = b.getBoolean(PickerBottomSheetFragment.BUNDLE_SWITCH_STATE);
                configBuilder.roundPipCorners(state);
                onSettingChanged();
            }
        });

        PickerBottomSheetFragment sheet = PickerBottomSheetFragment.newInstanceWithSwitch(
                getString(R.string.dual_cam_round_corners_title),
                new ArrayList<>(), null, resultKey,
                getString(R.string.dual_cam_round_corners_helper),
                getString(R.string.dual_cam_round_corners_title),
                enabled);
        sheet.show(getParentFragmentManager(), "round_corners_toggle_sheet");
    }

    // ── PiP Margin Input ───────────────────────────────────────────────

    private void showPipMarginInput() {
        DualCameraConfig config = prefs.getDualCameraConfig();
        int currentMargin = config.getPipMarginDp();
        final String resultKey = "picker_result_pip_margin";

        getParentFragmentManager().setFragmentResultListener(resultKey, this, (k, b) -> {
            if (b.containsKey(NumberInputBottomSheetFragment.RESULT_NUMBER)) {
                int val = b.getInt(NumberInputBottomSheetFragment.RESULT_NUMBER);
                configBuilder.pipMarginDp(val);
                onSettingChanged();
            }
        });

        NumberInputBottomSheetFragment sheet = NumberInputBottomSheetFragment.newInstance(
                getString(R.string.dual_cam_pip_margin_title),
                0, 48, currentMargin,
                getString(R.string.dual_cam_pip_margin_hint),
                0, 0,
                null, null,
                resultKey);
        sheet.show(getParentFragmentManager(), "pip_margin_input");
    }

    // ── Config Persistence ─────────────────────────────────────────────

    /**
     * Builds the config from the builder and saves it to SharedPreferences.
     */
    private void saveConfig() {
        DualCameraConfig config = configBuilder.build();
        prefs.saveDualCameraConfig(config);
        Log.d(TAG, "Config saved: " + config);
    }

    // ── Formatting Helpers ─────────────────────────────────────────────

    /**
     * Converts an enum name like "BOTTOM_RIGHT" to "Bottom Right".
     */
    @NonNull
    private static String formatEnum(@NonNull String enumName) {
        String[] words = enumName.toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            sb.append(Character.toUpperCase(w.charAt(0)))
              .append(w.substring(1))
              .append(' ');
        }
        return sb.toString().trim();
    }

    /**
     * Formats a {@link DualCameraConfig.PipSize} value for display.
     */
    @NonNull
    private static String formatPipSize(@NonNull DualCameraConfig.PipSize size) {
        return formatEnum(size.name()) + " (" + Math.round(size.ratio * 100) + "%)";
    }
}
