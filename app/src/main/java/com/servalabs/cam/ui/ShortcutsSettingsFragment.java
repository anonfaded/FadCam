package com.servalabs.cam.ui;

import android.content.Context;
import android.content.Intent;
import android.app.PendingIntent;
import android.content.IntentSender;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;
import androidx.fragment.app.Fragment;

import com.servalabs.cam.R;
import com.servalabs.cam.shortcuts.ShortcutsManager;
import com.servalabs.cam.shortcuts.ShortcutsPreferences;

import org.json.JSONObject;

/**
 * ShortcutsSettingsFragment
 * Full-screen settings screen listing Start/Stop/Torch shortcuts. Clicking the
 * icon pins to home.
 * Row click uses the unified PickerBottomSheetFragment to confirm pin.
 */
public class ShortcutsSettingsFragment extends Fragment {

    private View widgetPreviewContainer;
    private Handler previewUpdateHandler = new Handler(Looper.getMainLooper());
    private Runnable previewUpdateRunnable;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings_shortcuts, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // Init image picker for custom shortcut icons
        initImagePicker();
        View back = view.findViewById(R.id.back_button);
        if (back != null) {
            back.setOnClickListener(v -> OverlayNavUtil.dismiss(requireActivity()));
        }

        TextView helper = view.findViewById(R.id.shortcuts_helper);
        if (helper != null) {
            helper.setText(getString(R.string.shortcuts_helper_text));
        }
        // Ensure dynamic shortcuts reflect current customization when opening screen
        try {
            new ShortcutsManager(requireContext()).publishAllDynamic();
        } catch (Throwable ignored) {
        }

        wireShortcutRow(view, R.id.cell_start, R.id.icon_start, R.drawable.start_back_shortcut,
                getString(R.string.shortcut_start_back),
                new Intent(Intent.ACTION_VIEW)
                        .setClassName(requireContext(), "com.servalabs.cam.RecordingStartActivity")
                        .putExtra(com.servalabs.cam.RecordingStartActivity.EXTRA_SHORTCUT_CAMERA_MODE,
                                com.servalabs.cam.RecordingStartActivity.CAMERA_MODE_BACK),
                ShortcutsManager.ID_START);
        wireShortcutRow(view, R.id.cell_start_front, R.id.icon_start_front, R.drawable.start_front_shortcut,
                getString(R.string.shortcut_start_front),
                new Intent(Intent.ACTION_VIEW)
                        .setClassName(requireContext(), "com.servalabs.cam.RecordingStartActivity")
                        .putExtra(com.servalabs.cam.RecordingStartActivity.EXTRA_SHORTCUT_CAMERA_MODE,
                                com.servalabs.cam.RecordingStartActivity.CAMERA_MODE_FRONT),
                ShortcutsManager.ID_START_FRONT);
        wireShortcutRow(view, R.id.cell_start_current, R.id.icon_start_current, R.drawable.start_current_shortcut,
                getString(R.string.shortcut_start_current),
                new Intent(Intent.ACTION_VIEW)
                        .setClassName(requireContext(), "com.servalabs.cam.RecordingStartActivity")
                        .putExtra(com.servalabs.cam.RecordingStartActivity.EXTRA_SHORTCUT_CAMERA_MODE,
                                com.servalabs.cam.RecordingStartActivity.CAMERA_MODE_CURRENT),
                ShortcutsManager.ID_START_CURRENT);
        wireShortcutRow(view, R.id.cell_start_dual, R.id.icon_start_dual, R.drawable.start_dual_shortcut,
                getString(R.string.shortcut_start_dual),
                new Intent(Intent.ACTION_VIEW)
                        .setClassName(requireContext(), "com.servalabs.cam.RecordingStartActivity")
                        .putExtra(com.servalabs.cam.RecordingStartActivity.EXTRA_SHORTCUT_CAMERA_MODE,
                                com.servalabs.cam.RecordingStartActivity.CAMERA_MODE_DUAL),
                ShortcutsManager.ID_START_DUAL);
        wireShortcutRow(view, R.id.cell_stop, R.id.icon_stop, R.drawable.stop_shortcut,
                getString(R.string.stop_recording),
                new Intent(Intent.ACTION_VIEW).setClassName(requireContext(), "com.servalabs.cam.RecordingStopActivity"),
                ShortcutsManager.ID_STOP);
        wireShortcutRow(view, R.id.cell_torch, R.id.icon_torch, R.drawable.flashlight_shortcut,
                getString(R.string.torch_shortcut_short_label),
                new Intent(Intent.ACTION_VIEW).setClassName(requireContext(), "com.servalabs.cam.TorchToggleActivity"),
                ShortcutsManager.ID_TORCH);
        wireShortcutRow(view, R.id.cell_photo, R.id.icon_photo, R.drawable.servashot_shortcut,
                getString(R.string.shortcut_take_photo),
                new Intent(Intent.ACTION_VIEW).setClassName(requireContext(), "com.servalabs.cam.PhotoCaptureActivity"),
                ShortcutsManager.ID_SHOT);
        wireShortcutRow(view, R.id.cell_photo_front, R.id.icon_photo_front, R.drawable.servashot_front_shortcut,
                getString(R.string.shortcut_take_photo_front),
                new Intent(Intent.ACTION_VIEW)
                        .setClassName(requireContext(), "com.servalabs.cam.PhotoCaptureActivity")
                        .putExtra(com.servalabs.cam.PhotoCaptureActivity.EXTRA_SHORTCUT_PHOTO_CAMERA_MODE,
                                com.servalabs.cam.PhotoCaptureActivity.PHOTO_CAMERA_MODE_FRONT),
                ShortcutsManager.ID_SHOT_FRONT);
        wireShortcutRow(view, R.id.cell_screenshot, R.id.icon_screenshot, R.drawable.servarec_screenshot_shortcut,
                getString(R.string.shortcut_take_screenshot),
                new Intent(Intent.ACTION_VIEW).setClassName(requireContext(), "com.servalabs.cam.ScreenShotCaptureActivity"),
                ShortcutsManager.ID_SCREENSHOT);

        // Unify ripple/click: handle click on parent container only
        View widgetCell = view.findViewById(R.id.cell_widget_clock);
        View.OnClickListener widgetClick = v -> showClockWidgetSheet();
        if (widgetCell != null)
            widgetCell.setOnClickListener(widgetClick);

        // Initialize preview with current preferences
        updatePreview();
    }

    private void wireShortcutRow(View root, int rowId, int iconId, int iconRes, String title, Intent intent,
            String shortcutId) {
        LinearLayout row = root.findViewById(rowId);
        ImageView icon = root.findViewById(iconId);
        TextView label = root.findViewById(titleIdFor(rowId));
        // Also show immutable codename/purpose under the label if a dedicated subtitle
        // view exists
        TextView subtitle = null;
        if (rowId == R.id.cell_start) {
            subtitle = root.findViewById(R.id.subtitle_start);
        } else if (rowId == R.id.cell_start_front) {
            subtitle = root.findViewById(R.id.subtitle_start_front);
        } else if (rowId == R.id.cell_start_current) {
            subtitle = root.findViewById(R.id.subtitle_start_current);
        } else if (rowId == R.id.cell_start_dual) {
            subtitle = root.findViewById(R.id.subtitle_start_dual);
        } else if (rowId == R.id.cell_stop) {
            subtitle = root.findViewById(R.id.subtitle_stop);
        } else if (rowId == R.id.cell_torch) {
            subtitle = root.findViewById(R.id.subtitle_torch);
        } else if (rowId == R.id.cell_photo) {
            subtitle = root.findViewById(R.id.subtitle_photo);
        } else if (rowId == R.id.cell_photo_front) {
            subtitle = root.findViewById(R.id.subtitle_photo_front);
        } else if (rowId == R.id.cell_screenshot) {
            subtitle = root.findViewById(R.id.subtitle_screenshot);
        }
        // Apply custom label/icon if present
        ShortcutsPreferences sp = new ShortcutsPreferences(requireContext());
        String custom = sp.getCustomLabel(shortcutId);
        if (label != null)
            label.setText(custom != null ? custom : title);
        if (subtitle != null) {
            // Show a small badge with the canonical action name (no codename/id)
            String badgeText = title; // localized label like Start/Stop/Torch
            // Special badge text for photo shortcuts
            if (ShortcutsManager.ID_SHOT.equals(shortcutId)) {
                badgeText = "ServaShot Back";
            } else if (ShortcutsManager.ID_SHOT_FRONT.equals(shortcutId)) {
                badgeText = "ServaShot Front";
            }
            subtitle.setText(badgeText);
            subtitle.setVisibility(View.VISIBLE);
            // Apply background badge color based on shortcut
            if (ShortcutsManager.ID_START.equals(shortcutId)) {
                subtitle.setBackgroundResource(R.drawable.badge_blue);
            } else if (ShortcutsManager.ID_START_FRONT.equals(shortcutId)) {
                subtitle.setBackgroundResource(R.drawable.badge_purple);
            } else if (ShortcutsManager.ID_START_CURRENT.equals(shortcutId)) {
                subtitle.setBackgroundResource(R.drawable.badge_green);
            } else if (ShortcutsManager.ID_START_DUAL.equals(shortcutId)) {
                subtitle.setBackgroundResource(R.drawable.badge_cyan);
            } else if (ShortcutsManager.ID_STOP.equals(shortcutId)) {
                subtitle.setBackgroundResource(R.drawable.badge_red);
            } else if (ShortcutsManager.ID_TORCH.equals(shortcutId)) {
                subtitle.setBackgroundResource(R.drawable.badge_orange);
            } else if (ShortcutsManager.ID_SHOT.equals(shortcutId)) {
                subtitle.setBackgroundResource(R.drawable.badge_white);
            } else if (ShortcutsManager.ID_SHOT_FRONT.equals(shortcutId)) {
                subtitle.setBackgroundResource(R.drawable.badge_white);
            } else if (ShortcutsManager.ID_SCREENSHOT.equals(shortcutId)) {
                subtitle.setBackgroundResource(R.drawable.badge_orange);
            }
        }
        if (icon != null)
            loadShortcutIconInto(icon, shortcutId, iconRes);

        // Icon click must also show confirmation (no direct pinning)
        if (icon != null) {
            icon.setOnClickListener(v -> showShortcutSheet(title, shortcutId, iconRes, intent));
        }

        if (row != null) {
            row.setOnClickListener(v -> showShortcutSheet(title, shortcutId, iconRes, intent));
        }
    }

    private int titleIdFor(int rowId) {
        if (rowId == R.id.cell_start)
            return R.id.title_start;
        if (rowId == R.id.cell_start_front)
            return R.id.title_start_front;
        if (rowId == R.id.cell_start_current)
            return R.id.title_start_current;
        if (rowId == R.id.cell_start_dual)
            return R.id.title_start_dual;
        if (rowId == R.id.cell_stop)
            return R.id.title_stop;
        if (rowId == R.id.cell_torch)
            return R.id.title_torch;
        if (rowId == R.id.cell_photo_front)
            return R.id.title_photo_front;
        if (rowId == R.id.cell_screenshot)
            return R.id.title_screenshot;
        return R.id.title_photo;
    }

    // Removed codename display; using concise badges instead.

    private void requestPin(String shortcutId, String defaultLabel, int iconRes, Intent intent) {
        Context ctx = requireContext();
        ShortcutsManager sm = new ShortcutsManager(ctx);
        if (!sm.isPinSupported()) {
            android.widget.Toast.makeText(ctx, R.string.widgets_pin_unsupported, android.widget.Toast.LENGTH_SHORT)
                    .show();
            return;
        }
        ShortcutInfoCompat infoPin = sm.buildShortcutForPin(shortcutId, intent, iconRes, defaultLabel);
        ShortcutInfoCompat infoBase = sm.buildShortcut(shortcutId, intent, iconRes, defaultLabel);
        // Push both base and pin ids as dynamic to override any static fallback paths
        try {
            java.util.List<ShortcutInfoCompat> list = new java.util.ArrayList<>();
            list.add(infoBase);
            list.add(infoPin);
            for (ShortcutInfoCompat s : list) {
                androidx.core.content.pm.ShortcutManagerCompat.pushDynamicShortcut(ctx, s);
            }
            // Update too, so pinned shortcuts refresh immediately on some launchers
            androidx.core.content.pm.ShortcutManagerCompat.updateShortcuts(ctx, list);
        } catch (Throwable ignored) {
        }
        sm.requestPin(infoPin);
    }

    private void showShortcutSheet(String title, String shortcutId, int iconRes, Intent intent) {
        final String resultKey = "picker_result_pin_" + shortcutId;
        getParentFragmentManager().setFragmentResultListener(resultKey, this, (k, b) -> {
            if (b.containsKey(com.servalabs.cam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID)) {
                String selected = b.getString(com.servalabs.cam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
                if ("pin".equals(selected)) {
                    requestPin(shortcutId, title, iconRes, intent);
                } else if ("customize".equals(selected)) {
                    showCustomizeShortcutSheet(shortcutId, title, iconRes, intent);
                }
            }
        });
        java.util.ArrayList<com.servalabs.cam.ui.picker.OptionItem> items = new java.util.ArrayList<>();
        // Leading icon is the shortcut icon; trailing is external-link indicator
        items.add(new com.servalabs.cam.ui.picker.OptionItem("pin", getString(R.string.shortcuts_add_to_home), (String) null,
                null, iconRes, R.drawable.ic_open_in_new));
        items.add(new com.servalabs.cam.ui.picker.OptionItem(
                "customize",
                getString(R.string.shortcuts_customize_title),
                getString(R.string.shortcuts_customize_subtitle),
                null,
                R.drawable.ic_settings,
                R.drawable.ic_arrow_right));
        com.servalabs.cam.ui.picker.PickerBottomSheetFragment sheet = com.servalabs.cam.ui.picker.PickerBottomSheetFragment
                .newInstance(
                        title, items, null, resultKey,
                        getString(R.string.shortcuts_sheet_helper) + "\n" + getShortcutPurposeLine(shortcutId));
        // Hide selection check; we only show trailing external-link icon
        if (sheet.getArguments() != null) {
            sheet.getArguments().putBoolean(com.servalabs.cam.ui.picker.PickerBottomSheetFragment.ARG_HIDE_CHECK, true);
        }
        // Pass grid mode false, but we want to show the shortcut icon prominently in
        // the title area by setting header icon view if supported
        // As a simple approach, we prefix title with an inline space and rely on icon
        // in option; alternative is to extend picker layout (deferred)
        sheet.show(getParentFragmentManager(), "pin_sheet_" + shortcutId);
    }

    // method(showCustomizeShortcutSheet)-----------
    private void showCustomizeShortcutSheet(String shortcutId, String defaultLabel, int defaultIconRes, Intent intent) {
        final String resultKey = "picker_result_customize_" + shortcutId;
        getParentFragmentManager().setFragmentResultListener(resultKey, this, (k, b) -> {
            if (b.containsKey(com.servalabs.cam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID)) {
                String selected = b.getString(com.servalabs.cam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
                if ("rename".equals(selected)) {
                    promptRename(shortcutId, defaultLabel);
                } else if ("change_icon".equals(selected)) {
                    // Show source picker: choose from app icons or from gallery
                    showChangeIconSourceSheet(shortcutId);
                } else if ("reset".equals(selected)) {
                    ShortcutsManager sm = new ShortcutsManager(requireContext());
                    sm.reset(shortcutId);
                    sm.publishAllDynamic();
                    refreshShortcutRows();
                }
            }
        });
        java.util.ArrayList<com.servalabs.cam.ui.picker.OptionItem> items = new java.util.ArrayList<>();
        // We avoid unknown drawable ids by passing null leading icons
        items.add(new com.servalabs.cam.ui.picker.OptionItem("rename", getString(R.string.shortcuts_customize_action_rename),
                null, null, null, R.drawable.ic_arrow_right));
        items.add(new com.servalabs.cam.ui.picker.OptionItem("change_icon",
                getString(R.string.shortcuts_customize_action_change_icon), null, null, null,
                R.drawable.ic_arrow_right));
        items.add(new com.servalabs.cam.ui.picker.OptionItem("reset", getString(R.string.shortcuts_customize_action_reset),
                null, null, null, null));
        com.servalabs.cam.ui.picker.PickerBottomSheetFragment sheet = com.servalabs.cam.ui.picker.PickerBottomSheetFragment
                .newInstance(
                        getString(R.string.shortcuts_customize_title), items, null, resultKey,
                        getString(R.string.shortcuts_customize_sheet_desc));
        if (sheet.getArguments() != null) {
            sheet.getArguments().putBoolean(com.servalabs.cam.ui.picker.PickerBottomSheetFragment.ARG_HIDE_CHECK, true);
        }
        sheet.show(getParentFragmentManager(), "customize_" + shortcutId);
    }
    // method(showCustomizeShortcutSheet)-----------

    // method(showChangeIconSourceSheet)-----------
    private void showChangeIconSourceSheet(String shortcutId) {
        final String resultKey = "picker_result_icon_source_" + shortcutId;
        getParentFragmentManager().setFragmentResultListener(resultKey, this, (k, b) -> {
            if (b.containsKey(com.servalabs.cam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID)) {
                String sel = b.getString(com.servalabs.cam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
                if ("from_app_icons".equals(sel)) {
                    // Open grid of app icons and save selected as shortcut icon
                    ShortcutIconGridBottomSheet sheet = new ShortcutIconGridBottomSheet(iconResId -> {
                        ShortcutsManager sm = new ShortcutsManager(requireContext());
                        if (sm.setCustomIconFromResId(shortcutId, iconResId)) {
                            sm.publishAllDynamic();
                            refreshShortcutRows();
                        }
                    });
                    sheet.show(getParentFragmentManager(), "shortcut_icon_grid_" + shortcutId);
                } else if ("from_gallery".equals(sel)) {
                    pendingIconShortcutId = shortcutId;
                    if (imagePickerLauncher != null) {
                        imagePickerLauncher.launch(new String[] { "image/*" });
                    }
                }
            }
        });
        java.util.ArrayList<com.servalabs.cam.ui.picker.OptionItem> items = new java.util.ArrayList<>();
        items.add(new com.servalabs.cam.ui.picker.OptionItem("from_app_icons",
                getString(R.string.shortcuts_choose_from_app_icons), null, null, R.drawable.ic_theme, null));
        items.add(new com.servalabs.cam.ui.picker.OptionItem("from_gallery", getString(R.string.shortcuts_choose_from_gallery),
                null, null, R.drawable.ic_open_in_new, null));
        com.servalabs.cam.ui.picker.PickerBottomSheetFragment sheet = com.servalabs.cam.ui.picker.PickerBottomSheetFragment
                .newInstance(
                        getString(R.string.shortcuts_choose_icon_title), items, null, resultKey,
                        getString(R.string.shortcuts_choose_icon_desc));
        if (sheet.getArguments() != null) {
            sheet.getArguments().putBoolean(com.servalabs.cam.ui.picker.PickerBottomSheetFragment.ARG_HIDE_CHECK, true);
        }
        sheet.show(getParentFragmentManager(), "icon_source_" + shortcutId);
    }
    // method(showChangeIconSourceSheet)-----------

    private void promptRename(String shortcutId, String defaultLabel) {
        ShortcutsPreferences sp = new ShortcutsPreferences(requireContext());
        String current = sp.getCustomLabel(shortcutId);

        // Use unified InputActionBottomSheetFragment instead of
        // TextInputBottomSheetFragment
        InputActionBottomSheetFragment sheet = InputActionBottomSheetFragment.newInput(
                getString(R.string.shortcuts_customize_dialog_title),
                current != null ? current : defaultLabel,
                defaultLabel,
                getString(R.string.shortcuts_rename_action_title),
                getString(R.string.shortcuts_rename_action_subtitle),
                R.drawable.ic_edit_cut,
                getString(R.string.shortcuts_rename_helper) + "\n" + getShortcutPurposeLine(shortcutId));

        sheet.setCallbacks(new InputActionBottomSheetFragment.Callbacks() {
            @Override
            public void onInputConfirmed(String input) {
                // Persist empty as clearing customization to fall back to default
                ShortcutsManager sm = new ShortcutsManager(requireContext());
                sm.setCustomLabel(shortcutId, (input != null && !input.trim().isEmpty()) ? input.trim() : null);
                sm.publishAllDynamic();
                refreshShortcutRows();
                sheet.dismiss();
            }

            @Override
            public void onResetConfirmed() {
                // Not used in this context, but required by interface
            }

            @Override
            public void onImportConfirmed(JSONObject json) {
                // Not used in this context, but required by interface
            }
        });

        sheet.show(getParentFragmentManager(), "rename_" + shortcutId);
    }

    private String getShortcutPurposeLine(String shortcutId) {
        if (ShortcutsManager.ID_START.equals(shortcutId)) {
            return getString(R.string.shortcut_purpose_start);
        } else if (ShortcutsManager.ID_START_FRONT.equals(shortcutId)) {
            return getString(R.string.shortcut_purpose_start_front);
        } else if (ShortcutsManager.ID_START_CURRENT.equals(shortcutId)) {
            return getString(R.string.shortcut_purpose_start_current);
        } else if (ShortcutsManager.ID_START_DUAL.equals(shortcutId)) {
            return getString(R.string.shortcut_purpose_start_dual);
        } else if (ShortcutsManager.ID_STOP.equals(shortcutId)) {
            return getString(R.string.shortcut_purpose_stop);
        } else if (ShortcutsManager.ID_SHOT.equals(shortcutId)) {
            return getString(R.string.shortcut_purpose_photo);
        } else if (ShortcutsManager.ID_SHOT_FRONT.equals(shortcutId)) {
            return getString(R.string.shortcut_purpose_photo_front);
        } else if (ShortcutsManager.ID_SCREENSHOT.equals(shortcutId)) {
            return getString(R.string.shortcut_purpose_screenshot);
        } else {
            return getString(R.string.shortcut_purpose_torch);
        }
    }

    private void refreshShortcutRows() {
        View view = getView();
        if (view == null)
            return;
        // Start
        wireShortcutRow(view, R.id.cell_start, R.id.icon_start, R.drawable.start_back_shortcut,
                getString(R.string.shortcut_start_back),
                new Intent(Intent.ACTION_VIEW)
                        .setClassName(requireContext(), "com.servalabs.cam.RecordingStartActivity")
                        .putExtra(com.servalabs.cam.RecordingStartActivity.EXTRA_SHORTCUT_CAMERA_MODE,
                                com.servalabs.cam.RecordingStartActivity.CAMERA_MODE_BACK),
                ShortcutsManager.ID_START);
        wireShortcutRow(view, R.id.cell_start_front, R.id.icon_start_front, R.drawable.start_front_shortcut,
                getString(R.string.shortcut_start_front),
                new Intent(Intent.ACTION_VIEW)
                        .setClassName(requireContext(), "com.servalabs.cam.RecordingStartActivity")
                        .putExtra(com.servalabs.cam.RecordingStartActivity.EXTRA_SHORTCUT_CAMERA_MODE,
                                com.servalabs.cam.RecordingStartActivity.CAMERA_MODE_FRONT),
                ShortcutsManager.ID_START_FRONT);
        wireShortcutRow(view, R.id.cell_start_current, R.id.icon_start_current, R.drawable.start_current_shortcut,
                getString(R.string.shortcut_start_current),
                new Intent(Intent.ACTION_VIEW)
                        .setClassName(requireContext(), "com.servalabs.cam.RecordingStartActivity")
                        .putExtra(com.servalabs.cam.RecordingStartActivity.EXTRA_SHORTCUT_CAMERA_MODE,
                                com.servalabs.cam.RecordingStartActivity.CAMERA_MODE_CURRENT),
                ShortcutsManager.ID_START_CURRENT);
        wireShortcutRow(view, R.id.cell_start_dual, R.id.icon_start_dual, R.drawable.start_dual_shortcut,
                getString(R.string.shortcut_start_dual),
                new Intent(Intent.ACTION_VIEW)
                        .setClassName(requireContext(), "com.servalabs.cam.RecordingStartActivity")
                        .putExtra(com.servalabs.cam.RecordingStartActivity.EXTRA_SHORTCUT_CAMERA_MODE,
                                com.servalabs.cam.RecordingStartActivity.CAMERA_MODE_DUAL),
                ShortcutsManager.ID_START_DUAL);
        // Stop
        wireShortcutRow(view, R.id.cell_stop, R.id.icon_stop, R.drawable.stop_shortcut,
                getString(R.string.stop_recording),
                new Intent(Intent.ACTION_VIEW).setClassName(requireContext(), "com.servalabs.cam.RecordingStopActivity"),
                ShortcutsManager.ID_STOP);
        // Torch
        wireShortcutRow(view, R.id.cell_torch, R.id.icon_torch, R.drawable.flashlight_shortcut,
                getString(R.string.torch_shortcut_short_label),
                new Intent(Intent.ACTION_VIEW).setClassName(requireContext(), "com.servalabs.cam.TorchToggleActivity"),
                ShortcutsManager.ID_TORCH);
        // Photo
        wireShortcutRow(view, R.id.cell_photo, R.id.icon_photo, R.drawable.servashot_shortcut,
                getString(R.string.shortcut_take_photo),
                new Intent(Intent.ACTION_VIEW).setClassName(requireContext(), "com.servalabs.cam.PhotoCaptureActivity"),
                ShortcutsManager.ID_SHOT);
        wireShortcutRow(view, R.id.cell_photo_front, R.id.icon_photo_front, R.drawable.servashot_front_shortcut,
                getString(R.string.shortcut_take_photo_front),
                new Intent(Intent.ACTION_VIEW)
                        .setClassName(requireContext(), "com.servalabs.cam.PhotoCaptureActivity")
                        .putExtra(com.servalabs.cam.PhotoCaptureActivity.EXTRA_SHORTCUT_PHOTO_CAMERA_MODE,
                                com.servalabs.cam.PhotoCaptureActivity.PHOTO_CAMERA_MODE_FRONT),
                ShortcutsManager.ID_SHOT_FRONT);
        wireShortcutRow(view, R.id.cell_screenshot, R.id.icon_screenshot, R.drawable.servarec_screenshot_shortcut,
                getString(R.string.shortcut_take_screenshot),
                new Intent(Intent.ACTION_VIEW).setClassName(requireContext(), "com.servalabs.cam.ScreenShotCaptureActivity"),
                ShortcutsManager.ID_SCREENSHOT);
    }

    private void loadShortcutIconInto(ImageView imageView, String shortcutId, int defaultIconRes) {
        ShortcutsPreferences sp = new ShortcutsPreferences(requireContext());
        int res = sp.getCustomIconRes(shortcutId);
        if (res != 0) {
            imageView.setImageResource(res);
            return;
        }
        String path = sp.getCustomIconPath(shortcutId);
        if (path != null) {
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(path);
            if (bmp != null) {
                imageView.setImageBitmap(bmp);
                return;
            }
        }
        imageView.setImageResource(defaultIconRes);
    }

    private ActivityResultLauncher<String[]> imagePickerLauncher;
    private String pendingIconShortcutId;

    private void initImagePicker() {
        imagePickerLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri == null || pendingIconShortcutId == null)
                return;
            // Persist a copy into app storage via ShortcutsPreferences
            ShortcutsManager sm = new ShortcutsManager(requireContext());
            boolean ok = sm.setCustomIconFromUri(pendingIconShortcutId, uri);
            pendingIconShortcutId = null;
            if (ok) {
                sm.publishAllDynamic();
                refreshShortcutRows();
            }
        });
    }

    private void showClockWidgetSheet() {
        final String resultKey = "picker_result_pin_widget_clock";
        getParentFragmentManager().setFragmentResultListener(resultKey, this, (k, b) -> {
            if (b.containsKey(com.servalabs.cam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID)) {
                String selected = b.getString(com.servalabs.cam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
                if ("pin_widget".equals(selected)) {
                    // Delay to allow bottom sheet to dismiss completely before showing system
                    // dialog
                    getView().postDelayed(() -> requestPinClockWidget(), 300);
                } else if ("customize".equals(selected)) {
                    showCustomizeSheet();
                }
            }
        });
        java.util.ArrayList<com.servalabs.cam.ui.picker.OptionItem> items = new java.util.ArrayList<>();
        items.add(new com.servalabs.cam.ui.picker.OptionItem(
                "pin_widget",
                getString(R.string.shortcuts_add_to_home),
                (String) null,
                null,
                R.drawable.ic_clock_widget,
                R.drawable.ic_open_in_new));
        items.add(new com.servalabs.cam.ui.picker.OptionItem(
                "customize",
                getString(R.string.widget_customize_action_label),
                getString(R.string.widget_customize_action_subtitle),
                null,
                R.drawable.ic_settings,
                R.drawable.ic_arrow_right));
        com.servalabs.cam.ui.picker.PickerBottomSheetFragment sheet = com.servalabs.cam.ui.picker.PickerBottomSheetFragment
                .newInstance(
                        getString(R.string.widget_clock_title),
                        items,
                        null,
                        resultKey,
                        getString(R.string.shortcuts_sheet_helper));
        if (sheet.getArguments() != null) {
            sheet.getArguments().putBoolean(com.servalabs.cam.ui.picker.PickerBottomSheetFragment.ARG_HIDE_CHECK, true);
        }
        sheet.show(getParentFragmentManager(), "pin_sheet_widget_clock");
    }

    private void showCustomizeSheet() {
        final String resultKey = "picker_result_customize_widget";
        getParentFragmentManager().setFragmentResultListener(resultKey, this, (k, b) -> {
            if (b.containsKey(com.servalabs.cam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID)) {
                String selected = b.getString(com.servalabs.cam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
                com.servalabs.cam.widgets.WidgetPreferences prefs = new com.servalabs.cam.widgets.WidgetPreferences(requireContext());

                if ("time_format".equals(selected)) {
                    showTimeFormatSheet();
                } else if ("date_format".equals(selected)) {
                    showDateFormatSheet();
                } else if ("show_date".equals(selected)) {
                    // Handle switch toggle for show_date
                    prefs.setShowDate(!prefs.showDate());
                    updateAllWidgets();
                    updatePreview();
                } else if ("black_background".equals(selected)) {
                    // Handle switch toggle for black_background
                    prefs.setBlackBackground(!prefs.hasBlackBackground());
                    updateAllWidgets();
                    updatePreview();

                }
            }
        });

        com.servalabs.cam.widgets.WidgetPreferences prefs = new com.servalabs.cam.widgets.WidgetPreferences(requireContext());
        java.util.ArrayList<com.servalabs.cam.ui.picker.OptionItem> items = new java.util.ArrayList<>();

        items.add(new com.servalabs.cam.ui.picker.OptionItem(
                "time_format",
                getString(R.string.widget_time_format_title),
                prefs.is24HourFormat() ? getString(R.string.widget_time_format_24h)
                        : getString(R.string.widget_time_format_12h),
                null,
                R.drawable.ic_clock_widget,
                R.drawable.ic_arrow_right));
        items.add(new com.servalabs.cam.ui.picker.OptionItem(
                "show_date",
                getString(R.string.widget_show_date_title),
                R.drawable.ic_calendar,
                prefs.showDate()));
        items.add(new com.servalabs.cam.ui.picker.OptionItem(
                "date_format",
                getString(R.string.widget_date_format_title),
                prefs.showDate() ? getString(R.string.widget_date_format_sample)
                        : getString(R.string.widget_option_disabled),
                null,
                R.drawable.ic_calendar,
                R.drawable.ic_arrow_right));

        items.add(new com.servalabs.cam.ui.picker.OptionItem(
                "black_background",
                getString(R.string.widget_black_background_title),
                R.drawable.ic_theme,
                prefs.hasBlackBackground()));

        com.servalabs.cam.ui.picker.PickerBottomSheetFragment sheet = com.servalabs.cam.ui.picker.PickerBottomSheetFragment
                .newInstance(
                        getString(R.string.widget_customize_title),
                        items,
                        null,
                        resultKey,
                        getString(R.string.widget_customize_description));
        // Hide checkboxes for all customize options since they are switches/arrows only
        if (sheet.getArguments() != null) {
            sheet.getArguments().putBoolean("hide_check", true);
        }
        sheet.show(getParentFragmentManager(), "customize_widget");
    }

    private void showTimeFormatSheet() {
        final String resultKey = "picker_result_time_format";
        getParentFragmentManager().setFragmentResultListener(resultKey, this, (k, b) -> {
            if (b.containsKey(com.servalabs.cam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID)) {
                String selected = b.getString(com.servalabs.cam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
                com.servalabs.cam.widgets.WidgetPreferences prefs = new com.servalabs.cam.widgets.WidgetPreferences(requireContext());
                prefs.setTimeFormat("24h".equals(selected));
                updateAllWidgets();
                updatePreview();
                // Show customize sheet again after selection
                new Handler(Looper.getMainLooper()).postDelayed(() -> showCustomizeSheet(), 200);
            }
        });

        com.servalabs.cam.widgets.WidgetPreferences prefs = new com.servalabs.cam.widgets.WidgetPreferences(requireContext());
        String current = prefs.is24HourFormat() ? "24h" : "12h";

        java.util.ArrayList<com.servalabs.cam.ui.picker.OptionItem> items = new java.util.ArrayList<>();
        items.add(new com.servalabs.cam.ui.picker.OptionItem("12h", "12-hour format", "3:30 PM", null, null, null));
        items.add(new com.servalabs.cam.ui.picker.OptionItem("24h", "24-hour format", "15:30", null, null, null));

        com.servalabs.cam.ui.picker.PickerBottomSheetFragment sheet = com.servalabs.cam.ui.picker.PickerBottomSheetFragment
                .newInstance(
                        "Time Format", items, current, resultKey, null);
        sheet.show(getParentFragmentManager(), "time_format");
    }

    private void showDateFormatSheet() {
        final String resultKey = "picker_result_date_format";
        getParentFragmentManager().setFragmentResultListener(resultKey, this, (k, b) -> {
            if (b.containsKey(com.servalabs.cam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID)) {
                String selected = b.getString(com.servalabs.cam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
                com.servalabs.cam.widgets.WidgetPreferences prefs = new com.servalabs.cam.widgets.WidgetPreferences(requireContext());
                prefs.setDateFormat(selected);
                updateAllWidgets();
                updatePreview();
                // Show customize sheet again after selection
                new Handler(Looper.getMainLooper()).postDelayed(() -> showCustomizeSheet(), 200);
            }
        });

        com.servalabs.cam.widgets.WidgetPreferences prefs = new com.servalabs.cam.widgets.WidgetPreferences(requireContext());
        String current = prefs.getDateFormat();

        java.util.ArrayList<com.servalabs.cam.ui.picker.OptionItem> items = new java.util.ArrayList<>();
        items.add(new com.servalabs.cam.ui.picker.OptionItem(com.servalabs.cam.widgets.WidgetPreferences.DATE_FORMAT_DAY_MONTH_YEAR,
                "13 Aug 2025", "day month year", null, null, null));
        items.add(new com.servalabs.cam.ui.picker.OptionItem(com.servalabs.cam.widgets.WidgetPreferences.DATE_FORMAT_MONTH_DAY_YEAR,
                "Aug 13, 2025", "month day year", null, null, null));
        items.add(new com.servalabs.cam.ui.picker.OptionItem(com.servalabs.cam.widgets.WidgetPreferences.DATE_FORMAT_DMY_NUMERIC,
                "13/08/2025", "day/month/year", null, null, null));
        items.add(new com.servalabs.cam.ui.picker.OptionItem(com.servalabs.cam.widgets.WidgetPreferences.DATE_FORMAT_MDY_NUMERIC,
                "08/13/2025", "month/day/year", null, null, null));
        items.add(new com.servalabs.cam.ui.picker.OptionItem(com.servalabs.cam.widgets.WidgetPreferences.DATE_FORMAT_FULL_DAY_NO_YEAR,
                "Wednesday, 13 Aug", "full day name", null, null, null));
        items.add(
                new com.servalabs.cam.ui.picker.OptionItem(com.servalabs.cam.widgets.WidgetPreferences.DATE_FORMAT_SHORT_DAY_NO_YEAR,
                        "Wed, 13 Aug", "short day name", null, null, null));

        com.servalabs.cam.ui.picker.PickerBottomSheetFragment sheet = com.servalabs.cam.ui.picker.PickerBottomSheetFragment
                .newInstance(
                        "Date Format", items, current, resultKey, null);
        sheet.show(getParentFragmentManager(), "date_format");
    }


    private void updateAllWidgets() {
        android.content.Context ctx = requireContext();
        android.appwidget.AppWidgetManager awm = android.appwidget.AppWidgetManager.getInstance(ctx);
        android.content.ComponentName provider = new android.content.ComponentName(ctx,
                com.servalabs.cam.widgets.ClockWidgetProvider.class);
        int[] ids = awm.getAppWidgetIds(provider);
        if (ids.length > 0) {
            android.content.Intent intent = new android.content.Intent(ctx,
                    com.servalabs.cam.widgets.ClockWidgetProvider.class);
            intent.setAction(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_UPDATE);
            intent.putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_IDS, ids);
            ctx.sendBroadcast(intent);
        }
        updatePreview();
    }

    private void updatePreview() {
        if (getView() == null)
            return;
        View preview = getView().findViewById(R.id.clock_widget_preview);
        if (preview == null)
            return;

        com.servalabs.cam.widgets.WidgetPreferences prefs = new com.servalabs.cam.widgets.WidgetPreferences(requireContext());

        // Update background based on preferences
        if (prefs.hasBlackBackground()) {
            preview.setBackgroundResource(R.drawable.widget_black_background);
        } else {
            preview.setBackground(null);
        }



        // Update time and AM/PM
        java.text.SimpleDateFormat timeFormat;
        if (prefs.is24HourFormat()) {
            timeFormat = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault());
            android.widget.TextView timeView = preview.findViewById(R.id.clock_time_preview);
            android.widget.TextView ampmView = preview.findViewById(R.id.clock_ampm_preview);
            if (timeView != null)
                timeView.setText(timeFormat.format(new java.util.Date()));
            if (ampmView != null)
                ampmView.setVisibility(android.view.View.GONE);
        } else {
            timeFormat = new java.text.SimpleDateFormat("h:mm", java.util.Locale.getDefault());
            String ampm = new java.text.SimpleDateFormat("a", java.util.Locale.getDefault())
                    .format(new java.util.Date());
            android.widget.TextView timeView = preview.findViewById(R.id.clock_time_preview);
            android.widget.TextView ampmView = preview.findViewById(R.id.clock_ampm_preview);
            if (timeView != null)
                timeView.setText(timeFormat.format(new java.util.Date()));
            if (ampmView != null) {
                ampmView.setText(ampm);
                ampmView.setVisibility(android.view.View.VISIBLE);
            }
        }

        // Update date visibility and content (regular date independent of Arabic date)
        android.widget.TextView dateView = preview.findViewById(R.id.clock_date_preview);
        android.view.View dateContainer = preview.findViewById(R.id.date_container_preview);

        // Regular date
        boolean anyDateVisible = false;
        if (prefs.showDate()) {
            String datePattern = prefs.getDateFormat();
            String date = new java.text.SimpleDateFormat(datePattern, java.util.Locale.getDefault())
                    .format(new java.util.Date());
            if (dateView != null) {
                dateView.setText(date);
                dateView.setVisibility(android.view.View.VISIBLE);
            }
            anyDateVisible = true;
        } else if (dateView != null) {
            dateView.setVisibility(android.view.View.GONE);
        }

        // Show the date container if regular date is visible
        if (dateContainer != null) {
            dateContainer.setVisibility(anyDateVisible ? android.view.View.VISIBLE : android.view.View.GONE);
        }
    }

    private int dpToPx(float dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    private float pxToDp(float px) {
        float density = getResources().getDisplayMetrics().density;
        return px / density;
    }

    private void requestPinClockWidget() {
        android.content.Context ctx = requireContext();
        android.appwidget.AppWidgetManager awm = android.appwidget.AppWidgetManager.getInstance(ctx);
        android.content.ComponentName provider = new android.content.ComponentName(ctx,
                com.servalabs.cam.widgets.ClockWidgetProvider.class);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                && awm.isRequestPinAppWidgetSupported()) {
            PendingIntent success = PendingIntent.getActivity(
                    ctx, 0, new Intent(ctx, com.servalabs.cam.MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
            awm.requestPinAppWidget(provider, null, success);
        } else {
            android.widget.Toast.makeText(ctx, R.string.widgets_pin_unsupported, android.widget.Toast.LENGTH_LONG)
                    .show();
        }
    }
}
