package com.fadcam.ui;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.DialogFragment;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.ui.picker.OptionItem;
import com.fadcam.ui.picker.PickerBottomSheetFragment;
import com.google.android.material.sidesheet.SideSheetDialog;
import java.util.ArrayList;

/**
 * HomeSidebarFragment
 * Side overlay with settings-style grouped rows for Home options (tips, etc).
 * Based on RecordsSidebarFragment pattern.
 */
public class HomeSidebarFragment extends DialogFragment {

    private String resultKey = "home_sidebar_result";

    public static HomeSidebarFragment newInstance() {
        return new HomeSidebarFragment();
    }

    public void setResultKey(String key) {
        this.resultKey = key;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        // Create a Material SideSheetDialog to host the sidebar content
        SideSheetDialog dialog = new SideSheetDialog(requireContext());

        // Make window background fully transparent so our gradient shape shows without gray corners
        if (dialog.getWindow() != null) {
            android.view.Window window = dialog.getWindow();
            window.setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(
                    android.graphics.Color.TRANSPARENT
                )
            );
            // Remove decor view padding/insets that can cause gray strips
            android.view.View decor = window.getDecorView();
            if (decor instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) decor).setPadding(0, 0, 0, 0);
                decor.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            }
        }
        return dialog;
    }

    @Nullable
    @Override
    public View onCreateView(
        @NonNull LayoutInflater inflater,
        @Nullable ViewGroup container,
        @Nullable Bundle savedInstanceState
    ) {
        // Inflate the side sheet content (modal side sheet provided by Material components)
        return inflater.inflate(
            R.layout.fragment_home_sidebar,
            container,
            false
        );
    }

    @Override
    public void onViewCreated(
        @NonNull View view,
        @Nullable Bundle savedInstanceState
    ) {
        super.onViewCreated(view, savedInstanceState);

        // Handle close button
        ImageView closeButton = view.findViewById(R.id.home_sidebar_close_btn);
        if (closeButton != null) {
            closeButton.setOnClickListener(v -> dismiss());
        }

        // Tips row
        View tipsRow = view.findViewById(R.id.row_tips);
        if (tipsRow != null) {
            tipsRow.setOnClickListener(v -> {
                openTipsPicker();
                dismiss();
            });
        }

        // Preview control row - bind to existing layout elements and use centralized strings/prefs.
        try {
            final SharedPreferencesManager sp =
                SharedPreferencesManager.getInstance(requireContext());

            View previewRow = view.findViewById(R.id.row_preview_toggle);
            if (previewRow != null) {
                TextView tvTitle = previewRow.findViewById(
                    R.id.tv_preview_toggle_title
                );
                TextView tvSub = previewRow.findViewById(
                    R.id.tv_preview_toggle_sub
                );
                androidx.appcompat.widget.SwitchCompat sw =
                    previewRow.findViewById(R.id.switch_preview_toggle);

                // Title must use centralized string: "Preview Area"
                if (tvTitle != null) {
                    tvTitle.setText(R.string.ui_preview_area);
                }

                if (sw != null) {
                    boolean current = Boolean.TRUE.equals(
                        sp.isPreviewEnabled()
                    );
                    sw.setChecked(current);

                    // Initialize subtitle according to state using app strings
                    if (tvSub != null) {
                        tvSub.setText(
                            current
                                ? getString(R.string.setting_enabled_msg)
                                : getString(R.string.setting_disabled_msg)
                        );
                    }

                    sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
                        // Persist centralized preview flag
                        sp.setPreviewEnabled(isChecked);

                        // Update subtitle to reflect current state (uses strings)
                        if (tvSub != null) {
                            tvSub.setText(
                                isChecked
                                    ? getString(R.string.setting_enabled_msg)
                                    : getString(R.string.setting_disabled_msg)
                            );
                        }

                        // Notify interested fragments/consumers via fragment result (single source of truth)
                        try {
                            Bundle b = new Bundle();
                            b.putBoolean("preview_enabled", isChecked);
                            getParentFragmentManager().setFragmentResult(
                                resultKey,
                                b
                            );
                        } catch (Exception ignored) {}
                    });

                    // Make the whole row toggle the switch for better UX
                    previewRow.setOnClickListener(v ->
                        sw.setChecked(!sw.isChecked())
                    );
                }
            }
        } catch (Exception e) {
            android.util.Log.w(
                "HomeSidebar",
                "Failed to bind preview control",
                e
            );
        }
    }

    @Override
    public void onStart() {
        super.onStart();

        // Set the sheet edge to START (left side) after the view is created
        Dialog dialog = getDialog();
        if (dialog instanceof SideSheetDialog) {
            try {
                ((SideSheetDialog) dialog).setSheetEdge(
                    android.view.Gravity.START
                );
            } catch (Exception ignored) {
                // Fallback if setSheetEdge fails
            }
        }

        // Clear any default container backgrounds from the SideSheet host views to avoid gray edges around rounded corners
        View root = getView();
        if (root != null) {
            View p = (View) root.getParent();
            int guard = 0;
            while (p != null && guard < 5) {
                // climb a few levels safely
                try {
                    if (p.getBackground() != null) {
                        p.setBackgroundColor(
                            android.graphics.Color.TRANSPARENT
                        );
                    }
                } catch (Exception ignored) {}
                if (!(p.getParent() instanceof View)) break;
                p = (View) p.getParent();
                guard++;
            }
        }
    }

    private void openTipsPicker() {
        // Use the new TipsCarouselFragment for better tips display
        TipsCarouselFragment tipsCarousel = TipsCarouselFragment.newInstance();
        tipsCarousel.show(getParentFragmentManager(), "tips_carousel");
    }
}
