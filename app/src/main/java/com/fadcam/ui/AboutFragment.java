package com.fadcam.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.Html;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
        ScrollView scrollView = view.findViewById(R.id.scroll_view);

        appIcon.setImageResource(R.mipmap.ic_launcher);
        appName.setText(getString(R.string.app_name));
        appVersion.setText(String.format("Version %s", getAppVersion()));

        sourceCodeButton.setOnClickListener(v -> openUrl("https://github.com/fadsec-lab/FadCam"));
        donateButton.setOnClickListener(v -> openUrl("https://ko-fi.com/fadedx"));
        checkUpdatesButton.setOnClickListener(v -> checkForUpdates());
        emailText.setOnClickListener(v -> sendEmail());
        discordText.setOnClickListener(v -> openUrl("https://discord.gg/kvAZvdkuuN"));

        setupPrivacyInfo(privacyInfoCard, scrollView);
    }















    private void setupPrivacyInfo(MaterialCardView cardView, ScrollView scrollView) {
        String[] questions = {
                "Does FadCam collect any user data?",
                "Are there any ads in FadCam?"
        };
        String[] answers = {
                "No, FadCam does not collect, track, or analyze any user data. Your privacy is our priority.",
                "FadCam is completely ad-free, providing an uninterrupted user experience."
        };

        StringBuilder qnaContent = new StringBuilder();
        for (int i = 0; i < questions.length; i++) {
            qnaContent.append("<b><font color='#FFFFFF'>").append(questions[i]).append("</font></b><br>")
                    .append("<font color='#CFBAFD'>").append(answers[i]).append("</font><br><br>");
        }

        TextView privacyInfoContent = cardView.findViewById(R.id.privacy_info_content);
        privacyInfoContent.setText(Html.fromHtml(qnaContent.toString(), Html.FROM_HTML_MODE_LEGACY));

        ImageView expandIcon = cardView.findViewById(R.id.expand_icon);
        LinearLayout headerLayout = (LinearLayout) expandIcon.getParent();

        headerLayout.setOnClickListener(v -> {
            boolean isVisible = privacyInfoContent.getVisibility() == View.VISIBLE;

            if (!isVisible) {
                privacyInfoContent.setVisibility(View.VISIBLE);
            }

            privacyInfoContent.measure(View.MeasureSpec.makeMeasureSpec(cardView.getWidth(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int startHeight = isVisible ? privacyInfoContent.getHeight() : 0;
            int endHeight = isVisible ? 0 : privacyInfoContent.getMeasuredHeight();

            ValueAnimator heightAnimator = ValueAnimator.ofInt(startHeight, endHeight);
            heightAnimator.addUpdateListener(animation -> {
                privacyInfoContent.getLayoutParams().height = (int) animation.getAnimatedValue();
                privacyInfoContent.requestLayout();
            });
            heightAnimator.setDuration(300);

            heightAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationStart(Animator animation) {
                    // Start scroll animation
                    if (!isVisible) {
                        scrollView.post(() -> {
                            // Delay to ensure the card is fully expanded
                            scrollView.postDelayed(() -> {
                                int cardBottom = cardView.getBottom();
                                int scrollViewBottom = scrollView.getHeight() + scrollView.getScrollY();
                                int scrollAmount = cardBottom - scrollViewBottom;

                                ValueAnimator scrollAnimator = ValueAnimator.ofInt(scrollView.getScrollY(), scrollView.getScrollY() + scrollAmount);
                                scrollAnimator.addUpdateListener(scrollAnimation -> {
                                    scrollView.scrollTo(0, (int) scrollAnimation.getAnimatedValue());
                                });
                                scrollAnimator.setDuration(300); // Match the card animation duration
                                scrollAnimator.start();
                            }, 300); // Delay to match the expansion duration
                        });
                    }
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    if (isVisible) {
                        privacyInfoContent.setVisibility(View.GONE);
                    }
                }
            });

            heightAnimator.start();

            // Add animation for icon rotation
            float startRotation = isVisible ? 180f : 0f;
            float endRotation = isVisible ? 0f : 180f;
            ObjectAnimator iconAnimator = ObjectAnimator.ofFloat(expandIcon, "rotation", startRotation, endRotation);
            iconAnimator.setDuration(300);
            iconAnimator.start();
        });
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
