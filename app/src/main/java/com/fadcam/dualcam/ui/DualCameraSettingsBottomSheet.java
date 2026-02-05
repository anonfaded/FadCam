package com.fadcam.dualcam.ui;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.dualcam.DualCameraConfig;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * Bottom sheet for configuring dual camera PiP settings:
 * <ul>
 *   <li>PiP position (4 corners)</li>
 *   <li>PiP size (small / medium / large)</li>
 *   <li>Primary camera (front / back)</li>
 *   <li>Show border toggle</li>
 *   <li>Round corners toggle</li>
 * </ul>
 *
 * <p>All changes are persisted to SharedPreferences immediately.
 */
public class DualCameraSettingsBottomSheet extends BottomSheetDialogFragment {

    public static final String TAG = "DualCamSettings";

    private SharedPreferencesManager prefs;
    private DualCameraConfig.Builder configBuilder;

    /** Factory method. */
    @NonNull
    public static DualCameraSettingsBottomSheet newInstance() {
        return new DualCameraSettingsBottomSheet();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = SharedPreferencesManager.getInstance(requireContext());
        configBuilder = new DualCameraConfig.Builder(prefs.getDualCameraConfig());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        // Build UI programmatically to avoid adding XML in this phase.
        // This keeps the implementation self-contained in Java files.
        return buildSettingsView(inflater.getContext());
    }

    /**
     * Constructs the settings UI programmatically.
     * A future iteration can replace this with an XML layout.
     */
    @NonNull
    private View buildSettingsView(@NonNull android.content.Context ctx) {
        DualCameraConfig current = prefs.getDualCameraConfig();

        // Root vertical layout
        android.widget.LinearLayout root = new android.widget.LinearLayout(ctx);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = dpToPx(ctx, 20);
        root.setPadding(pad, pad, pad, pad);

        // ── Title ─────────────────────────────────────────────────────
        TextView title = new TextView(ctx);
        title.setText("Dual Camera Settings");
        title.setTextSize(18f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, lp());

        addSpacer(root, ctx, 16);

        // ── PiP Position ──────────────────────────────────────────────
        TextView posLabel = new TextView(ctx);
        posLabel.setText("PiP Position");
        posLabel.setTextSize(14f);
        posLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(posLabel, lp());

        RadioGroup posGroup = new RadioGroup(ctx);
        posGroup.setOrientation(RadioGroup.VERTICAL);
        for (DualCameraConfig.PipPosition pos : DualCameraConfig.PipPosition.values()) {
            RadioButton rb = new RadioButton(ctx);
            rb.setText(formatEnum(pos.name()));
            rb.setTag(pos);
            rb.setChecked(pos == current.getPipPosition());
            posGroup.addView(rb);
        }
        posGroup.setOnCheckedChangeListener((group, checkedId) -> {
            RadioButton rb = group.findViewById(checkedId);
            if (rb != null && rb.getTag() instanceof DualCameraConfig.PipPosition) {
                configBuilder.pipPosition((DualCameraConfig.PipPosition) rb.getTag());
                saveConfig();
            }
        });
        root.addView(posGroup, lp());

        addSpacer(root, ctx, 12);

        // ── PiP Size ──────────────────────────────────────────────────
        TextView sizeLabel = new TextView(ctx);
        sizeLabel.setText("PiP Size");
        sizeLabel.setTextSize(14f);
        sizeLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(sizeLabel, lp());

        RadioGroup sizeGroup = new RadioGroup(ctx);
        sizeGroup.setOrientation(RadioGroup.HORIZONTAL);
        for (DualCameraConfig.PipSize size : DualCameraConfig.PipSize.values()) {
            RadioButton rb = new RadioButton(ctx);
            rb.setText(formatEnum(size.name()) + " (" + Math.round(size.ratio * 100) + "%)");
            rb.setTag(size);
            rb.setChecked(size == current.getPipSize());
            sizeGroup.addView(rb);
        }
        sizeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            RadioButton rb = group.findViewById(checkedId);
            if (rb != null && rb.getTag() instanceof DualCameraConfig.PipSize) {
                configBuilder.pipSize((DualCameraConfig.PipSize) rb.getTag());
                saveConfig();
            }
        });
        root.addView(sizeGroup, lp());

        addSpacer(root, ctx, 12);

        // ── Primary Camera ────────────────────────────────────────────
        TextView camLabel = new TextView(ctx);
        camLabel.setText("Primary Camera");
        camLabel.setTextSize(14f);
        camLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(camLabel, lp());

        RadioGroup camGroup = new RadioGroup(ctx);
        camGroup.setOrientation(RadioGroup.HORIZONTAL);
        for (DualCameraConfig.PrimaryCamera cam : DualCameraConfig.PrimaryCamera.values()) {
            RadioButton rb = new RadioButton(ctx);
            rb.setText(formatEnum(cam.name()));
            rb.setTag(cam);
            rb.setChecked(cam == current.getPrimaryCamera());
            camGroup.addView(rb);
        }
        camGroup.setOnCheckedChangeListener((group, checkedId) -> {
            RadioButton rb = group.findViewById(checkedId);
            if (rb != null && rb.getTag() instanceof DualCameraConfig.PrimaryCamera) {
                configBuilder.primaryCamera((DualCameraConfig.PrimaryCamera) rb.getTag());
                saveConfig();
            }
        });
        root.addView(camGroup, lp());

        addSpacer(root, ctx, 12);

        // ── Show Border ───────────────────────────────────────────────
        Switch borderSwitch = new Switch(ctx);
        borderSwitch.setText("Show PiP Border");
        borderSwitch.setChecked(current.isShowPipBorder());
        borderSwitch.setOnCheckedChangeListener((btn, checked) -> {
            configBuilder.showPipBorder(checked);
            saveConfig();
        });
        root.addView(borderSwitch, lp());

        addSpacer(root, ctx, 8);

        // ── Round Corners ─────────────────────────────────────────────
        Switch cornersSwitch = new Switch(ctx);
        cornersSwitch.setText("Rounded PiP Corners");
        cornersSwitch.setChecked(current.isRoundPipCorners());
        cornersSwitch.setOnCheckedChangeListener((btn, checked) -> {
            configBuilder.roundPipCorners(checked);
            saveConfig();
        });
        root.addView(cornersSwitch, lp());

        return root;
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private void saveConfig() {
        DualCameraConfig config = configBuilder.build();
        prefs.saveDualCameraConfig(config);
        Log.d(TAG, "Config saved: pos=" + config.getPipPosition()
                + ", size=" + config.getPipSize()
                + ", primary=" + config.getPrimaryCamera());
    }

    @NonNull
    private static String formatEnum(@NonNull String enumName) {
        // "BOTTOM_RIGHT" → "Bottom Right"
        String[] words = enumName.toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            sb.append(Character.toUpperCase(w.charAt(0)))
              .append(w.substring(1))
              .append(' ');
        }
        return sb.toString().trim();
    }

    private static android.widget.LinearLayout.LayoutParams lp() {
        return new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private static void addSpacer(@NonNull android.widget.LinearLayout parent,
                                  @NonNull android.content.Context ctx, int dp) {
        View spacer = new View(ctx);
        android.widget.LinearLayout.LayoutParams p = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(ctx, dp));
        parent.addView(spacer, p);
    }

    private static int dpToPx(@NonNull android.content.Context ctx, int dp) {
        return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
    }
}
