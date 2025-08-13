package com.fadcam.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import com.fadcam.R;

/**
 * WidgetsBottomSheetFragment
 * Lets users pin Start/Stop/Torch shortcuts to the launcher and run them.
 */
public class WidgetsBottomSheetFragment extends BottomSheetDialogFragment {

    public static WidgetsBottomSheetFragment newInstance(){
        return new WidgetsBottomSheetFragment();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.sheet_widgets, container, false);
        // -------------- Fix Start for this method(onCreateView)-----------
    wireRow(v, R.id.row_start, R.drawable.ic_play, getString(R.string.start_recording),
        new Intent(Intent.ACTION_VIEW).setClassName(requireContext(), "com.fadcam.RecordingStartActivity"),
                "record_start");
    wireRow(v, R.id.row_stop, R.drawable.ic_stop, getString(R.string.stop_recording),
        new Intent(Intent.ACTION_VIEW).setClassName(requireContext(), "com.fadcam.RecordingStopActivity"),
                "record_stop");
    // Torch uses activity target for consistency
    Intent torchIntent = new Intent(Intent.ACTION_VIEW).setClassName(requireContext(), "com.fadcam.TorchToggleActivity");
    wireRow(v, R.id.row_torch, R.drawable.ic_torch, getString(R.string.torch_shortcut_short_label), torchIntent,
                "torch_toggle");

        TextView helper = v.findViewById(R.id.helper_text);
        if(helper != null){
            helper.setText(getString(R.string.widgets_helper_text));
        }
        // -------------- Fix Ended for this method(onCreateView)-----------
        return v;
    }

    private void wireRow(View root, int rowId, int iconRes, String title, Intent actionIntent, String shortcutId){
    LinearLayout row = root.findViewById(rowId);
        if(row == null) return;
    ImageView icon = row.findViewById(rowId == R.id.row_start ? R.id.item_icon_start : rowId == R.id.row_stop ? R.id.item_icon_stop : R.id.item_icon_torch);
    TextView label = row.findViewById(rowId == R.id.row_start ? R.id.item_title_start : rowId == R.id.row_stop ? R.id.item_title_stop : R.id.item_title_torch);
    ImageButton pin = row.findViewById(rowId == R.id.row_start ? R.id.item_pin_start : rowId == R.id.row_stop ? R.id.item_pin_stop : R.id.item_pin_torch);
        if(icon != null){ icon.setImageResource(iconRes); icon.setImageTintList(ContextCompat.getColorStateList(requireContext(), android.R.color.darker_gray)); }
        if(label != null){ label.setText(title); }

    // Tap does nothing; this sheet is for pinning only.

        // Pin: request shortcut pin
        if(pin != null){
            pin.setOnClickListener(v -> requestPin(shortcutId, title, iconRes, actionIntent));
        }
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
}
