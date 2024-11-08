package com.fadcam.ui;

import android.Manifest;
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
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.util.Range;
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

import com.fadcam.Constantes;
import com.fadcam.MainActivity;
import com.fadcam.R;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SettingsFragment extends Fragment {

    private SharedPreferences sharedPreferences;

    private LocationHelper locationHelper;

    private static final String PREF_WATERMARK_OPTION = "watermark_option";
    private static final String QUALITY_SD = "SD";
    private static final String QUALITY_HD = "HD";
    private static final String QUALITY_FHD = "FHD";
    static final String PREF_LOCATION_DATA = "location_data";

    private static final String PREFS_NAME = "app_prefs";
    private static final String LANGUAGE_KEY = "language";

    private static final int REQUEST_PERMISSIONS = 1;
    private static final String PREF_FIRST_LAUNCH = "first_launch";

    private Spinner frameRateSpinner;

    private MaterialButtonToggleGroup cameraSelectionToggle;
    private MaterialButton buttonBackCamera, buttonFrontCamera, buttonDualCamera;
    View view;

    private void vibrateTouch() {
        // Haptic Feedback
        Vibrator vibrator = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
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
        button.setTextColor(getResources().getColor(isSelected ? R.color.black : R.color.material_on_surface_emphasis_medium));
        button.setBackgroundColor(getResources().getColor(isSelected ? R.color.colorPrimary : android.R.color.transparent));
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sharedPreferences = requireActivity().getSharedPreferences("com.fadcam_preferences", Context.MODE_PRIVATE);
        locationHelper = new LocationHelper(requireContext());
    }

    @Override
    public void onResume() {
        super.onResume();
        // Registrar el receptor con try-catch
        try {
            IntentFilter filter = new IntentFilter("CAMERA_SELECTION_CHANGED");
            requireActivity().registerReceiver(cameraSelectionReceiver, filter);
        } catch (Exception e) {
            Log.e("SettingsFragment", "Error registering receiver: " + e.getMessage());
        }
        syncCameraSwitch(view, cameraSelectionToggle);
    }

    @Override
    public void onPause() {
        super.onPause();
        // Desregistrar el receptor con try-catch
        try {
            if (cameraSelectionReceiver != null) {
                requireActivity().unregisterReceiver(cameraSelectionReceiver);
            }
        } catch (Exception e) {
            Log.e("SettingsFragment", "Error unregistering receiver: " + e.getMessage());
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        view = inflater.inflate(R.layout.fragment_settings, container, false);

        // Setup language selection spinner items with array resource
        Spinner languageSpinner = view.findViewById(R.id.language_spinner);
        ArrayAdapter<CharSequence> languageAdapter = ArrayAdapter.createFromResource(
                getContext(), R.array.languages_array, android.R.layout.simple_spinner_item);
        languageAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        languageSpinner.setAdapter(languageAdapter);

        MaterialToolbar toolbar = view.findViewById(R.id.topAppBar);

        MaterialButton readmeButton = view.findViewById(R.id.readme_button);
        readmeButton.setOnClickListener(v -> showReadmeDialog());

        cameraSelectionToggle = view.findViewById(R.id.camera_selection_toggle);
        buttonBackCamera = view.findViewById(R.id.button_back_camera);
        buttonFrontCamera = view.findViewById(R.id.button_front_camera);
        buttonDualCamera = view.findViewById(R.id.button_dual_camera);

        // Configurar el estado inicial basado en las preferencias guardadas
        String currentCamera = sharedPreferences.getString(Constantes.PREF_CAMERA_SELECTION, Constantes.CAMERA_BACK);
        switch (currentCamera) {
            case Constantes.CAMERA_FRONT:
                buttonFrontCamera.setChecked(true);
                break;
            case Constantes.CAMERA_DUAL:
                buttonDualCamera.setChecked(true);
                break;
            default:
                buttonBackCamera.setChecked(true);
                break;
        }

        // Configurar listener para los cambios de selección
        cameraSelectionToggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                String selection;
                if (checkedId == R.id.button_front_camera) {
                    selection = Constantes.CAMERA_FRONT;
                } else if (checkedId == R.id.button_dual_camera) {
                    selection = Constantes.CAMERA_DUAL;
                } else {
                    selection = Constantes.CAMERA_BACK;
                }
                sharedPreferences.edit().putString(Constantes.PREF_CAMERA_SELECTION, selection).apply();
            }
        });

        // Setup spinner items with array resource
        Spinner qualitySpinner = view.findViewById(R.id.quality_spinner);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                getContext(), R.array.video_quality_options, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        qualitySpinner.setAdapter(adapter);

        frameRateSpinner = view.findViewById(R.id.framerate_spinner);

        // Set up camera selection toggle
        setupCameraSelectionToggle(view, cameraSelectionToggle);

        // Set up video quality spinner
        setupQualitySpinner(view, qualitySpinner);

        // Set up video framerate spinner
        setupFrameRateSpinner();

        // Setup watermark option spinner
        Spinner watermarkSpinner = view.findViewById(R.id.watermark_spinner);
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

        // Set up framerate note text
        setupFramerateNoteText();

        return view;
    }

    private void openInAppBrowser(String url) {
        Intent intent = new Intent(getContext(), WebViewActivity.class);
        intent.putExtra("url", url);
        startActivity(intent);
    }

    private void setupFramerateNoteText()
    {
        TextView frameworkNoteTextView = view.findViewById(R.id.framerate_note_textview);
        frameworkNoteTextView.setText(getString(R.string.note_framerate, Constantes.DEFAULT_VIDEO_FRAMERATE));
    }

    private void setupLocationSwitch(MaterialSwitch locationSwitch) {
        boolean isLocationEnabled = sharedPreferences.getBoolean(PREF_LOCATION_DATA, false);
        locationSwitch.setChecked(isLocationEnabled);

        locationSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
                    showLocationPermissionDialog(locationSwitch);
                } else {
                    sharedPreferences.edit().putBoolean(PREF_LOCATION_DATA, true).apply();
                }
            } else {
                sharedPreferences.edit().putBoolean(PREF_LOCATION_DATA, false).apply();
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
                    sharedPreferences.edit().putBoolean(PREF_LOCATION_DATA, false).apply();
                    dialog.dismiss();
                })
                .show();
    }
    private void requestLocationPermission() {
        ActivityCompat.requestPermissions(requireActivity(), new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_LOCATION_PERMISSION);
    }


    private void setupLocationToggle(View view, MaterialSwitch locationSwitch) {
        boolean isLocationEnabled = sharedPreferences.getBoolean(PREF_LOCATION_DATA, false);
        locationSwitch.setChecked(isLocationEnabled);

        if (isLocationEnabled) {
            locationHelper.startLocationUpdates();
        } else {
            locationHelper.stopLocationUpdates();
        }

        locationSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sharedPreferences.edit().putBoolean(PREF_LOCATION_DATA, isChecked).apply();
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
                sharedPreferences.edit().putBoolean(PREF_LOCATION_DATA, true).apply();
            } else {
                MaterialSwitch locationSwitch = getView().findViewById(R.id.location_toggle_group);
                if (locationSwitch != null) {
                    locationSwitch.setChecked(false);
                }
                sharedPreferences.edit().putBoolean(PREF_LOCATION_DATA, false).apply();
            }
        }
    }
    //To sync with the camera switch button on main page...
    private void syncCameraSwitch(View view, MaterialButtonToggleGroup cameraSelectionToggle){

        MaterialButton backCameraButton = view.findViewById(R.id.button_back_camera);
        MaterialButton frontCameraButton = view.findViewById(R.id.button_front_camera);
        MaterialButton dualCameraButton = view.findViewById(R.id.button_dual_camera);

        String currentCameraSelection = sharedPreferences.getString(Constantes.PREF_CAMERA_SELECTION, Constantes.CAMERA_BACK);

        // Actualizar el estado de los botones
        switch (currentCameraSelection) {
            case Constantes.CAMERA_FRONT:
                cameraSelectionToggle.check(R.id.button_front_camera);
                updateButtonAppearance(frontCameraButton, true);
                updateButtonAppearance(backCameraButton, false);
                updateButtonAppearance(dualCameraButton, false);
                break;
            case Constantes.CAMERA_DUAL:
                cameraSelectionToggle.check(R.id.button_dual_camera);
                updateButtonAppearance(dualCameraButton, true);
                updateButtonAppearance(frontCameraButton, false);
                updateButtonAppearance(backCameraButton, false);
                break;
            default: // CAMERA_BACK
                cameraSelectionToggle.check(R.id.button_back_camera);
                updateButtonAppearance(backCameraButton, true);
                updateButtonAppearance(frontCameraButton, false);
                updateButtonAppearance(dualCameraButton, false);
                break;
        }

        cameraSelectionToggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                String selectedCamera;
                if (checkedId == R.id.button_front_camera) {
                    selectedCamera = Constantes.CAMERA_FRONT;
                    updateButtonAppearance(frontCameraButton, true);
                    updateButtonAppearance(backCameraButton, false);
                    updateButtonAppearance(dualCameraButton, false);
                } else if (checkedId == R.id.button_dual_camera) {
                    selectedCamera = Constantes.CAMERA_DUAL;
                    updateButtonAppearance(dualCameraButton, true);
                    updateButtonAppearance(frontCameraButton, false);
                    updateButtonAppearance(backCameraButton, false);
                } else {
                    selectedCamera = Constantes.CAMERA_BACK;
                    updateButtonAppearance(backCameraButton, true);
                    updateButtonAppearance(frontCameraButton, false);
                    updateButtonAppearance(dualCameraButton, false);
                }

                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putString(Constantes.PREF_CAMERA_SELECTION, selectedCamera);
                editor.apply();

                Log.d("SettingsFragment", "Camera selection changed to: " + selectedCamera);
            }
        });
    }



    private void setupCameraSelectionToggle(View view, MaterialButtonToggleGroup cameraSelectionToggle) {
        MaterialButton backCameraButton = view.findViewById(R.id.button_back_camera);
        MaterialButton frontCameraButton = view.findViewById(R.id.button_front_camera);
        MaterialButton dualCameraButton = view.findViewById(R.id.button_dual_camera);

        String currentCameraSelection = sharedPreferences.getString(Constantes.PREF_CAMERA_SELECTION, Constantes.CAMERA_BACK);
        Log.d("SettingsFragment", "Current camera selection: " + currentCameraSelection);

        // Actualizar el estado de los botones
        switch (currentCameraSelection) {
            case Constantes.CAMERA_FRONT:
                cameraSelectionToggle.check(R.id.button_front_camera);
                updateButtonAppearance(frontCameraButton, true);
                updateButtonAppearance(backCameraButton, false);
                updateButtonAppearance(dualCameraButton, false);
                break;
            case Constantes.CAMERA_DUAL:
                cameraSelectionToggle.check(R.id.button_dual_camera);
                updateButtonAppearance(dualCameraButton, true);
                updateButtonAppearance(frontCameraButton, false);
                updateButtonAppearance(backCameraButton, false);
                break;
            default: // CAMERA_BACK
                cameraSelectionToggle.check(R.id.button_back_camera);
                updateButtonAppearance(backCameraButton, true);
                updateButtonAppearance(frontCameraButton, false);
                updateButtonAppearance(dualCameraButton, false);
                break;
        }

        cameraSelectionToggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                String selectedCamera;
                if (checkedId == R.id.button_front_camera) {
                    selectedCamera = Constantes.CAMERA_FRONT;
                    updateButtonAppearance(frontCameraButton, true);
                    updateButtonAppearance(backCameraButton, false);
                    updateButtonAppearance(dualCameraButton, false);
                } else if (checkedId == R.id.button_dual_camera) {
                    selectedCamera = Constantes.CAMERA_DUAL;
                    updateButtonAppearance(dualCameraButton, true);
                    updateButtonAppearance(frontCameraButton, false);
                    updateButtonAppearance(backCameraButton, false);
                } else {
                    selectedCamera = Constantes.CAMERA_BACK;
                    updateButtonAppearance(backCameraButton, true);
                    updateButtonAppearance(frontCameraButton, false);
                    updateButtonAppearance(dualCameraButton, false);
                }

                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putString(Constantes.PREF_CAMERA_SELECTION, selectedCamera);
                editor.apply();

                Log.d("SettingsFragment", "Camera selection changed to: " + selectedCamera);
            }
        });
    }

    private void setupQualitySpinner(View view, Spinner qualitySpinner) {
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(requireContext(),
                R.array.video_quality_array, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        qualitySpinner.setAdapter(adapter);

        int selectedIndex = getVideoQualityIndex();
        qualitySpinner.setSelection(selectedIndex);

        // Save the selected quality when user changes it
        qualitySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selectedQuality = QUALITY_HD;
                switch (position) {
                    case 0:
                        selectedQuality = QUALITY_FHD;
                        break;
                    case 2:
                        selectedQuality = QUALITY_SD;
                        break;
                }
                sharedPreferences.edit().putString(Constantes.PREF_VIDEO_QUALITY, selectedQuality).apply();

                updateFrameRateSpinner();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Do nothing
            }
        });
    }

    private void setupFrameRateSpinner() {

        updateFrameRateSpinner();

        // Save the selected quality when user changes it
        frameRateSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                List<Integer> videoFrameratesCompatibles = getCompatiblesVideoFrameRates(getSharedPreferencesCameraSelection());
                if(!videoFrameratesCompatibles.isEmpty()) {
                    sharedPreferences.edit().putInt(Constantes.PREF_VIDEO_FRAMERATE, videoFrameratesCompatibles.get(position)).apply();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Do nothing
            }
        });
    }

    private void updateFrameRateSpinner()
    {
        List<Integer> videoFrameratesCompatibles = getCompatiblesVideoFrameRates(getSharedPreferencesCameraSelection());

        ArrayAdapter<Integer> adapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_item,
                videoFrameratesCompatibles
        );

        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        frameRateSpinner.setAdapter(adapter);

        // Set the selected item based on the saved preference
        int selectedFramerate = sharedPreferences.getInt(Constantes.PREF_VIDEO_FRAMERATE, Constantes.DEFAULT_VIDEO_FRAMERATE);

        if(!videoFrameratesCompatibles.isEmpty()) {
            frameRateSpinner.setSelection(videoFrameratesCompatibles.size() == 1 ? 0 : getVideoFrameRateIndex(selectedFramerate));
            frameRateSpinner.setEnabled(videoFrameratesCompatibles.size() > 1);
        } else {
            frameRateSpinner.setEnabled(false);
        }
    }

    private void setupWatermarkSpinner(View view, Spinner watermarkSpinner) {
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(requireContext(),
                R.array.watermark_options, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        watermarkSpinner.setAdapter(adapter);

        SharedPreferences sharedPreferences = requireActivity().getPreferences(Context.MODE_PRIVATE);
        String savedWatermark = sharedPreferences.getString(PREF_WATERMARK_OPTION, "timestamp_fadcam");
        int watermarkIndex = getWatermarkIndex(savedWatermark);
        watermarkSpinner.setSelection(watermarkIndex);

        watermarkSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selectedWatermark = getWatermarkValue(position);
                sharedPreferences.edit().putString(PREF_WATERMARK_OPTION, selectedWatermark).apply();
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
        SharedPreferences prefs = requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(LANGUAGE_KEY, languageCode);
        editor.apply();
        Log.d("SettingsFragment", "Language preference saved: " + languageCode);
    }

    private void setupLanguageSpinner(Spinner languageSpinner) {
        String savedLanguageCode = sharedPreferences.getString(LANGUAGE_KEY, Locale.getDefault().getLanguage());
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
            public void onNothingSelected(AdapterView<?> parent) {
                // Do nothing
            }
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
        getActivity().getResources().updateConfiguration(config, getActivity().getResources().getDisplayMetrics());

        // Restart the activity or fragment to apply changes
        requireActivity().recreate();
    }

    /**
     * Set the selected item based on the frame rate
     * @param frameRate
     * @return Video frameRate index in selection list
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
     * Set the selected item based on the saved preference
     * @return Video quality index in selection list
     */
    private int getVideoQualityIndex()
    {
        String selectedQuality = sharedPreferences.getString(Constantes.PREF_VIDEO_QUALITY, QUALITY_HD);

        int selectedIndex = 1; // Default to HD
        switch (selectedQuality) {
            case QUALITY_FHD:
                selectedIndex = 0;
                break;
            case QUALITY_SD:
                selectedIndex = 2;
                break;
        }
        return selectedIndex;
    }

    /**
     * Get all compatibles video framerates
     * @param camera (front or back)
     * @return Cannot be empty
     */
    public List<Integer> getCompatiblesVideoFrameRates(String camera)
    {
        // Get the selected quality from preferences
        String selectedQuality = sharedPreferences.getString(Constantes.PREF_VIDEO_QUALITY, QUALITY_HD);

        // Get the available frame rates
        int[] videoFrameRates = getResources().getIntArray(R.array.video_framerate_array);
        List<Integer> videoFrameRatesCompatibles = new ArrayList<>();

        CamcorderProfile profile = null;

        // Determine the frame rate limits based on the camera and quality
        int maxFrameRate = 0;
        int cameraId = camera.equals(Constantes.CAMERA_FRONT) ? 1 : 0;

        switch (selectedQuality) {
            case QUALITY_FHD:
                if (CamcorderProfile.hasProfile(cameraId, CamcorderProfile.QUALITY_1080P)) {
                    profile = CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_1080P);
                }
                break;
            case QUALITY_HD:
                if (CamcorderProfile.hasProfile(cameraId, CamcorderProfile.QUALITY_720P)) {
                    profile = CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_720P);
                }
                break;
            case QUALITY_SD:
                if (CamcorderProfile.hasProfile(cameraId, CamcorderProfile.QUALITY_VGA)) {
                    profile = CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_VGA);
                }
                else if (CamcorderProfile.hasProfile(cameraId, CamcorderProfile.QUALITY_480P)) {
                    profile = CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_480P);
                }
                break;
        }

        if(profile != null)
        {
            maxFrameRate = profile.videoFrameRate;
        }

        // Add the compatible frame rates to the list
        for (int videoFramerate : videoFrameRates) {
            if (videoFramerate <= maxFrameRate) {
                videoFrameRatesCompatibles.add(videoFramerate);
            }
        }

        return videoFrameRatesCompatibles;
    }

    private String getSharedPreferencesCameraSelection()
    {
        return sharedPreferences.getString(Constantes.PREF_CAMERA_SELECTION, Constantes.CAMERA_BACK);
    }

    private BroadcastReceiver cameraSelectionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("CAMERA_SELECTION_CHANGED".equals(intent.getAction())) {
                String newSelection = intent.getStringExtra("camera_selection");
                if (newSelection != null) {
                    updateCameraSelectionUI(newSelection);
                }
            }
        }
    };

    private void updateCameraSelectionUI(String cameraSelection) {
        if (cameraSelectionToggle == null) return;

        switch (cameraSelection) {
            case Constantes.CAMERA_FRONT:
                cameraSelectionToggle.check(R.id.button_front_camera);
                updateButtonAppearance(buttonFrontCamera, true);
                updateButtonAppearance(buttonBackCamera, false);
                updateButtonAppearance(buttonDualCamera, false);
                break;
            case Constantes.CAMERA_DUAL:
                cameraSelectionToggle.check(R.id.button_dual_camera);
                updateButtonAppearance(buttonDualCamera, true);
                updateButtonAppearance(buttonFrontCamera, false);
                updateButtonAppearance(buttonBackCamera, false);
                break;
            default: // CAMERA_BACK
                cameraSelectionToggle.check(R.id.button_back_camera);
                updateButtonAppearance(buttonBackCamera, true);
                updateButtonAppearance(buttonFrontCamera, false);
                updateButtonAppearance(buttonDualCamera, false);
                break;
        }
    }
}
