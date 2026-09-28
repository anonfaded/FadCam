package com.fadcam.forensics.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import com.fadcam.R;
import com.fadcam.ui.VideoPlayerActivity;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

public class ForensicsEvidenceInfoBottomSheet extends BottomSheetDialogFragment {

    private static final String ARG_CLASS_NAME = "arg_class_name";
    private static final String ARG_EVENT_TYPE = "arg_event_type";
    private static final String ARG_CONFIDENCE = "arg_confidence";
    private static final String ARG_CAPTURED_AT = "arg_captured_at";
    private static final String ARG_TIMELINE_MS = "arg_timeline_ms";
    private static final String ARG_SOURCE_LABEL = "arg_source_label";
    private static final String ARG_SOURCE_URI = "arg_source_uri";
    private static final String ARG_SNAPSHOT_URI = "arg_snapshot_uri";

    public static ForensicsEvidenceInfoBottomSheet newInstance(
            @Nullable String className,
            @Nullable String eventType,
            float confidence,
            long capturedAt,
            long timelineMs,
            @Nullable String sourceLabel,
            @Nullable String sourceVideoUri,
            @Nullable String snapshotUri
    ) {
        ForensicsEvidenceInfoBottomSheet sheet = new ForensicsEvidenceInfoBottomSheet();
        Bundle args = new Bundle();
        args.putString(ARG_CLASS_NAME, className);
        args.putString(ARG_EVENT_TYPE, eventType);
        args.putFloat(ARG_CONFIDENCE, confidence);
        args.putLong(ARG_CAPTURED_AT, capturedAt);
        args.putLong(ARG_TIMELINE_MS, timelineMs);
        args.putString(ARG_SOURCE_LABEL, sourceLabel);
        args.putString(ARG_SOURCE_URI, sourceVideoUri);
        args.putString(ARG_SNAPSHOT_URI, snapshotUri);
        sheet.setArguments(args);
        return sheet;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottomsheet_forensics_evidence_info, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        View root = view.findViewById(R.id.picker_root);
        if (root != null) {
            root.setBackgroundResource(R.drawable.picker_bottom_sheet_gradient_bg_dynamic);
        }
        TextView title = view.findViewById(R.id.picker_title);
        if (title != null) {
            title.setText(R.string.forensics_evidence_details);
        }
        View close = view.findViewById(R.id.picker_close_btn);
        if (close != null) {
            close.setOnClickListener(v -> dismiss());
        }
        setupRows(view);
        setupCopy(view);
        setupOpenSource(view);
    }

    private void setupRows(@NonNull View root) {
        LinearLayout container = root.findViewById(R.id.video_info_grid);
        if (container == null) {
            return;
        }
        container.removeAllViews();
        Bundle args = getArguments();
        String className = args != null ? args.getString(ARG_CLASS_NAME) : null;
        String eventType = args != null ? args.getString(ARG_EVENT_TYPE) : null;
        float confidence = args != null ? args.getFloat(ARG_CONFIDENCE, 0f) : 0f;
        long capturedAt = args != null ? args.getLong(ARG_CAPTURED_AT, 0L) : 0L;
        long timelineMs = args != null ? args.getLong(ARG_TIMELINE_MS, 0L) : 0L;
        String sourceLabel = args != null ? args.getString(ARG_SOURCE_LABEL) : null;
        String sourceUri = args != null ? args.getString(ARG_SOURCE_URI) : null;
        String snapshotUri = args != null ? args.getString(ARG_SNAPSHOT_URI) : null;

        addRow(container, "pets", getString(R.string.forensics_filter_object), firstNonEmpty(className, "-"));
        addRow(container, "category", getString(R.string.forensics_events_title), firstNonEmpty(eventType, "-"));
        addRow(container, "center_focus_strong", getString(R.string.forensics_sort_confidence),
                String.format(Locale.US, "%.2f", confidence));
        addRow(container, "schedule", getString(R.string.video_info_last_modified),
                capturedAt > 0L
                        ? DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault()).format(new Date(capturedAt))
                        : getString(R.string.forensics_unknown_time));
        addRow(container, "timer", getString(R.string.forensics_timeline_label),
                formatTimeline(timelineMs));
        addRow(container, "description", getString(R.string.video_info_file_name),
                firstNonEmpty(sourceLabel, getString(R.string.forensics_unknown_source)));
        addRow(container, "image", getString(R.string.forensics_event_proof_frame),
                firstNonEmpty(snapshotUri, getString(R.string.forensics_not_available)));
    }

    private void addRow(@NonNull LinearLayout container, @NonNull String iconLigature, @NonNull String label, @NonNull String value) {
        View row = LayoutInflater.from(requireContext()).inflate(R.layout.video_info_row_item, container, false);
        TextView icon = row.findViewById(R.id.info_icon);
        TextView rowLabel = row.findViewById(R.id.info_label);
        TextView rowValue = row.findViewById(R.id.info_value);
        if (icon != null) {
            icon.setTypeface(ResourcesCompat.getFont(requireContext(), R.font.materialicons));
            icon.setText(iconLigature);
        }
        if (rowLabel != null) {
            rowLabel.setText(label);
        }
        if (rowValue != null) {
            rowValue.setText(value);
        }
        container.addView(row);
    }

    private void setupCopy(@NonNull View root) {
        View copyRow = root.findViewById(R.id.copy_action_row);
        if (copyRow != null) {
            copyRow.setOnClickListener(v -> {
                ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard == null) {
                    return;
                }
                clipboard.setPrimaryClip(ClipData.newPlainText("forensics_evidence", buildCopyText()));
                Toast.makeText(requireContext(), R.string.forensics_copy_done, Toast.LENGTH_SHORT).show();
            });
        }
    }

    private void setupOpenSource(@NonNull View root) {
        View row = root.findViewById(R.id.open_video_action_row);
        TextView subtitle = root.findViewById(R.id.open_video_subtitle);
        Bundle args = getArguments();
        String sourceUri = args != null ? args.getString(ARG_SOURCE_URI) : null;
        boolean available = !TextUtils.isEmpty(sourceUri);
        if (subtitle != null) {
            subtitle.setText(available ? getString(R.string.forensics_video_linked) : getString(R.string.forensics_not_available));
        }
        if (row != null) {
            row.setEnabled(available);
            row.setAlpha(available ? 1f : 0.55f);
            row.setOnClickListener(available ? v -> {
                try {
                    Intent intent = new Intent(requireContext(), VideoPlayerActivity.class);
                    intent.setData(Uri.parse(sourceUri));
                    intent.putExtra(ForensicsEventsFragment.EXTRA_OPEN_AT_MS,
                            Math.max(0L, args != null ? args.getLong(ARG_TIMELINE_MS, 0L) : 0L));
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(intent);
                    dismiss();
                } catch (Exception e) {
                    Toast.makeText(requireContext(), R.string.forensics_not_available, Toast.LENGTH_SHORT).show();
                }
            } : null);
        }
    }

    @NonNull
    private String buildCopyText() {
        Bundle args = getArguments();
        if (args == null) {
            return "";
        }
        return "Class: " + firstNonEmpty(args.getString(ARG_CLASS_NAME), "-")
                + "\nType: " + firstNonEmpty(args.getString(ARG_EVENT_TYPE), "-")
                + "\nConfidence: " + String.format(Locale.US, "%.2f", args.getFloat(ARG_CONFIDENCE, 0f))
                + "\nCaptured: " + (args.getLong(ARG_CAPTURED_AT, 0L) > 0L
                ? DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
                .format(new Date(args.getLong(ARG_CAPTURED_AT, 0L)))
                : getString(R.string.forensics_unknown_time))
                + "\nTimeline: " + formatTimeline(args.getLong(ARG_TIMELINE_MS, 0L))
                + "\nSource: " + firstNonEmpty(args.getString(ARG_SOURCE_LABEL), getString(R.string.forensics_unknown_source))
                + "\nSource URI: " + firstNonEmpty(args.getString(ARG_SOURCE_URI), getString(R.string.forensics_not_available))
                + "\nSnapshot URI: " + firstNonEmpty(args.getString(ARG_SNAPSHOT_URI), getString(R.string.forensics_not_available));
    }

    @NonNull
    private String formatTimeline(long ms) {
        long sec = Math.max(0L, ms / 1000L);
        return String.format(Locale.US, "%d:%02d", sec / 60L, sec % 60L);
    }

    @NonNull
    private String firstNonEmpty(@Nullable String a, @NonNull String fallback) {
        return TextUtils.isEmpty(a) ? fallback : a;
    }

    @Override
    public android.app.Dialog onCreateDialog(Bundle savedInstanceState) {
        android.app.Dialog dialog = super.onCreateDialog(savedInstanceState);
        dialog.setOnShowListener(d -> {
            View bottomSheet = ((BottomSheetDialog) dialog).findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                bottomSheet.setBackground(new ColorDrawable(Color.TRANSPARENT));
            }
        });
        return dialog;
    }
}
