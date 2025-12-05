package com.fadcam.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.streaming.RemoteStreamManager;
import com.fadcam.streaming.RemoteStreamService;
import com.google.android.material.materialswitch.MaterialSwitch;

public class RemoteFragment extends BaseFragment {
    private static final String TAG = "RemoteFragment";
    
    private MaterialSwitch streamingToggle;
    private TextView statusText;
    private TextView streamUrlText;
    private TextView modeText;
    
    private RemoteStreamService streamService;
    private boolean serviceBound = false;
    private Handler statusUpdateHandler;
    private Runnable statusUpdateRunnable;
    
    private SharedPreferencesManager prefsManager;
    
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            RemoteStreamService.LocalBinder binder = (RemoteStreamService.LocalBinder) service;
            streamService = binder.getService();
            serviceBound = true;
            updateUI();
            Log.d(TAG, "Service connected");
        }
        
        @Override
        public void onServiceDisconnected(ComponentName name) {
            streamService = null;
            serviceBound = false;
            Log.d(TAG, "Service disconnected");
        }
    };
    
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_remote, container, false);
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        prefsManager = SharedPreferencesManager.getInstance(requireContext());
        
        // Setup header bar
        LinearLayout headerBar = view.findViewById(R.id.header_bar);
        if (headerBar != null) {
            int colorTopBar = resolveThemeColor(R.attr.colorTopBar);
            headerBar.setBackgroundColor(colorTopBar);
        }
        
        // Initialize views
        streamingToggle = view.findViewById(R.id.streaming_toggle);
        statusText = view.findViewById(R.id.status_text);
        streamUrlText = view.findViewById(R.id.stream_url_text);
        modeText = view.findViewById(R.id.mode_text);
        
        // Set mode text (default: Stream & Save)
        RemoteStreamManager.StreamingMode mode = prefsManager.getStreamingMode();
        modeText.setText(mode == RemoteStreamManager.StreamingMode.STREAM_ONLY ? 
            "Mode: Stream Only" : "Mode: Stream & Save");
        
        // Setup toggle listener
        streamingToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                startStreaming();
            } else {
                stopStreaming();
            }
        });
        
        // Mode toggle button
        view.findViewById(R.id.mode_toggle_button).setOnClickListener(v -> toggleStreamingMode());
        
        // Start status updates
        statusUpdateHandler = new Handler(Looper.getMainLooper());
        statusUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                updateUI();
                statusUpdateHandler.postDelayed(this, 2000); // Update every 2 seconds
            }
        };
    }
    
    @Override
    public void onResume() {
        super.onResume();
        
        // Check if service is running before binding
        boolean serviceRunning = RemoteStreamManager.getInstance().isStreamingEnabled();
        if (serviceRunning) {
            Intent intent = new Intent(requireContext(), RemoteStreamService.class);
            requireContext().bindService(intent, serviceConnection, 0);
        }
        
        // Start status updates
        if (statusUpdateHandler != null && statusUpdateRunnable != null) {
            statusUpdateHandler.post(statusUpdateRunnable);
        }
        
        updateUI();
    }
    
    @Override
    public void onPause() {
        super.onPause();
        
        // Unbind from service
        if (serviceBound) {
            requireContext().unbindService(serviceConnection);
            serviceBound = false;
        }
        
        // Stop status updates
        if (statusUpdateHandler != null && statusUpdateRunnable != null) {
            statusUpdateHandler.removeCallbacks(statusUpdateRunnable);
        }
    }
    
    private void startStreaming() {
        Log.i(TAG, "Starting streaming");
        
        // Set streaming mode from preferences
        RemoteStreamManager.StreamingMode mode = prefsManager.getStreamingMode();
        RemoteStreamManager.getInstance().setStreamingMode(mode);
        
        // Start service
        Intent intent = new Intent(requireContext(), RemoteStreamService.class);
        requireContext().startForegroundService(intent);
        
        // Bind to service
        requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
        
        Toast.makeText(requireContext(), "Stream server starting...", Toast.LENGTH_SHORT).show();
    }
    
    private void stopStreaming() {
        Log.i(TAG, "Stopping streaming");
        
        // Unbind from service first
        if (streamService != null) {
            try {
                requireContext().unbindService(serviceConnection);
            } catch (Exception e) {
                Log.e(TAG, "Error unbinding service", e);
            }
            streamService = null;
        }
        
        // Stop service
        Intent intent = new Intent(requireContext(), RemoteStreamService.class);
        requireContext().stopService(intent);
        
        // Disable streaming in manager
        RemoteStreamManager.getInstance().setStreamingEnabled(false);
        
        Toast.makeText(requireContext(), "Stream server stopped", Toast.LENGTH_SHORT).show();
        
        updateUI();
    }
    
    private void toggleStreamingMode() {
        RemoteStreamManager.StreamingMode currentMode = prefsManager.getStreamingMode();
        RemoteStreamManager.StreamingMode newMode = currentMode == RemoteStreamManager.StreamingMode.STREAM_ONLY ?
            RemoteStreamManager.StreamingMode.STREAM_AND_SAVE : RemoteStreamManager.StreamingMode.STREAM_ONLY;
        
        prefsManager.setStreamingMode(newMode);
        RemoteStreamManager.getInstance().setStreamingMode(newMode);
        
        modeText.setText(newMode == RemoteStreamManager.StreamingMode.STREAM_ONLY ? 
            "Mode: Stream Only" : "Mode: Stream & Save");
        
        Toast.makeText(requireContext(), 
            newMode == RemoteStreamManager.StreamingMode.STREAM_ONLY ? 
                "Stream Only (no save)" : "Stream & Save to gallery", 
            Toast.LENGTH_SHORT).show();
    }
    
    private void updateUI() {
        // Temporarily remove listener to prevent triggering stopStreaming
        streamingToggle.setOnCheckedChangeListener(null);
        
        if (streamService != null && serviceBound && streamService.isServerRunning()) {
            streamingToggle.setChecked(true);
            String url = streamService.getStreamUrl();
            if (url != null) {
                statusText.setText("Status: Active");
                streamUrlText.setText(url);
                streamUrlText.setVisibility(View.VISIBLE);
            }
        } else {
            streamingToggle.setChecked(false);
            statusText.setText("Status: Inactive");
            streamUrlText.setVisibility(View.GONE);
        }
        
        // Restore listener
        streamingToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                startStreaming();
            } else {
                stopStreaming();
            }
        });
    }
    
    private int resolveThemeColor(int attr) {
        android.util.TypedValue typedValue = new android.util.TypedValue();
        requireContext().getTheme().resolveAttribute(attr, typedValue, true);
        return typedValue.data;
    }
}
