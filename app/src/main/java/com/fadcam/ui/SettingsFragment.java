package com.fadcam.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.media.CamcorderProfile;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.DocumentsContract;
import android.util.Log; // Make sure Log is imported
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.RadioButton; // Import RadioButton
import android.widget.RadioGroup;  // Import RadioGroup
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import android.content.Intent; // Add Intent import
import androidx.localbroadcastmanager.content.LocalBroadcastManager; // OR use ContextCompat if not using LocalBroadcastManager
import androidx.core.content.ContextCompat; // If using standard broadcast

public class SettingsFragment extends Fragment {

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
    private Spinner languageSpinner; // Declare languageSpinner

    private MaterialButtonToggleGroup cameraSelectionToggle;
    private MaterialSwitch locationSwitch; // Declare locationSwitch
    private MaterialSwitch debugSwitch; // Declare debugSwitch

    private View view; // Make sure view is accessible

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


    // --- Activity Result Launcher Initialization & onCreate---
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Use standard Log
        android.util.Log.d(TAG_SETTINGS,"onCreate: Initializing fragment.");
        // Initialize helpers/managers FIRST
        locationHelper = new LocationHelper(requireContext());
        sharedPreferencesManager = SharedPreferencesManager.getInstance(requireContext());
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
        languageSpinner = view.findViewById(R.id.language_spinner);
        MaterialToolbar toolbar = view.findViewById(R.id.topAppBar);
        MaterialButton readmeButton = view.findViewById(R.id.readme_button);
        cameraSelectionToggle = view.findViewById(R.id.camera_selection_toggle);
        resolutionSpinner = view.findViewById(R.id.resolution_spinner);
        frameRateSpinner = view.findViewById(R.id.framerate_spinner);
        codecSpinner = view.findViewById(R.id.codec_spinner);
        watermarkSpinner = view.findViewById(R.id.watermark_spinner);
        locationSwitch = view.findViewById(R.id.location_toggle_group);
        debugSwitch = view.findViewById(R.id.debug_toggle_group);
        MaterialButton reviewButton = view.findViewById(R.id.review_button);
        themeSpinner = view.findViewById(R.id.theme_spinner); // Initialize themeSpinner

        // Initialize Storage UI elements
        storageLocationRadioGroup = view.findViewById(R.id.storage_location_radio_group);
        radioInternalStorage = view.findViewById(R.id.radio_internal_storage);
        radioCustomLocation = view.findViewById(R.id.radio_custom_location);
        buttonChooseCustomLocation = view.findViewById(R.id.button_choose_custom_location);
        tvCustomLocationPath = view.findViewById(R.id.tv_custom_location_path);

        // Setup components
        setupLanguageSpinner(languageSpinner);
        readmeButton.setOnClickListener(v -> showReadmeDialog());
        setupCameraSelectionToggle(view, cameraSelectionToggle);
        setupResolutionSpinner();
        setupFrameRateSpinner();
        setupCodecSpinner();
        setupWatermarkSpinner(view, watermarkSpinner);
        setupLocationSwitch(locationSwitch); // Use switch variable
        setupDebugSwitch(debugSwitch);     // Use switch variable
        reviewButton.setOnClickListener(v -> openInAppBrowser("https://forms.gle/DvUoc1v9kB2bkFiS6"));
        setupThemeSpinner(view);
        setupFrameRateNoteText();
        setupCodecNoteText();

        // Setup listeners for storage options
        setupStorageLocationOptions();
        // Set initial UI state based on saved preferences
        updateStorageLocationUI();

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


    // Loads the available camcorder profiles for cameras
    private void initializeCamcorderProfiles() {
        camcorderProfilesAvailables.clear(); // Clear previous data
        Log.d(TAG_SETTINGS, "Initializing Camcorder Profiles");
        for(CameraType type : CameraType.values()) {
            List<CamcorderProfile> camcorderProfiles = getCamcorderProfiles(type);
            if(!camcorderProfiles.isEmpty()) {
                Log.d(TAG_SETTINGS,"Profiles found for " + type + ": " + camcorderProfiles.size());
                camcorderProfilesAvailables.put(type, camcorderProfiles);
            } else {
                Log.w(TAG_SETTINGS,"No profiles found for " + type);
            }
        }
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
        updateStorageLocationUI(); // Update storage UI on resume
        updateResolutionSpinner(); // Ensure spinner reflects current camera
        updateFrameRateSpinner(); // Ensure framerate reflects resolution
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


    private void setupCameraSelectionToggle(View view, MaterialButtonToggleGroup toggleGroup) {
        if(toggleGroup == null) return;
        syncCameraSwitch(view, toggleGroup); // Initial setup based on prefs

        toggleGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                // Ensure UI matches the checked state immediately
                updateButtonAppearance(view.findViewById(R.id.button_back_camera), checkedId == R.id.button_back_camera);
                updateButtonAppearance(view.findViewById(R.id.button_front_camera), checkedId == R.id.button_front_camera);

                // Save the preference AFTER visual update
                CameraType selectedCamera = (checkedId == R.id.button_front_camera) ? CameraType.FRONT : CameraType.BACK;
                sharedPreferencesManager.sharedPreferences.edit().putString(Constants.PREF_CAMERA_SELECTION, selectedCamera.toString()).apply();
                Log.d(TAG_SETTINGS, "Camera selection changed to: " + selectedCamera);
                vibrateTouch(); // Add feedback
                updateResolutionSpinner(); // Update resolutions for the new camera
                updateFrameRateSpinner(); // Update frame rates as well
            }
        });
    }


    private void setupResolutionSpinner() {
        updateResolutionSpinner(); // Populate initially
        resolutionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // Get the current camera to find correct profiles
                CameraType currentCamera = sharedPreferencesManager.getCameraSelection();
                List<CamcorderProfile> camcorderProfiles = camcorderProfilesAvailables.get(currentCamera);

                if(camcorderProfiles != null && position >= 0 && position < camcorderProfiles.size()) {
                    CamcorderProfile selectedProfile = camcorderProfiles.get(position);
                    // Save the selected width and height
                    sharedPreferencesManager.sharedPreferences.edit()
                            .putInt(Constants.PREF_VIDEO_RESOLUTION_WIDTH, selectedProfile.videoFrameWidth)
                            .putInt(Constants.PREF_VIDEO_RESOLUTION_HEIGHT, selectedProfile.videoFrameHeight)
                            .apply();
                    Log.d(TAG_SETTINGS,"Resolution preference saved: "+ selectedProfile.videoFrameWidth + "x" + selectedProfile.videoFrameHeight);
                    updateFrameRateSpinner(); // Update compatible frame rates
                } else {
                    Log.e(TAG_SETTINGS, "Error getting selected profile for resolution saving.");
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


    private void setupFrameRateSpinner() {
        updateFrameRateSpinner(); // Populate initially
        frameRateSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                CameraType selectedCamera = sharedPreferencesManager.getCameraSelection();
                List<Integer> compatibleRates = getCompatiblesVideoFrameRates(selectedCamera);
                if (position >= 0 && position < compatibleRates.size()) {
                    int selectedRate = compatibleRates.get(position);
                    sharedPreferencesManager.sharedPreferences.edit().putInt(Constants.PREF_VIDEO_FRAME_RATE, selectedRate).apply();
                    Log.d(TAG_SETTINGS,"Frame rate preference saved: "+ selectedRate);
                } else {
                    Log.e(TAG_SETTINGS, "Error getting selected frame rate.");
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }


    private void updateFrameRateSpinner()
    {
        if(frameRateSpinner == null) return;
        CameraType selectedCamera = sharedPreferencesManager.getCameraSelection();
        Log.d(TAG_SETTINGS, "Updating frame rates for camera: "+ selectedCamera);
        List<Integer> videoFrameRatesCompatibles = getCompatiblesVideoFrameRates(selectedCamera);

        List<String> videoFrameRatesCompatiblesAsString = new ArrayList<>();
        for (Integer frameRate : videoFrameRatesCompatibles) {
            videoFrameRatesCompatiblesAsString.add(String.valueOf(frameRate));
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_item,
                videoFrameRatesCompatiblesAsString
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        frameRateSpinner.setAdapter(adapter);

        if(!videoFrameRatesCompatibles.isEmpty()) {
            int selectedFramerate = sharedPreferencesManager.getVideoFrameRate();
            int selectedIndex = videoFrameRatesCompatibles.indexOf(selectedFramerate);

            if (selectedIndex == -1) { // If saved framerate is not compatible anymore
                Log.w(TAG_SETTINGS, "Saved framerate "+selectedFramerate+" no longer compatible. Defaulting.");
                selectedIndex = videoFrameRatesCompatibles.indexOf(Constants.DEFAULT_VIDEO_FRAME_RATE); // Try default
                if(selectedIndex == -1) selectedIndex = 0; // Fallback to first available

                sharedPreferencesManager.sharedPreferences.edit().putInt(Constants.PREF_VIDEO_FRAME_RATE, videoFrameRatesCompatibles.get(selectedIndex)).apply();
            }

            frameRateSpinner.setSelection(selectedIndex);
            frameRateSpinner.setEnabled(videoFrameRatesCompatibles.size() > 1); // Only enable if multiple options
            Log.d(TAG_SETTINGS,"Frame rate spinner updated. Count: "+ videoFrameRatesCompatibles.size() +". Selected index: "+selectedIndex);
        } else {
            frameRateSpinner.setEnabled(false);
            Log.w(TAG_SETTINGS,"No compatible frame rates found for " + selectedCamera);
        }
    }


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


    private void setupLanguageSpinner(Spinner spinner) {
        if(spinner == null) return;
        // Setup language adapter using array resource
        ArrayAdapter<CharSequence> languageAdapter  = ArrayAdapter.createFromResource(
                requireContext(), R.array.languages_array, android.R.layout.simple_spinner_item);
        languageAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(languageAdapter);


        String savedLanguageCode = sharedPreferencesManager.getLanguage();
        int selectedIndex = getLanguageIndex(savedLanguageCode);
        spinner.setSelection(selectedIndex);
        Log.d(TAG_SETTINGS, "Language spinner set to index: " + selectedIndex + " (Code: "+savedLanguageCode+")");

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String currentSavedLanguageCode = sharedPreferencesManager.getLanguage();
                String newlySelectedLanguageCode = getLanguageCode(position);
                Log.d(TAG_SETTINGS, "Language selected: " + newlySelectedLanguageCode + " (Position: "+position+")");

                // Only apply if language actually changed
                if (!newlySelectedLanguageCode.equals(currentSavedLanguageCode)) {
                    saveLanguagePreference(newlySelectedLanguageCode);
                    // Apply the language change (recreates activity)
                    if(getActivity() instanceof MainActivity){
                        ((MainActivity) requireActivity()).applyLanguage(newlySelectedLanguageCode);
                    } else {
                        Log.e(TAG_SETTINGS, "Cannot apply language, activity is not MainActivity instance.");
                        // Maybe just recreate fragment or show toast to restart app?
                        Toast.makeText(getContext(), "Language changed. Restart app to apply.", Toast.LENGTH_LONG).show();
                    }
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
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

    /**
     * Retrieves a list of compatible video resolutions for the specified camera type.
     */
    public List<Size> getCompatiblesVideoResolutions(CameraType cameraType)
    {
        List<CamcorderProfile> camcorderProfiles = this.getCamcorderProfiles(cameraType);
        List<Size> videoResolutionCompatibles = new ArrayList<>();

        if(camcorderProfiles != null) {
            for (CamcorderProfile camcorderProfile : camcorderProfiles) {
                // Filter out null profiles just in case
                if(camcorderProfile != null){
                    videoResolutionCompatibles.add(new Size(camcorderProfile.videoFrameWidth, camcorderProfile.videoFrameHeight));
                }
            }
        }
        Log.d(TAG_SETTINGS, "Compatible resolutions for " + cameraType + ": " + videoResolutionCompatibles.size());
        return videoResolutionCompatibles;
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
    private List<CamcorderProfile> getCamcorderProfiles(CameraType cameraType) {
        List<CamcorderProfile> profiles = new ArrayList<>();
        int cameraId = cameraType.getCameraId();

        // Prioritize common qualities
        int[] qualities = {
                CamcorderProfile.QUALITY_1080P,
                CamcorderProfile.QUALITY_720P,
                CamcorderProfile.QUALITY_480P,
                // Less common / higher-end
                CamcorderProfile.QUALITY_2160P, // 4K
        };

        // Add qualities based on SDK version compatibility
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Check and add 8K - order might matter for dropdown appearance
            if(CamcorderProfile.hasProfile(cameraId, CamcorderProfile.QUALITY_8KUHD)) profiles.add(CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_8KUHD));
        }
        // Check and add 4K (check again for redundancy if SDK < S handled 2160P)
        if(!profiles.stream().anyMatch(p -> p.quality == CamcorderProfile.QUALITY_2160P) && CamcorderProfile.hasProfile(cameraId, CamcorderProfile.QUALITY_2160P)){
            profiles.add(CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_2160P));
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if(CamcorderProfile.hasProfile(cameraId, CamcorderProfile.QUALITY_2K)) profiles.add(CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_2K));
        }

        // Add standard qualities if available
        for (int quality : qualities) {
            if (CamcorderProfile.hasProfile(cameraId, quality) && !profiles.stream().anyMatch(p -> p.quality == quality)) {
                profiles.add(CamcorderProfile.get(cameraId, quality));
            }
        }

        // Ensure profiles list is not null and remove null entries
        profiles.removeIf(p -> p == null);
        Log.d(TAG_SETTINGS,"Camcorder profiles retrieved for "+cameraType +": "+profiles.size());
        return profiles;
    }

    /**
     * Retrieves the selected CamcorderProfile based on saved resolution preferences.
     */
    private CamcorderProfile getCamcorderProfile(CameraType cameraType) {
        int savedWidth = sharedPreferencesManager.sharedPreferences.getInt(Constants.PREF_VIDEO_RESOLUTION_WIDTH, Constants.DEFAULT_VIDEO_RESOLUTION.getWidth());
        int savedHeight = sharedPreferencesManager.sharedPreferences.getInt(Constants.PREF_VIDEO_RESOLUTION_HEIGHT, Constants.DEFAULT_VIDEO_RESOLUTION.getHeight());

        List<CamcorderProfile> profiles = camcorderProfilesAvailables.get(cameraType);
        if (profiles == null || profiles.isEmpty()) {
            Log.e(TAG_SETTINGS, "No profiles available for " + cameraType + " when getting specific profile.");
            return CamcorderProfile.get(cameraType.getCameraId(), CamcorderProfile.QUALITY_HIGH); // Fallback needed
        }

        for (CamcorderProfile profile : profiles) {
            if (profile != null && profile.videoFrameWidth == savedWidth && profile.videoFrameHeight == savedHeight) {
                return profile;
            }
        }
        // If saved resolution not found in available profiles, return the first available (or default)
        Log.w(TAG_SETTINGS,"Saved resolution "+savedWidth+"x"+savedHeight+ " not found for " + cameraType + ", returning first available.");
        return profiles.get(0); // Return first as fallback
    }

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
}