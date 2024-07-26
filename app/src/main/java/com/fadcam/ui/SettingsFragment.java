package com.fadcam.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;

import androidx.fragment.app.Fragment;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;

import com.fadcam.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class SettingsFragment extends Fragment {

    private SharedPreferences sharedPreferences;
    private static final String PREF_CAMERA_SELECTION = "camera_selection";
    private static final String PREF_VIDEO_QUALITY = "video_quality";
    private static final String CAMERA_FRONT = "front";
    private static final String CAMERA_BACK = "back";
    private static final String QUALITY_SD = "SD";
    private static final String QUALITY_HD = "HD";
    private static final String QUALITY_FHD = "FHD";

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);

        sharedPreferences = requireActivity().getPreferences(Context.MODE_PRIVATE);

        MaterialToolbar toolbar = view.findViewById(R.id.topAppBar);

        MaterialButton readmeButton = view.findViewById(R.id.readme_button);
        readmeButton.setOnClickListener(v -> showReadmeDialog());

        MaterialButtonToggleGroup cameraSelectionToggle = view.findViewById(R.id.camera_selection_toggle);
        Spinner qualitySpinner = view.findViewById(R.id.quality_spinner);

        // Set up camera selection toggle
        setupCameraSelectionToggle(view, cameraSelectionToggle);

        // Set up video quality spinner
        setupQualitySpinner(view, qualitySpinner);

        return view;
    }

    private void setupCameraSelectionToggle(View view, MaterialButtonToggleGroup cameraSelectionToggle) {
        MaterialButton backCameraButton = view.findViewById(R.id.button_back_camera);
        MaterialButton frontCameraButton = view.findViewById(R.id.button_front_camera);

        String currentCameraSelection = sharedPreferences.getString(PREF_CAMERA_SELECTION, CAMERA_BACK);

        if (currentCameraSelection.equals(CAMERA_FRONT)) {
            cameraSelectionToggle.check(R.id.button_front_camera);
        } else {
            cameraSelectionToggle.check(R.id.button_back_camera);
        }

        cameraSelectionToggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                String selectedCamera = (checkedId == R.id.button_front_camera) ? CAMERA_FRONT : CAMERA_BACK;
                sharedPreferences.edit().putString(PREF_CAMERA_SELECTION, selectedCamera).apply();
            }
        });
    }

    private void setupQualitySpinner(View view, Spinner qualitySpinner) {
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(requireContext(),
                R.array.video_quality_array, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        qualitySpinner.setAdapter(adapter);

        String currentQuality = sharedPreferences.getString(PREF_VIDEO_QUALITY, QUALITY_HD);
        qualitySpinner.setSelection(adapter.getPosition(currentQuality));

        qualitySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selectedQuality = (String) parent.getItemAtPosition(position);
                sharedPreferences.edit().putString(PREF_VIDEO_QUALITY, selectedQuality).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Do nothing
            }
        });
    }

    private void showReadmeDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext());
        builder.setTitle("README");

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
}