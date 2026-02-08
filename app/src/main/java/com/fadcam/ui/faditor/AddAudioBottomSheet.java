package com.fadcam.ui.faditor;

import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import com.fadcam.R;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * Bottom sheet for adding audio to the timeline.
 *
 * <p>Options:</p>
 * <ul>
 *   <li><b>Extract from current video</b> — extracts the audio track of the
 *       currently selected video clip.</li>
 *   <li><b>Select audio file</b> — open file picker for audio files.</li>
 *   <li><b>Extract from another video</b> — open file picker for a video,
 *       then extract its audio.</li>
 * </ul>
 */
public class AddAudioBottomSheet extends BottomSheetDialogFragment {

    /** Callback for the selected action. */
    public interface Callback {
        void onExtractFromCurrentVideo();
        void onSelectAudioFile();
        void onExtractFromOtherVideo();
    }

    @Nullable
    private Callback callback;

    public static AddAudioBottomSheet newInstance() {
        return new AddAudioBottomSheet();
    }

    public void setCallback(@Nullable Callback callback) {
        this.callback = callback;
    }

    @Override
    public int getTheme() {
        return R.style.CustomBottomSheetDialogTheme;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.picker_bottom_sheet_dark_gradient_bg);
        int pad = dp(16);
        root.setPadding(pad, dp(12), pad, pad);

        // Title
        TextView title = new TextView(requireContext());
        title.setText(R.string.faditor_add_audio_title);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(18f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setPadding(dp(4), dp(4), 0, dp(12));
        root.addView(title);

        // Option 1: Extract from current video
        root.addView(createRow(
                "hearing",
                getString(R.string.faditor_audio_extract_current),
                getString(R.string.faditor_audio_extract_current_desc),
                v -> {
                    dismiss();
                    if (callback != null) callback.onExtractFromCurrentVideo();
                }
        ));

        // Option 2: Select audio file
        root.addView(createRow(
                "audio_file",
                getString(R.string.faditor_audio_select_file),
                getString(R.string.faditor_audio_select_file_desc),
                v -> {
                    dismiss();
                    if (callback != null) callback.onSelectAudioFile();
                }
        ));

        // Option 3: Extract from another video
        root.addView(createRow(
                "video_file",
                getString(R.string.faditor_audio_extract_other),
                getString(R.string.faditor_audio_extract_other_desc),
                v -> {
                    dismiss();
                    if (callback != null) callback.onExtractFromOtherVideo();
                }
        ));

        return root;
    }

    @NonNull
    private View createRow(@NonNull String iconLigature,
                           @NonNull String titleText,
                           @NonNull String subtitleText,
                           @NonNull View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.settings_home_row_bg);
        row.setPadding(dp(14), dp(12), dp(12), dp(12));
        row.setOnClickListener(listener);

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = dp(6);
        row.setLayoutParams(rowLp);

        // Icon
        TextView icon = new TextView(requireContext());
        icon.setTypeface(ResourcesCompat.getFont(requireContext(), R.font.materialicons));
        icon.setText(iconLigature);
        icon.setTextSize(24f);
        icon.setTextColor(0xFFCCCCCC);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(32), dp(32));
        iconLp.setMarginEnd(dp(14));
        icon.setLayoutParams(iconLp);
        icon.setGravity(Gravity.CENTER);
        row.addView(icon);

        // Text column
        LinearLayout textCol = new LinearLayout(requireContext());
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView titleView = new TextView(requireContext());
        titleView.setText(titleText);
        titleView.setTextColor(0xFFFFFFFF);
        titleView.setTextSize(15f);
        titleView.setTypeface(titleView.getTypeface(), android.graphics.Typeface.BOLD);
        textCol.addView(titleView);

        TextView subtitle = new TextView(requireContext());
        subtitle.setText(subtitleText);
        subtitle.setTextColor(0xFF999999);
        subtitle.setTextSize(12f);
        textCol.addView(subtitle);

        row.addView(textCol);

        // Arrow
        TextView arrow = new TextView(requireContext());
        arrow.setTypeface(ResourcesCompat.getFont(requireContext(), R.font.materialicons));
        arrow.setText("chevron_right");
        arrow.setTextSize(18f);
        arrow.setTextColor(0xFF666666);
        row.addView(arrow);

        return row;
    }

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
