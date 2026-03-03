package com.fadcam.ui;

import android.content.ComponentName;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import com.fadcam.Constants;
import com.fadcam.MainActivity;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.google.android.material.button.MaterialButton; // legacy import (no buttons now)
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * AppearanceSettingsFragment
 * Extracted subset from monolithic SettingsFragment for theme/icon/language.
 * Logic will be migrated incrementally; placeholder layout now.
 */
public class AppearanceSettingsFragment extends Fragment {

    private SharedPreferencesManager sharedPreferencesManager;

    @Nullable
    @Override
    public View onCreateView(
        @NonNull LayoutInflater inflater,
        @Nullable ViewGroup container,
        @Nullable Bundle savedInstanceState
    ) {
        return inflater.inflate(
            R.layout.fragment_settings_appearance,
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
        // -------------- Update Start for this method(onViewCreated)-----------
        sharedPreferencesManager = SharedPreferencesManager.getInstance(
            requireContext()
        );
        setupThemeRow(view);
        setupLanguageRow(view);
        setupAppIconRow(view);
        setupEyeColorRow(view);
        setupLogoRow(view);
        View backBtn = view.findViewById(R.id.back_button);
        if (backBtn != null) {
            backBtn.setOnClickListener(v ->
                OverlayNavUtil.dismiss(getActivity())
            );
        }
        // -------------- Update End for this method(onViewCreated)-----------
    }

    // -------------- Refactor Start: theme row -----------
    private void setupThemeRow(View view) {
        View row = view.findViewById(R.id.row_theme);
        TextView value = view.findViewById(R.id.value_theme);
        if (row == null || value == null) return;
        String currentTheme =
            sharedPreferencesManager.sharedPreferences.getString(
                Constants.PREF_APP_THEME,
                Constants.DEFAULT_APP_THEME
            );
        value.setText(currentTheme);
        row.setOnClickListener(v -> showThemePicker(value));
    }

    private void showThemePicker(TextView value) {
        final String resultKey = "picker_result_theme";
        getParentFragmentManager().setFragmentResultListener(
                resultKey,
                this,
                (k, b) -> {
                    if (
                        b.containsKey(
                            com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID
                        )
                    ) {
                        String id = b.getString(
                            com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID
                        );
                        if (id != null) {
                            // Persist immediately
                            sharedPreferencesManager.sharedPreferences
                                .edit()
                                .putString(Constants.PREF_APP_THEME, id)
                                .putBoolean(
                                    "reopen_appearance_after_theme",
                                    true
                                )
                                .putBoolean(
                                    "reopen_theme_sheet_after_theme",
                                    true
                                )
                                .apply();
                            // Update visible value text now
                            value.setText(id);
                            // Defer activity recreate until after sheet dismissal animation finishes to preserve
                            // correct checkmark animation & keep user on Appearance screen.
                            View root = getView();
                            if (root != null) {
                                root.postDelayed(
                                    () -> {
                                        if (!isAdded()) return; // fragment no longer attached
                                        if (
                                            getActivity() instanceof
                                            MainActivity
                                        ) {
                                            ((MainActivity) requireActivity()).applyThemeFromSettings(
                                                id
                                            );
                                        }
                                    },
                                    260
                                ); // picker uses 160ms dismiss delay; add buffer
                            }
                        }
                    }
                }
            );
        java.util.ArrayList<com.fadcam.ui.picker.OptionItem> items =
            new java.util.ArrayList<>();
        items.add(
            new com.fadcam.ui.picker.OptionItem(
                "Crimson Bloom",
                getString(R.string.theme_red),
                null,
                ContextCompat.getColor(
                    requireContext(),
                    R.color.red_theme_primary
                )
            )
        );
        items.add(
            new com.fadcam.ui.picker.OptionItem(
                "Midnight Dusk",
                "Midnight Dusk",
                null,
                ContextCompat.getColor(requireContext(), R.color.gray)
            )
        );
        items.add(
            new com.fadcam.ui.picker.OptionItem(
                "Faded Night",
                "Faded Night",
                null,
                ContextCompat.getColor(requireContext(), R.color.amoled_surface)
            )
        );
        items.add(
            new com.fadcam.ui.picker.OptionItem(
                "Premium Gold",
                getString(R.string.theme_gold),
                null,
                ContextCompat.getColor(
                    requireContext(),
                    R.color.gold_theme_primary
                )
            )
        );
        items.add(
            new com.fadcam.ui.picker.OptionItem(
                "Silent Forest",
                getString(R.string.theme_silentforest),
                null,
                ContextCompat.getColor(
                    requireContext(),
                    R.color.silentforest_theme_primary
                )
            )
        );
        items.add(
            new com.fadcam.ui.picker.OptionItem(
                "Shadow Alloy",
                getString(R.string.theme_shadowalloy),
                null,
                ContextCompat.getColor(
                    requireContext(),
                    R.color.shadowalloy_theme_primary
                )
            )
        );
        items.add(
            new com.fadcam.ui.picker.OptionItem(
                "Pookie Pink",
                getString(R.string.theme_pookiepink),
                null,
                ContextCompat.getColor(
                    requireContext(),
                    R.color.pookiepink_theme_primary
                )
            )
        );
        items.add(
            new com.fadcam.ui.picker.OptionItem(
                "Snow Veil",
                getString(R.string.theme_snowveil),
                null,
                Color.WHITE
            )
        );
        String currentTheme =
            sharedPreferencesManager.sharedPreferences.getString(
                Constants.PREF_APP_THEME,
                Constants.DEFAULT_APP_THEME
            );
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet =
            com.fadcam.ui.picker.PickerBottomSheetFragment.newInstanceGradient(
                getString(R.string.settings_option_theme),
                items,
                currentTheme,
                resultKey,
                getString(R.string.helper_theme_option),
                true
            );
        sheet.show(getParentFragmentManager(), "theme_picker_sheet");
    }

    // -------------- Refactor End: theme row -----------

    // -------------- Refactor Start: language row wrapper -----------
    private void setupLanguageRow(View view) {
        TextView value = view.findViewById(R.id.value_language);
        if (value == null) return;
        setupSettingsLanguageDialog(value);
    }

    // -------------- Refactor End: language row wrapper -----------

    private void saveLanguagePreference(String languageCode) {
        SharedPreferences.Editor editor =
            sharedPreferencesManager.sharedPreferences.edit();
        editor.putString(Constants.LANGUAGE_KEY, languageCode);
        editor.apply();
    }

    private void setupSettingsLanguageDialog(TextView valueView) {
        String[] languages = getResources().getStringArray(
            R.array.languages_array
        );
        String savedLanguageCode = sharedPreferencesManager.getLanguage();
        int selectedIndex = getLanguageIndex(savedLanguageCode);
        valueView.setText(languages[selectedIndex]);
        View row = requireView().findViewById(R.id.row_language);
        if (row == null) return;
        row.setOnClickListener(v -> showLanguagePicker(valueView));
    }

    private void showLanguagePicker(TextView valueView) {
        final String resultKey = "picker_result_language";
        getParentFragmentManager().setFragmentResultListener(
                resultKey,
                this,
                (k, b) -> {
                    if (
                        b.containsKey(
                            com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID
                        )
                    ) {
                        String code = b.getString(
                            com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID
                        );
                        if (code != null) {
                            if (
                                !code.equals(
                                    sharedPreferencesManager.getLanguage()
                                )
                            ) {
                                saveLanguagePreference(code);
                                if (getActivity() instanceof MainActivity) {
                                    ((MainActivity) requireActivity()).applyLanguage(
                                        code
                                    );
                                }
                            }
                            String[] langs = getResources().getStringArray(
                                R.array.languages_array
                            );
                            int idx = getLanguageIndex(code);
                            valueView.setText(langs[idx]);
                        }
                    }
                }
            );
        // map codes to display strings in same order as getLanguageIndex switch
        String[] langDisplay = getResources().getStringArray(
            R.array.languages_array
        );
        java.util.ArrayList<com.fadcam.ui.picker.OptionItem> items =
            new java.util.ArrayList<>();
        String[] codes = {
            "en",
            "zh",
            "ar",
            "fr",
            "tr",
            "ps",
            "in",
            "it",
            "el",
            "de",
            "es",
            "et",
            "ru",
        };
        for (int i = 0; i < codes.length; i++) {
            // Explicit (String) null to select string-based constructor (avoid ambiguity with iconResId)
            items.add(
                new com.fadcam.ui.picker.OptionItem(
                    codes[i],
                    langDisplay[i],
                    (String) null
                )
            );
        }
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet =
            com.fadcam.ui.picker.PickerBottomSheetFragment.newInstanceGradient(
                getString(R.string.setting_language_title),
                items,
                sharedPreferencesManager.getLanguage(),
                resultKey,
                getString(R.string.helper_language_option),
                true
            );
        sheet.show(getParentFragmentManager(), "language_picker_sheet");
    }

    private int getLanguageIndex(String languageCode) {
        switch (languageCode) {
            case "en":
                return 0;
            case "zh":
                return 1;
            case "ar":
                return 2;
            case "fr":
                return 3;
            case "tr":
                return 4;
            case "ps":
                return 5;
            case "in":
                return 6;
            case "it":
                return 7;
            case "el":
                return 8;
            case "de":
                return 9;
            case "es":
                return 10; // Added for Spanish
            case "et":
                return 11; // Added for Estonian
            case "ru":
                return 12; // Added for Russian
            default:
                return 0;
        }
    }

    private String getLanguageCode(int position) {
        switch (position) {
            case 0:
                return "en";
            case 1:
                return "zh";
            case 2:
                return "ar";
            case 3:
                return "fr";
            case 4:
                return "tr";
            case 5:
                return "ps";
            case 6:
                return "in";
            case 7:
                return "it";
            case 8:
                return "el";
            case 9:
                return "de";
            case 10:
                return "es"; // Added for Spanish
            case 11:
                return "et"; // Added for Estonian
            case 12:
                return "ru"; // Added for Russian
            default:
                return "en";
        }
    }


    private void setupAppIconRow(View root) {
        View row = root.findViewById(R.id.row_app_icon);
        TextView value = root.findViewById(R.id.value_app_icon);
        if (row == null || value == null) return;
        String currentIcon =
            sharedPreferencesManager.sharedPreferences.getString(
                Constants.PREF_APP_ICON,
                Constants.APP_ICON_DEFAULT
            );
        setAppIconButtonText(value, currentIcon);
        row.setOnClickListener(v -> showAppIconSelectionDialog(value));
    }

    private void setAppIconButtonText(TextView button, String key) {
        if (key.equals(Constants.APP_ICON_DEFAULT)) button.setText(
            getString(R.string.app_icon_default)
        );
        else if (key.equals(Constants.APP_ICON_MINIMAL)) button.setText(
            getString(R.string.app_icon_minimal)
        );
        else if (key.equals(Constants.APP_ICON_ALTERNATIVE)) button.setText(
            getString(R.string.app_icon_alternative)
        );
        else if (key.equals(Constants.APP_ICON_FADED)) button.setText(
            getString(R.string.app_icon_faded)
        );
        else if (key.equals(Constants.APP_ICON_PALESTINE)) button.setText(
            getString(R.string.app_icon_palestine)
        );
        else if (key.equals(Constants.APP_ICON_PAKISTAN)) button.setText(
            getString(R.string.app_icon_pakistan)
        );
        else if (key.equals(Constants.APP_ICON_FADSECLAB)) button.setText(
            getString(R.string.app_icon_fadseclab)
        );
        else if (key.equals(Constants.APP_ICON_NOOR)) button.setText(
            getString(R.string.app_icon_noor)
        );
        else if (key.equals(Constants.APP_ICON_BAT)) button.setText(
            getString(R.string.app_icon_bat)
        );
        else if (key.equals(Constants.APP_ICON_REDBINARY)) button.setText(
            getString(R.string.app_icon_redbinary)
        );
        else if (key.equals(Constants.APP_ICON_NOTES)) button.setText(
            getString(R.string.app_icon_notes)
        );
        else if (key.equals(Constants.APP_ICON_CALCULATOR)) button.setText(
            getString(R.string.app_icon_calculator)
        );
        else if (key.equals(Constants.APP_ICON_CLOCK)) button.setText(
            getString(R.string.app_icon_clock)
        );
        else if (key.equals(Constants.APP_ICON_WEATHER)) button.setText(
            getString(R.string.app_icon_weather)
        );
        else if (key.equals(Constants.APP_ICON_FOOTBALL)) button.setText(
            getString(R.string.app_icon_football)
        );
        else if (key.equals(Constants.APP_ICON_CAR)) button.setText(
            getString(R.string.app_icon_car)
        );
        else if (key.equals(Constants.APP_ICON_JET)) button.setText(
            getString(R.string.app_icon_jet)
        );
        else if (key.equals(Constants.APP_ICON_BLACK)) button.setText("");
    }

    private void showAppIconSelectionDialog(TextView valueView) {
        final String resultKey = "picker_result_app_icon";
        getParentFragmentManager().setFragmentResultListener(
                resultKey,
                this,
                (k, b) -> {
                    if (
                        b.containsKey(
                            com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID
                        )
                    ) {
                        String id = b.getString(
                            com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID
                        );
                        if (id != null) {
                            updateAppIcon(id);
                            setAppIconButtonText(valueView, id);
                        }
                    }
                }
            );
        java.util.ArrayList<com.fadcam.ui.picker.OptionItem> items =
            new java.util.ArrayList<>();
        // Provide iconResId mapping to mipmap resources (assumes names exist)
        items.add(
            new com.fadcam.ui.picker.OptionItem(
                Constants.APP_ICON_DEFAULT,
                getString(R.string.app_icon_default),
                R.mipmap.ic_launcher
            )
        );
        items.add(
            new com.fadcam.ui.picker.OptionItem(
                Constants.APP_ICON_MINIMAL,
                getString(R.string.app_icon_minimal),
                R.mipmap.ic_launcher_minimal
            )
        );
        items.add(
            new com.fadcam.ui.picker.OptionItem(
                Constants.APP_ICON_ALTERNATIVE,
                getString(R.string.app_icon_alternative),
                R.mipmap.ic_launcher_2
            )
        );
        items.add(
            new com.fadcam.ui.picker.OptionItem(
                Constants.APP_ICON_FADED,
                getString(R.string.app_icon_faded),
                R.mipmap.ic_launcher_faded
            )
        );
        items.add(
            new com.fadcam.ui.picker.OptionItem(
                Constants.APP_ICON_PALESTINE,
                getString(R.string.app_icon_palestine),
                R.mipmap.ic_launcher_palestine
            )
        );
        items.add(
            new com.fadcam.ui.picker.OptionItem(
                Constants.APP_ICON_PAKISTAN,
                getString(R.string.app_icon_pakistan),
                R.mipmap.ic_launcher_pakistan
            )
        );
        items.add(
            new com.fadcam.ui.picker.OptionItem(
                Constants.APP_ICON_FADSECLAB,
                getString(R.string.app_icon_fadseclab),
                R.mipmap.ic_launcher_fadseclab
            )
        );
        items.add(
            new com.fadcam.ui.picker.OptionItem(
                Constants.APP_ICON_NOOR,
                getString(R.string.app_icon_noor),
                R.mipmap.ic_launcher_noor
            )
        );
        items.add(
            new com.fadcam.ui.picker.OptionItem(
                Constants.APP_ICON_BAT,
                getString(R.string.app_icon_bat),
                R.mipmap.ic_launcher_bat
            )
        );
        items.add(
            new com.fadcam.ui.picker.OptionItem(
                Constants.APP_ICON_REDBINARY,
                getString(R.string.app_icon_redbinary),
                R.mipmap.ic_launcher_redbinary
            )
        );
        items.add(
            new com.fadcam.ui.picker.OptionItem(
                Constants.APP_ICON_NOTES,
                getString(R.string.app_icon_notes),
                R.mipmap.ic_launcher_notes
            )
        );
        items.add(
            new com.fadcam.ui.picker.OptionItem(
                Constants.APP_ICON_CALCULATOR,
                getString(R.string.app_icon_calculator),
                R.mipmap.ic_launcher_calculator
            )
        );
        items.add(
            new com.fadcam.ui.picker.OptionItem(
                Constants.APP_ICON_CLOCK,
                getString(R.string.app_icon_clock),
                R.mipmap.ic_launcher_clock
            )
        );
        items.add(
            new com.fadcam.ui.picker.OptionItem(
                Constants.APP_ICON_WEATHER,
                getString(R.string.app_icon_weather),
                R.mipmap.ic_launcher_weather
            )
        );
        items.add(
            new com.fadcam.ui.picker.OptionItem(
                Constants.APP_ICON_FOOTBALL,
                getString(R.string.app_icon_football),
                R.mipmap.ic_launcher_football
            )
        );
        items.add(
            new com.fadcam.ui.picker.OptionItem(
                Constants.APP_ICON_CAR,
                getString(R.string.app_icon_car),
                R.mipmap.ic_launcher_car
            )
        );
        items.add(
            new com.fadcam.ui.picker.OptionItem(
                Constants.APP_ICON_JET,
                getString(R.string.app_icon_jet),
                R.mipmap.ic_launcher_jet
            )
        );
        items.add(
            new com.fadcam.ui.picker.OptionItem(
                Constants.APP_ICON_BLACK,
                "",
                R.mipmap.ic_launcher_black
            )
        );
        String current = sharedPreferencesManager.sharedPreferences.getString(
            Constants.PREF_APP_ICON,
            Constants.APP_ICON_DEFAULT
        );
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet =
            com.fadcam.ui.picker.PickerBottomSheetFragment.newInstanceGrid(
                getString(R.string.setting_app_icon_title),
                items,
                current,
                resultKey,
                getString(R.string.helper_app_icon_option)
            );
        sheet.show(getParentFragmentManager(), "app_icon_picker_sheet");
    }

    private void updateAppIcon(String iconKey) {
        PackageManager pm = requireContext().getPackageManager();
        ComponentName defaultIcon = new ComponentName(
            requireContext(),
            "com.fadcam.SplashActivity"
        );
        ComponentName alternativeIcon = new ComponentName(
            requireContext(),
            "com.fadcam.MainActivity.AlternativeIcon"
        );
        ComponentName fadedIcon = new ComponentName(
            requireContext(),
            "com.fadcam.MainActivity.FadedIcon"
        );
        ComponentName palestineIcon = new ComponentName(
            requireContext(),
            "com.fadcam.MainActivity.PalestineIcon"
        );
        ComponentName pakistanIcon = new ComponentName(
            requireContext(),
            "com.fadcam.MainActivity.PakistanIcon"
        );
        ComponentName fadseclabIcon = new ComponentName(
            requireContext(),
            "com.fadcam.MainActivity.FadSecLabIcon"
        );
        ComponentName noorIcon = new ComponentName(
            requireContext(),
            "com.fadcam.MainActivity.NoorIcon"
        );
        ComponentName batIcon = new ComponentName(
            requireContext(),
            "com.fadcam.MainActivity.BatIcon"
        );
        ComponentName redbinaryIcon = new ComponentName(
            requireContext(),
            "com.fadcam.MainActivity.RedBinaryIcon"
        );
        ComponentName notesIcon = new ComponentName(
            requireContext(),
            "com.fadcam.MainActivity.NotesIcon"
        );
        ComponentName calculatorIcon = new ComponentName(
            requireContext(),
            "com.fadcam.MainActivity.CalculatorIcon"
        );
        ComponentName clockIcon = new ComponentName(
            requireContext(),
            "com.fadcam.MainActivity.ClockIcon"
        );
        ComponentName weatherIcon = new ComponentName(
            requireContext(),
            "com.fadcam.MainActivity.WeatherIcon"
        );
        ComponentName footballIcon = new ComponentName(
            requireContext(),
            "com.fadcam.MainActivity.FootballIcon"
        );
        ComponentName carIcon = new ComponentName(
            requireContext(),
            "com.fadcam.MainActivity.CarIcon"
        );
        ComponentName jetIcon = new ComponentName(
            requireContext(),
            "com.fadcam.MainActivity.JetIcon"
        );
        ComponentName minimalIcon = new ComponentName(
            requireContext(),
            "com.fadcam.MainActivity.MinimalIcon"
        );
        ComponentName blackIcon = new ComponentName(
            requireContext(),
            "com.fadcam.MainActivity.BlackIcon"
        );

        ComponentName[] all = {
            defaultIcon,
            alternativeIcon,
            fadedIcon,
            palestineIcon,
            pakistanIcon,
            fadseclabIcon,
            noorIcon,
            batIcon,
            redbinaryIcon,
            notesIcon,
            calculatorIcon,
            clockIcon,
            weatherIcon,
            footballIcon,
            carIcon,
            jetIcon,
            minimalIcon,
            blackIcon,
        };
        for (ComponentName cn : all) {
            pm.setComponentEnabledSetting(
                cn,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            );
        }

        ComponentName enable = defaultIcon;
        if (Constants.APP_ICON_DEFAULT.equals(iconKey)) enable = defaultIcon;
        else if (Constants.APP_ICON_ALTERNATIVE.equals(iconKey)) enable =
            alternativeIcon;
        else if (Constants.APP_ICON_FADED.equals(iconKey)) enable = fadedIcon;
        else if (Constants.APP_ICON_PALESTINE.equals(iconKey)) enable =
            palestineIcon;
        else if (Constants.APP_ICON_PAKISTAN.equals(iconKey)) enable =
            pakistanIcon;
        else if (Constants.APP_ICON_FADSECLAB.equals(iconKey)) enable =
            fadseclabIcon;
        else if (Constants.APP_ICON_NOOR.equals(iconKey)) enable = noorIcon;
        else if (Constants.APP_ICON_BAT.equals(iconKey)) enable = batIcon;
        else if (Constants.APP_ICON_REDBINARY.equals(iconKey)) enable =
            redbinaryIcon;
        else if (Constants.APP_ICON_NOTES.equals(iconKey)) enable = notesIcon;
        else if (Constants.APP_ICON_CALCULATOR.equals(iconKey)) enable =
            calculatorIcon;
        else if (Constants.APP_ICON_CLOCK.equals(iconKey)) enable = clockIcon;
        else if (Constants.APP_ICON_WEATHER.equals(iconKey)) enable =
            weatherIcon;
        else if (Constants.APP_ICON_FOOTBALL.equals(iconKey)) enable =
            footballIcon;
        else if (Constants.APP_ICON_CAR.equals(iconKey)) enable = carIcon;
        else if (Constants.APP_ICON_JET.equals(iconKey)) enable = jetIcon;
        else if (Constants.APP_ICON_MINIMAL.equals(iconKey)) enable =
            minimalIcon;
        else if (Constants.APP_ICON_BLACK.equals(iconKey)) enable = blackIcon;

        pm.setComponentEnabledSetting(
            enable,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        );
        sharedPreferencesManager.sharedPreferences
            .edit()
            .putString(Constants.PREF_APP_ICON, iconKey)
            .apply();
        // Use existing translated string resource for icon change confirmation
        Toast.makeText(
            requireContext(),
            R.string.app_icon_changed,
            Toast.LENGTH_LONG
        ).show();
        // Refresh shortcuts so pinned/dynamic shortcuts update their icons/labels where supported.
        try {
            com.fadcam.shortcuts.ShortcutsManager sm =
                new com.fadcam.shortcuts.ShortcutsManager(requireContext());
            sm.refreshShortcuts();
        } catch (Exception ignored) {}
    }

    // -------------- Header Logo Style Picker -----------

    private void setupLogoRow(View root) {
        View row = root.findViewById(R.id.row_header_logo);
        TextView value = root.findViewById(R.id.value_header_logo);
        if (row == null || value == null) return;

        String current = sharedPreferencesManager.sharedPreferences.getString(
                Constants.PREF_HEADER_LOGO_STYLE, Constants.HEADER_LOGO_DEFAULT);
        value.setText(current.equals(Constants.HEADER_LOGO_AVATAR)
                ? getString(R.string.header_logo_avatar)
                : getString(R.string.header_logo_default));

        row.setOnClickListener(v -> showLogoPicker(value));
    }

    private void showLogoPicker(TextView value) {
        final String resultKey = "picker_result_header_logo";
        getParentFragmentManager().setFragmentResultListener(
                resultKey, this, (k, b) -> {
                    if (b.containsKey(
                            com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID)) {
                        String id = b.getString(
                                com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
                        if (id != null) {
                            sharedPreferencesManager.sharedPreferences.edit()
                                    .putString(Constants.PREF_HEADER_LOGO_STYLE, id)
                                    .apply();
                            value.setText(id.equals(Constants.HEADER_LOGO_AVATAR)
                                    ? getString(R.string.header_logo_avatar)
                                    : getString(R.string.header_logo_default));
                        }
                    }
                });

        java.util.ArrayList<com.fadcam.ui.picker.OptionItem> items = new java.util.ArrayList<>();
        items.add(new com.fadcam.ui.picker.OptionItem(
                Constants.HEADER_LOGO_DEFAULT,
                getString(R.string.header_logo_default),
                null,
                null,
                R.drawable.menu_icon_unknown));
        items.add(new com.fadcam.ui.picker.OptionItem(
                Constants.HEADER_LOGO_AVATAR,
                getString(R.string.header_logo_avatar),
                null,
                null,
                R.drawable.toggle_on_idle));

        String currentId = sharedPreferencesManager.sharedPreferences.getString(
                Constants.PREF_HEADER_LOGO_STYLE, Constants.HEADER_LOGO_DEFAULT);

        com.fadcam.ui.picker.PickerBottomSheetFragment sheet =
                com.fadcam.ui.picker.PickerBottomSheetFragment.newInstanceGradient(
                        getString(R.string.setting_header_logo_title),
                        items,
                        currentId,
                        resultKey,
                        getString(R.string.setting_header_logo_desc),
                        true);
        // Enable avatar icon rendering; FadCam logo uses default static icon.
        if (sheet.getArguments() != null) {
            sheet.getArguments().putBoolean(
                    com.fadcam.ui.picker.PickerBottomSheetFragment.ARG_BROWSE_MODE, true);
        }
        sheet.show(getParentFragmentManager(), "header_logo_picker_sheet");
    }

    // -------------- Eye Color Picker -----------

    /** Map of eye-color ID → ARGB int.  0 = white / no tint. */
    private static final java.util.LinkedHashMap<String, Integer> EYE_COLORS = new java.util.LinkedHashMap<>();
    static {
        EYE_COLORS.put("white",    0);           // no tint (default)
        EYE_COLORS.put("ruby",     0xFFFF1744);  // Material Red A400
        EYE_COLORS.put("cyan",     0xFF00E5FF);  // Material Cyan A400
        EYE_COLORS.put("violet",   0xFFD500F9);  // Material Purple A400
        EYE_COLORS.put("cobalt",   0xFF2979FF);  // Material Blue A400
        EYE_COLORS.put("amber",    0xFFFFD740);  // Material Amber A200
        EYE_COLORS.put("lime",     0xFF00E676);  // Material Green A400
        EYE_COLORS.put("magenta",  0xFFF50057);  // Material Pink A400
    }

    private void setupEyeColorRow(View root) {
        View row = root.findViewById(R.id.row_eye_color);
        TextView value = root.findViewById(R.id.value_eye_color);
        View swatch = root.findViewById(R.id.eye_color_swatch);
        if (row == null || value == null) return;

        int current = sharedPreferencesManager.sharedPreferences.getInt(
                Constants.PREF_AVATAR_EYE_COLOR, Constants.DEFAULT_AVATAR_EYE_COLOR);
        updateEyeColorDisplay(value, swatch, current);
        row.setOnClickListener(v -> showEyeColorPicker(value, swatch));
    }

    private void updateEyeColorDisplay(TextView value, @Nullable View swatch, int colorInt) {
        // Find friendly name for color.
        String label = "White";
        for (java.util.Map.Entry<String, Integer> e : EYE_COLORS.entrySet()) {
            if (e.getValue() == colorInt) {
                label = capitalize(e.getKey());
                break;
            }
        }
        value.setText(label);
        if (swatch != null) {
            android.graphics.drawable.GradientDrawable bg =
                    (android.graphics.drawable.GradientDrawable) swatch.getBackground().mutate();
            bg.setColor(colorInt == 0 ? 0xFFFFFFFF : colorInt);
            swatch.setBackground(bg);
        }
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void showEyeColorPicker(TextView valueView, @Nullable View swatch) {
        final String resultKey = "picker_result_eye_color";
        getParentFragmentManager().setFragmentResultListener(
                resultKey, this, (k, b) -> {
                    // Browse-mode preview: live-tint sidebar avatar without persisting.
                    if (b.containsKey(
                            com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_PREVIEW_ID)) {
                        String prevId = b.getString(
                                com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_PREVIEW_ID);
                        if (prevId != null && EYE_COLORS.containsKey(prevId)) {
                            int previewColor = EYE_COLORS.get(prevId);
                            applyEyeColorLivePreview(previewColor, valueView, swatch);
                        }
                        return; // don't persist yet
                    }
                    // Final selection (on dismiss).
                    if (b.containsKey(
                            com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID)) {
                        String id = b.getString(
                                com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
                        if (id != null && EYE_COLORS.containsKey(id)) {
                            int color = EYE_COLORS.get(id);
                            sharedPreferencesManager.sharedPreferences.edit()
                                    .putInt(Constants.PREF_AVATAR_EYE_COLOR, color)
                                    .apply();
                            updateEyeColorDisplay(valueView, swatch, color);
                        }
                    }
                });

        java.util.ArrayList<com.fadcam.ui.picker.OptionItem> items = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, Integer> e : EYE_COLORS.entrySet()) {
            int c = e.getValue();
            items.add(new com.fadcam.ui.picker.OptionItem(
                    e.getKey(),
                    capitalize(e.getKey()),
                    null,
                    c == 0 ? 0xFFFFFFFF : c));
        }

        int current = sharedPreferencesManager.sharedPreferences.getInt(
                Constants.PREF_AVATAR_EYE_COLOR, Constants.DEFAULT_AVATAR_EYE_COLOR);
        String currentId = "white";
        for (java.util.Map.Entry<String, Integer> e : EYE_COLORS.entrySet()) {
            if (e.getValue() == current) { currentId = e.getKey(); break; }
        }

        com.fadcam.ui.picker.PickerBottomSheetFragment sheet =
                com.fadcam.ui.picker.PickerBottomSheetFragment.newInstanceGradient(
                        getString(R.string.setting_eye_color_title),
                        items,
                        currentId,
                        resultKey,
                        getString(R.string.setting_eye_color_desc),
                        true);
        // Enable browse mode + avatar swatch rendering.
        if (sheet.getArguments() != null) {
            sheet.getArguments().putBoolean(
                    com.fadcam.ui.picker.PickerBottomSheetFragment.ARG_BROWSE_MODE, true);
            sheet.getArguments().putBoolean(
                    com.fadcam.ui.picker.PickerBottomSheetFragment.ARG_AVATAR_SWATCH, true);
        }
        sheet.show(getParentFragmentManager(), "eye_color_picker_sheet");
    }

    /**
     * Applies the given eye color during browse-mode preview. Updates the settings
     * row swatch so the user sees instant feedback while picking.
     */
    private void applyEyeColorLivePreview(int color, TextView valueView, @Nullable View swatch) {
        updateEyeColorDisplay(valueView, swatch, color);
    }

    private void vibrateTouch() {
        // no-op placeholder (original logic in SettingsFragment). Safe to omit for now.
    }
}
