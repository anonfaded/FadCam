package com.fadcam.ui;

import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Html;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.airbnb.lottie.LottieAnimationView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.fadcam.R;
import com.fadcam.utils.ChangelogParser;

import java.util.Random;

public class UpdateAvailableBottomSheet extends BottomSheetDialogFragment {
    private static final String TAG = "UpdateBottomSheet";
    private static final String ARG_VERSION = "version";
    private static final String ARG_CHANGELOG = "changelog";
    private static final String ARG_TAG_URL = "tag_url";
    
    // Store the original variation HTML
    private String originalVariationHtml = "";

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

        // Set up close button
        ImageButton btnCloseSheet = view.findViewById(R.id.btnCloseSheet);
        if (btnCloseSheet != null) {
            btnCloseSheet.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        Log.d(TAG, "Close button clicked, attempting to dismiss");
                        dismissAllowingStateLoss();
                        Log.d(TAG, "Bottom sheet dismissed");
                    } catch (Exception e) {
                        Log.e(TAG, "Error dismissing bottom sheet", e);
                        // Try alternative dismiss methods
                        try {
                            if (getDialog() != null) {
                                getDialog().dismiss();
                            } else {
                                dismiss();
                            }
                        } catch (Exception ex) {
                            Log.e(TAG, "All dismiss methods failed", ex);
                        }
                    }
                }
            });
        }

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
            Random rand = new Random();
            int idx = rand.nextInt(variations.length);
            originalVariationHtml = variations[idx]; // Store the original HTML
            tvUpdateDescription.setText(fromHtml(originalVariationHtml));
            tvUpdateDescription.setTextColor(Color.WHITE);
            
            // Fetch changelog from GitHub
            fetchAndDisplayChangelog(tvUpdateDescription);
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
    
    /**
     * Fetches the changelog from GitHub and displays it in the TextView
     * @param tvUpdateDescription TextView to display the changelog
     */
    private void fetchAndDisplayChangelog(TextView tvUpdateDescription) {
        ChangelogParser.fetchChangelog().thenAccept(changelogHtml -> {
            if (getActivity() == null || !isAdded()) return;
            
            requireActivity().runOnUiThread(() -> {
                if (!changelogHtml.isEmpty()) {
                    // Use the original HTML string we stored earlier
                    String updatedText = originalVariationHtml + 
                        "<br><br><font color='#E43C3C'><b>" + 
                        getString(R.string.changelog_label) + 
                        ":</b></font><br>" + changelogHtml;
                    tvUpdateDescription.setText(fromHtml(updatedText));
                }
            });
        }).exceptionally(e -> {
            Log.e(TAG, "Error fetching changelog", e);
            return null;
        });
    }
    
    /**
     * Helper method to handle HTML formatting with backward compatibility
     * @param html HTML string to format
     * @return Formatted CharSequence
     */
    private CharSequence fromHtml(String html) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY);
        } else {
            return Html.fromHtml(html);
        }
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