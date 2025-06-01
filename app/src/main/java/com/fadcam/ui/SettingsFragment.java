package com.fadcam.ui;

import static android.content.ContentValues.TAG;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
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
import android.widget.ImageView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
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

// Add AppLock imports
import com.guardanis.applock.AppLock;
import com.guardanis.applock.dialogs.LockCreationDialogBuilder;
import com.guardanis.applock.dialogs.UnlockDialogBuilder;
import com.guardanis.applock.services.PINLockService;

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
import java.util.concurrent.TimeUnit;

import android.content.Intent; // Add Intent import
import androidx.localbroadcastmanager.content.LocalBroadcastManager; // OR use ContextCompat if not using LocalBroadcastManager
import androidx.core.content.ContextCompat; // If using standard broadcast

// ----- Fix Start for this class (SettingsFragment_video_splitting_imports) -----
import android.text.Editable;
import android.text.TextWatcher;
// ----- Fix Ended for this class (SettingsFragment_video_splitting_imports) -----

import android.graphics.drawable.GradientDrawable;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

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
    private MaterialSwitch locationEmbedSwitch; // Declare location embedding switch
    private MaterialSwitch debugSwitch; // Declare debugSwitch
    private MaterialSwitch audioSwitch; // Declare audioSwitch
    
    // App Lock
    private MaterialButton appLockConfigureButton;
    private static final String PREF_APPLOCK_ENABLED = "applock_enabled";

    // App Icon
    private MaterialButton appIconChooseButton;

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
            // ----- Fix Start for Audio Input Source dialog text color -----
            // Check if we're using Snow Veil theme for text color
            String currentTheme = sharedPreferencesManager.sharedPreferences.getString(Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);
            boolean isSnowVeilTheme = "Snow Veil".equals(currentTheme);
            int textColor = ContextCompat.getColor(requireContext(), isSnowVeilTheme ? android.R.color.black : android.R.color.white);
            // ----- Fix End for Audio Input Source dialog text color -----
            
            TextView messageView = new TextView(requireContext());
            messageView.setText(getString(R.string.audio_input_source_wired_not_available));
            messageView.setTextColor(textColor);
            messageView.setTextSize(16);
            int padding = (int) (16 * requireContext().getResources().getDisplayMetrics().density);
            messageView.setPadding(padding, padding, padding, padding);
            AlertDialog dialog = themedDialogBuilder(requireContext())
                .setTitle(getString(R.string.setting_audio_input_source_title))
                .setView(messageView)
                .setPositiveButton(android.R.string.ok, null)
                .show();
                
            // Apply theme-specific colors to dialog buttons
            setDialogButtonColors(dialog);
            return;
        }
        
        // ----- Fix Start for Audio Input Source dialog text color -----
        // Check if we're using Snow Veil theme for text color
        String currentTheme = sharedPreferencesManager.sharedPreferences.getString(Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);
        boolean isSnowVeilTheme = "Snow Veil".equals(currentTheme);
        int color = ContextCompat.getColor(requireContext(), isSnowVeilTheme ? android.R.color.black : android.R.color.white);
        // ----- Fix End for Audio Input Source dialog text color -----
        
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(requireContext(), android.R.layout.simple_list_item_single_choice, availableMicLabels) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView text1 = view.findViewById(android.R.id.text1);
                if (text1 != null) text1.setTextColor(color);
                return view;
            }
        };
        AlertDialog dialog = themedDialogBuilder(requireContext())
            .setTitle(getString(R.string.setting_audio_input_source_title))
            .setSingleChoiceItems(adapter, checkedItem, (dialogInterface, which) -> {
                selectedMic = availableInputMics.get(which);
                updateAudioInputSourceStatusUI();
                sharedPreferencesManager.setAudioInputSource(selectedMic == null ? SharedPreferencesManager.AUDIO_INPUT_SOURCE_PHONE : SharedPreferencesManager.AUDIO_INPUT_SOURCE_WIRED);
                dialogInterface.dismiss();
            })
            .setNegativeButton(R.string.universal_cancel, null)
            .show();
            
        // Apply theme-specific colors to dialog buttons
        setDialogButtonColors(dialog);
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
        locationEmbedSwitch = view.findViewById(R.id.location_embed_toggle_group);
        debugSwitch = view.findViewById(R.id.debug_toggle_group);
        audioSwitch = view.findViewById(R.id.audio_toggle_group); // Find audio switch
        MaterialButton reviewButton = view.findViewById(R.id.review_button);
        MaterialButton audioSettingsButton = view.findViewById(R.id.audio_settings_button);
        
        // ----- Fix Start for button text colors -----
        // Set white text color for buttons for better visibility
        if (readmeButton != null) {
            readmeButton.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white));
        }
        
        if (reviewButton != null) {
            reviewButton.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white));
        }
        // ----- Fix End for button text colors -----
        
        if (audioSettingsButton != null) {
            audioSettingsButton.setOnClickListener(v -> showAudioSettingsDialog());
            audioSettingsButton.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white));
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
            videoBitrateButton.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white));
        }

        // ----- Fix Start for onCreateView: Setup Choose Button for mic selection -----
        MaterialButton audioInputSourceButton = view.findViewById(R.id.audio_input_source_button);
        if (audioInputSourceButton != null) {
            audioInputSourceButton.setOnClickListener(v -> showMicSelectionDialog());
            audioInputSourceButton.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white));
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

        setupUI();

        // ----- Fix Start: Apply theme colors to headings, buttons, toggles using theme attributes -----
        applyThemeToUI(view);
        // ----- Fix End: Apply theme colors to headings, buttons, toggles using theme attributes -----

        // ----- Fix Start: Apply theme color to top bar and buttons -----
        // Top app bar
        if (toolbar != null && "Crimson Bloom".equals(sharedPreferencesManager.sharedPreferences.getString(Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME))) {
            toolbar.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.red_theme_primary_variant));
        } else if (toolbar != null) {
            toolbar.setBackgroundColor(resolveThemeColor(R.attr.colorTopBar));
        }
        // ----- Fix End: Apply theme color to top bar and buttons -----

        return view;
    }

    private void setupUI() {
        // Set up the UI with saved preferences
        setupAppIconButton(view); // Add app icon setup
        setupCameraSelectionToggle(view, cameraSelectionToggle);
        setupResolutionSpinner();
        setupFrameRateSpinner();
        setupCodecSpinner();
        setupWatermarkSpinner(view, watermarkSpinner);
        setupLocationSwitch(locationSwitch);
        setupLocationEmbedSwitch(locationEmbedSwitch);
        setupDebugSwitch(debugSwitch);
        setupAudioSwitch(audioSwitch);
        setupThemeSpinner(view);
        setupOrientationSpinner();
        setupVideoSplittingSection();

        locationHelper = new LocationHelper(getContext());
        
        setupStorageLocationOptions();
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

        // ----- Fix Start: Set radio button text color for Faded Night theme -----
        String currentTheme = sharedPreferencesManager.sharedPreferences.getString(Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);
        boolean isFadedNightTheme = "Faded Night".equals(currentTheme);
        
        if (isFadedNightTheme) {
            // Create a ColorStateList that uses white for both checked and unchecked states
            int[][] states = new int[][] {
                new int[] { android.R.attr.state_checked },  // checked state
                new int[] { -android.R.attr.state_checked }  // unchecked state
            };
            
            int[] colors = new int[] {
                Color.WHITE,  // color for checked state - WHITE
                Color.WHITE   // color for unchecked state - WHITE
            };
            
            ColorStateList colorStateList = new ColorStateList(states, colors);
            
            // Apply to specific radio buttons
            if (radioInternalStorage != null) {
                radioInternalStorage.setTextColor(colorStateList);
                // Also adjust the button tint if needed
                radioInternalStorage.setButtonTintList(colorStateList);
            }
            
            if (radioCustomLocation != null) {
                radioCustomLocation.setTextColor(colorStateList);
                // Also adjust the button tint if needed
                radioCustomLocation.setButtonTintList(colorStateList);
            }
            
            // Also apply to any other radio buttons in the group
            for (int i = 0; i < storageLocationRadioGroup.getChildCount(); i++) {
                View child = storageLocationRadioGroup.getChildAt(i);
                if (child instanceof RadioButton) {
                    RadioButton radioButton = (RadioButton) child;
                    radioButton.setTextColor(colorStateList);
                    radioButton.setButtonTintList(colorStateList);
                }
            }
        }
        // ----- Fix End: Set radio button text color for Faded Night theme -----

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
        if (button == null) return;
        
        int themeColor = resolveThemeColor(R.attr.colorButton); // Theme color for selected
        int black = ContextCompat.getColor(requireContext(), R.color.black); // Black for unselected in Faded Night
        int white = ContextCompat.getColor(requireContext(), android.R.color.white);
        int purpleColor = ContextCompat.getColor(requireContext(), R.color.colorPrimary); // #cfbafd
        
        // Check current theme to apply different styles
        String currentTheme = sharedPreferencesManager.sharedPreferences.getString(Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);
        
        if (isSelected) {
            if ("Midnight Dusk".equals(currentTheme)) {
                // For Midnight Dusk, ALWAYS use #cfbafd color directly for selected buttons
                // Don't use themeColor or resolveThemeColor which might return gray
                button.setBackgroundColor(purpleColor);
                button.setTextColor(black); // Black text on light purple background
                button.setStrokeColor(ColorStateList.valueOf(purpleColor)); // No visible border
                button.setIconTintResource(android.R.color.black); // Icon should be black too for contrast
            } else if ("Premium Gold".equals(currentTheme)) {
                // For Gold theme, use gold color with black text for contrast
                button.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.gold_theme_primary));
                button.setTextColor(black); // Black text on gold background
                button.setStrokeColor(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.gold_theme_primary)));
                button.setIconTintResource(android.R.color.black);
            } else if ("Silent Forest".equals(currentTheme)) {
                // For Silent Forest theme, use green/teal color with black text for contrast
                button.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.silentforest_theme_primary));
                button.setTextColor(black); // Black text on green background
                button.setStrokeColor(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.silentforest_theme_primary)));
                button.setIconTintResource(android.R.color.black);
            } else if ("Shadow Alloy".equals(currentTheme)) {
                // For Shadow Alloy theme, use silver color with black text for contrast
                button.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.shadowalloy_theme_primary));
                button.setTextColor(black); // Black text on silver background
                button.setStrokeColor(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.shadowalloy_theme_primary)));
                button.setIconTintResource(android.R.color.black);
            } else {
                // For other themes, use theme color
                button.setBackgroundColor(themeColor);
                button.setTextColor(white);
                button.setStrokeColor(ColorStateList.valueOf(themeColor)); // No visible border
                button.setIconTintResource(android.R.color.white);
            }
        } else {
            // Unselected button style (different per theme)
            if ("Faded Night".equals(currentTheme) || "AMOLED".equals(currentTheme) || "Amoled".equals(currentTheme) || "Midnight Dusk".equals(currentTheme)) {
                // Faded Night and Midnight Dusk themes - black background, white text, no stroke
                button.setBackgroundColor(black);
                button.setTextColor(white);
                button.setStrokeWidth(0); // Remove stroke completely
                button.setStrokeColor(ColorStateList.valueOf(black)); // Set stroke to match background
                button.setIconTintResource(android.R.color.white);
            } else if ("Crimson Bloom".equals(currentTheme)) {
                // Crimson Bloom theme - darker red background, white text
                int darkRed = ContextCompat.getColor(requireContext(), R.color.red_theme_primary_variant);
                button.setBackgroundColor(darkRed);
                button.setTextColor(white);
                button.setStrokeWidth(0);
                button.setStrokeColor(ColorStateList.valueOf(darkRed));
                button.setIconTintResource(android.R.color.white);
            } else if ("Premium Gold".equals(currentTheme)) {
                // Premium Gold theme - darker gold background, white text
                int darkGold = ContextCompat.getColor(requireContext(), R.color.gold_theme_primary_variant);
                button.setBackgroundColor(darkGold);
                button.setTextColor(white);
                button.setStrokeWidth(0);
                button.setStrokeColor(ColorStateList.valueOf(darkGold));
                button.setIconTintResource(android.R.color.white);
            } else if ("Silent Forest".equals(currentTheme)) {
                // Silent Forest theme - darker green background, white text
                int darkGreen = ContextCompat.getColor(requireContext(), R.color.silentforest_theme_primary_variant);
                button.setBackgroundColor(darkGreen);
                button.setTextColor(white);
                button.setStrokeWidth(0);
                button.setStrokeColor(ColorStateList.valueOf(darkGreen));
                button.setIconTintResource(android.R.color.white);
            } else if ("Shadow Alloy".equals(currentTheme)) {
                // Shadow Alloy theme - darker silver background, white text
                int darkSilver = ContextCompat.getColor(requireContext(), R.color.shadowalloy_theme_primary_variant);
                button.setBackgroundColor(darkSilver);
                button.setTextColor(white);
                button.setStrokeWidth(0);
                button.setStrokeColor(ColorStateList.valueOf(darkSilver));
                button.setIconTintResource(android.R.color.white);
            } else if ("Pookie Pink".equals(currentTheme)) {
                // Pookie Pink theme - darker pink background, white text
                int darkPink = ContextCompat.getColor(requireContext(), R.color.pookiepink_theme_primary_variant);
                button.setBackgroundColor(darkPink);
                button.setTextColor(white);
                button.setStrokeWidth(0);
                button.setStrokeColor(ColorStateList.valueOf(darkPink));
                button.setIconTintResource(android.R.color.white);
            } else if ("Snow Veil".equals(currentTheme)) {
                // Snow Veil theme - light grey background, black text
                int lightGrey = ContextCompat.getColor(requireContext(), R.color.snowveil_theme_primary_variant);
                button.setBackgroundColor(lightGrey);
                button.setTextColor(black);
                button.setStrokeWidth(0);
                button.setStrokeColor(ColorStateList.valueOf(lightGrey));
                button.setIconTintResource(android.R.color.black);
            } else {
                // Fallback for any other theme - dark gray with white text
                int darkGray = ContextCompat.getColor(requireContext(), R.color.gray_button_filled);
                button.setBackgroundColor(darkGray);
                button.setTextColor(white);
                button.setStrokeWidth(0);
                button.setStrokeColor(ColorStateList.valueOf(darkGray));
                button.setIconTintResource(android.R.color.white);
            }
        }
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
        
        // Force refresh the camera toggle buttons appearance
        if (cameraSelectionToggle != null && view != null) {
            MaterialButton backCameraButton = view.findViewById(R.id.button_back_camera);
            MaterialButton frontCameraButton = view.findViewById(R.id.button_front_camera);
            if (backCameraButton != null && frontCameraButton != null) {
                CameraType selected = sharedPreferencesManager.getCameraSelection();
                updateButtonAppearance(backCameraButton, selected == CameraType.BACK);
                updateButtonAppearance(frontCameraButton, selected == CameraType.FRONT);
                
                // Special handling for Midnight Dusk theme to ensure purple color
                String currentTheme = sharedPreferencesManager.sharedPreferences.getString(Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);
                if ("Midnight Dusk".equals(currentTheme)) {
                    // Force apply correct purple colors to selected button
                    MaterialButton selectedButton = (selected == CameraType.BACK) ? backCameraButton : frontCameraButton;
                    int purpleColor = ContextCompat.getColor(requireContext(), R.color.colorPrimary); // #cfbafd
                    selectedButton.setBackgroundColor(purpleColor);
                    selectedButton.setTextColor(Color.BLACK);
                    selectedButton.setStrokeColor(ColorStateList.valueOf(purpleColor));
                    selectedButton.setIconTintResource(android.R.color.black);
                    
                    Log.d(TAG_SETTINGS, "onResume: Forcing purple color for Midnight Dusk theme");
                }
            }
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
        
        // Define common focal lengths for lens classification
        final float ULTRA_WIDE_MAX = 15.0f;       // Ultra-wide typically around 13-15mm
        final float WIDE_MAX = 28.0f;             // Standard wide typically 24-28mm
        final float PORTRAIT_MIN = 45.0f;         // Portrait/telephoto starts around 45-50mm
        final float TELEPHOTO_MIN = 70.0f;        // Longer telephoto typically 70mm+
        
        try {
            String[] cameraIds = manager.getCameraIdList();
            Log.d(TAG, "System reported Camera IDs: " + Arrays.toString(cameraIds));
            
            // Create a map to store all detected cameras and their characteristics
            Map<String, CameraCharacteristics> allCameras = new HashMap<>();
            Set<String> backCameraIds = new HashSet<>();
            Set<String> logicalCameraIds = new HashSet<>();
            
            // First pass: identify all cameras, both logical and physical
            for (String id : cameraIds) {
                try {
                    CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                    allCameras.put(id, characteristics);
                    
                    // Check if it's a back camera
                    Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                    if (facing != null && facing == CameraMetadata.LENS_FACING_BACK) {
                        backCameraIds.add(id);
                        Log.d(TAG, "ID " + id + ": Confirmed as back-facing camera");
                        
                        // Check if it's a logical camera (on API 28+)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            try {
                                Set<String> physicalIds = characteristics.getPhysicalCameraIds();
                                if (physicalIds != null && !physicalIds.isEmpty()) {
                                    logicalCameraIds.add(id);
                                    Log.d(TAG, "ID " + id + " is a LOGICAL camera with physical IDs: " + physicalIds);
                                }
                            } catch (Exception e) {
                                Log.w(TAG, "Error checking physical IDs for " + id, e);
                            }
                        }
                    }
                } catch (CameraAccessException e) {
                    Log.e(TAG, "Couldn't access camera " + id, e);
                }
            }
            
            // On Android P and above, find all physical cameras including those not directly exposed
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                for (String logicalId : logicalCameraIds) {
                    CameraCharacteristics chars = allCameras.get(logicalId);
                    if (chars != null) {
                        try {
                            Set<String> physicalIds = chars.getPhysicalCameraIds();
                            Log.d(TAG, "Checking physical cameras in logical camera " + logicalId + ": " + physicalIds);
                            
                            // Add all physical IDs to our map if they're not already there
                            for (String physicalId : physicalIds) {
                                if (!allCameras.containsKey(physicalId)) {
                                    try {
                                        CameraCharacteristics physicalChars = manager.getCameraCharacteristics(physicalId);
                                        allCameras.put(physicalId, physicalChars);
                                        backCameraIds.add(physicalId); // These are all back cameras since the logical camera was
                                        Log.d(TAG, "Added physical camera ID " + physicalId + " from logical camera " + logicalId);
                                    } catch (Exception e) {
                                        Log.w(TAG, "Couldn't get characteristics for physical camera " + physicalId, e);
                                    }
                                }
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "Error processing physical IDs for logical camera " + logicalId, e);
                        }
                    }
                }
            }
            
            // Process each back camera
            for (String id : backCameraIds) {
                CameraCharacteristics characteristics = allCameras.get(id);
                if (characteristics == null) continue;
                
                try {
                    // 1. Get Focal Length - Key for lens type identification
                    float[] focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                    Float focalLength = null;
                    if (focalLengths != null && focalLengths.length > 0) {
                        focalLength = focalLengths[0]; // Use the first reported focal length
                        Log.d(TAG, "ID " + id + ": Focal length reported: " + focalLength + "mm");
                    } else {
                        Log.w(TAG, "ID " + id + ": No focal length info available.");
                    }
                    
                    // 2. Check if it's a logical multi-camera
                    boolean isLogicalCamera = false;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && logicalCameraIds.contains(id)) {
                        isLogicalCamera = true;
                    }
                    
                    // 3. Determine Display Name
                    StringBuilder displayNameBuilder = new StringBuilder();
                    boolean isDefaultCamera = id.equals(Constants.DEFAULT_BACK_CAMERA_ID);
                    
                    // Basic camera role description
                    if (isDefaultCamera) {
                        displayNameBuilder.append("Main");
                    } else if (focalLength != null) {
                        // Lens type based on focal length
                        if (focalLength <= ULTRA_WIDE_MAX) {
                            displayNameBuilder.append("Ultra-Wide");
                        } else if (focalLength <= WIDE_MAX) {
                            if (!isDefaultCamera) {
                                displayNameBuilder.append("Wide-Angle");
                            } else {
                                displayNameBuilder.append("Main");
                            }
                        } else if (focalLength >= TELEPHOTO_MIN) {
                            displayNameBuilder.append("Telephoto");
                        } else if (focalLength >= PORTRAIT_MIN) {
                            displayNameBuilder.append("Portrait");
                        } else {
                            displayNameBuilder.append("Camera");
                        }
                    } else {
                        displayNameBuilder.append("Camera");
                    }
                    
                    // Always add ID for clear identification
                    displayNameBuilder.append(" (").append(id).append(")");
                    
                    // Add focal length if available for more detail
                    if (focalLength != null) {
                        displayNameBuilder.append(" ").append(Math.round(focalLength)).append("mm");
                    }
                    
                    // Add logical tag if applicable
                    if (isLogicalCamera) {
                        displayNameBuilder.append(" (Logical)");
                    }
                    
                    // 4. Add to the list
                    String finalDisplayName = displayNameBuilder.toString().trim();
                    availableBackCameras.add(new CameraIdInfo(id, finalDisplayName));
                    Log.i(TAG, ">>> ADDED Back Camera: ID=" + id + ", Assigned Name=" + finalDisplayName + " <<<");
                    
                } catch (Exception e) {
                    Log.e(TAG, "Error processing camera " + id, e);
                }
            }
            
            // Sort cameras: Main (ID 0) first, then by ID number 
            Collections.sort(availableBackCameras, (a, b) -> {
                // Always put the main camera (usually ID "0") first
                if (a.id.equals(Constants.DEFAULT_BACK_CAMERA_ID)) return -1;
                if (b.id.equals(Constants.DEFAULT_BACK_CAMERA_ID)) return 1;
                
                // Then try to sort numerically if the IDs are numbers
                try {
                    return Integer.parseInt(a.id) - Integer.parseInt(b.id);
                } catch (NumberFormatException e) {
                    // If not numbers, sort by string
                    return a.id.compareTo(b.id);
                }
            });
            
            Log.i(TAG, "=== Finished Detection. Final Back Camera List Size: " + availableBackCameras.size() + " ===");
            
        } catch (CameraAccessException e) {
            Log.e(TAG, "!!! CRITICAL ERROR getting camera ID list !!!", e);
            availableBackCameras.clear(); // Clear list on critical error
            
            // Add a fallback camera to prevent crashes
            availableBackCameras.add(new CameraIdInfo(
                Constants.DEFAULT_BACK_CAMERA_ID, "Default Camera"));
            Log.w(TAG, "Added fallback Default Camera with ID " + Constants.DEFAULT_BACK_CAMERA_ID);
        }
    }

    // Helper class to store camera information during detection
    private static class CameraInfo {
        final String id;
        final Float focalLength;
        final boolean isLogicalCamera;
        final Set<String> physicalIds;
        final float sensorSize;
        final String hardwareLevel;

        CameraInfo(String id, Float focalLength, boolean isLogicalCamera, 
                   Set<String> physicalIds, float sensorSize, String hardwareLevel) {
            this.id = id;
            this.focalLength = focalLength;
            this.isLogicalCamera = isLogicalCamera;
            this.physicalIds = physicalIds;
            this.sensorSize = sensorSize;
            this.hardwareLevel = hardwareLevel;
        }

        @Override
        public String toString() {
            return "Camera{id='" + id + "', focal=" + focalLength + 
                   "mm, logical=" + isLogicalCamera + 
                   ", physicalIds=" + physicalIds.size() + 
                   ", sensorSize=" + sensorSize + 
                   ", hwLevel=" + hardwareLevel + "}";
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
        
        // Force refresh button styles for Midnight Dusk and Premium Gold themes
        String currentTheme = sharedPreferencesManager.sharedPreferences.getString(Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);
        if ("Midnight Dusk".equals(currentTheme)) {
            // Direct approach - bypass theme resolution entirely for Midnight Dusk
            MaterialButton selectedButton = (selected == CameraType.FRONT) ? frontCameraButton : backCameraButton;
            
            // Set explicit colors
            int purpleColor = ContextCompat.getColor(requireContext(), R.color.colorPrimary); // #cfbafd
            selectedButton.setBackgroundColor(purpleColor);
            selectedButton.setTextColor(Color.BLACK);
            selectedButton.setStrokeColor(ColorStateList.valueOf(purpleColor));
            selectedButton.setIconTintResource(android.R.color.black);
            
            // Make sure unselected button is black with white text
            MaterialButton unselectedButton = (selected == CameraType.FRONT) ? backCameraButton : frontCameraButton;
            unselectedButton.setBackgroundColor(Color.BLACK);
            unselectedButton.setTextColor(Color.WHITE);
            unselectedButton.setStrokeWidth(0);
            unselectedButton.setStrokeColor(ColorStateList.valueOf(Color.BLACK));
            unselectedButton.setIconTintResource(android.R.color.white);
            
            Log.d(TAG_SETTINGS, "Applied direct colors for Midnight Dusk theme");
        } else if ("Premium Gold".equals(currentTheme)) {
            // Direct approach for Premium Gold theme
            MaterialButton selectedButton = (selected == CameraType.FRONT) ? frontCameraButton : backCameraButton;
            
            // Set explicit colors for Gold theme
            int goldColor = ContextCompat.getColor(requireContext(), R.color.gold_theme_primary);
            selectedButton.setBackgroundColor(goldColor);
            selectedButton.setTextColor(Color.BLACK); // Black text on gold background
            selectedButton.setStrokeColor(ColorStateList.valueOf(goldColor));
            selectedButton.setIconTintResource(android.R.color.black);
            
            // Make sure unselected button is dark gold with white text
            MaterialButton unselectedButton = (selected == CameraType.FRONT) ? backCameraButton : frontCameraButton;
            int darkGold = ContextCompat.getColor(requireContext(), R.color.gold_theme_primary_variant);
            unselectedButton.setBackgroundColor(darkGold);
            unselectedButton.setTextColor(Color.WHITE);
            unselectedButton.setStrokeWidth(0);
            unselectedButton.setStrokeColor(ColorStateList.valueOf(darkGold));
            unselectedButton.setIconTintResource(android.R.color.white);
            
            Log.d(TAG_SETTINGS, "Applied direct colors for Premium Gold theme");
        } else if ("Silent Forest".equals(currentTheme)) {
            // Direct approach for Silent Forest theme
            MaterialButton selectedButton = (selected == CameraType.FRONT) ? frontCameraButton : backCameraButton;
            
            // Set explicit colors for Silent Forest theme
            int greenColor = ContextCompat.getColor(requireContext(), R.color.silentforest_theme_primary);
            selectedButton.setBackgroundColor(greenColor);
            selectedButton.setTextColor(Color.BLACK); // Black text on green background
            selectedButton.setStrokeColor(ColorStateList.valueOf(greenColor));
            selectedButton.setIconTintResource(android.R.color.black);
            
            // Make sure unselected button is dark green with white text
            MaterialButton unselectedButton = (selected == CameraType.FRONT) ? backCameraButton : frontCameraButton;
            int darkGreen = ContextCompat.getColor(requireContext(), R.color.silentforest_theme_primary_variant);
            unselectedButton.setBackgroundColor(darkGreen);
            unselectedButton.setTextColor(Color.WHITE);
            unselectedButton.setStrokeWidth(0);
            unselectedButton.setStrokeColor(ColorStateList.valueOf(darkGreen));
            unselectedButton.setIconTintResource(android.R.color.white);
            
            Log.d(TAG_SETTINGS, "Applied direct colors for Silent Forest theme");
        } else if ("Shadow Alloy".equals(currentTheme)) {
            // Direct approach for Shadow Alloy theme
            MaterialButton selectedButton = (selected == CameraType.FRONT) ? frontCameraButton : backCameraButton;
            
            // Set explicit colors for Shadow Alloy theme
            int silverColor = ContextCompat.getColor(requireContext(), R.color.shadowalloy_theme_primary);
            selectedButton.setBackgroundColor(silverColor);
            selectedButton.setTextColor(Color.BLACK); // Black text on silver background
            selectedButton.setStrokeColor(ColorStateList.valueOf(silverColor));
            selectedButton.setIconTintResource(android.R.color.black);
            
            // Make sure unselected button is dark silver with white text
            MaterialButton unselectedButton = (selected == CameraType.FRONT) ? backCameraButton : frontCameraButton;
            int darkSilver = ContextCompat.getColor(requireContext(), R.color.shadowalloy_theme_primary_variant);
            unselectedButton.setBackgroundColor(darkSilver);
            unselectedButton.setTextColor(Color.WHITE);
            unselectedButton.setStrokeWidth(0);
            unselectedButton.setStrokeColor(ColorStateList.valueOf(darkSilver));
            unselectedButton.setIconTintResource(android.R.color.white);
            
            Log.d(TAG_SETTINGS, "Applied direct colors for Shadow Alloy theme");
        } else if ("Pookie Pink".equals(currentTheme)) {
            // Direct approach for Pookie Pink theme
            MaterialButton selectedButton = (selected == CameraType.FRONT) ? frontCameraButton : backCameraButton;
            
            // Set explicit colors for Pookie Pink theme
            int pinkColor = ContextCompat.getColor(requireContext(), R.color.pookiepink_theme_primary);
            selectedButton.setBackgroundColor(pinkColor);
            selectedButton.setTextColor(Color.BLACK); // Black text on pink background
            selectedButton.setStrokeColor(ColorStateList.valueOf(pinkColor));
            selectedButton.setIconTintResource(android.R.color.black);
            
            // Make sure unselected button is dark pink with white text
            MaterialButton unselectedButton = (selected == CameraType.FRONT) ? backCameraButton : frontCameraButton;
            int darkPink = ContextCompat.getColor(requireContext(), R.color.pookiepink_theme_primary_variant);
            unselectedButton.setBackgroundColor(darkPink);
            unselectedButton.setTextColor(Color.WHITE);
            unselectedButton.setStrokeWidth(0);
            unselectedButton.setStrokeColor(ColorStateList.valueOf(darkPink));
            unselectedButton.setIconTintResource(android.R.color.white);
            
            Log.d(TAG_SETTINGS, "Applied direct colors for Pookie Pink theme");
        } else if ("Snow Veil".equals(currentTheme)) {
            // Direct approach for Snow Veil theme
            MaterialButton selectedButton = (selected == CameraType.FRONT) ? frontCameraButton : backCameraButton;
            
            // Set explicit colors for Snow Veil theme
            int snowColor = ContextCompat.getColor(requireContext(), R.color.snowveil_theme_primary);
            selectedButton.setBackgroundColor(snowColor);
            selectedButton.setTextColor(Color.BLACK); // Black text on white background
            selectedButton.setStrokeColor(ColorStateList.valueOf(snowColor));
            selectedButton.setIconTintResource(android.R.color.black);
            
            // Make sure unselected button is light grey with black text
            MaterialButton unselectedButton = (selected == CameraType.FRONT) ? backCameraButton : frontCameraButton;
            int lightGrey = ContextCompat.getColor(requireContext(), R.color.snowveil_theme_primary_variant);
            unselectedButton.setBackgroundColor(lightGrey);
            unselectedButton.setTextColor(Color.BLACK);
            unselectedButton.setStrokeWidth(0);
            unselectedButton.setStrokeColor(ColorStateList.valueOf(lightGrey));
            unselectedButton.setIconTintResource(android.R.color.black);
            
            Log.d(TAG_SETTINGS, "Applied direct colors for Snow Veil theme");
        }
        
        Log.d(TAG_SETTINGS,"Synced camera switch UI to: " + sharedPreferencesManager.getCameraSelection());
    }


    // --- setupCameraSelectionToggle MUST call updateFrameRateSpinner ---
    private void setupCameraSelectionToggle(View view, MaterialButtonToggleGroup toggleGroup) {
        // ... (Existing syncCameraSwitch call and appearance update logic) ...
        if (toggleGroup == null || view == null) return;
        
        // Apply initial toggle state and appearance
        syncCameraSwitch(view, toggleGroup);

        // Add listener for toggle changes
        toggleGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                MaterialButton backCameraButton = view.findViewById(R.id.button_back_camera);
                MaterialButton frontCameraButton = view.findViewById(R.id.button_front_camera);
                
                // Update appearance of both buttons
                updateButtonAppearance(backCameraButton, checkedId == R.id.button_back_camera);
                updateButtonAppearance(frontCameraButton, checkedId == R.id.button_front_camera);

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
                
                // Check special theme handling for Midnight Dusk and Premium Gold
                String currentTheme = sharedPreferencesManager.sharedPreferences.getString(Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);
                if ("Midnight Dusk".equals(currentTheme)) {
                    // Force apply correct colors
                    MaterialButton selectedButton = (checkedId == R.id.button_front_camera) ? frontCameraButton : backCameraButton;
                    int purpleColor = ContextCompat.getColor(requireContext(), R.color.colorPrimary); // #cfbafd
                    selectedButton.setBackgroundColor(purpleColor);
                    selectedButton.setTextColor(ContextCompat.getColor(requireContext(), R.color.black));
                    selectedButton.setStrokeColor(ColorStateList.valueOf(purpleColor));
                    selectedButton.setIconTintResource(android.R.color.black);
                } else if ("Premium Gold".equals(currentTheme)) {
                    // Force apply correct colors for Gold theme
                    MaterialButton selectedButton = (checkedId == R.id.button_front_camera) ? frontCameraButton : backCameraButton;
                    int goldColor = ContextCompat.getColor(requireContext(), R.color.gold_theme_primary);
                    selectedButton.setBackgroundColor(goldColor);
                    selectedButton.setTextColor(ContextCompat.getColor(requireContext(), R.color.black)); // Black text
                    selectedButton.setStrokeColor(ColorStateList.valueOf(goldColor));
                    selectedButton.setIconTintResource(android.R.color.black);
                } else if ("Silent Forest".equals(currentTheme)) {
                    // Force apply correct colors for Silent Forest theme
                    MaterialButton selectedButton = (checkedId == R.id.button_front_camera) ? frontCameraButton : backCameraButton;
                    int greenColor = ContextCompat.getColor(requireContext(), R.color.silentforest_theme_primary);
                    selectedButton.setBackgroundColor(greenColor);
                    selectedButton.setTextColor(ContextCompat.getColor(requireContext(), R.color.black)); // Black text
                    selectedButton.setStrokeColor(ColorStateList.valueOf(greenColor));
                    selectedButton.setIconTintResource(android.R.color.black);
                } else if ("Shadow Alloy".equals(currentTheme)) {
                    // Force apply correct colors for Shadow Alloy theme
                    MaterialButton selectedButton = (checkedId == R.id.button_front_camera) ? frontCameraButton : backCameraButton;
                    int silverColor = ContextCompat.getColor(requireContext(), R.color.shadowalloy_theme_primary);
                    selectedButton.setBackgroundColor(silverColor);
                    selectedButton.setTextColor(Color.BLACK); // Black text on silver background
                    selectedButton.setStrokeColor(ColorStateList.valueOf(silverColor));
                    selectedButton.setIconTintResource(android.R.color.black);
                } else if ("Pookie Pink".equals(currentTheme)) {
                    // Force apply correct colors for Pookie Pink theme
                    MaterialButton selectedButton = (checkedId == R.id.button_front_camera) ? frontCameraButton : backCameraButton;
                    int pinkColor = ContextCompat.getColor(requireContext(), R.color.pookiepink_theme_primary);
                    selectedButton.setBackgroundColor(pinkColor);
                    selectedButton.setTextColor(Color.BLACK); // Black text on pink background
                    selectedButton.setStrokeColor(ColorStateList.valueOf(pinkColor));
                    selectedButton.setIconTintResource(android.R.color.black);
                } else if ("Snow Veil".equals(currentTheme)) {
                    // Force apply correct colors for Snow Veil theme
                    MaterialButton selectedButton = (checkedId == R.id.button_front_camera) ? frontCameraButton : backCameraButton;
                    int snowColor = ContextCompat.getColor(requireContext(), R.color.snowveil_theme_primary);
                    selectedButton.setBackgroundColor(snowColor);
                    selectedButton.setTextColor(Color.BLACK); // Black text on white background
                    selectedButton.setStrokeColor(ColorStateList.valueOf(snowColor));
                    selectedButton.setIconTintResource(android.R.color.black);
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
        // Safety check view readiness
        if (backCameraLensLayout == null || backCameraLensSpinner == null || 
            backCameraLensDivider == null || cameraSelectionToggle == null || getContext() == null) {
            Log.w(TAG,"updateBackLensSpinnerVisibility: Views or context not ready, skipping update.");
            return;
        }

        // 1. Determine visibility based on BACK camera selection
        boolean isBackCameraSelected = sharedPreferencesManager.getCameraSelection() == CameraType.BACK;
        
        // This method will re-scan for cameras if needed
        if (isBackCameraSelected && availableBackCameras.isEmpty()) {
            Log.d(TAG, "Back camera is selected but no lenses detected. Re-running detection.");
            detectAvailableBackCameras();
        }

        // 2. Set visibility for BOTH the layout and the divider
        boolean shouldShowLensSelector = isBackCameraSelected && availableBackCameras.size() > 1;
        int visibility = shouldShowLensSelector ? View.VISIBLE : View.GONE;
        
        backCameraLensLayout.setVisibility(visibility);
        backCameraLensDivider.setVisibility(visibility);
        
        Log.d(TAG, "Lens selector visibility: " + (shouldShowLensSelector ? "VISIBLE" : "GONE") + 
                  " (Back selected: " + isBackCameraSelected + ", Lens count: " + availableBackCameras.size() + ")");

        // 3. Configure the spinner if section is visible
        if (shouldShowLensSelector) {
            backCameraLensSpinner.setVisibility(View.VISIBLE);
            
            // Populate the spinner with available options
            populateBackCameraLensSpinner();
            
            // Enable the spinner and show it at full opacity
            backCameraLensSpinner.setEnabled(true);
            backCameraLensSpinner.setClickable(true);
            backCameraLensSpinner.setAlpha(1.0f);
            
            Log.d(TAG, "Lens selector enabled with " + availableBackCameras.size() + " options");
        } else if (isBackCameraSelected && availableBackCameras.size() == 1) {
            // Special case: Show a disabled spinner with the single camera name
            backCameraLensLayout.setVisibility(View.VISIBLE);
            backCameraLensDivider.setVisibility(View.VISIBLE);
            backCameraLensSpinner.setVisibility(View.VISIBLE);
            
            // Populate with the single camera
            populateBackCameraLensSpinner();
            
            // Make it look disabled but still visible
            backCameraLensSpinner.setEnabled(false);
            backCameraLensSpinner.setClickable(false);
            backCameraLensSpinner.setAlpha(0.7f);
            
            Log.d(TAG, "Single lens detected. Showing disabled spinner with name: " + 
                      (availableBackCameras.size() > 0 ? availableBackCameras.get(0).displayName : "Unknown"));
        } else {
            // Front camera selected or no back cameras - hide everything
            backCameraLensSpinner.setVisibility(View.GONE);
            Log.d(TAG, "Lens selector completely hidden");
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
     * Handles all cases including multiple cameras, a single camera, or no cameras.
     * Ensures a valid selection is always made.
     */
    private void populateBackCameraLensSpinner() {
        if (backCameraLensSpinner == null || getContext() == null) {
            Log.w(TAG, "Cannot populate back lens spinner (null view or context).");
            return;
        }

        // Create adapter for spinner
        ArrayAdapter<CameraIdInfo> adapter;
        
        if (availableBackCameras.isEmpty()) {
            // No cameras detected: Add a placeholder item with an invalid ID
            availableBackCameras.add(new CameraIdInfo("-1", "No back cameras found"));
            Log.w(TAG, "No back cameras found, using placeholder text in spinner.");
        }

        // Create adapter with the available cameras list
        adapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_item,
                availableBackCameras
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        backCameraLensSpinner.setAdapter(adapter);

        // Determine which camera should be selected
        String savedId = sharedPreferencesManager.getSelectedBackCameraId();
        Log.d(TAG, "Selecting camera from saved ID: " + savedId + " among " + availableBackCameras.size() + " options");
        
        // Try to find the saved ID
        int selectedIndex = -1;
        for (int i = 0; i < availableBackCameras.size(); i++) {
            if (availableBackCameras.get(i).id.equals(savedId)) {
                selectedIndex = i;
                Log.d(TAG, "Found saved camera ID at index " + i);
                break;
            }
        }

        // If saved ID not found, try the default "0" or just use the first available
        if (selectedIndex == -1) {
            Log.w(TAG, "Saved camera ID '" + savedId + "' not found in available list.");
            
            // Try to find the default camera (ID "0")
            for (int i = 0; i < availableBackCameras.size(); i++) {
                if (Constants.DEFAULT_BACK_CAMERA_ID.equals(availableBackCameras.get(i).id)) {
                    selectedIndex = i;
                    sharedPreferencesManager.setSelectedBackCameraId(Constants.DEFAULT_BACK_CAMERA_ID);
                    Log.d(TAG, "Selected default camera ID '0' at index " + i);
                    break;
                }
            }
            
            // If still not found, use the first one (this will happen if DEFAULT_BACK_CAMERA_ID isn't in the list)
            if (selectedIndex == -1 && !availableBackCameras.isEmpty()) {
                selectedIndex = 0;
                sharedPreferencesManager.setSelectedBackCameraId(availableBackCameras.get(0).id);
                Log.d(TAG, "Selected first available camera ID '" + availableBackCameras.get(0).id + "' at index 0");
            }
        }

        // Apply the selection if we have a valid index
        if (selectedIndex >= 0 && selectedIndex < availableBackCameras.size()) {
            backCameraLensSpinner.setSelection(selectedIndex);
            Log.d(TAG, "Set camera spinner selection to index " + selectedIndex + 
                      ": " + availableBackCameras.get(selectedIndex).displayName);
        } else {
            Log.e(TAG, "Could not determine a valid camera selection index");
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
            @OptIn(markerClass = ExperimentalCamera2Interop.class)
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
    @OptIn(markerClass = ExperimentalCamera2Interop.class)
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
        MaterialAlertDialogBuilder builder = themedDialogBuilder(requireContext());
        builder.setTitle(R.string.dialog_welcome_title);
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_readme, null);
        if(dialogView == null) return; // Check if inflation failed
        
        // Check if we're using Snow Veil theme
        String currentTheme = sharedPreferencesManager.sharedPreferences.getString(Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);
        boolean isSnowVeilTheme = "Snow Veil".equals(currentTheme);
        
        MaterialButton githubButton = dialogView.findViewById(R.id.github_button);
        MaterialButton discordButton = dialogView.findViewById(R.id.discord_button);
        if (githubButton != null) {
            githubButton.setOnClickListener(v -> openUrl("https://github.com/anonfaded/FadCam"));
            githubButton.setTextColor(isSnowVeilTheme ? Color.BLACK : Color.WHITE);
        }
        if (discordButton != null) {
            discordButton.setOnClickListener(v -> openUrl("https://discord.gg/kvAZvdkuuN"));
            discordButton.setTextColor(isSnowVeilTheme ? Color.BLACK : Color.WHITE);
        }
        builder.setView(dialogView);
        builder.setPositiveButton(android.R.string.ok, null); // Use standard OK text
        
        // Create dialog and show it
        AlertDialog dialog = builder.create();
        dialog.show();
        
        // Set button colors based on theme
        setDialogButtonColors(dialog);
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
            // ----- Fix Start for Language Dialog text color -----
            // Check if we're using Snow Veil theme for text color
            String currentTheme = sharedPreferencesManager.sharedPreferences.getString(Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);
            boolean isSnowVeilTheme = "Snow Veil".equals(currentTheme);
            int color = ContextCompat.getColor(requireContext(), isSnowVeilTheme ? android.R.color.black : android.R.color.white);
            // ----- Fix End for Language Dialog text color -----
            
            ArrayAdapter<String> adapter = new ArrayAdapter<String>(requireContext(), android.R.layout.simple_list_item_single_choice, languages) {
                @Override
                public View getView(int position, View convertView, ViewGroup parent) {
                    View view = super.getView(position, convertView, parent);
                    TextView text1 = view.findViewById(android.R.id.text1);
                    if (text1 != null) text1.setTextColor(color);
                    return view;
                }
            };
            
            AlertDialog dialog = themedDialogBuilder(requireContext())
                .setTitle(R.string.setting_language_title)
                .setSingleChoiceItems(adapter, selectedIndex, (dialogInterface, which) -> {
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
                    dialogInterface.dismiss();
                })
                .setNegativeButton(R.string.universal_cancel, null)
                .show();
                
            // Apply button colors for Snow Veil theme
            setDialogButtonColors(dialog);
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


    // ----- Fix Start for this method(setupLocationSwitch_independent)-----
    private void setupLocationSwitch(MaterialSwitch switchView) {
        if(switchView == null) return;
        
        // Store reference to the switch for use in permission callback
        locationSwitch = switchView;
        
        boolean isLocationEnabled = sharedPreferencesManager.isLocalisationEnabled();
        switchView.setChecked(isLocationEnabled);
        Log.d(TAG_SETTINGS,"Location switch initialized. State: " + isLocationEnabled);

        switchView.setOnCheckedChangeListener((buttonView, isChecked) -> {
            vibrateTouch();
            
            Log.d(TAG_SETTINGS, "Location watermark switch toggled by user to: " + isChecked);
            
            if (isChecked) {
                if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
                    showLocationPermissionDialog(switchView); // Pass the switch to handle denial
                } else {
                    // Update only the watermark preference, not the embedding preference
                    sharedPreferencesManager.setLocationEnabled(true);
                    Log.d(TAG_SETTINGS,"Location permission granted, setting watermark enabled.");
                    
                    // Notify service about the change
                    initializeLocationHelpersInService();
                }
            } else {
                // Update only the watermark preference, not the embedding preference
                sharedPreferencesManager.setLocationEnabled(false);
                Log.d(TAG_SETTINGS,"Location watermark disabled.");
                
                // Stop location updates for watermark if needed
                if(locationHelper != null) {
                    locationHelper.stopLocationUpdates();
                }
                
                // Notify service about the change
                initializeLocationHelpersInService();
            }
        });
    }
    // ----- Fix Ended for this method(setupLocationSwitch_independent)-----


    private void showLocationPermissionDialog(MaterialSwitch switchView) {
        // ----- Fix Start for this method(showLocationPermissionDialog) -----
        // Check which switch initiated this dialog to handle properly
        boolean isLocationEmbedSwitch = (switchView == locationEmbedSwitch);
        String dialogTitle = getString(R.string.location_permission_title);
        String dialogMessage = getString(isLocationEmbedSwitch ? 
                R.string.note_location_embed_extra : 
                R.string.location_permission_description);
        
        Log.d(TAG_SETTINGS, "Showing location permission dialog initiated by " + 
                (isLocationEmbedSwitch ? "location embedding switch" : "location watermark switch"));
        // ----- Fix Ended for this method(showLocationPermissionDialog) -----

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(dialogTitle)
                .setMessage(dialogMessage)
                .setPositiveButton("Grant", (dialog, which) -> requestLocationPermission())
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                    // User denied permission via dialog, uncheck the switch and save pref
                    if (switchView != null) switchView.setChecked(false);
                    
                    // ----- Fix Start for this method(showLocationPermissionDialog_save_appropriate_pref) -----
                    if (isLocationEmbedSwitch) {
                        // If this was the embedding switch, update that preference
                        sharedPreferencesManager.sharedPreferences.edit()
                                .putBoolean(Constants.PREF_EMBED_LOCATION_DATA, false)
                                .apply();
                        Log.d(TAG_SETTINGS,"User cancelled location embedding permission request.");
                    } else {
                        // Otherwise update the watermark preference
                        sharedPreferencesManager.sharedPreferences.edit()
                                .putBoolean(Constants.PREF_LOCATION_DATA, false)
                                .apply();
                        Log.d(TAG_SETTINGS,"User cancelled location watermark permission request.");
                    }
                    // ----- Fix Ended for this method(showLocationPermissionDialog_save_appropriate_pref) -----
                    
                    dialog.dismiss();
                })
                .setCancelable(false) // Prevent dismissing without choice
                .show();
    }


    // ----- Fix Start for this method(requestLocationPermission)-----
    private void requestLocationPermission() {
        Log.d(TAG_SETTINGS, "Requesting location permission from system");
        
        // Request the permission
        ActivityCompat.requestPermissions(
                requireActivity(),
                new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                REQUEST_LOCATION_PERMISSION
        );
        
        // Note: Result will be handled in onRequestPermissionsResult
    }
    // ----- Fix Ended for this method(requestLocationPermission)-----


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_LOCATION_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission was granted
                Log.d(TAG_SETTINGS, "Location permission granted via system dialog.");
                
                // We don't automatically enable either feature
                // Just show a toast advising the user that they can now use the feature
                Toast.makeText(requireContext(), 
                    R.string.location_permission_granted_manual_toggle, 
                    Toast.LENGTH_LONG).show();
                    
                // Update any service that might be running
                initializeLocationHelpersInService();
                
            } else {
                // Permission was denied
                Log.d(TAG_SETTINGS, "Location permission DENIED via system dialog");
                
                // Disable both location features when permission is denied
                sharedPreferencesManager.setLocationEnabled(false);
                sharedPreferencesManager.setLocationEmbeddingEnabled(false);
                
                // Update UI to match
                if (locationSwitch != null) {
                    locationSwitch.setChecked(false);
                }
                if (locationEmbedSwitch != null) {
                    locationEmbedSwitch.setChecked(false);
                }
                
                Toast.makeText(requireContext(), 
                    R.string.location_permission_denied, 
                    Toast.LENGTH_SHORT).show();
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
        MaterialButton themeButton = view.findViewById(R.id.theme_choose_button); // Add a button in layout for theme selection
        if (themeButton == null) return;
        String[] themeNames = {getString(R.string.theme_red), "Midnight Dusk", "Faded Night", getString(R.string.theme_gold), getString(R.string.theme_silentforest), getString(R.string.theme_shadowalloy), getString(R.string.theme_pookiepink), getString(R.string.theme_snowveil)};
        int[] themeColors = {
            ContextCompat.getColor(requireContext(), R.color.red_theme_primary),
            ContextCompat.getColor(requireContext(), R.color.gray),
            ContextCompat.getColor(requireContext(), R.color.amoled_surface), // Use surface color instead of background
            ContextCompat.getColor(requireContext(), R.color.gold_theme_primary),
            ContextCompat.getColor(requireContext(), R.color.silentforest_theme_primary),
            ContextCompat.getColor(requireContext(), R.color.shadowalloy_theme_primary),
            ContextCompat.getColor(requireContext(), R.color.pookiepink_theme_primary),
            ContextCompat.getColor(requireContext(), R.color.snowveil_theme_primary)
        };
        String currentTheme = sharedPreferencesManager.sharedPreferences.getString(Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);
        int tempThemeIndex = 1; // Default to Dark Mode index
        if ("Crimson Bloom".equals(currentTheme)) tempThemeIndex = 0;
        else if ("Faded Night".equals(currentTheme) || "AMOLED".equals(currentTheme) || "Amoled".equals(currentTheme)) tempThemeIndex = 2;
        else if ("Premium Gold".equals(currentTheme)) tempThemeIndex = 3;
        else if ("Silent Forest".equals(currentTheme)) tempThemeIndex = 4;
        else if ("Shadow Alloy".equals(currentTheme)) tempThemeIndex = 5;
        else if ("Pookie Pink".equals(currentTheme)) tempThemeIndex = 6;
        else if ("Snow Veil".equals(currentTheme)) tempThemeIndex = 7;
        
        final int themeIndex = tempThemeIndex;
        
        // ----- Fix Start for theme button text display -----
        // Set the button text to show the CURRENT theme name, not a hardcoded value
        themeButton.setText(themeNames[themeIndex]);
        // ----- Fix End for theme button text display -----
        
        // Set text color based on theme - black for Gold, Silent Forest, Shadow Alloy, Pookie Pink, and Snow Veil, white for others
        if ("Premium Gold".equals(currentTheme) || "Silent Forest".equals(currentTheme) || "Shadow Alloy".equals(currentTheme) || "Pookie Pink".equals(currentTheme) || "Snow Veil".equals(currentTheme)) {
            themeButton.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.black));
        } else {
            themeButton.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white));
        }
        
        // Apply the correct background tint based on the current theme
        if ("Faded Night".equals(currentTheme) || "AMOLED".equals(currentTheme) || "Amoled".equals(currentTheme)) {
            // For Faded Night theme, use the #232323 color which is more visible than pure black
            themeButton.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.amoled_surface)));
        } else {
            themeButton.setBackgroundTintList(ColorStateList.valueOf(themeColors[themeIndex]));
        }
        
        themeButton.setOnClickListener(v -> {
            // Use the themed dialog builder instead of creating a new one directly
            // ----- Fix Start for theme selection dialog -----
            MaterialAlertDialogBuilder builder = themedDialogBuilder(requireContext());
            // ----- Fix End for theme selection dialog -----
            builder.setTitle(R.string.settings_option_theme);
            
            // Get current theme for text color decisions
            // ----- Fix Start for theme selection dialog -----
            
            boolean isSnowVeilTheme = "Snow Veil".equals(currentTheme);
            // ----- Fix End for theme selection dialog -----
            
            // Create a custom adapter for the theme selection with color circles
            ArrayAdapter<String> adapter = new ArrayAdapter<String>(requireContext(), R.layout.item_theme_option, R.id.theme_name, themeNames) {
                @Override
                public View getView(int position, View convertView, ViewGroup parent) {
                    View view = super.getView(position, convertView, parent);
                    
                    // Set the theme name
                    TextView themeName = view.findViewById(R.id.theme_name);
                    themeName.setText(themeNames[position]);
                    
                    // ----- Fix Start for theme selection dialog -----
                    // Use black text for Snow Veil theme, white for all other themes
                    if (isSnowVeilTheme) {
                        themeName.setTextColor(Color.BLACK);
                    } else {
                        themeName.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white));
                    }
                    // ----- Fix End for theme selection dialog -----
                    
                    // Set the color circle
                    View colorCircle = view.findViewById(R.id.theme_color_circle);
                    GradientDrawable drawable = (GradientDrawable) colorCircle.getBackground();
                    if (position == 2) { // Faded Night position
                        drawable.setColor(ContextCompat.getColor(requireContext(), R.color.amoled_surface));
                    } else {
                        drawable.setColor(themeColors[position]);
                    }
                    
                    // ----- Fix Start for theme selection dialog -----
                    // Highlight the current selection using theme-appropriate background
                    if (position == themeIndex) {
                        if (isSnowVeilTheme) {
                            // For Snow Veil theme (light theme), use a slightly darker background
                            // that creates better contrast with white background
                            GradientDrawable highlightBg = new GradientDrawable();
                            highlightBg.setCornerRadius(8 * getResources().getDisplayMetrics().density); // 8dp
                            highlightBg.setColor(ContextCompat.getColor(requireContext(), R.color.snowveil_theme_accent));
                            view.setBackground(highlightBg);
                        } else {
                            // For dark themes, use the standard selection background
                            view.setBackgroundResource(R.drawable.selected_theme_bg);
                        }
                    } else {
                        view.setBackground(null);
                    }
                    // ----- Fix End for theme selection dialog -----
                    
                    return view;
                }
            };
            
            builder.setSingleChoiceItems(adapter, themeIndex, (dialog, which) -> {
                String newTheme = themeNames[which];
                if ("Midnight Dusk".equals(newTheme)) {
                    newTheme = "Midnight Dusk";
                } else if ("Faded Night".equals(newTheme)) {
                    newTheme = "Faded Night";
                } else if (getString(R.string.theme_gold).equals(newTheme)) {
                    newTheme = "Premium Gold";
                } else if (getString(R.string.theme_silentforest).equals(newTheme)) {
                    newTheme = "Silent Forest";
                } else if (getString(R.string.theme_shadowalloy).equals(newTheme)) {
                    newTheme = "Shadow Alloy";
                } else if (getString(R.string.theme_pookiepink).equals(newTheme)) {
                    newTheme = "Pookie Pink";
                } else if (getString(R.string.theme_snowveil).equals(newTheme)) {
                    newTheme = "Snow Veil";
                } else {
                    newTheme = "Crimson Bloom";
                }
                
                // Save theme and restart app if needed
                if (!newTheme.equals(currentTheme)) {
                    sharedPreferencesManager.sharedPreferences.edit()
                            .putString(Constants.PREF_APP_THEME, newTheme)
                            .apply();
                    
                    // Update default clock color for the new theme
                    sharedPreferencesManager.updateDefaultClockColorForTheme();

                    // Schedule app restart
                    vibrateTouch();
                    
                    // Update UI immediately (colors may not fully update until restart)
                    themeButton.setText(themeNames[which]);
                    
                    // Update button background with the right color for the selected theme
                    if ("Faded Night".equals(newTheme)) {
                        themeButton.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.amoled_surface)));
                    } else {
                        themeButton.setBackgroundTintList(ColorStateList.valueOf(themeColors[which]));
                    }
                    
                    // Apply theme changes that don't require restart
                    applyAppTheme(newTheme);
                    
                    MaterialToolbar toolbar = requireActivity().findViewById(R.id.topAppBar);
                    if (toolbar != null && "Crimson Bloom".equals(newTheme)) {
                        toolbar.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.red_theme_primary_variant));
                    } else if (toolbar != null) {
                        // Reset to default toolbar color
                        toolbar.setBackgroundColor(resolveThemeColor(R.attr.colorTopBar));
                    }
                    
                    Toast.makeText(requireContext(), R.string.settings_theme_changed, Toast.LENGTH_SHORT).show();
                    
                    // CRITICAL: Recreate the activity to apply the theme change
                    requireActivity().recreate();
                }
                dialog.dismiss();
            });
            
            builder.setNegativeButton(R.string.universal_cancel, null);
            AlertDialog dialog = builder.create();
            dialog.setOnShowListener(dialogInterface -> {
                // ----- Fix Start for theme selection dialog -----
                // Apply appropriate button text colors for the current theme
                setDialogButtonColors(dialog);
                // ----- Fix End for theme selection dialog -----
            });
            dialog.show();
        });
    }

    private void applyAppTheme(String themeName) {
        if ("Crimson Bloom".equals(themeName)) {
            // Red theme
            ContextCompat.getColor(requireContext(), R.color.red_theme_primary);
            // Apply other Red theme-specific UI changes that can be done without recreation
        } else if ("Faded Night".equals(themeName)) {
            // AMOLED theme
            ContextCompat.getColor(requireContext(), R.color.amoled_background);
            // Apply other AMOLED theme-specific UI changes that can be done without recreation
        } else if ("Midnight Dusk".equals(themeName)) {
            // Default dark theme
            ContextCompat.getColor(requireContext(), R.color.gray);
            // Apply other default theme-specific UI changes that can be done without recreation
        } else if ("Premium Gold".equals(themeName)) {
            // Gold theme
            ContextCompat.getColor(requireContext(), R.color.gold_theme_primary);
            // Apply other Gold theme-specific UI changes that can be done without recreation
        } else if ("Silent Forest".equals(themeName)) {
            // Silent Forest theme
            ContextCompat.getColor(requireContext(), R.color.silentforest_theme_primary);
            // Apply other Silent Forest theme-specific UI changes that can be done without recreation
        } else if ("Shadow Alloy".equals(themeName)) {
            // Shadow Alloy theme
            ContextCompat.getColor(requireContext(), R.color.shadowalloy_theme_primary);
            // Apply other Shadow Alloy theme-specific UI changes that can be done without recreation
        } else if ("Pookie Pink".equals(themeName)) {
            // Pookie Pink theme
            ContextCompat.getColor(requireContext(), R.color.pookiepink_theme_primary);
            // Apply other Pookie Pink theme-specific UI changes that can be done without recreation
        } else if ("Snow Veil".equals(themeName)) {
            // Snow Veil theme
            ContextCompat.getColor(requireContext(), R.color.snowveil_theme_primary);
            // Apply other Snow Veil theme-specific UI changes that can be done without recreation
        }
        // Apply other theme-agnostic UI updates
        applyThemeToUI(requireView());
    }
    // --- End Theme Spinner Logic ---


    /**
     * Queries the Camera2 API for supported FPS ranges for the primary camera of the specified type
     * and returns a list of all unique framerates supported by the hardware.
     *
     * @param cameraType The camera type (FRONT or BACK) to query.
     * @return A sorted List<Integer> of all supported frame rates. Returns default [30] on critical errors.
     */
    @ExperimentalCamera2Interop
    private List<Integer> getHardwareSupportedFrameRates(CameraType cameraType) {
        // ----- Fix Start for this method(getHardwareSupportedFrameRates)-----
        if (getContext() == null) {
            Log.e(TAG_SETTINGS, "FPS Query: Context is null.");
            return Collections.singletonList(Constants.DEFAULT_VIDEO_FRAME_RATE);
        }
        
        // Use the new CameraX utility for framerate detection
        try {
            Log.i(TAG_SETTINGS, "Using CameraX API for framerate detection");
            return com.fadcam.utils.CameraXFrameRateUtil.getHardwareSupportedFrameRates(requireContext(), cameraType);
        } catch (Exception e) {
            Log.e(TAG_SETTINGS, "Error using CameraX for framerate detection, falling back to Camera2", e);
            // Fallback to Camera2 API implementation - retain original logic
            return getHardwareSupportedFrameRatesUsingCamera2(cameraType);
        }
        // ----- Fix Ended for this method(getHardwareSupportedFrameRates)-----
    }
    
    /**
     * Original Camera2 API implementation for getting supported framerates.
     * Kept as a fallback method in case CameraX implementation fails.
     * 
     * @param cameraType The camera type (FRONT or BACK) to query.
     * @return A sorted List<Integer> of all supported frame rates.
     */
    private List<Integer> getHardwareSupportedFrameRatesUsingCamera2(CameraType cameraType) {
        Log.i(TAG_SETTINGS, "=== Getting Hardware Supported FPS for CameraType: " + cameraType + " using Camera2 API ===");
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

        // Create a set to store all possible framerates from the ranges
        Set<Integer> framerates = new TreeSet<>(); // TreeSet automatically sorts
        
        // First check for higher framerates in CamcorderProfiles
        // This is important because some devices don't report high FPS in Camera2 AE ranges
        // but do support them in CamcorderProfile
        int maxProfileFps = 30; // Default assumption
        
        try {
            // Check all quality levels for max framerates
            int cameraId = Integer.parseInt(targetCameraId);
            int[] qualities = {
                CamcorderProfile.QUALITY_HIGH, CamcorderProfile.QUALITY_2160P,
                CamcorderProfile.QUALITY_1080P, CamcorderProfile.QUALITY_720P
            };
            
            for (int quality : qualities) {
                if (CamcorderProfile.hasProfile(cameraId, quality)) {
                    CamcorderProfile profile = CamcorderProfile.get(cameraId, quality);
                    if (profile != null && profile.videoFrameRate > maxProfileFps) {
                        maxProfileFps = profile.videoFrameRate;
                        Log.d(TAG_SETTINGS, "FPS Query: Found higher framerate " + maxProfileFps + 
                              " in CamcorderProfile quality " + quality);
                    }
                }
            }
            
            // Check for specific high-framerate profiles if they exist
            if (Build.VERSION.SDK_INT >= 29) { // Android 10+
                try {
                    // Some devices have specific high-FPS profiles for 60fps/120fps
                    if (CamcorderProfile.hasProfile(cameraId, CamcorderProfile.QUALITY_HIGH_SPEED_HIGH)) {
                        CamcorderProfile profile = CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_HIGH_SPEED_HIGH);
                        if (profile != null && profile.videoFrameRate > maxProfileFps) {
                            maxProfileFps = profile.videoFrameRate;
                            Log.d(TAG_SETTINGS, "FPS Query: Found high-speed framerate " + 
                                  maxProfileFps + " in QUALITY_HIGH_SPEED_HIGH");
                        }
                    }
                    
                    if (CamcorderProfile.hasProfile(cameraId, CamcorderProfile.QUALITY_HIGH_SPEED_1080P)) {
                        CamcorderProfile profile = CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_HIGH_SPEED_1080P);
                        if (profile != null && profile.videoFrameRate > maxProfileFps) {
                            maxProfileFps = profile.videoFrameRate;
                            Log.d(TAG_SETTINGS, "FPS Query: Found high-speed framerate " + 
                                  maxProfileFps + " in QUALITY_HIGH_SPEED_1080P");
                        }
                    }
                    
                    if (CamcorderProfile.hasProfile(cameraId, CamcorderProfile.QUALITY_HIGH_SPEED_720P)) {
                        CamcorderProfile profile = CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_HIGH_SPEED_720P);
                        if (profile != null && profile.videoFrameRate > maxProfileFps) {
                            maxProfileFps = profile.videoFrameRate;
                            Log.d(TAG_SETTINGS, "FPS Query: Found high-speed framerate " + 
                                  maxProfileFps + " in QUALITY_HIGH_SPEED_720P");
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG_SETTINGS, "FPS Query: Error checking high-speed profiles: " + e.getMessage());
                }
            }
            
            Log.d(TAG_SETTINGS, "FPS Query: Maximum framerate found in CamcorderProfiles: " + maxProfileFps);
            
        } catch (NumberFormatException e) {
            Log.w(TAG_SETTINGS, "FPS Query: Could not parse camera ID as integer: " + targetCameraId);
            // Continue with Camera2 API method only
        } catch (Exception e) {
            Log.w(TAG_SETTINGS, "FPS Query: Error checking CamcorderProfiles: " + e.getMessage());
            // Continue with Camera2 API method only
        }

        if (hardwareFpsRanges == null || hardwareFpsRanges.length == 0) {
            Log.w(TAG_SETTINGS, "FPS Query: No AE FPS ranges reported by hardware for camera " + targetCameraId + ". Using CamcorderProfile.");
            // Create some basic framerates based on CamcorderProfile information
            for (int fps = 10; fps <= maxProfileFps; fps += 5) {
                if (fps <= 30 || fps % 30 == 0) { // Include all multiples of 30 over 30fps
                    framerates.add(fps);
                }
            }
            
            // Add standard framerates
            int[] standardRates = {24, 25, 30, 60, 90, 120};
            for (int rate : standardRates) {
                if (rate <= maxProfileFps) {
                    framerates.add(rate);
                }
            }
            
            if (framerates.isEmpty()) {
                framerates.add(Constants.DEFAULT_VIDEO_FRAME_RATE); // Default fallback
            }
        } else {
            Log.d(TAG_SETTINGS, "FPS Query: Hardware reported AE ranges for ID " + targetCameraId + ": " + Arrays.toString(hardwareFpsRanges));

            // Process each range to get ALL supported framerates
            for (Range<Integer> range : hardwareFpsRanges) {
                if (range != null) {
                    int lower = range.getLower();
                    int upper = range.getUpper();
                    
                    Log.d(TAG_SETTINGS, "FPS Query: Processing range " + lower + "-" + upper);
                    
                    // For most devices, framerates are available at discrete steps (usually 1fps)
                    // Add ALL integer values within the range to ensure we catch values like 59fps
                    for (int fps = lower; fps <= upper; fps++) {
                        framerates.add(fps);
                    }
                }
            }
            
            // If CamcorderProfile reported higher framerates than Camera2 API, add those too
            if (maxProfileFps > 30) {
                Log.d(TAG_SETTINGS, "FPS Query: Adding higher framerates from CamcorderProfile");
                
                // Add standard high framerates if they're supported by the profile
                int[] highRates = {60, 90, 120, 240};
                for (int rate : highRates) {
                    if (rate <= maxProfileFps) {
                        framerates.add(rate);
                        Log.d(TAG_SETTINGS, "FPS Query: Added " + rate + "fps from CamcorderProfile");
                    }
                }
            }
        }

        // Ensure we have at least one value (the default)
        if (framerates.isEmpty()) {
            Log.e(TAG_SETTINGS, "FPS Query: No valid framerates found from hardware ranges. Adding default: " + Constants.DEFAULT_VIDEO_FRAME_RATE);
            framerates.add(Constants.DEFAULT_VIDEO_FRAME_RATE);
        }

        // Convert to list and ensure the list is sorted (which TreeSet already does)
        List<Integer> finalSupportedRates = new ArrayList<>(framerates);
        
        // If the list is too large (some devices might report hundreds of values),
        // we could optionally filter to keep just common/useful values or step at 5-10fps intervals
        if (finalSupportedRates.size() > 20) {
            Log.w(TAG_SETTINGS, "FPS Query: Large number of framerates detected (" + finalSupportedRates.size() + 
                  "), keeping only useful values for UI");
            
            // Filter to keep standard values + any higher FPS values
            Set<Integer> filteredRates = new TreeSet<>();
            
            // Important standard rates to always include if supported
            int[] standardRates = {24, 25, 30, 50, 60, 90, 120, 240};
            for (int rate : standardRates) {
                if (framerates.contains(rate)) {
                    filteredRates.add(rate);
                }
            }
            
            // Also include significant non-standard rates 
            // This handles cases like 59.94fps (which is often rounded to 59 or 60)
            for (int fps : framerates) {
                // Include rates divisible by 5 (e.g., 5, 10, 15, 20, 25...)
                if (fps % 5 == 0 && fps <= 60) {
                    filteredRates.add(fps);
                }
                // Include all higher framerates (e.g., 72, 90, 120, etc.)
                else if (fps > 60) {
                    filteredRates.add(fps);
                }
            }
            
            // If we've excluded the default rate by accident, add it back
            if (!filteredRates.contains(Constants.DEFAULT_VIDEO_FRAME_RATE) && 
                framerates.contains(Constants.DEFAULT_VIDEO_FRAME_RATE)) {
                filteredRates.add(Constants.DEFAULT_VIDEO_FRAME_RATE);
            }
            
            // Replace the full list with our filtered list
            finalSupportedRates = new ArrayList<>(filteredRates);
            Log.d(TAG_SETTINGS, "FPS Query: Filtered to " + finalSupportedRates.size() + " useful framerates");
        }
        
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
        final TextView infoText = dialogView.findViewById(R.id.audio_info_text);

        // ----- Fix Start for Audio Settings dialog text color -----
        // Check if we're using Snow Veil theme for text color
        String currentTheme = sharedPreferencesManager.sharedPreferences.getString(Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);
        boolean isSnowVeilTheme = "Snow Veil".equals(currentTheme);
        boolean isFadedNightTheme = "Faded Night".equals(currentTheme);
        
        int textColor = ContextCompat.getColor(requireContext(), isSnowVeilTheme ? android.R.color.black : android.R.color.white);
        
        summaryText.setTextColor(textColor);
        if (infoText != null) infoText.setTextColor(textColor);
        if (bitrateLabel != null) bitrateLabel.setTextColor(textColor);
        if (samplingRateLabel != null) samplingRateLabel.setTextColor(textColor);
        
        // Set reset button text color to white specifically for Faded Night theme
        if (isFadedNightTheme && resetButton != null) {
            resetButton.setTextColor(Color.WHITE);
        }
        // ----- Fix End for Audio Settings dialog text color -----

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

        MaterialAlertDialogBuilder builder = themedDialogBuilder(requireContext());
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
            
            // ----- Fix Start for Audio Settings dialog button colors -----
            // Apply theme-specific colors to dialog buttons
            setDialogButtonColors(dialog);
            // ----- Fix End for Audio Settings dialog button colors -----
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

    // ----- Fix Start for this method(setupLocationEmbedSwitch_independent)-----
    private void setupLocationEmbedSwitch(MaterialSwitch switchView) {
        if (switchView == null) return;
        
        // Store reference to the switch for use in permission callback
        locationEmbedSwitch = switchView;
        
        // Get current preference value
        boolean isLocationEmbeddingEnabled = sharedPreferencesManager.isLocationEmbeddingEnabled();
        Log.d(TAG_SETTINGS, "Setting up location embedding switch. Current preference: " + isLocationEmbeddingEnabled);
        
        // Set UI state to match preference
        switchView.setChecked(isLocationEmbeddingEnabled);
        
        switchView.setOnCheckedChangeListener((buttonView, isChecked) -> {
            vibrateTouch();
            
            Log.d(TAG_SETTINGS, "Location embedding switch toggled by user to: " + isChecked);
            
            if (isChecked) {
                // Check if location permission is granted
                if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
                    
                    // Permission not granted, show dialog and turn off switch
                    Log.d(TAG_SETTINGS, "Location permission not granted, showing permission dialog");
                    switchView.setChecked(false);
                    showLocationEmbedPermissionDialog(switchView);
                    
                } else {
                    // Permission granted, update preference and notify service
                    Log.d(TAG_SETTINGS, "Location permission already granted, enabling embedding");
                    
                    // Update preference to match user selection (ON)
                    sharedPreferencesManager.setLocationEmbeddingEnabled(true);
                    
                    // Show confirmation toast
                    Toast.makeText(requireContext(), R.string.location_embedding_enabled, Toast.LENGTH_SHORT).show();
                    
                    // Notify service about the change
                    initializeLocationHelpersInService();
                }
            } else {
                // User turned embedding OFF
                Log.d(TAG_SETTINGS, "User disabled location embedding");
                
                // Update preference to match user selection (OFF)
                sharedPreferencesManager.setLocationEmbeddingEnabled(false);
                
                // Notify service about the change
                initializeLocationHelpersInService();
            }
        });
    }
    // ----- Fix Ended for this method(setupLocationEmbedSwitch_independent)-----

    // ----- Fix Start for this method(showLocationEmbedPermissionDialog)-----
    private void showLocationEmbedPermissionDialog(MaterialSwitch switchView) {
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.location_permission_title)
            .setMessage(R.string.location_permission_message)
            .setPositiveButton(R.string.allow_location_access, (dialog, which) -> {
                // Request permission but DON'T enable the switch yet
                // The switch will stay OFF until user manually toggles it after permission granted
                Log.d(TAG_SETTINGS, "User clicked Allow in location permission dialog");
                requestLocationPermission();
            })
            .setNegativeButton(R.string.universal_cancel, (dialog, which) -> {
                // Ensure switch is OFF since permission was denied
                Log.d(TAG_SETTINGS, "User clicked Cancel in location permission dialog");
                switchView.setChecked(false);
                sharedPreferencesManager.setLocationEmbeddingEnabled(false);
            })
            .setCancelable(false)
            .show();
    }
    // ----- Fix Ended for this method(showLocationEmbedPermissionDialog)-----

    // --- Video Bitrate Dialog ---
    private void showVideoBitrateDialog() {
        Context context = requireContext();
        LayoutInflater inflater = LayoutInflater.from(context);
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
        
        // ----- Fix Start for Bitrate dialog text color -----
        // Check if we're using Snow Veil theme for text color
        String currentTheme = sharedPreferencesManager.sharedPreferences.getString(Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);
        boolean isSnowVeilTheme = "Snow Veil".equals(currentTheme);
        int textColor = ContextCompat.getColor(context, isSnowVeilTheme ? android.R.color.black : android.R.color.white);
        helper.setTextColor(textColor);
        // ----- Fix End for Bitrate dialog text color -----
        
        layout.addView(input);
        layout.addView(helper);
        Runnable updateHelper = () -> {
            String text = input.getText().toString().trim();
            int color = textColor;
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
        MaterialAlertDialogBuilder builder = themedDialogBuilder(context)
            .setTitle(getString(R.string.setting_video_bitrate_title))
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
            });
        // Set the dialog message as a white TextView
        TextView messageView = new TextView(context);
        messageView.setText(getString(R.string.bitrate_explanation_text));
        messageView.setTextColor(textColor);
        messageView.setTextSize(14);
        messageView.setPadding(0, padding / 2, 0, padding / 2);
        layout.addView(messageView, 0); // Add at the top
        builder.setView(layout);
        AlertDialog dialog = builder.show();
        
        // Set button colors based on theme
        setDialogButtonColors(dialog);
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
        final int[] valuesMb = {500, 1024, 2048, 4096, -1};
        int currentSizeMb = sharedPreferencesManager.getVideoSplitSizeMb();
        int checkedItem = -1;
        for (int i = 0; i < valuesMb.length -1; i++) {
            if (valuesMb[i] == currentSizeMb) {
                checkedItem = i;
                break;
            }
        }
        int color = ContextCompat.getColor(requireContext(), android.R.color.white);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(requireContext(), android.R.layout.simple_list_item_single_choice, items) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView text1 = view.findViewById(android.R.id.text1);
                if (text1 != null) text1.setTextColor(color);
                return view;
            }
        };
        themedDialogBuilder(requireContext())
            .setTitle("Set Video Split Size")
            .setSingleChoiceItems(adapter, checkedItem, (dialog, which) -> {
                if (which == items.length - 1) {
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

        MaterialAlertDialogBuilder builder = themedDialogBuilder(requireContext());
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

    // ----- Fix Start for this class(SettingsFragment) -----
    /**
     * Helper method to initialize location helpers in the active RecordingService if it's running
     */
    private void initializeLocationHelpersInService() {
        Context context = getContext();
        if (context == null) return;
        
        // Get the current preference states
        boolean locationEnabled = sharedPreferencesManager.isLocalisationEnabled();
        boolean embedLocationEnabled = sharedPreferencesManager.isLocationEmbeddingEnabled();
        boolean hasLocationPermission = ContextCompat.checkSelfPermission(context, 
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        
        Log.d(TAG_SETTINGS, "=== Initializing location helpers in service ===");
        Log.d(TAG_SETTINGS, "Location watermark enabled: " + locationEnabled);
        Log.d(TAG_SETTINGS, "Location embedding enabled: " + embedLocationEnabled);
        Log.d(TAG_SETTINGS, "Has location permission: " + hasLocationPermission);
        
        // Send broadcast to notify service about location preference changes
        Intent intent = new Intent(Constants.INTENT_ACTION_REINITIALIZE_LOCATION);
        
        // Add important flags to ensure delivery
        intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        
        // Add extra data to the intent
        intent.putExtra("force_init", true);
        intent.putExtra("embed_location", embedLocationEnabled);
        intent.putExtra("has_permission", hasLocationPermission);
        
        // Send the broadcast
        Log.d(TAG_SETTINGS, "Sending location helpers broadcast to service");
        context.sendBroadcast(intent);
    }
    // ----- Fix Ended for this class(SettingsFragment) -----

    // Add the AppLock setup method
    private void setupAppLockButton() {
        if (appLockConfigureButton == null) return;
        
        appLockConfigureButton.setOnClickListener(v -> {
            showAppLockConfigDialog();
        });
    }
    
    // Method to show the AppLock configuration dialog
    private void showAppLockConfigDialog() {
        boolean isEnrolled = AppLock.isEnrolled(requireContext());
        boolean isEnabled = sharedPreferencesManager.isAppLockEnabled();
        List<String> options = new ArrayList<>();
        if (isEnabled) {
            options.add(getString(R.string.applock_disable));
        } else {
            options.add(getString(R.string.applock_enable));
        }
        if (!isEnrolled) {
            options.add(getString(R.string.applock_set_pin));
        } else {
            options.add(getString(R.string.applock_change_pin));
            options.add(getString(R.string.applock_remove_pin));
        }
        
        // ----- Fix Start for App Lock dialog text color -----
        // Check if we're using Snow Veil theme for text color
        String currentTheme = sharedPreferencesManager.sharedPreferences.getString(Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);
        boolean isSnowVeilTheme = "Snow Veil".equals(currentTheme);
        int color = ContextCompat.getColor(requireContext(), isSnowVeilTheme ? android.R.color.black : android.R.color.white);
        // ----- Fix End for App Lock dialog text color -----
        
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(requireContext(), android.R.layout.simple_list_item_1, options) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView text1 = view.findViewById(android.R.id.text1);
                if (text1 != null) text1.setTextColor(color);
                return view;
            }
        };
        
        AlertDialog dialog = themedDialogBuilder(requireContext())
            .setTitle(R.string.applock_dialog_title)
            .setAdapter(adapter, (dialogInterface, which) -> {
                String selectedOption = options.get(which);
                if (selectedOption.equals(getString(R.string.applock_enable))) {
                    if (isEnrolled) {
                        verifyPinThenExecute(() -> setAppLockEnabled(true), R.string.applock_verify_to_enable);
                    } else {
                        showPinCreationDialog(true);
                    }
                } else if (selectedOption.equals(getString(R.string.applock_disable))) {
                    verifyPinThenExecute(() -> setAppLockEnabled(false), R.string.applock_verify_to_disable);
                } else if (selectedOption.equals(getString(R.string.applock_set_pin)) || 
                           selectedOption.equals(getString(R.string.applock_change_pin))) {
                    if (isEnrolled) {
                        verifyPinThenExecute(() -> showPinCreationDialog(isEnabled), R.string.applock_verify_to_change);
                    } else {
                        showPinCreationDialog(isEnabled);
                    }
                } else if (selectedOption.equals(getString(R.string.applock_remove_pin))) {
                    verifyPinThenExecute(() -> {
                        AppLock appLock = AppLock.getInstance(requireContext());
                        appLock.invalidateEnrollments();
                        setAppLockEnabled(false);
                        Toast.makeText(requireContext(), R.string.applock_pin_removed, Toast.LENGTH_SHORT).show();
                    }, R.string.applock_verify_to_remove);
                }
            })
            .show();
            
        // Apply button colors for Snow Veil theme
        setDialogButtonColors(dialog);
    }
    
    /**
     * Verifies the current PIN and executes the action if successful.
     * @param action The action to execute after successful verification
     * @param titleResId Resource ID for the verification dialog title
     */
    private void verifyPinThenExecute(Runnable action, int titleResId) {
        // Show a toast message indicating what we're verifying for
        Toast.makeText(requireContext(), getString(titleResId), Toast.LENGTH_SHORT).show();
        
        new UnlockDialogBuilder(requireActivity())
            .onUnlocked(() -> {
                // Successfully verified PIN, execute the action
                if (action != null) {
                    action.run();
                }
            })
            .onCanceled(() -> {
                // User canceled verification, do nothing
                Toast.makeText(requireContext(), R.string.applock_verification_canceled, Toast.LENGTH_SHORT).show();
            })
            .show();
    }

    // Method to handle PIN creation/change using the dialog
    private void showPinCreationDialog(boolean enableAfterCreation) {
        new LockCreationDialogBuilder(requireActivity())
            .onCanceled(() -> {
                // PIN creation canceled
            })
            .onLockCreated(() -> {
                // PIN successfully created
                if (enableAfterCreation) {
                    setAppLockEnabled(true);
                }
                Toast.makeText(requireContext(), R.string.applock_pin_created, Toast.LENGTH_SHORT).show();
            })
            .show();
    }

    // Method to enable/disable AppLock
    private void setAppLockEnabled(boolean enabled) {
        sharedPreferencesManager.setAppLockEnabled(enabled);
        
        String message = enabled ? 
            getString(R.string.applock_enable) + " " + getString(R.string.universal_ok) : 
            getString(R.string.applock_disable) + " " + getString(R.string.universal_ok);
        
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        sharedPreferencesManager = SharedPreferencesManager.getInstance(requireContext());
        
        // Initialize UI components
        resolutionSpinner = view.findViewById(R.id.resolution_spinner);
        frameRateSpinner = view.findViewById(R.id.framerate_spinner);
        codecSpinner = view.findViewById(R.id.codec_spinner);
        watermarkSpinner = view.findViewById(R.id.watermark_spinner);
        MaterialButton readmeButton = view.findViewById(R.id.readme_button);
        MaterialButton languageChooseButton = view.findViewById(R.id.language_choose_button);
        
        // ----- Fix Start for README button text color -----
        // Always set README button text color to white for better visibility
        if (readmeButton != null) {
            readmeButton.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white));
        }
        // ----- Fix End for README button text color -----
        
        cameraSelectionToggle = view.findViewById(R.id.camera_selection_toggle);
        
        // Initialize and setup the lens section
        backCameraLensSpinner = view.findViewById(R.id.back_camera_lens_spinner);
        backCameraLensLayout = view.findViewById(R.id.back_camera_lens_layout);
        backCameraLensDivider = view.findViewById(R.id.back_camera_lens_divider);
        
        // Initialize orientation spinner
        orientationSpinner = view.findViewById(R.id.orientation_spinner);
        
        // Initialize App Lock button
        appLockConfigureButton = view.findViewById(R.id.app_lock_configure_button);
        
        // ... existing code ...
        
        setupSettingsLanguageDialog(languageChooseButton);
        setupThemeSpinner(view);
        setupOrientationSpinner();
        setupAppLockButton();
        
        // Apply theme-specific UI adjustments for Snow Veil theme
        String currentTheme = sharedPreferencesManager.sharedPreferences.getString(Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);
        if ("Snow Veil".equals(currentTheme)) {
            applySnowVeilThemeToUI(view);
        }
        
        // ... rest of existing code ...
    }
    
    // New method to handle Snow Veil theme UI adjustments
    private void applySnowVeilThemeToUI(View rootView) {
        // Find all icon views and set them to black for better contrast
        tintAllIcons(rootView, Color.BLACK);
        
        // Ensure all text views have proper contrast
        ensureTextContrast(rootView);
        
        // ----- Fix Start: Explicitly set README and REVIEW buttons to white -----
        // Force white text for specific buttons regardless of theme
        MaterialButton readmeButton = rootView.findViewById(R.id.readme_button);
        if (readmeButton != null) {
            readmeButton.setTextColor(Color.WHITE);
        }
        
        MaterialButton reviewButton = rootView.findViewById(R.id.review_button);
        if (reviewButton != null) {
            reviewButton.setTextColor(Color.WHITE);
        }
        // ----- Fix End: Explicitly set README and REVIEW buttons to white -----
        
        // ----- Fix Start: Make dividers darker for better visibility in Snow Veil theme -----
        // Find all divider views and set them to a darker color
        findAndColorDividers(rootView);
        // ----- Fix End: Make dividers darker for better visibility in Snow Veil theme -----
    }
    
    /**
     * Recursively finds all divider views (1dp height Views with listDivider background)
     * and sets them to a darker color for better visibility in Snow Veil theme
     */
    private void findAndColorDividers(View view) {
        if (view instanceof ViewGroup) {
            ViewGroup viewGroup = (ViewGroup) view;
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                View child = viewGroup.getChildAt(i);
                findAndColorDividers(child);
            }
        } else if (view != null && view.getLayoutParams() != null && 
                  view.getLayoutParams().height == (int) (1 * getResources().getDisplayMetrics().density)) {
            // This is likely a divider (1dp height View)
            // Check if it has the listDivider background or any background set
            Drawable background = view.getBackground();
            if (background != null) {
                // Set a dark gray color for dividers (using color with 20% opacity for subtle appearance)
                view.setBackgroundColor(Color.argb(51, 0, 0, 0)); // #33000000 (20% black)
            }
        }
    }
    
    // Helper method to tint all icons in the view hierarchy
    private void tintAllIcons(View view, int color) {
        if (view instanceof ViewGroup) {
            ViewGroup viewGroup = (ViewGroup) view;
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                tintAllIcons(viewGroup.getChildAt(i), color);
            }
        } else if (view instanceof ImageView) {
            ((ImageView) view).setColorFilter(color);
        } else if (view instanceof MaterialButton) {
            MaterialButton button = (MaterialButton) view;
            if (button.getIcon() != null) {
                button.setIconTint(ColorStateList.valueOf(color));
            }
        }
    }
    
    // Helper method to ensure text has proper contrast
    private void ensureTextContrast(View view) {
        if (view instanceof ViewGroup) {
            ViewGroup viewGroup = (ViewGroup) view;
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                ensureTextContrast(viewGroup.getChildAt(i));
            }
        } else if (view instanceof TextView) {
            TextView textView = (TextView) view;
            // Special handling for readme and review buttons - always keep them white
            int viewId = view.getId();
            if (viewId == R.id.readme_button || viewId == R.id.review_button) {
                textView.setTextColor(Color.WHITE);
                return;
            }
            
            // Only override if the text is very light (poor contrast against white)
            int currentColor = textView.getCurrentTextColor();
            if (isLightColor(currentColor)) {
                textView.setTextColor(Color.BLACK);
            }
        }
    }
    
    // Helper method to determine if a color is light
    private boolean isLightColor(int color) {
        double darkness = 1 - (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255;
        return darkness < 0.5; // If darkness is less than 0.5, it's a light color
    }

    // ----- Fix Start: Apply theme colors to headings, buttons, toggles using theme attributes -----
    private int resolveThemeColor(int attr) {
        // Special handling for Midnight Dusk theme's colorButton
        if (attr == R.attr.colorButton) {
            String currentTheme = sharedPreferencesManager.sharedPreferences.getString(Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);
            if ("Midnight Dusk".equals(currentTheme)) {
                // Return purple directly for the Midnight Dusk theme's buttons
                return ContextCompat.getColor(requireContext(), R.color.colorPrimary); // #cfbafd
            }
        }
        
        // Normal theme attribute resolution for other cases
        android.util.TypedValue typedValue = new android.util.TypedValue();
        requireContext().getTheme().resolveAttribute(attr, typedValue, true);
        return typedValue.data;
    }

    private void applyThemeToUI(View view) {
        // Headings (only those with real IDs)
        int headingColor = resolveThemeColor(R.attr.colorHeading);
        for (int id : new int[]{R.id.location_title, R.id.location_embed_title, R.id.audio_title, R.id.debug_title, R.id.onboarding_title}) {
            TextView tv = view.findViewById(id);
            if (tv != null) tv.setTextColor(headingColor);
        }
        // Buttons
        int buttonColor = resolveThemeColor(R.attr.colorButton);
        // ... rest of method ...
    }
    // ----- Fix End: Apply theme colors to headings, buttons, toggles using theme attributes -----

    // ----- Fix Start: Add themedDialogBuilder helper to SettingsFragment -----
    private MaterialAlertDialogBuilder themedDialogBuilder(Context context) {
        int dialogTheme = R.style.ThemeOverlay_FadCam_Dialog;
        SharedPreferencesManager spm = SharedPreferencesManager.getInstance(context);
        String currentTheme = spm.sharedPreferences.getString(Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);
        if ("Crimson Bloom".equals(currentTheme)) dialogTheme = R.style.ThemeOverlay_FadCam_Red_Dialog;
        else if ("Faded Night".equals(currentTheme)) dialogTheme = R.style.ThemeOverlay_FadCam_Amoled_MaterialAlertDialog;
        else if ("Snow Veil".equals(currentTheme)) dialogTheme = R.style.ThemeOverlay_FadCam_SnowVeil_Dialog;
        return new MaterialAlertDialogBuilder(context, dialogTheme);
    }
    // ----- Fix End: Add themedDialogBuilder helper to SettingsFragment -----

    /**
     * Sets button text colors for dialogs based on the current theme
     * Use this after showing the dialog to ensure proper contrast
     * @param dialog The dialog whose buttons need color adjustment
     */
    private void setDialogButtonColors(AlertDialog dialog) {
        if (dialog == null) return;
        
        // Check if we're using Snow Veil theme
        String currentTheme = sharedPreferencesManager.sharedPreferences.getString(Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);
        boolean isSnowVeilTheme = "Snow Veil".equals(currentTheme);
        
        // Set text color for all dialog buttons
        int buttonTextColor = isSnowVeilTheme ? Color.BLACK : Color.WHITE;
        
        if (dialog.getButton(DialogInterface.BUTTON_POSITIVE) != null) {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setTextColor(buttonTextColor);
        }
        if (dialog.getButton(DialogInterface.BUTTON_NEGATIVE) != null) {
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setTextColor(buttonTextColor);
        }
        if (dialog.getButton(DialogInterface.BUTTON_NEUTRAL) != null) {
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setTextColor(buttonTextColor);
        }
    }

    // --- App Icon Selection Logic ---
    private void setupAppIconButton(View view) {
        appIconChooseButton = view.findViewById(R.id.app_icon_choose_button);
        if (appIconChooseButton == null) return;
        
        // Get currently selected app icon
        String currentIcon = sharedPreferencesManager.sharedPreferences.getString(Constants.PREF_APP_ICON, Constants.APP_ICON_DEFAULT);
        
        // Set the button text to show the current icon name
        appIconChooseButton.setText(currentIcon.equals(Constants.APP_ICON_DEFAULT) ? 
                getString(R.string.app_icon_default) : getString(R.string.app_icon_alternative));
                
        // Set proper text color based on current theme
        String currentTheme = sharedPreferencesManager.sharedPreferences.getString(Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);
        if ("Premium Gold".equals(currentTheme) || "Silent Forest".equals(currentTheme) || 
            "Shadow Alloy".equals(currentTheme) || "Pookie Pink".equals(currentTheme) || 
            "Snow Veil".equals(currentTheme)) {
            appIconChooseButton.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.black));
        } else {
            appIconChooseButton.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white));
        }
        
        // Set button click listener to show icon selection dialog
        appIconChooseButton.setOnClickListener(v -> showAppIconSelectionDialog());
    }
    
    /**
     * Shows a dialog for selecting the app icon
     */
    private void showAppIconSelectionDialog() {
        // Get current app icon
        String currentIcon = sharedPreferencesManager.sharedPreferences.getString(Constants.PREF_APP_ICON, Constants.APP_ICON_DEFAULT);
        
        // Create dialog builder with themed style
        MaterialAlertDialogBuilder builder = themedDialogBuilder(requireContext());
        builder.setTitle(R.string.app_icon_dialog_title);
        
        // Icon options
        String[] iconNames = {
            getString(R.string.app_icon_default),
            getString(R.string.app_icon_alternative)
        };
        
        // Create a custom list adapter for the app icons
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(requireContext(), R.layout.item_app_icon_option, R.id.app_icon_name, iconNames) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                
                // Get references to views
                TextView iconName = view.findViewById(R.id.app_icon_name);
                ImageView iconPreview = view.findViewById(R.id.app_icon_preview);
                RadioButton radioButton = view.findViewById(R.id.app_icon_radio_button);
                
                // Set icon preview image
                if (position == 0) {
                    // Default icon
                    iconPreview.setImageResource(R.mipmap.ic_launcher);
                } else {
                    // Detective icon
                    iconPreview.setImageResource(R.mipmap.ic_launcher_2);
                }
                
                // Set text color based on current theme
                String currentTheme = sharedPreferencesManager.sharedPreferences.getString(
                        Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);
                boolean isSnowVeilTheme = "Snow Veil".equals(currentTheme);
                
                if (isSnowVeilTheme) {
                    iconName.setTextColor(Color.BLACK);
                } else {
                    iconName.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white));
                }
                
                // Select current icon
                String iconKey = position == 0 ? Constants.APP_ICON_DEFAULT : Constants.APP_ICON_ALTERNATIVE;
                radioButton.setChecked(iconKey.equals(currentIcon));
                
                // Highlight background if selected
                if (radioButton.isChecked()) {
                    if (isSnowVeilTheme) {
                        // Light highlight for Snow Veil theme
                        GradientDrawable highlightBg = new GradientDrawable();
                        highlightBg.setCornerRadius(8 * getResources().getDisplayMetrics().density); // 8dp
                        highlightBg.setColor(ContextCompat.getColor(requireContext(), R.color.snowveil_theme_accent));
                        view.setBackground(highlightBg);
                    } else {
                        // Standard selection background for other themes
                        view.setBackgroundResource(R.drawable.selected_theme_bg);
                    }
                } else {
                    view.setBackground(null);
                }
                
                return view;
            }
        };
        
        // Set click listener for each item
        builder.setSingleChoiceItems(adapter, currentIcon.equals(Constants.APP_ICON_DEFAULT) ? 0 : 1, (dialog, which) -> {
            String newIcon = which == 0 ? Constants.APP_ICON_DEFAULT : Constants.APP_ICON_ALTERNATIVE;
            
            if (!newIcon.equals(currentIcon)) {
                // Save the new icon preference
                sharedPreferencesManager.sharedPreferences.edit()
                        .putString(Constants.PREF_APP_ICON, newIcon)
                        .apply();
                
                // Update button text
                appIconChooseButton.setText(which == 0 ? 
                        getString(R.string.app_icon_default) : getString(R.string.app_icon_alternative));
                
                // Apply the icon change
                updateAppIcon(newIcon);
                
                // Give feedback
                vibrateTouch();
                Toast.makeText(requireContext(), R.string.app_icon_changed, Toast.LENGTH_SHORT).show();
            }
            
            dialog.dismiss();
        });
        
        builder.setNegativeButton(R.string.universal_cancel, null);
        
        // Create and show dialog
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(dialogInterface -> {
            // Apply appropriate button text colors for the current theme
            setDialogButtonColors(dialog);
        });
        dialog.show();
    }
    
    /**
     * Updates the app icon by enabling/disabling activity-alias components
     * @param iconKey The key of the icon to enable
     */
    private void updateAppIcon(String iconKey) {
        PackageManager pm = requireContext().getPackageManager();
        
        // Component names for our activity aliases
        ComponentName defaultIcon = new ComponentName(requireContext(), "com.fadcam.MainActivity");
        ComponentName alternativeIcon = new ComponentName(requireContext(), "com.fadcam.MainActivity.AlternativeIcon");
        
        // Enable/disable appropriate components based on selected icon
        if (Constants.APP_ICON_DEFAULT.equals(iconKey)) {
            pm.setComponentEnabledSetting(defaultIcon, 
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED, 
                    PackageManager.DONT_KILL_APP);
            pm.setComponentEnabledSetting(alternativeIcon, 
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, 
                    PackageManager.DONT_KILL_APP);
        } else {
            pm.setComponentEnabledSetting(defaultIcon, 
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, 
                    PackageManager.DONT_KILL_APP);
            pm.setComponentEnabledSetting(alternativeIcon, 
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED, 
                    PackageManager.DONT_KILL_APP);
        }
    }


}