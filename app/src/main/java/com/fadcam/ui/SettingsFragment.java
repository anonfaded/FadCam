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
import android.util.Log;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.fadcam.CameraType;
import com.fadcam.Constants;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public class SettingsFragment extends Fragment {

    private LocationHelper locationHelper;

    private static final String PREF_WATERMARK_OPTION = "watermark_option";
    static final String PREF_LOCATION_DATA = "location_data";

    private static final int REQUEST_PERMISSIONS = 1;
    private static final String PREF_FIRST_LAUNCH = "first_launch";

    private Spinner resolutionSpinner;
    private Spinner frameRateSpinner;
    private Spinner codecSpinner;
    private Spinner watermarkSpinner;

    MaterialButtonToggleGroup cameraSelectionToggle;

    View view;

    private BroadcastReceiver broadcastOnRecordingStarted;
    private BroadcastReceiver broadcastOnRecordingStopped;

    private Map<CameraType, List<CamcorderProfile>> camcorderProfilesAvailables;

    private SharedPreferencesManager sharedPreferencesManager;

    private void vibrateTouch() {
        // Haptic Feedback
        Vibrator vibrator = (Vibrator) requireContext().getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            VibrationEffect effect = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(effect);
            }
        }
    }

    private void updateButtonAppearance(MaterialButton button, boolean isSelected) {
        button.setIconTintResource(isSelected ? R.color.black : android.R.color.transparent); // color for check icon
        button.setStrokeColorResource(isSelected ? R.color.colorPrimary : R.color.material_on_surface_stroke); // the last color is for the button that's not selected
        button.setTextColor(ContextCompat.getColor(requireContext(), isSelected ? R.color.black : R.color.material_on_surface_emphasis_medium));
        button.setBackgroundColor(ContextCompat.getColor(requireContext(), isSelected ? R.color.colorPrimary : android.R.color.transparent));
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        locationHelper = new LocationHelper(requireContext());
        sharedPreferencesManager = SharedPreferencesManager.getInstance(requireContext());
        initializeCamcorderProfiles();
        initializeVideoCodec();
    }

    // Loads the available camcorder profiles for cameras
    private void initializeCamcorderProfiles() {
        camcorderProfilesAvailables = new HashMap<>();
        List<CamcorderProfile> camcorderProfiles = getCamcorderProfiles(CameraType.FRONT);
        if(!camcorderProfiles.isEmpty()) {
            camcorderProfilesAvailables.put(CameraType.FRONT, camcorderProfiles);
        }
        camcorderProfiles = getCamcorderProfiles(CameraType.BACK);
        if(!camcorderProfiles.isEmpty()) {
            camcorderProfilesAvailables.put(CameraType.BACK, camcorderProfiles);
        }
    }

    private void initializeVideoCodec() {
        if(!sharedPreferencesManager.isVideoCodecExist()) {
            sharedPreferencesManager.sharedPreferences.edit().putString(Constants.PREF_VIDEO_CODEC, getCompatiblesVideoCodec().toString()).apply();
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    public void onStart()
    {
        super.onStart();

        registerBroadcastOnRecordingStarted();
        registerBrodcastOnRecordingStopped();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 11 and above
            requireActivity().registerReceiver(broadcastOnRecordingStarted, new IntentFilter(Constants.BROADCAST_ON_RECORDING_STARTED), Context.RECEIVER_EXPORTED);
            requireActivity().registerReceiver(broadcastOnRecordingStopped, new IntentFilter(Constants.BROADCAST_ON_RECORDING_STOPPED), Context.RECEIVER_EXPORTED);
        } else {
            // Below Android 11
            requireActivity().registerReceiver(broadcastOnRecordingStarted, new IntentFilter(Constants.BROADCAST_ON_RECORDING_STARTED));
            requireActivity().registerReceiver(broadcastOnRecordingStopped, new IntentFilter(Constants.BROADCAST_ON_RECORDING_STOPPED));
        }
    }

    private void registerBroadcastOnRecordingStarted() {
        broadcastOnRecordingStarted = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent i)
            {
                cameraSelectionToggle.setEnabled(false);
                resolutionSpinner.setEnabled(false);
                frameRateSpinner.setEnabled(false);
                watermarkSpinner.setEnabled(false);
            }
        };
    }

    private void registerBrodcastOnRecordingStopped() {
        broadcastOnRecordingStopped = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent i)
            {
                cameraSelectionToggle.setEnabled(true);
                resolutionSpinner.setEnabled(true);
                frameRateSpinner.setEnabled(true);
                watermarkSpinner.setEnabled(true);
            }
        };
    }

    @Override
    public void onStop() {
        super.onStop();

        requireActivity().unregisterReceiver(broadcastOnRecordingStarted);
        requireActivity().unregisterReceiver(broadcastOnRecordingStopped);
    }

    @Override
    public void onResume() {
        super.onResume();
        syncCameraSwitch(view, cameraSelectionToggle);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        view = inflater.inflate(R.layout.fragment_settings, container, false);

        // Setup  language selection spinner items with array resource
        Spinner languageSpinner = view.findViewById(R.id.language_spinner);
        ArrayAdapter<CharSequence> languageAdapter  = ArrayAdapter.createFromResource(
                requireContext(), R.array.languages_array, android.R.layout.simple_spinner_item);
        languageAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        languageSpinner.setAdapter(languageAdapter );

        MaterialToolbar toolbar = view.findViewById(R.id.topAppBar);

        MaterialButton readmeButton = view.findViewById(R.id.readme_button);
        readmeButton.setOnClickListener(v -> showReadmeDialog());

        cameraSelectionToggle = view.findViewById(R.id.camera_selection_toggle);
        // Setup spinner items with array resource
        resolutionSpinner = view.findViewById(R.id.resolution_spinner);

        frameRateSpinner = view.findViewById(R.id.framerate_spinner);
        codecSpinner = view.findViewById(R.id.codec_spinner);

        // Set up camera selection toggle
        setupCameraSelectionToggle(view, cameraSelectionToggle);

        camcorderProfilesAvailables.put(CameraType.BACK, getCamcorderProfiles(CameraType.BACK));
        camcorderProfilesAvailables.put(CameraType.FRONT, getCamcorderProfiles(CameraType.FRONT));

        // Set up video quality spinner
        setupResolutionSpinner();

        // Set up video framerate spinner
        setupFrameRateSpinner();

        setupCodecSpinner();

        // Setup watermark option spinner
        watermarkSpinner = view.findViewById(R.id.watermark_spinner);
        setupWatermarkSpinner(view, watermarkSpinner);

        // Set up spinner based on saved language preference
        setupLanguageSpinner(languageSpinner);

//        // Set up location toggle group
//        MaterialButtonToggleGroup locationToggleGroup = view.findViewById(R.id.location_toggle_group);
//        setupLocationToggle(view, locationToggleGroup);

        MaterialSwitch locationSwitch = view.findViewById(R.id.location_toggle_group);
        setupLocationSwitch(locationSwitch);

        // Initialize the Review Button
        MaterialButton reviewButton = view.findViewById(R.id.review_button);
        reviewButton.setOnClickListener(v -> openInAppBrowser("https://forms.gle/DvUoc1v9kB2bkFiS6"));

        // Set up frame rate note text
        setupFrameRateNoteText();

        // Set up codec note text
        setupCodecNoteText();

        return view;
    }

    private void openInAppBrowser(String url) {
        Intent intent = new Intent(getContext(), WebViewActivity.class);
        intent.putExtra("url", url);
        startActivity(intent);
    }

    private void setupFrameRateNoteText()
    {
        TextView frameworkNoteTextView = view.findViewById(R.id.framerate_note_textview);
        frameworkNoteTextView.setText(getString(R.string.note_framerate, Constants.DEFAULT_VIDEO_FRAME_RATE));
    }

    private void setupCodecNoteText()
    {
        TextView frameworkNoteTextView = view.findViewById(R.id.codec_note_textview);
        frameworkNoteTextView.setText(getString(R.string.note_codec, Constants.DEFAULT_VIDEO_CODEC.getName()));
    }

    private void setupLocationSwitch(MaterialSwitch locationSwitch) {
        boolean isLocationEnabled = sharedPreferencesManager.isLocalisationEnabled();
        locationSwitch.setChecked(isLocationEnabled);

        locationSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
                    showLocationPermissionDialog(locationSwitch);
                } else {
                    sharedPreferencesManager.sharedPreferences.edit().putBoolean(PREF_LOCATION_DATA, true).apply();
                }
            } else {
                sharedPreferencesManager.sharedPreferences.edit().putBoolean(PREF_LOCATION_DATA, false).apply();
            }
            vibrateTouch();
        });
    }


    private void showLocationPermissionDialog(MaterialSwitch locationSwitch) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.location_permission_title)) // Use string resource for title
                .setMessage(getString(R.string.location_permission_description))
                .setPositiveButton("Grant", (dialog, which) -> requestLocationPermission())
                .setNegativeButton("Cancel", (dialog, which) -> {
                    locationSwitch.setChecked(false); // Disable the switch if the user cancels
                    sharedPreferencesManager.sharedPreferences.edit().putBoolean(PREF_LOCATION_DATA, false).apply();
                    dialog.dismiss();
                })
                .show();
    }
    private void requestLocationPermission() {
        ActivityCompat.requestPermissions(requireActivity(), new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_LOCATION_PERMISSION);
    }


    private void setupLocationToggle(View view, MaterialSwitch locationSwitch) {
        boolean isLocationEnabled = sharedPreferencesManager.isLocalisationEnabled();
        locationSwitch.setChecked(isLocationEnabled);

        if (isLocationEnabled) {
            locationHelper.startLocationUpdates();
        } else {
            locationHelper.stopLocationUpdates();
        }

        locationSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sharedPreferencesManager.sharedPreferences.edit().putBoolean(PREF_LOCATION_DATA, isChecked).apply();
            if (isChecked) {
                requestLocationPermission();
            } else {
                locationHelper.stopLocationUpdates();
            }
            vibrateTouch();
        });
    }


    private static final int REQUEST_LOCATION_PERMISSION = 1;
//    private void requestLocationPermission() {
//        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
//            ActivityCompat.requestPermissions(requireActivity(), new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_LOCATION_PERMISSION);
//        } else {
//            locationHelper.startLocationUpdates();
//        }
//    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_LOCATION_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                sharedPreferencesManager.sharedPreferences.edit().putBoolean(PREF_LOCATION_DATA, true).apply();
            } else {
                MaterialSwitch locationSwitch = requireView().findViewById(R.id.location_toggle_group);
                if (locationSwitch != null) {
                    locationSwitch.setChecked(false);
                }
                sharedPreferencesManager.sharedPreferences.edit().putBoolean(PREF_LOCATION_DATA, false).apply();
            }
        }
    }
    //To sync with the camera switch button on main page...
    private void syncCameraSwitch(View view, MaterialButtonToggleGroup cameraSelectionToggle){

        MaterialButton backCameraButton = view.findViewById(R.id.button_back_camera);
        MaterialButton frontCameraButton = view.findViewById(R.id.button_front_camera);

        if (sharedPreferencesManager.getCameraSelection().equals(CameraType.FRONT)) {
            cameraSelectionToggle.check(R.id.button_front_camera);
            updateButtonAppearance(frontCameraButton, true);
            updateButtonAppearance(backCameraButton, false);
        } else {
            cameraSelectionToggle.check(R.id.button_back_camera);
            updateButtonAppearance(backCameraButton, true);
            updateButtonAppearance(frontCameraButton, false);
        }
        cameraSelectionToggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                CameraType selectedCamera = (checkedId == R.id.button_front_camera) ? CameraType.FRONT : CameraType.BACK;
                sharedPreferencesManager.sharedPreferences.edit().putString(Constants.PREF_CAMERA_SELECTION, selectedCamera.toString()).apply();
                updateButtonAppearance(backCameraButton, checkedId == R.id.button_back_camera);
                updateButtonAppearance(frontCameraButton, checkedId == R.id.button_front_camera);
            }
        });
    }

    private void setupCameraSelectionToggle(View view, MaterialButtonToggleGroup cameraSelectionToggle) {
        MaterialButton backCameraButton = view.findViewById(R.id.button_back_camera);
        MaterialButton frontCameraButton = view.findViewById(R.id.button_front_camera);

        if(!camcorderProfilesAvailables.containsKey(CameraType.BACK)) {
            backCameraButton.setEnabled(false);
        }

        if(!camcorderProfilesAvailables.containsKey(CameraType.FRONT)) {
            frontCameraButton.setEnabled(false);
        }

        if (sharedPreferencesManager.getCameraSelection().equals(CameraType.FRONT)) {
            cameraSelectionToggle.check(R.id.button_front_camera);
            updateButtonAppearance(frontCameraButton, true);
            updateButtonAppearance(backCameraButton, false);
        } else {
            cameraSelectionToggle.check(R.id.button_back_camera);
            updateButtonAppearance(backCameraButton, true);
            updateButtonAppearance(frontCameraButton, false);
        }

        cameraSelectionToggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                CameraType selectedCamera = (checkedId == R.id.button_front_camera) ? CameraType.FRONT : CameraType.BACK;
                sharedPreferencesManager.sharedPreferences.edit().putString(Constants.PREF_CAMERA_SELECTION, selectedCamera.toString()).apply();
                updateButtonAppearance(backCameraButton, checkedId == R.id.button_back_camera);
                updateButtonAppearance(frontCameraButton, checkedId == R.id.button_front_camera);
            }
            updateResolutionSpinner();
            updateFrameRateSpinner();
        });
    }

    private void setupResolutionSpinner() {

        updateResolutionSpinner();

        // Save the selected quality when user changes it
        resolutionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                List<CamcorderProfile> camcorderProfile = getCamcorderProfiles(sharedPreferencesManager.getCameraSelection());

                sharedPreferencesManager.sharedPreferences.edit().putInt(Constants.PREF_VIDEO_RESOLUTION_WIDTH, camcorderProfile.get(position).videoFrameWidth).apply();
                sharedPreferencesManager.sharedPreferences.edit().putInt(Constants.PREF_VIDEO_RESOLUTION_HEIGHT, camcorderProfile.get(position).videoFrameHeight).apply();

                updateFrameRateSpinner();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void updateResolutionSpinner() {
        List<String> camcorderProfilesList = getCompatiblesVideoResolutionsAsString(sharedPreferencesManager.getCameraSelection());

        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, camcorderProfilesList);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        resolutionSpinner.setAdapter(adapter);

        int selectedIndex = getCamcorderProfileIndexPreferences(sharedPreferencesManager.getCameraSelection());
        resolutionSpinner.setSelection(selectedIndex);
    }

    private void setupFrameRateSpinner() {

        updateFrameRateSpinner();

        // Save the selected quality when user changes it
        frameRateSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                sharedPreferencesManager.sharedPreferences.edit().putInt(Constants.PREF_VIDEO_FRAME_RATE, getCompatiblesVideoFrameRates(sharedPreferencesManager.getCameraSelection()).get(position)).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void setupCodecSpinner() {

        List<VideoCodec> videoCodecsCompatibles = getCompatiblesVideoCodecs();

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

        codecSpinner.setSelection(videoCodecsCompatibles.size() == 1 ? 0 : getVideoCodecIndex(selectedCodec));
        codecSpinner.setEnabled(videoCodecsCompatibles.size() > 1);

        // Save the selected codec when user changes it
        codecSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                sharedPreferencesManager.sharedPreferences.edit().putString(Constants.PREF_VIDEO_CODEC, getCompatiblesVideoCodecs().get(position).toString()).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void updateFrameRateSpinner()
    {
        List<Integer> videoFrameRatesCompatibles = getCompatiblesVideoFrameRates(sharedPreferencesManager.getCameraSelection());

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

        // Set the selected item based on the saved preference
        int selectedFramerate = sharedPreferencesManager.getVideoFrameRate();

        // If the selected frame rate is not in the list of compatible frame rates, reset it to the default value
        if(!videoFrameRatesCompatibles.contains(selectedFramerate)) {
            selectedFramerate = Constants.DEFAULT_VIDEO_FRAME_RATE;
        }

        if(!videoFrameRatesCompatibles.isEmpty()) {
            frameRateSpinner.setSelection(videoFrameRatesCompatibles.size() == 1 ? 0 : getVideoFrameRateIndex(selectedFramerate));
            frameRateSpinner.setEnabled(videoFrameRatesCompatibles.size() > 1);
        } else {
            frameRateSpinner.setEnabled(false);
        }
    }

    private void setupWatermarkSpinner(View view, Spinner watermarkSpinner) {
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(requireContext(),
                R.array.watermark_options, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        watermarkSpinner.setAdapter(adapter);

        String savedWatermark = sharedPreferencesManager.getWatermarkOption();
        int watermarkIndex = getWatermarkIndex(savedWatermark);
        watermarkSpinner.setSelection(watermarkIndex);

        watermarkSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selectedWatermark = getWatermarkValue(position);
                sharedPreferencesManager.sharedPreferences.edit().putString(PREF_WATERMARK_OPTION, selectedWatermark).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Do nothing
            }
        });
    }

    private int getWatermarkIndex(String value) {
        String[] values = getResources().getStringArray(R.array.watermark_values);
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) {
                return i;
            }
        }
        return 0; // Default to first option
    }

    private String getWatermarkValue(int index) {
        String[] values = getResources().getStringArray(R.array.watermark_values);
        return values[index];
    }

    private void showReadmeDialog() {
        vibrateTouch();
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext());
        builder.setTitle(R.string.dialog_welcome_title);

        View dialogView = getLayoutInflater().inflate(R.layout.dialog_readme, null);

        MaterialButton githubButton = dialogView.findViewById(R.id.github_button);
        githubButton.setOnClickListener(v -> openUrl("https://github.com/anonfaded/FadCam"));

        MaterialButton discordButton = dialogView.findViewById(R.id.discord_button);
        discordButton.setOnClickListener(v -> openUrl("https://discord.gg/kvAZvdkuuN"));

        builder.setView(dialogView);
        builder.setPositiveButton("OK", null);
        builder.show();
    }

    private void openUrl(String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        startActivity(intent);
    }

    private void saveLanguagePreference(String languageCode) {
        SharedPreferences prefs = requireActivity().getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(Constants.LANGUAGE_KEY, languageCode);
        editor.apply();
        Log.d("SettingsFragment", "Language preference saved: " + languageCode);
    }

    private void setupLanguageSpinner(Spinner languageSpinner) {
        String savedLanguageCode = sharedPreferencesManager.getLanguage();
        int selectedIndex = getLanguageIndex(savedLanguageCode);
        languageSpinner.setSelection(selectedIndex);

        languageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String languageCode = getLanguageCode(position);
                if (!languageCode.equals(savedLanguageCode)) {
                    saveLanguagePreference(languageCode);
                    ((MainActivity) requireActivity()).applyLanguage(languageCode);  // This will recreate the activity if the language is different
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private int getLanguageIndex(String languageCode) {
        switch (languageCode) {
            case "zh":
                return 1;
            case "ar":
                return 2;
            case "fr":
                return 3;
            case "tr":
                return 4;
            case "ps":
                return 5;
            case "in":
                return 6;
            default:
                return 0; // Default to English
        }
    }

    private String getLanguageCode(int position) {
        switch (position) {
            case 1:
                return "zh";
            case 2:
                return "ar";
            case 3:
                return "fr";
            case 4:
                return "tr";
            case 5:
                return "ps";
            case 6:
                return "in";
            default:
                return "en";
        }
    }

    private void applyLanguage(String languageCode) {
        Log.d("SettingsFragment", "Applying new language: " + languageCode);
        Locale locale = new Locale(languageCode);
        Locale.setDefault(locale);
        Configuration config = new Configuration();
        config.setLocale(locale);
        requireActivity().getResources().updateConfiguration(config, requireActivity().getResources().getDisplayMetrics());

        // Restart the activity or fragment to apply changes
        requireActivity().recreate();
    }

    /**
     * Retrieves the index of the selected frame rate from the frame rate options list.
     *
     * @param frameRate The desired frame rate.
     * @return The index of the frame rate in the selection list, or 0 if not found.
     */
    private int getVideoFrameRateIndex(int frameRate)
    {
        int[] frameRateOptions = getResources().getIntArray(R.array.video_framerate_options);
        for(int i = 0;i < frameRateOptions.length;i++)
        {
            if(frameRateOptions[i] == frameRate)
            {
                return i;
            }
        }
        return 0;
    }

    /**
     * Retrieves the index of the selected video codec from the codec list.
     *
     * @param videoCodec The desired video codec.
     * @return The index of the video codec in the codec list, or -1 if not found.
     */
    private int getVideoCodecIndex(VideoCodec videoCodec)
    {
        VideoCodec[] codecs = VideoCodec.values();
        for (int i = 0; i < codecs.length; i++) {
            if (codecs[i] == videoCodec) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Retrieves a list of compatible video resolutions for the specified camera type.
     * The resolutions are derived from the camcorder profiles available for the camera.
     *
     * @param cameraType the type of camera (e.g., front or back) for which resolutions are determined.
     * @return a list of compatible video resolutions as {@link Size} objects, each containing width and height.
     */
    public List<Size> getCompatiblesVideoResolutions(CameraType cameraType)
    {
        List<CamcorderProfile> camcorderProfiles = this.getCamcorderProfiles(cameraType);

        // Get the available frame rates
        List<Size> videoResolutionCompatibles = new ArrayList<>();

        for(CamcorderProfile camcorderProfile : camcorderProfiles) {
            videoResolutionCompatibles.add(new Size(camcorderProfile.videoFrameWidth, camcorderProfile.videoFrameHeight));
        }
        return videoResolutionCompatibles;
    }

    /**
     * Retrieves a list of compatible video resolutions for the specified camera type
     * in a human-readable string format. Each resolution is matched with its
     * corresponding display value from predefined resources.
     *
     * @param cameraType the type of camera (e.g., front or back) for which resolutions are determined.
     * @return a list of compatible video resolutions as formatted strings.
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
     * Builds a human-readable string representation of a video resolution.
     * The string is constructed by matching the resolution dimensions with predefined keys
     * and appending the corresponding value. If no matching value is found, the dimensions are appended directly.
     *
     * @param videoResolution the resolution to be converted to a string (contains width and height).
     * @param resolutionKeys an array of resolution keys (e.g., "1920x1080").
     * @param resolutionValues an array of resolution values corresponding to the keys (e.g., "Full HD").
     * @return a string representing the video resolution, either using the predefined value or the width/height.
     */
    @NonNull
    private static String getVideoResolutionStringBuilder(Size videoResolution, String[] resolutionKeys, String[] resolutionValues) {
        StringBuilder videoResolutionText = new StringBuilder();

        for (int i = 0; i < resolutionKeys.length; i++) {
            if (resolutionKeys[i].equals(videoResolution.getWidth() + "x" + videoResolution.getHeight())) {
                videoResolutionText.append(resolutionValues[i]);
            }
        }

        videoResolutionText.append(" (")
                .append(videoResolution.getWidth())
                .append("x")
                .append(videoResolution.getHeight())
                .append(")");
        return videoResolutionText.toString();
    }

    /**
     * Retrieves a list of compatible video frame rates for the specified camera type.
     *
     * @param cameraType the type of camera (e.g., front or back) for which frame rates are determined.
     * @return a list of compatible video frame rates as Integer values.
     */
    private List<Integer> getCompatiblesVideoFrameRates(CameraType cameraType)
    {
        CamcorderProfile camcorderProfile = getCamcorderProfile(cameraType);

        int[] videoFrameRatesAvailable = getResources().getIntArray(R.array.video_framerate_options);

        return Arrays.stream(videoFrameRatesAvailable)
                // Only frame rates that are less than or equal to the camera's maximum supported frame rate are included
                .filter(frameRate -> frameRate <= camcorderProfile.videoFrameRate)
                .boxed()
                .collect(Collectors.toList());
    }

    private List<VideoCodec> getCompatiblesVideoCodecs()
    {
        List<VideoCodec> videoCodecs = new ArrayList<>();

        for(VideoCodec videoCodec : VideoCodec.values())
        {
            if(Utils.isCodecSupported(videoCodec.getMimeType())) {
                videoCodecs.add(videoCodec);
            }
        }

        if(videoCodecs.isEmpty()) {
            videoCodecs.add(Constants.DEFAULT_VIDEO_CODEC);
        }

        return videoCodecs;
    }

    /**
     * Returns the video codec with the highest priority from the list of compatible codecs.
     * If no codec is compatible, it returns null.
     */
    private VideoCodec getCompatiblesVideoCodec() {
        List<VideoCodec> compatibleCodecs = getCompatiblesVideoCodecs();
        VideoCodec highestPriorityCodec = null;

        for (VideoCodec videoCodec : compatibleCodecs) {
            if (highestPriorityCodec == null || videoCodec.getPriority() > highestPriorityCodec.getPriority()) {
                highestPriorityCodec = videoCodec;
            }
        }

        return highestPriorityCodec;
    }


    /**
     * Retrieves a list of available camcorder profiles for the specified camera type.
     * The profiles are determined based on the camera's ID and supported resolutions.
     *
     * @param cameraType the type of camera (e.g., front or back) for which camcorder profiles are retrieved.
     * @return a list of {@link CamcorderProfile} objects representing the supported profiles.
     */
    private List<CamcorderProfile> getCamcorderProfiles(CameraType cameraType) {

        List<CamcorderProfile> profiles = new ArrayList<>();

        int cameraId = cameraType.getCameraId();

        List<Integer> qualities = new ArrayList<>();
        qualities.add(CamcorderProfile.QUALITY_480P);
        qualities.add(CamcorderProfile.QUALITY_720P);
        qualities.add(CamcorderProfile.QUALITY_1080P);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            qualities.add(CamcorderProfile.QUALITY_2K);
        }

        qualities.add(CamcorderProfile.QUALITY_2160P); // 4K

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            qualities.add(CamcorderProfile.QUALITY_8KUHD); // 8K
        }

        for (int quality : qualities) {
            if (CamcorderProfile.hasProfile(cameraId, quality)) {
                CamcorderProfile profile = CamcorderProfile.get(cameraId, quality);
                if(profile != null) {
                    profiles.add(profile);
                }
            }
        }
        return profiles;
    }

    /**
     * Retrieves the selected camcorder profile for the specified camera type based on user preferences.
     * The method checks the preferences for the index of the desired profile and returns the corresponding profile.
     *
     * @param cameraType the type of camera (e.g., front or back) for which the camcorder profile is retrieved.
     * @return the selected {@link CamcorderProfile}, or null if no profile matches the selection.
     */
    private CamcorderProfile getCamcorderProfile(CameraType cameraType) {
        int camcorderProfileIndexPreference = this.getCamcorderProfileIndexPreferences(cameraType);
        for (Map.Entry<CameraType, List<CamcorderProfile>> entry : camcorderProfilesAvailables.entrySet()) {
            if(entry.getKey() == cameraType) {
                return entry.getValue().get(camcorderProfileIndexPreference);
            }
        }
        return null;
    }

    /**
     * Retrieves the index of the preferred camcorder profile for the specified camera type
     * based on the stored video resolution in shared preferences.
     * If no matching resolution is found, the default profile index (0) is returned.
     *
     * @param cameraType the type of camera (e.g., front or back) for which the profile index is determined.
     * @return the index of the camcorder profile that matches the stored video resolution, or 0 if no match is found.
     */
    private int getCamcorderProfileIndexPreferences(CameraType cameraType)
    {
        Size videoResolution = sharedPreferencesManager.getCameraResolution();

        List<CamcorderProfile> camcorderProfiles = camcorderProfilesAvailables.get(cameraType);

        int index = 0;

        if(camcorderProfiles != null && !camcorderProfiles.isEmpty()) {
            for (CamcorderProfile camcorderProfile : camcorderProfiles) {
                if (camcorderProfile.videoFrameWidth == videoResolution.getWidth()
                        && camcorderProfile.videoFrameHeight == videoResolution.getHeight()) {
                    return index;
                }
                index++;
            }
        }
        return 0;
    }
}