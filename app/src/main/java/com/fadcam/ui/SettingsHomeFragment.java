package com.fadcam.ui;

import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.fadcam.R;

/**
 * SettingsHomeFragment
 * Lightweight entry point replacing the monolithic SettingsFragment.
 * Shows grouped navigation rows with a mode selector popup to filter
 * settings between FadCam, FadRec (Screen Recording), FadMic, or All.
 */
public class SettingsHomeFragment extends Fragment {

    public enum SettingsMode { ALL, FADCAM, FADREC }

    private SettingsMode currentMode = SettingsMode.ALL;

    // View references for mode-specific groups
    private View dividerAfterVideo;
    private View dividerAfterAudio;
    private View dividerAfterWatermark;

    private View groupVideoQuick;
    private View groupAudioQuick;
    private View groupWatermarkFrame;   // the FrameLayout wrapping watermark row + badge
    private View groupWatermarkBadge;
    private View groupScreenRecording;
    private View dividerBeforeScreenRec;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_settings_home, container, false);

        setupModeSelector(root);
        setupRowHandlers(root);
        cacheGroupReferences(root);

        // Apply initial filter
        applyModeFilter(SettingsMode.ALL, root);
        refreshAppInlineValues(root);
        manageBadgeVisibility(root);

        return root;
    }

    /** Setup the mode selector button that opens a custom popup. */
    private void setupModeSelector(View root) {
        View modeBtn = root.findViewById(R.id.mode_selector_btn);
        if (modeBtn == null) return;

        modeBtn.setOnClickListener(v -> showModePopup(v));
    }

    /** Show the custom mode selector popup anchored below the header button. */
    private void showModePopup(View anchor) {
        View popupView = LayoutInflater.from(requireContext())
                .inflate(R.layout.mode_selector_popup, null);

        PopupWindow popup = new PopupWindow(
                popupView,
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                true
        );
        popup.setBackgroundDrawable(new ColorDrawable(0x00000000));
        popup.setOutsideTouchable(true);
        popup.setElevation(12f);

        // Set check states
        updatePopupChecks(popupView, currentMode);

        // Click handlers
        popupView.findViewById(R.id.mode_all).setOnClickListener(v -> {
            currentMode = SettingsMode.ALL;
            updatePopupChecks(popupView, currentMode);
            animatePopupDismiss(popup, popupView);
            updateModeSelectorUI();
            applyModeFilter(currentMode);
        });
        popupView.findViewById(R.id.mode_fadcam).setOnClickListener(v -> {
            currentMode = SettingsMode.FADCAM;
            updatePopupChecks(popupView, currentMode);
            animatePopupDismiss(popup, popupView);
            updateModeSelectorUI();
            applyModeFilter(currentMode);
        });
        popupView.findViewById(R.id.mode_fadrec).setOnClickListener(v -> {
            currentMode = SettingsMode.FADREC;
            updatePopupChecks(popupView, currentMode);
            animatePopupDismiss(popup, popupView);
            updateModeSelectorUI();
            applyModeFilter(currentMode);
        });

        // Position popup below the anchor button
        int[] anchorPos = new int[2];
        anchor.getLocationOnScreen(anchorPos);
        int x = anchorPos[0] + anchor.getWidth() / 2 - 100; // center the 200dp popup
        int y = anchorPos[1] + anchor.getHeight() + 4;

        popup.showAtLocation(anchor, android.view.Gravity.TOP | android.view.Gravity.START, x, y);

        // Animate popup entrance
        animatePopupEnter(popupView);
    }

    /** Animate the popup entrance with scale + fade */
    private void animatePopupEnter(View popupView) {
        popupView.setAlpha(0f);
        popupView.setScaleX(0.9f);
        popupView.setScaleY(0.9f);
        popupView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(180)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
    }

    /** Animate the popup dismissal with scale + fade */
    private void animatePopupDismiss(PopupWindow popup, View popupView) {
        popupView.animate()
                .alpha(0f)
                .scaleX(0.95f)
                .scaleY(0.95f)
                .setDuration(120)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(() -> popup.dismiss())
                .start();
    }

    private void updatePopupChecks(View popupView, SettingsMode mode) {
        TextView checkAll = popupView.findViewById(R.id.check_all);
        TextView checkFadCam = popupView.findViewById(R.id.check_fadcam);
        TextView checkFadRec = popupView.findViewById(R.id.check_fadrec);

        if (checkAll != null)
            checkAll.setText(mode == SettingsMode.ALL ? "check_circle" : "radio_button_unchecked");
        if (checkFadCam != null)
            checkFadCam.setText(mode == SettingsMode.FADCAM ? "check_circle" : "radio_button_unchecked");
        if (checkFadRec != null)
            checkFadRec.setText(mode == SettingsMode.FADREC ? "check_circle" : "radio_button_unchecked");

        int green = 0xFF4CAF50;
        int grey = 0xFF666666;
        if (checkAll != null) checkAll.setTextColor(mode == SettingsMode.ALL ? green : grey);
        if (checkFadCam != null) checkFadCam.setTextColor(mode == SettingsMode.FADCAM ? green : grey);
        if (checkFadRec != null) checkFadRec.setTextColor(mode == SettingsMode.FADREC ? green : grey);
    }

    /** Update the header button text/icon to reflect current mode. */
    private void updateModeSelectorUI() {
        View root = getView();
        if (root == null) return;

        TextView modeText = root.findViewById(R.id.mode_selector_text);
        ImageView modeIcon = root.findViewById(R.id.mode_selector_icon);

        if (modeText != null) {
            switch (currentMode) {
                case ALL: modeText.setText("All"); break;
                case FADCAM: modeText.setText("FadCam"); break;
                case FADREC: modeText.setText("FadRec"); break;
            }
        }
        if (modeIcon != null) {
            switch (currentMode) {
                case ALL: modeIcon.setImageResource(R.drawable.play_button_rounded); break;
                case FADCAM: modeIcon.setImageResource(R.drawable.ic_vid_cam); break;
                case FADREC: modeIcon.setImageResource(R.drawable.screen_recorder); break;
            }
        }
    }

    /** Filter visibility of settings groups based on selected mode, with smooth animations. */
    void applyModeFilter(SettingsMode mode) {
        applyModeFilter(mode, getView());
    }

    /** Filter with explicit root view — use this from onCreateView. */
    private void applyModeFilter(SettingsMode mode, View rootView) {
        if (rootView == null) return;

        boolean showVideo = (mode == SettingsMode.ALL || mode == SettingsMode.FADCAM);
        boolean showAudio = (mode == SettingsMode.ALL || mode == SettingsMode.FADCAM);
        boolean showWatermark = (mode == SettingsMode.ALL || mode == SettingsMode.FADCAM);
        boolean showScreenRec = (mode == SettingsMode.ALL || mode == SettingsMode.FADREC);

        // Collect all animatable views with their target visibility
        java.util.List<ViewAnim> viewAnims = new java.util.ArrayList<>();
        addViewAnim(viewAnims, groupVideoQuick, showVideo);
        addViewAnim(viewAnims, dividerAfterVideo, showVideo);
        addViewAnim(viewAnims, groupAudioQuick, showAudio);
        addViewAnim(viewAnims, dividerAfterAudio, showAudio);
        addViewAnim(viewAnims, groupWatermarkFrame, showWatermark);
        addViewAnim(viewAnims, groupWatermarkBadge, showWatermark);
        addViewAnim(viewAnims, dividerAfterWatermark, showWatermark);
        addViewAnim(viewAnims, groupScreenRecording, showScreenRec);
        addViewAnim(viewAnims, dividerBeforeScreenRec, showScreenRec);

        // Animate items together: hide all first, then show all
        // This creates a unified transition instead of cascading row-by-row
        final java.util.List<View> toHide = new java.util.ArrayList<>();
        final java.util.List<View> toShow = new java.util.ArrayList<>();

        for (ViewAnim va : viewAnims) {
            if (va.view == null) continue;
            if (va.targetVisible && va.view.getVisibility() == View.VISIBLE) continue;
            if (!va.targetVisible && va.view.getVisibility() == View.GONE) continue;

            if (va.targetVisible) {
                toShow.add(va.view);
            } else {
                toHide.add(va.view);
            }
        }

        // Hide views first — all together with same duration
        for (View v : toHide) {
            v.animate()
                    .alpha(0f)
                    .setDuration(140)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator())
                    .withEndAction(() -> v.setVisibility(View.GONE))
                    .start();
        }

        // Show views after hide animations complete — all fade in together
        int showDelay = 100;
        for (View v : toShow) {
            v.setVisibility(View.VISIBLE);
            v.setAlpha(0f);
            v.animate()
                    .alpha(1f)
                    .setStartDelay(showDelay)
                    .setDuration(180)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        }

        // Remove trailing dividers after animations complete
        int totalDuration = showDelay + 220;
        rootView.postDelayed(this::fixTrailingDividers, totalDuration);
    }

    private static class ViewAnim {
        final View view;
        final boolean targetVisible;
        ViewAnim(View view, boolean targetVisible) {
            this.view = view;
            this.targetVisible = targetVisible;
        }
    }

    private void addViewAnim(java.util.List<ViewAnim> list, View v, boolean visible) {
        if (v != null) list.add(new ViewAnim(v, visible));
    }

    /** Hide any divider that is immediately followed by GONE rows or is the last visible item. */
    private void fixTrailingDividers() {
        fixTrailingDividerInGroup(dividerAfterVideo, groupAudioQuick);
        fixTrailingDividerInGroup(dividerAfterAudio, groupWatermarkFrame);
        fixTrailingDividerInGroup(dividerAfterWatermark, groupScreenRecording);
        fixTrailingDividerInGroup(dividerBeforeScreenRec, groupScreenRecording);
    }

    private void fixTrailingDividerInGroup(View divider, View nextRow) {
        if (divider == null || divider.getVisibility() != View.VISIBLE) return;
        // Hide divider if next row is also GONE
        if (nextRow != null && nextRow.getVisibility() == View.GONE) {
            divider.setVisibility(View.GONE);
        }
    }

    /** Cache references to groups and their dividers for filtering. */
    private void cacheGroupReferences(View root) {
        groupVideoQuick = root.findViewById(R.id.group_video_quick);
        groupAudioQuick = root.findViewById(R.id.group_audio_quick);

        // Watermark is wrapped in FrameLayout — find the parent FrameLayout
        View watermarkRow = root.findViewById(R.id.group_watermark_quick);
        if (watermarkRow != null && watermarkRow.getParent() instanceof ViewGroup) {
            groupWatermarkFrame = (View) watermarkRow.getParent();
        }
        groupWatermarkBadge = root.findViewById(R.id.watermark_new_badge);

        groupScreenRecording = root.findViewById(R.id.group_screen_recording);

        // Find dividers by position: they are View elements between rows inside the card
        LinearLayout card = root.findViewById(R.id.group_card_quick_access);
        if (card != null) {
            int childCount = card.getChildCount();
            for (int i = 0; i < childCount - 1; i++) {
                View child = card.getChildAt(i);
                if (child instanceof View && !(child instanceof LinearLayout) && child.getId() == View.NO_ID) {
                    // This is a divider — identify which one by what comes before/after
                    View prev = i > 0 ? card.getChildAt(i - 1) : null;
                    View next = i + 1 < childCount ? card.getChildAt(i + 1) : null;

                    if (prev instanceof FrameLayout) {
                        // Divider after watermark FrameLayout
                        if (dividerAfterWatermark == null) dividerAfterWatermark = child;
                    } else if (next != null && next.getId() == R.id.group_screen_recording) {
                        dividerBeforeScreenRec = child;
                    } else if (prev instanceof LinearLayout && prev.getId() == R.id.group_video_quick) {
                        dividerAfterVideo = child;
                    } else if (prev instanceof LinearLayout && prev.getId() == R.id.group_audio_quick) {
                        dividerAfterAudio = child;
                    }
                }
            }
        }
    }

    /** Wire click handlers for all settings rows. */
    private void setupRowHandlers(View root) {
        bindRow(root, R.id.group_appearance, () -> openSubFragment(new AppearanceSettingsFragment()));
        bindRow(root, R.id.group_video_quick, () -> openSubFragment(new VideoSettingsFragment()));
        bindRow(root, R.id.group_video_player_settings, () -> openSubFragment(new VideoPlayerSettingsFragment()));
        bindRow(root, R.id.group_audio_quick, () -> openSubFragment(new AudioSettingsFragment()));
        bindRow(root, R.id.group_screen_recording, () -> openSubFragment(new com.fadcam.fadrec.ui.ScreenRecordingSettingsFragment()));
        bindRow(root, R.id.group_storage, () -> openSubFragment(new StorageSettingsFragment()));
        bindRow(root, R.id.group_security, () -> openSubFragment(new SecuritySettingsFragment()));
        bindRow(root, R.id.group_motion_lab, () -> openSubFragment(new MotionLabSettingsFragment()));
        bindRow(root, R.id.group_digital_forensics, () -> openSubFragment(new DigitalForensicsSettingsFragment()));
        bindRow(root, R.id.group_widgets, () -> openSubFragment(new ShortcutsSettingsFragment()));
        bindRow(root, R.id.group_notifications, () -> openSubFragment(new NotificationSettingsFragment()));
        bindRow(root, R.id.group_watermark_quick, () -> openSubFragment(new WatermarkSettingsFragment()));
        bindRow(root, R.id.group_about, () -> openSubFragment(new AboutFragment()));
        bindRow(root, R.id.group_review, () -> launchReview());
        bindRow(root, R.id.group_readme, () -> openReadmeDialog());
    }

    private void bindRow(View root, int id, Runnable action) {
        View v = root.findViewById(id);
        if (v != null) v.setOnClickListener(x -> action.run());
    }

    private void openSubFragment(Fragment fragment) {
        OverlayNavUtil.show(requireActivity(), fragment, fragment.getClass().getSimpleName());
    }

    /** Launch Play Store review intent. */
    private void launchReview() {
        try {
            android.net.Uri uri = android.net.Uri.parse("market://details?id=" + requireContext().getPackageName());
            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW, uri);
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NO_HISTORY |
                    android.content.Intent.FLAG_ACTIVITY_NEW_DOCUMENT |
                    android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            startActivity(intent);
        } catch (Exception e) {
            // Fallback to web
            try {
                android.net.Uri uri = android.net.Uri.parse("https://play.google.com/store/apps/details?id=" + requireContext().getPackageName());
                android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW, uri);
                startActivity(intent);
            } catch (Exception ignore) {}
        }
    }

    /** Open the README dialog. */
    private void openReadmeDialog() {
        new ReadmeBottomSheetFragment().show(getChildFragmentManager(), "readme");
    }

    /** Refresh inline value TextViews in settings rows (e.g. resolution subtitle). */
    private void refreshAppInlineValues(View root) {
        // Subtitles are static for now; can be made dynamic if needed
    }

    /** Show or hide the watermark NEW badge based on whether the feature has been seen. */
    private void manageBadgeVisibility(View root) {
        try {
            TextView watermarkBadge = root.findViewById(R.id.watermark_new_badge);
            if (watermarkBadge != null) {
                android.content.SharedPreferences prefs = requireContext()
                        .getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE);
                boolean hasSeen = prefs.getBoolean("seen_watermark", false);
                watermarkBadge.setVisibility(hasSeen ? View.GONE : View.VISIBLE);
            }
        } catch (Exception ignore) {}
    }
}
