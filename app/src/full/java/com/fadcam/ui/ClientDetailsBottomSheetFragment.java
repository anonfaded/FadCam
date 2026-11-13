package com.fadcam.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;
import com.fadcam.ui.bottomsheet.ClientEventLogsBottomSheet;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.util.List;

/**
 * Bottom sheet displaying connected client information
 */
public class ClientDetailsBottomSheetFragment extends BottomSheetDialogFragment {

    private static final String ARG_CLIENT_IPS = "client_ips";
    private List<String> clientIPs;

    public static ClientDetailsBottomSheetFragment newInstance(List<String> clientIPs) {
        ClientDetailsBottomSheetFragment fragment = new ClientDetailsBottomSheetFragment();
        Bundle args = new Bundle();
        args.putStringArrayList(ARG_CLIENT_IPS, new java.util.ArrayList<>(clientIPs));
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            clientIPs = getArguments().getStringArrayList(ARG_CLIENT_IPS);
            if (clientIPs == null) {
                clientIPs = new java.util.ArrayList<>();
            }
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_client_details, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        LinearLayout clientsContainer = view.findViewById(R.id.clients_container);

        if (clientIPs.isEmpty()) {
            TextView noClients = new TextView(requireContext());
            noClients.setText("No clients connected");
            noClients.setTextSize(14);
            noClients.setPadding(16, 16, 16, 16);
            clientsContainer.addView(noClients);
            return;
        }

        for (int i = 0; i < clientIPs.size(); i++) {
            String ip = clientIPs.get(i);
            addClientRow(clientsContainer, ip, i + 1);
        }
    }

    private void addClientRow(LinearLayout container, String ip, int clientNumber) {
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        View row = inflater.inflate(R.layout.client_info_row_item, container, false);

        TextView clientLabel = row.findViewById(R.id.client_label);
        TextView clientIp = row.findViewById(R.id.client_ip);
        TextView clientType = row.findViewById(R.id.client_type);
        ImageView copyIpButton = row.findViewById(R.id.copy_ip_button);
        LinearLayout eventLogsButton = row.findViewById(R.id.event_logs_button);

        clientLabel.setText("Client " + clientNumber);
        clientIp.setText(ip);
        
        // Try to determine client type from IP (this is basic - could be enhanced)
        String clientType_text = ip.startsWith("127.") ? "Local" : "Remote";
        clientType.setText(clientType_text);

        // Get API call statistics from RemoteStreamManager
        com.fadcam.streaming.RemoteStreamManager manager = com.fadcam.streaming.RemoteStreamManager.getInstance();
        com.fadcam.streaming.model.ClientMetrics metrics = manager.getClientMetrics(ip);
        
        // Get the main vertical container (the outer LinearLayout's first and only child)
        LinearLayout mainContainer = null;
        if (row instanceof LinearLayout) {
            LinearLayout rowLinear = (LinearLayout) row;
            if (rowLinear.getChildCount() > 0 && rowLinear.getChildAt(0) instanceof LinearLayout) {
                mainContainer = (LinearLayout) rowLinear.getChildAt(0);
            }
        }
        
        // Create and add API stats text view
        if (mainContainer != null) {
            TextView apiStatsText = new TextView(requireContext());
            
            if (metrics != null) {
                int totalApiCalls = metrics.getGetRequestsCount() + metrics.getPostRequestsCount();
                apiStatsText.setText(String.format("API Calls: %d (GET: %d, POST: %d)",
                    totalApiCalls, metrics.getGetRequestsCount(), metrics.getPostRequestsCount()));
            } else {
                apiStatsText.setText("API Calls: 0 (GET: 0, POST: 0)");
            }
            
            apiStatsText.setTextSize(11);
            apiStatsText.setTextColor(android.graphics.Color.parseColor("#8b949e"));
            
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, 6, 0, 0);
            
            mainContainer.addView(apiStatsText, params);
            
            // Add helper text explaining GET vs POST
            TextView helperText = new TextView(requireContext());
            helperText.setText("📥 GET: status queries  •  📤 POST: control commands");
            helperText.setTextSize(10);
            helperText.setTextColor(android.graphics.Color.parseColor("#6e7681"));
            helperText.setAlpha(0.75f);
            
            LinearLayout.LayoutParams helperParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
            helperParams.setMargins(0, 2, 0, 0);
            
            mainContainer.addView(helperText, helperParams);
        }

        // Copy IP button click listener
        copyIpButton.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Client IP", ip);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(requireContext(), "IP copied: " + ip, Toast.LENGTH_SHORT).show();
        });

        // Event logs button click listener
        eventLogsButton.setOnClickListener(v -> {
            ClientEventLogsBottomSheet logsSheet = ClientEventLogsBottomSheet.newInstance(ip);
            logsSheet.show(getParentFragmentManager(), "client_event_logs");
        });

        container.addView(row);
    }
}
