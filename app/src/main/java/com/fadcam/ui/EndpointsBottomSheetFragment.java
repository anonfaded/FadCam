package com.fadcam.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * Bottom sheet displaying all server endpoints with GET/POST separation
 */
public class EndpointsBottomSheetFragment extends BottomSheetDialogFragment {

    private static final String ARG_SERVER_URL = "server_url";
    private String serverUrl;

    public static EndpointsBottomSheetFragment newInstance(String serverUrl) {
        EndpointsBottomSheetFragment fragment = new EndpointsBottomSheetFragment();
        Bundle args = new Bundle();
        args.putString(ARG_SERVER_URL, serverUrl);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            serverUrl = getArguments().getString(ARG_SERVER_URL, "");
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_endpoints_simple, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        LinearLayout getContainer = view.findViewById(R.id.get_endpoints_container);
        LinearLayout postContainer = view.findViewById(R.id.post_endpoints_container);

        // GET Endpoints
        addEndpoint(getContainer, "Live Stream (HLS)", "/live.m3u8", "GET");
        addEndpoint(getContainer, "Init Segment", "/init.mp4", "GET");
        addEndpoint(getContainer, "Status API", "/status", "GET");
        addEndpoint(getContainer, "Get Volume Level", "/audio/volume", "GET");

        // POST Endpoints
        addEndpoint(postContainer, "Toggle Torch", "/torch/toggle", "POST");
        addEndpoint(postContainer, "Toggle Recording", "/recording/toggle", "POST");
        addEndpoint(postContainer, "Set Recording Mode", "/config/recordingMode", "POST");
        addEndpoint(postContainer, "Set Stream Quality", "/config/streamQuality", "POST");
        addEndpoint(postContainer, "Set Battery Warning", "/config/batteryWarning", "POST");
        addEndpoint(postContainer, "Set Volume Level", "/audio/volume", "POST");
        addEndpoint(postContainer, "Ring Alarm", "/alarm/ring", "POST");
        addEndpoint(postContainer, "Stop Alarm", "/alarm/stop", "POST");
        addEndpoint(postContainer, "Schedule Alarm", "/alarm/schedule", "POST");
    }

    private void addEndpoint(LinearLayout container, String label, String endpoint, String method) {
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        View row = inflater.inflate(R.layout.endpoint_row_item, container, false);

        TextView methodBadge = row.findViewById(R.id.endpoint_method_badge);
        TextView labelView = row.findViewById(R.id.endpoint_label_text);
        TextView urlView = row.findViewById(R.id.endpoint_url_text);
        View copyButton = row.findViewById(R.id.endpoint_copy_button);

        methodBadge.setText(method);
        methodBadge.setBackgroundResource("GET".equals(method) ? 
            R.drawable.method_badge_get : R.drawable.method_badge_post);

        labelView.setText(label);

        String fullUrl = serverUrl.isEmpty() || serverUrl.contains("---") ? 
            "Server not active" : serverUrl + endpoint;
        urlView.setText(fullUrl);

        // Copy button click listener
        copyButton.setOnClickListener(v -> {
            if (!fullUrl.contains("not active")) {
                copyToClipboard(fullUrl);
                Toast.makeText(requireContext(), "Copied: " + endpoint, Toast.LENGTH_SHORT).show();
            }
        });

        container.addView(row);
    }

    private void copyToClipboard(String text) {
        ClipboardManager clipboard = (ClipboardManager) requireContext()
            .getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("Endpoint URL", text);
        clipboard.setPrimaryClip(clip);
    }
}
