package com.fadcam.ui.bottomsheet;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;
import com.fadcam.streaming.RemoteStreamManager;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * Bottom sheet displaying detailed battery information.
 */
public class BatteryInfoBottomSheet extends BottomSheetDialogFragment {
    
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_battery_info, container, false);
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        TextView currentBatteryLevel = view.findViewById(R.id.currentBatteryLevel);
        TextView consumedPercentage = view.findViewById(R.id.consumedPercentage);
        TextView consumptionRate = view.findViewById(R.id.consumptionRate);
        TextView timeRemaining = view.findViewById(R.id.timeRemaining);
        TextView chargingStatus = view.findViewById(R.id.chargingStatus);
        LinearLayout consumptionCard = view.findViewById(R.id.consumptionCard);
        LinearLayout timeRemainingCard = view.findViewById(R.id.timeRemainingCard);
        LinearLayout chargingStatusCard = view.findViewById(R.id.chargingStatusCard);
        LinearLayout lowBatteryWarning = view.findViewById(R.id.lowBatteryWarning);
        
        // Get battery info from RemoteStreamManager
        RemoteStreamManager manager = RemoteStreamManager.getInstance();
        int batteryPercent = manager.getBatteryPercentage(requireContext());
        String batteryInfo = manager.getBatteryInfo(requireContext());
        
        // Parse battery info
        currentBatteryLevel.setText(batteryPercent + "%");
        
        // Show charging status
        if (batteryInfo.contains("🔌 Charging")) {
            chargingStatus.setText("🔌 Charging");
            chargingStatusCard.setVisibility(View.VISIBLE);
        } else {
            chargingStatus.setText("Not Charging");
            chargingStatusCard.setVisibility(View.VISIBLE);
        }
        
        // Check if streaming to show consumption data
        if (batteryInfo.contains("consumed:")) {
            // Extract consumed percentage
            String[] parts = batteryInfo.split("consumed: ");
            if (parts.length > 1) {
                String consumedPart = parts[1].split("%")[0];
                consumedPercentage.setText(consumedPart + "%");
                
                // Calculate consumption rate if possible
                java.util.Map<String, Object> uptimeDetails = manager.getUptimeDetails();
                long uptimeSeconds = (long) uptimeDetails.get("seconds");
                if (uptimeSeconds > 3600) { // At least 1 hour
                    double consumed = Double.parseDouble(consumedPart);
                    double hours = uptimeSeconds / 3600.0;
                    double ratePerHour = consumed / hours;
                    consumptionRate.setText(String.format("Consumption rate: ~%.1f%% per hour", ratePerHour));
                } else {
                    consumptionRate.setVisibility(View.GONE);
                }
                
                // Extract time remaining if available
                if (batteryInfo.contains("~") && batteryInfo.contains("h remaining")) {
                    String timePart = batteryInfo.split("~")[1].split("h remaining")[0].trim();
                    timeRemaining.setText("~" + timePart + " hours");
                } else {
                    timeRemainingCard.setVisibility(View.GONE);
                }
            }
        } else {
            // Not streaming or no consumption data yet
            consumptionCard.setVisibility(View.GONE);
            timeRemainingCard.setVisibility(View.GONE);
        }
        
        // Show low battery warning if < 20% (only if not charging)
        if (batteryPercent < 20 && !batteryInfo.contains("🔌 Charging")) {
            lowBatteryWarning.setVisibility(View.VISIBLE);
        }
    }
}
