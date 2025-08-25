package com.fadcam.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.fadcam.R;
import com.fadcam.Constants;
import com.fadcam.MainActivity;
import com.fadcam.SharedPreferencesManager;
import com.google.android.material.button.MaterialButton; // legacy import (no buttons now)
import android.content.pm.PackageManager;
import android.content.ComponentName;
import android.widget.Toast;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import android.content.SharedPreferences;
import android.view.ViewGroup.LayoutParams;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Color;
import android.view.ViewGroup;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.LinearLayout;

/**
 * AppearanceSettingsFragment
 * Extracted subset from monolithic SettingsFragment for theme/icon/language.
 * Logic will be migrated incrementally; placeholder layout now.
 */
public class AppearanceSettingsFragment extends Fragment {

    private SharedPreferencesManager sharedPreferencesManager;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings_appearance, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // -------------- Update Start for this method(onViewCreated)-----------
        sharedPreferencesManager = SharedPreferencesManager.getInstance(requireContext());
        setupThemeRow(view);
        setupLanguageRow(view);
        setupAppIconRow(view);
    View backBtn = view.findViewById(R.id.back_button);
    if(backBtn!=null){ backBtn.setOnClickListener(v -> OverlayNavUtil.dismiss(getActivity())); }
        // -------------- Update End for this method(onViewCreated)-----------
    }

    // -------------- Refactor Start: theme row -----------
    private void setupThemeRow(View view){
        View row = view.findViewById(R.id.row_theme);
        TextView value = view.findViewById(R.id.value_theme);
        if(row==null || value==null) return;
        String currentTheme = sharedPreferencesManager.sharedPreferences.getString(Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);
        value.setText(currentTheme);
        row.setOnClickListener(v -> showThemePicker(value));
    }

    private void showThemePicker(TextView value){
        final String resultKey = "picker_result_theme";
        getParentFragmentManager().setFragmentResultListener(resultKey, this, (k,b)->{
            if(b.containsKey(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID)){
                String id = b.getString(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
                if(id!=null){
                    // -------------- Fix Start for this logic(theme selection defer recreate)-----------
                    // Persist immediately
            sharedPreferencesManager.sharedPreferences.edit()
                .putString(Constants.PREF_APP_THEME, id)
                .putBoolean("reopen_appearance_after_theme", true)
                .putBoolean("reopen_theme_sheet_after_theme", true)
                .apply();
                    // Update visible value text now
                    value.setText(id);
                    // Defer activity recreate until after sheet dismissal animation finishes to preserve
                    // correct checkmark animation & keep user on Appearance screen.
                    View root = getView();
                    if(root!=null){
                        root.postDelayed(() -> {
                            if(!isAdded()) return; // fragment no longer attached
                            if(getActivity() instanceof MainActivity){
                                ((MainActivity) requireActivity()).applyThemeFromSettings(id);
                            }
                        }, 260); // picker uses 160ms dismiss delay; add buffer
                    }
                    // -------------- Fix Ended for this logic(theme selection defer recreate)-----------
                }
            }
        });
        java.util.ArrayList<com.fadcam.ui.picker.OptionItem> items = new java.util.ArrayList<>();
    items.add(new com.fadcam.ui.picker.OptionItem("Crimson Bloom", getString(R.string.theme_red), null, ContextCompat.getColor(requireContext(), R.color.red_theme_primary)));
    items.add(new com.fadcam.ui.picker.OptionItem("Midnight Dusk", "Midnight Dusk", null, ContextCompat.getColor(requireContext(), R.color.gray)));
    items.add(new com.fadcam.ui.picker.OptionItem("Faded Night", "Faded Night", null, ContextCompat.getColor(requireContext(), R.color.amoled_surface)));
    items.add(new com.fadcam.ui.picker.OptionItem("Premium Gold", getString(R.string.theme_gold), null, ContextCompat.getColor(requireContext(), R.color.gold_theme_primary)));
    items.add(new com.fadcam.ui.picker.OptionItem("Silent Forest", getString(R.string.theme_silentforest), null, ContextCompat.getColor(requireContext(), R.color.silentforest_theme_primary)));
    items.add(new com.fadcam.ui.picker.OptionItem("Shadow Alloy", getString(R.string.theme_shadowalloy), null, ContextCompat.getColor(requireContext(), R.color.shadowalloy_theme_primary)));
    items.add(new com.fadcam.ui.picker.OptionItem("Pookie Pink", getString(R.string.theme_pookiepink), null, ContextCompat.getColor(requireContext(), R.color.pookiepink_theme_primary)));
    items.add(new com.fadcam.ui.picker.OptionItem("Snow Veil", getString(R.string.theme_snowveil), null, Color.WHITE));
    String currentTheme = sharedPreferencesManager.sharedPreferences.getString(Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);
    com.fadcam.ui.picker.PickerBottomSheetFragment sheet = com.fadcam.ui.picker.PickerBottomSheetFragment.newInstanceGradient(
        getString(R.string.settings_option_theme), items, currentTheme, resultKey, getString(R.string.helper_theme_option), true);
        sheet.show(getParentFragmentManager(), "theme_picker_sheet");
    }
    // -------------- Refactor End: theme row -----------

    // -------------- Refactor Start: language row wrapper -----------
    private void setupLanguageRow(View view){
        TextView value = view.findViewById(R.id.value_language);
        if(value==null) return;
        setupSettingsLanguageDialog(value);
    }
    // -------------- Refactor End: language row wrapper -----------

    // -------------- Fix Start for language setup(copy)-----------
    private void saveLanguagePreference(String languageCode) {
        SharedPreferences.Editor editor = sharedPreferencesManager.sharedPreferences.edit();
        editor.putString(Constants.LANGUAGE_KEY, languageCode);
        editor.apply();
    }

    private void setupSettingsLanguageDialog(TextView valueView) {
        String[] languages = getResources().getStringArray(R.array.languages_array);
        String savedLanguageCode = sharedPreferencesManager.getLanguage();
        int selectedIndex = getLanguageIndex(savedLanguageCode);
        valueView.setText(languages[selectedIndex]);
        View row = requireView().findViewById(R.id.row_language);
        if(row==null) return;
        row.setOnClickListener(v -> showLanguagePicker(valueView));
    }

    private void showLanguagePicker(TextView valueView){
        final String resultKey = "picker_result_language";
        getParentFragmentManager().setFragmentResultListener(resultKey, this, (k,b)->{
            if(b.containsKey(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID)){
                String code = b.getString(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
                if(code!=null){
                    if(!code.equals(sharedPreferencesManager.getLanguage())){
                        saveLanguagePreference(code);
                        if(getActivity() instanceof MainActivity){
                            ((MainActivity) requireActivity()).applyLanguage(code);
                        }
                    }
                    String[] langs = getResources().getStringArray(R.array.languages_array);
                    int idx = getLanguageIndex(code);
                    valueView.setText(langs[idx]);
                }
            }
        });
        // map codes to display strings in same order as getLanguageIndex switch
        String[] langDisplay = getResources().getStringArray(R.array.languages_array);
        java.util.ArrayList<com.fadcam.ui.picker.OptionItem> items = new java.util.ArrayList<>();
        String[] codes = {"en","zh","ar","fr","tr","ps","in","it","el","de"};
        for(int i=0;i<codes.length;i++){
            // Explicit (String) null to select string-based constructor (avoid ambiguity with iconResId)
            items.add(new com.fadcam.ui.picker.OptionItem(codes[i], langDisplay[i], (String) null));
        }
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet = com.fadcam.ui.picker.PickerBottomSheetFragment.newInstanceGradient(
                getString(R.string.setting_language_title), items, sharedPreferencesManager.getLanguage(), resultKey, getString(R.string.helper_language_option), true);
        sheet.show(getParentFragmentManager(), "language_picker_sheet");
    }

    private int getLanguageIndex(String languageCode) {
        switch (languageCode) {
            case "en": return 0; case "zh": return 1; case "ar": return 2; case "fr": return 3; case "tr": return 4; case "ps": return 5; case "in": return 6; case "it": return 7; case "el": return 8; case "de": return 9; default: return 0; }
    }
    private String getLanguageCode(int position) {
        switch (position) { case 0: return "en"; case 1: return "zh"; case 2: return "ar"; case 3: return "fr"; case 4: return "tr"; case 5: return "ps"; case 6: return "in"; case 7: return "it"; case 8: return "el"; case 9: return "de"; default: return "en"; }
    }
    // -------------- Fix Ended for language setup(copy)-----------

    // -------------- Fix Start for app icon setup(copy)-----------
    private void setupAppIconRow(View root){
        View row = root.findViewById(R.id.row_app_icon);
        TextView value = root.findViewById(R.id.value_app_icon);
        if(row==null || value==null) return;
        String currentIcon = sharedPreferencesManager.sharedPreferences.getString(Constants.PREF_APP_ICON, Constants.APP_ICON_DEFAULT);
        setAppIconButtonText(value, currentIcon);
        row.setOnClickListener(v -> showAppIconSelectionDialog(value));
    }

    private void setAppIconButtonText(TextView button, String key){
        if (key.equals(Constants.APP_ICON_DEFAULT)) button.setText(getString(R.string.app_icon_default));
        else if (key.equals(Constants.APP_ICON_MINIMAL)) button.setText(getString(R.string.app_icon_minimal));
        else if (key.equals(Constants.APP_ICON_ALTERNATIVE)) button.setText(getString(R.string.app_icon_alternative));
        else if (key.equals(Constants.APP_ICON_FADED)) button.setText(getString(R.string.app_icon_faded));
        else if (key.equals(Constants.APP_ICON_PALESTINE)) button.setText(getString(R.string.app_icon_palestine));
        else if (key.equals(Constants.APP_ICON_PAKISTAN)) button.setText(getString(R.string.app_icon_pakistan));
        else if (key.equals(Constants.APP_ICON_FADSECLAB)) button.setText(getString(R.string.app_icon_fadseclab));
        else if (key.equals(Constants.APP_ICON_NOOR)) button.setText(getString(R.string.app_icon_noor));
        else if (key.equals(Constants.APP_ICON_BAT)) button.setText(getString(R.string.app_icon_bat));
        else if (key.equals(Constants.APP_ICON_REDBINARY)) button.setText(getString(R.string.app_icon_redbinary));
        else if (key.equals(Constants.APP_ICON_NOTES)) button.setText(getString(R.string.app_icon_notes));
        else if (key.equals(Constants.APP_ICON_CALCULATOR)) button.setText(getString(R.string.app_icon_calculator));
        else if (key.equals(Constants.APP_ICON_CLOCK)) button.setText(getString(R.string.app_icon_clock));
        else if (key.equals(Constants.APP_ICON_WEATHER)) button.setText(getString(R.string.app_icon_weather));
        else if (key.equals(Constants.APP_ICON_FOOTBALL)) button.setText(getString(R.string.app_icon_football));
        else if (key.equals(Constants.APP_ICON_CAR)) button.setText(getString(R.string.app_icon_car));
    else if (key.equals(Constants.APP_ICON_JET)) button.setText(getString(R.string.app_icon_jet));
    else if (key.equals(Constants.APP_ICON_BLACK)) button.setText("");
    }

    private void showAppIconSelectionDialog(TextView valueView){
        final String resultKey = "picker_result_app_icon";
        getParentFragmentManager().setFragmentResultListener(resultKey, this, (k,b)->{
            if(b.containsKey(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID)){
                String id = b.getString(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
                if(id!=null){
                    updateAppIcon(id);
                    setAppIconButtonText(valueView, id);
                }
            }
        });
        java.util.ArrayList<com.fadcam.ui.picker.OptionItem> items = new java.util.ArrayList<>();
    // Provide iconResId mapping to mipmap resources (assumes names exist)
    items.add(new com.fadcam.ui.picker.OptionItem(Constants.APP_ICON_DEFAULT, getString(R.string.app_icon_default), R.mipmap.ic_launcher));
    items.add(new com.fadcam.ui.picker.OptionItem(Constants.APP_ICON_MINIMAL, getString(R.string.app_icon_minimal), R.mipmap.ic_launcher_minimal));
    items.add(new com.fadcam.ui.picker.OptionItem(Constants.APP_ICON_ALTERNATIVE, getString(R.string.app_icon_alternative), R.mipmap.ic_launcher_2));
    items.add(new com.fadcam.ui.picker.OptionItem(Constants.APP_ICON_FADED, getString(R.string.app_icon_faded), R.mipmap.ic_launcher_faded));
    items.add(new com.fadcam.ui.picker.OptionItem(Constants.APP_ICON_PALESTINE, getString(R.string.app_icon_palestine), R.mipmap.ic_launcher_palestine));
    items.add(new com.fadcam.ui.picker.OptionItem(Constants.APP_ICON_PAKISTAN, getString(R.string.app_icon_pakistan), R.mipmap.ic_launcher_pakistan));
    items.add(new com.fadcam.ui.picker.OptionItem(Constants.APP_ICON_FADSECLAB, getString(R.string.app_icon_fadseclab), R.mipmap.ic_launcher_fadseclab));
    items.add(new com.fadcam.ui.picker.OptionItem(Constants.APP_ICON_NOOR, getString(R.string.app_icon_noor), R.mipmap.ic_launcher_noor));
    items.add(new com.fadcam.ui.picker.OptionItem(Constants.APP_ICON_BAT, getString(R.string.app_icon_bat), R.mipmap.ic_launcher_bat));
    items.add(new com.fadcam.ui.picker.OptionItem(Constants.APP_ICON_REDBINARY, getString(R.string.app_icon_redbinary), R.mipmap.ic_launcher_redbinary));
    items.add(new com.fadcam.ui.picker.OptionItem(Constants.APP_ICON_NOTES, getString(R.string.app_icon_notes), R.mipmap.ic_launcher_notes));
    items.add(new com.fadcam.ui.picker.OptionItem(Constants.APP_ICON_CALCULATOR, getString(R.string.app_icon_calculator), R.mipmap.ic_launcher_calculator));
    items.add(new com.fadcam.ui.picker.OptionItem(Constants.APP_ICON_CLOCK, getString(R.string.app_icon_clock), R.mipmap.ic_launcher_clock));
    items.add(new com.fadcam.ui.picker.OptionItem(Constants.APP_ICON_WEATHER, getString(R.string.app_icon_weather), R.mipmap.ic_launcher_weather));
    items.add(new com.fadcam.ui.picker.OptionItem(Constants.APP_ICON_FOOTBALL, getString(R.string.app_icon_football), R.mipmap.ic_launcher_football));
    items.add(new com.fadcam.ui.picker.OptionItem(Constants.APP_ICON_CAR, getString(R.string.app_icon_car), R.mipmap.ic_launcher_car));
    items.add(new com.fadcam.ui.picker.OptionItem(Constants.APP_ICON_JET, getString(R.string.app_icon_jet), R.mipmap.ic_launcher_jet));
    items.add(new com.fadcam.ui.picker.OptionItem(Constants.APP_ICON_BLACK, "", R.mipmap.ic_launcher_black));
        String current = sharedPreferencesManager.sharedPreferences.getString(Constants.PREF_APP_ICON, Constants.APP_ICON_DEFAULT);
    com.fadcam.ui.picker.PickerBottomSheetFragment sheet = com.fadcam.ui.picker.PickerBottomSheetFragment.newInstanceGrid(
        getString(R.string.setting_app_icon_title), items, current, resultKey, getString(R.string.helper_app_icon_option));
        sheet.show(getParentFragmentManager(), "app_icon_picker_sheet");
    }

    private void updateAppIcon(String iconKey) {
        PackageManager pm = requireContext().getPackageManager();
        ComponentName defaultIcon = new ComponentName(requireContext(), "com.fadcam.SplashActivity");
        ComponentName alternativeIcon = new ComponentName(requireContext(), "com.fadcam.MainActivity.AlternativeIcon");
        ComponentName fadedIcon = new ComponentName(requireContext(), "com.fadcam.MainActivity.FadedIcon");
        ComponentName palestineIcon = new ComponentName(requireContext(), "com.fadcam.MainActivity.PalestineIcon");
        ComponentName pakistanIcon = new ComponentName(requireContext(), "com.fadcam.MainActivity.PakistanIcon");
        ComponentName fadseclabIcon = new ComponentName(requireContext(), "com.fadcam.MainActivity.FadSecLabIcon");
        ComponentName noorIcon = new ComponentName(requireContext(), "com.fadcam.MainActivity.NoorIcon");
        ComponentName batIcon = new ComponentName(requireContext(), "com.fadcam.MainActivity.BatIcon");
        ComponentName redbinaryIcon = new ComponentName(requireContext(), "com.fadcam.MainActivity.RedBinaryIcon");
        ComponentName notesIcon = new ComponentName(requireContext(), "com.fadcam.MainActivity.NotesIcon");
        ComponentName calculatorIcon = new ComponentName(requireContext(), "com.fadcam.MainActivity.CalculatorIcon");
        ComponentName clockIcon = new ComponentName(requireContext(), "com.fadcam.MainActivity.ClockIcon");
        ComponentName weatherIcon = new ComponentName(requireContext(), "com.fadcam.MainActivity.WeatherIcon");
        ComponentName footballIcon = new ComponentName(requireContext(), "com.fadcam.MainActivity.FootballIcon");
        ComponentName carIcon = new ComponentName(requireContext(), "com.fadcam.MainActivity.CarIcon");
        ComponentName jetIcon = new ComponentName(requireContext(), "com.fadcam.MainActivity.JetIcon");
    ComponentName minimalIcon = new ComponentName(requireContext(), "com.fadcam.MainActivity.MinimalIcon");
    ComponentName blackIcon = new ComponentName(requireContext(), "com.fadcam.MainActivity.BlackIcon");

    ComponentName[] all = {defaultIcon, alternativeIcon, fadedIcon, palestineIcon, pakistanIcon, fadseclabIcon, noorIcon, batIcon, redbinaryIcon, notesIcon, calculatorIcon, clockIcon, weatherIcon, footballIcon, carIcon, jetIcon, minimalIcon, blackIcon};
        for(ComponentName cn: all){
            pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        }

        ComponentName enable = defaultIcon;
        if (Constants.APP_ICON_DEFAULT.equals(iconKey)) enable = defaultIcon;
        else if (Constants.APP_ICON_ALTERNATIVE.equals(iconKey)) enable = alternativeIcon;
        else if (Constants.APP_ICON_FADED.equals(iconKey)) enable = fadedIcon;
        else if (Constants.APP_ICON_PALESTINE.equals(iconKey)) enable = palestineIcon;
        else if (Constants.APP_ICON_PAKISTAN.equals(iconKey)) enable = pakistanIcon;
        else if (Constants.APP_ICON_FADSECLAB.equals(iconKey)) enable = fadseclabIcon;
        else if (Constants.APP_ICON_NOOR.equals(iconKey)) enable = noorIcon;
        else if (Constants.APP_ICON_BAT.equals(iconKey)) enable = batIcon;
        else if (Constants.APP_ICON_REDBINARY.equals(iconKey)) enable = redbinaryIcon;
        else if (Constants.APP_ICON_NOTES.equals(iconKey)) enable = notesIcon;
        else if (Constants.APP_ICON_CALCULATOR.equals(iconKey)) enable = calculatorIcon;
        else if (Constants.APP_ICON_CLOCK.equals(iconKey)) enable = clockIcon;
        else if (Constants.APP_ICON_WEATHER.equals(iconKey)) enable = weatherIcon;
        else if (Constants.APP_ICON_FOOTBALL.equals(iconKey)) enable = footballIcon;
        else if (Constants.APP_ICON_CAR.equals(iconKey)) enable = carIcon;
        else if (Constants.APP_ICON_JET.equals(iconKey)) enable = jetIcon;
    else if (Constants.APP_ICON_MINIMAL.equals(iconKey)) enable = minimalIcon;
    else if (Constants.APP_ICON_BLACK.equals(iconKey)) enable = blackIcon;

        pm.setComponentEnabledSetting(enable, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        sharedPreferencesManager.sharedPreferences.edit().putString(Constants.PREF_APP_ICON, iconKey).apply();
    // Use existing translated string resource for icon change confirmation
    Toast.makeText(requireContext(), R.string.app_icon_changed, Toast.LENGTH_LONG).show();
    }

    private void vibrateTouch(){
        // no-op placeholder (original logic in SettingsFragment). Safe to omit for now.
    }
    // -------------- Fix Ended for app icon setup(copy)-----------
}
