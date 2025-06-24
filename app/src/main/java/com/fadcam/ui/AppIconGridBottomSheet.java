package com.fadcam.ui;

import android.app.Dialog;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fadcam.Constants;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

public class AppIconGridBottomSheet extends BottomSheetDialogFragment {

    private SharedPreferencesManager sharedPreferencesManager;
    private OnIconSelectedListener listener;

    public interface OnIconSelectedListener {
        void onIconSelected(String iconKey, String iconName);
    }

    // Constructor that accepts both SharedPreferencesManager and listener
    public AppIconGridBottomSheet(SharedPreferencesManager sharedPreferencesManager, OnIconSelectedListener listener) {
        this.sharedPreferencesManager = sharedPreferencesManager;
        this.listener = listener;
    }
    
    // Default constructor with just the listener
    public AppIconGridBottomSheet(OnIconSelectedListener listener) {
        this.listener = listener;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.bottomsheet_app_icon_grid, container, false);

        // Initialize shared preferences if needed
        if (sharedPreferencesManager == null) {
            sharedPreferencesManager = SharedPreferencesManager.getInstance(requireContext());
        }

        // Setup the RecyclerView with grid layout
        RecyclerView gridRecyclerView = view.findViewById(R.id.icon_grid_recycler_view);

        // Set up the grid layout manager with more columns for landscape
        int spanCount = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE ? 4 : 3;
        GridLayoutManager layoutManager = new GridLayoutManager(requireContext(), spanCount);
        gridRecyclerView.setLayoutManager(layoutManager);

        // Apply layout animation for items coming from bottom
        LayoutAnimationController animation = AnimationUtils.loadLayoutAnimation(
                requireContext(), R.anim.layout_animation_from_bottom);
        gridRecyclerView.setLayoutAnimation(animation);

        // Get current app icon
        String currentIcon = sharedPreferencesManager.sharedPreferences.getString(Constants.PREF_APP_ICON, Constants.APP_ICON_DEFAULT);

        // Customize the title
        TextView titleTextView = view.findViewById(R.id.bottom_sheet_title);
        titleTextView.setText(R.string.app_icon_dialog_title);

        // Setup icon grid data - rearranged according to user preference
        String[] iconNames = {
            getString(R.string.app_icon_default),          // Legacy first
            getString(R.string.app_icon_minimal),          // Minimal (new)
            getString(R.string.app_icon_alternative),      // $ sign icon
            getString(R.string.app_icon_noor),             // Noor
            getString(R.string.app_icon_redbinary),        // 0xFF0000
            getString(R.string.app_icon_fadseclab),        // FadSecLab
            getString(R.string.app_icon_bat),              // Bat icon
            getString(R.string.app_icon_football),         // Football icon
            getString(R.string.app_icon_car),              // Car icon
            getString(R.string.app_icon_jet),              // JF-17 fighter jet
            getString(R.string.app_icon_palestine),        // Palestine flag
            getString(R.string.app_icon_pakistan),         // Pakistan flag
            getString(R.string.app_icon_faded),            // Faded icon
            getString(R.string.app_icon_clock),            // Utility icons
            getString(R.string.app_icon_weather),          // last
            getString(R.string.app_icon_notes),
            getString(R.string.app_icon_calculator)
        };

        // Icon resources - rearranged to match the names array
        int[] iconResources = {
            R.mipmap.ic_launcher,                // Legacy
            R.mipmap.ic_launcher_minimal,        // Minimal (new)
            R.mipmap.ic_launcher_2,              // Alternative ($ sign)
            R.mipmap.ic_launcher_noor,           // Noor
            R.mipmap.ic_launcher_redbinary,      // 0xFF0000
            R.mipmap.ic_launcher_fadseclab,      // FadSecLab
            R.mipmap.ic_launcher_bat,            // Bat
            R.mipmap.ic_launcher_football,       // Football
            R.mipmap.ic_launcher_car,            // Car
            R.mipmap.ic_launcher_jet,            // JF-17 jet
            R.mipmap.ic_launcher_palestine,      // Palestine
            R.mipmap.ic_launcher_pakistan,       // Pakistan
            R.mipmap.ic_launcher_faded,          // Faded
            R.mipmap.ic_launcher_clock,          // Utility icons
            R.mipmap.ic_launcher_weather,
            R.mipmap.ic_launcher_notes,
            R.mipmap.ic_launcher_calculator
        };

        // Icon keys - rearranged to match the order above
        String[] iconKeys = {
            Constants.APP_ICON_DEFAULT,          // Legacy
            Constants.APP_ICON_MINIMAL,          // Minimal (new)
            Constants.APP_ICON_ALTERNATIVE,      // Alternative ($ sign)
            Constants.APP_ICON_NOOR,             // Noor
            Constants.APP_ICON_REDBINARY,        // 0xFF0000
            Constants.APP_ICON_FADSECLAB,        // FadSecLab
            Constants.APP_ICON_BAT,              // Bat
            Constants.APP_ICON_FOOTBALL,         // Football
            Constants.APP_ICON_CAR,              // Car
            Constants.APP_ICON_JET,              // JF-17 jet
            Constants.APP_ICON_PALESTINE,        // Palestine
            Constants.APP_ICON_PAKISTAN,         // Pakistan
            Constants.APP_ICON_FADED,            // Faded
            Constants.APP_ICON_CLOCK,            // Utility icons
            Constants.APP_ICON_WEATHER,
            Constants.APP_ICON_NOTES,
            Constants.APP_ICON_CALCULATOR
        };

        // Check current theme for text color
        String currentTheme = sharedPreferencesManager.sharedPreferences.getString(
                Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);
        boolean isSnowVeilTheme = "Snow Veil".equals(currentTheme);

        // Create adapter
        AppIconAdapter adapter = new AppIconAdapter(
                requireContext(),
                iconNames,
                iconResources,
                iconKeys,
                currentIcon,
                isSnowVeilTheme,
                (iconKey, position) -> {
                    if (!iconKey.equals(currentIcon)) {
                        // Save the new icon preference
                        sharedPreferencesManager.sharedPreferences.edit()
                                .putString(Constants.PREF_APP_ICON, iconKey)
                                .apply();

                        // Call the listener to update UI outside
                        if (listener != null) {
                            listener.onIconSelected(iconKey, iconNames[position]);
                        }

                        // Show toast to confirm the change
                        Toast.makeText(requireContext(), R.string.app_icon_changed, Toast.LENGTH_SHORT).show();

                        // Dismiss the bottom sheet
                        dismiss();
                    }
                });

        gridRecyclerView.setAdapter(adapter);

        return view;
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