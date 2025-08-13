package com.fadcam.ui;

import android.content.Context;
import android.content.Intent;
import android.app.PendingIntent;
import android.content.IntentSender;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;
import androidx.fragment.app.Fragment;

import com.fadcam.R;

/**
 * ShortcutsSettingsFragment
 * Full-screen settings screen listing Start/Stop/Torch shortcuts. Clicking the icon pins to home.
 * Row click uses the unified PickerBottomSheetFragment to confirm pin.
 */
public class ShortcutsSettingsFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings_shortcuts, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // -------------- Fix Start for this method(onViewCreated)-----------
        View back = view.findViewById(R.id.back_button);
        if(back!=null){ back.setOnClickListener(v -> OverlayNavUtil.dismiss(requireActivity())); }

    TextView helper = view.findViewById(R.id.shortcuts_helper);
    if(helper!=null){ helper.setText(getString(R.string.shortcuts_helper_text)); }

    wireShortcutRow(view, R.id.cell_start, R.id.icon_start, R.drawable.start_shortcut,
                getString(R.string.start_recording),
                new Intent(Intent.ACTION_VIEW).setClassName(requireContext(), "com.fadcam.RecordingStartActivity"),
                "record_start");
    wireShortcutRow(view, R.id.cell_stop, R.id.icon_stop, R.drawable.stop_shortcut,
                getString(R.string.stop_recording),
                new Intent(Intent.ACTION_VIEW).setClassName(requireContext(), "com.fadcam.RecordingStopActivity"),
                "record_stop");
    wireShortcutRow(view, R.id.cell_torch, R.id.icon_torch, R.drawable.flashlight_shortcut,
                getString(R.string.torch_shortcut_short_label),
                new Intent(Intent.ACTION_VIEW).setClassName(requireContext(), "com.fadcam.TorchToggleActivity"),
                "torch_toggle");
        // -------------- Fix Ended for this method(onViewCreated)-----------
    }

    private void wireShortcutRow(View root, int rowId, int iconId, int iconRes, String title, Intent intent, String shortcutId){
        LinearLayout row = root.findViewById(rowId);
        ImageView icon = root.findViewById(iconId);
        TextView label = root.findViewById(titleIdFor(rowId));
        if(label!=null) label.setText(title);

        // Icon click must also show confirmation (no direct pinning)
        if(icon!=null){ icon.setOnClickListener(v -> showConfirmSheet(title, shortcutId, iconRes, intent)); }

        if(row!=null){
            row.setOnClickListener(v -> showConfirmSheet(title, shortcutId, iconRes, intent));
        }
    }

    private int titleIdFor(int rowId){
        if(rowId == R.id.cell_start) return R.id.title_start;
        if(rowId == R.id.cell_stop) return R.id.title_stop;
        return R.id.title_torch;
    }

    private void requestPin(String shortcutId, String label, int iconRes, Intent intent){
        Context ctx = requireContext();
        if(!ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)){
            android.widget.Toast.makeText(ctx, R.string.widgets_pin_unsupported, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        ShortcutInfoCompat info = new ShortcutInfoCompat.Builder(ctx, shortcutId)
                .setShortLabel(label)
                .setIcon(IconCompat.createWithResource(ctx, iconRes))
                .setIntent(intent)
                .build();
    ShortcutManagerCompat.requestPinShortcut(ctx, info, null);
    }

    private void showConfirmSheet(String title, String shortcutId, int iconRes, Intent intent){
        final String resultKey = "picker_result_pin_" + shortcutId;
        getParentFragmentManager().setFragmentResultListener(resultKey, this, (k,b)->{
            if(b.containsKey(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID)){
                String selected = b.getString(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
                if("pin".equals(selected)){
                    requestPin(shortcutId, title, iconRes, intent);
                }
            }
        });
    java.util.ArrayList<com.fadcam.ui.picker.OptionItem> items = new java.util.ArrayList<>();
    // Leading icon is the shortcut icon; trailing is external-link indicator
    items.add(new com.fadcam.ui.picker.OptionItem("pin", getString(R.string.shortcuts_add_to_home), (String) null, null, iconRes, R.drawable.ic_open_in_new));
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet = com.fadcam.ui.picker.PickerBottomSheetFragment.newInstance(
                title, items, null, resultKey, getString(R.string.shortcuts_sheet_helper));
        // Pass grid mode false, but we want to show the shortcut icon prominently in the title area by setting header icon view if supported
        // As a simple approach, we prefix title with an inline space and rely on icon in option; alternative is to extend picker layout (deferred)
        sheet.show(getParentFragmentManager(), "pin_sheet_"+shortcutId);
    }
}
