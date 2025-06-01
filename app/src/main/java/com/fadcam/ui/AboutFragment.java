package com.fadcam.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.text.Html;
import android.text.Spanned;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.graphics.drawable.Drawable;

import androidx.appcompat.app.AlertDialog;
import androidx.cardview.widget.CardView;
import androidx.core.text.HtmlCompat;
import androidx.fragment.app.Fragment;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.fadcam.Constants;
import com.fadcam.Log;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AboutFragment extends BaseFragment {

    private View view;
    private ExecutorService executorService;
    private AlertDialog loadingDialog;
    private MaterialAlertDialogBuilder alertDialogBuilder;


    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        view = inflater.inflate(R.layout.fragment_about, container, false);
        
        // Initialize with executor service
        executorService = Executors.newSingleThreadExecutor();
        
        // Initialize views and setup interactions
        initializeViews();
        
        // Apply theme-specific UI adjustments for Snow Veil theme
        SharedPreferencesManager sharedPreferencesManager = SharedPreferencesManager.getInstance(requireContext());
        String currentTheme = sharedPreferencesManager.sharedPreferences.getString(com.fadcam.Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);
        if ("Snow Veil".equals(currentTheme)) {
            applySnowVeilThemeToUI(view);
        }
        
        return view;
    }

    private void initializeViews() {
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

        int colorHeading = resolveThemeColor(R.attr.colorHeading);
        int colorButton = resolveThemeColor(R.attr.colorButton);
        int colorDialog = resolveThemeColor(R.attr.colorDialog);
        int colorOnPrimary = resolveThemeColor(android.R.attr.textColorPrimary);
        int colorOnSurface = resolveThemeColor(android.R.attr.textColorSecondary);
        
        // Get current theme
        String currentTheme = com.fadcam.SharedPreferencesManager.getInstance(requireContext()).sharedPreferences.getString(com.fadcam.Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);
        
        // Define theme-specific colors
        int themeTextColor;
        if ("Midnight Dusk".equals(currentTheme)) {
            themeTextColor = ContextCompat.getColor(requireContext(), R.color.colorPrimary); // Light purple (#cfbafd)
        } else if ("Crimson Bloom".equals(currentTheme)) {
            themeTextColor = ContextCompat.getColor(requireContext(), R.color.red_theme_secondary); // Bright red (#FF5252)
        } else if ("Premium Gold".equals(currentTheme)) {
            themeTextColor = ContextCompat.getColor(requireContext(), R.color.gold_theme_primary); // Gold (#FFD700)
        } else if ("Silent Forest".equals(currentTheme)) {
            themeTextColor = ContextCompat.getColor(requireContext(), R.color.silentforest_theme_primary); // Green (#26A69A)
        } else if ("Shadow Alloy".equals(currentTheme)) {
            themeTextColor = ContextCompat.getColor(requireContext(), R.color.shadowalloy_theme_primary); // Silver (#A5A9AB)
        } else if ("Pookie Pink".equals(currentTheme)) {
            themeTextColor = ContextCompat.getColor(requireContext(), R.color.pookiepink_theme_primary); // Pink (#F06292)
        } else if ("Snow Veil".equals(currentTheme)) {
            themeTextColor = ContextCompat.getColor(requireContext(), R.color.snowveil_theme_text_primary); // Black (#212121)
            // Set any Snow Veil specific theme elements to black
            appDescription.setTextColor(Color.BLACK);
            emailText.setTextColor(Color.BLACK);
            discordText.setTextColor(Color.BLACK);
            checkUpdatesButton.setTextColor(Color.BLACK);
            checkUpdatesButton.setIconTint(ColorStateList.valueOf(Color.BLACK));
        } else {
            themeTextColor = Color.WHITE; // Default for Faded Night
        }

        appIcon.setImageResource(R.mipmap.ic_launcher);
        appName.setText(getString(R.string.app_name));
        // Set app name to theme color instead of default colorHeading
        appName.setTextColor(themeTextColor);
        
        try {
            String versionName = requireContext().getPackageManager().getPackageInfo(requireContext().getPackageName(), 0).versionName;
            appVersion.setText(getString(R.string.version_format, versionName));
            appVersion.setTextColor(Color.WHITE);
        } catch (Exception e) {
            appVersion.setText("");
        }
        String appDesc = getString(R.string.app_description);
        // Use theme-specific colors for highlighting
        String highlightColorHex;
        if ("Midnight Dusk".equals(currentTheme)) {
            highlightColorHex = "#cfbafd"; // Light purple for Midnight Dusk
        } else if ("Crimson Bloom".equals(currentTheme)) {
            highlightColorHex = "#FF5252"; // Red theme secondary color for Crimson Bloom
        } else if ("Premium Gold".equals(currentTheme)) {
            highlightColorHex = "#FFD700"; // Gold color for Premium Gold
        } else if ("Silent Forest".equals(currentTheme)) {
            highlightColorHex = "#26A69A"; // Green color for Silent Forest
        } else if ("Shadow Alloy".equals(currentTheme)) {
            highlightColorHex = "#A5A9AB"; // Silver color for Shadow Alloy
        } else if ("Pookie Pink".equals(currentTheme)) {
            highlightColorHex = "#F06292"; // Pink color for Pookie Pink
        } else if ("Snow Veil".equals(currentTheme)) {
            highlightColorHex = "#2196F3"; // Light Blue for Snow Veil
        } else {
            highlightColorHex = "#AAAAAA"; // Default dark gray for other themes (like Faded Night)
        }
        
        appDesc = appDesc.replaceAll("#cfbafd", highlightColorHex);
        appDescription.setText(Html.fromHtml(appDesc, Html.FROM_HTML_MODE_LEGACY));
        appDescription.setTextColor(Color.WHITE);
        checkUpdatesButton.setTextColor(Color.WHITE);
        checkUpdatesButton.setIconTint(ColorStateList.valueOf(Color.WHITE));
        checkUpdatesButton.setStrokeColor(ColorStateList.valueOf(colorButton));
        fadSecInfoCard.setCardBackgroundColor(colorDialog);
        fadSecInfoCard.setStrokeColor(colorButton);
        TextView fadSecInfoText = fadSecInfoCard.findViewById(R.id.fadsec_info_text);
        if (fadSecInfoText != null) fadSecInfoText.setTextColor(Color.WHITE);
        sourceCodeButton.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.gray_button_filled)));
        sourceCodeButton.setStrokeColor(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.gray)));
        sourceCodeButton.setTextColor(Color.WHITE);
        sourceCodeButton.setIconTint(ColorStateList.valueOf(Color.WHITE));
        int gold = ContextCompat.getColor(requireContext(), R.color.gold);
        int black = ContextCompat.getColor(requireContext(), R.color.black);
        donateButton.setBackgroundTintList(ColorStateList.valueOf(gold));
        donateButton.setTextColor(black);
        donateButton.setIconTint(ColorStateList.valueOf(black));
        emailText.setTextColor(Color.WHITE);
        discordText.setTextColor(Color.WHITE);
        privacyInfoCard.setCardBackgroundColor(colorDialog);
        privacyInfoCard.setStrokeColor(colorButton);
        LinearLayout privacyHeader = privacyInfoCard.findViewById(R.id.privacy_info_header);
        if (privacyHeader != null) {
            TextView privacyTitle = privacyHeader.findViewById(R.id.privacy_info_title);
            if (privacyTitle != null) privacyTitle.setTextColor(themeTextColor);
            ImageView expandIcon = privacyHeader.findViewById(R.id.expand_icon);
            if (expandIcon != null) expandIcon.setColorFilter(themeTextColor);
        }
        TextView privacyInfoContent = privacyInfoCard.findViewById(R.id.privacy_info_content);
        if (privacyInfoContent != null) privacyInfoContent.setTextColor(Color.WHITE);
        String[] questions = getResources().getStringArray(R.array.questions_array);
        String[] answers = getResources().getStringArray(R.array.answers_array);
        
        // Check current theme to determine answers color
        String currentThemeAnswers = com.fadcam.SharedPreferencesManager.getInstance(requireContext()).sharedPreferences.getString(com.fadcam.Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);
        
        // Use theme-specific colors for answers
        String answerColorHex;
        if ("Midnight Dusk".equals(currentThemeAnswers)) {
            answerColorHex = "#cfbafd"; // Light purple for Midnight Dusk
        } else if ("Crimson Bloom".equals(currentThemeAnswers)) {
            answerColorHex = "#FF5252"; // Red theme secondary color for Crimson Bloom
        } else if ("Premium Gold".equals(currentThemeAnswers)) {
            answerColorHex = "#FFD700"; // Gold color for Premium Gold
        } else if ("Silent Forest".equals(currentThemeAnswers)) {
            answerColorHex = "#26A69A"; // Green color for Silent Forest
        } else if ("Shadow Alloy".equals(currentThemeAnswers)) {
            answerColorHex = "#A5A9AB"; // Silver color for Shadow Alloy
        } else if ("Pookie Pink".equals(currentThemeAnswers)) {
            answerColorHex = "#F06292"; // Pink color for Pookie Pink
        } else if ("Snow Veil".equals(currentThemeAnswers)) {
            answerColorHex = "#2196F3"; // Light Blue for Snow Veil theme
        } else {
            answerColorHex = "#AAAAAA"; // Default dark gray for other themes (like Faded Night)
        }
        
        StringBuilder qnaContent = new StringBuilder();
        for (int i = 0; i < questions.length; i++) {
            qnaContent.append("<b><font color='#FFFFFF'>").append(questions[i]).append("</font></b><br>")
                    .append("<font color='" + answerColorHex + "'>").append(answers[i]).append("</font><br><br>");
        }
        privacyInfoContent.setText(Html.fromHtml(qnaContent.toString(), Html.FROM_HTML_MODE_LEGACY));
        sourceCodeButton.setOnClickListener(v -> openUrl("https://github.com/fadsec-lab/"));
        donateButton.setOnClickListener(v -> {
            KoFiSupportBottomSheet bottomSheet = new KoFiSupportBottomSheet();
            bottomSheet.show(getParentFragmentManager(), "KoFiSupportBottomSheet");
        });
        checkUpdatesButton.setOnClickListener(v -> checkForUpdates());
        emailText.setOnClickListener(v -> sendEmail());
        discordText.setOnClickListener(v -> openUrl("https://discord.gg/kvAZvdkuuN"));
        setupPrivacyInfo(privacyInfoCard, scrollView);
        view.findViewById(R.id.check_updates_button).setOnClickListener(v -> checkForUpdates());

        // Find text elements with specific content and set their colors
        ScrollView scrollViewObj = view.findViewById(R.id.scroll_view);
        if (scrollViewObj != null && scrollViewObj.getChildCount() > 0) {
            ViewGroup rootLayout = (ViewGroup) scrollViewObj.getChildAt(0);
            for (int i = 0; i < rootLayout.getChildCount(); i++) {
                View child = rootLayout.getChildAt(i);
                if (child instanceof TextView) {
                    TextView textView = (TextView) child;
                    CharSequence text = textView.getText();
                    if (text != null) {
                        String textStr = text.toString();
                        // Set copyright text color
                        if (textStr.contains(getString(R.string.copyright_info))) {
                            textView.setTextColor(themeTextColor);
                        } 
                        // Set contact heading color
                        else if (textStr.equals(getString(R.string.contact))) {
                            textView.setTextColor(themeTextColor);
                        }
                    }
                }
            }
        }

        // Initialize the alert dialog builder just once
        alertDialogBuilder = new MaterialAlertDialogBuilder(requireContext())
                .setView(R.layout.loading_dialog)
                .setCancelable(false);
    }

    private void setupPrivacyInfo(MaterialCardView cardView, ScrollView scrollView) {
        String[] questions = getResources().getStringArray(R.array.questions_array);
        String[] answers = getResources().getStringArray(R.array.answers_array);
        
        // Check current theme to determine answers color
        String currentTheme = com.fadcam.SharedPreferencesManager.getInstance(requireContext()).sharedPreferences.getString(com.fadcam.Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);
        
        // Use theme-specific colors for answers
        String answerColorHex;
        if ("Midnight Dusk".equals(currentTheme)) {
            answerColorHex = "#cfbafd"; // Light purple for Midnight Dusk
        } else if ("Crimson Bloom".equals(currentTheme)) {
            answerColorHex = "#FF5252"; // Red theme secondary color for Crimson Bloom
        } else if ("Premium Gold".equals(currentTheme)) {
            answerColorHex = "#FFD700"; // Gold color for Premium Gold
        } else if ("Silent Forest".equals(currentTheme)) {
            answerColorHex = "#26A69A"; // Green color for Silent Forest
        } else if ("Shadow Alloy".equals(currentTheme)) {
            answerColorHex = "#A5A9AB"; // Silver color for Shadow Alloy
        } else if ("Pookie Pink".equals(currentTheme)) {
            answerColorHex = "#F06292"; // Pink color for Pookie Pink
        } else if ("Snow Veil".equals(currentTheme)) {
            answerColorHex = "#2196F3"; // Light Blue for Snow Veil
        } else {
            answerColorHex = "#AAAAAA"; // Default dark gray for other themes (like Faded Night)
        }
        
        // Determine question text color based on theme
        String questionColorHex = "#FFFFFF"; // Default white for most themes
        if ("Snow Veil".equals(currentTheme)) {
            questionColorHex = "#000000"; // Black for Snow Veil theme
        }
        
        StringBuilder qnaContent = new StringBuilder();
        for (int i = 0; i < questions.length; i++) {
            qnaContent.append("<b><font color='").append(questionColorHex).append("'>").append(questions[i]).append("</font></b><br>")
                    .append("<font color='" + answerColorHex + "'>").append(answers[i]).append("</font><br><br>");
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
                    if (!isVisible) {
                        scrollView.post(() -> {
                            scrollView.post(() -> {
                                scrollView.postDelayed(() -> {
                                    int cardTop = cardView.getTop();
                                    ValueAnimator scrollAnimator = ValueAnimator.ofInt(scrollView.getScrollY(), cardTop);
                                    scrollAnimator.addUpdateListener(scrollAnimation -> {
                                        scrollView.scrollTo(0, (int) scrollAnimation.getAnimatedValue());
                                    });
                                    scrollAnimator.setDuration(400); // Match the card animation duration
                                    scrollAnimator.start();
                                }, 400); // Delay to match the expansion duration
                            });

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

    private String getAppVersionForUpdates() {
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


    // Code for checking updates


    private void checkForUpdates() {
        showLoadingDialog(getString(R.string.up_to_date_loading));

        executorService.execute(() -> {
            try {
                JSONObject releaseInfo = fetchLatestReleaseInfo();
                String latestVersion = releaseInfo.getString("tag_name").substring(1); // Remove 'v' prefix
                String currentVersion = getAppVersionForUpdates();
//                String downloadUrl = getDownloadUrl(releaseInfo);

                requireActivity().runOnUiThread(() -> {
                    dismissLoadingDialog();
                    if (isUpdateAvailable(currentVersion, latestVersion)) {
//                        showUpdateAvailableDialog(latestVersion, downloadUrl);
                        showUpdateAvailableDialog(latestVersion); // Pass only the latestVersion
                    } else {
                        dismissLoadingDialog(); // Dismiss the loading dialog in case of an error

                        showUpToDateDialog();


                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
                requireActivity().runOnUiThread(() -> {
                    dismissLoadingDialog();
                    showErrorDialog("Failed to check for updates. Please try again later.");
                });
            }
        });
    }

    private JSONObject fetchLatestReleaseInfo() throws Exception {
        URL url = new URL("https://api.github.com/repos/anonfaded/FadCam/releases/latest");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
            StringBuilder result = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line);
            }
            return new JSONObject(result.toString());
        } finally {
            connection.disconnect();
        }
    }

    private String getDownloadUrl(JSONObject releaseInfo) throws JSONException {
        JSONArray assets = releaseInfo.getJSONArray("assets");
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            if (asset.getString("name").endsWith(".apk")) {
                return asset.getString("browser_download_url");
            }
        }
        throw new JSONException("No APK found in release assets");
    }

    private String getAppVersion() {
        try {
            PackageManager pm = requireActivity().getPackageManager();
            PackageInfo pInfo = pm.getPackageInfo(requireActivity().getPackageName(), 0);
            return pInfo.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            return "0.0.0";
        }
    }

    private boolean isUpdateAvailable(String currentVersion, String latestVersion) {
        boolean currentIsBeta = currentVersion.contains("beta");

        currentVersion = currentVersion.replace("-beta", "");
        String[] current = currentVersion.split("\\.");
        String[] latest = latestVersion.split("\\.");

        for (int i = 0; i < Math.min(current.length, latest.length); i++) {
            int currentPart = Integer.parseInt(current[i]);
            int latestPart = Integer.parseInt(latest[i]);

            if (latestPart > currentPart) {
                return true;
            } else if (latestPart < currentPart) {
                return false;
            }
        }

        return latest.length > current.length || (latest.length == current.length && currentIsBeta);
    }

    private void showLoadingDialog(String message) {
        requireActivity().runOnUiThread(() -> {
            TextView messageView = new TextView(requireContext());
            messageView.setText(message);
            
            // Check if we're using Snow Veil theme
            String currentTheme = SharedPreferencesManager.getInstance(requireContext()).sharedPreferences.getString(com.fadcam.Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);
            boolean isSnowVeilTheme = "Snow Veil".equals(currentTheme);
            
            // Set text color based on theme
            messageView.setTextColor(isSnowVeilTheme ? Color.BLACK : Color.WHITE);
            
            messageView.setPadding(48, 32, 48, 32);
            messageView.setTextSize(16);
            MaterialAlertDialogBuilder builder = themedDialogBuilder(requireContext());
            builder.setView(messageView);
            builder.setCancelable(false);
            loadingDialog = builder.create();
            loadingDialog.show();
        });
    }

    private void dismissLoadingDialog() {
        if (loadingDialog != null && loadingDialog.isShowing()) {
            loadingDialog.dismiss();
        }
    }


    private void showUpdateAvailableDialog(String newVersion) {
        MaterialAlertDialogBuilder builder = themedDialogBuilder(requireContext())
                .setTitle(getString(R.string.update_available_title))
                .setMessage(getString(R.string.update_available_message, newVersion))
                .setPositiveButton(getString(R.string.visit_fdroid), (dialog, which) -> {
                    openUpdateUrl("https://f-droid.org/packages/com.fadcam");
                })
                .setNegativeButton(getString(R.string.visit_github), (dialog, which) -> {
                    openUpdateUrl("https://github.com/anonfaded/FadCam");
                });
        
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(dialogInterface -> setDialogButtonColors(dialog));
        dialog.show();
    }


    private void showUpToDateDialog() {
        TextView messageView = new TextView(requireContext());
        messageView.setText(getString(R.string.up_to_date_description));
        
        // Check if we're using Snow Veil theme
        String currentTheme = SharedPreferencesManager.getInstance(requireContext()).sharedPreferences.getString(com.fadcam.Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);
        boolean isSnowVeilTheme = "Snow Veil".equals(currentTheme);
        
        // Set text color based on theme
        messageView.setTextColor(isSnowVeilTheme ? Color.BLACK : Color.WHITE);
        
        messageView.setPadding(48, 32, 48, 32);
        messageView.setTextSize(16);
        
        MaterialAlertDialogBuilder builder = themedDialogBuilder(requireContext())
                .setTitle(getString(R.string.up_to_date))
                .setView(messageView)
                .setPositiveButton("OK", null);
        
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(dialogInterface -> setDialogButtonColors(dialog));
        dialog.show();
    }

    private void showErrorDialog(String message) {
        // Check if we're using Snow Veil theme
        String currentTheme = SharedPreferencesManager.getInstance(requireContext()).sharedPreferences.getString(com.fadcam.Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);
        boolean isSnowVeilTheme = "Snow Veil".equals(currentTheme);
        
        MaterialAlertDialogBuilder builder = themedDialogBuilder(requireContext())
                .setTitle("Error")
                .setMessage(message)
                .setPositiveButton("OK", null);
        
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(dialogInterface -> setDialogButtonColors(dialog));
        dialog.show();
        
        // If using Snow Veil theme, make sure the message text is black
        if (isSnowVeilTheme && dialog.findViewById(android.R.id.message) instanceof TextView) {
            ((TextView) dialog.findViewById(android.R.id.message)).setTextColor(Color.BLACK);
        }
    }

    private void openUpdateUrl(String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        startActivity(intent);
    }


// Below is the old version of update-checking logic where it downloads the apk too

//    private void showUpdateAvailableDialog(String newVersion, String downloadUrl) {
//        new MaterialAlertDialogBuilder(requireContext())
//                .setTitle("Update Available")
//                .setMessage("A new version (" + newVersion + ") is available. Do you want to download and install it?")
//                .setPositiveButton("Yes", (dialog, which) -> startUpdateDownload(downloadUrl))
//                .setNegativeButton("No", null)
//                .show();
//    }
//
//    private void showUpToDateDialog() {
//        new MaterialAlertDialogBuilder(requireContext())
//                .setTitle("Up to Date")
//                .setMessage("You are already using the latest version.")
//                .setPositiveButton("OK", null)
//                .show();
//    }
//
//    private void showErrorDialog(String message) {
//        new MaterialAlertDialogBuilder(requireContext())
//                .setTitle("Error")
//                .setMessage(message)
//                .setPositiveButton("OK", null)
//                .show();
//    }
//
//    private void startUpdateDownload(String downloadUrl) {
//        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(downloadUrl))
//                .setTitle("FadCam Update")
//                .setDescription("Downloading the latest version of FadCam")
//                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
//                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "FadCam_update.apk")
//                .setAllowedOverMetered(true)
//                .setAllowedOverRoaming(true);
//
//        DownloadManager downloadManager = (DownloadManager) requireContext().getSystemService(Context.DOWNLOAD_SERVICE);
//        long downloadId = downloadManager.enqueue(request);
//
//        BroadcastReceiver onComplete = new BroadcastReceiver() {
//            @Override
//            public void onReceive(Context context, Intent intent) {
//                if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
//                    long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
//                    if (id == downloadId) {
//                        installUpdate();
//                    }
//                }
//            }
//        };
//
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
//                requireActivity().registerReceiver(onComplete, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_NOT_EXPORTED);
//            }
//        }
//    }
//
//    private void installUpdate() {
//        File file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "FadCam_update.apk");
//        Uri uri;
//
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
//            uri = FileProvider.getUriForFile(requireContext(), requireContext().getPackageName() + ".provider", file);
//        } else {
//            uri = Uri.fromFile(file);
//        }
//
//        Intent intent = new Intent(Intent.ACTION_VIEW);
//        intent.setDataAndType(uri, "application/vnd.android.package-archive");
//        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
//        startActivity(intent);
//    }


    private int resolveThemeColor(int attr) {
        android.util.TypedValue typedValue = new android.util.TypedValue();
        requireContext().getTheme().resolveAttribute(attr, typedValue, true);
        return typedValue.data;
    }

    private MaterialAlertDialogBuilder themedDialogBuilder(Context context) {
        // Get current theme
        SharedPreferencesManager spm = SharedPreferencesManager.getInstance(context);
        String currentTheme = spm.sharedPreferences.getString(com.fadcam.Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);
        
        // Select the appropriate dialog theme based on current app theme
        int dialogTheme;
        if ("Crimson Bloom".equals(currentTheme)) {
            dialogTheme = R.style.ThemeOverlay_FadCam_Red_Dialog;
        } else if ("Faded Night".equals(currentTheme)) {
            dialogTheme = R.style.ThemeOverlay_FadCam_Amoled_MaterialAlertDialog;
        } else if ("Snow Veil".equals(currentTheme)) {
            dialogTheme = R.style.ThemeOverlay_FadCam_SnowVeil_Dialog;
        } else {
            dialogTheme = R.style.ThemeOverlay_FadCam_Dialog;
        }
        
        return new MaterialAlertDialogBuilder(context, dialogTheme);
    }
    
    // Helper method to make dialog buttons white or black based on theme
    private void setDialogButtonColors(AlertDialog dialog) {
        if (dialog == null) return;
        
        // Check if we're using Snow Veil theme
        String currentTheme = SharedPreferencesManager.getInstance(requireContext()).sharedPreferences.getString(com.fadcam.Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);
        boolean isSnowVeilTheme = "Snow Veil".equals(currentTheme);
        
        // Set button colors based on theme
        int buttonColor = isSnowVeilTheme ? Color.BLACK : Color.WHITE;
        
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(buttonColor);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(buttonColor);
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(buttonColor);
    }

    // New method to handle Snow Veil theme UI adjustments
    private void applySnowVeilThemeToUI(View rootView) {
        // Find all icon views and set them to black for better contrast
        tintAllIcons(rootView, Color.BLACK);
        
        // Ensure all text views have proper contrast
        ensureTextContrast(rootView);
        
        // Apply specific adjustments to MaterialCardViews
        applySnowVeilToCards(rootView);
    }
    
    private void applySnowVeilToCards(View rootView) {
        try {
            // Find MaterialCardViews in the fragment
            MaterialCardView fadsecInfoCard = rootView.findViewById(R.id.fadsec_info_card);
            MaterialCardView privacyInfoCard = rootView.findViewById(R.id.privacy_info_card);
            
            // Apply Snow Veil theme styles to cards
            if (fadsecInfoCard != null) {
                fadsecInfoCard.setCardBackgroundColor(Color.WHITE);
                fadsecInfoCard.setStrokeColor(Color.LTGRAY);
                
                // Find text views inside the card and set color
                TextView fadsecInfoText = fadsecInfoCard.findViewById(R.id.fadsec_info_text);
                if (fadsecInfoText != null) {
                    fadsecInfoText.setTextColor(Color.BLACK);
                }
            }
            
            if (privacyInfoCard != null) {
                privacyInfoCard.setCardBackgroundColor(Color.WHITE);
                privacyInfoCard.setStrokeColor(Color.LTGRAY);
                
                // Find views inside the privacy card
                TextView privacyTitle = privacyInfoCard.findViewById(R.id.privacy_info_title);
                TextView privacyContent = privacyInfoCard.findViewById(R.id.privacy_info_content);
                ImageView expandIcon = privacyInfoCard.findViewById(R.id.expand_icon);
                
                if (privacyTitle != null) {
                    privacyTitle.setTextColor(Color.BLACK);
                }
                
                if (privacyContent != null) {
                    privacyContent.setTextColor(Color.BLACK);
                }
                
                if (expandIcon != null) {
                    expandIcon.setColorFilter(Color.BLACK);
                }
            }
            
            // Apply to other UI elements
            MaterialButton sourceCodeButton = rootView.findViewById(R.id.source_code_button);
            MaterialButton donateButton = rootView.findViewById(R.id.donate_button);
            MaterialButton checkUpdatesButton = rootView.findViewById(R.id.check_updates_button);
            
            if (sourceCodeButton != null) {
                sourceCodeButton.setTextColor(Color.WHITE);
                sourceCodeButton.setIconTint(ColorStateList.valueOf(Color.WHITE));
                sourceCodeButton.setStrokeColor(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.gray)));
                sourceCodeButton.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.gray_button_filled)));
            }
            
            if (donateButton != null) {
                donateButton.setTextColor(Color.BLACK);
                donateButton.setIconTint(ColorStateList.valueOf(Color.BLACK));
            }
            
            if (checkUpdatesButton != null) {
                checkUpdatesButton.setTextColor(Color.BLACK);
                checkUpdatesButton.setIconTint(ColorStateList.valueOf(Color.BLACK));
                checkUpdatesButton.setStrokeColor(ColorStateList.valueOf(Color.BLACK));
            }
            
            // ----- Fix Start: Only tint email icon for Snow Veil theme -----
            // Find and apply black tint ONLY to the email icon
            TextView emailText = rootView.findViewById(R.id.email_text);
            if (emailText != null) {
                // For the email icon, use direct approach to set the drawable tint
                Drawable[] emailDrawables = emailText.getCompoundDrawablesRelative();
                if (emailDrawables[0] != null) { // Start drawable
                    Drawable emailIcon = emailDrawables[0].mutate(); // Create a mutable copy
                    emailIcon.setColorFilter(Color.BLACK, android.graphics.PorterDuff.Mode.SRC_IN);
                    emailText.setCompoundDrawablesRelativeWithIntrinsicBounds(emailIcon, null, null, null);
                }
                emailText.setTextColor(Color.BLACK);
            }
            // ----- Fix End: Only tint email icon for Snow Veil theme -----
            
        } catch (Exception e) {
            Log.e("AboutFragment", "Error applying Snow Veil theme to cards: " + e.getMessage());
        }
    }
    
    private void setTextRecursive(ViewGroup viewGroup, int color) {
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            View child = viewGroup.getChildAt(i);
            if (child instanceof TextView) {
                ((TextView) child).setTextColor(color);
            } else if (child instanceof ViewGroup) {
                setTextRecursive((ViewGroup) child, color);
            }
        }
    }
    
    // Helper method to tint all icons in the view hierarchy
    private void tintAllIcons(View view, int color) {
        if (view instanceof ViewGroup) {
            ViewGroup viewGroup = (ViewGroup) view;
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                tintAllIcons(viewGroup.getChildAt(i), color);
            }
        } else if (view instanceof ImageView) {
            // ----- Fix Start: Exclude app logo from black tint -----
            // Skip applying tint to the app icon
            if (view.getId() == R.id.app_icon) {
                return; // Don't apply tint to app logo
            }
            // ----- Fix End: Exclude app logo from black tint -----
            
            ((ImageView) view).setColorFilter(color);
        } else if (view instanceof MaterialButton) {
            MaterialButton button = (MaterialButton) view;
            if (button.getIcon() != null) {
                button.setIconTint(ColorStateList.valueOf(color));
            }
        }
    }
    
    // Helper method to ensure text has proper contrast
    private void ensureTextContrast(View view) {
        if (view instanceof ViewGroup) {
            ViewGroup viewGroup = (ViewGroup) view;
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                ensureTextContrast(viewGroup.getChildAt(i));
            }
        } else if (view instanceof TextView) {
            TextView textView = (TextView) view;
            // Only override if the text is very light (poor contrast against white)
            int currentColor = textView.getCurrentTextColor();
            if (isLightColor(currentColor)) {
                textView.setTextColor(Color.BLACK);
            }
        }
    }
    
    // Helper method to determine if a color is light
    private boolean isLightColor(int color) {
        double darkness = 1 - (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255;
        return darkness < 0.5; // If darkness is less than 0.5, it's a light color
    }
}
