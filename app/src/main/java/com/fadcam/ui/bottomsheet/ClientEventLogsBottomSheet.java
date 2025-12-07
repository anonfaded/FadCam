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
import com.fadcam.streaming.model.ClientEvent;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Bottom sheet displaying event logs for a specific client.
 */
public class ClientEventLogsBottomSheet extends BottomSheetDialogFragment {

    private static final String ARG_CLIENT_IP = "client_ip";
    private String clientIP;

    public static ClientEventLogsBottomSheet newInstance(String clientIP) {
        ClientEventLogsBottomSheet fragment = new ClientEventLogsBottomSheet();
        Bundle args = new Bundle();
        args.putString(ARG_CLIENT_IP, clientIP);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            clientIP = getArguments().getString(ARG_CLIENT_IP, "Unknown");
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_client_event_logs, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        TextView clientIPTitle = view.findViewById(R.id.client_ip_title);
        LinearLayout eventsContainer = view.findViewById(R.id.events_container);

        clientIPTitle.setText("Events for " + clientIP);

        // Get all events and filter by client IP
        RemoteStreamManager manager = RemoteStreamManager.getInstance();
        List<ClientEvent> allEvents = manager.getClientEventLog();
        List<ClientEvent> clientEvents = new ArrayList<>();

        for (ClientEvent event : allEvents) {
            if (clientIP.equals(event.getClientIP())) {
                clientEvents.add(event);
            }
        }

        if (clientEvents.isEmpty()) {
            TextView noEvents = new TextView(requireContext());
            noEvents.setText("No events recorded for this client");
            noEvents.setTextSize(14);
            noEvents.setPadding(16, 16, 16, 16);
            noEvents.setTextColor(requireContext().getColor(android.R.color.darker_gray));
            eventsContainer.addView(noEvents);
            return;
        }

        // Add events in reverse order (newest first)
        for (int i = clientEvents.size() - 1; i >= 0; i--) {
            ClientEvent event = clientEvents.get(i);
            addEventRow(eventsContainer, event);
        }
    }

    private void addEventRow(LinearLayout container, ClientEvent event) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(16, 12, 16, 12);
        row.setBackgroundResource(com.fadcam.R.drawable.settings_group_card_bg);
        
        LinearLayout.LayoutParams rowParams = (LinearLayout.LayoutParams) row.getLayoutParams();
        rowParams.bottomMargin = 8;
        row.setLayoutParams(rowParams);

        // Event type and timestamp header
        LinearLayout headerLayout = new LinearLayout(requireContext());
        headerLayout.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        headerLayout.setOrientation(LinearLayout.HORIZONTAL);
        headerLayout.setGravity(android.view.Gravity.CENTER_VERTICAL);

        // Event type badge
        TextView eventType = new TextView(requireContext());
        eventType.setText(event.getEventType().name());
        eventType.setTextSize(12);
        eventType.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));
        eventType.setTextColor(0xFFFFFFFF); // White text
        eventType.setBackgroundColor(getEventTypeColor(event.getEventType()));
        eventType.setPadding(8, 4, 8, 4);
        eventType.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        headerLayout.addView(eventType);

        // Timestamp
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.US);
        String timeStr = sdf.format(new Date(event.getTimestamp()));
        
        TextView timestamp = new TextView(requireContext());
        timestamp.setText(timeStr);
        timestamp.setTextSize(11);
        timestamp.setTextColor(requireContext().getColor(android.R.color.darker_gray));
        LinearLayout.LayoutParams timestampParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        timestampParams.setMarginStart(12);
        timestamp.setLayoutParams(timestampParams);
        headerLayout.addView(timestamp);

        row.addView(headerLayout);

        // Event details
        if (event.getDetails() != null && !event.getDetails().isEmpty()) {
            TextView details = new TextView(requireContext());
            details.setText(event.getDetails());
            details.setTextSize(11);
            details.setTextColor(requireContext().getColor(android.R.color.darker_gray));
            LinearLayout.LayoutParams detailsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            detailsParams.topMargin = 4;
            details.setLayoutParams(detailsParams);
            row.addView(details);
        }

        container.addView(row);
    }

    private int getEventTypeColor(ClientEvent.EventType type) {
        switch (type) {
            case CONNECTED:
                return 0xFF4CAF50; // Green
            case DISCONNECTED:
                return 0xFFF44336; // Red
            case FIRST_REQUEST:
                return 0xFF2196F3; // Blue
            case DATA_MILESTONE:
                return 0xFFFFC107; // Amber
            default:
                return 0xFF9E9E9E; // Gray
        }
    }
}
