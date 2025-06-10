package com.fadcam.ui;

import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.airbnb.lottie.LottieAnimationView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.fadcam.R;

public class UpdateAvailableBottomSheet extends BottomSheetDialogFragment {
    private static final String ARG_VERSION = "version";
    private static final String ARG_CHANGELOG = "changelog";
    private static final String ARG_TAG_URL = "tag_url";

    public static UpdateAvailableBottomSheet newInstance(String version, String changelog, String tagUrl) {
        UpdateAvailableBottomSheet fragment = new UpdateAvailableBottomSheet();
        Bundle args = new Bundle();
        args.putString(ARG_VERSION, version);
        args.putString(ARG_CHANGELOG, changelog);
        args.putString(ARG_TAG_URL, tagUrl);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.bottomsheet_update_available, container, false);

        // Set icon rain to use unknown_icon3
        UpdateRainView updateRainView = view.findViewById(R.id.updateRainView);
        if (updateRainView != null) {
            updateRainView.setIconResource(R.drawable.unknown_icon3);
            updateRainView.invalidate(); // ensure redraw/animation
        }

        Bundle args = getArguments();
        String version = args != null ? args.getString(ARG_VERSION, "-") : "-";
        String changelog = args != null ? args.getString(ARG_CHANGELOG, "") : "";
        String tagUrl = args != null ? args.getString(ARG_TAG_URL, "https://github.com/anonfaded/FadCam/releases/latest") : "https://github.com/anonfaded/FadCam/releases/latest";

        // Sarcastic message
        TextView tvSarcastic = view.findViewById(R.id.tvSarcasticMessage);
        if (tvSarcastic != null) {
            tvSarcastic.setText(getString(R.string.update_sarcastic_heading));
            tvSarcastic.setTextColor(Color.WHITE);
            tvSarcastic.setAlpha(0f);
            tvSarcastic.animate().alpha(1f).setDuration(900).start();
        }

        // Playful, color-highlighted description with 4 user-selected variations
        TextView tvUpdateDescription = view.findViewById(R.id.tvUpdateDescription);
        if (tvUpdateDescription != null) {
            String currentV = getCurrentAppVersion();
            String latestV = version;
            String[] variations = new String[] {
                // Variation 1
                getString(R.string.update_variation_1, currentV, latestV),
                // Variation 2
                getString(R.string.update_variation_2, currentV, latestV),
                // Variation 3
                getString(R.string.update_variation_3, currentV, latestV),
                // Variation 4
                getString(R.string.update_variation_4, currentV, latestV)
            };
            java.util.Random rand = new java.util.Random();
            int idx = rand.nextInt(variations.length);
            String descHtml = variations[idx] +
                    (changelog.isEmpty() ? "" : ("<br><br><b>" + getString(R.string.changelog_label) + ":</b><br>" + changelog.replace("\n", "<br>")));
            tvUpdateDescription.setText(android.text.Html.fromHtml(descHtml));
            tvUpdateDescription.setTextColor(Color.WHITE);
        }

        // Version row: current version (red), arrow, new version (green)
        TextView tvCurrentVersion = view.findViewById(R.id.tvCurrentVersion);
        TextView tvArrow = view.findViewById(R.id.tvArrow);
        TextView tvNewVersion = view.findViewById(R.id.tvNewVersion);
        String currentVersion = getCurrentAppVersion();
        if (tvCurrentVersion != null) {
            tvCurrentVersion.setText("v" + currentVersion);
            tvCurrentVersion.setTextColor(Color.parseColor("#E43C3C"));
        }
        if (tvArrow != null) {
            tvArrow.setText(getString(R.string.update_version_arrow));
            tvArrow.setTextColor(Color.WHITE);
        }
        if (tvNewVersion != null) {
            tvNewVersion.setText("v" + version);
            tvNewVersion.setTextColor(Color.parseColor("#77DD77"));
        }

        // Use the Lottie animation for update icon
        LottieAnimationView ivUpdateIcon = view.findViewById(R.id.ivUpdateIcon);
        // The animation is auto-played by the XML attributes

        // Update button text
        TextView tvUpdateButtonText = view.findViewById(R.id.tvUpdateButtonText);
        if (tvUpdateButtonText != null) {
            tvUpdateButtonText.setText(getString(R.string.update_visit_github));
        }
        View layoutUpdateButtonRow = view.findViewById(R.id.layoutUpdateButtonRow);
        if (layoutUpdateButtonRow != null) {
            layoutUpdateButtonRow.setOnClickListener(v -> {
                if (tagUrl != null && !tagUrl.isEmpty()) {
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(tagUrl));
                    startActivity(browserIntent);
                }
            });
        }

        return view;
    }

    // Helper to get the current app version for the version row
    private String getCurrentAppVersion() {
        try {
            android.content.pm.PackageManager pm = requireActivity().getPackageManager();
            android.content.pm.PackageInfo pInfo = pm.getPackageInfo(requireActivity().getPackageName(), 0);
            return pInfo.versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            return "-";
        }
    }

    @Override
    public int getTheme() {
        return R.style.CustomBottomSheetDialogTheme;
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        dialog.setOnShowListener(d -> {
            View bottomSheet = ((BottomSheetDialog) dialog).findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                bottomSheet.setBackgroundResource(R.drawable.gradient_background);
            }
        });
        return dialog;
    }
} 