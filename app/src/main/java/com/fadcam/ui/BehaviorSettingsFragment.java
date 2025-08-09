package com.fadcam.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.MainActivity;
import com.fadcam.ui.OverlayNavUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

/**
 * BehaviorSettingsFragment
 * Hosts general behavior preferences (notification customization relocated from legacy SettingsFragment).
 * NOTE: This currently implements a simplified notification preset dialog; full legacy
 * customization (including custom text editing & preview) will be migrated verbatim later.
 */
public class BehaviorSettingsFragment extends BaseFragment {

    private SharedPreferencesManager prefs;
    private TextView valueNotificationPreset;
    private TextView valueOnboardingState;
    private TextView valueAutoUpdateState;
    private com.google.android.material.materialswitch.MaterialSwitch toggleOnboarding;
    private com.google.android.material.materialswitch.MaterialSwitch toggleAutoUpdate;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings_behavior, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // -------------- Fix Start for this method(onViewCreated)-----------
        prefs = SharedPreferencesManager.getInstance(requireContext());
        valueNotificationPreset = view.findViewById(R.id.value_notification_preset);
        valueOnboardingState = view.findViewById(R.id.value_onboarding_state);
        valueAutoUpdateState = view.findViewById(R.id.value_auto_update_state);
        toggleOnboarding = view.findViewById(R.id.toggle_onboarding);
        toggleAutoUpdate = view.findViewById(R.id.toggle_auto_update);
    View back = view.findViewById(R.id.back_button);
    if (back != null) back.setOnClickListener(v -> OverlayNavUtil.dismiss(requireActivity()));
        View rowNotif = view.findViewById(R.id.row_notification_customization);
        if (rowNotif != null) {
            rowNotif.setOnClickListener(v -> openNotificationCustomizationDialog());
        }
        // -------------- Fix Start for this method(onViewCreated - onboarding & auto update toggles)-----------
        if (toggleOnboarding != null) {
            boolean show = prefs.isShowOnboarding();
            toggleOnboarding.setChecked(show);
            toggleOnboarding.setOnCheckedChangeListener((buttonView, isChecked) -> {
                // If enabling onboarding again, also clear FIRST_INSTALL_CHECKED_KEY so onboarding shows
                if (isChecked) {
                    prefs.sharedPreferences.edit().putBoolean(com.fadcam.Constants.FIRST_INSTALL_CHECKED_KEY, false).apply();
                }
                prefs.setShowOnboarding(isChecked);
                refreshValues();
            });
        }
        if (toggleAutoUpdate != null) {
            boolean enabled = prefs.sharedPreferences.getBoolean("auto_update_check_enabled", true);
            toggleAutoUpdate.setChecked(enabled);
            toggleAutoUpdate.setOnCheckedChangeListener((buttonView, isChecked) -> {
                prefs.sharedPreferences.edit().putBoolean("auto_update_check_enabled", isChecked).apply();
                refreshValues();
            });
        }
        // -------------- Fix Ended for this method(onViewCreated - onboarding & auto update toggles)-----------
        refreshValues();
        // -------------- Fix Ended for this method(onViewCreated)-----------
    }

    // Removed duplicate manual back handling; centralized via OverlayNavUtil

    private void refreshValues() {
        // -------------- Fix Start for this method(refreshValues)-----------
        if (valueNotificationPreset != null) {
            String preset = prefs.getNotificationPreset();
            valueNotificationPreset.setText(mapPresetLabel(preset));
        }
        if (valueOnboardingState != null) {
            valueOnboardingState.setText(prefs.isShowOnboarding() ? "Enabled" : "Disabled");
        }
        if (valueAutoUpdateState != null) {
            boolean enabled = prefs.sharedPreferences.getBoolean("auto_update_check_enabled", true);
            valueAutoUpdateState.setText(enabled ? "Enabled" : "Disabled");
        }
        // -------------- Fix Ended for this method(refreshValues)-----------
    }

    private String mapPresetLabel(String key) {
        if (key == null) return "Default";
        switch (key) {
            case SharedPreferencesManager.NOTIFICATION_PRESET_SYSTEM_UPDATE:
                return "System Update";
            case SharedPreferencesManager.NOTIFICATION_PRESET_DOWNLOADING:
                return "Downloading";
            case SharedPreferencesManager.NOTIFICATION_PRESET_SYNCING:
                return "Syncing";
            case SharedPreferencesManager.NOTIFICATION_PRESET_CUSTOM:
                return "Custom";
            default:
                return "Default";
        }
    }

    private void openNotificationCustomizationDialog() {
        // -------------- Fix Start for this method(openNotificationCustomizationDialog)-----------
        final View customView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_notification_customization, null);
        final AlertDialog dialog = themedDialogBuilder()
                .setTitle(R.string.notification_setup_title)
                .setView(customView)
                .setCancelable(true)
                .create();

        RadioGroup presetGroup = customView.findViewById(R.id.radiogroup_notification_preset);
        RadioButton defaultPreset = customView.findViewById(R.id.radio_preset_default);
        RadioButton systemUpdatePreset = customView.findViewById(R.id.radio_preset_system_update);
        RadioButton downloadingPreset = customView.findViewById(R.id.radio_preset_downloading);
        RadioButton syncingPreset = customView.findViewById(R.id.radio_preset_syncing);
        RadioButton customPreset = customView.findViewById(R.id.radio_preset_custom);

        View customTextLayout = customView.findViewById(R.id.layout_custom_notification);
        TextInputEditText customTitleInput = customView.findViewById(R.id.edit_custom_title);
        TextInputEditText customTextInput = customView.findViewById(R.id.edit_custom_text);
        MaterialSwitch hideStopButtonSwitch = customView.findViewById(R.id.switch_hide_stop_button);

        View notificationPreview = customView.findViewById(R.id.notification_preview_card);
        TextView previewTitle = customView.findViewById(R.id.notification_preview_title);
        TextView previewText = customView.findViewById(R.id.notification_preview_text);
        TextView previewStopButton = customView.findViewById(R.id.notification_preview_stop_button);

        Button saveButton = customView.findViewById(R.id.button_save);
        Button cancelButton = customView.findViewById(R.id.button_cancel);

        String currentPreset = prefs.getNotificationPreset();
        boolean hideStopButton = prefs.isNotificationStopButtonHidden();
        switch (currentPreset) {
            case SharedPreferencesManager.NOTIFICATION_PRESET_SYSTEM_UPDATE:
                systemUpdatePreset.setChecked(true); break;
            case SharedPreferencesManager.NOTIFICATION_PRESET_DOWNLOADING:
                downloadingPreset.setChecked(true); break;
            case SharedPreferencesManager.NOTIFICATION_PRESET_SYNCING:
                syncingPreset.setChecked(true); break;
            case SharedPreferencesManager.NOTIFICATION_PRESET_CUSTOM:
                customPreset.setChecked(true); customTextLayout.setVisibility(View.VISIBLE); break;
            default:
                defaultPreset.setChecked(true); break;
        }

        String customTitle = prefs.getCustomNotificationTitle();
        String customText = prefs.getCustomNotificationText();
        if (customTitle != null) customTitleInput.setText(customTitle);
        if (customText != null) customTextInput.setText(customText);
        hideStopButtonSwitch.setChecked(hideStopButton);

        updateNotificationPreview(previewTitle, previewText, previewStopButton,
                getSelectedPreset(presetGroup),
                customTitleInput.getText() != null ? customTitleInput.getText().toString() : "",
                customTextInput.getText() != null ? customTextInput.getText().toString() : "",
                hideStopButtonSwitch.isChecked());

        presetGroup.setOnCheckedChangeListener((group, checkedId) -> {
            customTextLayout.setVisibility(checkedId == R.id.radio_preset_custom ? View.VISIBLE : View.GONE);
            updateNotificationPreview(previewTitle, previewText, previewStopButton,
                    getSelectedPreset(presetGroup),
                    customTitleInput.getText() != null ? customTitleInput.getText().toString() : "",
                    customTextInput.getText() != null ? customTextInput.getText().toString() : "",
                    hideStopButtonSwitch.isChecked());
        });

        customTitleInput.addTextChangedListener(new SimpleTextWatcher(() -> {
            if (customPreset.isChecked()) {
                updateNotificationPreview(previewTitle, previewText, previewStopButton,
                        SharedPreferencesManager.NOTIFICATION_PRESET_CUSTOM,
                        customTitleInput.getText() != null ? customTitleInput.getText().toString() : "",
                        customTextInput.getText() != null ? customTextInput.getText().toString() : "",
                        hideStopButtonSwitch.isChecked());
            }
        }));
        customTextInput.addTextChangedListener(new SimpleTextWatcher(() -> {
            if (customPreset.isChecked()) {
                updateNotificationPreview(previewTitle, previewText, previewStopButton,
                        SharedPreferencesManager.NOTIFICATION_PRESET_CUSTOM,
                        customTitleInput.getText() != null ? customTitleInput.getText().toString() : "",
                        customTextInput.getText() != null ? customTextInput.getText().toString() : "",
                        hideStopButtonSwitch.isChecked());
            }
        }));

        hideStopButtonSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> updateNotificationPreview(previewTitle, previewText, previewStopButton,
                getSelectedPreset(presetGroup),
                customTitleInput.getText() != null ? customTitleInput.getText().toString() : "",
                customTextInput.getText() != null ? customTextInput.getText().toString() : "",
                isChecked));

        saveButton.setOnClickListener(v -> {
            String selectedPreset = getSelectedPreset(presetGroup);
            prefs.setNotificationPreset(selectedPreset);
            if (SharedPreferencesManager.NOTIFICATION_PRESET_CUSTOM.equals(selectedPreset)) {
                prefs.setCustomNotificationTitle(customTitleInput.getText() != null ? customTitleInput.getText().toString() : "");
                prefs.setCustomNotificationText(customTextInput.getText() != null ? customTextInput.getText().toString() : "");
            }
            prefs.setNotificationStopButtonHidden(hideStopButtonSwitch.isChecked());
            dialog.dismiss();
            Toast.makeText(requireContext(), getString(R.string.notification_settings_saved), Toast.LENGTH_SHORT).show();
            refreshValues();
        });
        cancelButton.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
        setDialogButtonColors(dialog);
        // -------------- Fix Ended for this method(openNotificationCustomizationDialog)-----------
    }

    // -------------- Fix Start for this method(updateNotificationPreview)-----------
    private void updateNotificationPreview(TextView titleView, TextView textView, View stopButton,
                                           String preset, String customTitle, String customText, boolean hideStop) {
        String title; String text;
        switch (preset) {
            case SharedPreferencesManager.NOTIFICATION_PRESET_SYSTEM_UPDATE:
                title = "System Update"; text = "Update in progress"; break;
            case SharedPreferencesManager.NOTIFICATION_PRESET_DOWNLOADING:
                title = "Downloading"; text = "Download in progress"; break;
            case SharedPreferencesManager.NOTIFICATION_PRESET_SYNCING:
                title = "Syncing Data"; text = "Sync in progress"; break;
            case SharedPreferencesManager.NOTIFICATION_PRESET_CUSTOM:
                title = customTitle.isEmpty() ? "Notification" : customTitle;
                text = customText.isEmpty() ? "Process running" : customText; break;
            default:
                title = getString(R.string.notification_video_recording);
                text = getString(R.string.notification_video_recording_progress_description); break;
        }
        titleView.setText(title);
        textView.setText(text);
        stopButton.setVisibility(hideStop ? View.GONE : View.VISIBLE);
    }
    // -------------- Fix Ended for this method(updateNotificationPreview)-----------

    // -------------- Fix Start for this method(getSelectedPreset)-----------
    private String getSelectedPreset(RadioGroup group) {
        int id = group.getCheckedRadioButtonId();
        if (id == R.id.radio_preset_system_update) return SharedPreferencesManager.NOTIFICATION_PRESET_SYSTEM_UPDATE;
        if (id == R.id.radio_preset_downloading) return SharedPreferencesManager.NOTIFICATION_PRESET_DOWNLOADING;
        if (id == R.id.radio_preset_syncing) return SharedPreferencesManager.NOTIFICATION_PRESET_SYNCING;
        if (id == R.id.radio_preset_custom) return SharedPreferencesManager.NOTIFICATION_PRESET_CUSTOM;
        return SharedPreferencesManager.NOTIFICATION_PRESET_DEFAULT;
    }
    // -------------- Fix Ended for this method(getSelectedPreset)-----------

    // -------------- Fix Start for this class(SimpleTextWatcher)-----------
    private static class SimpleTextWatcher implements TextWatcher {
        private final Runnable onChange;
        SimpleTextWatcher(Runnable r){ this.onChange = r; }
        public void beforeTextChanged(CharSequence s,int a,int b,int c){}
        public void onTextChanged(CharSequence s,int a,int b,int c){ onChange.run(); }
        public void afterTextChanged(Editable s){}
    }
    // -------------- Fix Ended for this class(SimpleTextWatcher)-----------

    // -------------- Fix Start for this method(themedDialogBuilder)-----------
    private MaterialAlertDialogBuilder themedDialogBuilder(){
        String theme = prefs.sharedPreferences.getString(com.fadcam.Constants.PREF_APP_THEME, com.fadcam.Constants.DEFAULT_APP_THEME);
        int dialogTheme;
        if ("Crimson Bloom".equals(theme)) dialogTheme = R.style.ThemeOverlay_FadCam_Red_Dialog;
        else if ("Faded Night".equals(theme)) dialogTheme = R.style.ThemeOverlay_FadCam_Amoled_MaterialAlertDialog;
        else if ("Snow Veil".equals(theme)) dialogTheme = R.style.ThemeOverlay_FadCam_SnowVeil_Dialog;
        else dialogTheme = R.style.ThemeOverlay_FadCam_Dialog;
        return new MaterialAlertDialogBuilder(requireContext(), dialogTheme);
    }
    // -------------- Fix Ended for this method(themedDialogBuilder)-----------

    // -------------- Fix Start for this method(setDialogButtonColors)-----------
    private void setDialogButtonColors(AlertDialog dialog){
        if(dialog==null) return;
        String theme = prefs.sharedPreferences.getString(com.fadcam.Constants.PREF_APP_THEME, com.fadcam.Constants.DEFAULT_APP_THEME);
        boolean snow = "Snow Veil".equals(theme);
        int color = snow ? android.graphics.Color.BLACK : android.graphics.Color.WHITE;
        if(dialog.getButton(AlertDialog.BUTTON_POSITIVE)!=null) dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(color);
        if(dialog.getButton(AlertDialog.BUTTON_NEGATIVE)!=null) dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(color);
        if(dialog.getButton(AlertDialog.BUTTON_NEUTRAL)!=null) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(color);
    }
    // -------------- Fix Ended for this method(setDialogButtonColors)-----------
}
