package com.servalabs.cam.ui.bottomsheet;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.servalabs.cam.R;
import com.servalabs.cam.streaming.RemoteStreamManager;
import com.servalabs.cam.streaming.model.StreamQuality;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * Bottom sheet for selecting stream quality preset.
 * Allows user to choose between LOW, MEDIUM, HIGH, and ULTRA quality.
 */
public class QualityPresetBottomSheet extends BottomSheetDialogFragment {
    
    private LinearLayout rowLow, rowMedium, rowHigh, rowUltra;
    private ImageView checkLow, checkMedium, checkHigh, checkUltra;
    
    private StreamQuality.Preset currentPreset;
    private StreamQuality.Preset selectedPreset;
    private OnQualitySelectedListener listener;
    
    /**
     * Factory method to create new instance.
     */
    public static QualityPresetBottomSheet newInstance(StreamQuality.Preset currentPreset) {
        QualityPresetBottomSheet fragment = new QualityPresetBottomSheet();
        Bundle args = new Bundle();
        args.putString("current_preset", currentPreset.name());
        fragment.setArguments(args);
        return fragment;
    }
    
    /**
     * Callback interface for quality selection.
     */
    public interface OnQualitySelectedListener {
        void onQualitySelected(StreamQuality.Preset preset);
    }
    
    /**
     * Set callback listener.
     */
    public void setOnQualitySelectedListener(OnQualitySelectedListener listener) {
        this.listener = listener;
    }
    
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_quality_preset, container, false);
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // Get current preset from arguments
        Bundle args = getArguments();
        if (args != null) {
            String presetName = args.getString("current_preset", "HIGH");
            currentPreset = StreamQuality.Preset.valueOf(presetName);
        } else {
            currentPreset = StreamQuality.Preset.HIGH;
        }
        
        selectedPreset = currentPreset;
        
        // Initialize views
        rowLow = view.findViewById(R.id.rowLow);
        rowMedium = view.findViewById(R.id.rowMedium);
        rowHigh = view.findViewById(R.id.rowHigh);
        rowUltra = view.findViewById(R.id.rowUltra);
        checkLow = view.findViewById(R.id.checkLow);
        checkMedium = view.findViewById(R.id.checkMedium);
        checkHigh = view.findViewById(R.id.checkHigh);
        checkUltra = view.findViewById(R.id.checkUltra);
        
        // Show current selection
        updateSelection();
        
        // Set up row click listeners
        rowLow.setOnClickListener(v -> selectPreset(StreamQuality.Preset.LOW));
        rowMedium.setOnClickListener(v -> selectPreset(StreamQuality.Preset.MEDIUM));
        rowHigh.setOnClickListener(v -> selectPreset(StreamQuality.Preset.HIGH));
        rowUltra.setOnClickListener(v -> selectPreset(StreamQuality.Preset.ULTRA));
    }
    
    private void selectPreset(StreamQuality.Preset preset) {
        selectedPreset = preset;
        updateSelection();
        
        // Check if preset changed
        if (selectedPreset == currentPreset) {
            Toast.makeText(getContext(), "Quality already set to " + selectedPreset.getDisplayName(), Toast.LENGTH_SHORT).show();
            dismiss();
            return;
        }
        
        // Apply new preset
        if (listener != null) {
            listener.onQualitySelected(selectedPreset);
        }
        
        // Update in RemoteStreamManager
        RemoteStreamManager manager = RemoteStreamManager.getInstance();
        manager.setStreamQuality(selectedPreset, requireContext());
        
        // Only show "restart recording" if recording is active
        if (manager.isRecording()) {
            Toast.makeText(getContext(), 
                "Quality updated to " + selectedPreset.getDisplayName() + ". Restart recording to apply.", 
                Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(getContext(), 
                "Quality updated to " + selectedPreset.getDisplayName() + ".", 
                Toast.LENGTH_SHORT).show();
        }
        
        dismiss();
    }
    
    private void updateSelection() {
        // Hide all checkmarks
        checkLow.setVisibility(View.GONE);
        checkMedium.setVisibility(View.GONE);
        checkHigh.setVisibility(View.GONE);
        checkUltra.setVisibility(View.GONE);
        
        // Show selected checkmark
        switch (selectedPreset) {
            case LOW:
                checkLow.setVisibility(View.VISIBLE);
                break;
            case MEDIUM:
                checkMedium.setVisibility(View.VISIBLE);
                break;
            case HIGH:
                checkHigh.setVisibility(View.VISIBLE);
                break;
            case ULTRA:
                checkUltra.setVisibility(View.VISIBLE);
                break;
        }
    }

    @Override
    public int getTheme() {
        return R.style.CustomBottomSheetDialogTheme;
    }

    @Override
    public android.app.Dialog onCreateDialog(Bundle savedInstanceState) {
        android.app.Dialog dialog = super.onCreateDialog(savedInstanceState);
        dialog.setOnShowListener(d -> {
            View bottomSheet = ((com.google.android.material.bottomsheet.BottomSheetDialog) dialog)
                .findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                bottomSheet.setBackgroundResource(R.drawable.picker_bottom_sheet_dark_gradient_bg);
            }
        });
        if (dialog.getWindow() != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            dialog.getWindow().setNavigationBarColor(android.graphics.Color.BLACK);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                dialog.getWindow().setNavigationBarContrastEnforced(false);
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                int flags = dialog.getWindow().getDecorView().getSystemUiVisibility();
                dialog.getWindow().getDecorView().setSystemUiVisibility(
                    flags & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                );
            }
        }
        return dialog;
    }
    
}
