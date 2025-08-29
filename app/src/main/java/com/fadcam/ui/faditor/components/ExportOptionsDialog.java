package com.fadcam.ui.faditor.components;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import com.fadcam.R;
import com.fadcam.ui.faditor.models.VideoMetadata;
import com.fadcam.ui.faditor.processors.VideoExporter;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.text.DecimalFormat;

/**
 * Material 3 export options dialog for video export settings.
 * Provides quality, format, and advanced export options with real-time preview.
 */
public class ExportOptionsDialog extends DialogFragment {
    
    private static final String ARG_VIDEO_METADATA = "video_metadata";
    private static final String ARG_PROJECT_NAME = "project_name";
    
    public interface ExportOptionsListener {
        void onExportConfirmed(VideoExporter.ExportSettings settings);
        void onExportCancelled();
    }
    
    // UI Components
    private Slider qualitySlider;
    private TextView qualityValueText;
    private TextView qualityDescriptionText;
    private AutoCompleteTextView formatDropdown;
    private TextInputEditText fileNameEditText;
    private TextInputLayout fileNameLayout;
    private SwitchMaterial maintainQualitySwitch;
    private SwitchMaterial customBitrateSwitch;
    private Slider bitrateSlider;
    private TextView bitrateValueText;
    private TextView estimatedSizeText;
    private MaterialButton presetLowButton;
    private MaterialButton presetMediumButton;
    private MaterialButton presetHighButton;
    private MaterialButton presetUltraButton;
    private MaterialButton exportButton;
    private MaterialButton cancelButton;
    
    // Data
    private VideoMetadata videoMetadata;
    private String projectName;
    private ExportOptionsListener listener;
    private VideoExporter.ExportSettings currentSettings;
    
    public static ExportOptionsDialog newInstance(VideoMetadata metadata, String projectName) {
        ExportOptionsDialog dialog = new ExportOptionsDialog();
        Bundle args = new Bundle();
        // Store metadata in the dialog instance instead of Bundle since it's not Serializable
        dialog.videoMetadata = metadata;
        args.putString(ARG_PROJECT_NAME, projectName);
        dialog.setArguments(args);
        return dialog;
    }
    
    public void setExportOptionsListener(ExportOptionsListener listener) {
        this.listener = listener;
    }
    
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        if (getArguments() != null) {
            // VideoMetadata will be passed differently since it's not Serializable
            projectName = getArguments().getString(ARG_PROJECT_NAME, "Untitled");
            // For now, create a default metadata - this should be passed differently in production
            videoMetadata = new VideoMetadata();
        }
        
        // Initialize with recommended settings
        VideoExporter exporter = new VideoExporter(requireContext());
        currentSettings = exporter.getRecommendedSettings(videoMetadata);
    }
    
    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext());
        
        // Inflate custom layout
        LayoutInflater inflater = requireActivity().getLayoutInflater();
        View view = inflater.inflate(R.layout.dialog_export_options, null);
        
        initializeViews(view);
        setupListeners();
        updateUI();
        
        builder.setView(view)
               .setTitle(R.string.faditor_export_video)
               .setCancelable(true);
        
        return builder.create();
    }
    
    private void initializeViews(View view) {
        // Quality controls
        qualitySlider = view.findViewById(R.id.quality_slider);
        qualityValueText = view.findViewById(R.id.quality_value_text);
        qualityDescriptionText = view.findViewById(R.id.quality_description_text);
        
        // Format controls
        formatDropdown = view.findViewById(R.id.format_dropdown);
        
        // File name controls
        fileNameEditText = view.findViewById(R.id.file_name_edit_text);
        fileNameLayout = view.findViewById(R.id.file_name_layout);
        
        // Advanced options
        maintainQualitySwitch = view.findViewById(R.id.maintain_quality_switch);
        customBitrateSwitch = view.findViewById(R.id.custom_bitrate_switch);
        bitrateSlider = view.findViewById(R.id.bitrate_slider);
        bitrateValueText = view.findViewById(R.id.bitrate_value_text);
        
        // Preview
        estimatedSizeText = view.findViewById(R.id.estimated_size_text);
        
        // Preset buttons
        presetLowButton = view.findViewById(R.id.preset_low_button);
        presetMediumButton = view.findViewById(R.id.preset_medium_button);
        presetHighButton = view.findViewById(R.id.preset_high_button);
        presetUltraButton = view.findViewById(R.id.preset_ultra_button);
        
        // Action buttons
        exportButton = view.findViewById(R.id.export_button);
        cancelButton = view.findViewById(R.id.cancel_button);
        
        // Setup format dropdown
        String[] formats = VideoExporter.getAvailableFormats();
        ArrayAdapter<String> formatAdapter = new ArrayAdapter<>(requireContext(), 
            android.R.layout.simple_dropdown_item_1line, formats);
        formatDropdown.setAdapter(formatAdapter);
        
        // Set default file name
        if (fileNameEditText != null) {
            fileNameEditText.setText(projectName + "_exported");
        }
    }
    
    private void setupListeners() {
        // Quality slider
        if (qualitySlider != null) {
            qualitySlider.addOnChangeListener((slider, value, fromUser) -> {
                if (fromUser) {
                    updateQualitySettings((int) value);
                    updatePreview();
                }
            });
        }
        
        // Format dropdown
        if (formatDropdown != null) {
            formatDropdown.setOnItemClickListener((parent, view, position, id) -> {
                String selectedFormat = (String) parent.getItemAtPosition(position);
                updateFormatSettings(selectedFormat);
                updatePreview();
            });
        }
        
        // File name
        if (fileNameEditText != null) {
            fileNameEditText.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                
                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    validateFileName(s.toString());
                }
                
                @Override
                public void afterTextChanged(Editable s) {}
            });
        }
        
        // Maintain quality switch
        if (maintainQualitySwitch != null) {
            maintainQualitySwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                updateMaintainQualitySettings(isChecked);
                updatePreview();
            });
        }
        
        // Custom bitrate switch
        if (customBitrateSwitch != null) {
            customBitrateSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                updateCustomBitrateSettings(isChecked);
                updatePreview();
            });
        }
        
        // Bitrate slider
        if (bitrateSlider != null) {
            bitrateSlider.addOnChangeListener((slider, value, fromUser) -> {
                if (fromUser) {
                    updateBitrateSettings((int) value);
                    updatePreview();
                }
            });
        }
        
        // Preset buttons
        if (presetLowButton != null) {
            presetLowButton.setOnClickListener(v -> applyPreset(VideoExporter.ExportSettings.createLowQuality()));
        }
        if (presetMediumButton != null) {
            presetMediumButton.setOnClickListener(v -> applyPreset(VideoExporter.ExportSettings.createMediumQuality()));
        }
        if (presetHighButton != null) {
            presetHighButton.setOnClickListener(v -> applyPreset(VideoExporter.ExportSettings.createHighQuality()));
        }
        if (presetUltraButton != null) {
            presetUltraButton.setOnClickListener(v -> applyPreset(VideoExporter.ExportSettings.createHighQuality()));
        }
        
        // Action buttons
        if (exportButton != null) {
            exportButton.setOnClickListener(v -> confirmExport());
        }
        if (cancelButton != null) {
            cancelButton.setOnClickListener(v -> cancelExport());
        }
    }
    
    private void updateUI() {
        // Update quality slider
        if (qualitySlider != null) {
            qualitySlider.setValue(currentSettings.quality);
            updateQualityDisplay(currentSettings.quality);
        }
        
        // Update format dropdown
        if (formatDropdown != null) {
            formatDropdown.setText(currentSettings.format.toUpperCase(), false);
        }
        
        // Update switches
        if (maintainQualitySwitch != null) {
            maintainQualitySwitch.setChecked(currentSettings.maintainOriginalQuality);
        }
        
        if (customBitrateSwitch != null) {
            boolean hasCustomBitrate = currentSettings.bitrate > 0;
            customBitrateSwitch.setChecked(hasCustomBitrate);
            updateCustomBitrateVisibility(hasCustomBitrate);
        }
        
        // Update bitrate slider
        if (bitrateSlider != null && currentSettings.bitrate > 0) {
            bitrateSlider.setValue(currentSettings.bitrate / 1000000f); // Convert to Mbps
            updateBitrateDisplay(currentSettings.bitrate);
        }
        
        updatePreview();
    }
    
    private void updateQualitySettings(int quality) {
        currentSettings = new VideoExporter.ExportSettings(
            quality,
            currentSettings.format,
            currentSettings.bitrate,
            currentSettings.maintainOriginalQuality,
            currentSettings.width,
            currentSettings.height,
            currentSettings.outputFileName
        );
        
        updateQualityDisplay(quality);
    }
    
    private void updateQualityDisplay(int quality) {
        if (qualityValueText != null) {
            qualityValueText.setText(quality + "%");
        }
        
        if (qualityDescriptionText != null) {
            String description;
            if (quality >= 90) {
                description = getString(R.string.faditor_quality_ultra_description);
            } else if (quality >= 75) {
                description = getString(R.string.faditor_quality_high_description);
            } else if (quality >= 50) {
                description = getString(R.string.faditor_quality_medium_description);
            } else {
                description = getString(R.string.faditor_quality_low_description);
            }
            qualityDescriptionText.setText(description);
        }
    }
    
    private void updateFormatSettings(String format) {
        currentSettings = new VideoExporter.ExportSettings(
            currentSettings.quality,
            format.toLowerCase(),
            currentSettings.bitrate,
            currentSettings.maintainOriginalQuality,
            currentSettings.width,
            currentSettings.height,
            currentSettings.outputFileName
        );
    }
    
    private void updateMaintainQualitySettings(boolean maintainQuality) {
        currentSettings = new VideoExporter.ExportSettings(
            currentSettings.quality,
            currentSettings.format,
            currentSettings.bitrate,
            maintainQuality,
            currentSettings.width,
            currentSettings.height,
            currentSettings.outputFileName
        );
    }
    
    private void updateCustomBitrateSettings(boolean useCustomBitrate) {
        int bitrate = useCustomBitrate ? (int) (bitrateSlider.getValue() * 1000000) : -1;
        
        currentSettings = new VideoExporter.ExportSettings(
            currentSettings.quality,
            currentSettings.format,
            bitrate,
            currentSettings.maintainOriginalQuality,
            currentSettings.width,
            currentSettings.height,
            currentSettings.outputFileName
        );
        
        updateCustomBitrateVisibility(useCustomBitrate);
    }
    
    private void updateCustomBitrateVisibility(boolean visible) {
        if (bitrateSlider != null) {
            bitrateSlider.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
        if (bitrateValueText != null) {
            bitrateValueText.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }
    
    private void updateBitrateSettings(int bitrateKbps) {
        int bitrateBps = bitrateKbps * 1000;
        
        currentSettings = new VideoExporter.ExportSettings(
            currentSettings.quality,
            currentSettings.format,
            bitrateBps,
            currentSettings.maintainOriginalQuality,
            currentSettings.width,
            currentSettings.height,
            currentSettings.outputFileName
        );
        
        updateBitrateDisplay(bitrateBps);
    }
    
    private void updateBitrateDisplay(int bitrateBps) {
        if (bitrateValueText != null) {
            float bitrateMbps = bitrateBps / 1000000f;
            DecimalFormat df = new DecimalFormat("#.#");
            bitrateValueText.setText(df.format(bitrateMbps) + " Mbps");
        }
    }
    
    private void applyPreset(VideoExporter.ExportSettings preset) {
        currentSettings = preset;
        updateUI();
    }
    
    private void updatePreview() {
        if (estimatedSizeText != null && videoMetadata != null) {
            VideoExporter exporter = new VideoExporter(requireContext());
            long estimatedSize = exporter.estimateFileSize(videoMetadata, currentSettings);
            
            String sizeText = formatFileSize(estimatedSize);
            estimatedSizeText.setText(getString(R.string.faditor_estimated_size, sizeText));
        }
    }
    
    private String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        } else {
            return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }
    
    private void validateFileName(String fileName) {
        if (fileNameLayout == null) return;
        
        if (fileName.trim().isEmpty()) {
            fileNameLayout.setError(getString(R.string.faditor_filename_empty_error));
        } else if (fileName.contains("/") || fileName.contains("\\") || fileName.contains(":") || 
                   fileName.contains("*") || fileName.contains("?") || fileName.contains("\"") ||
                   fileName.contains("<") || fileName.contains(">") || fileName.contains("|")) {
            fileNameLayout.setError(getString(R.string.faditor_filename_invalid_chars_error));
        } else {
            fileNameLayout.setError(null);
        }
    }
    
    private void confirmExport() {
        // Validate file name
        String fileName = fileNameEditText != null ? fileNameEditText.getText().toString().trim() : "";
        if (fileName.isEmpty()) {
            if (fileNameLayout != null) {
                fileNameLayout.setError(getString(R.string.faditor_filename_empty_error));
            }
            return;
        }
        
        // Update settings with file name
        currentSettings = new VideoExporter.ExportSettings(
            currentSettings.quality,
            currentSettings.format,
            currentSettings.bitrate,
            currentSettings.maintainOriginalQuality,
            currentSettings.width,
            currentSettings.height,
            fileName
        );
        
        // Notify listener
        if (listener != null) {
            listener.onExportConfirmed(currentSettings);
        }
        
        dismiss();
    }
    
    private void cancelExport() {
        if (listener != null) {
            listener.onExportCancelled();
        }
        dismiss();
    }
}