package com.fadcam.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.graphics.PorterDuff;
import com.google.android.material.button.MaterialButton;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import com.airbnb.lottie.LottieAnimationView;
import com.fadcam.R;

public class OnboardingHumanFragment extends Fragment {
    private boolean checked1 = false;
    private boolean checked2 = false;
    private MaterialButton continueButton;
    private ImageView icon1, icon2;
    private TextView label1, label2;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.onboarding_human_slide, container, false);

        LinearLayout row1 = v.findViewById(R.id.checkbox_row_1);
        LinearLayout row2 = v.findViewById(R.id.checkbox_row_2);
        icon1 = v.findViewById(R.id.checkbox_icon_1);
        icon2 = v.findViewById(R.id.checkbox_icon_2);
        label1 = v.findViewById(R.id.checkbox_label_1);
        label2 = v.findViewById(R.id.checkbox_label_2);
        continueButton = v.findViewById(R.id.btn_human_continue);
        
        // Find the PalestineRainView and prevent it from intercepting touch events
        View palestineRainView = v.findViewById(R.id.palestineRainView);
        if (palestineRainView != null) {
            palestineRainView.setClickable(false);
            palestineRainView.setFocusable(false);
        }
        
        // Configure the LottieAnimationView
        LottieAnimationView lottieHuman = v.findViewById(R.id.lottieHuman);
        if (lottieHuman != null) {
            lottieHuman.setSpeed(0.8f);
        }

        // Set initial button state
        continueButton.setEnabled(false);
        continueButton.setAlpha(0.6f);

        // Get the redPastel color
        int redPastelColor = ContextCompat.getColor(requireContext(), R.color.redPastel);

        View.OnClickListener update = view -> {
            continueButton.setEnabled(checked1 && checked2);
            continueButton.setAlpha((checked1 && checked2) ? 1f : 0.6f);
        };

        View.OnClickListener toggle1 = view -> {
            checked1 = !checked1;
            // Set the appropriate checkbox image and apply redPastel tint when checked
            icon1.setImageResource(checked1 ? R.drawable.placeholder_checkbox_checked : R.drawable.placeholder_checkbox_outline);
            if (checked1) {
                // Apply redPastel tint to the checked checkbox
                icon1.setColorFilter(redPastelColor, PorterDuff.Mode.SRC_IN);
            } else {
                // Clear any color filter for unchecked state
                icon1.clearColorFilter();
            }
            update.onClick(view);
        };
        
        View.OnClickListener toggle2 = view -> {
            checked2 = !checked2;
            // Set the appropriate checkbox image and apply redPastel tint when checked
            icon2.setImageResource(checked2 ? R.drawable.placeholder_checkbox_checked : R.drawable.placeholder_checkbox_outline);
            if (checked2) {
                // Apply redPastel tint to the checked checkbox
                icon2.setColorFilter(redPastelColor, PorterDuff.Mode.SRC_IN);
            } else {
                // Clear any color filter for unchecked state
                icon2.clearColorFilter();
            }
            update.onClick(view);
        };

        row1.setOnClickListener(toggle1);
        icon1.setOnClickListener(toggle1);
        label1.setOnClickListener(toggle1);
        row2.setOnClickListener(toggle2);
        icon2.setOnClickListener(toggle2);
        label2.setOnClickListener(toggle2);

        continueButton.setOnClickListener(view -> {
            if (getActivity() instanceof OnboardingActivity) {
                ((OnboardingActivity) getActivity()).finishOnboarding();
            } else {
                requireActivity().finish();
            }
        });

        return v;
    }
} 