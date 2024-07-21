package com.fadcam.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioGroup;

import androidx.fragment.app.Fragment;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textview.MaterialTextView;

import com.fadcam.R;

public class SettingsFragment extends Fragment {

    private SharedPreferences sharedPreferences;
    private static final String PREF_CAMERA_SELECTION = "camera_selection";
    private static final String CAMERA_FRONT = "front";
    private static final String CAMERA_BACK = "back";

    private void updateButtonAppearance(MaterialButton button, boolean isSelected) {
        button.setIconTintResource(isSelected ? R.color.colorPrimary : android.R.color.transparent);
        button.setStrokeColorResource(isSelected ? R.color.colorPrimary : R.color.material_on_surface_stroke);
        button.setTextColor(getResources().getColor(isSelected ? R.color.colorPrimary : R.color.material_on_surface_emphasis_medium));
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);

        sharedPreferences = requireActivity().getPreferences(Context.MODE_PRIVATE);

        MaterialToolbar toolbar = view.findViewById(R.id.topAppBar);

        MaterialButton readmeButton = view.findViewById(R.id.readme_button);
        readmeButton.setOnClickListener(v -> showReadmeDialog());

        MaterialButtonToggleGroup cameraSelectionToggle = view.findViewById(R.id.camera_selection_toggle);
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

        return view;
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