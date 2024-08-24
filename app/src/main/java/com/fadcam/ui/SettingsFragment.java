package com.fadcam.ui;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.fadcam.MainActivity;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;

import com.fadcam.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.Locale;

public class SettingsFragment extends Fragment {

    private SharedPreferences sharedPreferences;

    private LocationHelper locationHelper;

    private static final String PREF_CAMERA_SELECTION = "camera_selection";
    private static final String PREF_VIDEO_QUALITY = "video_quality";
    private static final String PREF_WATERMARK_OPTION = "watermark_option";
    private static final String CAMERA_FRONT = "front";
    private static final String CAMERA_BACK = "back";
    private static final String QUALITY_SD = "SD";
    private static final String QUALITY_HD = "HD";
    private static final String QUALITY_FHD = "FHD";
    static final String PREF_LOCATION_DATA = "location_data";

    private static final String PREFS_NAME = "app_prefs";
    private static final String LANGUAGE_KEY = "language";

    private static final int REQUEST_PERMISSIONS = 1;
    private static final String PREF_FIRST_LAUNCH = "first_launch";

    MaterialButtonToggleGroup cameraSelectionToggle;
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
        sharedPreferences = requireActivity().getPreferences(Context.MODE_PRIVATE);
        locationHelper = new LocationHelper(requireContext());
    }

    @Override
    public void onResume() {
        super.onResume();
        syncCameraSwitch(view, cameraSelectionToggle);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        view = inflater.inflate(R.layout.fragment_settings, container, false);


        // Initialize shared preferences
        sharedPreferences = requireActivity().getPreferences(Context.MODE_PRIVATE);

        // Setup  language selection spinner items with array resource
        Spinner languageSpinner = view.findViewById(R.id.language_spinner);
        ArrayAdapter<CharSequence> languageAdapter  = ArrayAdapter.createFromResource(
                getContext(), R.array.languages_array, android.R.layout.simple_spinner_item);
        languageAdapter .setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        languageSpinner.setAdapter(languageAdapter );





        sharedPreferences = requireActivity().getPreferences(Context.MODE_PRIVATE);

        MaterialToolbar toolbar = view.findViewById(R.id.topAppBar);

        MaterialButton readmeButton = view.findViewById(R.id.readme_button);
        readmeButton.setOnClickListener(v -> showReadmeDialog());

        cameraSelectionToggle = view.findViewById(R.id.camera_selection_toggle);
        // Setup spinner items with array resource
        Spinner qualitySpinner = view.findViewById(R.id.quality_spinner);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                getContext(), R.array.video_quality_options, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        qualitySpinner.setAdapter(adapter);

        // Set up camera selection toggle
        setupCameraSelectionToggle(view, cameraSelectionToggle);

        // Set up video quality spinner
        setupQualitySpinner(view, qualitySpinner);

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

        return view;
    }

    private void openInAppBrowser(String url) {
        Intent intent = new Intent(getContext(), WebViewActivity.class);
        intent.putExtra("url", url);
        startActivity(intent);
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
        String currentCameraSelection = sharedPreferences.getString(PREF_CAMERA_SELECTION, CAMERA_BACK);
        if (currentCameraSelection.equals(CAMERA_FRONT)) {
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
                String selectedCamera = (checkedId == R.id.button_front_camera) ? CAMERA_FRONT : CAMERA_BACK;
                sharedPreferences.edit().putString(PREF_CAMERA_SELECTION, selectedCamera).apply();
                updateButtonAppearance(backCameraButton, checkedId == R.id.button_back_camera);
                updateButtonAppearance(frontCameraButton, checkedId == R.id.button_front_camera);
            }
        });
    }



    private void setupCameraSelectionToggle(View view, MaterialButtonToggleGroup cameraSelectionToggle) {
        MaterialButton backCameraButton = view.findViewById(R.id.button_back_camera);
        MaterialButton frontCameraButton = view.findViewById(R.id.button_front_camera);

        String currentCameraSelection = sharedPreferences.getString(PREF_CAMERA_SELECTION, CAMERA_BACK);

        if (currentCameraSelection.equals(CAMERA_FRONT)) {
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
                String selectedCamera = (checkedId == R.id.button_front_camera) ? CAMERA_FRONT : CAMERA_BACK;
                sharedPreferences.edit().putString(PREF_CAMERA_SELECTION, selectedCamera).apply();
                updateButtonAppearance(backCameraButton, checkedId == R.id.button_back_camera);
                updateButtonAppearance(frontCameraButton, checkedId == R.id.button_front_camera);
            }
        });
    }

    private void setupQualitySpinner(View view, Spinner qualitySpinner) {
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(requireContext(),
                R.array.video_quality_array, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        qualitySpinner.setAdapter(adapter);

        // Set the selected item based on the saved preference
        String selectedQuality = sharedPreferences.getString(PREF_VIDEO_QUALITY, QUALITY_HD);
        int selectedIndex = 1; // Default to HD
        switch (selectedQuality) {
            case QUALITY_FHD:
                selectedIndex = 0;
                break;
            case QUALITY_SD:
                selectedIndex = 2;
                break;
        }
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
                sharedPreferences.edit().putString(PREF_VIDEO_QUALITY, selectedQuality).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Do nothing
            }
        });
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
        builder.setTitle("Welcome to FadCam!");

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

}