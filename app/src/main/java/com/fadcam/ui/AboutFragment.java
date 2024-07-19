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
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import com.fadcam.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

public class AboutFragment extends Fragment {

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_about, container, false);

        initializeViews(view);

        return view;
    }

    private void initializeViews(View view) {
        ImageView appIcon = view.findViewById(R.id.app_icon);
        TextView appName = view.findViewById(R.id.app_name);
        TextView appVersion = view.findViewById(R.id.app_version);
        TextView appDescription = view.findViewById(R.id.app_description);
        MaterialCardView fadSecInfoCard = view.findViewById(R.id.fadsec_info_card);
        MaterialButton sourceCodeButton = view.findViewById(R.id.source_code_button);
        MaterialButton donateButton = view.findViewById(R.id.donate_button);
        MaterialButton checkUpdatesButton = view.findViewById(R.id.check_updates_button);
        TextView emailText = view.findViewById(R.id.email_text);
        TextView discordText = view.findViewById(R.id.discord_text);
        MaterialCardView privacyInfoCard = view.findViewById(R.id.privacy_info_card);

        appIcon.setImageResource(R.mipmap.ic_launcher);
        appName.setText(getString(R.string.app_name));
        appVersion.setText(String.format("Version %s", getAppVersion()));

        sourceCodeButton.setOnClickListener(v -> openUrl("https://github.com/fadsec-lab/FadCam"));
        donateButton.setOnClickListener(v -> openUrl("https://ko-fi.com/fadedx"));
        checkUpdatesButton.setOnClickListener(v -> checkForUpdates());
        emailText.setOnClickListener(v -> sendEmail());
        discordText.setOnClickListener(v -> openUrl("https://discord.gg/kvAZvdkuuN"));

        setupPrivacyInfo(privacyInfoCard);
    }

    private void setupPrivacyInfo(MaterialCardView cardView) {
        String[] questions = {
                "Does FadCam collect any user data?",
                "Are there any ads in FadCam?"
        };
        String[] answers = {
                "No, FadCam does not collect, track, or analyze any user data. Your privacy is our priority.",
                "FadCam is completely ad-free, providing an uninterrupted user experience."
        };

        for (int i = 0; i < questions.length; i++) {
            View qnaItem = getLayoutInflater().inflate(R.layout.qna_item, cardView, false);
            TextView questionView = qnaItem.findViewById(R.id.question);
            TextView answerView = qnaItem.findViewById(R.id.answer);

            questionView.setText(questions[i]);
            answerView.setText(answers[i]);

            questionView.setTextColor(getResources().getColor(R.color.white));
            answerView.setTextColor(getResources().getColor(R.color.black));

            qnaItem.setOnClickListener(v -> {
                answerView.setVisibility(answerView.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            });

            cardView.addView(qnaItem);
        }
    }

    private String getAppVersion() {
        try {
            PackageManager pm = requireActivity().getPackageManager();
            PackageInfo pInfo = pm.getPackageInfo(requireActivity().getPackageName(), 0);
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

    private void checkForUpdates() {
        Toast.makeText(requireContext(), "Checking for updates...", Toast.LENGTH_SHORT).show();
        // Implement the update checking logic here
        // You might want to use a background thread to check for updates
        // and then update the UI accordingly
        openUrl("https://github.com/anonfaded/FadCam/releases");
    }
}