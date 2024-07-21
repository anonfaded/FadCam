package com.fadcam.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.fragment.app.Fragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textview.MaterialTextView;

import com.fadcam.R;

public class SettingsFragment extends Fragment {

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);

        MaterialTextView titleTextView = view.findViewById(R.id.settings_title);
        titleTextView.setText("Settings");

        MaterialButton readmeButton = view.findViewById(R.id.readme_button);
        readmeButton.setOnClickListener(v -> showReadmeDialog());

        MaterialTextView comingSoonTextView = view.findViewById(R.id.coming_soon_text);
        comingSoonTextView.setText("More settings coming soon...");

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