package com.fadcam.ui;

import static android.content.ContentValues.TAG;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.media.CamcorderProfile;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.util.Log; // Make sure Log is imported
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton; // Import RadioButton
import android.widget.RadioGroup;  // Import RadioGroup
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.Fragment;

import com.fadcam.CameraType;
import com.fadcam.Constants;
// Replace FadCam's Log with standard android.util.Log or remove if not needed for logging here
// import com.fadcam.Log;
import com.fadcam.MainActivity;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.Utils;
import com.fadcam.VideoCodec;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textview.MaterialTextView;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import android.util.Range; // Make sure this import is present
import java.util.TreeSet; // Used for sorting and uniqueness
import java.util.Set;     // Used for intermediate storage
import java.util.stream.IntStream; // For easy array conversion
import java.util.Comparator; // For sorting camera IDs
import java.util.concurrent.ExecutorService; // Make sure this import exists
import java.util.concurrent.Executors;

import android.content.Intent; // Add Intent import
import androidx.localbroadcastmanager.content.LocalBroadcastManager; // OR use ContextCompat if not using LocalBroadcastManager
import androidx.core.content.ContextCompat; // If using standard broadcast

// ----- Fix Start for this class (SettingsFragment_video_splitting_imports) -----
import android.text.Editable;
import android.text.TextWatcher;
// ----- Fix Ended for this class (SettingsFragment_video_splitting_imports) -----


public class SettingsFragment extends BaseFragment {

    private LocationHelper locationHelper;

    // Storage location prefs were moved to SharedPreferencesManager
    // Remove these duplicates if they exist here:
    // private static final String PREF_WATERMARK_OPTION = "watermark_option";
    // static final String PREF_LOCATION_DATA = "location_data";
    // private static final String PREF_DEBUG_DATA = "debug_data";

    private static final int REQUEST_PERMISSIONS = 1;
    private static final int REQUEST_LOCATION_PERMISSION = 1;

    private Spinner resolutionSpinner;
    private Spinner frameRateSpinner;
    private Spinner codecSpinner;
    private Spinner watermarkSpinner;
    private Spinner themeSpinner;
    private Spinner orientationSpinner; // Add field for orientation spinner

    private MaterialButtonToggleGroup cameraSelectionToggle;
    private MaterialSwitch locationSwitch; // Declare locationSwitch
    private MaterialSwitch debugSwitch; // Declare debugSwitch
    private MaterialSwitch audioSwitch; // Declare audioSwitch

    private View view; // Make sure view is accessible
    private View backCameraLensDivider; // *** ADD FIELD FOR THE DIVIDER ***

    private BroadcastReceiver broadcastOnRecordingStarted;
    private BroadcastReceiver broadcastOnRecordingStopped;

    private Map<CameraType, List<CamcorderProfile>> camcorderProfilesAvailables = new HashMap<>();

    // --- STORAGE VARIABLES ---
    private SharedPreferencesManager sharedPreferencesManager;
    private RadioGroup storageLocationRadioGroup;
    private RadioButton radioInternalStorage;
    private RadioButton radioCustomLocation;
    private MaterialButton buttonChooseCustomLocation;
    private MaterialTextView tvCustomLocationPath;
    private ActivityResultLauncher<Uri> openDocumentTreeLauncher;
    private static final String TAG_SETTINGS = "SettingsFragment"; // Use a specific tag
    // --- END STORAGE VARIABLES ---
    private Spinner backCameraLensSpinner;
    private LinearLayout backCameraLensLayout;
    private ExecutorService executorService; // <-- *** ADD THIS DECLARATION ***
    private List<CameraIdInfo> availableBackCameras = new ArrayList<>(); // Store detected back cameras

    // ----- Fix Start for this class (SettingsFragment_video_splitting_fields) -----
    private MaterialSwitch videoSplittingSwitch;
    private LinearLayout videoSplitSizeLayout;
    private MaterialTextView videoSplitSizeValueTextView; // New TextView
    // ----- Fix Ended for this class (SettingsFragment_video_splitting_fields) -----

    private TextView audioInputSourceStatus;
    private BroadcastReceiver headsetPlugReceiver;

    // Simple class to hold camera ID and its display name
    private static class CameraIdInfo {
        final String id;
        final String displayName;

        CameraIdInfo(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        @NonNull
        @Override
        public String toString() {
            return displayName; // What's shown in the Spinner
        }
        // equals/hashCode needed if comparing these objects
        @Override public boolean equals(Object o) { if (this == o) return true; if (o == null || getClass() != o.getClass()) return false; CameraIdInfo that = (CameraIdInfo) o; return id.equals(that.id); }
        @Override public int hashCode() { return Objects.hash(id); }
    }

    private TextView bitrateInfoTextView;
    private TextView bitrateHelperTextView;

    // Holds all detected input mics
    private List<AudioDeviceInfo> availableInputMics = new ArrayList<>();
    // Holds the selected mic (null = default/phone mic)
    private AudioDeviceInfo selectedMic = null;
    // Holds the list of labels for dialog
    private List<String> availableMicLabels = new ArrayList<>();

    // ----- Fix Start for this class(SettingsFragment_isWiredMicConnected_field)-----
    private boolean isWiredMicConnected = false;
    // ----- Fix Ended for this class(SettingsFragment_isWiredMicConnected_field)-----

    // ----- Fix Start: Add micPlugReceiver field to SettingsFragment -----
    private BroadcastReceiver micPlugReceiver;
    // ----- Fix End: Add micPlugReceiver field to SettingsFragment -----

    /**
     * Scans for all available input microphones (wired, USB, Bluetooth, etc), logs them,
     * and updates the availableInputMics and availableMicLabels lists.
     */
    private void scanAvailableInputMics() {
        availableInputMics.clear();
        availableMicLabels.clear();
        AudioManager audioManager = (AudioManager) requireContext().getSystemService(Context.AUDIO_SERVICE);
        boolean headphonesNoMicDetected = false;
        boolean wiredMicDetected = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);
            StringBuilder logBuilder = new StringBuilder("Detected input devices: ");
            for (AudioDeviceInfo device : devices) {
                int type = device.getType();
                String typeStr = getAudioDeviceTypeString(type);
                    String name = device.getProductName() != null ? device.getProductName().toString() : "Unknown";
                    logBuilder.append("[Type: ").append(typeStr).append(", Name: ").append(name).append("] ");
                if (device.isSource()) {
                    switch (type) {
                        case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                            availableInputMics.add(device);
                            availableMicLabels.add(name + " (" + typeStr + ")");
                            wiredMicDetected = true;
                            break;
                        case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                            // Only add to list if no headset (with mic) detected
                            headphonesNoMicDetected = true;
                            break;
                        case AudioDeviceInfo.TYPE_USB_DEVICE:
                        case AudioDeviceInfo.TYPE_USB_HEADSET:
                        case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                        case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                        case AudioDeviceInfo.TYPE_BLE_HEADSET:
                        case AudioDeviceInfo.TYPE_BLE_BROADCAST:
                        case AudioDeviceInfo.TYPE_BLE_SPEAKER:
                            availableInputMics.add(device);
                            availableMicLabels.add(name + " (" + typeStr + ")");
                            break;
                        default:
                            // Not a supported external mic, but log it
                            break;
                    }
                }
            }
            android.util.Log.i(TAG_SETTINGS, logBuilder.toString());
        } else {
            // Fallback for older devices: use legacy intent sticky
            Intent intent = requireContext().registerReceiver(null, new IntentFilter(Intent.ACTION_HEADSET_PLUG));
            boolean legacyWired = intent != null && intent.getIntExtra("state", 0) == 1;
            if (legacyWired) {
                availableMicLabels.add("Wired Headset (Legacy)");
                availableInputMics.add(null); // No AudioDeviceInfo, but we can still show
                wiredMicDetected = true;
            }
            android.util.Log.i(TAG_SETTINGS, "Legacy headset plug state: " + legacyWired);
        }
        // Add special label if only headphones (no mic) detected
        if (!wiredMicDetected && headphonesNoMicDetected) {
            availableMicLabels.add(getString(R.string.audio_input_source_headphones_no_mic));
            availableInputMics.add(null); // No mic, just for display
        }
        // Always add the default phone mic as the first option
        availableMicLabels.add(0, getString(R.string.audio_input_source_phone));
        availableInputMics.add(0, null); // null means default/phone mic
    }

    /**
     * Returns a human-readable string for AudioDeviceInfo type.
     */
    private String getAudioDeviceTypeString(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "Wired Headset";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return "Wired Headphones";
            case AudioDeviceInfo.TYPE_USB_DEVICE: return "USB Device";
            case AudioDeviceInfo.TYPE_USB_HEADSET: return "USB Headset";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "Bluetooth SCO";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "Bluetooth A2DP";
            case AudioDeviceInfo.TYPE_BUILTIN_MIC: return "Built-in Mic";
            case AudioDeviceInfo.TYPE_BLE_HEADSET: return "BLE Headset";
            case AudioDeviceInfo.TYPE_BLE_BROADCAST: return "BLE Broadcast";
            case AudioDeviceInfo.TYPE_BLE_SPEAKER: return "BLE Speaker";
            default: return "Other (" + type + ")";
        }
    }

    /**
     * Shows a Material dialog listing all detected mics for user selection.
     * Updates selectedMic and selectedMicLabel, and updates the status TextView.
     */
    private void showMicSelectionDialog() {
        scanAvailableInputMics();
        int checkedItem = 0;
        if (selectedMic != null) {
            for (int i = 0; i < availableInputMics.size(); i++) {
                if (availableInputMics.get(i) != null && selectedMic != null &&
                        availableInputMics.get(i).getId() == selectedMic.getId()) {
                    checkedItem = i;
                    break;
                }
            }
        }
        if (availableInputMics.size() == 1 && availableInputMics.get(0) == null) {
            // Only phone mic available
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.setting_audio_input_source_title))
                    .setMessage(getString(R.string.audio_input_source_wired_not_available))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.setting_audio_input_source_title))
                .setSingleChoiceItems(availableMicLabels.toArray(new String[0]), checkedItem, (dialog, which) -> {
                    selectedMic = availableInputMics.get(which);
                    updateAudioInputSourceStatusUI();
                    // Save selection in SharedPreferences if needed
                    sharedPreferencesManager.setAudioInputSource(selectedMic == null ? SharedPreferencesManager.AUDIO_INPUT_SOURCE_PHONE : SharedPreferencesManager.AUDIO_INPUT_SOURCE_WIRED);
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.universal_cancel, null)
                .show();
    }

    /**
     * Updates the status TextView to show the selected mic.
     */
    private void updateAudioInputSourceStatusUI() {
        String status;
        boolean headphonesNoMicDetected = false;
        for (String label : availableMicLabels) {
            if (label.equals(getString(R.string.audio_input_source_headphones_no_mic))) {
                headphonesNoMicDetected = true;
                break;
            }
        }
        if (selectedMic == null) {
            if (headphonesNoMicDetected) {
                status = getString(R.string.setting_audio_input_source_status_default) +
                        "\n" + getString(R.string.audio_input_source_headphones_no_mic);
        } else {
                status = getString(R.string.setting_audio_input_source_status_default);
                if (availableInputMics.size() == 1) {
                    status += "\n" + getString(R.string.audio_input_source_wired_not_available);
        }
            }
        } else {
            status = getString(R.string.setting_audio_input_source_status_wired) + ":\n" + selectedMic.getProductName();
        }
        audioInputSourceStatus.setText(status);
        android.util.Log.i(TAG_SETTINGS, "Audio input source status updated. Selected: " + selectedMic);
    }

    // --- Activity Result Launcher Initialization & onCreate---
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Use standard Log
        android.util.Log.d(TAG_SETTINGS,"onCreate: Initializing fragment.");
        // Initialize helpers/managers FIRST
        locationHelper = new LocationHelper(requireContext());
        sharedPreferencesManager = SharedPreferencesManager.getInstance(requireContext());
        executorService = Executors.newSingleThreadExecutor(); // Ensure initialized
        // *** ADD: Detect cameras ONCE here (or consider a dedicated CameraHelper class) ***
        detectAvailableBackCameras();

        initializeCamcorderProfiles(); // Call initialization methods
        initializeVideoCodec();

        // Register the launcher FOR CUSTOM LOCATION SELECTION
        openDocumentTreeLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(),
                uri -> { // This lambda executes AFTER user picks a folder (or cancels)
                    if (uri != null) {
                        Log.i(TAG_SETTINGS, "SAF URI selected: " + uri);
                        boolean success = false;
                        try {
                            // --- IMPORTANT: Persist Permission ---
                            final int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                            requireContext().getContentResolver().takePersistableUriPermission(uri, takeFlags);

                            // --- Save Preferences ---
                            sharedPreferencesManager.setCustomStorageUri(uri.toString());
                            sharedPreferencesManager.setStorageMode(SharedPreferencesManager.STORAGE_MODE_CUSTOM);
                            success = true;

                            Toast.makeText(requireContext(), "Custom location set", Toast.LENGTH_SHORT).show();

                            // --- SEND BROADCAST on SUCCESS ---
                            sendStorageChangedBroadcast();

                        } catch (SecurityException e) { Log.e(TAG_SETTINGS, "Failed take permission", e); Toast.makeText(requireContext(),"Error setting permission",Toast.LENGTH_LONG).show();}
                        catch (Exception e) { Log.e(TAG_SETTINGS, "Error processing URI", e); Toast.makeText(requireContext(),"Could not use folder",Toast.LENGTH_LONG).show(); }

                        // --- Update UI & potentially reset prefs on failure ---
                        if (!success) {
                            sharedPreferencesManager.setStorageMode(SharedPreferencesManager.STORAGE_MODE_INTERNAL);
                            sharedPreferencesManager.setCustomStorageUri(null);
                        }
                        updateStorageLocationUI(); // Update UI regardless

                    } else {
                        Log.w(TAG_SETTINGS, "SAF folder selection cancelled (null URI returned).");
                        // IMPORTANT: Revert radio button if user cancels selection while trying to enable Custom
                        String currentMode = sharedPreferencesManager.getStorageMode();
                        if(!SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(currentMode)){
                            // If they weren't already in Custom mode, and cancelled, revert UI to internal
                            storageLocationRadioGroup.check(R.id.radio_internal_storage);
                        }
                        updateStorageLocationUI(); // Ensure UI is consistent
                    }
                }); // End of registerForActivityResult lambda
    }


    // --- NEW: Helper method to send the broadcast ---
    private void sendStorageChangedBroadcast() {
        if(getContext() == null) return;
        Intent intent = new Intent(Constants.ACTION_STORAGE_LOCATION_CHANGED);
        // No specific data needed, the receiver just needs to know a change happened
        // Using standard ContextCompat for broadcast without LocalBroadcastManager
        // Make it explicit that it's not exported
        // sendBroadcast(intent) // Using this implicitly might trigger lint warnings depending on target SDK
        requireContext().sendBroadcast(intent); // Standard way if not using LocalBroadcastManager
        Log.i(TAG_SETTINGS, "Sent ACTION_STORAGE_LOCATION_CHANGED broadcast.");
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        view = inflater.inflate(R.layout.fragment_settings, container, false);
        android.util.Log.d(TAG_SETTINGS,"onCreateView: Inflating layout and setting up views.");

        // Find views by ID
        MaterialToolbar toolbar = view.findViewById(R.id.topAppBar);
        MaterialButton readmeButton = view.findViewById(R.id.readme_button);
        cameraSelectionToggle = view.findViewById(R.id.camera_selection_toggle);
        resolutionSpinner = view.findViewById(R.id.resolution_spinner);
        frameRateSpinner = view.findViewById(R.id.framerate_spinner);
        codecSpinner = view.findViewById(R.id.codec_spinner);
        watermarkSpinner = view.findViewById(R.id.watermark_spinner);
        locationSwitch = view.findViewById(R.id.location_toggle_group);
        debugSwitch = view.findViewById(R.id.debug_toggle_group);
        audioSwitch = view.findViewById(R.id.audio_toggle_group); // Find audio switch
        MaterialButton reviewButton = view.findViewById(R.id.review_button);
        themeSpinner = view.findViewById(R.id.theme_spinner); // Initialize themeSpinner
        MaterialButton audioSettingsButton = view.findViewById(R.id.audio_settings_button);
        if (audioSettingsButton != null) {
            audioSettingsButton.setOnClickListener(v -> showAudioSettingsDialog());
        }

        // Initialize Storage UI elements
        storageLocationRadioGroup = view.findViewById(R.id.storage_location_radio_group);
        radioInternalStorage = view.findViewById(R.id.radio_internal_storage);
        radioCustomLocation = view.findViewById(R.id.radio_custom_location);
        buttonChooseCustomLocation = view.findViewById(R.id.button_choose_custom_location);
        tvCustomLocationPath = view.findViewById(R.id.tv_custom_location_path);

        // *** Find the NEW views ***
        backCameraLensSpinner = view.findViewById(R.id.back_camera_lens_spinner);
        backCameraLensLayout = view.findViewById(R.id.back_camera_lens_layout);
        backCameraLensDivider = view.findViewById(R.id.back_camera_lens_divider); // *** FIND THE DIVIDER ***
        orientationSpinner = view.findViewById(R.id.orientation_spinner);

        // ----- Fix Start for this class (SettingsFragment_video_splitting_view_finding) -----
        videoSplittingSwitch = view.findViewById(R.id.video_splitting_switch);
        videoSplitSizeLayout = view.findViewById(R.id.video_split_size_layout);
        videoSplitSizeValueTextView = view.findViewById(R.id.video_split_size_value_textview); // New TextView
        // ----- Fix Ended for this class (SettingsFragment_video_splitting_view_finding) -----

        audioInputSourceStatus = view.findViewById(R.id.audio_input_source_status);
        setupAudioInputSourceSection();

        // *** Safety check for the new view ***
        if (backCameraLensDivider == null) {
            Log.e(TAG, "onCreateView: Critical - back_camera_lens_divider View not found!");
        }
        // *** Add null check for the layout too if not done elsewhere ***
        if (backCameraLensLayout == null) {
            Log.e(TAG, "onCreateView: Critical - back_camera_lens_layout LinearLayout not found!");
        }

        bitrateInfoTextView = view.findViewById(R.id.bitrate_info_textview);
        bitrateHelperTextView = view.findViewById(R.id.bitrate_helper_textview);

        // Setup components

        readmeButton.setOnClickListener(v -> showReadmeDialog());
        setupCameraSelectionToggle(view, cameraSelectionToggle);
        setupBackCameraLensSpinner();                             // Setup spinner listener
        setupResolutionSpinner();
        setupFrameRateSpinner();
        setupCodecSpinner();
        setupWatermarkSpinner(view, watermarkSpinner);
        setupLocationSwitch(locationSwitch); // Use switch variable
        setupDebugSwitch(debugSwitch);     // Use switch variable
        setupAudioSwitch(audioSwitch); // Setup audio switch
        reviewButton.setOnClickListener(v -> openInAppBrowser("https://forms.gle/DvUoc1v9kB2bkFiS6"));
        setupThemeSpinner(view);
        setupFrameRateNoteText();
        setupCodecNoteText();
        setupOrientationSpinner();

        // ----- Fix Start for this class (SettingsFragment_video_splitting_setup_call) -----
        setupVideoSplittingSection();
        // ----- Fix Ended for this class (SettingsFragment_video_splitting_setup_call) -----

        // Setup listeners for storage options
        setupStorageLocationOptions();
        // Set initial UI state based on saved preferences
        updateStorageLocationUI();

        setupCameraSelectionToggle(view, cameraSelectionToggle); // Setup front/back toggle FIRST
        // *** Setup the NEW spinner AFTER the main toggle ***
        setupBackCameraLensSpinner();
        // Call initial UI update for the lens spinner based on current Front/Back selection
        updateBackLensSpinnerVisibility();

        MaterialButton videoBitrateButton = view.findViewById(R.id.video_bitrate_button);
        if (videoBitrateButton != null) {
            videoBitrateButton.setOnClickListener(v -> showVideoBitrateDialog());
        }

        // ----- Fix Start for onCreateView: Setup Choose Button for mic selection -----
        MaterialButton audioInputSourceButton = view.findViewById(R.id.audio_input_source_button);
        if (audioInputSourceButton != null) {
            audioInputSourceButton.setOnClickListener(v -> showMicSelectionDialog());
        }
        // Remove row click logic for audio_input_source_layout
        // ... existing code ...
        // ----- Fix End for onCreateView: Setup Choose Button for mic selection -----

        // ----- Fix Start for onboarding toggle logic in onCreateView -----
        MaterialSwitch onboardingToggle = view.findViewById(R.id.onboarding_toggle);
        if (onboardingToggle != null) {
            boolean showOnboarding = sharedPreferencesManager.isShowOnboarding();
            onboardingToggle.setChecked(showOnboarding);
            onboardingToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
                sharedPreferencesManager.setShowOnboarding(isChecked);
            });
        }
        // ----- Fix Ended for onboarding toggle logic in onCreateView -----

        MaterialButton languageChooseButton = view.findViewById(R.id.language_choose_button);
        if (languageChooseButton != null) {
            setupSettingsLanguageDialog(languageChooseButton);
        }

        return view;
    }


    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    public void onStart()
    {
        super.onStart();
        android.util.Log.d(TAG_SETTINGS,"onStart: Registering receivers.");
        registerBroadcastOnRecordingStarted();
        registerBrodcastOnRecordingStopped();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireActivity().registerReceiver(broadcastOnRecordingStarted, new IntentFilter(Constants.BROADCAST_ON_RECORDING_STARTED), Context.RECEIVER_EXPORTED);
            requireActivity().registerReceiver(broadcastOnRecordingStopped, new IntentFilter(Constants.BROADCAST_ON_RECORDING_STOPPED), Context.RECEIVER_EXPORTED);
        } else {
            requireActivity().registerReceiver(broadcastOnRecordingStarted, new IntentFilter(Constants.BROADCAST_ON_RECORDING_STARTED));
            requireActivity().registerReceiver(broadcastOnRecordingStopped, new IntentFilter(Constants.BROADCAST_ON_RECORDING_STOPPED));
        }
        // Call sync camera switch AFTER views are inflated and listeners possibly set
        syncCameraSwitch(view, cameraSelectionToggle);
        // Register micPlugReceiver for real-time mic feedback
        if (micPlugReceiver == null) {
            micPlugReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    updateAudioInputSourceUI();
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction("android.hardware.usb.action.USB_DEVICE_ATTACHED");
            filter.addAction("android.hardware.usb.action.USB_DEVICE_DETACHED");
            filter.addAction(Intent.ACTION_HEADSET_PLUG);
            requireContext().registerReceiver(micPlugReceiver, filter);
        }
    }


    // --- NEW Storage Logic Methods ---

    // ** Inside SettingsFragment.java **

    private void setupStorageLocationOptions() {
        // Ensure RadioGroup exists
        if (storageLocationRadioGroup == null) {
            Log.e(TAG_SETTINGS,"storageLocationRadioGroup is null in setupStorageLocationOptions!");
            return;
        }

        storageLocationRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            boolean preferenceChanged = false; // Flag to track if a broadcast is needed due to mode CHANGE

            // Get current saved values BEFORE making changes
            String previouslySavedMode = sharedPreferencesManager.getStorageMode();
            String existingCustomUri = sharedPreferencesManager.getCustomStorageUri();

            if (checkedId == R.id.radio_internal_storage) {
                // Changing *to* Internal
                if (!SharedPreferencesManager.STORAGE_MODE_INTERNAL.equals(previouslySavedMode)) {
                    Log.i(TAG_SETTINGS, "User switched to Internal Storage via Radio Button.");
                    sharedPreferencesManager.setStorageMode(SharedPreferencesManager.STORAGE_MODE_INTERNAL);
                    sharedPreferencesManager.setCustomStorageUri(null); // Clear custom URI
                    preferenceChanged = true;
                }
                updateStorageLocationUI(); // Update UI regardless
            }
            else if (checkedId == R.id.radio_custom_location) {
                // Changing *to* Custom (or re-selecting it)

                // ** FIX: Only launch picker if NO valid URI exists **
                if (existingCustomUri == null || !isValidUri(existingCustomUri)) { // Add an isValidUri check if needed
                    Log.i(TAG_SETTINGS,"Custom Radio checked, but no valid URI exists. Launching picker.");
                    launchDirectoryPicker();
                    // The mode and URI will be set ONLY AFTER successful picker result.
                    // No preference change YET, so preferenceChanged remains false.
                }
                // ** Else: Valid URI already exists **
                else {
                    Log.d(TAG_SETTINGS,"Custom Radio checked, valid URI already exists. Setting mode.");
                    // Check if the mode actually needs changing
                    if (!SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(previouslySavedMode)) {
                        sharedPreferencesManager.setStorageMode(SharedPreferencesManager.STORAGE_MODE_CUSTOM);
                        preferenceChanged = true; // Mode changed, need broadcast
                    }
                    updateStorageLocationUI(); // Ensure UI reflects the custom state
                }
            }

            // Send broadcast *only if* the mode preference actually changed and was committed
            if (preferenceChanged) {
                sendStorageChangedBroadcast();
            }
        });

        // Choose button listener remains the same
        if (buttonChooseCustomLocation != null) {
            buttonChooseCustomLocation.setOnClickListener(v -> launchDirectoryPicker());
        } else {
            Log.e(TAG_SETTINGS, "buttonChooseCustomLocation is null!");
        }
    }

    // Helper to quickly check if a URI string seems parseable (optional but good practice)
    private boolean isValidUri(String uriString){
        if(uriString == null || uriString.isEmpty()) return false;
        try {
            Uri.parse(uriString);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void launchDirectoryPicker() {
        android.util.Log.d(TAG_SETTINGS, "Launching directory picker (ACTION_OPEN_DOCUMENT_TREE)");
        Uri initialUri = null;
        try {
            openDocumentTreeLauncher.launch(initialUri);
        } catch (Exception e) {
            android.util.Log.e(TAG_SETTINGS, "Error launching directory picker", e);
            Toast.makeText(requireContext(),"Could not open folder picker", Toast.LENGTH_SHORT).show();
            sharedPreferencesManager.setStorageMode(SharedPreferencesManager.STORAGE_MODE_INTERNAL);
            updateStorageLocationUI(); // Reset UI if launch fails
        }
    }

    private void updateStorageLocationUI() {
        // Ensure view elements are not null before accessing
        if(storageLocationRadioGroup == null || buttonChooseCustomLocation == null || tvCustomLocationPath == null){
            Log.w(TAG_SETTINGS,"updateStorageLocationUI: UI elements not yet initialized.");
            return;
        }

        String currentMode = sharedPreferencesManager.getStorageMode();
        String customUriString = sharedPreferencesManager.getCustomStorageUri();
        boolean isInCustomMode = SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(currentMode) && customUriString != null;

        Log.d(TAG_SETTINGS, "Updating UI - Mode: " + currentMode + ", URI set: " + (customUriString != null));

        if (isInCustomMode) {
            storageLocationRadioGroup.check(R.id.radio_custom_location);
            buttonChooseCustomLocation.setVisibility(View.VISIBLE);
            tvCustomLocationPath.setVisibility(View.VISIBLE);
            tvCustomLocationPath.setText(getDisplayPath(customUriString));
            Log.d(TAG_SETTINGS, "UI updated to show Custom Location: " + tvCustomLocationPath.getText());
        } else {
            storageLocationRadioGroup.check(R.id.radio_internal_storage);
            buttonChooseCustomLocation.setVisibility(View.GONE);
            tvCustomLocationPath.setVisibility(View.GONE);
            if (SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(currentMode)){
                Log.w(TAG_SETTINGS,"Custom mode was set but URI is null, reverting mode to internal.");
                sharedPreferencesManager.setStorageMode(SharedPreferencesManager.STORAGE_MODE_INTERNAL);
            }
            Log.d(TAG_SETTINGS, "UI updated to show Internal Storage");
        }
    }

    private String getDisplayPath(String uriString) {
        if (uriString == null) return "None selected";
        try {
            Uri treeUri = Uri.parse(uriString);
            DocumentFile pickedDir = DocumentFile.fromTreeUri(requireContext(), treeUri);
            if (pickedDir != null && pickedDir.canRead()) { // Check readability
                String name = pickedDir.getName();
                String path = treeUri.getPath();
                // Basic check for typical external storage/SD card format
                if (name == null && path != null && path.contains(":") && path.startsWith("/tree/")) {
                    return "SD Card / Ext. Storage";
                } else if (name != null) {
                    return "Folder: " + name;
                } else {
                    // Fallback if name is null but readable
                    return "Selected Folder";
                }
            } else {
                Log.w(TAG_SETTINGS,"Could not get DocumentFile, name or cannot read URI: "+uriString);
                return "Custom Location (Unreadable/Invalid)";
            }
        } catch (Exception e) {
            Log.e(TAG_SETTINGS, "Error parsing URI for display: "+uriString, e);
            return "Custom Location (Error)";
        }
    }
    // --- END NEW Storage Logic Methods ---


    // --- Existing Helper & Setup methods ---

    // Helper method to apply ripple effect on touch (used by various listeners)
    private void vibrateTouch() {
        Vibrator vibrator = (Vibrator) requireContext().getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(50); // Deprecated in API 26
            }
        }
    }

    // Method to visually update toggle button group state
    private void updateButtonAppearance(MaterialButton button, boolean isSelected) {
        if(getContext() == null) return; // Prevent crashes if context is gone
        button.setIconTintResource(isSelected ? R.color.black : android.R.color.transparent);
        button.setStrokeColorResource(isSelected ? R.color.colorPrimary : R.color.material_on_surface_stroke);
        button.setTextColor(ContextCompat.getColor(requireContext(), isSelected ? R.color.black : R.color.material_on_surface_emphasis_medium));
        button.setBackgroundColor(ContextCompat.getColor(requireContext(), isSelected ? R.color.colorPrimary : android.R.color.transparent));
    }


    // --- Ensure initializeCamcorderProfiles is defined ---
    private void initializeCamcorderProfiles() {
        // Ensure camcorderProfilesAvailables map is created
        if(camcorderProfilesAvailables == null) {
            camcorderProfilesAvailables = new HashMap<>();
        } else {
            camcorderProfilesAvailables.clear(); // Clear previous data if re-initializing
        }

        Log.d(TAG, "Initializing Camcorder Profiles Map");
        for(CameraType type : CameraType.values()) {
            List<CamcorderProfile> camcorderProfiles = getCamcorderProfilesForTypeInternal(type); // Renamed helper
            if(!camcorderProfiles.isEmpty()) {
                Log.d(TAG,"Profiles found for " + type + ": " + camcorderProfiles.size());
                camcorderProfilesAvailables.put(type, camcorderProfiles);
            } else {
                Log.w(TAG,"No profiles found for " + type);
                // Optionally add a default high profile if list is empty?
                // camcorderProfilesAvailables.put(type, Collections.singletonList(CamcorderProfile.get(type.getCameraId(), CamcorderProfile.QUALITY_HIGH)));
            }
        }
        Log.d(TAG,"Finished initializing profiles map.");
    }

    private void initializeVideoCodec() {
        if(!sharedPreferencesManager.isVideoCodecExist()) {
            VideoCodec compatibleCodec = getCompatiblesVideoCodec();
            if(compatibleCodec != null) {
                sharedPreferencesManager.sharedPreferences.edit().putString(Constants.PREF_VIDEO_CODEC, compatibleCodec.toString()).apply();
                Log.d(TAG_SETTINGS, "Initialized video codec preference to: " + compatibleCodec);
            } else {
                Log.e(TAG_SETTINGS,"Could not find any compatible video codec!");
                // Fallback or handle error appropriately
                sharedPreferencesManager.sharedPreferences.edit().putString(Constants.PREF_VIDEO_CODEC, Constants.DEFAULT_VIDEO_CODEC.toString()).apply();
            }
        } else {
            Log.d(TAG_SETTINGS,"Video codec preference already exists: " + sharedPreferencesManager.getVideoCodec());
        }
    }


    @Override
    public void onStop() {
        super.onStop();
        Log.d(TAG_SETTINGS,"onStop: Unregistering receivers.");
        try {
            if(broadcastOnRecordingStarted != null) requireActivity().unregisterReceiver(broadcastOnRecordingStarted);
            if(broadcastOnRecordingStopped != null) requireActivity().unregisterReceiver(broadcastOnRecordingStopped);
        } catch(IllegalArgumentException e){
            Log.w(TAG_SETTINGS,"Receiver not registered? "+e.getMessage());
        }
        broadcastOnRecordingStarted = null; // Nullify to prevent leaks
        broadcastOnRecordingStopped = null;
        // Unregister micPlugReceiver for real-time mic feedback
        if (micPlugReceiver != null) {
            requireContext().unregisterReceiver(micPlugReceiver);
            micPlugReceiver = null;
        }
    }


    private void registerBroadcastOnRecordingStarted() {
        if(broadcastOnRecordingStarted != null) return; // Already registered
        broadcastOnRecordingStarted = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent i)
            {
                Log.d(TAG_SETTINGS, "Received BROADCAST_ON_RECORDING_STARTED");
                // Disable UI elements when recording starts
                if(cameraSelectionToggle != null) cameraSelectionToggle.setEnabled(false);
                if(resolutionSpinner != null) resolutionSpinner.setEnabled(false);
                if(frameRateSpinner != null) frameRateSpinner.setEnabled(false);
                if(watermarkSpinner != null) watermarkSpinner.setEnabled(false);
                if(codecSpinner != null) codecSpinner.setEnabled(false);
                if(storageLocationRadioGroup != null) { // Disable storage options too
                    for(int j = 0; j < storageLocationRadioGroup.getChildCount(); j++) {
                        storageLocationRadioGroup.getChildAt(j).setEnabled(false);
                    }
                }
                if(buttonChooseCustomLocation != null) buttonChooseCustomLocation.setEnabled(false);
            }
        };
        Log.d(TAG_SETTINGS,"Recording started broadcast receiver created.");
    }

    private void registerBrodcastOnRecordingStopped() {
        if(broadcastOnRecordingStopped != null) return; // Already registered
        broadcastOnRecordingStopped = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent i)
            {
                Log.d(TAG_SETTINGS, "Received BROADCAST_ON_RECORDING_STOPPED");
                // Re-enable UI elements when recording stops
                if(cameraSelectionToggle != null) cameraSelectionToggle.setEnabled(true);
                if(resolutionSpinner != null) resolutionSpinner.setEnabled(true);
                if(frameRateSpinner != null) frameRateSpinner.setEnabled(true);
                if(watermarkSpinner != null) watermarkSpinner.setEnabled(true);
                if(codecSpinner != null) codecSpinner.setEnabled(sharedPreferencesManager.isVideoCodecExist()); // Enable based on prefs
                if(storageLocationRadioGroup != null) { // Enable storage options
                    for(int j = 0; j < storageLocationRadioGroup.getChildCount(); j++) {
                        storageLocationRadioGroup.getChildAt(j).setEnabled(true);
                    }
                }
                if(buttonChooseCustomLocation != null) buttonChooseCustomLocation.setEnabled(true);
            }
        };
        Log.d(TAG_SETTINGS,"Recording stopped broadcast receiver created.");
    }

    // Overriding onResume to ensure UI is correctly updated when fragment becomes visible
    @Override
    public void onResume() {
        super.onResume();
        Log.d(TAG_SETTINGS, "onResume: Syncing UI states.");
        // Ensure sharedPreferencesManager is valid
        if (sharedPreferencesManager == null) {
            sharedPreferencesManager = SharedPreferencesManager.getInstance(requireContext());
        }
        // Sync UI state with current preferences
        syncCameraSwitch(view, cameraSelectionToggle);
        updateBackLensSpinnerVisibility(); // Sync lens visibility based on F/B state
        updateStorageLocationUI(); // Update storage UI on resume
        updateResolutionSpinner(); // Ensure spinner reflects current camera
        updateFrameRateSpinner(); // Ensure framerate reflects resolution
        updateBitrateInfoAndHelper(); // Ensure bitrate info is updated
        registerHeadsetPlugReceiver();
        updateAudioInputSourceUI();
    }

    @Override
    public void onPause() {
        super.onPause();
        unregisterHeadsetPlugReceiver();
    }

    private void registerHeadsetPlugReceiver() {
        if (headsetPlugReceiver != null) return;
        headsetPlugReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                if (intent.getAction() != null && intent.getAction().equals(Intent.ACTION_HEADSET_PLUG)) {
                    updateAudioInputSourceUI();
                }
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_HEADSET_PLUG);
        requireContext().registerReceiver(headsetPlugReceiver, filter);
    }

    private void unregisterHeadsetPlugReceiver() {
        if (headsetPlugReceiver != null) {
            requireContext().unregisterReceiver(headsetPlugReceiver);
            headsetPlugReceiver = null;
        }
    }

    private void setupAudioInputSourceSection() {
        updateAudioInputSourceStatusUI();
        View audioInputSourceLayout = view.findViewById(R.id.audio_input_source_layout);
        if (audioInputSourceLayout != null) {
            audioInputSourceLayout.setOnClickListener(v -> showMicSelectionDialog());
        }
    }

    private void updateAudioInputSourceUI() {
        scanAvailableInputMics();
        updateAudioInputSourceStatusUI();
    }

    private void updateWiredMicStatus() {
        AudioManager audioManager = (AudioManager) requireContext().getSystemService(Context.AUDIO_SERVICE);
        selectedMic = null;
        isWiredMicConnected = false; // Reset at the start
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);
            StringBuilder logBuilder = new StringBuilder("Detected input devices: ");
            for (AudioDeviceInfo device : devices) {
                String typeStr = getAudioDeviceTypeString(device.getType());
                String name = device.getProductName() != null ? device.getProductName().toString() : "Unknown";
                logBuilder.append("[Type: ").append(typeStr).append(", Name: ").append(name).append("] ");
                // Prioritize wired/USB/Bluetooth mics
                if (device.isSource()) {
                    switch (device.getType()) {
                        case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                        case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                        case AudioDeviceInfo.TYPE_USB_DEVICE:
                        case AudioDeviceInfo.TYPE_USB_HEADSET:
                        case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                        case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                        case AudioDeviceInfo.TYPE_BLE_HEADSET:
                        case AudioDeviceInfo.TYPE_BLE_BROADCAST:
                        case AudioDeviceInfo.TYPE_BLE_SPEAKER:
                            if (!isWiredMicConnected) { // Only set first found
                                isWiredMicConnected = true;
                                selectedMic = device;
                            }
                            break;
                        default:
                            // For completeness, log all input devices
                            break;
                    }
                }
            }
            android.util.Log.i(TAG_SETTINGS, logBuilder.toString());
        } else {
            // Fallback for older devices: use legacy intent sticky
            Intent intent = requireContext().registerReceiver(null, new IntentFilter(Intent.ACTION_HEADSET_PLUG));
            isWiredMicConnected = intent != null && intent.getIntExtra("state", 0) == 1;
            selectedMic = null; // No AudioDeviceInfo available on legacy
            android.util.Log.i(TAG_SETTINGS, "Legacy headset plug state: " + isWiredMicConnected);
        }
    }


    // Replace this entire method in SettingsFragment.java

    /**
     * Detects all available physical back-facing cameras and assigns descriptive names.
     * Populates the `availableBackCameras` list used by the spinner. Includes detailed logging
     * and refined labelling logic.
     */
    private void detectAvailableBackCameras() {
        availableBackCameras.clear();
        if (getContext() == null) {
            Log.e(TAG, "detectAvailableBackCameras: Context is null.");
            return;
        }
        CameraManager manager = (CameraManager) requireContext().getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) {
            Log.e(TAG, "detectAvailableBackCameras: CameraManager is null.");
            return;
        }

        Log.i(TAG, "=== Starting Back Camera Detection (Including Logical) ===");
        try {
            String[] cameraIds = manager.getCameraIdList();
            Log.d(TAG, "System reported Camera IDs: " + Arrays.toString(cameraIds));

            for (String id : cameraIds) {
                Log.d(TAG, "--- Checking ID: " + id + " ---");
                try {
                    CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);

                    // 1. Check LENS_FACING - Primary Filter
                    Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                    if (facing == null || facing != CameraMetadata.LENS_FACING_BACK) {
                        String facingStr = (facing == null) ? "null" : (facing == CameraMetadata.LENS_FACING_FRONT ? "FRONT" : (facing == CameraMetadata.LENS_FACING_EXTERNAL ? "EXTERNAL" : "UNKNOWN(" + facing + ")"));
                        Log.d(TAG,"ID " + id + ": Skipping - Not LENS_FACING_BACK. Actual: "+facingStr);
                        continue;
                    }
                    Log.d(TAG, "ID " + id + ": Passed LENS_FACING_BACK check.");

                    // 2. Determine if it's a Logical Camera
                    boolean isLogicalCamera = false;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        Set<String> physicalIds = characteristics.getPhysicalCameraIds();
                        if (physicalIds != null && !physicalIds.isEmpty()){
                            isLogicalCamera = true;
                            Log.d(TAG,"ID " + id + " is a LOGICAL camera (contains physical IDs: " + physicalIds + "). Will be included.");
                        } else {
                            Log.d(TAG,"ID " + id + ": Confirmed as physical (or pre-Android P).");
                        }
                    }

                    // 3. Get Focal Length for HINTS
                    float[] focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                    Float focalLength = null;
                    if (focalLengths != null && focalLengths.length > 0) {
                        focalLength = focalLengths[0]; // Use the first reported focal length
                        Log.d(TAG,"ID " + id + ": Focal length reported: " + focalLength);
                    } else {
                        Log.w(TAG, "ID " + id + ": No focal length info available.");
                    }

                    // 4. Determine Display Name
                    StringBuilder displayNameBuilder = new StringBuilder();
                    boolean isDefaultCamera = id.equals(Constants.DEFAULT_BACK_CAMERA_ID);

                    if (isDefaultCamera) {
                        displayNameBuilder.append("Main");
                    } else {
                        displayNameBuilder.append("Camera");
                    }
                    displayNameBuilder.append(" (").append(id).append(")");

                    if (isLogicalCamera) {
                        displayNameBuilder.append(" (Logical)");
                    }

                    // Only add focal length hints for non-default cameras
                    if (!isDefaultCamera) {
                        if (focalLength != null) {
                            if (focalLength <= 22f) { // Example threshold for Ultra Wide
                                displayNameBuilder.append(" (Ultra Wide)");
                            } else if (focalLength >= 50f && focalLength <= 85f) { // Example for Portrait/Short Tele
                                displayNameBuilder.append(" (Portrait/Zoom)");
                            } else if (focalLength > 60f) { // Example threshold for Telephoto
                                displayNameBuilder.append(" (Telephoto)");
                            } else if (!isLogicalCamera) {
                                // For non-default, non-logical, physical cameras without specific focal length match
                                // displayNameBuilder.append(" (Auxiliary)"); // Kept commented as per previous logic
                            }
                        } else if (!isLogicalCamera) {
                            // For non-default, non-logical, physical cameras with NO focal length
                            // displayNameBuilder.append(" (Auxiliary)"); // Kept commented as per previous logic
                        }
                    }

                    // 5. Add to the list
                    String finalDisplayName = displayNameBuilder.toString().trim().replaceAll("\\s+", " ");
                    availableBackCameras.add(new CameraIdInfo(id, finalDisplayName));
                    Log.i(TAG, ">>> ADDED Back Camera: ID=" + id + ", Assigned Name=" + finalDisplayName + " <<<");

                } catch (CameraAccessException | IllegalArgumentException e) {
                    Log.e(TAG, "!!! Skipping ID " + id + ": Could not access characteristics.", e);
                } catch (AssertionError e) { // Catch assertion errors from getPhysicalCameraIds on some devices
                    Log.e(TAG,"!!! Skipping ID " + id + ": AssertionError checking physical IDs for " + id, e);
                }
                Log.d(TAG,"--- Finished checking ID: " + id + " ---");
            } // End loop

            Collections.sort(availableBackCameras, Comparator.comparing(info -> info.id));
            Log.i(TAG, "=== Finished Detection. Final Back Camera List (includes logical cameras if present) Size: " + availableBackCameras.size() + " ===");

        } catch (CameraAccessException e) {
            Log.e(TAG, "!!! CRITICAL ERROR getting camera ID list !!!", e);
            availableBackCameras.clear(); // Clear list on critical error
        }
    }

    private void syncCameraSwitch(View view, MaterialButtonToggleGroup toggleGroup){
        if(view == null || toggleGroup == null) return;

        MaterialButton backCameraButton = view.findViewById(R.id.button_back_camera);
        MaterialButton frontCameraButton = view.findViewById(R.id.button_front_camera);
        if(backCameraButton == null || frontCameraButton == null) return;

        CameraType selected = sharedPreferencesManager.getCameraSelection();

        // Disable unavailable buttons first
        backCameraButton.setEnabled(camcorderProfilesAvailables.containsKey(CameraType.BACK));
        frontCameraButton.setEnabled(camcorderProfilesAvailables.containsKey(CameraType.FRONT));


        if (selected == CameraType.FRONT && frontCameraButton.isEnabled()) {
            toggleGroup.check(R.id.button_front_camera);
            updateButtonAppearance(frontCameraButton, true);
            updateButtonAppearance(backCameraButton, false);
        } else { // Default to BACK if front is selected but disabled, or if BACK is selected
            toggleGroup.check(R.id.button_back_camera);
            updateButtonAppearance(backCameraButton, true);
            updateButtonAppearance(frontCameraButton, false);
            // Ensure preference matches if defaulted
            if (selected == CameraType.FRONT && !frontCameraButton.isEnabled()){
                sharedPreferencesManager.sharedPreferences.edit().putString(Constants.PREF_CAMERA_SELECTION, CameraType.BACK.toString()).apply();
            }
        }
        Log.d(TAG_SETTINGS,"Synced camera switch UI to: " + sharedPreferencesManager.getCameraSelection());
    }


    // --- setupCameraSelectionToggle MUST call updateFrameRateSpinner ---
    private void setupCameraSelectionToggle(View view, MaterialButtonToggleGroup toggleGroup) {
        // ... (Existing syncCameraSwitch call and appearance update logic) ...
        if (toggleGroup == null || view == null) return;
        syncCameraSwitch(view, toggleGroup);

        toggleGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                updateButtonAppearance(view.findViewById(R.id.button_back_camera), checkedId == R.id.button_back_camera);
                updateButtonAppearance(view.findViewById(R.id.button_front_camera), checkedId == R.id.button_front_camera);

                CameraType selectedCamera = (checkedId == R.id.button_front_camera) ? CameraType.FRONT : CameraType.BACK;
                if(selectedCamera != sharedPreferencesManager.getCameraSelection()) {
                    sharedPreferencesManager.sharedPreferences.edit().putString(Constants.PREF_CAMERA_SELECTION, selectedCamera.toString()).apply();
                    Log.i(TAG_SETTINGS, "Camera selection changed to: " + selectedCamera);
                    vibrateTouch();
                    // *** Update Visibility & Dependent Spinners ***
                    updateBackLensSpinnerVisibility(); // Show/Hide lens spinner
                    updateResolutionSpinner();         // Update resolutions for the new camera
                    updateFrameRateSpinner();          // Update framerates for the new camera
                    updateBitrateInfoAndHelper();      // Update bitrate info for the new camera
                } else {
                    Log.d(TAG, "Camera main selection didn't change.");
                    // Still need to update lens spinner visibility if fragment was just created
                    updateBackLensSpinnerVisibility();
                }
            }
        });
    } // End setupCameraSelectionToggle

    // *** Method requested: updateBackLensSpinnerVisibility (Complete Revised Code) ***
    /**
     * Updates the visibility of the back camera lens row AND its following divider.
     * Configures the spinner's enabled state based on detected lenses.
     */
    private void updateBackLensSpinnerVisibility() {
        // Safety check view readiness (include divider)
        if (backCameraLensLayout == null || backCameraLensSpinner == null || backCameraLensDivider == null || cameraSelectionToggle == null || getContext() == null) {
            Log.w(TAG,"updateBackLensSpinnerVisibility: Views or context not ready, skipping update.");
            return;
        }

        // 1. Determine visibility based on BACK camera selection
        boolean isBackCameraSelected = sharedPreferencesManager.getCameraSelection() == CameraType.BACK;

        // 2. Set visibility for BOTH the layout and the divider
        backCameraLensLayout.setVisibility(isBackCameraSelected ? View.VISIBLE : View.GONE);
        backCameraLensDivider.setVisibility(isBackCameraSelected ? View.VISIBLE : View.GONE); // *** ADDED THIS LINE ***
        Log.d(TAG, "Lens Section & Divider Visibility set to: " + (isBackCameraSelected ? "VISIBLE" : "GONE"));

        // 3. If the section is visible, configure the Spinner
        if (isBackCameraSelected) {
            backCameraLensSpinner.setVisibility(View.VISIBLE); // Spinner always visible when section is

            // Populate the spinner (already handles empty case)
            populateBackCameraLensSpinner();

            // Set Spinner enabled state based on lens count
            if (availableBackCameras.size() > 1) {
                backCameraLensSpinner.setEnabled(true);
                backCameraLensSpinner.setClickable(true);
                backCameraLensSpinner.setAlpha(1.0f);
                Log.d(TAG,"Multiple back lenses found (" + availableBackCameras.size() + "). Spinner ENABLED.");
            } else {
                backCameraLensSpinner.setEnabled(false);
                backCameraLensSpinner.setClickable(false);
                backCameraLensSpinner.setAlpha(0.5f);
                if (availableBackCameras.isEmpty()) {
                    Log.e(TAG,"No back lenses detected. Spinner DISABLED.");
                } else {
                    Log.d(TAG,"Single back lens detected. Spinner DISABLED, showing lens name.");
                }
            }
        } else {
            // If section is hidden, spinner is irrelevant (but set GONE for robustness)
            backCameraLensSpinner.setVisibility(View.GONE);
        }
    }

    // --- New Method: Setup Back Camera Lens Spinner ---
    private void setupBackCameraLensSpinner() {
        if (backCameraLensSpinner == null) {
            Log.e(TAG, "Back camera lens spinner is null, cannot set up.");
            return;
        }

        backCameraLensSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < availableBackCameras.size()) {
                    CameraIdInfo selectedInfo = availableBackCameras.get(position);
                    String selectedId = selectedInfo.id;
                    String currentlySavedId = sharedPreferencesManager.getSelectedBackCameraId();

                    // Save ONLY if the selection is different from saved preference
                    if (!selectedId.equals(currentlySavedId)) {
                        sharedPreferencesManager.setSelectedBackCameraId(selectedId);
                        Log.i(TAG, "Selected back camera lens ID saved: " + selectedId + " (" + selectedInfo.displayName + ")");
                        vibrateTouch();
                        // Optionally update Resolution/FPS spinners IF they are lens-dependent (less common)
                        // updateResolutionSpinner();
                        // updateFrameRateSpinner();
                    }
                } else {
                    Log.e(TAG, "Invalid position selected in back lens spinner: " + position);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });

        // Initial population will happen in updateBackLensSpinnerVisibility/populate...
    }

    // *** Method requested: populateBackCameraLensSpinner (Complete Revised Code) ***
    /**
     * Populates the back camera lens spinner with detected cameras.
     * Handles cases for multiple cameras, a single camera, or no cameras detected.
     */
    private void populateBackCameraLensSpinner() {
        // Safety checks
        if (backCameraLensSpinner == null || getContext() == null) {
            Log.w(TAG, "Cannot populate back lens spinner (null view or context).");
            return;
        }

        List<CameraIdInfo> itemsToDisplay = new ArrayList<>();
        ArrayAdapter<CameraIdInfo> adapter;

        if (availableBackCameras.isEmpty()) {
            // No cameras detected: Add a placeholder item
            itemsToDisplay.add(new CameraIdInfo("-1", "No back lenses found")); // Use an invalid ID like "-1"
            Log.w(TAG,"Populating spinner with 'No back lenses found'.");
        } else {
            // One or more cameras detected: Use the actual list
            itemsToDisplay.addAll(availableBackCameras);
            Log.d(TAG,"Populating spinner with " + itemsToDisplay.size() + " detected lenses.");
        }

        // Create and set adapter
        adapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_item,
                itemsToDisplay
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        backCameraLensSpinner.setAdapter(adapter);

        // Set current selection only if cameras were actually found
        if (!availableBackCameras.isEmpty()) {
            String savedId = sharedPreferencesManager.getSelectedBackCameraId();
            int selectedIndex = -1;
            for (int i = 0; i < availableBackCameras.size(); i++) {
                if (availableBackCameras.get(i).id.equals(savedId)) {
                    selectedIndex = i;
                    break;
                }
            }

            // Fallback logic if saved ID not found (remains the same)
            if (selectedIndex == -1) {
                Log.w(TAG,"Saved back camera ID '"+savedId+"' not found. Applying fallback logic.");
                for (int i = 0; i < availableBackCameras.size(); i++) {
                    if (Constants.DEFAULT_BACK_CAMERA_ID.equals(availableBackCameras.get(i).id)) {
                        selectedIndex = i;
                        sharedPreferencesManager.setSelectedBackCameraId(Constants.DEFAULT_BACK_CAMERA_ID);
                        Log.d(TAG,"Fallback applied: Selected default ID '" + Constants.DEFAULT_BACK_CAMERA_ID + "' at index " + selectedIndex);
                        break;
                    }
                }
                if (selectedIndex == -1) { // If default "0" wasn't found either
                    selectedIndex = 0; // Select the first available one
                    if(!availableBackCameras.isEmpty()){
                        sharedPreferencesManager.setSelectedBackCameraId(availableBackCameras.get(0).id);
                        Log.d(TAG,"Fallback applied: Selected first available camera ID '" + availableBackCameras.get(0).id + "' at index 0");
                    } else {
                        Log.e(TAG,"Cannot set selection index - no cameras available."); // Should be caught earlier, but belt-and-suspenders
                    }
                }
            }

            // Apply the determined selection
            if(selectedIndex >= 0 && selectedIndex < itemsToDisplay.size()) { // Bounds check for safety
                backCameraLensSpinner.setSelection(selectedIndex);
                Log.d(TAG,"Spinner selection set to index: "+selectedIndex);
            } else {
                Log.e(TAG,"Final selected index " + selectedIndex + " is out of bounds for items list size " + itemsToDisplay.size());
            }
        } else {
            // If no cameras, the adapter has the placeholder, just ensure selection is 0
            backCameraLensSpinner.setSelection(0);
            Log.d(TAG,"Spinner selection set to index 0 (placeholder).");
        }
    }


    private void setupResolutionSpinner() {
        updateResolutionSpinner(); // Populate initially

        resolutionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                CameraType currentCamera = sharedPreferencesManager.getCameraSelection();
                List<CamcorderProfile> camcorderProfiles = camcorderProfilesAvailables.get(currentCamera); // Uses cached profiles map

                if (camcorderProfiles != null && position >= 0 && position < camcorderProfiles.size()) {
                    CamcorderProfile selectedProfile = camcorderProfiles.get(position);
                    int newWidth = selectedProfile.videoFrameWidth;
                    int newHeight = selectedProfile.videoFrameHeight;
                    // Check if resolution actually changed
                    Size oldResolution = sharedPreferencesManager.getCameraResolution();
                    if(newWidth != oldResolution.getWidth() || newHeight != oldResolution.getHeight()) {
                        sharedPreferencesManager.sharedPreferences.edit()
                                .putInt(Constants.PREF_VIDEO_RESOLUTION_WIDTH, newWidth)
                                .putInt(Constants.PREF_VIDEO_RESOLUTION_HEIGHT, newHeight)
                                .apply();
                        Log.i(TAG_SETTINGS, "Resolution preference saved: " + newWidth + "x" + newHeight);
                        // *** REMOVED call to updateFrameRateSpinner() ***
                        // The FPS spinner is now independent of resolution selection
                        onResolutionOrFramerateChanged(); // Call the new method
                    }
                } else {
                    Log.e(TAG_SETTINGS, "Error getting selected profile for resolution saving. Pos=" + position + ", Profiles size=" + (camcorderProfiles != null ? camcorderProfiles.size(): "null"));
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }


    private void updateResolutionSpinner() {
        if(resolutionSpinner == null) return;
        CameraType selectedCamera = sharedPreferencesManager.getCameraSelection();
        Log.d(TAG_SETTINGS, "Updating resolutions for camera: "+ selectedCamera);
        List<String> camcorderProfilesList = getCompatiblesVideoResolutionsAsString(selectedCamera);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, camcorderProfilesList);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        resolutionSpinner.setAdapter(adapter);

        if(!camcorderProfilesList.isEmpty()) {
            int selectedIndex = getCamcorderProfileIndexPreferences(selectedCamera);
            // Ensure selectedIndex is within bounds
            if(selectedIndex < 0 || selectedIndex >= camcorderProfilesList.size()){
                Log.w(TAG_SETTINGS,"Selected resolution index "+selectedIndex+ " out of bounds, defaulting to 0");
                selectedIndex = 0;
                // Optionally update prefs to match default if out of bounds
            }
            resolutionSpinner.setSelection(selectedIndex);
            resolutionSpinner.setEnabled(true); // Enable if list is not empty
            Log.d(TAG_SETTINGS,"Resolution spinner updated. Count: "+camcorderProfilesList.size() + ". Selected index: "+selectedIndex);
        } else {
            resolutionSpinner.setEnabled(false); // Disable if no resolutions found
            Log.w(TAG_SETTINGS,"No compatible resolutions found for "+ selectedCamera);
        }
    }


    // Ensure setupFrameRateSpinner method uses the specific pref keys correctly
    private void setupFrameRateSpinner() {
        updateFrameRateSpinner(); // Populate initially based on current camera type

        frameRateSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if(getContext() == null) return;

                CameraType currentSelectedCamera = sharedPreferencesManager.getCameraSelection(); // Get currently selected TYPE
                List<Integer> currentHardwareRates = getHardwareSupportedFrameRates(currentSelectedCamera); // Get rates for THIS type

                if (position >= 0 && position < currentHardwareRates.size()) {
                    int newlySelectedRate = currentHardwareRates.get(position);
                    int currentlySavedRateSpecific = sharedPreferencesManager.getSpecificVideoFrameRate(currentSelectedCamera); // Get SPECIFIC pref

                    Log.d(TAG,"FPS Spinner Item Selected: Value="+newlySelectedRate + " for CameraType "+currentSelectedCamera+". Currently Saved Specific="+currentlySavedRateSpecific);

                    if (newlySelectedRate != currentlySavedRateSpecific) {
                        // Save to the preference key FOR THIS SPECIFIC CAMERA TYPE
                        sharedPreferencesManager.setSpecificVideoFrameRate(currentSelectedCamera, newlySelectedRate);
                        Log.i(TAG, "FPS PREFERENCE SAVED for CameraType [" + currentSelectedCamera + "]: " + newlySelectedRate + "fps");
                        vibrateTouch(); // Add feedback on successful save
                        onResolutionOrFramerateChanged(); // Call the new method
                    } else {
                        Log.d(TAG,"User selected same FPS as already saved for "+currentSelectedCamera+". No save needed.");
                    }
                } else {
                    Log.e(TAG, "Invalid position selected in FPS spinner: " + position + ". Rates available: "+ currentHardwareRates.size());
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }// End setupFrameRateSpinner


    // Keep getHardwareSupportedFrameRates as defined previously, it works per camera

    /**
     * Updates the frame rate spinner's adapter and selection based on hardware capabilities
     * and the SAVED PREFERENCE FOR THE CURRENTLY SELECTED CAMERA TYPE.
     */
    private void updateFrameRateSpinner() {
        // Safety checks
        if (frameRateSpinner == null || getContext() == null || sharedPreferencesManager == null) {
            Log.w(TAG_SETTINGS,"updateFrameRateSpinner: Prerequisites not met (Spinner/Context/PrefsMgr null).");
            if (frameRateSpinner != null) frameRateSpinner.setEnabled(false); // Disable spinner if cannot populate
            return;
        }

        CameraType selectedCameraType = sharedPreferencesManager.getCameraSelection();
        Log.d(TAG_SETTINGS, "Updating FPS spinner display FOR CAMERA TYPE: " + selectedCameraType);

        // 1. Get the list of actually supported rates for this camera type using the refined method
        List<Integer> supportedHardwareRates = getHardwareSupportedFrameRates(selectedCameraType);

        // 2. Populate adapter
        // Convert integer list to string list for the adapter
        List<String> ratesAsString = new ArrayList<>();
        for (Integer rate : supportedHardwareRates) {
            ratesAsString.add(String.valueOf(rate));
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>( requireContext(),
                android.R.layout.simple_spinner_item, ratesAsString);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        frameRateSpinner.setAdapter(adapter);

        // 3. Determine Selection
        int selectedIndex = -1; // Default to invalid index

        if (!supportedHardwareRates.isEmpty()) {
            // Get the FPS preference specifically saved for this camera type (FRONT or BACK)
            int savedRateForThisCameraType = sharedPreferencesManager.getSpecificVideoFrameRate(selectedCameraType);
            Log.d(TAG_SETTINGS,"FPS Spinner Update: Saved FPS Pref for Type "+selectedCameraType+" = "+savedRateForThisCameraType);

            // Check if the saved preference value is actually in the list of supported rates
            if (supportedHardwareRates.contains(savedRateForThisCameraType)) {
                selectedIndex = supportedHardwareRates.indexOf(savedRateForThisCameraType);
                Log.d(TAG_SETTINGS,"FPS Spinner Update: Selecting SAVED rate ("+savedRateForThisCameraType+") at index: "+selectedIndex);
            } else {
                // Saved rate is NOT supported/available. Fallback needed.
                Log.w(TAG_SETTINGS,"FPS Spinner Update: Saved rate ("+savedRateForThisCameraType+") is NOT in the supported list " + supportedHardwareRates + ". Falling back.");

                // Try falling back to the default rate (30 FPS)
                int defaultRate = Constants.DEFAULT_VIDEO_FRAME_RATE;
                if (supportedHardwareRates.contains(defaultRate)) {
                    selectedIndex = supportedHardwareRates.indexOf(defaultRate);
                    Log.d(TAG_SETTINGS,"FPS Spinner Update: Falling back to Default ("+defaultRate+") at index: "+selectedIndex);
                    // Update the preference ONLY because the previously saved one was invalid
                    sharedPreferencesManager.setSpecificVideoFrameRate(selectedCameraType, defaultRate);
                } else if (!supportedHardwareRates.isEmpty()){
                    // If even default 30 isn't supported, select the first available rate in the list
                    selectedIndex = 0;
                    int firstAvailableRate = supportedHardwareRates.get(selectedIndex);
                    Log.w(TAG_SETTINGS,"FPS Spinner Update: Default (30) also NOT supported. Selecting first available rate: "+firstAvailableRate +" at index 0.");
                    // Update preference to the first valid rate since saved/default were invalid
                    sharedPreferencesManager.setSpecificVideoFrameRate(selectedCameraType, firstAvailableRate);
                } else {
                    // This case should be prevented by getHardwareSupportedFrameRates returning [30] if empty
                    Log.e(TAG_SETTINGS,"FPS Spinner Update: CRITICAL ERROR - supportedHardwareRates became empty unexpectedly!");
                }
            }

            // 4. Set Spinner Selection and Enabled State
            if(selectedIndex >= 0 && selectedIndex < ratesAsString.size()) { // Check index bounds
                frameRateSpinner.setSelection(selectedIndex, false); // Set selection without triggering listener initially
                frameRateSpinner.setEnabled(true); // Enable spinner as there are options
                Log.d(TAG_SETTINGS,"FPS Spinner: Final selection set to index "+selectedIndex);
            } else {
                Log.e(TAG_SETTINGS,"FPS Spinner Update: Invalid final index ("+selectedIndex+"), cannot set selection. Disabling spinner.");
                frameRateSpinner.setEnabled(false); // Disable if no valid selection found
            }

        } else {
            // No rates were found/supported by getHardwareSupportedFrameRates (which should return [30] in that case)
            // If this block is reached, something is inconsistent. Disable the spinner.
            frameRateSpinner.setEnabled(false);
            Log.e(TAG_SETTINGS,"FPS Spinner Update: CRITICAL - supportedHardwareRates is unexpectedly empty for " + selectedCameraType + ". Disabling spinner.");
            // Clear the adapter to show nothing or placeholder? (Adapter already set with empty list earlier)
        }
        Log.d(TAG_SETTINGS,"FPS Spinner update finished for "+selectedCameraType+". Enabled: "+frameRateSpinner.isEnabled());
    } // End updateFrameRateSpinner

    private void setupCodecSpinner() {
        if(codecSpinner == null) return;
        List<VideoCodec> videoCodecsCompatibles = getCompatiblesVideoCodecs();
        Log.d(TAG_SETTINGS, "Setting up codec spinner. Compatible: " + videoCodecsCompatibles);

        List<String> videoCodecsCompatiblesAsString = new ArrayList<>();
        for (VideoCodec videoCodecCompatible : videoCodecsCompatibles) {
            videoCodecsCompatiblesAsString.add(videoCodecCompatible.getName());
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_item,
                videoCodecsCompatiblesAsString
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        codecSpinner.setAdapter(adapter);

        VideoCodec selectedCodec = sharedPreferencesManager.getVideoCodec();
        int selectedIndex = videoCodecsCompatibles.indexOf(selectedCodec);

        // Handle case where saved codec is somehow no longer compatible
        if(selectedIndex < 0){
            Log.w(TAG_SETTINGS,"Saved codec "+selectedCodec+" not compatible. Defaulting.");
            selectedIndex = videoCodecsCompatibles.indexOf(getCompatiblesVideoCodec()); // Get highest priority
            if (selectedIndex < 0) selectedIndex = 0; // Fallback
            sharedPreferencesManager.sharedPreferences.edit().putString(Constants.PREF_VIDEO_CODEC, videoCodecsCompatibles.get(selectedIndex).toString()).apply();
        }

        codecSpinner.setSelection(selectedIndex);
        codecSpinner.setEnabled(videoCodecsCompatibles.size() > 1); // Enable only if choices exist
        Log.d(TAG_SETTINGS, "Codec spinner updated. Count: "+videoCodecsCompatibles.size()+". Selected index: "+selectedIndex);

        codecSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                List<VideoCodec> codecs = getCompatiblesVideoCodecs();
                if (position >= 0 && position < codecs.size()){
                    sharedPreferencesManager.sharedPreferences.edit().putString(Constants.PREF_VIDEO_CODEC, codecs.get(position).toString()).apply();
                    Log.d(TAG_SETTINGS, "Codec preference saved: "+ codecs.get(position));
                } else {
                    Log.e(TAG_SETTINGS,"Invalid position selected in codec spinner: "+position);
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }


    private void setupWatermarkSpinner(View view, Spinner watermarkSpinner) {
        if(watermarkSpinner == null) return;
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(requireContext(),
                R.array.watermark_options, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        watermarkSpinner.setAdapter(adapter);

        String savedWatermark = sharedPreferencesManager.getWatermarkOption();
        int watermarkIndex = getWatermarkIndex(savedWatermark);
        watermarkSpinner.setSelection(watermarkIndex);
        Log.d(TAG_SETTINGS, "Watermark spinner set to index: " + watermarkIndex);

        watermarkSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selectedWatermarkValue = getWatermarkValue(position);
                sharedPreferencesManager.sharedPreferences.edit().putString(Constants.PREF_WATERMARK_OPTION, selectedWatermarkValue).apply();
                Log.d(TAG_SETTINGS, "Watermark preference saved: " + selectedWatermarkValue);
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    // Helper methods for watermark spinner
    private int getWatermarkIndex(String value) {
        String[] values = getResources().getStringArray(R.array.watermark_values);
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) {
                return i;
            }
        }
        Log.w(TAG_SETTINGS,"Watermark value '"+value+"' not found in R.array.watermark_values, defaulting to 0.");
        return 0; // Default to first option if not found
    }

    private String getWatermarkValue(int index) {
        String[] values = getResources().getStringArray(R.array.watermark_values);
        if(index >=0 && index < values.length) {
            return values[index];
        }
        Log.e(TAG_SETTINGS, "Invalid index for watermark values: " + index);
        return values[0]; // Default to first on error
    }


    private void showReadmeDialog() {
        vibrateTouch();
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext());
        builder.setTitle(R.string.dialog_welcome_title);
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_readme, null);
        if(dialogView == null) return; // Check if inflation failed
        MaterialButton githubButton = dialogView.findViewById(R.id.github_button);
        MaterialButton discordButton = dialogView.findViewById(R.id.discord_button);
        if (githubButton != null) {
            githubButton.setOnClickListener(v -> openUrl("https://github.com/anonfaded/FadCam"));
        }
        if (discordButton != null) {
            discordButton.setOnClickListener(v -> openUrl("https://discord.gg/kvAZvdkuuN"));
        }
        builder.setView(dialogView);
        builder.setPositiveButton(android.R.string.ok, null); // Use standard OK text
        builder.show();
    }


    private void openUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        } catch(Exception e){
            Log.e(TAG_SETTINGS, "Could not open URL: " + url, e);
            Toast.makeText(requireContext(), "Could not open link", Toast.LENGTH_SHORT).show();
        }
    }


    private void saveLanguagePreference(String languageCode) {
        SharedPreferences.Editor editor = sharedPreferencesManager.sharedPreferences.edit();
        editor.putString(Constants.LANGUAGE_KEY, languageCode);
        editor.apply();
        Log.d(TAG_SETTINGS, "Language preference saved: " + languageCode);
    }


    private void setupSettingsLanguageDialog(MaterialButton chooseButton) {
        String[] languages = getResources().getStringArray(R.array.languages_array);
        String savedLanguageCode = sharedPreferencesManager.getLanguage();
        int selectedIndex = getLanguageIndex(savedLanguageCode);
        chooseButton.setText(languages[selectedIndex]);
        chooseButton.setOnClickListener(v -> {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.setting_language_title)
                .setSingleChoiceItems(languages, selectedIndex, (dialog, which) -> {
                    String newLangCode = getLanguageCode(which);
                    if (!newLangCode.equals(sharedPreferencesManager.getLanguage())) {
                        saveLanguagePreference(newLangCode);
                    if(getActivity() instanceof MainActivity){
                            ((MainActivity) requireActivity()).applyLanguage(newLangCode);
                    } else {
                        Toast.makeText(getContext(), "Language changed. Restart app to apply.", Toast.LENGTH_LONG).show();
                    }
                }
                    chooseButton.setText(languages[which]);
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.universal_cancel, null)
                .show();
        });
    }


    // Helper to map language code to spinner index
    private int getLanguageIndex(String languageCode) {
        switch (languageCode) {
            case "en": return 0;
            case "zh": return 1;
            case "ar": return 2;
            case "fr": return 3;
            case "tr": return 4;
            case "ps": return 5;
            case "in": return 6;
            case "it": return 7; // Added for Italian
            default: return 0; // Default to English if unknown
        }
    }

    // Helper to map spinner index to language code
    private String getLanguageCode(int position) {
        switch (position) {
            case 0: return "en";
            case 1: return "zh";
            case 2: return "ar";
            case 3: return "fr";
            case 4: return "tr";
            case 5: return "ps";
            case 6: return "in";
            case 7: return "it"; // Added for Italian
            default: return "en"; // Default to English on error
        }
    }


    private void setupLocationSwitch(MaterialSwitch switchView) {
        if(switchView == null) return;
        boolean isLocationEnabled = sharedPreferencesManager.isLocalisationEnabled();
        switchView.setChecked(isLocationEnabled);
        Log.d(TAG_SETTINGS,"Location switch initialized. State: " + isLocationEnabled);

        switchView.setOnCheckedChangeListener((buttonView, isChecked) -> {
            vibrateTouch();
            if (isChecked) {
                if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
                    showLocationPermissionDialog(switchView); // Pass the switch to handle denial
                } else {
                    sharedPreferencesManager.sharedPreferences.edit().putBoolean(Constants.PREF_LOCATION_DATA, true).apply();
                    Log.d(TAG_SETTINGS,"Location permission granted, setting enabled.");
                }
            } else {
                sharedPreferencesManager.sharedPreferences.edit().putBoolean(Constants.PREF_LOCATION_DATA, false).apply();
                Log.d(TAG_SETTINGS,"Location setting disabled.");
                if(locationHelper != null) locationHelper.stopLocationUpdates(); // Stop updates when disabled
            }
        });
    }


    private void showLocationPermissionDialog(MaterialSwitch switchView) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.location_permission_title))
                .setMessage(getString(R.string.location_permission_description))
                .setPositiveButton("Grant", (dialog, which) -> requestLocationPermission())
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                    // User denied permission via dialog, uncheck the switch and save pref
                    if (switchView != null) switchView.setChecked(false);
                    sharedPreferencesManager.sharedPreferences.edit().putBoolean(Constants.PREF_LOCATION_DATA, false).apply();
                    Log.d(TAG_SETTINGS,"User cancelled location permission request.");
                    dialog.dismiss();
                })
                .setCancelable(false) // Prevent dismissing without choice
                .show();
    }


    private void requestLocationPermission() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)){
            Log.w(TAG_SETTINGS,"Location permission previously denied, showing rationale dialog first.");
            // Optionally show a rationale again before requesting
            // For simplicity now, just re-request:
            ActivityCompat.requestPermissions(requireActivity(), new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_LOCATION_PERMISSION);
        } else {
            ActivityCompat.requestPermissions(requireActivity(), new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_LOCATION_PERMISSION);
            Log.d(TAG_SETTINGS,"Requesting location permission.");
        }
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_LOCATION_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                sharedPreferencesManager.sharedPreferences.edit().putBoolean(Constants.PREF_LOCATION_DATA, true).apply();
                Log.d(TAG_SETTINGS, "Location permission granted via system dialog.");
                // Re-check the switch (should already be checked if dialog was triggered by toggle)
                if (locationSwitch != null && !locationSwitch.isChecked()) locationSwitch.setChecked(true);
                if(locationHelper != null) locationHelper.startLocationUpdates(); // Start updates now
            } else {
                // User denied permission via system dialog
                sharedPreferencesManager.sharedPreferences.edit().putBoolean(Constants.PREF_LOCATION_DATA, false).apply();
                // Ensure the switch is unchecked
                if (locationSwitch != null) locationSwitch.setChecked(false);
                Log.w(TAG_SETTINGS,"Location permission denied via system dialog.");
                Toast.makeText(getContext(), "Location permission denied", Toast.LENGTH_SHORT).show();
            }
        }
        // Handle other permission results if necessary
    }


    private void setupDebugSwitch(MaterialSwitch switchView) {
        if(switchView == null) return;
        boolean isDebugEnabled = sharedPreferencesManager.isDebugLoggingEnabled();
        switchView.setChecked(isDebugEnabled);
        Log.d(TAG_SETTINGS,"Debug switch initialized. State: " + isDebugEnabled);

        switchView.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sharedPreferencesManager.sharedPreferences.edit().putBoolean(Constants.PREF_DEBUG_DATA, isChecked).apply();
            // Use standard Log - Replace com.fadcam.Log if it exists
            // Log.setDebugEnabled(isChecked); // Remove if com.fadcam.Log is removed
            vibrateTouch();
            Log.d(TAG_SETTINGS,"Debug logging preference changed to: " + isChecked);
        });
    }

    private void setupAudioSwitch(MaterialSwitch switchView) {
        if(switchView == null) return;
        boolean isAudioEnabled = sharedPreferencesManager.isRecordAudioEnabled();
        switchView.setChecked(isAudioEnabled);
        Log.d(TAG_SETTINGS,"Audio switch initialized. State: " + isAudioEnabled);

        switchView.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sharedPreferencesManager.setRecordAudioEnabled(isChecked);
            vibrateTouch();
            Log.d(TAG_SETTINGS,"Audio recording preference changed to: " + isChecked);
        });
    }


    private void openInAppBrowser(String url) {
        try {
            Intent intent = new Intent(getContext(), WebViewActivity.class);
            intent.putExtra("url", url);
            startActivity(intent);
        } catch(Exception e){
            Log.e(TAG_SETTINGS, "Could not open WebViewActivity: " + url, e);
            Toast.makeText(requireContext(), "Could not open link", Toast.LENGTH_SHORT).show();
        }
    }


    private void setupFrameRateNoteText()
    {
        TextView noteTextView = view.findViewById(R.id.framerate_note_textview);
        if (noteTextView != null) {
            noteTextView.setText(getString(R.string.note_framerate, Constants.DEFAULT_VIDEO_FRAME_RATE));
        }
    }


    private void setupCodecNoteText()
    {
        TextView noteTextView = view.findViewById(R.id.codec_note_textview);
        if(noteTextView != null) {
            VideoCodec defaultCodec = Constants.DEFAULT_VIDEO_CODEC;
            noteTextView.setText(getString(R.string.note_codec, defaultCodec != null ? defaultCodec.getName() : "N/A"));
        }
    }

    // Helper to map frame rate value to spinner index (using specific array)
    private int getVideoFrameRateIndex(int frameRate)
    {
        List<Integer> rates = getCompatiblesVideoFrameRates(sharedPreferencesManager.getCameraSelection());
        int index = rates.indexOf(frameRate);
        return Math.max(index, 0); // Return 0 if not found
    }

    // Helper to map codec value to spinner index
    private int getVideoCodecIndex(VideoCodec videoCodec)
    {
        List<VideoCodec> codecs = getCompatiblesVideoCodecs();
        int index = codecs.indexOf(videoCodec);
        return Math.max(index, 0); // Return 0 if not found
    }

    // Replace the entire existing getCompatiblesVideoResolutions method in SettingsFragment.java

    /**
     * Retrieves a list of compatible video resolutions (as Size objects)
     * for the specified camera type by using the internal profile loading helper.
     *
     * @param cameraType The camera type (FRONT or BACK) to get resolutions for.
     * @return A List of Size objects representing compatible resolutions. Returns empty list on error.
     */
    public List<Size> getCompatiblesVideoResolutions(CameraType cameraType) {
        Log.d(TAG_SETTINGS, "Getting compatible video resolutions for: " + cameraType);

        // *** CORRECTION: Call the correctly named internal helper method ***
        List<CamcorderProfile> camcorderProfiles = this.getCamcorderProfilesForTypeInternal(cameraType);
        List<Size> videoResolutionCompatibles = new ArrayList<>();

        // Perform null check on the result from the helper
        if(camcorderProfiles != null && !camcorderProfiles.isEmpty()) {
            Log.d(TAG_SETTINGS, "Processing " + camcorderProfiles.size() + " profiles from internal helper.");
            for (CamcorderProfile camcorderProfile : camcorderProfiles) {
                // Filter out null profiles just in case (belt-and-suspenders)
                if(camcorderProfile != null){
                    // Create Size object from profile dimensions
                    videoResolutionCompatibles.add(new Size(camcorderProfile.videoFrameWidth, camcorderProfile.videoFrameHeight));
                } else {
                    Log.w(TAG_SETTINGS, "Encountered a null CamcorderProfile in the list for " + cameraType);
                }
            }
        } else {
            Log.e(TAG_SETTINGS, "getCamcorderProfilesForTypeInternal returned null or empty list for " + cameraType);
            // Return empty list in this case
        }

        // Use a Set to get unique sizes, as multiple profiles might have the same resolution
        Set<Size> uniqueSizes = new HashSet<>(videoResolutionCompatibles);
        List<Size> uniqueSortedSizes = new ArrayList<>(uniqueSizes);

        // Sort the unique sizes (e.g., descending by area)
        try {
            Collections.sort(uniqueSortedSizes, (s1, s2) -> {
                long area1 = (long) s1.getWidth() * s1.getHeight();
                long area2 = (long) s2.getWidth() * s2.getHeight();
                return Long.compare(area2, area1); // Descending order
            });
        } catch (Exception e) {
            Log.e(TAG_SETTINGS,"Error sorting compatible resolutions", e);
        }


        Log.d(TAG_SETTINGS, "Returning " + uniqueSortedSizes.size() + " unique compatible resolutions for " + cameraType);
        return uniqueSortedSizes; // Return the sorted list of unique sizes
    }

    /**
     * Retrieves a list of compatible video resolutions as strings.
     */
    public List<String> getCompatiblesVideoResolutionsAsString(CameraType cameraType)
    {
        List<Size> videoResolutions = this.getCompatiblesVideoResolutions(cameraType);
        String[] resolutionKeys = getResources().getStringArray(R.array.video_resolutions_keys);
        String[] resolutionValues = getResources().getStringArray(R.array.video_resolutions_values);
        List<String> videoResolutionList = new ArrayList<>();

        for (Size videoResolution : videoResolutions) {
            videoResolutionList.add(getVideoResolutionStringBuilder(videoResolution, resolutionKeys, resolutionValues));
        }
        return videoResolutionList;
    }

    /**
     * Builds a display string for a video resolution.
     */
    @NonNull
    private static String getVideoResolutionStringBuilder(Size videoResolution, String[] resolutionKeys, String[] resolutionValues) {
        StringBuilder videoResolutionText = new StringBuilder();
        String label = null;
        String resKey = videoResolution.getWidth() + "x" + videoResolution.getHeight();

        for (int i = 0; i < resolutionKeys.length; i++) {
            if (resolutionKeys[i].equals(resKey)) {
                label = resolutionValues[i];
                break;
            }
        }
        if (label != null){
            videoResolutionText.append(label);
        }

        videoResolutionText.append(" (")
                .append(videoResolution.getWidth())
                .append("x")
                .append(videoResolution.getHeight())
                .append(")");
        return videoResolutionText.toString();
    }

    /**
     * Retrieves a list of compatible frame rates for the current camera and resolution.
     */
    private List<Integer> getCompatiblesVideoFrameRates(CameraType cameraType) {
        // Get the CamcorderProfile for the CURRENTLY selected resolution
        CamcorderProfile currentProfile = getCamcorderProfile(cameraType);
        if (currentProfile == null) {
            Log.e(TAG_SETTINGS,"Could not get profile for "+cameraType+", cannot determine compatible frame rates.");
            return Collections.singletonList(Constants.DEFAULT_VIDEO_FRAME_RATE); // Fallback
        }

        int maxSupportedRate = currentProfile.videoFrameRate;
        Log.d(TAG_SETTINGS, "Max supported rate for current resolution: " + maxSupportedRate);
        int[] allRateOptions = getResources().getIntArray(R.array.video_framerate_options);

        List<Integer> compatibleRates = new ArrayList<>();
        for (int rate : allRateOptions) {
            if (rate <= maxSupportedRate) {
                compatibleRates.add(rate);
            }
        }

        if (compatibleRates.isEmpty()) {
            Log.w(TAG_SETTINGS,"No standard frame rates compatible <= " + maxSupportedRate + ". Adding max rate itself.");
            compatibleRates.add(maxSupportedRate); // Add the max if none of the standards fit
        }
        Log.d(TAG_SETTINGS,"Compatible frame rates found: " + compatibleRates);
        return compatibleRates;
    }

    /**
     * Retrieves a list of supported video codecs on the device.
     */
    private List<VideoCodec> getCompatiblesVideoCodecs()
    {
        List<VideoCodec> videoCodecs = new ArrayList<>();
        for(VideoCodec videoCodec : VideoCodec.values()) {
            if(Utils.isCodecSupported(videoCodec.getMimeType())) {
                videoCodecs.add(videoCodec);
            }
        }
        if(videoCodecs.isEmpty()) {
            Log.e(TAG_SETTINGS, "No standard video codecs found! Defaulting.");
            videoCodecs.add(Constants.DEFAULT_VIDEO_CODEC);
        }
        return videoCodecs;
    }


    // Retrieves the video codec with the highest priority among compatible ones.
    private VideoCodec getCompatiblesVideoCodec() {
        List<VideoCodec> compatibleCodecs = getCompatiblesVideoCodecs();
        if (compatibleCodecs.isEmpty()){
            return Constants.DEFAULT_VIDEO_CODEC; // Should not happen if list isn't empty
        }

        // Simple priority check (can be made more complex if needed)
        VideoCodec highestPriorityCodec = compatibleCodecs.get(0); // Start with first
        for (VideoCodec videoCodec : compatibleCodecs) {
            // Assuming lower integer value means higher priority if getPriority defined that way
            if (videoCodec.getPriority() < highestPriorityCodec.getPriority()) {
                highestPriorityCodec = videoCodec;
            }
        }
        return highestPriorityCodec;
    }


    /**
     * Retrieves CamcorderProfiles for a given camera type.
     */
    // Make sure your getCamcorderProfile method is present and correct
    // (as used in the fallback above)


    // Internal helper renamed from the previous 'getCamcorderProfiles' to avoid conflict
    private List<CamcorderProfile> getCamcorderProfilesForTypeInternal(CameraType cameraType) {
        // --- Ensure context is available ---
        if(getContext() == null) {
            Log.e(TAG, "Context null in getCamcorderProfilesForTypeInternal for "+cameraType);
            return new ArrayList<>(); // Return empty
        }

        List<CamcorderProfile> profiles = new ArrayList<>();
        int cameraId = cameraType.getCameraId(); // Assume this correctly gets 0 or 1 etc.

        // --- Standard Qualities Array ---
        // Include common resolutions first, higher/lower later
        int[] qualities = {
                CamcorderProfile.QUALITY_1080P, CamcorderProfile.QUALITY_720P, CamcorderProfile.QUALITY_480P,
                CamcorderProfile.QUALITY_CIF, CamcorderProfile.QUALITY_QCIF, CamcorderProfile.QUALITY_LOW
        };

        // --- SDK-Specific High Resolutions (Highest first) ---
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // 8K Check (API 31+)
            if(CamcorderProfile.hasProfile(cameraId, CamcorderProfile.QUALITY_8KUHD)) profiles.add(CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_8KUHD));
        }
        if (CamcorderProfile.hasProfile(cameraId, CamcorderProfile.QUALITY_2160P)) { // 4K (API 21+)
            profiles.add(CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_2160P));
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { // 2K (QHD) Check (API 30+)
            if(CamcorderProfile.hasProfile(cameraId, CamcorderProfile.QUALITY_2K)) profiles.add(CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_2K));
        }
        // Handle QUALITY_QHD which was reused for 2K before API 30 (might be needed for older devices)
        // It's generally same resolution as QUALITY_2K but constant differs by SDK level
        // This can get complex, stick to API 30+ check for 2K for simplicity for now unless QHD devices fail

        // --- Add Standard Qualities if Available and Not Already Added ---
        Set<Integer> addedQualities = new HashSet<>(); // Keep track of qualities already added by resolution value
        for(CamcorderProfile p : profiles) { if(p!=null) addedQualities.add(p.quality); }

        for (int quality : qualities) {
            if (!addedQualities.contains(quality) && CamcorderProfile.hasProfile(cameraId, quality)) {
                CamcorderProfile profile = CamcorderProfile.get(cameraId, quality);
                if(profile != null) { // Check profile is not null
                    profiles.add(profile);
                    addedQualities.add(quality);
                }
            }
        }

        // --- Quality High/Low as Fallbacks ---
        // Often QUALITY_HIGH is redundant with 1080p/720p but add if needed
        if(!addedQualities.contains(CamcorderProfile.QUALITY_HIGH) && CamcorderProfile.hasProfile(cameraId, CamcorderProfile.QUALITY_HIGH)){
            profiles.add(CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_HIGH));
        }
        // QUALITY_LOW is often redundant with QCIF etc.
        // if(!addedQualities.contains(CamcorderProfile.QUALITY_LOW) && CamcorderProfile.hasProfile(cameraId, CamcorderProfile.QUALITY_LOW)){
        //    profiles.add(CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_LOW));
        // }


        // --- Final Cleanup ---
        profiles.removeIf(Objects::isNull); // Ensure no null profiles remain

        Log.d(TAG,"Camcorder profiles retrieved internally for "+cameraType +": "+profiles.size());
        // Optionally sort them by resolution descending here if needed?
        // Collections.sort(profiles, (p1, p2) -> Integer.compare(p2.videoFrameWidth * p2.videoFrameHeight, p1.videoFrameWidth * p1.videoFrameHeight));
        return profiles;
    }

    /**
     * Retrieves the selected CamcorderProfile based on saved resolution preferences.
     * Used as a fallback when AE ranges aren't available for FPS determination.
     */
    // Replace the existing getCamcorderProfile method in SettingsFragment.java with this complete version:

    /**
     * Retrieves the selected CamcorderProfile based on saved resolution preferences
     * for the given CameraType. Used as a fallback when AE ranges aren't available
     * for FPS determination or when specific profile info is needed.
     *
     * @param cameraType The camera type (FRONT or BACK) to get the profile for.
     * @return The matching CamcorderProfile, or a fallback (like QUALITY_HIGH), or null if errors occur.
     */
    private CamcorderProfile getCamcorderProfile(CameraType cameraType) {
        // Ensure SharedPreferencesManager is initialized
        if (sharedPreferencesManager == null) {
            // Try initializing if context is available
            if (getContext() != null) {
                sharedPreferencesManager = SharedPreferencesManager.getInstance(requireContext());
            }
            // Still null? Log error and prepare for basic fallback
            if (sharedPreferencesManager == null) {
                Log.e(TAG, "getCamcorderProfile: SharedPreferencesManager is null, context likely missing.");
                // Determine Camera ID safely even if context is gone initially
                int camId = (cameraType != null) ? cameraType.getCameraId() : CameraType.BACK.getCameraId();
                try {
                    Log.w(TAG, "Falling back to default HIGH quality profile due to missing SharedPrefsManager.");
                    return CamcorderProfile.get(camId, CamcorderProfile.QUALITY_HIGH); // Basic fallback
                } catch (Exception e) {
                    Log.e(TAG, "Failed to get even default fallback profile", e);
                    return null; // Return null if even basic fails
                }
            }
        }

        // Get saved resolution preferences using the SharedPreferencesManager
        int savedWidth = sharedPreferencesManager.getCameraResolution().getWidth();
        int savedHeight = sharedPreferencesManager.getCameraResolution().getHeight();
        Log.d(TAG,"getCamcorderProfile: Looking for profile matching " + savedWidth + "x" + savedHeight + " for " + cameraType);

        // Ensure camcorderProfilesAvailables map is populated
        // Check if the map exists AND contains the key for the requested type
        if (camcorderProfilesAvailables == null || !camcorderProfilesAvailables.containsKey(cameraType)){
            Log.w(TAG,"getCamcorderProfile: Camcorder profiles map not initialized or missing key for " + cameraType + ". Re-initializing.");
            initializeCamcorderProfiles(); // Make sure this method populates the map correctly
            // Check again after init
            if(camcorderProfilesAvailables == null || !camcorderProfilesAvailables.containsKey(cameraType)){
                Log.e(TAG,"Still no profiles after re-init for " + cameraType + ". Cannot get specific profile, using fallback.");
                // Fallback to default HIGH if initialization failed or still no profiles
                try {
                    return CamcorderProfile.get(cameraType.getCameraId(), CamcorderProfile.QUALITY_HIGH);
                } catch (Exception e){
                    Log.e(TAG, "Failed getting HIGH quality fallback after re-init failed for " + cameraType, e);
                    return null;
                }
            }
        }

        // Get the list of profiles for the specified camera type
        List<CamcorderProfile> profiles = camcorderProfilesAvailables.get(cameraType);
        if (profiles == null || profiles.isEmpty()) {
            Log.e(TAG, "No profiles available in map for " + cameraType + " even after check. Cannot get specific profile, using fallback.");
            // Fallback to default HIGH quality if list is empty
            try {
                return CamcorderProfile.get(cameraType.getCameraId(), CamcorderProfile.QUALITY_HIGH);
            } catch (Exception e){
                Log.e(TAG, "Failed getting HIGH quality fallback for empty profile list for " + cameraType, e);
                return null;
            }
        }

        // Iterate through the available profiles to find a match for the saved resolution
        for (CamcorderProfile profile : profiles) {
            // Important: Check profile is not null before accessing its members
            if (profile != null && profile.videoFrameWidth == savedWidth && profile.videoFrameHeight == savedHeight) {
                Log.d(TAG,"Found matching CamcorderProfile for " + savedWidth + "x" + savedHeight);
                return profile; // Return the matching profile
            }
        }

        // If saved resolution not found in available profiles, return the first available profile as a fallback
        Log.w(TAG,"Saved resolution "+savedWidth+"x"+savedHeight+ " not found in profile list for " + cameraType + ". Returning first available profile as fallback.");
        if (!profiles.isEmpty() && profiles.get(0) != null) {
            return profiles.get(0); // Return the first non-null profile in the list
        } else {
            Log.e(TAG,"First profile in list is null or list empty after search for "+ cameraType +". Final fallback.");
            // Final fallback if even the first profile was null or list was empty unexpectedly
            try {
                return CamcorderProfile.get(cameraType.getCameraId(), CamcorderProfile.QUALITY_HIGH);
            } catch (Exception e) {
                Log.e(TAG, "Failed getting final HIGH quality fallback for " + cameraType, e);
                return null; // Return null if all fallbacks fail
            }
        }
    } // End of getCamcorderProfile method
    /**
     * Gets the index of the CamcorderProfile matching saved preferences.
     */
    private int getCamcorderProfileIndexPreferences(CameraType cameraType)
    {
        int savedWidth = sharedPreferencesManager.sharedPreferences.getInt(Constants.PREF_VIDEO_RESOLUTION_WIDTH, Constants.DEFAULT_VIDEO_RESOLUTION.getWidth());
        int savedHeight = sharedPreferencesManager.sharedPreferences.getInt(Constants.PREF_VIDEO_RESOLUTION_HEIGHT, Constants.DEFAULT_VIDEO_RESOLUTION.getHeight());

        List<CamcorderProfile> profiles = camcorderProfilesAvailables.get(cameraType);
        if (profiles == null || profiles.isEmpty()) {
            return 0; // Default to first index if no profiles
        }

        for (int i = 0; i < profiles.size(); i++) {
            CamcorderProfile profile = profiles.get(i);
            if (profile != null && profile.videoFrameWidth == savedWidth && profile.videoFrameHeight == savedHeight) {
                return i; // Found matching profile
            }
        }
        return 0; // Default to first index if saved resolution not found
    }


    // --- Theme Spinner Logic ---
    private void setupThemeSpinner(View view) {
        themeSpinner = view.findViewById(R.id.theme_spinner);
        if (themeSpinner == null) {
            Log.e(TAG_SETTINGS, "Theme spinner not found!");
            return;
        }
        String[] themeOptions = {"Dark Mode", "AMOLED Black"};
        ArrayAdapter<String> themeAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, themeOptions);
        themeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        themeSpinner.setAdapter(themeAdapter);

        String currentTheme = sharedPreferencesManager.sharedPreferences.getString(Constants.PREF_APP_THEME, "Dark Mode");
        int currentThemeIndex = Arrays.asList(themeOptions).indexOf(currentTheme);
        themeSpinner.setSelection(currentThemeIndex >= 0 ? currentThemeIndex : 0); // Select current or default
        Log.d(TAG_SETTINGS,"Theme spinner set to index: " + themeSpinner.getSelectedItemPosition());

        themeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selectedTheme = themeOptions[position];
                String currentSavedTheme = sharedPreferencesManager.sharedPreferences.getString(Constants.PREF_APP_THEME, "Dark Mode");

                if (!selectedTheme.equals(currentSavedTheme)) {
                    Log.d(TAG_SETTINGS, "Theme selection changed to: " + selectedTheme);
                    sharedPreferencesManager.sharedPreferences.edit().putString(Constants.PREF_APP_THEME, selectedTheme).apply();
                    vibrateTouch();
                    // Prompt user to restart
                    new MaterialAlertDialogBuilder(requireContext())
                            .setTitle("Theme Change")
                            .setMessage("Restart the app to apply the new theme?")
                            .setPositiveButton("Restart", (dialog, which) -> {
                                Intent intent = requireActivity().getPackageManager()
                                        .getLaunchIntentForPackage(requireActivity().getPackageName());
                                if (intent != null) {
                                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                                    startActivity(intent);
                                    requireActivity().finishAffinity(); // Close all activities of the app
                                }
                            })
                            .setNegativeButton("Later", null)
                            .show();
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }
    // --- End Theme Spinner Logic ---


    /**
     * Queries the Camera2 API to get supported standard FPS ranges for a given camera TYPE (FRONT/BACK)
     * and returns a filtered list of common, usable frame rates defined in arrays.xml.
     *
     * @param cameraType The camera TYPE (FRONT or BACK) to query. Queries the primary ID for that type.
     * @return A sorted List<Integer> of supported frame rates from R.array.video_framerate_options. Returns default [30] on error.
     */
    /**
     * Queries the Camera2 API for supported FPS ranges for the primary camera of the specified type
     * and returns a filtered list of frame rates from R.array.video_framerate_options that are supported.
     *
     * @param cameraType The camera type (FRONT or BACK) to query.
     * @return A sorted List<Integer> of supported frame rates. Returns default [30] on critical errors.
     */
    private List<Integer> getHardwareSupportedFrameRates(CameraType cameraType) {
        Log.i(TAG_SETTINGS, "=== Getting Hardware Supported FPS for CameraType: " + cameraType + " ===");
        final List<Integer> defaultRateList = Collections.singletonList(Constants.DEFAULT_VIDEO_FRAME_RATE);

        if (getContext() == null) {
            Log.e(TAG_SETTINGS, "FPS Query: Context is null.");
            return defaultRateList;
        }

        CameraManager manager = (CameraManager) requireContext().getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) {
            Log.e(TAG_SETTINGS, "FPS Query: CameraManager is null.");
            return defaultRateList;
        }

        String targetCameraId = null;
        try {
            // Find the primary camera ID for the requested type (Prioritize ID "0" for BACK)
            String firstBackIdFallback = null;
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null) {
                    if (cameraType == CameraType.FRONT && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                        targetCameraId = id;
                        Log.d(TAG_SETTINGS,"FPS Query: Found FRONT camera ID: " + targetCameraId);
                        break;
                    }
                    if (cameraType == CameraType.BACK && facing == CameraCharacteristics.LENS_FACING_BACK) {
                        if (id.equals(Constants.DEFAULT_BACK_CAMERA_ID)) {
                            targetCameraId = id; // Found preferred default BACK ID "0"
                            Log.d(TAG_SETTINGS,"FPS Query: Found Primary BACK camera ID: " + targetCameraId);
                            break;
                        } else if (firstBackIdFallback == null) {
                            firstBackIdFallback = id; // Store first BACK ID encountered as fallback
                        }
                    }
                }
            }
            // If default BACK "0" wasn't found, use the fallback if available
            if (cameraType == CameraType.BACK && targetCameraId == null && firstBackIdFallback != null){
                targetCameraId = firstBackIdFallback;
                Log.w(TAG_SETTINGS,"FPS Query: Default Back ID '0' not found/back-facing. Using first available back ID: " + targetCameraId);
            }
        } catch (CameraAccessException | IllegalArgumentException e) {
            Log.e(TAG_SETTINGS, "FPS Query: Error accessing camera list/characteristics during ID selection", e);
            return defaultRateList;
        }

        if (targetCameraId == null) {
            Log.e(TAG_SETTINGS, "FPS Query: Could not find a valid Camera ID for type: " + cameraType);
            return defaultRateList;
        }
        Log.d(TAG_SETTINGS, "FPS Query: Using Camera ID: " + targetCameraId + " for characteristic lookup.");

        // Get the available AE FPS ranges for the target camera
        Range<Integer>[] hardwareFpsRanges = null;
        try {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(targetCameraId);
            hardwareFpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        } catch (CameraAccessException | IllegalArgumentException e) {
            Log.e(TAG_SETTINGS, "FPS Query: Camera access/arg exception getting FPS ranges for ID " + targetCameraId, e);
            // Return default [30] on error accessing ranges
            return defaultRateList;
        } catch (Exception e){
            Log.e(TAG_SETTINGS, "FPS Query: Unexpected error getting FPS ranges for ID " + targetCameraId, e);
            return defaultRateList;
        }

        if (hardwareFpsRanges == null || hardwareFpsRanges.length == 0) {
            Log.w(TAG_SETTINGS, "FPS Query: No AE FPS ranges reported by hardware for camera " + targetCameraId + ". Checking profile.");
            // Fallback: Use the rate from the CamcorderProfile for the *current* resolution setting
            CamcorderProfile profile = getCamcorderProfile(cameraType); // Make sure this returns a valid profile or fallback
            if (profile != null) {
                Log.d(TAG_SETTINGS, "FPS Query: Using fallback rate from CamcorderProfile: " + profile.videoFrameRate);
                return Collections.singletonList(profile.videoFrameRate); // Return only the profile rate
            } else {
                Log.e(TAG_SETTINGS, "FPS Query: Both AE Ranges and CamcorderProfile failed. Returning default [30].");
                return defaultRateList; // Ultimate fallback
            }
        }

        Log.d(TAG_SETTINGS, "FPS Query: Hardware reported AE ranges for ID " + targetCameraId + ": " + Arrays.toString(hardwareFpsRanges));

        // Filter the predefined options based on hardware ranges
        Set<Integer> hardwareMaxFpsValues = new HashSet<>();
        for (Range<Integer> range : hardwareFpsRanges) {
            if (range != null && range.getUpper() != null) {
                hardwareMaxFpsValues.add(range.getUpper());
            }
        }

        List<Integer> finalSupportedRates = new ArrayList<>();
        int[] predefinedOptions = getResources().getIntArray(R.array.video_framerate_options);

        for (int option : predefinedOptions) {
            // An option is supported if it's one of the max values reported by hardware AND in our predefined list.
            if (hardwareMaxFpsValues.contains(option)) {
                finalSupportedRates.add(option);
                Log.v(TAG_SETTINGS,"FPS Query: Adding option " + option + " as it's a hardware max and predefined.");
            }
        }


        // Ensure default rate is present if possible, even if not in predefined options exactly
        if (!finalSupportedRates.contains(Constants.DEFAULT_VIDEO_FRAME_RATE)) {
            boolean defaultSupportedByHardware = false;
            for (Range<Integer> range : hardwareFpsRanges) {
                if(range != null && range.getUpper() != null && Constants.DEFAULT_VIDEO_FRAME_RATE <= range.getUpper()) {
                    defaultSupportedByHardware = true; break;
                }
            }
            if(defaultSupportedByHardware) {
                Log.d(TAG_SETTINGS, "FPS Query: Adding default rate " + Constants.DEFAULT_VIDEO_FRAME_RATE + " because it's supported by hardware but wasn't in final list.");
                finalSupportedRates.add(Constants.DEFAULT_VIDEO_FRAME_RATE);
            }
        }


        // If the list is STILL empty after filtering, add the default 30fps as a last resort
        if (finalSupportedRates.isEmpty()) {
            Log.e(TAG_SETTINGS, "FPS Query: No predefined rates found matching hardware ranges! Adding default: " + Constants.DEFAULT_VIDEO_FRAME_RATE);
            finalSupportedRates.add(Constants.DEFAULT_VIDEO_FRAME_RATE);
        }

        // Ensure the final list is sorted numerically
        Collections.sort(finalSupportedRates);

        Log.i(TAG_SETTINGS, "=== Final Supported FPS options for " + cameraType + " (ID: " + targetCameraId + "): " + finalSupportedRates + " ===");
        return finalSupportedRates;
    }

    // --- Audio Settings Dialog ---
    private void showAudioSettingsDialog() {
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        View dialogView = inflater.inflate(R.layout.dialog_audio_settings, null);
        final TextView summaryText = dialogView.findViewById(R.id.audio_settings_summary);
        final TextView bitrateLabel = dialogView.findViewById(R.id.audio_bitrate_label);
        final TextView samplingRateLabel = dialogView.findViewById(R.id.audio_sampling_rate_label);
        final android.widget.EditText bitrateInput = dialogView.findViewById(R.id.audio_bitrate_input);
        final android.widget.EditText samplingRateInput = dialogView.findViewById(R.id.audio_sampling_rate_input);
        final MaterialButton resetButton = dialogView.findViewById(R.id.audio_reset_button);
        final TextView bitrateError = dialogView.findViewById(R.id.audio_bitrate_error);
        final TextView samplingRateError = dialogView.findViewById(R.id.audio_sampling_rate_error);

        int currentBitrate = sharedPreferencesManager.getAudioBitrate();
        int currentSamplingRate = sharedPreferencesManager.getAudioSamplingRate();
        bitrateInput.setText(String.valueOf(currentBitrate));
        samplingRateInput.setText(String.valueOf(currentSamplingRate));
        summaryText.setText(getString(R.string.dialog_audio_settings_summary));
        bitrateLabel.setText(getString(R.string.dialog_audio_bitrate_label));
        samplingRateLabel.setText(getString(R.string.dialog_audio_sampling_rate_label));

        // Helper for validation
        class ValidationState {
            boolean bitrateValid = true;
            boolean samplingValid = true;
        }
        ValidationState validation = new ValidationState();

        // Color helpers
        int errorColor = ContextCompat.getColor(requireContext(), android.R.color.holo_red_light);
        int validColor = ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark);
        int normalColor = ContextCompat.getColor(requireContext(), android.R.color.transparent);

        // Validation logic
        Runnable validate = () -> {
            String bitrateStr = bitrateInput.getText().toString().trim();
            String samplingStr = samplingRateInput.getText().toString().trim();
            validation.bitrateValid = false;
            validation.samplingValid = false;

            // Bitrate validation
            try {
                int bitrate = Integer.parseInt(bitrateStr);
                if (bitrate >= 64000 && bitrate <= 384000) {
                    bitrateError.setVisibility(View.GONE);
                    bitrateInput.setBackgroundColor(validColor);
                    validation.bitrateValid = true;
                } else {
                    bitrateError.setText(getString(R.string.dialog_audio_invalid_bitrate));
                    bitrateError.setVisibility(View.VISIBLE);
                    bitrateInput.setBackgroundColor(errorColor);
                }
            } catch (Exception e) {
                bitrateError.setText(getString(R.string.dialog_audio_invalid_bitrate));
                bitrateError.setVisibility(View.VISIBLE);
                bitrateInput.setBackgroundColor(errorColor);
            }

            // Sampling rate validation
            try {
                int sampling = Integer.parseInt(samplingStr);
                if (sampling == 44100 || sampling == 48000) {
                    samplingRateError.setVisibility(View.GONE);
                    samplingRateInput.setBackgroundColor(validColor);
                    validation.samplingValid = true;
                } else {
                    samplingRateError.setText(getString(R.string.dialog_audio_invalid_sampling_rate));
                    samplingRateError.setVisibility(View.VISIBLE);
                    samplingRateInput.setBackgroundColor(errorColor);
                }
            } catch (Exception e) {
                samplingRateError.setText(getString(R.string.dialog_audio_invalid_sampling_rate));
                samplingRateError.setVisibility(View.VISIBLE);
                samplingRateInput.setBackgroundColor(errorColor);
            }
        };

        bitrateInput.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { validate.run(); }
            public void afterTextChanged(android.text.Editable s) {}
        });
        samplingRateInput.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { validate.run(); }
            public void afterTextChanged(android.text.Editable s) {}
        });

        resetButton.setOnClickListener(v -> {
            bitrateInput.setText("192000");
            samplingRateInput.setText("48000");
        });

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext());
        builder.setTitle(R.string.dialog_audio_settings_title);
        builder.setView(dialogView);
        builder.setPositiveButton(R.string.dialog_audio_save, null); // We'll override this
        builder.setNegativeButton(R.string.dialog_audio_cancel, null);
        final androidx.appcompat.app.AlertDialog dialog = builder.create();
        dialog.setOnShowListener(dlg -> {
            final android.widget.Button saveBtn = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE);
            validate.run();
            saveBtn.setEnabled(validation.bitrateValid && validation.samplingValid);
            // Live enable/disable
            android.text.TextWatcher watcher = new android.text.TextWatcher() {
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    validate.run();
                    saveBtn.setEnabled(validation.bitrateValid && validation.samplingValid);
                }
                public void afterTextChanged(android.text.Editable s) {}
            };
            bitrateInput.addTextChangedListener(watcher);
            samplingRateInput.addTextChangedListener(watcher);
            saveBtn.setOnClickListener(v -> {
                validate.run();
                if (validation.bitrateValid && validation.samplingValid) {
                    int bitrate = Integer.parseInt(bitrateInput.getText().toString().trim());
                    int sampling = Integer.parseInt(samplingRateInput.getText().toString().trim());
                    sharedPreferencesManager.setAudioBitrate(bitrate);
                    sharedPreferencesManager.setAudioSamplingRate(sampling);
                    Log.i(TAG_SETTINGS, "Audio settings saved: bitrate=" + bitrate + ", samplingRate=" + sampling);
                    dialog.dismiss();
                }
            });
        });
        dialog.show();
    }

    private void setupOrientationSpinner() {
        if (orientationSpinner == null) return;
        String[] orientationOptions = new String[] {
            getString(R.string.video_orientation_portrait),
            getString(R.string.video_orientation_landscape)
        };
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, orientationOptions);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        orientationSpinner.setAdapter(adapter);

        // Set selection based on saved preference
        String savedOrientation = sharedPreferencesManager.getVideoOrientation();
        int selectedIndex = savedOrientation.equals(SharedPreferencesManager.ORIENTATION_LANDSCAPE) ? 1 : 0;
        orientationSpinner.setSelection(selectedIndex);

        orientationSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String newOrientation = (position == 1) ? SharedPreferencesManager.ORIENTATION_LANDSCAPE : SharedPreferencesManager.ORIENTATION_PORTRAIT;
                if (!sharedPreferencesManager.getVideoOrientation().equals(newOrientation)) {
                    sharedPreferencesManager.setVideoOrientation(newOrientation);
                    Log.d(TAG_SETTINGS, "Video orientation preference changed to: " + newOrientation);
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    // --- Video Bitrate Dialog ---
    private void showVideoBitrateDialog() {
        Context context = requireContext();
        LayoutInflater inflater = LayoutInflater.from(context);
        // Custom layout for dialog
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * context.getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);

        final EditText input = new EditText(context);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setHint("1 - 200 Mbps");
        int currentMbps = getBitrateCustomValue() / 1000;
        input.setText(String.valueOf(currentMbps));
        input.setSelection(input.getText().length());
        // Limit input to max 200
        input.setFilters(new android.text.InputFilter[]{
            new android.text.InputFilter.LengthFilter(3),
            (source, start, end, dest, dstart, dend) -> {
                try {
                    String result = dest.toString().substring(0, dstart) + source + dest.toString().substring(dend);
                    if (result.isEmpty()) return null;
                    int value = Integer.parseInt(result);
                    if (value > 200) return "";
                } catch (Exception ignored) {}
                return null;
            }
        });

        final TextView helper = new TextView(context);
        helper.setTextSize(14);
        helper.setPadding(0, padding / 2, 0, 0);
        helper.setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray));
        layout.addView(input);
        layout.addView(helper);

        // Helper update logic
        Runnable updateHelper = () -> {
            String text = input.getText().toString().trim();
            int color = ContextCompat.getColor(context, android.R.color.darker_gray);
            String msg = context.getString(R.string.bitrate_info_ok);
            try {
                int value = Integer.parseInt(text);
                if (value < 3) {
                    msg = context.getString(R.string.bitrate_info_warning_low);
                    color = ContextCompat.getColor(context, android.R.color.holo_orange_light);
                } else if (value > 100) {
                    msg = context.getString(R.string.bitrate_info_warning_high);
                    color = ContextCompat.getColor(context, android.R.color.holo_red_light);
                } else {
                    msg = context.getString(R.string.bitrate_info_ok);
                    color = ContextCompat.getColor(context, android.R.color.holo_green_dark);
                }
            } catch (Exception e) {
                msg = context.getString(R.string.bitrate_info_ok);
            }
            helper.setText(msg);
            helper.setTextColor(color);
        };
        input.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { updateHelper.run(); }
            public void afterTextChanged(android.text.Editable s) {}
        });
        updateHelper.run();

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                .setTitle(getString(R.string.setting_video_bitrate_title))
                .setMessage(getString(R.string.bitrate_explanation_text))
                .setView(layout)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String text = input.getText().toString().trim();
                    try {
                        int value = Integer.parseInt(text);
                        if (value < 1 || value > 200) {
                            Toast.makeText(context, getString(R.string.bitrate_invalid, 1, 200), Toast.LENGTH_LONG).show();
                        } else {
                            setBitrateCustomValue(value * 1000); // Store as kbps
                            setBitrateMode(true);
                            updateBitrateInfoAndHelper();
                            Toast.makeText(context, R.string.bitrate_save_success, Toast.LENGTH_SHORT).show();
                        }
                    } catch (Exception e) {
                        Toast.makeText(context, getString(R.string.bitrate_invalid, 1, 200), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.bitrate_reset_button, (dialog, which) -> {
                    setBitrateMode(false);
                    setBitrateCustomValue(getDefaultBitrate());
                    updateBitrateInfoAndHelper();
                    Toast.makeText(context, R.string.bitrate_reset_success, Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    // --- Bitrate Preference Helpers ---
    private boolean getBitrateMode() {
        // false = default, true = custom
        return sharedPreferencesManager.sharedPreferences.getBoolean("bitrate_mode_custom", false);
    }
    private void setBitrateMode(boolean custom) {
        sharedPreferencesManager.sharedPreferences.edit().putBoolean("bitrate_mode_custom", custom).apply();
    }
    private int getBitrateCustomValue() {
        return sharedPreferencesManager.sharedPreferences.getInt("bitrate_custom_value", getDefaultBitrate());
    }
    private String getBitrateCustomValueString() {
        int v = getBitrateCustomValue();
        return v > 0 ? String.valueOf(v) : "";
    }
    private void setBitrateCustomValue(int value) {
        sharedPreferencesManager.sharedPreferences.edit().putInt("bitrate_custom_value", value).apply();
    }
    private int getDefaultBitrate() {
        // Example: return based on resolution/framerate
        CamcorderProfile profile = getCamcorderProfile(sharedPreferencesManager.getCameraSelection());
        if (profile != null) return profile.videoBitRate / 1000; // Convert to kbps
        return 16000; // Fallback 16 Mbps
    }
    private int getCurrentBitrate() {
        return getBitrateMode() ? getBitrateCustomValue() : getDefaultBitrate();
    }
    // --- Call this in onCreateView and after resolution/framerate changes ---
    // ...existing code...
    private void onResolutionOrFramerateChanged() {
        updateBitrateInfoAndHelper();
    }
    // In setupResolutionSpinner and setupFrameRateSpinner, after saving new value, call onResolutionOrFramerateChanged()
    // ...existing code...
    // Updates the bitrate info and helper text in the UI
    private void updateBitrateInfoAndHelper() {
        if (bitrateInfoTextView == null || bitrateHelperTextView == null) return;
        int bitrate = getCurrentBitrate();
        boolean isCustom = getBitrateMode();
        String info;
        int color = ContextCompat.getColor(requireContext(), android.R.color.darker_gray);
        if (isCustom) {
            info = getString(R.string.bitrate_info_custom, bitrate / 1000);
            if (bitrate < 3000) {
                bitrateHelperTextView.setText(getString(R.string.bitrate_info_warning_low));
                color = ContextCompat.getColor(requireContext(), android.R.color.holo_orange_light);
            } else if (bitrate > 100000) {
                bitrateHelperTextView.setText(getString(R.string.bitrate_info_warning_high));
                color = ContextCompat.getColor(requireContext(), android.R.color.holo_red_light);
            } else {
                bitrateHelperTextView.setText(getString(R.string.bitrate_info_ok));
                color = ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark);
            }
        } else {
            info = getString(R.string.bitrate_info_default, bitrate / 1000);
            bitrateHelperTextView.setText(getString(R.string.bitrate_helper_text));
        }
        bitrateInfoTextView.setText(info);
        bitrateHelperTextView.setTextColor(color);
    }
    // ...existing code...

    // ----- Fix Start for this class (SettingsFragment_video_splitting_methods) -----
    private void setupVideoSplittingSection() {
        if (videoSplittingSwitch == null || videoSplitSizeLayout == null || videoSplitSizeValueTextView == null || sharedPreferencesManager == null) {
            Log.e(TAG_SETTINGS, "Video splitting UI elements or SharedPreferencesManager not initialized.");
            return;
        }

        boolean isSplittingEnabled = sharedPreferencesManager.isVideoSplittingEnabled();
        videoSplittingSwitch.setChecked(isSplittingEnabled);
        videoSplitSizeLayout.setVisibility(isSplittingEnabled ? View.VISIBLE : View.GONE);
        updateVideoSplitSizeSummary(); // Update summary TextView

        videoSplittingSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            vibrateTouch();
            sharedPreferencesManager.setVideoSplittingEnabled(isChecked);
            videoSplitSizeLayout.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            Log.d(TAG_SETTINGS, "Video splitting enabled: " + isChecked);
            // When disabling, the summary will naturally be hidden by the layout's visibility change.
        });

        videoSplitSizeLayout.setOnClickListener(v -> {
            vibrateTouch();
            showVideoSplitSizeDialog();
        });
    }

    private void updateVideoSplitSizeSummary() {
        if (sharedPreferencesManager == null || videoSplitSizeValueTextView == null) return;

        boolean isEnabled = sharedPreferencesManager.isVideoSplittingEnabled();
        if (isEnabled) {
            int splitSizeMb = sharedPreferencesManager.getVideoSplitSizeMb();
            String summaryText;
            if (splitSizeMb == 500) summaryText = "Current: 500 MB";
            else if (splitSizeMb == 1024) summaryText = "Current: 1 GB";
            else if (splitSizeMb == 2048) summaryText = "Current: 2 GB (Recommended)";
            else if (splitSizeMb == 4096) summaryText = "Current: 4 GB";
            else if (splitSizeMb > 0) { // Custom value
                summaryText = String.format(Locale.getDefault(), "Current: Custom (%d MB)", splitSizeMb);
            }
            else summaryText = "Current: 2 GB (Recommended)"; // Default if somehow invalid but enabled
            videoSplitSizeValueTextView.setText(summaryText);
        } else {
            videoSplitSizeValueTextView.setText("Disabled"); // Or some other placeholder
        }
    }

    private void showVideoSplitSizeDialog() {
        if (getContext() == null || sharedPreferencesManager == null) return;

        final String[] items = {"500 MB", "1 GB", "2 GB (Recommended)", "4 GB", "Custom Size..."};
        final int[] valuesMb = {500, 1024, 2048, 4096, -1}; // -1 for custom

        int currentSizeMb = sharedPreferencesManager.getVideoSplitSizeMb();
        int checkedItem = -1;
        for (int i = 0; i < valuesMb.length -1; i++) { // Exclude "Custom Size..."
            if (valuesMb[i] == currentSizeMb) {
                checkedItem = i;
                break;
            }
        }
        // If no preset matches, and it's not one of the standard values, it's custom
        if (checkedItem == -1 && currentSizeMb > 0 &&
            currentSizeMb != 500 && currentSizeMb != 1024 &&
            currentSizeMb != 2048 && currentSizeMb != 4096) {
            // No direct preset matches, if a custom value is set, don't pre-select a preset.
            // Or, could select the "Custom Size..." item if we wanted to be more explicit.
        }


        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Set Video Split Size")
                // .setMessage("Videos will be split into segments once the selected size is reached. New segments are automatically started (e.g., video_001.mp4, video_002.mp4).") // Temporarily commented out
                .setSingleChoiceItems(items, checkedItem, (dialog, which) -> {
                    if (which == items.length - 1) { // "Custom Size..."
                        dialog.dismiss();
                        showCustomSplitSizeDialog();
                    } else {
                        sharedPreferencesManager.setVideoSplitSizeMb(valuesMb[which]);
                        updateVideoSplitSizeSummary();
                        dialog.dismiss();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showCustomSplitSizeDialog() {
        if (getContext() == null || sharedPreferencesManager == null) return;

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext());
        builder.setTitle("Custom Split Size (MB)");

        // Inflate custom layout
        LayoutInflater inflater = requireActivity().getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_custom_split_size, null); // You'll need to create this layout
        builder.setView(dialogView);

        final com.google.android.material.textfield.TextInputEditText inputEditText = dialogView.findViewById(R.id.custom_split_size_edittext); // ID in your dialog_custom_split_size.xml
        final com.google.android.material.textview.MaterialTextView errorTextView = dialogView.findViewById(R.id.custom_split_size_error_textview); // ID in your dialog_custom_split_size.xml

        int currentCustomSize = sharedPreferencesManager.getVideoSplitSizeMb();
        // If current value is one of the presets, default to 2048 for custom, otherwise use current custom.
        if (currentCustomSize == 500 || currentCustomSize == 1024 || currentCustomSize == 2048 || currentCustomSize == 4096) {
            inputEditText.setText(String.valueOf(2048));
        } else if (currentCustomSize > 0) {
            inputEditText.setText(String.valueOf(currentCustomSize));
        } else {
            inputEditText.setText(String.valueOf(2048)); // Default if no valid custom size previously
        }
        inputEditText.requestFocus(); // Show keyboard


        builder.setPositiveButton("OK", (dialog, which) -> {
            // This listener will be overridden to prevent closing on invalid input
        });
        builder.setNegativeButton("Cancel", null);

        AlertDialog alertDialog = builder.create();

        inputEditText.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(android.text.Editable s) {
                validateCustomSplitSizeInput(s.toString(), errorTextView, alertDialog.getButton(AlertDialog.BUTTON_POSITIVE));
            }
        });


        alertDialog.setOnShowListener(dialogInterface -> {
            Button positiveButton = alertDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            // Initial validation
            validateCustomSplitSizeInput(inputEditText.getText().toString(), errorTextView, positiveButton);

            positiveButton.setOnClickListener(view -> {
                String inputText = inputEditText.getText().toString();
                if (validateCustomSplitSizeInput(inputText, errorTextView, positiveButton)) {
                    try {
                        int sizeMb = Integer.parseInt(inputText);
                        sharedPreferencesManager.setVideoSplitSizeMb(sizeMb);
                        updateVideoSplitSizeSummary();
                        alertDialog.dismiss();
                    } catch (NumberFormatException e) {
                        // Should be caught by validation, but as a safeguard
                        errorTextView.setText("Invalid number format.");
                        errorTextView.setVisibility(View.VISIBLE);
                        positiveButton.setEnabled(false);
                    }
                }
            });
        });

        alertDialog.show();
    }

    private boolean validateCustomSplitSizeInput(String input, MaterialTextView errorTextView, Button positiveButton) {
        final int MIN_SIZE_MB = 10; // Min 10 MB
        final int MAX_SIZE_MB = 1024 * 100; // Max 100 GB (102400 MB)

        if (input.isEmpty()) {
            errorTextView.setText("Value cannot be empty.");
            errorTextView.setVisibility(View.VISIBLE);
            if (positiveButton != null) positiveButton.setEnabled(false);
            return false;
        }

        try {
            int value = Integer.parseInt(input);
            if (value < MIN_SIZE_MB) {
                errorTextView.setText(String.format(Locale.US, "Minimum size is %d MB.", MIN_SIZE_MB));
                errorTextView.setVisibility(View.VISIBLE);
                if (positiveButton != null) positiveButton.setEnabled(false);
                return false;
            } else if (value > MAX_SIZE_MB) {
                errorTextView.setText(String.format(Locale.US, "Maximum size is %d MB (100 GB).", MAX_SIZE_MB));
                errorTextView.setVisibility(View.VISIBLE);
                if (positiveButton != null) positiveButton.setEnabled(false);
                return false;
            }
            errorTextView.setVisibility(View.GONE);
            if (positiveButton != null) positiveButton.setEnabled(true);
            return true;
        } catch (NumberFormatException e) {
            errorTextView.setText("Invalid number format.");
            errorTextView.setVisibility(View.VISIBLE);
            if (positiveButton != null) positiveButton.setEnabled(false);
            return false;
        }
    }
    // ----- Fix Ended for this class (SettingsFragment_video_splitting_methods) -----

    @Override
    protected boolean onBackPressed() {
        // Check if we have any dialogs open that should be closed first
        if (isAnyDialogShowing()) {
            dismissOpenDialogs();
            return true;
        }
        
        // Let the default implementation handle it
        return false;
    }
    
    // Helper method to check if any dialogs are showing
    private boolean isAnyDialogShowing() {
        // Implement this based on your dialog management
        return false;
    }
    
    // Helper method to dismiss open dialogs
    private void dismissOpenDialogs() {
        // Implement this based on your dialog management
    }

}