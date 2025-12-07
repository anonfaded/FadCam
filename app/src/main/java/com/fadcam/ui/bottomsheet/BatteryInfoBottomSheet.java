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
import org.json.JSONException;
import org.json.JSONObject;

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
        
        // Get battery info from RemoteStreamManager using new JSON format
        RemoteStreamManager manager = RemoteStreamManager.getInstance();
        String batteryDetailsJson = manager.getBatteryDetailsJson(requireContext());
        
        try {
            // Parse the battery details JSON
            JSONObject batteryData = new JSONObject(batteryDetailsJson);
            int batteryPercent = batteryData.getInt("percent");
            String status = batteryData.getString("status");
            int consumed = batteryData.getInt("consumed");
            double remainingHours = batteryData.getDouble("remaining_hours");
            String warning = batteryData.getString("warning");
            
            // Display current battery level
            currentBatteryLevel.setText(batteryPercent + "%");
            
            // Show charging status
            chargingStatus.setText(status.equals("Charging") ? "🔌 " + status : "⚇ " + status);
            chargingStatusCard.setVisibility(View.VISIBLE);
            
            // Show consumption data if available (streaming active and battery consumed)
            if (consumed > 0) {
                consumptionCard.setVisibility(View.VISIBLE);
                consumedPercentage.setText(consumed + "%");
                
                // Calculate consumption rate if possible
                java.util.Map<String, Object> uptimeDetails = manager.getUptimeDetails();
                long uptimeSeconds = (long) uptimeDetails.get("seconds");
                if (uptimeSeconds > 3600) { // At least 1 hour
                    double hours = uptimeSeconds / 3600.0;
                    double ratePerHour = consumed / hours;
                    consumptionRate.setText(String.format("Rate: ~%.1f%% per hour", ratePerHour));
                } else {
                    consumptionRate.setVisibility(View.GONE);
                }
            } else {
                // Not streaming or no consumption yet
                consumptionCard.setVisibility(View.GONE);
            }
            
            // Show time remaining if available
            if (remainingHours > 0) {
                timeRemainingCard.setVisibility(View.VISIBLE);
                if (remainingHours < 500) {
                    timeRemaining.setText(String.format("~%.1f hours remaining", remainingHours));
                } else {
                    timeRemaining.setText("~" + (int)remainingHours + "h+ remaining");
                }
            } else {
                timeRemainingCard.setVisibility(View.GONE);
            }
            
            // Show low battery warning if needed
            if (!warning.isEmpty()) {
                lowBatteryWarning.setVisibility(View.VISIBLE);
            } else {
                lowBatteryWarning.setVisibility(View.GONE);
            }
            
        } catch (JSONException e) {
            // Fallback to simple display if parsing fails
            currentBatteryLevel.setText("Unable to parse battery info");
            consumptionCard.setVisibility(View.GONE);
            timeRemainingCard.setVisibility(View.GONE);
            chargingStatusCard.setVisibility(View.GONE);
            lowBatteryWarning.setVisibility(View.GONE);
        }
    }
}

