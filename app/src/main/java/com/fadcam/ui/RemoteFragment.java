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

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import com.fadcam.Constants;
import com.fadcam.MainActivity;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.streaming.CloudAuthManager;
import com.fadcam.streaming.CloudStatusManager;
import com.fadcam.streaming.CloudStreamUploader;
import com.fadcam.streaming.RemoteAuthManager;
import com.fadcam.streaming.RemoteStreamManager;
import com.fadcam.streaming.RemoteStreamService;
import com.fadcam.streaming.model.StreamQuality;
import com.fadcam.ui.bottomsheet.BatteryInfoBottomSheet;
import com.fadcam.ui.bottomsheet.QualityPresetBottomSheet;
import com.fadcam.ui.bottomsheet.UptimeInfoBottomSheet;
import com.fadcam.ui.picker.OptionItem;
import com.fadcam.ui.picker.PickerBottomSheetFragment;
import com.fadcam.ui.utils.NewFeatureManager;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.util.ArrayList;
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
    
    // Remote Security UI
    private Switch remoteAuthToggle;
    private LinearLayout remoteAuthPasswordRow;
    private TextView remoteAuthPasswordValue;
    private LinearLayout remoteAuthAutoLockRow;
    private TextView remoteAuthAutoLockValue;
    private LinearLayout remoteAuthLogoutAllRow;
    
    // Cloud Account UI
    private View cloudAccountButton;
    private ImageView cloudIcon;
    private TextView cloudStatusText;
    private View cloudUnlinkedDot;
    private CloudAuthManager cloudAuthManager;
    private ActivityResultLauncher<Intent> cloudAccountLauncher;
    
    // Cloud Streaming UI
    private LinearLayout cloudStreamingRow;
    private Switch cloudStreamingToggle;
    private TextView cloudStreamingStatus;
    
    // Streaming Mode Selector UI
    private LinearLayout streamingModeRow;
    private TextView streamingModeValue;
    
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
    
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Initialize CloudAuthManager
        cloudAuthManager = CloudAuthManager.getInstance(requireContext());
        
        // Register activity result launcher for cloud account linking
        cloudAccountLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == android.app.Activity.RESULT_OK) {
                    Intent data = result.getData();
                    if (data != null && data.getBooleanExtra("linked", false)) {
                        String email = data.getStringExtra("email");
                        Log.i(TAG, "Device linked to: " + email);
                        Toast.makeText(requireContext(), R.string.cloud_account_link_success, Toast.LENGTH_SHORT).show();
                        updateCloudButtonState();
                    }
                }
            }
        );
    }
    
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
        
        // Initialize Remote Security views
        remoteAuthToggle = view.findViewById(R.id.remote_auth_toggle);
        remoteAuthPasswordRow = view.findViewById(R.id.remote_auth_password_row);
        remoteAuthPasswordValue = view.findViewById(R.id.remote_auth_password_value);
        remoteAuthAutoLockRow = view.findViewById(R.id.remote_auth_auto_lock_row);
        remoteAuthAutoLockValue = view.findViewById(R.id.remote_auth_auto_lock_value);
        remoteAuthLogoutAllRow = view.findViewById(R.id.remote_auth_logout_all_row);
        
        // Initialize Cloud Account button
        cloudAccountButton = view.findViewById(R.id.cloud_account_button);
        cloudIcon = view.findViewById(R.id.cloud_icon);
        cloudStatusText = view.findViewById(R.id.cloud_label_status);
        cloudUnlinkedDot = view.findViewById(R.id.cloud_unlinked_dot);
        if (cloudAccountButton != null) {
            cloudAccountButton.setOnClickListener(v -> onCloudAccountClick());
            updateCloudButtonState();
        }
        
        // Initialize Cloud Streaming toggle (legacy, kept for compatibility)
        cloudStreamingRow = view.findViewById(R.id.cloud_streaming_row);
        cloudStreamingToggle = view.findViewById(R.id.cloud_streaming_toggle);
        cloudStreamingStatus = view.findViewById(R.id.cloud_streaming_status);
        
        // Initialize Streaming Mode Selector
        streamingModeRow = view.findViewById(R.id.streaming_mode_row);
        streamingModeValue = view.findViewById(R.id.streaming_mode_value);
        
        setupStreamingModeSelector();
        setupCloudStreaming();
        
        // Set context for status reporting
        RemoteStreamManager.getInstance().setContext(requireContext());
        
        // Initialize RemoteAuthManager
        RemoteAuthManager.getInstance(requireContext());
        
        // Setup Remote Security
        setupRemoteSecurity();
        
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
            getString(R.string.stream_only) : getString(R.string.stream_and_save);
        String descText = mode == RemoteStreamManager.StreamingMode.STREAM_ONLY ?
            getString(R.string.stream_only_desc) : getString(R.string.stream_and_save_desc);
        recordingModeValue.setText(descText);
    }
    
    private void updateUI() {
        streamingToggle.setOnCheckedChangeListener(null);
        
        boolean isStreaming = streamService != null && serviceBound && streamService.isServerRunning();
        
        if (isStreaming) {
                    streamingToggle.setChecked(true);
            statusText.setText(R.string.stream_active);
            statusText.setTextColor(0xFF4CAF50); // Green for active
            animateStatusIndicator(true);
            
            // Show URL based on streaming mode
            android.content.SharedPreferences prefs = requireContext().getSharedPreferences("FadCamCloudPrefs", Context.MODE_PRIVATE);
            int streamingMode = prefs.getInt(KEY_STREAMING_MODE, MODE_LOCAL);
            
            if (streamingMode == MODE_CLOUD && cloudAuthManager.isLinked()) {
                // Cloud mode - show dashboard URL
                String deviceId = cloudAuthManager.getDeviceId();
                if (deviceId != null) {
                    String cloudUrl = "https://fadcam.fadseclab.com/stream/" + deviceId + "/";
                    rootUrlText.setText(cloudUrl);
                } else {
                    rootUrlText.setText("https://fadcam.fadseclab.com/...");
                }
            } else {
                // Local mode - show local IP
                String serverUrlWithPort = streamService.getDeviceIpWithPort();
                if (serverUrlWithPort != null) {
                    String rootUrl = "http://" + serverUrlWithPort + "/";
                    rootUrlText.setText(rootUrl);
                }
            }
        } else {
            streamingToggle.setChecked(false);
            statusText.setText(R.string.stream_inactive);
            statusText.setTextColor(0xFFFF5252); // Red for inactive
            animateStatusIndicator(false);
            
            // Show placeholder URL based on streaming mode
            android.content.SharedPreferences prefs = requireContext().getSharedPreferences("FadCamCloudPrefs", Context.MODE_PRIVATE);
            int streamingMode = prefs.getInt(KEY_STREAMING_MODE, MODE_LOCAL);
            
            if (streamingMode == MODE_CLOUD) {
                rootUrlText.setText("https://fadcam.fadseclab.com/...");
            } else {
                rootUrlText.setText("http://---.---.---.---:----/");
            }
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
        
        // Get active connections count (local + cloud viewers)
        int localConnections = manager.getActiveConnections();
        int cloudConnections = manager.getCloudViewerCount();
        int totalConnections = localConnections + cloudConnections;
        
        // Display with breakdown if cloud viewers present
        if (cloudConnections > 0 && localConnections > 0) {
            // Both local and cloud: "3 (1 local, 2 cloud)"
            connectionsText.setText(String.format("%d (%d local, %d cloud)", 
                totalConnections, localConnections, cloudConnections));
        } else if (cloudConnections > 0) {
            // Cloud only: "2 (cloud)"
            connectionsText.setText(String.format("%d (cloud)", cloudConnections));
        } else {
            // Local only or zero
            connectionsText.setText(String.valueOf(localConnections));
        }
        
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
    
    /**
     * Setup Remote Security UI and event listeners
     */
    private void setupRemoteSecurity() {
        RemoteAuthManager authManager = RemoteAuthManager.getInstance(requireContext());
        
        // Load initial state
        boolean authEnabled = authManager.isAuthEnabled();
        remoteAuthToggle.setChecked(authEnabled);
        updateSecurityRowsVisibility(authEnabled);
        
        // Auth toggle listener
        remoteAuthToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            RemoteAuthManager.getInstance(requireContext()).setAuthEnabled(isChecked);
            updateSecurityRowsVisibility(isChecked);
            
            if (isChecked && !authManager.hasPassword()) {
                // First time enabling - prompt for password
                showSetPasswordSheet();
            }
        });
        
        // Set Password row
        remoteAuthPasswordRow.setOnClickListener(v -> {
            if (!authManager.isAuthEnabled()) {
                Toast.makeText(requireContext(), R.string.remote_security_enable_first, Toast.LENGTH_SHORT).show();
                return;
            }
            showSetPasswordSheet();
        });
        
        // Auto-Lock Timeout row
        remoteAuthAutoLockRow.setOnClickListener(v -> {
            if (!authManager.isAuthEnabled()) {
                Toast.makeText(requireContext(), R.string.remote_security_enable_first, Toast.LENGTH_SHORT).show();
                return;
            }
            showAutoLockTimeoutPicker();
        });
        
        // Logout All Sessions row
        remoteAuthLogoutAllRow.setOnClickListener(v -> {
            if (!authManager.isAuthEnabled()) {
                Toast.makeText(requireContext(), R.string.remote_security_enable_first, Toast.LENGTH_SHORT).show();
                return;
            }
            handleLogoutAllSessions();
        });
        
        // Update auto-lock display
        updateAutoLockDisplay();
    }
    
    /**
     * Show/hide security rows based on auth enabled state
     */
    private void updateSecurityRowsVisibility(boolean authEnabled) {
        int visibility = authEnabled ? View.VISIBLE : View.GONE;
        remoteAuthPasswordRow.setVisibility(visibility);
        remoteAuthAutoLockRow.setVisibility(visibility);
        remoteAuthLogoutAllRow.setVisibility(visibility);
    }
    
    /**
     * Show InputActionBottomSheetFragment for password setting
     */
    private void showSetPasswordSheet() {
        RemoteAuthManager authManager = RemoteAuthManager.getInstance(requireContext());
        String initialValue = ""; // Empty, not dots
        String hint = "Enter new password";
        String helperText = "Change your authentication password here. Minimum 4 characters. This is basic HTTP authentication - not highly secure but better than none. When you change the password, all other devices will be logged out automatically.";
        
        InputActionBottomSheetFragment sheet = InputActionBottomSheetFragment.newInput(
            getString(R.string.remote_security_password),
            initialValue,
            hint,
            getString(android.R.string.ok),
            getString(R.string.remote_security_password_desc),
            android.R.drawable.ic_lock_idle_lock,
            helperText
        );
        
        sheet.setCallbacks(new InputActionBottomSheetFragment.Callbacks() {
            @Override
            public void onImportConfirmed(JSONObject json) {
                // Not used
            }
            
            @Override
            public void onResetConfirmed() {
                // Not used
            }
            
            @Override
            public void onInputConfirmed(String password) {
                if (password == null || password.trim().isEmpty()) {
                    Toast.makeText(requireContext(), R.string.remote_security_password_empty, Toast.LENGTH_SHORT).show();
                    return;
                }
                
                // Validate password length
                if (password.length() < Constants.REMOTE_AUTH_MIN_PASSWORD_LENGTH || 
                    password.length() > Constants.REMOTE_AUTH_MAX_PASSWORD_LENGTH) {
                    Toast.makeText(requireContext(), R.string.remote_security_password_required, Toast.LENGTH_SHORT).show();
                    return;
                }
                
                // Set password (hashed internally)
                authManager.setPassword(password);
                Toast.makeText(requireContext(), R.string.remote_security_password_set, Toast.LENGTH_SHORT).show();
                
                // Update password value display
                remoteAuthPasswordValue.setText("••••••••");
            }
        });
        
        sheet.show(getParentFragmentManager(), "set_password_sheet");
    }
    
    /**
     * Show auto-lock timeout picker
     */
    private void showAutoLockTimeoutPicker() {
        if (!RemoteAuthManager.getInstance(requireContext()).isAuthEnabled()) {
            Toast.makeText(requireContext(), R.string.remote_security_enable_first, Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Create timeout options
        ArrayList<OptionItem> options = new ArrayList<>();
        options.add(new OptionItem("never", "Never", "Keep sessions active indefinitely"));
        options.add(new OptionItem("30min", "30 Minutes", "Lock after 30 minutes of inactivity"));
        options.add(new OptionItem("1hr", "1 Hour", "Lock after 1 hour of inactivity"));
        options.add(new OptionItem("3hr", "3 Hours", "Lock after 3 hours of inactivity"));
        options.add(new OptionItem("6hr", "6 Hours", "Lock after 6 hours of inactivity"));
        
        // Get current selection (default to "never")
        String currentTimeout = "never";
        
        PickerBottomSheetFragment picker = PickerBottomSheetFragment.newInstance(
            getString(R.string.remote_security_auto_lock),
            options,
            currentTimeout,
            "auto_lock_timeout_result"
        );
        
        // Listen for selection
        getParentFragmentManager().setFragmentResultListener(
            "auto_lock_timeout_result",
            getViewLifecycleOwner(),
            (requestKey, result) -> {
                String selectedId = result.getString(PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
                if (selectedId != null) {
                    // Update display
                    String displayText = getTimeoutDisplayText(selectedId);
                    remoteAuthAutoLockValue.setText(displayText);
                    
                    // Save to RemoteAuthManager
                    RemoteAuthManager authManager = RemoteAuthManager.getInstance(requireContext());
                    int timeoutMinutes = parseTimeoutMinutes(selectedId);
                    authManager.setAutoLockTimeout(timeoutMinutes);
                    
                    Toast.makeText(requireContext(), "Auto-lock timeout set to: " + displayText, Toast.LENGTH_SHORT).show();
                }
            }
        );
        
        picker.show(getParentFragmentManager(), "auto_lock_timeout_picker");
    }
    
    /**
     * Get display text for timeout value
     */
    private String getTimeoutDisplayText(String timeoutId) {
        switch (timeoutId) {
            case "never":
                return "Never";
            case "30min":
                return "30 Minutes";
            case "1hr":
                return "1 Hour";
            case "3hr":
                return "3 Hours";
            case "6hr":
                return "6 Hours";
            default:
                return "Never";
        }
    }
    
    /**
     * Parse timeout ID to minutes
     */
    private int parseTimeoutMinutes(String timeoutId) {
        switch (timeoutId) {
            case "never":
                return 0;  // 0 = never auto-lock
            case "30min":
                return 30;
            case "1hr":
                return 60;
            case "3hr":
                return 180;
            case "6hr":
                return 360;
            default:
                return 0;
        }
    }
    
    /**
     * Update auto-lock timeout display
     */
    private void updateAutoLockDisplay() {
        RemoteAuthManager authManager = RemoteAuthManager.getInstance(requireContext());
        int timeoutMinutes = authManager.getAutoLockTimeout();
        String displayText = formatTimeoutMinutesToText(timeoutMinutes);
        remoteAuthAutoLockValue.setText(displayText);
    }
    
    /**
     * Format timeout minutes to human-readable text
     */
    private String formatTimeoutMinutesToText(int minutes) {
        if (minutes == 0) {
            return "Never";
        } else if (minutes == 30) {
            return "30 Minutes";
        } else if (minutes == 60) {
            return "1 Hour";
        } else if (minutes == 180) {
            return "3 Hours";
        } else if (minutes == 360) {
            return "6 Hours";
        } else {
            return "Never";
        }
    }
    
    /**
     * Handle logout all sessions action
     */
    private void handleLogoutAllSessions() {
        RemoteAuthManager authManager = RemoteAuthManager.getInstance(requireContext());
        int sessionCount = authManager.getActiveSessions().size();
        
        if (sessionCount == 0) {
            Toast.makeText(requireContext(), R.string.remote_security_no_active_sessions, Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Clear all sessions
        authManager.clearAllSessions();
        Toast.makeText(requireContext(), 
            getString(R.string.remote_security_sessions_cleared), 
            Toast.LENGTH_SHORT).show();
    }
    
    private int resolveThemeColor(int attr) {
        android.util.TypedValue typedValue = new android.util.TypedValue();
        requireContext().getTheme().resolveAttribute(attr, typedValue, true);
        return typedValue.data;
    }
    
    // ==================== Cloud Account Methods ====================
    
    /**
     * Handle cloud account button click.
     * If already linked, shows account info. If not, prompts for device name and opens WebView.
     */
    private void onCloudAccountClick() {
        if (cloudAuthManager.isLinked()) {
            // Already linked - show options (view info, unlink, etc.)
            showCloudAccountInfo();
        } else {
            // Not linked - prompt for device name
            showDeviceNameInput();
        }
    }
    
    /**
     * Show input dialog for device name before linking
     */
    private void showDeviceNameInput() {
        String defaultName = android.os.Build.MODEL;
        
        InputActionBottomSheetFragment input = InputActionBottomSheetFragment.newInput(
            getString(R.string.cloud_account_device_name_title),
            defaultName,
            getString(R.string.cloud_account_device_name_hint),
            getString(R.string.cloud_account_link),
            getString(R.string.cloud_account_device_name_desc),
            R.drawable.ic_cloud_account
        );
        
        input.setCallbacks(new InputActionBottomSheetFragment.Callbacks() {
            @Override
            public void onInputConfirmed(String value) {
                if (value != null && !value.trim().isEmpty()) {
                    launchCloudAccountWebView(value.trim());
                }
            }
            
            @Override
            public void onImportConfirmed(org.json.JSONObject json) {
                // Not used for input mode
            }
            
            @Override
            public void onResetConfirmed() {
                // Not used for input mode
            }
        });
        
        input.show(getChildFragmentManager(), "device_name_input");
    }
    
    /**
     * Launch the CloudAccountActivity WebView for device linking
     */
    private void launchCloudAccountWebView(String deviceName) {
        Intent intent = new Intent(requireContext(), CloudAccountActivity.class);
        intent.putExtra(CloudAccountActivity.EXTRA_DEVICE_NAME, deviceName);
        cloudAccountLauncher.launch(intent);
    }
    
    /**
     * Show cloud account info when already linked.
     * First syncs device info from server to get latest name if renamed on web.
     */
    private void showCloudAccountInfo() {
        // Sync device info from server first (handles renamed device on web)
        cloudAuthManager.syncDeviceInfo(new CloudAuthManager.DeviceInfoListener() {
            @Override
            public void onSuccess(String name, String deviceType, boolean isActive) {
                // Device info synced, now show picker with updated name
                showCloudAccountPicker();
            }
            
            @Override
            public void onError(String error) {
                // Sync failed, show picker with cached data anyway
                Log.w(TAG, "Failed to sync device info: " + error);
                showCloudAccountPicker();
            }
        });
    }
    
    /**
     * Show the cloud account info picker (after sync)
     */
    private void showCloudAccountPicker() {
        String email = cloudAuthManager.getUserEmail();
        String deviceName = cloudAuthManager.getDeviceName();
        String deviceId = cloudAuthManager.getShortDeviceId();
        
        // Update cloud button state in case name changed
        updateCloudButtonState();
        
        // Build options list using correct OptionItem constructors
        ArrayList<OptionItem> options = new ArrayList<>();
        
        // Account info (non-clickable header) - use (id, title, subtitle) constructor
        if (email != null) {
            options.add(new OptionItem("email", email, "Linked Account"));
        }
        
        // Device name
        if (deviceName != null) {
            options.add(new OptionItem("device", deviceName, "Device Name"));
        }
        
        // Device ID (copyable)
        options.add(new OptionItem("copy_id", deviceId, getString(R.string.cloud_account_device_id) + " (tap to copy)"));
        
        // Unlink option with helper text as subtitle
        options.add(new OptionItem("unlink", getString(R.string.cloud_account_unlink), getString(R.string.cloud_account_helper_text)));
        
        PickerBottomSheetFragment picker = PickerBottomSheetFragment.newInstance(
            getString(R.string.cloud_account_title),
            options,
            null, // no pre-selected
            "cloud_account_action"
        );
        
        // Listen for selection via fragment result
        getChildFragmentManager().setFragmentResultListener("cloud_account_action", this, (requestKey, result) -> {
            String selectedId = result.getString(PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if ("unlink".equals(selectedId)) {
                showUnlinkConfirmation();
            } else if ("copy_id".equals(selectedId)) {
                copyToClipboard(cloudAuthManager.getDeviceId());
                Toast.makeText(requireContext(), "Device ID copied", Toast.LENGTH_SHORT).show();
            }
        });
        
        picker.show(getChildFragmentManager(), "cloud_account_info");
    }
    
    /**
     * Show unlink confirmation dialog
     */
    private void showUnlinkConfirmation() {
        InputActionBottomSheetFragment confirm = InputActionBottomSheetFragment.newConfirm(
            getString(R.string.cloud_account_unlink),
            getString(R.string.cloud_account_unlink),
            getString(R.string.cloud_account_unlink_confirm),
            R.drawable.ic_delete_red
        );
        
        confirm.setCallbacks(new InputActionBottomSheetFragment.Callbacks() {
            @Override
            public void onResetConfirmed() {
                cloudAuthManager.unlinkDevice();
                Toast.makeText(requireContext(), R.string.cloud_account_unlink_success, Toast.LENGTH_SHORT).show();
                updateCloudButtonState();
            }
            
            @Override
            public void onImportConfirmed(org.json.JSONObject json) {
                // Not used for confirm mode
            }
        });
        
        confirm.show(getChildFragmentManager(), "unlink_confirm");
    }
    
    /**
     * Update cloud button visual state based on link status
     */
    private void updateCloudButtonState() {
        if (cloudIcon == null) return;
        
        boolean isLinked = cloudAuthManager.isLinked();
        boolean hasValidToken = cloudAuthManager.hasValidToken();
        boolean hasRefreshToken = cloudAuthManager.getRefreshToken() != null;
        boolean canUseCloud = isLinked && (hasValidToken || hasRefreshToken);
        
        if (isLinked) {
            // Linked - show green tint, hide unlinked dot, update status text
            cloudIcon.setColorFilter(0xFF4CAF50); // Green
            if (cloudUnlinkedDot != null) {
                cloudUnlinkedDot.setVisibility(View.GONE);
            }
            if (cloudStatusText != null) {
                String deviceName = cloudAuthManager.getDeviceName();
                if (deviceName != null && !deviceName.isEmpty()) {
                    cloudStatusText.setText(deviceName);
                    cloudStatusText.setTextColor(0xFF4CAF50); // Green
                } else {
                    cloudStatusText.setText(R.string.cloud_account_linked);
                    cloudStatusText.setTextColor(0xFF4CAF50); // Green
                }
            }
        } else {
            // Not linked - show red tint (FadCam brand color), show unlinked dot
            cloudIcon.setColorFilter(0xFFFF4444); // Red
            if (cloudUnlinkedDot != null) {
                cloudUnlinkedDot.setVisibility(View.VISIBLE);
            }
            if (cloudStatusText != null) {
                cloudStatusText.setText(R.string.cloud_account_not_linked);
                cloudStatusText.setTextColor(0xFF888888); // Gray
            }
            // Disable cloud streaming and reset to local mode
            CloudStreamUploader uploader = CloudStreamUploader.getInstance(requireContext());
            if (uploader.isEnabled()) {
                uploader.setEnabled(false);
            }
            // Reset to local mode
            android.content.SharedPreferences prefs = requireContext().getSharedPreferences("FadCamCloudPrefs", Context.MODE_PRIVATE);
            prefs.edit().putInt(KEY_STREAMING_MODE, MODE_LOCAL).putBoolean("cloud_streaming_enabled", false).apply();
        }
        
        // Hide legacy cloud streaming row
        if (cloudStreamingRow != null) {
            cloudStreamingRow.setVisibility(View.GONE);
        }
        
        // Update streaming mode display
        updateStreamingModeDisplay();
    }
    
    // Streaming mode constants
    private static final String KEY_STREAMING_MODE = "streaming_mode";
    private static final int MODE_LOCAL = 0;
    private static final int MODE_CLOUD = 1;
    
    /**
     * Setup the streaming mode selector (Local Network vs FadSec Cloud)
     */
    private void setupStreamingModeSelector() {
        if (streamingModeRow == null) return;
        
        // Update display based on current mode
        updateStreamingModeDisplay();
        
        // Click handler opens picker
        streamingModeRow.setOnClickListener(v -> showStreamingModePicker());
    }
    
    /**
     * Show bottom sheet picker for streaming mode selection
     */
    private void showStreamingModePicker() {
        android.content.SharedPreferences prefs = requireContext().getSharedPreferences("FadCamCloudPrefs", Context.MODE_PRIVATE);
        int currentMode = prefs.getInt(KEY_STREAMING_MODE, MODE_LOCAL);
        
        // Check if cloud is available
        boolean hasValidToken = cloudAuthManager.hasValidToken();
        boolean hasRefreshToken = cloudAuthManager.getRefreshToken() != null;
        boolean canUseCloud = cloudAuthManager.isLinked() && (hasValidToken || hasRefreshToken);
        
        ArrayList<OptionItem> items = new ArrayList<>();
        
        // Local Network option
        items.add(new OptionItem(
            "mode_local",
            getString(R.string.streaming_mode_local),
            getString(R.string.streaming_mode_local_desc),
            null, null, null, null, null,
            "wifi", // Material icon ligature
            null, null, null
        ));
        
        // FadSec Cloud option
        if (canUseCloud) {
            // Cloud is available
            items.add(OptionItem.withLigatureBadge(
                "mode_cloud",
                getString(R.string.streaming_mode_cloud),
                "cloud", // Material icon ligature
                getString(R.string.streaming_mode_cloud_featured),
                R.drawable.featured_badge_bg,
                false, // not disabled
                getString(R.string.streaming_mode_cloud_desc)
            ));
        } else {
            // Cloud not available - show as disabled with link prompt
            items.add(OptionItem.withLigatureBadge(
                "mode_cloud",
                getString(R.string.streaming_mode_cloud),
                "cloud",
                getString(R.string.streaming_mode_cloud_featured),
                R.drawable.featured_badge_bg,
                true, // disabled
                getString(R.string.streaming_mode_link_required)
            ));
        }
        
        String selectedId = currentMode == MODE_CLOUD ? "mode_cloud" : "mode_local";
        final String REQUEST_KEY = "rk_streaming_mode";
        
        PickerBottomSheetFragment picker = PickerBottomSheetFragment.newInstance(
            getString(R.string.streaming_mode_title),
            items,
            selectedId,
            REQUEST_KEY,
            getString(R.string.streaming_mode_helper)
        );
        
        getParentFragmentManager().setFragmentResultListener(REQUEST_KEY, this, (key, bundle) -> {
            String selected = bundle.getString(PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if (selected == null) return;
            
            if ("mode_cloud".equals(selected)) {
                // Check again if cloud is available
                boolean cloudAvailable = cloudAuthManager.isLinked() && 
                    (cloudAuthManager.hasValidToken() || cloudAuthManager.getRefreshToken() != null);
                
                if (!cloudAvailable) {
                    Toast.makeText(requireContext(), R.string.streaming_mode_link_required, Toast.LENGTH_SHORT).show();
                    onCloudAccountClick();
                    return;
                }
                setStreamingMode(MODE_CLOUD);
            } else {
                setStreamingMode(MODE_LOCAL);
            }
        });
        
        picker.show(getParentFragmentManager(), "streaming_mode_picker");
    }
    
    /**
     * Set the streaming mode and update UI/preferences
     */
    private void setStreamingMode(int mode) {
        android.content.SharedPreferences prefs = requireContext().getSharedPreferences("FadCamCloudPrefs", Context.MODE_PRIVATE);
        prefs.edit().putInt(KEY_STREAMING_MODE, mode).apply();
        
        // Also sync the legacy cloud_streaming_enabled flag
        boolean cloudEnabled = (mode == MODE_CLOUD);
        prefs.edit().putBoolean("cloud_streaming_enabled", cloudEnabled).apply();
        
        // Update uploader
        CloudStreamUploader uploader = CloudStreamUploader.getInstance(requireContext());
        uploader.setEnabled(cloudEnabled);
        
        // Start/stop cloud status manager if server is already running
        CloudStatusManager statusManager = CloudStatusManager.getInstance(requireContext());
        if (cloudEnabled) {
            // Try to start - will only actually start if server is running and cloud is ready
            statusManager.start();
        } else {
            // Stop if running
            statusManager.stop();
        }
        
        // Update display
        updateStreamingModeDisplay();
        
        Log.i(TAG, "Streaming mode set to: " + (mode == MODE_CLOUD ? "Cloud" : "Local"));
    }
    
    /**
     * Update the streaming mode value display
     */
    private void updateStreamingModeDisplay() {
        if (streamingModeValue == null) return;
        
        android.content.SharedPreferences prefs = requireContext().getSharedPreferences("FadCamCloudPrefs", Context.MODE_PRIVATE);
        int currentMode = prefs.getInt(KEY_STREAMING_MODE, MODE_LOCAL);
        
        if (currentMode == MODE_CLOUD) {
            streamingModeValue.setText(R.string.streaming_mode_cloud);
            streamingModeValue.setTextColor(0xFF4CAF50); // Green for cloud
        } else {
            streamingModeValue.setText(R.string.streaming_mode_local);
            streamingModeValue.setTextColor(0xFF888888); // Gray for local
        }
    }
    
    /**
     * Setup cloud streaming toggle and its state
     * Note: Legacy method - functionality moved to streaming mode selector
     */
    private void setupCloudStreaming() {
        // Legacy toggle is now hidden - functionality moved to streaming mode selector
        if (cloudStreamingRow != null) {
            cloudStreamingRow.setVisibility(View.GONE);
        }
        
        // Trigger token refresh if needed
        boolean hasValidToken = cloudAuthManager.hasValidToken();
        boolean hasRefreshToken = cloudAuthManager.getRefreshToken() != null;
        
        if (!hasValidToken && hasRefreshToken) {
            Log.i(TAG, "Token expired, triggering background refresh...");
            cloudAuthManager.refreshTokenAsync(new CloudAuthManager.TokenRefreshListener() {
                @Override
                public void onRefreshSuccess(String newToken, long newExpiry) {
                    Log.i(TAG, "Token refreshed successfully");
                    // Update streaming mode display after refresh
                    if (isAdded()) {
                        requireActivity().runOnUiThread(() -> updateStreamingModeDisplay());
                    }
                }
                
                @Override
                public void onRefreshFailed(String error) {
                    Log.e(TAG, "Token refresh failed: " + error);
                }
            });
        }
    }
    
    /**
     * Update cloud streaming status text (legacy, kept for compatibility)
     */
    private void updateCloudStreamingStatus(boolean enabled) {
        if (cloudStreamingStatus == null) return;
        
        if (enabled) {
            cloudStreamingStatus.setText(R.string.cloud_streaming_status_on);
            cloudStreamingStatus.setTextColor(0xFF4CAF50); // Green
        } else {
            cloudStreamingStatus.setText(R.string.cloud_streaming_status_off);
            cloudStreamingStatus.setTextColor(0xFF888888); // Gray
        }
    }
}