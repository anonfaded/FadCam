package com.fadcam.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import com.fadcam.R;

public class AboutFragment extends Fragment {

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_about, container, false);

        ImageView appIcon = view.findViewById(R.id.app_icon);
        TextView appName = view.findViewById(R.id.app_name);
        TextView appVersion = view.findViewById(R.id.app_version);
        TextView appDescription = view.findViewById(R.id.app_description);
        View sourceCodeButton = view.findViewById(R.id.source_code_button);
        View donateButton = view.findViewById(R.id.donate_button);
        TextView emailText = view.findViewById(R.id.email_text);
        TextView discordText = view.findViewById(R.id.discord_text);

        appIcon.setImageResource(R.mipmap.ic_launcher);
        appName.setText(getString(R.string.app_name));
        Object BuildConfig = null;
        appVersion.setText(String.format("Version %s", BuildConfig));
        appDescription.setText(getString(R.string.app_description));

        sourceCodeButton.setOnClickListener(v -> openUrl("https://github.com/anonfaded/FadCam"));
        donateButton.setOnClickListener(v -> openUrl("https://ko-fi.com/fadedx"));
        emailText.setOnClickListener(v -> sendEmail());
        discordText.setOnClickListener(v -> openUrl("https://discord.gg/gUMnBJxGBW"));

        return view;
    }

    private void openUrl(String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        startActivity(intent);
    }

    private void sendEmail() {
        Intent intent = new Intent(Intent.ACTION_SENDTO);
        intent.setData(Uri.parse("mailto:fadedhood@proton.me"));
        intent.putExtra(Intent.EXTRA_SUBJECT, "FadCam Feedback");
        startActivity(intent);
    }
}