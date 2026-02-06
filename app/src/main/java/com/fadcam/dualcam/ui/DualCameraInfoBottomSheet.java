package com.fadcam.dualcam.ui;

import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;
import com.fadcam.dualcam.DualCameraCapability;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * Bottom sheet showing detailed device compatibility information for dual camera mode.
 * Uses a dedicated XML layout with the dark gradient background, matching the
 * {@link com.fadcam.ui.SegmentsInfoBottomSheet} pattern.
 */
public class DualCameraInfoBottomSheet extends BottomSheetDialogFragment {

    /** Factory method. */
    @NonNull
    public static DualCameraInfoBottomSheet newInstance() {
        return new DualCameraInfoBottomSheet();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_dual_cam_info, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Close button
        View closeBtn = view.findViewById(R.id.btn_close);
        if (closeBtn != null) {
            closeBtn.setOnClickListener(v -> dismiss());
        }

        // Populate device info
        DualCameraCapability capability = new DualCameraCapability(requireContext());
        boolean supported = capability.isSupported();
        boolean confirmed = capability.isConcurrentApiConfirmed();

        // Device
        TextView deviceVal = view.findViewById(R.id.info_device_value);
        if (deviceVal != null) {
            deviceVal.setText(Build.MANUFACTURER + " " + Build.MODEL);
        }

        // Processor
        TextView processorVal = view.findViewById(R.id.info_processor_value);
        if (processorVal != null) {
            String processor;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                processor = Build.SOC_MODEL;
                if (processor == null || processor.isEmpty()
                        || "unknown".equalsIgnoreCase(processor)) {
                    processor = Build.HARDWARE;
                }
            } else {
                processor = Build.HARDWARE;
            }
            processorVal.setText(processor);
        }

        // Android Version
        TextView androidVal = view.findViewById(R.id.info_android_value);
        if (androidVal != null) {
            androidVal.setText("Android " + Build.VERSION.RELEASE);
        }

        // API Level
        TextView apiVal = view.findViewById(R.id.info_api_value);
        if (apiVal != null) {
            apiVal.setText(String.valueOf(Build.VERSION.SDK_INT));
        }

        // Concurrent Camera API
        TextView concurrentVal = view.findViewById(R.id.info_concurrent_value);
        if (concurrentVal != null) {
            concurrentVal.setText(confirmed
                    ? getString(R.string.dual_cam_info_concurrent_yes)
                    : getString(R.string.dual_cam_info_concurrent_no));
        }

        // Status text
        TextView statusText = view.findViewById(R.id.info_status);
        if (statusText != null) {
            if (confirmed) {
                statusText.setText(R.string.dual_cam_info_supported_status);
                statusText.setTextColor(0xFF4CAF50); // green
            } else if (supported) {
                statusText.setText(R.string.dual_cam_info_supported_unconfirmed);
                statusText.setTextColor(0xFFFF9800); // amber
            } else {
                statusText.setText(R.string.dual_cam_info_unsupported_status);
                statusText.setTextColor(0xFFFF5252); // red
            }
        }

        // Explanation section — show when not confirmed
        LinearLayout explanationContainer = view.findViewById(R.id.info_explanation_container);
        if (explanationContainer != null) {
            explanationContainer.setVisibility(confirmed ? View.GONE : View.VISIBLE);
        }

        // Unsupported reason
        TextView reasonText = view.findViewById(R.id.info_reason);
        if (reasonText != null && !supported) {
            String reason = capability.getUnsupportedReason();
            if (reason != null) {
                reasonText.setText("Reason: " + reason);
                reasonText.setVisibility(View.VISIBLE);
            }
        }
    }
}
