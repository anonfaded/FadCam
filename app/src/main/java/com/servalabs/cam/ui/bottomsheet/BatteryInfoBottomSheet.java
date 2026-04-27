package com.servalabs.cam.ui.bottomsheet;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.servalabs.cam.R;
import com.servalabs.cam.streaming.RemoteStreamManager;
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
        LinearLayout lowBatteryWarningCard = view.findViewById(R.id.lowBatteryWarningCard);
        TextView lowBatteryWarningText = view.findViewById(R.id.lowBatteryWarningText);
        
        // Battery warning threshold UI
        EditText batteryWarningInput = view.findViewById(R.id.batteryWarningInput);
        Button setBatteryWarningBtn = view.findViewById(R.id.setBatteryWarningBtn);
        TextView warningThresholdStatus = view.findViewById(R.id.warningThresholdStatus);
        
        // Battery capacity (mAh) UI
        EditText batteryCapacityInput = view.findViewById(R.id.batteryCapacityInput);
        Button setBatteryCapacityBtn = view.findViewById(R.id.setBatteryCapacityBtn);
        TextView batteryCapacityStatus = view.findViewById(R.id.batteryCapacityStatus);
        TextView batteryEstimateExample1 = view.findViewById(R.id.batteryEstimateExample1);
        TextView batteryEstimateExample2 = view.findViewById(R.id.batteryEstimateExample2);
        
        // Get battery info from RemoteStreamManager using new JSON format
        RemoteStreamManager manager = RemoteStreamManager.getInstance();
        String batteryDetailsJson = manager.getBatteryDetailsJson(requireContext());
        
        // Load current battery warning threshold and set it in the input
        int currentThreshold = manager.getBatteryWarningThreshold();
        batteryWarningInput.setText(String.valueOf(currentThreshold));
        warningThresholdStatus.setText("Current threshold: " + currentThreshold + "%");
        
        // Load current battery capacity (mAh) and set it in the input
        int currentCapacityMah = manager.getBatteryCapacityMah();
        batteryCapacityInput.setText(String.valueOf(currentCapacityMah));
        batteryCapacityStatus.setText("Current capacity: " + currentCapacityMah + " mAh");
        
        // Update battery estimate examples based on current mAh
        updateBatteryEstimateExamples(currentCapacityMah, batteryEstimateExample1, batteryEstimateExample2);
        
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
            chargingStatus.setText(status.equals("Charging") ? "🔌 Charging" : "🔋 Discharging");
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
            
            // Show time remaining - ALWAYS show now (based on tested formula)
            timeRemainingCard.setVisibility(View.VISIBLE);
            if (remainingHours > 0) {
                if (remainingHours < 500) {
                    timeRemaining.setText(String.format("~%.1f hours remaining", remainingHours));
                } else {
                    timeRemaining.setText("~" + (int)remainingHours + "h+ remaining");
                }
            } else {
                // Fallback if calculation failed
                timeRemaining.setText("Unable to calculate");
            }
            
            // Show low battery warning if needed
            if (!warning.isEmpty()) {
                lowBatteryWarningCard.setVisibility(View.VISIBLE);
                if (lowBatteryWarningText != null) {
                    lowBatteryWarningText.setText(warning);
                }
            } else {
                lowBatteryWarningCard.setVisibility(View.GONE);
            }
            
        } catch (JSONException e) {
            // Fallback to simple display if parsing fails
            currentBatteryLevel.setText("Unable to parse battery info");
            consumptionCard.setVisibility(View.GONE);
            timeRemainingCard.setVisibility(View.GONE);
            chargingStatusCard.setVisibility(View.GONE);
            lowBatteryWarningCard.setVisibility(View.GONE);
        }
        
        // Set battery warning threshold button click listener
        setBatteryWarningBtn.setOnClickListener(v -> {
            String thresholdStr = batteryWarningInput.getText().toString().trim();
            if (thresholdStr.isEmpty()) {
                Toast.makeText(requireContext(), "Please enter a percentage", Toast.LENGTH_SHORT).show();
                return;
            }
            
            try {
                int threshold = Integer.parseInt(thresholdStr);
                if (threshold < 5 || threshold > 100) {
                    Toast.makeText(requireContext(), "Percentage must be between 5 and 100", Toast.LENGTH_SHORT).show();
                    return;
                }
                
                // Make API call to set battery warning threshold
                setBatteryWarningThreshold(threshold, warningThresholdStatus);
            } catch (NumberFormatException e) {
                Toast.makeText(requireContext(), "Invalid number", Toast.LENGTH_SHORT).show();
            }
        });
        
        // Set battery capacity button click listener
        setBatteryCapacityBtn.setOnClickListener(v -> {
            String capacityStr = batteryCapacityInput.getText().toString().trim();
            if (capacityStr.isEmpty()) {
                Toast.makeText(requireContext(), "Please enter battery capacity in mAh", Toast.LENGTH_SHORT).show();
                return;
            }
            
            try {
                int capacity = Integer.parseInt(capacityStr);
                if (capacity < 1000 || capacity > 10000) {
                    Toast.makeText(requireContext(), "Capacity must be between 1000 and 10000 mAh", Toast.LENGTH_SHORT).show();
                    return;
                }
                
                // Save battery capacity
                setBatteryCapacity(capacity, batteryCapacityStatus, batteryEstimateExample1, batteryEstimateExample2);
            } catch (NumberFormatException e) {
                Toast.makeText(requireContext(), "Invalid number", Toast.LENGTH_SHORT).show();
            }
        });
        
        // Update status text with current threshold if available
        warningThresholdStatus.setText("Current threshold: " + currentThreshold + "%");
    }
    
    /**
     * Set battery warning threshold via HTTP POST request
     */
    private void setBatteryWarningThreshold(int threshold, TextView statusText) {
        Thread thread = new Thread(() -> {
            try {
                RemoteStreamManager manager = RemoteStreamManager.getInstance();
                manager.setBatteryWarningThreshold(threshold);
                
                // Update UI on main thread
                requireActivity().runOnUiThread(() -> {
                    Toast.makeText(requireContext(), "Battery warning threshold set to " + threshold + "%", Toast.LENGTH_SHORT).show();
                    statusText.setText("Current threshold: " + threshold + "%");
                });
            } catch (Exception e) {
                requireActivity().runOnUiThread(() -> {
                    Toast.makeText(requireContext(), "Failed to set threshold: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
        thread.start();
    }
    
    /**
     * Set battery capacity (mAh) and update estimate examples & battery estimate live
     */
    private void setBatteryCapacity(int capacityMah, TextView statusText, TextView example1, TextView example2) {
        Thread thread = new Thread(() -> {
            try {
                RemoteStreamManager manager = RemoteStreamManager.getInstance();
                manager.setBatteryCapacityMah(capacityMah);
                
                // Get updated battery estimates with new mAh
                String updatedBatteryJson = manager.getBatteryDetailsJson(requireContext());
                
                // Update UI on main thread
                requireActivity().runOnUiThread(() -> {
                    Toast.makeText(requireContext(), "Battery capacity set to " + capacityMah + " mAh", Toast.LENGTH_SHORT).show();
                    statusText.setText("Current capacity: " + capacityMah + " mAh");
                    updateBatteryEstimateExamples(capacityMah, example1, example2);
                    
                    // Refresh the remaining hours estimate with new mAh calculation
                    try {
                        org.json.JSONObject batteryData = new org.json.JSONObject(updatedBatteryJson);
                        double remainingHours = batteryData.getDouble("remaining_hours");
                        TextView timeRemaining = getView() != null ? getView().findViewById(R.id.timeRemaining) : null;
                        if (timeRemaining != null) {
                            if (remainingHours > 0) {
                                if (remainingHours < 500) {
                                    timeRemaining.setText(String.format("~%.1f hours remaining", remainingHours));
                                } else {
                                    timeRemaining.setText("~" + (int)remainingHours + "h+ remaining");
                                }
                            }
                        }
                    } catch (Exception e) {
                        // If refresh fails, leave it as is
                    }
                });
            } catch (Exception e) {
                requireActivity().runOnUiThread(() -> {
                    Toast.makeText(requireContext(), "Failed to set capacity: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
        thread.start();
    }
    
    /**
     * Calculate and update dynamic battery estimate examples based on device mAh
     */
    private void updateBatteryEstimateExamples(int deviceMah, TextView example1, TextView example2) {
        // Base rate: 7.625% per hour for 5000mAh device
        // Adjusted rate = 7.625 * (5000 / deviceMah)
        final double BASE_RATE = 7.625;
        final int REFERENCE_MAH = 5000;
        double adjustedRate = BASE_RATE * (REFERENCE_MAH / (double) deviceMah);
        
        // Calculate remaining hours at 100% battery
        double hoursAt100Percent = 100.0 / adjustedRate;
        
        // Update example texts
        example1.setText(String.format("• Per-hour consumption: ~%.1f%%", adjustedRate));
        example2.setText(String.format("• At 100%% battery: ~%.1f hours", hoursAt100Percent));
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
