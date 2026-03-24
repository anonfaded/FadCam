package com.fadcam.ui;

import com.fadcam.Log;
import com.fadcam.FLog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.fadcam.R;
import com.fadcam.streaming.RemoteStreamManager;
import com.fadcam.streaming.RemoteStreamService;

/**
 * WatchRemoteFragment — Page 2 of the watch ViewPager.
 * Shows current local-streaming state (on/off) and lets the user toggle it.
 * Streaming only works while recording is active; this toggle simply starts/stops
 * the RemoteStreamService (the same way RemoteFragment does on phone).
 */
public class WatchRemoteFragment extends Fragment {

    private static final String TAG = "WatchRemoteFragment";

    private AvatarToggleView remoteToggle;
    private TextView tvStatus;
    private TextView tvUrl;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_watch_remote, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        remoteToggle = view.findViewById(R.id.watch_remote_toggle);
        tvStatus     = view.findViewById(R.id.tv_watch_remote_status);
        tvUrl        = view.findViewById(R.id.tv_watch_remote_url);

        // Apply initial state
        refreshUi();

        // Toggle streaming on tap
        if (remoteToggle != null) {
            remoteToggle.setOnClickListener(v -> toggleStreaming());
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshUi();
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /** Reads current RemoteStreamManager state and updates UI accordingly. */
    private void refreshUi() {
        boolean streaming = RemoteStreamManager.getInstance().isStreamingEnabled();

        if (remoteToggle != null) {
            remoteToggle.setChecked(streaming);
        }

        if (tvStatus != null) {
            tvStatus.setText(streaming
                    ? R.string.watch_remote_stream_on
                    : R.string.watch_remote_stream_off);
            tvStatus.setTextColor(streaming ? 0xFFFF5252 : 0xFFAAAAAA);
        }

        if (tvUrl != null) {
            // URL is only meaningful while the service is active
            String url = streaming ? buildLocalUrl() : "";
            tvUrl.setText(url);
        }
    }

    /** Toggles the streaming service on or off. */
    private void toggleStreaming() {
        Context ctx = requireContext();
        boolean currently = RemoteStreamManager.getInstance().isStreamingEnabled();
        try {
            if (currently) {
                // Stop
                ctx.stopService(new Intent(ctx, RemoteStreamService.class));
            } else {
                // Start
                Intent intent = new Intent(ctx, RemoteStreamService.class);
                ctx.startService(intent);
            }
        } catch (Exception e) {
            FLog.e(TAG, "Toggle streaming failed", e);
        }

        // Refresh after slight delay to allow service to update state
        if (remoteToggle != null) {
            remoteToggle.postDelayed(this::refreshUi, 500);
        }
    }

    /**
     * Returns the real local HLS URL using the device's Wi-Fi address and the
     * actual streaming port saved by RemoteStreamService in SharedPreferences.
     */
    private String buildLocalUrl() {
        try {
            // Get the Wi-Fi IP (works on watch + phone, doesn't return 127.0.0.1)
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    requireContext().getApplicationContext()
                            .getSystemService(Context.WIFI_SERVICE);
            int rawIp = wm.getConnectionInfo().getIpAddress();
            String ipStr = String.format(java.util.Locale.US, "%d.%d.%d.%d",
                    (rawIp & 0xff),
                    (rawIp >> 8  & 0xff),
                    (rawIp >> 16 & 0xff),
                    (rawIp >> 24 & 0xff));

            // Use the port saved by RemoteStreamService (default 8080)
            int port = com.fadcam.SharedPreferencesManager
                    .getInstance(requireContext())
                    .sharedPreferences
                    .getInt("stream_server_port", 8080);

            return "http://" + ipStr + ":" + port + "/";
        } catch (Exception e) {
            return "http://[device-ip]:8080/";
        }
    }
}
