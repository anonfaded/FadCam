package com.fadcam.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;

/**
 * NotificationSettingsFragment
 * Extracted from BehaviorSettingsFragment. Future work: migrate dialog-based customization
 * to unified bottom sheet pickers & number inputs. Currently placeholder showing preset & action row.
 */
public class NotificationSettingsFragment extends Fragment {

    private SharedPreferencesManager prefs;
    private TextView valuePreset;
    private TextView previewTitle; private TextView previewText; private TextView previewStopButton;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings_notification, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        prefs = SharedPreferencesManager.getInstance(requireContext());
    valuePreset = view.findViewById(R.id.value_notification_preset);
    previewTitle = view.findViewById(R.id.preview_title);
    previewText = view.findViewById(R.id.preview_text);
    previewStopButton = view.findViewById(R.id.preview_stop_button);
        View back = view.findViewById(R.id.back_button);
        if(back!=null){ back.setOnClickListener(v -> OverlayNavUtil.dismiss(requireActivity())); }
    View row = view.findViewById(R.id.row_notification_preset);
    if(row!=null){ row.setOnClickListener(v -> showPresetPicker()); }
    View hideRow = view.findViewById(R.id.row_hide_stop_button);
    if(hideRow!=null){ hideRow.setOnClickListener(v -> showHideStopBottomSheet()); }
        refreshValues();
    }

    private void refreshValues(){
        String preset = prefs.getNotificationPreset();
        if(valuePreset!=null){ valuePreset.setText(mapPresetLabel(preset)); }
        updatePreview(preset);
    TextView hideValue = getView()==null? null : getView().findViewById(R.id.value_hide_stop_button);
    if(hideValue!=null){ hideValue.setText(prefs.isNotificationStopButtonHidden()? getString(R.string.notification_stop_button_hidden) : getString(R.string.notification_stop_button_visible)); }
    }

    private void showPresetPicker(){
        java.util.ArrayList<com.fadcam.ui.picker.OptionItem> items = new java.util.ArrayList<>();
        items.add(new com.fadcam.ui.picker.OptionItem(SharedPreferencesManager.NOTIFICATION_PRESET_DEFAULT, getString(R.string.notification_preset_default)));
        items.add(new com.fadcam.ui.picker.OptionItem(SharedPreferencesManager.NOTIFICATION_PRESET_SYSTEM_UPDATE, getString(R.string.notification_preset_system_update)));
        items.add(new com.fadcam.ui.picker.OptionItem(SharedPreferencesManager.NOTIFICATION_PRESET_DOWNLOADING, getString(R.string.notification_preset_downloading)));
        items.add(new com.fadcam.ui.picker.OptionItem(SharedPreferencesManager.NOTIFICATION_PRESET_SYNCING, getString(R.string.notification_preset_syncing)));
        items.add(new com.fadcam.ui.picker.OptionItem(SharedPreferencesManager.NOTIFICATION_PRESET_CUSTOM, getString(R.string.notification_preset_custom)));
        String current = prefs.getNotificationPreset();
        String resultKey = "notif_preset_result";
        getParentFragmentManager().setFragmentResultListener(resultKey, this, (k,b)->{
            if(b.containsKey(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID)){
                String id = b.getString(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
                if(id!=null){
                    prefs.setNotificationPreset(id);
                    if(SharedPreferencesManager.NOTIFICATION_PRESET_CUSTOM.equals(id)){
                        showCustomEditorSheet();
                    } else {
                        refreshValues();
                    }
                }
            }
        });
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet = com.fadcam.ui.picker.PickerBottomSheetFragment.newInstance(
                getString(R.string.notification_style_row_title), items, current, resultKey, getString(R.string.notification_preset_helper)
        );
        sheet.show(getParentFragmentManager(), "notif_preset_sheet");
    }

    private void showCustomEditorSheet(){
        com.google.android.material.bottomsheet.BottomSheetDialog dialog = new com.google.android.material.bottomsheet.BottomSheetDialog(requireContext(), com.google.android.material.R.style.ThemeOverlay_Material3_BottomSheetDialog);
        View content = LayoutInflater.from(requireContext()).inflate(R.layout.bottomsheet_notification_custom, null);
        dialog.setContentView(content);
        android.widget.EditText inputTitle = content.findViewById(R.id.input_custom_title);
        android.widget.EditText inputText = content.findViewById(R.id.input_custom_text);
        String existingTitle = prefs.getCustomNotificationTitle();
        String existingText = prefs.getCustomNotificationText();
        if(existingTitle!=null) inputTitle.setText(existingTitle);
        if(existingText!=null) inputText.setText(existingText);
        android.text.TextWatcher watcher = new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,int a,int b,int c){}
            public void onTextChanged(CharSequence s,int a,int b,int c){
                prefs.setCustomNotificationTitle(inputTitle.getText().toString());
                prefs.setCustomNotificationText(inputText.getText().toString());
                updatePreview(SharedPreferencesManager.NOTIFICATION_PRESET_CUSTOM);
            }
            public void afterTextChanged(android.text.Editable s){}
        };
        inputTitle.addTextChangedListener(watcher);
        inputText.addTextChangedListener(watcher);
        View ok = content.findViewById(R.id.button_custom_ok);
        if(ok!=null){ ok.setOnClickListener(v-> dialog.dismiss()); }
        dialog.setOnDismissListener(di -> {
            refreshValues();
            Toast.makeText(requireContext(), getString(R.string.notification_settings_saved), Toast.LENGTH_SHORT).show();
        });
        dialog.show();
    }

    private void updateCustomPreviewViews(TextView pTitle, TextView pText, TextView pStop, String title, String text, boolean hideStop){
        if(title==null || title.isEmpty()) title = getString(R.string.notification_title_custom_default);
        if(text==null || text.isEmpty()) text = getString(R.string.notification_text_custom_default);
        pTitle.setText(title);
        pText.setText(text);
        pStop.setVisibility(hideStop? View.GONE: View.VISIBLE);
    }

    private void updatePreview(String preset){
        if(previewTitle==null || previewText==null || previewStopButton==null) return;
        boolean hideStop = prefs.isNotificationStopButtonHidden();
        String title; String text;
        switch (preset){
            case SharedPreferencesManager.NOTIFICATION_PRESET_SYSTEM_UPDATE:
                title = getString(R.string.notification_title_system_update);
                text = getString(R.string.notification_text_system_update_progress); break;
            case SharedPreferencesManager.NOTIFICATION_PRESET_DOWNLOADING:
                title = getString(R.string.notification_title_downloading);
                text = getString(R.string.notification_text_downloading_progress); break;
            case SharedPreferencesManager.NOTIFICATION_PRESET_SYNCING:
                title = getString(R.string.notification_title_syncing);
                text = getString(R.string.notification_text_syncing_progress); break;
            case SharedPreferencesManager.NOTIFICATION_PRESET_CUSTOM:
                String ct = prefs.getCustomNotificationTitle();
                String cx = prefs.getCustomNotificationText();
                title = (ct==null || ct.isEmpty()) ? getString(R.string.notification_title_custom_default) : ct;
                text = (cx==null || cx.isEmpty()) ? getString(R.string.notification_text_custom_default) : cx; break;
            default:
                title = getString(R.string.notification_video_recording);
                text = getString(R.string.notification_video_recording_progress_description); break;
        }
        previewTitle.setText(title);
        previewText.setText(text);
        previewStopButton.setVisibility(hideStop? View.GONE: View.VISIBLE);
    }

    private void showHideStopBottomSheet(){
        boolean hidden = prefs.isNotificationStopButtonHidden();
        java.util.ArrayList<com.fadcam.ui.picker.OptionItem> items = new java.util.ArrayList<>(); // none, switch only
        final String resultKey = "picker_result_hide_stop";
        getParentFragmentManager().setFragmentResultListener(resultKey, this, (k,b)->{
            if(b.containsKey(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SWITCH_STATE)){
                boolean state = b.getBoolean(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SWITCH_STATE);
                prefs.setNotificationStopButtonHidden(state);
                updatePreview(prefs.getNotificationPreset());
                refreshValues();
            }
        });
    com.fadcam.ui.picker.PickerBottomSheetFragment sheet = com.fadcam.ui.picker.PickerBottomSheetFragment.newInstanceWithSwitch(
        getString(R.string.notification_hide_stop_button), items, null, resultKey,
        getString(R.string.notification_hide_stop_button_description),
        getString(R.string.notification_hide_stop_button), hidden);
        sheet.show(getParentFragmentManager(), "hide_stop_button_picker");
    }

    private String mapPresetLabel(String key){
        if(key==null) return getString(R.string.notification_preset_default);
        switch (key){
            case SharedPreferencesManager.NOTIFICATION_PRESET_SYSTEM_UPDATE: return getString(R.string.notification_preset_system_update);
            case SharedPreferencesManager.NOTIFICATION_PRESET_DOWNLOADING: return getString(R.string.notification_preset_downloading);
            case SharedPreferencesManager.NOTIFICATION_PRESET_SYNCING: return getString(R.string.notification_preset_syncing);
            case SharedPreferencesManager.NOTIFICATION_PRESET_CUSTOM: return getString(R.string.notification_preset_custom);
            default: return getString(R.string.notification_preset_default);
        }
    }
}
