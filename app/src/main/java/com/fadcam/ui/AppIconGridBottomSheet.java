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

        // Setup icon grid data
        String[] iconNames = {
            getString(R.string.app_icon_default),
            getString(R.string.app_icon_alternative),
            getString(R.string.app_icon_faded),
            getString(R.string.app_icon_palestine),
            getString(R.string.app_icon_pakistan),
            getString(R.string.app_icon_fadseclab),
            getString(R.string.app_icon_noor),
            getString(R.string.app_icon_bat),
            getString(R.string.app_icon_redbinary),
            getString(R.string.app_icon_notes),
            getString(R.string.app_icon_calculator),
            getString(R.string.app_icon_clock),
            getString(R.string.app_icon_weather),
            getString(R.string.app_icon_football),
            getString(R.string.app_icon_car),
            getString(R.string.app_icon_jet)
        };

        // Icon resources
        int[] iconResources = {
            R.mipmap.ic_launcher,
            R.mipmap.ic_launcher_2,
            R.mipmap.ic_launcher_faded,
            R.mipmap.ic_launcher_palestine,
            R.mipmap.ic_launcher_pakistan,
            R.mipmap.ic_launcher_fadseclab,
            R.mipmap.ic_launcher_noor,
            R.mipmap.ic_launcher_bat,
            R.mipmap.ic_launcher_redbinary,
            R.mipmap.ic_launcher_notes,
            R.mipmap.ic_launcher_calculator,
            R.mipmap.ic_launcher_clock,
            R.mipmap.ic_launcher_weather,
            R.mipmap.ic_launcher_football,
            R.mipmap.ic_launcher_car,
            R.mipmap.ic_launcher_jet
        };

        // Icon keys
        String[] iconKeys = {
            Constants.APP_ICON_DEFAULT,
            Constants.APP_ICON_ALTERNATIVE,
            Constants.APP_ICON_FADED,
            Constants.APP_ICON_PALESTINE,
            Constants.APP_ICON_PAKISTAN,
            Constants.APP_ICON_FADSECLAB,
            Constants.APP_ICON_NOOR,
            Constants.APP_ICON_BAT,
            Constants.APP_ICON_REDBINARY,
            Constants.APP_ICON_NOTES,
            Constants.APP_ICON_CALCULATOR,
            Constants.APP_ICON_CLOCK,
            Constants.APP_ICON_WEATHER,
            Constants.APP_ICON_FOOTBALL,
            Constants.APP_ICON_CAR,
            Constants.APP_ICON_JET
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