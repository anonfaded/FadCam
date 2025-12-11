package com.fadcam.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;
import com.fadcam.streaming.model.NetworkHealth;
import com.fadcam.streaming.util.NetworkMonitor;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Bottom sheet showing detailed network health metrics.
 */
public class NetworkHealthBottomSheet extends BottomSheetDialogFragment {
    
    private TextView statusText;
    private TextView downloadSpeedText;
    private TextView uploadSpeedText;
    private TextView latencyText;
    private TextView lastTestedText;
    private TextView testNowButton;
    
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_network_health, container, false);
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        statusText = view.findViewById(R.id.network_status_text);
        downloadSpeedText = view.findViewById(R.id.download_speed_text);
        uploadSpeedText = view.findViewById(R.id.upload_speed_text);
        latencyText = view.findViewById(R.id.latency_text);
        lastTestedText = view.findViewById(R.id.last_tested_text);
        testNowButton = view.findViewById(R.id.test_now_button);
        
        // Test Now button
        testNowButton.setOnClickListener(v -> {
            testNowButton.setText("Testing...");
            testNowButton.setEnabled(false);
            
            NetworkMonitor.getInstance().testNow();
            
            // Re-enable after 3 seconds
            view.postDelayed(() -> {
                if (testNowButton != null) {
                    testNowButton.setText("Test Now");
                    testNowButton.setEnabled(true);
                    loadNetworkData();
                }
            }, 3000);
        });
        
        // Load data
        loadNetworkData();
    }
    
    private void loadNetworkData() {
        NetworkHealth health = NetworkMonitor.getInstance().getNetworkHealth();
        
        // Status
        String statusStr = capitalize(health.getStatusString());
        statusText.setText(statusStr);
        statusText.setTextColor(health.getStatusColorInt());
        
        // Download speed
        if (health.getDownloadSpeedMbps() > 0) {
            downloadSpeedText.setText(String.format(Locale.US, "%.2f Mbps", health.getDownloadSpeedMbps()));
        } else {
            downloadSpeedText.setText("Not tested");
        }
        
        // Upload speed
        if (health.getUploadSpeedMbps() > 0) {
            uploadSpeedText.setText(String.format(Locale.US, "%.2f Mbps", health.getUploadSpeedMbps()));
        } else {
            uploadSpeedText.setText("Not tested");
        }
        
        // Latency
        if (health.getLatencyMs() >= 0) {
            latencyText.setText(health.getLatencyMs() + " ms");
        } else {
            latencyText.setText("Unknown");
        }
        
        // Last tested
        if (health.getLastMeasurementTime() > 0) {
            long timeSince = System.currentTimeMillis() - health.getLastMeasurementTime();
            String timeStr;
            if (timeSince < 60000) {
                timeStr = "Just now";
            } else if (timeSince < 3600000) {
                timeStr = (timeSince / 60000) + " min ago";
            } else {
                SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.US);
                timeStr = sdf.format(new Date(health.getLastMeasurementTime()));
            }
            lastTestedText.setText("Last tested: " + timeStr);
        } else {
            lastTestedText.setText("Last tested: Never");
        }
    }
    
    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }
}
