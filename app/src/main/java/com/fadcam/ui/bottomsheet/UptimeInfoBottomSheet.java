package com.fadcam.ui.bottomsheet;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;
import com.fadcam.streaming.RemoteStreamManager;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.text.NumberFormat;
import java.util.Locale;
import java.util.Map;

/**
 * Bottom sheet displaying detailed uptime information.
 */
public class UptimeInfoBottomSheet extends BottomSheetDialogFragment {
    
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_uptime_info, container, false);
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        TextView formattedUptime = view.findViewById(R.id.formattedUptime);
        TextView uptimeSeconds = view.findViewById(R.id.uptimeSeconds);
        TextView startDate = view.findViewById(R.id.startDate);
        TextView startTime = view.findViewById(R.id.startTime);
        TextView startTimestamp = view.findViewById(R.id.startTimestamp);
        
        // Get uptime details from RemoteStreamManager
        RemoteStreamManager manager = RemoteStreamManager.getInstance();
        Map<String, Object> details = manager.getUptimeDetails();
        
        // Format and display (with proper type conversion)
        String formatted = (String) details.get("formatted");
        Object secondsObj = details.get("seconds");
        long seconds = (secondsObj instanceof Long) ? (Long) secondsObj : ((Number) secondsObj).longValue();
        String startDateStr = (String) details.get("startDate");
        String startTimeStr = (String) details.get("startTime");
        Object timestampObj = details.get("startTimestamp");
        long timestamp = (timestampObj instanceof Long) ? (Long) timestampObj : ((Number) timestampObj).longValue();

        if (seconds == 0) {
            formattedUptime.setText(R.string.remote_not_started);
            startDate.setText(R.string.remote_not_started);
            startTime.setText(R.string.remote_not_started);

            NumberFormat numberFormat = NumberFormat.getInstance(Locale.US);
            uptimeSeconds.setText("0 " + getString(R.string.remote_uptime_seconds_label));
            startTimestamp.setText(getString(R.string.remote_uptime_unix_prefix) + " 0");
        } else {
            formattedUptime.setText(formatted);

            // Format seconds with comma separator
            NumberFormat numberFormat = NumberFormat.getInstance(Locale.US);
            uptimeSeconds.setText(numberFormat.format(seconds) + " " + getString(R.string.remote_uptime_seconds_label));

            startDate.setText(startDateStr);
            startTime.setText(startTimeStr);
            startTimestamp.setText(getString(R.string.remote_uptime_unix_prefix) + " " + timestamp);
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
