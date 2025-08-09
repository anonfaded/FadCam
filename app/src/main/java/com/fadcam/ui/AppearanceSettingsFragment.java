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
        String[] themeNames = { getString(R.string.theme_red), "Midnight Dusk", "Faded Night",
                getString(R.string.theme_gold), getString(R.string.theme_silentforest),
                getString(R.string.theme_shadowalloy), getString(R.string.theme_pookiepink),
                getString(R.string.theme_snowveil) };
        int[] themeColors = {
                ContextCompat.getColor(requireContext(), R.color.red_theme_primary),
                ContextCompat.getColor(requireContext(), R.color.gray),
                ContextCompat.getColor(requireContext(), R.color.amoled_surface),
                ContextCompat.getColor(requireContext(), R.color.gold_theme_primary),
                ContextCompat.getColor(requireContext(), R.color.silentforest_theme_primary),
                ContextCompat.getColor(requireContext(), R.color.shadowalloy_theme_primary),
                ContextCompat.getColor(requireContext(), R.color.pookiepink_theme_primary),
                ContextCompat.getColor(requireContext(), R.color.snowveil_theme_primary)
        };
        String currentTheme = sharedPreferencesManager.sharedPreferences.getString(Constants.PREF_APP_THEME,
                Constants.DEFAULT_APP_THEME);
        int tempThemeIndex = 1;
        if ("Crimson Bloom".equals(currentTheme)) tempThemeIndex = 0;
        else if ("Faded Night".equals(currentTheme) || "AMOLED".equals(currentTheme) || "Amoled".equals(currentTheme)) tempThemeIndex = 2;
        else if ("Premium Gold".equals(currentTheme)) tempThemeIndex = 3;
        else if ("Silent Forest".equals(currentTheme)) tempThemeIndex = 4;
        else if ("Shadow Alloy".equals(currentTheme)) tempThemeIndex = 5;
        else if ("Pookie Pink".equals(currentTheme)) tempThemeIndex = 6;
        else if ("Snow Veil".equals(currentTheme)) tempThemeIndex = 7;
        final int themeIndex = tempThemeIndex;
    value.setText(themeNames[themeIndex]);
    row.setOnClickListener(v -> {
            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext(), com.google.android.material.R.style.MaterialAlertDialog_Material3);
            builder.setTitle(R.string.settings_option_theme);
            boolean isSnowVeilTheme = "Snow Veil".equals(currentTheme);
            ArrayAdapter<String> adapter = new ArrayAdapter<String>(requireContext(), R.layout.item_theme_option,
                    R.id.theme_name, themeNames) {
                @Override
                public View getView(int position, View convertView, ViewGroup parent) {
                    View vw = super.getView(position, convertView, parent);
                    TextView themeName = vw.findViewById(R.id.theme_name);
                    if(isSnowVeilTheme) themeName.setTextColor(Color.BLACK); else themeName.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white));
                    View colorCircle = vw.findViewById(R.id.theme_color_circle);
                    GradientDrawable drawable = (GradientDrawable) colorCircle.getBackground();
                    if(position==2){
                        drawable.setColor(ContextCompat.getColor(requireContext(), R.color.amoled_surface));
                    } else {
                        drawable.setColor(themeColors[position]);
                    }
                    if(position==themeIndex){
                        GradientDrawable highlightBg = new GradientDrawable();
                        highlightBg.setCornerRadius(8 * getResources().getDisplayMetrics().density);
                        highlightBg.setColor(isSnowVeilTheme ? ContextCompat.getColor(requireContext(), R.color.snowveil_theme_accent) : 0x33FFFFFF);
                        vw.setBackground(highlightBg);
                    } else {
                        vw.setBackground(null);
                    }
                    return vw;
                }
            };
            builder.setSingleChoiceItems(adapter, themeIndex, (dialogInterface, which) -> {
                String newTheme = themeNames[which];
                sharedPreferencesManager.sharedPreferences.edit().putString(Constants.PREF_APP_THEME, newTheme).apply();
                if(getActivity() instanceof MainActivity){
                    ((MainActivity) requireActivity()).applyThemeFromSettings(newTheme);
                }
                value.setText(newTheme);
                dialogInterface.dismiss();
            });
            builder.setNegativeButton(R.string.universal_cancel, null).show();
        });
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
        row.setOnClickListener(v -> {
            String currentTheme = sharedPreferencesManager.sharedPreferences.getString(Constants.PREF_APP_THEME,
                    Constants.DEFAULT_APP_THEME);
            boolean isSnowVeilTheme = "Snow Veil".equals(currentTheme);
            int color = ContextCompat.getColor(requireContext(), isSnowVeilTheme ? android.R.color.black : android.R.color.white);
            ArrayAdapter<String> adapter = new ArrayAdapter<String>(requireContext(), android.R.layout.simple_list_item_single_choice, languages) {
                @Override
                public View getView(int position, View convertView, ViewGroup parent) {
                    View vw = super.getView(position, convertView, parent);
                    TextView text1 = vw.findViewById(android.R.id.text1);
                    if(text1!=null) text1.setTextColor(color);
                    return vw;
                }
            };
            AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext(), com.google.android.material.R.style.MaterialAlertDialog_Material3)
                    .setTitle(R.string.setting_language_title)
                    .setSingleChoiceItems(adapter, selectedIndex, (dialogInterface, which) -> {
                        String newLangCode = getLanguageCode(which);
                        if(!newLangCode.equals(sharedPreferencesManager.getLanguage())){
                            saveLanguagePreference(newLangCode);
                            if(getActivity() instanceof MainActivity){
                                ((MainActivity) requireActivity()).applyLanguage(newLangCode);
                            }
                        }
                        valueView.setText(languages[which]);
                        dialogInterface.dismiss();
                    })
                    .setNegativeButton(R.string.universal_cancel, null)
                    .show();
        });
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
    }

    private void showAppIconSelectionDialog(TextView valueView){
        AppIconGridBottomSheet bottomSheet = new AppIconGridBottomSheet((iconKey, iconName) -> {
            setAppIconButtonText(valueView, iconKey);
            updateAppIcon(iconKey);
            vibrateTouch();
        });
        bottomSheet.show(getParentFragmentManager(), "AppIconGridBottomSheetAppearance");
    }

    private void updateAppIcon(String iconKey) {
        PackageManager pm = requireContext().getPackageManager();
        ComponentName defaultIcon = new ComponentName(requireContext(), "com.fadcam.MainActivity");
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

        ComponentName[] all = {defaultIcon, alternativeIcon, fadedIcon, palestineIcon, pakistanIcon, fadseclabIcon, noorIcon, batIcon, redbinaryIcon, notesIcon, calculatorIcon, clockIcon, weatherIcon, footballIcon, carIcon, jetIcon, minimalIcon};
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
