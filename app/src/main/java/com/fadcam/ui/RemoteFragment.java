package com.fadcam.ui;

import android.animation.ObjectAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
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
import android.view.animation.AlphaAnimation;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.Constants;
import com.fadcam.MainActivity;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.streaming.RemoteStreamManager;
import com.fadcam.streaming.RemoteStreamService;
import com.fadcam.streaming.model.StreamQuality;
import com.fadcam.ui.bottomsheet.BatteryInfoBottomSheet;
import com.fadcam.ui.bottomsheet.QualityPresetBottomSheet;
import com.fadcam.ui.bottomsheet.UptimeInfoBottomSheet;
import com.fadcam.ui.utils.NewFeatureManager;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.util.List;

public class RemoteFragment extends BaseFragment {
    private static final String TAG = "RemoteFragment";
    
    private Switch streamingToggle;
    private TextView statusText;
    private TextView rootUrlText;
    private ImageView copyRootButton;
    private View statusIndicatorDot;
    private View statusGlow;
    private FrameLayout glowContainer;
    private LinearLayout rootUrlContainer;
    private LinearLayout recordingModeRow;
    private LinearLayout viewEndpointsRow;
    private LinearLayout clientsRow;
    private LinearLayout fpsFormatRow;
    private LinearLayout uptimeRow;
    private LinearLayout batteryRow;
    private TextView recordingModeValue;
    private TextView uptimeText;
    private TextView connectionsText;
    private TextView batteryText;
    private TextView fragmentsText;
    private TextView fpsResolutionText;
    private TextView networkHealthText;
    private TextView memoryText;
    private TextView storageText;
    private TextView dataTransferredText;
    private LinearLayout dataSentRow;
    private LinearLayout segmentsRow;
    private LinearLayout networkHealthRow;
    
    private RemoteStreamService streamService;
    private boolean serviceBound = false;
    private Handler statusUpdateHandler;
    private Runnable statusUpdateRunnable;
    private Handler healthUpdateHandler;
    private Runnable healthUpdateRunnable;
    
    private SharedPreferencesManager prefsManager;
    private long streamStartTime = 0;
    
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            RemoteStreamService.LocalBinder binder = (RemoteStreamService.LocalBinder) service;
            streamService = binder.getService();
            serviceBound = true;
            streamStartTime = System.currentTimeMillis();
            updateUI();
            Log.d(TAG, "Service connected");
        }
        
        @Override
        public void onServiceDisconnected(ComponentName name) {
            streamService = null;
            serviceBound = false;
            streamStartTime = 0;
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
        
        // Initialize views
        streamingToggle = view.findViewById(R.id.streaming_toggle);
        statusText = view.findViewById(R.id.status_text);
        rootUrlText = view.findViewById(R.id.root_url_text);
        copyRootButton = view.findViewById(R.id.copy_root_button);
        statusIndicatorDot = view.findViewById(R.id.status_indicator_dot);
        statusGlow = view.findViewById(R.id.status_glow);
        glowContainer = view.findViewById(R.id.glow_container);
        rootUrlContainer = view.findViewById(R.id.root_url_container);
        recordingModeRow = view.findViewById(R.id.recording_mode_row);
        recordingModeValue = view.findViewById(R.id.recording_mode_value);
        viewEndpointsRow = view.findViewById(R.id.view_endpoints_row);
        clientsRow = view.findViewById(R.id.clients_row);
        fpsFormatRow = view.findViewById(R.id.fps_format_row);
        uptimeRow = view.findViewById(R.id.uptime_row);
        batteryRow = view.findViewById(R.id.battery_row);
        uptimeText = view.findViewById(R.id.uptime_text);
        connectionsText = view.findViewById(R.id.connections_text);
        batteryText = view.findViewById(R.id.battery_text);
        fragmentsText = view.findViewById(R.id.fragments_text);
        fpsResolutionText = view.findViewById(R.id.fps_resolution_text);
        networkHealthText = view.findViewById(R.id.network_health_text);
        memoryText = view.findViewById(R.id.memory_text);
        storageText = view.findViewById(R.id.storage_text);
        dataTransferredText = view.findViewById(R.id.data_transferred_text);
        dataSentRow = view.findViewById(R.id.data_sent_row);
        segmentsRow = view.findViewById(R.id.segments_row);
        networkHealthRow = view.findViewById(R.id.network_health_row);
        
        // Set context for status reporting
        RemoteStreamManager.getInstance().setContext(requireContext());
        
        // Update FPS and resolution display
        updateRequirementsDisplay();
        
        // Setup toggle listener
        streamingToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                startStreaming();
            } else {
                stopStreaming();
            }
        });
        
        // Recording mode row click listener
        recordingModeRow.setOnClickListener(v -> showRecordingModePicker());
        
        // View endpoints row click listener
        viewEndpointsRow.setOnClickListener(v -> showEndpointsPicker());
        
        // Clients row click listener
        clientsRow.setOnClickListener(v -> showClientDetailsPicker());
        
        // Stream Quality row click listener (bitrate + FPS cap)
        fpsFormatRow.setOnClickListener(v -> showQualityPresetPicker());
        
        // Uptime row click listener
        uptimeRow.setOnClickListener(v -> showUptimeInfo());
        
        // Battery row click listener
        batteryRow.setOnClickListener(v -> showBatteryInfo());
        
        // Data sent row click listener
        dataSentRow.setOnClickListener(v -> showClientDataUsage());
        
        // Segments row click listener
        segmentsRow.setOnClickListener(v -> showSegmentsInfo());
        
        // Network health row click listener
        networkHealthRow.setOnClickListener(v -> showNetworkHealth());
        
        // Copy URL button listener
        copyRootButton.setOnClickListener(v -> {
            String rootUrl = rootUrlText.getText().toString();
            if (!rootUrl.contains("---")) {
                copyToClipboard(rootUrl);
                Toast.makeText(requireContext(), R.string.stream_url_copied, Toast.LENGTH_SHORT).show();
            }
        });
        
        // Update initial mode display
        updateModeDisplay();
        
        // Start status updates
        statusUpdateHandler = new Handler(Looper.getMainLooper());
        statusUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                updateUI();
                updateModeDisplay(); // ---------- Fix: Refresh mode display every update cycle like quality
                statusUpdateHandler.postDelayed(this, 2000);
            }
        };
        
        // Health update handler
        healthUpdateHandler = new Handler(Looper.getMainLooper());
        healthUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                updateServerHealth();
                healthUpdateHandler.postDelayed(this, 1000);
            }
        };
    }
    
    @Override
    public void onResume() {
        super.onResume();
        
        // Set bottom nav AND status bar to black when remote tab is visible
        if (getActivity() instanceof MainActivity) {
            MainActivity mainActivity = (MainActivity) getActivity();
            mainActivity.setBottomNavColor(0xFF000000); // Pure black
            mainActivity.setStatusBarColor(0xFF000000); // Pure black
        }
        
        boolean serviceRunning = RemoteStreamManager.getInstance().isStreamingEnabled();
        if (serviceRunning) {
            Intent intent = new Intent(requireContext(), RemoteStreamService.class);
            requireContext().bindService(intent, serviceConnection, 0);
            streamStartTime = System.currentTimeMillis();
        }
        
        if (statusUpdateHandler != null && statusUpdateRunnable != null) {
            statusUpdateHandler.post(statusUpdateRunnable);
        }
        
        if (healthUpdateHandler != null && healthUpdateRunnable != null) {
            healthUpdateHandler.post(healthUpdateRunnable);
        }
        
        updateUI();
        updateModeDisplay(); // Refresh mode display in case web client changed it
    }
    
    @Override
    public void onPause() {
        super.onPause();
        
        // Mark remote feature as seen when user leaves the Remote tab
        // (This ensures badge is visible until user explicitly views it)
        Log.d(TAG, "onPause: Marking remote feature as seen");
        NewFeatureManager.markFeatureAsSeen(requireContext(), "remote");
        
        // Refresh badge UI immediately to show the change
        if (getActivity() instanceof MainActivity) {
            MainActivity mainActivity = (MainActivity) getActivity();
            mainActivity.refreshFeatureBadges(); // Update badge UI immediately
            mainActivity.setBottomNavColor(0); // Restore original
            mainActivity.setStatusBarColor(0); // Restore original from theme
        }
        
        if (serviceBound) {
            try {
                requireContext().unbindService(serviceConnection);
            } catch (Exception e) {
                Log.e(TAG, "Error unbinding service", e);
            }
            serviceBound = false;
        }
        
        if (statusUpdateHandler != null && statusUpdateRunnable != null) {
            statusUpdateHandler.removeCallbacks(statusUpdateRunnable);
        }
        
        if (healthUpdateHandler != null && healthUpdateRunnable != null) {
            healthUpdateHandler.removeCallbacks(healthUpdateRunnable);
        }
    }
    
    private void startStreaming() {
        Log.i(TAG, "Starting streaming");
        
        RemoteStreamManager.StreamingMode mode = prefsManager.getStreamingMode();
        RemoteStreamManager.getInstance().setStreamingMode(mode);
        
        Intent intent = new Intent(requireContext(), RemoteStreamService.class);
        requireContext().startForegroundService(intent);
        requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
        
        streamStartTime = System.currentTimeMillis();
        Toast.makeText(requireContext(), "Stream server starting...", Toast.LENGTH_SHORT).show();
    }
    
    private void stopStreaming() {
        Log.i(TAG, "Stopping streaming");
        
        if (streamService != null) {
            try {
                requireContext().unbindService(serviceConnection);
            } catch (Exception e) {
                Log.e(TAG, "Error unbinding service", e);
            }
            streamService = null;
        }
        
        Intent intent = new Intent(requireContext(), RemoteStreamService.class);
        requireContext().stopService(intent);
        RemoteStreamManager.getInstance().setStreamingEnabled(false);
        
        streamStartTime = 0;
        Toast.makeText(requireContext(), "Stream server stopped", Toast.LENGTH_SHORT).show();
        updateUI();
    }
    
    private void switchStreamingMode(RemoteStreamManager.StreamingMode newMode) {
        prefsManager.setStreamingMode(newMode);
        RemoteStreamManager.getInstance().setStreamingMode(newMode);
        updateModeDisplay();
        
        Toast.makeText(requireContext(), 
            newMode == RemoteStreamManager.StreamingMode.STREAM_ONLY ? 
                "Stream Only mode" : "Stream & Save mode", 
            Toast.LENGTH_SHORT).show();
    }
    
    private void updateModeDisplay() {
        RemoteStreamManager.StreamingMode mode = prefsManager.getStreamingMode();
        String modeText = mode == RemoteStreamManager.StreamingMode.STREAM_ONLY ? 
            "Stream Only" : "Stream & Save";
        recordingModeValue.setText(modeText);
    }
    
    private void updateUI() {
        streamingToggle.setOnCheckedChangeListener(null);
        
        boolean isStreaming = streamService != null && serviceBound && streamService.isServerRunning();
        
        if (isStreaming) {
            streamingToggle.setChecked(true);
            statusText.setText(R.string.stream_active);
            statusText.setTextColor(0xFF4CAF50); // Green for active
            animateStatusIndicator(true);
            
            // Get base server URL (without endpoint path)
            String serverUrlWithPort = streamService.getDeviceIpWithPort();
            if (serverUrlWithPort != null) {
                String rootUrl = "http://" + serverUrlWithPort + "/";
                rootUrlText.setText(rootUrl);
            }
        } else {
            streamingToggle.setChecked(false);
            statusText.setText(R.string.stream_inactive);
            statusText.setTextColor(0xFFFF5252); // Red for inactive
            animateStatusIndicator(false);
            
            // Show placeholder URL when inactive
            rootUrlText.setText("http://---.---.---.---:----/");
        }
        
        // Update quality display (FPS cap + bitrate)
        updateRequirementsDisplay();
        
        streamingToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                startStreaming();
            } else {
                stopStreaming();
            }
        });
    }
    
    private void animateStatusIndicator(boolean isActive) {
        if (isActive) {
            statusIndicatorDot.setBackground(requireContext().getDrawable(R.drawable.status_dot_pulse));
            statusGlow.setVisibility(View.VISIBLE);
            
            // Smooth constant pulse animation
            statusGlow.setAlpha(0.7f);
            statusGlow.setScaleX(1f);
            statusGlow.setScaleY(1f);
            
            // Single smooth scale and fade animation
            ObjectAnimator scaleX = ObjectAnimator.ofFloat(statusGlow, "scaleX", 1f, 1.3f);
            ObjectAnimator scaleY = ObjectAnimator.ofFloat(statusGlow, "scaleY", 1f, 1.3f);
            ObjectAnimator alpha = ObjectAnimator.ofFloat(statusGlow, "alpha", 0.7f, 0f);
            
            scaleX.setDuration(2000);
            scaleY.setDuration(2000);
            alpha.setDuration(2000);
            
            // Use AccelerateDecelerateInterpolator for smooth motion
            android.view.animation.AccelerateDecelerateInterpolator interpolator = 
                new android.view.animation.AccelerateDecelerateInterpolator();
            scaleX.setInterpolator(interpolator);
            scaleY.setInterpolator(interpolator);
            alpha.setInterpolator(interpolator);
            
            // Infinite repeat
            scaleX.setRepeatCount(ObjectAnimator.INFINITE);
            scaleY.setRepeatCount(ObjectAnimator.INFINITE);
            alpha.setRepeatCount(ObjectAnimator.INFINITE);
            scaleX.setRepeatMode(ObjectAnimator.RESTART);
            scaleY.setRepeatMode(ObjectAnimator.RESTART);
            alpha.setRepeatMode(ObjectAnimator.RESTART);
            
            scaleX.start();
            scaleY.start();
            alpha.start();
        } else {
            statusIndicatorDot.setBackground(requireContext().getDrawable(R.drawable.status_dot_inactive));
            statusGlow.setVisibility(View.GONE);
            statusGlow.clearAnimation();
        }
    }
    
    private void updateServerHealth() {
        RemoteStreamManager manager = RemoteStreamManager.getInstance();
        
        // Get persistent uptime from manager
        long uptimeMs = manager.getServerUptimeMs();
        String uptime = formatUptime(uptimeMs);
        uptimeText.setText(uptime);
        
        // Get active connections count
        int connections = manager.getActiveConnections();
        connectionsText.setText(String.valueOf(connections));
        
        // Get battery percentage
        int battery = manager.getBatteryPercentage(requireContext());
        String batteryDisplay = battery > 0 ? battery + "%" : "--";
        
        // Check if battery warning should be shown
        String batteryDetailsJson = manager.getBatteryDetailsJson(requireContext());
        try {
            org.json.JSONObject batteryData = new org.json.JSONObject(batteryDetailsJson);
            String warning = batteryData.getString("warning");
            if (warning != null && !warning.isEmpty()) {
                batteryDisplay = "⚠️ " + batteryDisplay;
            }
        } catch (Exception e) {
            // Ignore, just show battery percentage
        }
        
        batteryText.setText(batteryDisplay);
        
        // Get buffered fragments count
        int fragments = manager.getBufferedCount();
        fragmentsText.setText(String.valueOf(fragments));
        
        // Update system metrics with NetworkHealth model
        com.fadcam.streaming.model.NetworkHealth networkHealth = manager.getNetworkHealth();
        networkHealthText.setText(capitalize(networkHealth.getStatusString()));
        networkHealthText.setTextColor(networkHealth.getStatusColorInt());
        
        String memoryUsage = manager.getMemoryUsage(requireContext());
        memoryText.setText(memoryUsage);
        
        String storageInfo = manager.getStorageInfo();
        storageText.setText(storageInfo);
        
        long dataMb = manager.getTotalDataTransferred() / (1024 * 1024);
        dataTransferredText.setText(String.format("%d MB", dataMb));
    }
    
    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }
    
    private String formatUptime(long ms) {
        long seconds = ms / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        
        if (hours > 0) {
            return String.format("%dh %dm", hours, minutes % 60);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds % 60);
        } else {
            return String.format("%ds", seconds);
        }
    }
    
    private void updateRequirementsDisplay() {
        // Display current stream quality preset
        // NOTE: Resolution and orientation now use normal recording settings
        
        // Read quality preset from SharedPreferences to get real-time updates from web changes
        android.content.SharedPreferences prefs = requireContext().getSharedPreferences("FadCamPrefs", android.content.Context.MODE_PRIVATE);
        String presetName = prefs.getString("quality_preset", "HIGH");
        StreamQuality.Preset preset = StreamQuality.Preset.valueOf(presetName);
        
        // Format: "High • 30fps cap • 5 Mbps"
        // (Resolution comes from normal recording settings)
        String displayText = String.format("%s • %dfps cap • %s",
            preset.getDisplayName(),
            preset.getFps(),
            preset.getBitrateString()
        );
        
        fpsResolutionText.setText(displayText);
    }
    
    private void copyToClipboard(String text) {
        ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("Stream", text);
        clipboard.setPrimaryClip(clip);
    }
    
    private void showClientDetailsPicker() {
        RemoteStreamManager manager = RemoteStreamManager.getInstance();
        List<String> clientIPs = manager.getConnectedClientIPs();
        
        if (clientIPs.isEmpty()) {
            Toast.makeText(requireContext(), "No clients connected", Toast.LENGTH_SHORT).show();
            return;
        }
        
        ClientDetailsBottomSheetFragment sheet = ClientDetailsBottomSheetFragment.newInstance(clientIPs);
        sheet.show(getParentFragmentManager(), "client_details");
    }
    
    private void showRecordingModePicker() {
        java.util.ArrayList<com.fadcam.ui.picker.OptionItem> items = new java.util.ArrayList<>();
        items.add(new com.fadcam.ui.picker.OptionItem(
            "stream_only", "Stream Only", "Stream without saving to storage", null));
        items.add(new com.fadcam.ui.picker.OptionItem(
            "stream_and_save", "Stream & Save", "Stream and save recording to storage", null));
        
        RemoteStreamManager.StreamingMode currentMode = prefsManager.getStreamingMode();
        String selectedId = currentMode == RemoteStreamManager.StreamingMode.STREAM_ONLY ? 
            "stream_only" : "stream_and_save";
        
        com.fadcam.ui.picker.PickerBottomSheetFragment picker = 
            com.fadcam.ui.picker.PickerBottomSheetFragment.newInstance(
                "Recording Mode", items, selectedId, "recording_mode_picker");
        
        getParentFragmentManager().setFragmentResultListener("recording_mode_picker", 
            getViewLifecycleOwner(), (requestKey, result) -> {
                String newSelectedId = result.getString("selected_id");
                if (newSelectedId != null) {
                    RemoteStreamManager.StreamingMode newMode = 
                        "stream_only".equals(newSelectedId) ? 
                        RemoteStreamManager.StreamingMode.STREAM_ONLY : 
                        RemoteStreamManager.StreamingMode.STREAM_AND_SAVE;
                    switchStreamingMode(newMode);
                }
            });
        
        picker.show(getParentFragmentManager(), "recording_mode_picker");
    }
    
    private void showEndpointsPicker() {
        String serverUrl = "";
        if (streamService != null && serviceBound && streamService.isServerRunning()) {
            String serverUrlWithPort = streamService.getDeviceIpWithPort();
            if (serverUrlWithPort != null) {
                serverUrl = "http://" + serverUrlWithPort;
            }
        }
        
        EndpointsBottomSheetFragment endpointsSheet = EndpointsBottomSheetFragment.newInstance(serverUrl);
        endpointsSheet.show(getParentFragmentManager(), "endpoints_sheet");
    }
    
    private void showQualityPresetPicker() {
        RemoteStreamManager manager = RemoteStreamManager.getInstance();
        StreamQuality quality = manager.getStreamQuality();
        StreamQuality.Preset currentPreset = quality.getCurrentPreset();
        
        QualityPresetBottomSheet sheet = QualityPresetBottomSheet.newInstance(currentPreset);
        sheet.setOnQualitySelectedListener(preset -> {
            // Quality will be updated in RemoteStreamManager by the bottom sheet
            // Update UI to reflect new quality immediately
            updateRequirementsDisplay();
            updateUI(); // Refresh all UI elements
        });
        sheet.show(getParentFragmentManager(), "quality_preset_sheet");
    }
    
    private void showBatteryInfo() {
        BatteryInfoBottomSheet sheet = new BatteryInfoBottomSheet();
        sheet.show(getParentFragmentManager(), "battery_info_sheet");
    }
    
    private void showUptimeInfo() {
        UptimeInfoBottomSheet sheet = new UptimeInfoBottomSheet();
        sheet.show(getParentFragmentManager(), "uptime_info_sheet");
    }
    
    private void showClientDataUsage() {
        ClientDataBottomSheet sheet = new ClientDataBottomSheet();
        sheet.show(getParentFragmentManager(), "client_data_sheet");
    }
    
    private void showSegmentsInfo() {
        SegmentsInfoBottomSheet sheet = new SegmentsInfoBottomSheet();
        sheet.show(getParentFragmentManager(), "segments_info_sheet");
    }
    
    private void showNetworkHealth() {
        NetworkHealthBottomSheet sheet = new NetworkHealthBottomSheet();
        sheet.show(getParentFragmentManager(), "network_health_sheet");
    }
    
    private int resolveThemeColor(int attr) {
        android.util.TypedValue typedValue = new android.util.TypedValue();
        requireContext().getTheme().resolveAttribute(attr, typedValue, true);
        return typedValue.data;
    }
}

