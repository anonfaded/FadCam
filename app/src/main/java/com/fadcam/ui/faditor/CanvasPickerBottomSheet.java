package com.fadcam.ui.faditor;

import android.app.Dialog;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import com.fadcam.R;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * Material bottom sheet for choosing canvas (output aspect ratio).
 * Shows visual previews of each aspect ratio preset.
 */
public class CanvasPickerBottomSheet extends BottomSheetDialogFragment {

    /** Callback when user picks a canvas preset. */
    public interface Callback {
        void onCanvasSelected(@NonNull String preset);
    }

    /**
     * Canvas preset definitions.
     * Format: {key, label, ratioW, ratioH}
     * ratioW=0, ratioH=0 means "original" (no canvas change).
     */
    private static final String[][] CANVAS_PRESETS = {
        {"original", "Original",           "0",  "0"},
        {"16_9",     "16:9  Landscape",    "16", "9"},
        {"9_16",     "9:16  Portrait",     "9",  "16"},
        {"1_1",      "1:1  Square",        "1",  "1"},
        {"4_3",      "4:3  Classic",       "4",  "3"},
        {"3_4",      "3:4  Portrait",      "3",  "4"},
        {"4_5",      "4:5  Social",        "4",  "5"},
        {"21_9",     "21:9  Cinematic",    "21", "9"},
    };

    @Nullable private Callback callback;
    @NonNull private String currentPreset = "original";

    /**
     * Create a new CanvasPickerBottomSheet.
     *
     * @param currentPreset the currently selected canvas preset
     * @return a new instance
     */
    public static CanvasPickerBottomSheet newInstance(@NonNull String currentPreset) {
        CanvasPickerBottomSheet sheet = new CanvasPickerBottomSheet();
        Bundle args = new Bundle();
        args.putString("currentPreset", currentPreset);
        sheet.setArguments(args);
        return sheet;
    }

    /**
     * Set the callback for canvas selection events.
     *
     * @param callback the callback to notify
     */
    public void setCallback(@Nullable Callback callback) {
        this.callback = callback;
    }

    @Override
    public int getTheme() {
        return R.style.CustomBottomSheetDialogTheme;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        dialog.setOnShowListener(d -> {
            View bottomSheet = ((BottomSheetDialog) dialog)
                    .findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                bottomSheet.setBackgroundResource(R.drawable.picker_bottom_sheet_dark_gradient_bg);
            }
        });
        return dialog;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        if (getArguments() != null) {
            currentPreset = getArguments().getString("currentPreset", "original");
        }

        float dp = getResources().getDisplayMetrics().density;
        Typeface materialIcons = ResourcesCompat.getFont(requireContext(), R.font.materialicons);

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, (int) (12 * dp), 0, (int) (24 * dp));

        // Title
        TextView title = new TextView(requireContext());
        title.setText(R.string.faditor_canvas_title);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding((int) (20 * dp), (int) (12 * dp),
                (int) (20 * dp), (int) (16 * dp));
        root.addView(title);

        // Presets
        for (String[] preset : CANVAS_PRESETS) {
            String key = preset[0];
            String label = preset[1];
            int ratioW = Integer.parseInt(preset[2]);
            int ratioH = Integer.parseInt(preset[3]);
            boolean selected = key.equals(currentPreset);

            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.settings_home_row_bg);
            int hPad = (int) (20 * dp);
            int vPad = (int) (14 * dp);
            row.setPadding(hPad, vPad, hPad, vPad);

            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLp.setMargins((int) (12 * dp), (int) (2 * dp), (int) (12 * dp), (int) (2 * dp));
            row.setLayoutParams(rowLp);

            // Aspect ratio visual preview
            FrameLayout previewContainer = new FrameLayout(requireContext());
            int containerSize = (int) (40 * dp);
            LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(
                    containerSize, containerSize);
            previewLp.setMarginEnd((int) (16 * dp));
            previewContainer.setLayoutParams(previewLp);

            // Add the visual preview
            View previewView = createAspectPreview(ratioW, ratioH, containerSize, selected, dp);
            previewContainer.addView(previewView);
            row.addView(previewContainer);

            // Label
            TextView labelView = new TextView(requireContext());
            labelView.setText(label);
            labelView.setTextSize(15);
            labelView.setTextColor(selected ? 0xFF4CAF50 : 0xFFCCCCCC);
            labelView.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            labelView.setLayoutParams(labelLp);
            row.addView(labelView);

            // Check mark for selected
            if (selected) {
                TextView check = new TextView(requireContext());
                check.setTypeface(materialIcons);
                check.setText("check");
                check.setTextSize(20);
                check.setTextColor(0xFF4CAF50);
                check.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams checkLp = new LinearLayout.LayoutParams(
                        (int) (24 * dp), (int) (24 * dp));
                check.setLayoutParams(checkLp);
                row.addView(check);
            }

            row.setOnClickListener(v -> {
                if (callback != null) {
                    callback.onCanvasSelected(key);
                }
                dismiss();
            });

            root.addView(row);
        }

        return root;
    }

    /**
     * Creates a View that visually shows the aspect ratio as a rectangle.
     * For "original", shows "crop_free" style icon.
     */
    @NonNull
    private View createAspectPreview(int ratioW, int ratioH, int containerSize,
                                     boolean selected, float dp) {
        return new View(requireContext()) {
            private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

            {
                borderPaint.setStyle(Paint.Style.STROKE);
                borderPaint.setStrokeWidth(1.5f * dp);
                borderPaint.setColor(selected ? 0xFF4CAF50 : 0xFF666666);

                fillPaint.setStyle(Paint.Style.FILL);
                fillPaint.setColor(selected ? 0x334CAF50 : 0x22FFFFFF);
            }

            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                float w = getWidth();
                float h = getHeight();
                float margin = 4 * dp;

                float rectW, rectH;
                if (ratioW == 0 || ratioH == 0) {
                    // Original: draw a dashed/outlined frame
                    rectW = w - margin * 2;
                    rectH = h - margin * 2;
                    borderPaint.setStyle(Paint.Style.STROKE);
                    float cx = w / 2f;
                    float cy = h / 2f;
                    RectF r = new RectF(cx - rectW / 2, cy - rectH / 2,
                            cx + rectW / 2, cy + rectH / 2);
                    float corner = 3 * dp;
                    canvas.drawRoundRect(r, corner, corner, fillPaint);
                    canvas.drawRoundRect(r, corner, corner, borderPaint);
                    return;
                }

                // Calculate proportional rectangle
                float maxW = w - margin * 2;
                float maxH = h - margin * 2;
                float aspect = (float) ratioW / ratioH;

                if (aspect >= 1f) {
                    // Wider: fit to width
                    rectW = maxW;
                    rectH = rectW / aspect;
                    if (rectH > maxH) {
                        rectH = maxH;
                        rectW = rectH * aspect;
                    }
                } else {
                    // Taller: fit to height
                    rectH = maxH;
                    rectW = rectH * aspect;
                    if (rectW > maxW) {
                        rectW = maxW;
                        rectH = rectW / aspect;
                    }
                }

                float cx = w / 2f;
                float cy = h / 2f;
                RectF r = new RectF(cx - rectW / 2, cy - rectH / 2,
                        cx + rectW / 2, cy + rectH / 2);

                float corner = 2 * dp;
                canvas.drawRoundRect(r, corner, corner, fillPaint);
                canvas.drawRoundRect(r, corner, corner, borderPaint);
            }
        };
    }

    /**
     * Resolves a canvas preset key to output dimensions based on source video size.
     * The output resolution maintains the source's largest dimension and adjusts the other.
     *
     * @param preset    canvas preset key (e.g., "16_9", "9_16", "1_1")
     * @param srcWidth  source video width
     * @param srcHeight source video height
     * @return int[2] = {outputWidth, outputHeight}, even-aligned for encoding
     */
    public static int[] resolveCanvasDimensions(@NonNull String preset,
                                                 int srcWidth, int srcHeight) {
        int ratioW, ratioH;
        switch (preset) {
            case "16_9":  ratioW = 16; ratioH = 9;  break;
            case "9_16":  ratioW = 9;  ratioH = 16; break;
            case "1_1":   ratioW = 1;  ratioH = 1;  break;
            case "4_3":   ratioW = 4;  ratioH = 3;  break;
            case "3_4":   ratioW = 3;  ratioH = 4;  break;
            case "4_5":   ratioW = 4;  ratioH = 5;  break;
            case "21_9":  ratioW = 21; ratioH = 9;  break;
            default: return new int[]{srcWidth, srcHeight}; // Original
        }

        // Use the larger source dimension as the base
        int maxDim = Math.max(srcWidth, srcHeight);
        int outW, outH;
        if (ratioW >= ratioH) {
            outW = maxDim;
            outH = Math.round((float) maxDim * ratioH / ratioW);
        } else {
            outH = maxDim;
            outW = Math.round((float) maxDim * ratioW / ratioH);
        }

        // Ensure even dimensions (required by video encoders)
        outW = (outW / 2) * 2;
        outH = (outH / 2) * 2;

        return new int[]{outW, outH};
    }
}
