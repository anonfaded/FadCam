package com.fadcam.ui.bottomsheet;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;
import com.fadcam.streaming.RemoteStreamManager;
import com.fadcam.streaming.model.StreamQuality;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * Bottom sheet for selecting stream orientation.
 * Note: This ONLY affects streaming orientation, not normal recording.
 */
public class StreamOrientationBottomSheet extends BottomSheetDialogFragment {
    
    private LinearLayout rowPortrait, rowLandscape, rowAuto;
    private ImageView checkPortrait, checkLandscape, checkAuto;
    
    private StreamQuality.StreamOrientation currentOrientation;
    private StreamQuality.StreamOrientation selectedOrientation;
    private OnOrientationSelectedListener listener;
    
    /**
     * Factory method to create new instance.
     */
    public static StreamOrientationBottomSheet newInstance(StreamQuality.StreamOrientation currentOrientation) {
        StreamOrientationBottomSheet fragment = new StreamOrientationBottomSheet();
        Bundle args = new Bundle();
        args.putString("current_orientation", currentOrientation.name());
        fragment.setArguments(args);
        return fragment;
    }
    
    /**
     * Callback interface for orientation selection.
     */
    public interface OnOrientationSelectedListener {
        void onOrientationSelected(StreamQuality.StreamOrientation orientation);
    }
    
    /**
     * Set callback listener.
     */
    public void setOnOrientationSelectedListener(OnOrientationSelectedListener listener) {
        this.listener = listener;
    }
    
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_stream_orientation, container, false);
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // Get current orientation from arguments
        Bundle args = getArguments();
        if (args != null) {
            String orientationName = args.getString("current_orientation", "AUTO");
            currentOrientation = StreamQuality.StreamOrientation.valueOf(orientationName);
        } else {
            currentOrientation = StreamQuality.StreamOrientation.AUTO;
        }
        
        selectedOrientation = currentOrientation;
        
        // Initialize views
        rowPortrait = view.findViewById(R.id.rowPortrait);
        rowLandscape = view.findViewById(R.id.rowLandscape);
        rowAuto = view.findViewById(R.id.rowAuto);
        checkPortrait = view.findViewById(R.id.checkPortrait);
        checkLandscape = view.findViewById(R.id.checkLandscape);
        checkAuto = view.findViewById(R.id.checkAuto);
        
        // Show current selection
        updateSelection();
        
        // Set up row click listeners
        rowPortrait.setOnClickListener(v -> selectOrientation(StreamQuality.StreamOrientation.PORTRAIT));
        rowLandscape.setOnClickListener(v -> selectOrientation(StreamQuality.StreamOrientation.LANDSCAPE));
        rowAuto.setOnClickListener(v -> selectOrientation(StreamQuality.StreamOrientation.AUTO));
    }
    
    private void selectOrientation(StreamQuality.StreamOrientation orientation) {
        selectedOrientation = orientation;
        updateSelection();
        
        // Check if orientation changed
        if (selectedOrientation == currentOrientation) {
            Toast.makeText(getContext(), "Orientation already set to " + selectedOrientation.getDisplayName(), Toast.LENGTH_SHORT).show();
            dismiss();
            return;
        }
        
        // Apply new orientation
        if (listener != null) {
            listener.onOrientationSelected(selectedOrientation);
        }
        
        // Update in RemoteStreamManager
        RemoteStreamManager manager = RemoteStreamManager.getInstance();
        manager.setStreamOrientation(selectedOrientation, requireContext());
        
        Toast.makeText(getContext(), 
            "Stream orientation updated to " + selectedOrientation.getDisplayName() + ".", 
            Toast.LENGTH_SHORT).show();
        
        dismiss();
    }
    
    private void updateSelection() {
        // Hide all checkmarks
        checkPortrait.setVisibility(View.GONE);
        checkLandscape.setVisibility(View.GONE);
        checkAuto.setVisibility(View.GONE);
        
        // Show selected checkmark
        switch (selectedOrientation) {
            case PORTRAIT:
                checkPortrait.setVisibility(View.VISIBLE);
                break;
            case LANDSCAPE:
                checkLandscape.setVisibility(View.VISIBLE);
                break;
            case AUTO:
                checkAuto.setVisibility(View.VISIBLE);
                break;
        }
    }
    
}
