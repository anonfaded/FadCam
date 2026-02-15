package com.fadcam.forensics.ui;

import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.Fragment;

import com.fadcam.R;
import com.fadcam.forensics.data.local.ForensicsDatabase;
import com.fadcam.forensics.data.local.model.ForensicsSnapshotWithMedia;
import com.fadcam.ui.OverlayNavUtil;
import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.io.OutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ForensicsExportCenterFragment extends Fragment {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private MaterialButton exportButton;
    private MaterialButton pathButton;
    private TextView status;
    private TextView pathText;
    private TextView statsText;
    @Nullable
    private Uri targetExportUri;
    @Nullable
    private List<ForensicsSnapshotWithMedia> cachedRows;

    private final ActivityResultLauncher<String> createDocumentLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/zip"), uri -> {
                if (!isAdded()) {
                    return;
                }
                targetExportUri = uri;
                if (pathText != null) {
                    pathText.setText(uri == null
                            ? getString(R.string.forensics_export_no_path)
                            : getString(R.string.forensics_export_selected_path, uri.toString()));
                }
                if (exportButton != null) {
                    exportButton.setEnabled(uri != null);
                }
            });

    public ForensicsExportCenterFragment() {
        super(R.layout.fragment_forensics_export_center);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        View back = view.findViewById(R.id.back_button);
        if (back != null) {
            back.setOnClickListener(v -> OverlayNavUtil.popLevel(requireActivity()));
        }

        pathButton = view.findViewById(R.id.button_pick_export_path);
        exportButton = view.findViewById(R.id.button_export_forensics);
        status = view.findViewById(R.id.text_export_status);
        pathText = view.findViewById(R.id.text_export_path);
        statsText = view.findViewById(R.id.text_export_stats);
        if (pathButton != null) {
            pathButton.setOnClickListener(v -> {
                String name = "forensics_export_" + System.currentTimeMillis() + ".zip";
                createDocumentLauncher.launch(name);
            });
        }
        if (exportButton != null) {
            exportButton.setOnClickListener(v -> startExport());
            exportButton.setEnabled(false);
        }
        refreshStats();
    }

    private void refreshStats() {
        executor.execute(() -> {
            List<ForensicsSnapshotWithMedia> rows = ForensicsDatabase.getInstance(requireContext())
                    .aiEventSnapshotDao()
                    .getGallerySnapshots(null, 0f, null, 0L, 20000);
            cachedRows = rows;
            long total = 0L;
            if (rows != null) {
                for (ForensicsSnapshotWithMedia row : rows) {
                    total += resolveSize(row.imageUri);
                }
            }
            final int count = rows == null ? 0 : rows.size();
            final long bytes = total;
            if (!isAdded()) {
                return;
            }
            requireActivity().runOnUiThread(() -> {
                if (statsText != null) {
                    statsText.setText(getString(
                            R.string.forensics_export_stats,
                            count,
                            Formatter.formatFileSize(requireContext(), bytes)));
                }
            });
        });
    }

    private void startExport() {
        if (targetExportUri == null) {
            Toast.makeText(requireContext(), R.string.forensics_export_pick_path_first, Toast.LENGTH_SHORT).show();
            return;
        }
        if (exportButton != null) {
            exportButton.setEnabled(false);
        }
        if (pathButton != null) {
            pathButton.setEnabled(false);
        }
        if (status != null) {
            status.setText(R.string.forensics_export_running);
        }
        executor.execute(() -> {
            try {
                List<ForensicsSnapshotWithMedia> rows = cachedRows;
                if (rows == null) {
                    rows = ForensicsDatabase.getInstance(requireContext())
                            .aiEventSnapshotDao()
                            .getGallerySnapshots(null, 0f, null, 0L, 20000);
                }
                writeExportZip(targetExportUri, rows);
                if (!isAdded()) {
                    return;
                }
                requireActivity().runOnUiThread(() -> {
                    if (exportButton != null) {
                        exportButton.setEnabled(true);
                    }
                    if (pathButton != null) {
                        pathButton.setEnabled(true);
                    }
                    if (status != null) {
                        status.setText(getString(R.string.forensics_export_done, targetExportUri.toString()));
                    }
                    Toast.makeText(requireContext(), getString(R.string.forensics_export_done, targetExportUri.toString()), Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                if (!isAdded()) {
                    return;
                }
                requireActivity().runOnUiThread(() -> {
                    if (exportButton != null) {
                        exportButton.setEnabled(true);
                    }
                    if (pathButton != null) {
                        pathButton.setEnabled(true);
                    }
                    if (status != null) {
                        status.setText(e.getMessage() == null ? "Export failed" : e.getMessage());
                    }
                });
            }
        });
    }

    private void writeExportZip(@NonNull Uri exportUri, @Nullable List<ForensicsSnapshotWithMedia> rows) throws Exception {
        try (OutputStream out = requireContext().getContentResolver().openOutputStream(exportUri, "w")) {
            if (out == null) {
                throw new IllegalStateException("Unable to open selected export path");
            }
            try (ZipOutputStream zos = new ZipOutputStream(out)) {
            writeManifest(zos, rows);
            if (rows != null) {
                for (ForensicsSnapshotWithMedia row : rows) {
                    addSnapshotToZip(zos, row);
                }
            }
            }
        }
    }

    private void writeManifest(@NonNull ZipOutputStream zos, @Nullable List<ForensicsSnapshotWithMedia> rows) throws Exception {
        StringBuilder builder = new StringBuilder();
        builder.append("{\"generated_at\":").append(System.currentTimeMillis()).append(",\"snapshots\":[");
        if (rows != null) {
            for (int i = 0; i < rows.size(); i++) {
                ForensicsSnapshotWithMedia row = rows.get(i);
                if (i > 0) {
                    builder.append(',');
                }
                builder.append('{')
                        .append("\"snapshot_uid\":\"").append(escapeJson(row.snapshotUid)).append("\",")
                        .append("\"event_uid\":\"").append(escapeJson(row.eventUid)).append("\",")
                        .append("\"media_uid\":\"").append(escapeJson(row.mediaUid)).append("\",")
                        .append("\"event_type\":\"").append(escapeJson(row.eventType)).append("\",")
                        .append("\"class_name\":\"").append(escapeJson(row.className)).append("\",")
                        .append("\"confidence\":").append(String.format(Locale.US, "%.4f", row.confidence)).append(",")
                        .append("\"captured_epoch_ms\":").append(row.capturedEpochMs).append(',')
                        .append("\"timeline_ms\":").append(row.timelineMs).append(',')
                        .append("\"media_uri\":\"").append(escapeJson(row.mediaUri)).append("\",")
                        .append("\"media_missing\":").append(row.mediaMissing).append(',')
                        .append("\"sha256\":\"").append(escapeJson(row.sha256)).append("\"")
                        .append('}');
            }
        }
        builder.append("]}");
        zos.putNextEntry(new ZipEntry("manifest.json"));
        zos.write(builder.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private void addSnapshotToZip(@NonNull ZipOutputStream zos, @NonNull ForensicsSnapshotWithMedia row) throws Exception {
        if (TextUtils.isEmpty(row.imageUri)) {
            return;
        }
        String ext = ".jpg";
        int dot = row.imageUri.lastIndexOf('.');
        if (dot > 0 && dot < row.imageUri.length() - 1) {
            String candidate = row.imageUri.substring(dot).toLowerCase(Locale.US);
            if (candidate.length() <= 5) {
                ext = candidate;
            }
        }
        String name = "snapshots/" + sanitize(row.snapshotUid) + ext;
        zos.putNextEntry(new ZipEntry(name));
        try (InputStream in = requireContext().getContentResolver().openInputStream(Uri.parse(row.imageUri))) {
            if (in != null) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    zos.write(buffer, 0, read);
                }
            }
        } finally {
            zos.closeEntry();
        }
    }

    @NonNull
    private String sanitize(@Nullable String value) {
        if (value == null || value.isEmpty()) {
            return "snapshot";
        }
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    @NonNull
    private String escapeJson(@Nullable String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private long resolveSize(@Nullable String uriString) {
        if (TextUtils.isEmpty(uriString)) {
            return 0L;
        }
        try {
            Uri uri = Uri.parse(uriString);
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                File file = new File(uri.getPath());
                return file.exists() ? file.length() : 0L;
            }
            try (android.content.res.AssetFileDescriptor afd = requireContext().getContentResolver().openAssetFileDescriptor(uri, "r")) {
                if (afd != null && afd.getLength() > 0L) {
                    return afd.getLength();
                }
            }
        } catch (Throwable ignored) {
        }
        return 0L;
    }
}
