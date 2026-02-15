package com.fadcam.forensics.ui;

import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.fadcam.R;
import com.fadcam.forensics.data.local.ForensicsDatabase;
import com.fadcam.forensics.data.local.model.ForensicsSnapshotWithMedia;
import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.io.FileOutputStream;
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
    private TextView status;

    public ForensicsExportCenterFragment() {
        super(R.layout.fragment_forensics_export_center);
    }

    @Override
    public void onViewCreated(@NonNull android.view.View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        exportButton = view.findViewById(R.id.button_export_forensics);
        status = view.findViewById(R.id.text_export_status);
        if (exportButton != null) {
            exportButton.setOnClickListener(v -> startExport());
        }
    }

    private void startExport() {
        if (exportButton != null) {
            exportButton.setEnabled(false);
        }
        if (status != null) {
            status.setText(R.string.forensics_export_running);
        }
        executor.execute(() -> {
            try {
                List<ForensicsSnapshotWithMedia> rows = ForensicsDatabase.getInstance(requireContext())
                        .aiEventSnapshotDao()
                        .getGallerySnapshots(null, 0f, null, 0L, 20000);
                File outFile = writeExportZip(rows);
                if (!isAdded()) {
                    return;
                }
                requireActivity().runOnUiThread(() -> {
                    if (exportButton != null) {
                        exportButton.setEnabled(true);
                    }
                    if (status != null) {
                        status.setText(getString(R.string.forensics_export_done, outFile.getAbsolutePath()));
                    }
                    Toast.makeText(requireContext(), getString(R.string.forensics_export_done, outFile.getAbsolutePath()), Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                if (!isAdded()) {
                    return;
                }
                requireActivity().runOnUiThread(() -> {
                    if (exportButton != null) {
                        exportButton.setEnabled(true);
                    }
                    if (status != null) {
                        status.setText(e.getMessage() == null ? "Export failed" : e.getMessage());
                    }
                });
            }
        });
    }

    @NonNull
    private File writeExportZip(@Nullable List<ForensicsSnapshotWithMedia> rows) throws Exception {
        File base = requireContext().getExternalFilesDir(null);
        if (base == null) {
            throw new IllegalStateException("External files directory is unavailable");
        }
        File exportDir = new File(base, "FadCam/Forensics/Exports");
        if (!exportDir.exists() && !exportDir.mkdirs()) {
            throw new IllegalStateException("Unable to create export directory");
        }
        String filename = "forensics_export_" + System.currentTimeMillis() + ".zip";
        File output = new File(exportDir, filename);
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(output))) {
            writeManifest(zos, rows);
            if (rows != null) {
                for (ForensicsSnapshotWithMedia row : rows) {
                    addSnapshotToZip(zos, row);
                }
            }
        }
        return output;
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
        if (row.imageUri == null || row.imageUri.isEmpty()) {
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
}
