package com.fadcam.ui;


import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
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
        TextView privacyInfoText = view.findViewById(R.id.privacy_info_text);

        appIcon.setImageResource(R.mipmap.ic_launcher);
        appName.setText(getString(R.string.app_name));
        appVersion.setText(String.format("Version %s", getAppVersion()));
//        appDescription.setText(getString(R.string.app_description));

        sourceCodeButton.setOnClickListener(v -> openUrl("https://github.com/anonfaded/FadCam"));
        donateButton.setOnClickListener(v -> openUrl("https://ko-fi.com/fadedx"));
        emailText.setOnClickListener(v -> sendEmail());
        discordText.setOnClickListener(v -> openUrl("https://discord.gg/gUMnBJxGBW"));
        String privacyInfo = "Q: Does FadCam collect any user data?\nA: No, FadCam does not collect, track, or analyze any user data. Your privacy is our priority.\n\n" +
                "Q: Are there any ads in FadCam?\nA: FadCam is completely ad-free, providing an uninterrupted user experience.";
        privacyInfoText.setText(privacyInfo);


        return view;
    }

    private String getAppVersion() {
        try {
            PackageManager pm = getActivity().getPackageManager();
            PackageInfo pInfo = pm.getPackageInfo(getActivity().getPackageName(), 0);
            return pInfo.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            return "N/A";
        }
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
