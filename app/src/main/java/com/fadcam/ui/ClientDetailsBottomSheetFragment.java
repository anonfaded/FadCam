package com.fadcam.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;
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

        clientLabel.setText("Client " + clientNumber);
        clientIp.setText(ip);
        
        // Try to determine client type from IP (this is basic - could be enhanced)
        String clientType_text = ip.startsWith("127.") ? "Local" : "Remote";
        clientType.setText(clientType_text);

        container.addView(row);
    }
}
