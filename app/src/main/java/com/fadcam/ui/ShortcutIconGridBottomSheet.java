package com.fadcam.ui;

import android.app.Dialog;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fadcam.Constants;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * ShortcutIconGridBottomSheet
 * A lightweight copy of the AppIconGridBottomSheet UI that lists the app's icon set
 * for selecting a shortcut icon. It does NOT modify the app icon setting; it only
 * returns the selected mipmap resource ID via a listener.
 */
public class ShortcutIconGridBottomSheet extends BottomSheetDialogFragment {

    public interface OnShortcutIconSelectedListener {
        void onShortcutIconSelected(int iconResId);
    }

    private OnShortcutIconSelectedListener listener;
    private SharedPreferencesManager spm;

    public ShortcutIconGridBottomSheet(OnShortcutIconSelectedListener listener) {
        this.listener = listener;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.bottomsheet_app_icon_grid, container, false);

        if (spm == null) spm = SharedPreferencesManager.getInstance(requireContext());

        RecyclerView gridRecyclerView = view.findViewById(R.id.icon_grid_recycler_view);
        int spanCount = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE ? 4 : 3;
        GridLayoutManager layoutManager = new GridLayoutManager(requireContext(), spanCount);
        gridRecyclerView.setLayoutManager(layoutManager);
        LayoutAnimationController animation = AnimationUtils.loadLayoutAnimation(requireContext(), R.anim.layout_animation_from_bottom);
        gridRecyclerView.setLayoutAnimation(animation);

        // Title and description for shortcut icon selection
        TextView titleTextView = view.findViewById(R.id.bottom_sheet_title);
        if (titleTextView != null) titleTextView.setText(R.string.shortcuts_choose_icon_title);
        TextView desc = view.findViewById(R.id.tv_icon_description);
        if (desc != null) desc.setText(R.string.shortcuts_choose_icon_desc);

        // We reuse AppIconAdapter but ignore names and radio checks, just handle click
    String[] iconNames = {
                getString(R.string.app_icon_default),
                getString(R.string.app_icon_minimal),
                getString(R.string.app_icon_alternative),
                getString(R.string.app_icon_noor),
                getString(R.string.app_icon_redbinary),
                getString(R.string.app_icon_fadseclab),
                getString(R.string.app_icon_bat),
                getString(R.string.app_icon_football),
                getString(R.string.app_icon_car),
                getString(R.string.app_icon_jet),
                getString(R.string.app_icon_palestine),
                getString(R.string.app_icon_pakistan),
                getString(R.string.app_icon_faded),
                getString(R.string.app_icon_clock),
                getString(R.string.app_icon_weather),
                getString(R.string.app_icon_notes),
        getString(R.string.app_icon_calculator),
        "" // Black icon has no display name
        };

    int[] iconResources = {
                R.mipmap.ic_launcher,
                R.mipmap.ic_launcher_minimal,
                R.mipmap.ic_launcher_2,
                R.mipmap.ic_launcher_noor,
                R.mipmap.ic_launcher_redbinary,
                R.mipmap.ic_launcher_fadseclab,
                R.mipmap.ic_launcher_bat,
                R.mipmap.ic_launcher_football,
                R.mipmap.ic_launcher_car,
                R.mipmap.ic_launcher_jet,
                R.mipmap.ic_launcher_palestine,
                R.mipmap.ic_launcher_pakistan,
                R.mipmap.ic_launcher_faded,
                R.mipmap.ic_launcher_clock,
                R.mipmap.ic_launcher_weather,
                R.mipmap.ic_launcher_notes,
        R.mipmap.ic_launcher_calculator,
        R.mipmap.ic_launcher_black
        };

        // fake key arrays to satisfy the adapter; we'll not use selection state
    String[] iconKeys = {
                Constants.APP_ICON_DEFAULT,
                Constants.APP_ICON_MINIMAL,
                Constants.APP_ICON_ALTERNATIVE,
                Constants.APP_ICON_NOOR,
                Constants.APP_ICON_REDBINARY,
                Constants.APP_ICON_FADSECLAB,
                Constants.APP_ICON_BAT,
                Constants.APP_ICON_FOOTBALL,
                Constants.APP_ICON_CAR,
                Constants.APP_ICON_JET,
                Constants.APP_ICON_PALESTINE,
                Constants.APP_ICON_PAKISTAN,
                Constants.APP_ICON_FADED,
                Constants.APP_ICON_CLOCK,
                Constants.APP_ICON_WEATHER,
                Constants.APP_ICON_NOTES,
        Constants.APP_ICON_CALCULATOR,
        Constants.APP_ICON_BLACK
        };

        boolean isSnowVeilTheme = "Snow Veil".equals(spm.sharedPreferences.getString(Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME));
        String currentIcon = ""; // no selection

        AppIconAdapter adapter = new AppIconAdapter(
                requireContext(),
                iconNames,
                iconResources,
                iconKeys,
                currentIcon,
                isSnowVeilTheme,
                (iconKey, position) -> {
                    if (listener != null) listener.onShortcutIconSelected(iconResources[position]);
                    dismiss();
                }
        );
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
