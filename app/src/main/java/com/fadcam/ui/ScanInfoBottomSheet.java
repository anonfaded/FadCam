package com.fadcam.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import com.fadcam.FLog;
import com.fadcam.R;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.io.File;
import java.io.FileInputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Scanner;

public class ScanInfoBottomSheet extends BottomSheetDialogFragment {
    private static final String TAG = "ScanInfoBottomSheet";
    private static final String ARG_URI = "file_uri";
    private static final String ARG_NAME = "file_name";

    private Uri fileUri;
    private String displayName;
    private Typeface materialIconsTypeface;
    private String rawValue = "";
    private String format = "";
    private String timestamp = "";

    @Override
    public android.app.Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        android.app.Dialog dialog = super.onCreateDialog(savedInstanceState);
        dialog.setOnShowListener(d -> {
            View bottomSheet = ((com.google.android.material.bottomsheet.BottomSheetDialog) dialog)
                    .findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                bottomSheet.setBackground(new ColorDrawable(Color.TRANSPARENT));
            }
        });
        return dialog;
    }

    public static ScanInfoBottomSheet newInstance(String uriString, String displayName) {
        ScanInfoBottomSheet f = new ScanInfoBottomSheet();
        Bundle args = new Bundle();
        args.putString(ARG_URI, uriString);
        args.putString(ARG_NAME, displayName);
        f.setArguments(args);
        return f;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            String uriStr = getArguments().getString(ARG_URI);
            if (uriStr != null) fileUri = Uri.parse(uriStr);
            displayName = getArguments().getString(ARG_NAME);
        }
        if (getContext() != null) {
            materialIconsTypeface = ResourcesCompat.getFont(getContext(), R.font.materialicons);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottomsheet_video_info, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        if (fileUri == null) { dismiss(); return; }

        // Gradient background
        View root = view.findViewById(R.id.picker_root);
        if (root != null) root.setBackgroundResource(R.drawable.picker_bottom_sheet_gradient_bg_dynamic);

        // Title
        TextView title = view.findViewById(R.id.picker_title);
        if (title != null) title.setText(getString(R.string.scan_info_title));

        // Close
        View close = view.findViewById(R.id.picker_close_btn);
        if (close != null) close.setOnClickListener(v -> dismiss());

        // Read metadata
        readMetadata();

        // Populate info grid
        LinearLayout grid = view.findViewById(R.id.video_info_grid);
        if (grid != null) {
            grid.removeAllViews();
            addRow(grid, "description", "Format", format);
            addRowWithCopy(grid, "qr_code", "Scanned Value", rawValue);
            addRow(grid, "schedule", "Saved", getFormattedDate());
            addRow(grid, "folder", "File", displayName);
            addRow(grid, "insert_drive_file", "Path", getReadablePath());
        }

        // Copy button
        LinearLayout copyAction = view.findViewById(R.id.copy_action_row);
        if (copyAction != null) {
            copyAction.setOnClickListener(v -> {
                String text = "Format: " + format + "\nValue: " + rawValue + "\nSaved: " + getFormattedDate() + "\nFile: " + displayName;
                ClipboardManager cm = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("Scan Info", text));
                Toast.makeText(getContext(), getString(R.string.scan_copied_clipboard), Toast.LENGTH_SHORT).show();
            });
        }
        // Update copy subtitle
        TextView copySub = view.findViewById(R.id.copy_subtitle);
        if (copySub != null) copySub.setText(getString(R.string.scan_copy_metadata));
    }

    private void readMetadata() {
        try {
            File jpg = new File(fileUri.getPath());
            File dir = jpg.getParentFile();
            File meta = new File(dir, "metadata.json");
            if (meta.exists()) {
                FileInputStream fis = new FileInputStream(meta);
                Scanner s = new Scanner(fis).useDelimiter("\\A");
                String json = s.hasNext() ? s.next() : "";
                s.close();
                fis.close();
                if (!json.isEmpty()) {
                    org.json.JSONObject obj = new org.json.JSONObject(json);
                    format = obj.optString("format", "Unknown");
                    rawValue = obj.optString("rawValue", "");
                    timestamp = obj.optString("timestamp", "");
                }
            }
        } catch (Exception e) { FLog.w(TAG, "Metadata read failed", e); }
    }

    private String getFormattedDate() {
        if (timestamp.isEmpty()) return "";
        try {
            Date d = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(timestamp);
            String abs = new SimpleDateFormat("MMM dd, yyyy  hh:mm:ss a", Locale.US).format(d);
            String rel = getRelativeTime(d);
            return rel.isEmpty() ? abs : rel + "  (" + abs + ")";
        } catch (Exception e) { return timestamp; }
    }

    private String getRelativeTime(Date date) {
        long diff = System.currentTimeMillis() - date.getTime();
        long sec = diff / 1000;
        if (sec < 0) return "";
        if (sec < 60) return "just now";
        long min = sec / 60;
        if (min < 60) return min + " min ago";
        long hr = min / 60;
        if (hr < 24) return hr + " hr ago";
        long day = hr / 24;
        if (day < 7) return day + " day" + (day > 1 ? "s" : "") + " ago";
        long week = day / 7;
        if (week < 5) return week + " week" + (week > 1 ? "s" : "") + " ago";
        long month = day / 30;
        if (month < 12) return month + " month" + (month > 1 ? "s" : "") + " ago";
        return day / 365 + " year" + (day > 365 ? "s" : "") + " ago";
    }

    private String getReadablePath() {
        String p = fileUri.getPath();
        if (p != null && p.contains("FadCam")) {
            int idx = p.indexOf("FadCam");
            return "..." + p.substring(idx);
        }
        return p != null ? p : "";
    }

    private void addRow(LinearLayout container, String iconLigature, String label, String value) {
        // Explicit divider row between info items — matches VideoInfoBottomSheet
        if (container.getChildCount() > 0) {
            View div = new View(getContext());
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
            dlp.setMargins(dp(14), 0, dp(12), 0);
            div.setLayoutParams(dlp);
            div.setBackgroundColor(0xff404040);
            container.addView(div);
        }

        View row = LayoutInflater.from(getContext()).inflate(R.layout.video_info_row_item, container, false);
        TextView iconView = row.findViewById(R.id.info_icon);
        TextView labelView = row.findViewById(R.id.info_label);
        TextView valueView = row.findViewById(R.id.info_value);

        if (iconView != null && materialIconsTypeface != null) {
            iconView.setTypeface(materialIconsTypeface);
            iconView.setText(iconLigature);
        }
        if (labelView != null) labelView.setText(label);
        if (valueView != null) valueView.setText(value != null ? value : "");

        container.addView(row);
    }

    private void addRowWithCopy(LinearLayout container, String iconLigature, String label, String value) {
        // Add row with copy icon inline — copy icon replaces value's weight so alignment stays
        if (container.getChildCount() > 0) {
            View div = new View(getContext());
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
            dlp.setMargins(dp(14), 0, dp(12), 0);
            div.setLayoutParams(dlp);
            div.setBackgroundColor(0xff404040);
            container.addView(div);
        }

        View row = LayoutInflater.from(getContext()).inflate(R.layout.video_info_row_item, container, false);
        LinearLayout rowLayout = (LinearLayout) row;
        TextView iconView = row.findViewById(R.id.info_icon);
        TextView labelView = row.findViewById(R.id.info_label);
        TextView valueView = row.findViewById(R.id.info_value);

        if (iconView != null && materialIconsTypeface != null) {
            iconView.setTypeface(materialIconsTypeface);
            iconView.setText(iconLigature);
        }
        if (labelView != null) labelView.setText(label);

        // Remove old value view and replace with a horizontal container holding value + copy icon
        if (valueView != null) {
            int idx = rowLayout.indexOfChild(valueView);
            rowLayout.removeView(valueView);

            // Get original layout params and ensure wrap_content height
            ViewGroup.LayoutParams origParams = valueView.getLayoutParams();
            LinearLayout.LayoutParams vrlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (origParams instanceof LinearLayout.LayoutParams) {
                vrlp.weight = ((LinearLayout.LayoutParams) origParams).weight;
            }
            vrlp.gravity = android.view.Gravity.CENTER_VERTICAL;

            LinearLayout valueRow = new LinearLayout(getContext());
            valueRow.setOrientation(LinearLayout.HORIZONTAL);
            valueRow.setLayoutParams(vrlp);
            valueRow.setBaselineAligned(false);

            TextView valText = new TextView(getContext());
            valText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
            valText.setTextColor(getResources().getColor(R.color.gray_text_light, null));
            valText.setText(value != null ? value : "");
            valText.setMaxLines(Integer.MAX_VALUE);
            LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            vp.gravity = android.view.Gravity.CENTER_VERTICAL;
            valText.setLayoutParams(vp);
            valueRow.addView(valText);

            TextView copyIcon = new TextView(getContext());
            copyIcon.setLayoutParams(new LinearLayout.LayoutParams(dp(22), dp(22)));
            copyIcon.setTypeface(materialIconsTypeface);
            copyIcon.setText("content_copy");
            copyIcon.setTextSize(16);
            copyIcon.setTextColor(getResources().getColor(R.color.gray_text_light, null));
            copyIcon.setGravity(android.view.Gravity.CENTER);
            valueRow.addView(copyIcon);

            valueRow.setOnClickListener(v -> {
                ClipboardManager cm = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("Scanned Value", value));
                Toast.makeText(getContext(), getString(R.string.scan_value_copied), Toast.LENGTH_SHORT).show();
            });

            rowLayout.addView(valueRow, idx);
        }

        container.addView(row);
    }

    private int dp(float dpVal) {
        return (int) (dpVal * requireContext().getResources().getDisplayMetrics().density);
    }
}
