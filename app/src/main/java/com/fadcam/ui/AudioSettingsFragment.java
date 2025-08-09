package com.fadcam.ui;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.fadcam.MainActivity;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * AudioSettingsFragment
 * Modular extraction of audio related settings from legacy SettingsFragment.
 * Preserves EXACT logic & preference keys. UI presented as iOS-style rows.
 */
public class AudioSettingsFragment extends Fragment {

    private static final String TAG = "AudioSettingsFragment";

    private SharedPreferencesManager prefs;

    // Row value TextViews
    private TextView valueRecordAudio;
    private TextView valueInputSource;
    private TextView valueNoiseSuppression;
    private TextView valueAdvancedSummary;

    // Row containers for enabling/disabling when record audio off
    private View rowInputSource;
    private View rowAdvanced;
    private View rowNoiseSuppression;

    // State mirrors (legacy variables)
    private final List<AudioDeviceInfo> availableInputMics = new ArrayList<>();
    private final List<String> availableMicLabels = new ArrayList<>();
    private AudioDeviceInfo selectedMic = null; // Null = phone mic / no external
    private boolean headphonesNoMicDetected = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings_audio, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // -------------- Fix Start for this method(onViewCreated)-----------
        prefs = SharedPreferencesManager.getInstance(requireContext());
        bindViews(view);
        bindRowHandlers(view);
        refreshMicList();
        refreshAllValues();
    View back = view.findViewById(R.id.back_button);
    if(back!=null){ back.setOnClickListener(v -> com.fadcam.ui.OverlayNavUtil.dismiss(requireActivity())); }
        // Removed redundant back handling; central navigation handles this globally.
        // -------------- Fix Ended for this method(onViewCreated)-----------
    }

    // Removed handleBack(): obsolete after centralized overlay/back implementation.

    private void bindViews(View root){
        valueRecordAudio = root.findViewById(R.id.value_record_audio);
        valueInputSource = root.findViewById(R.id.value_audio_input_source);
        valueNoiseSuppression = root.findViewById(R.id.value_noise_suppression);
        valueAdvancedSummary = root.findViewById(R.id.value_audio_advanced);
        rowInputSource = root.findViewById(R.id.row_audio_input_source);
    rowAdvanced = root.findViewById(R.id.row_audio_advanced);
    rowNoiseSuppression = root.findViewById(R.id.row_noise_suppression);
    }

    private void bindRowHandlers(View root){
    root.findViewById(R.id.row_record_audio).setOnClickListener(v -> showRecordAudioBottomSheet());
    rowInputSource.setOnClickListener(v -> showMicSelectionBottomSheet());
    root.findViewById(R.id.row_noise_suppression).setOnClickListener(v -> showNoiseSuppressionBottomSheet());
        rowAdvanced.setOnClickListener(v -> showAudioAdvancedBottomSheet());
    }

    private void refreshAllValues(){
        // -------------- Fix Start for this method(refreshAllValues)-----------
        boolean rec = prefs.isRecordAudioEnabled();
    valueRecordAudio.setText(rec? "Enabled" : "Disabled");

        updateAudioInputSourceStatusUI();

        boolean ns = prefs.isNoiseSuppressionEnabled();
        if(valueNoiseSuppression!=null){
            valueNoiseSuppression.setText(ns? "Enabled" : "Disabled");
        }

        // Advanced summary (bitrate + sampling)
        int br = prefs.getAudioBitrate();
        int sr = prefs.getAudioSamplingRate();
        valueAdvancedSummary.setText(String.format(Locale.getDefault(), "%d kbps @ %d Hz", br, sr));
    // Enable/disable dependent rows (mic & advanced) when recording disabled
    updateDependentRowStates(rec);
        // -------------- Fix Ended for this method(refreshAllValues)-----------
    }

    private void toggleRecordAudio(){
        // -------------- Fix Start for this method(toggleRecordAudio)-----------
        boolean current = prefs.isRecordAudioEnabled();
        prefs.setRecordAudioEnabled(!current);
        refreshAllValues();
        // -------------- Fix Ended for this method(toggleRecordAudio)-----------
    }

    private void toggleNoiseSuppression(){
        // -------------- Fix Start for this method(toggleNoiseSuppression)-----------
        boolean cur = prefs.isNoiseSuppressionEnabled();
        prefs.setNoiseSuppressionEnabled(!cur);
        refreshAllValues();
        // -------------- Fix Ended for this method(toggleNoiseSuppression)-----------
    }

    private void showRecordAudioBottomSheet(){
        // -------------- Fix Start for this method(showRecordAudioBottomSheet)-----------
        boolean enabled = prefs.isRecordAudioEnabled();
        ArrayList<com.fadcam.ui.picker.OptionItem> items = new ArrayList<>(); // No nested items now
        final String resultKey = "picker_result_record_audio";
        getParentFragmentManager().setFragmentResultListener(resultKey, this, (k,b)->{
            if(b.containsKey(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SWITCH_STATE)){
                boolean state = b.getBoolean(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SWITCH_STATE);
                prefs.setRecordAudioEnabled(state);
                refreshAllValues();
            }
        });
        String helper = getString(R.string.helper_record_audio);
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet = com.fadcam.ui.picker.PickerBottomSheetFragment.newInstanceWithSwitch(
                getString(R.string.row_record_audio_title), items, null, resultKey, helper,
                getString(R.string.row_record_audio_title), enabled);
        sheet.show(getParentFragmentManager(), "record_audio_picker");
        // -------------- Fix Ended for this method(showRecordAudioBottomSheet)-----------
    }

    private void showNoiseSuppressionBottomSheet(){
        // -------------- Fix Start for this method(showNoiseSuppressionBottomSheet)-----------
        boolean enabled = prefs.isNoiseSuppressionEnabled();
        ArrayList<com.fadcam.ui.picker.OptionItem> items = new ArrayList<>();
        final String resultKey = "picker_result_noise_suppression";
        getParentFragmentManager().setFragmentResultListener(resultKey, this, (k,b)->{
            if(b.containsKey(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SWITCH_STATE)){
                boolean state = b.getBoolean(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SWITCH_STATE);
                prefs.setNoiseSuppressionEnabled(state);
                refreshAllValues();
            }
        });
    String helper = getString(R.string.helper_noise_suppression);
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet = com.fadcam.ui.picker.PickerBottomSheetFragment.newInstanceWithSwitch(
                getString(R.string.dialog_audio_noise_suppression_label), items, null, resultKey, helper,
                getString(R.string.dialog_audio_noise_suppression_label), enabled);
        sheet.show(getParentFragmentManager(), "noise_suppression_picker");
        // -------------- Fix Ended for this method(showNoiseSuppressionBottomSheet)-----------
    }

    // --- Legacy audio input source logic (copied & adapted) ---
    private void refreshMicList(){
        // -------------- Fix Start for this method(refreshMicList)-----------
        availableInputMics.clear();
        availableMicLabels.clear();
        headphonesNoMicDetected = false;
        Context ctx = getContext(); if(ctx==null) return;
        AudioManager audioManager = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        try{
            AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);
            if(devices!=null){
                for(AudioDeviceInfo device: devices){
                    if(device==null) continue;
                    int type = device.getType();
                    switch(type){
                        case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                        case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                        case AudioDeviceInfo.TYPE_USB_DEVICE:
                        case AudioDeviceInfo.TYPE_USB_HEADSET:
                        case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                        case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                        case AudioDeviceInfo.TYPE_BLE_HEADSET:
                        case AudioDeviceInfo.TYPE_BLE_BROADCAST:
                        case AudioDeviceInfo.TYPE_BLE_SPEAKER:
                            // Acceptable external candidates
                            if(device.getType()==AudioDeviceInfo.TYPE_WIRED_HEADPHONES){
                                // Wired headphones w/o mic
                                headphonesNoMicDetected = true;
                                availableInputMics.add(null);
                                availableMicLabels.add(getString(R.string.audio_input_source_headphones_no_mic));
                            } else {
                                availableInputMics.add(device);
                                availableMicLabels.add(getAudioDeviceTypeString(type));
                            }
                            break;
                        default:
                            break; // ignore others
                    }
                }
            }
        }catch(Exception e){ Log.e(TAG, "Error enumerating audio devices", e); }
        // Always add Phone Mic as first option
        availableMicLabels.add(0, getString(R.string.audio_input_source_phone));
        availableInputMics.add(0, null); // Represents phone mic
        selectedMic = null; // selection derived from preference later if needed
        // -------------- Fix Ended for this method(refreshMicList)-----------
    }

    private String getAudioDeviceTypeString(int type){
        // -------------- Fix Start for this method(getAudioDeviceTypeString)-----------
        switch(type){
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "Wired Headset";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return "Wired Headphones";
            case AudioDeviceInfo.TYPE_USB_DEVICE: return "USB";
            case AudioDeviceInfo.TYPE_USB_HEADSET: return "USB Headset";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "BT (SCO)";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "BT (A2DP)";
            case AudioDeviceInfo.TYPE_BLE_HEADSET: return "BLE Headset";
            case AudioDeviceInfo.TYPE_BLE_BROADCAST: return "BLE Broadcast";
            case AudioDeviceInfo.TYPE_BLE_SPEAKER: return "BLE Speaker";
            case AudioDeviceInfo.TYPE_BUILTIN_MIC: return "Built-in Mic";
        }
        return "Device";
        // -------------- Fix Ended for this method(getAudioDeviceTypeString)-----------
    }

    private void showMicSelectionBottomSheet(){
        // -------------- Fix Start for this method(showMicSelectionBottomSheet)-----------
        if(getContext()==null) return;
        refreshMicList();
        ArrayList<com.fadcam.ui.picker.OptionItem> items = new ArrayList<>();
        // Build list
        for(int i=0;i<availableMicLabels.size();i++){
            String label = availableMicLabels.get(i);
            String id = label; // use label as id for now (legacy logic based on label match)
            items.add(new com.fadcam.ui.picker.OptionItem(id, label));
        }
        final String resultKey = "picker_result_audio_mic";
        getParentFragmentManager().setFragmentResultListener(resultKey, this, (k,b)->{
            String sel = b.getString(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if(sel==null) return;
            // Match legacy behavior by comparing label text
            int index = availableMicLabels.indexOf(sel);
            if(index>=0){
                if(sel.equals(getString(R.string.audio_input_source_phone))){
                    prefs.setAudioInputSource(SharedPreferencesManager.AUDIO_INPUT_SOURCE_PHONE);
                    selectedMic = null;
                } else if(sel.equals(getString(R.string.audio_input_source_headphones_no_mic))){
                    prefs.setAudioInputSource(SharedPreferencesManager.AUDIO_INPUT_SOURCE_PHONE);
                    selectedMic = null;
                } else {
                    prefs.setAudioInputSource(SharedPreferencesManager.AUDIO_INPUT_SOURCE_WIRED);
                    selectedMic = availableInputMics.get(index);
                }
                updateAudioInputSourceStatusUI();
                refreshAllValues();
            }
        });
        String helper = getString(R.string.setting_audio_input_source_title);
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet = com.fadcam.ui.picker.PickerBottomSheetFragment.newInstance(
                getString(R.string.setting_audio_input_source_title), items, null, resultKey, helper);
        sheet.show(getParentFragmentManager(), "audio_mic_picker");
        // -------------- Fix Ended for this method(showMicSelectionBottomSheet)-----------
    }

    private void updateAudioInputSourceStatusUI(){
        // -------------- Fix Start for this method(updateAudioInputSourceStatusUI)-----------
        if(valueInputSource==null) return;
        String pref = prefs.getAudioInputSource();
        String status;
        if(SharedPreferencesManager.AUDIO_INPUT_SOURCE_WIRED.equals(pref)){
            if(selectedMic!=null){
                status = getString(R.string.setting_audio_input_source_status_wired) + ": " + selectedMic.getProductName();
            } else {
                status = getString(R.string.setting_audio_input_source_status_wired);
            }
        } else { // phone mic
            if(headphonesNoMicDetected){
                status = getString(R.string.setting_audio_input_source_status_default)+" (No-mic headphones)";
            } else {
                status = getString(R.string.setting_audio_input_source_status_default);
            }
        }
        valueInputSource.setText(status);
        Log.i(TAG, "Audio input source status updated. Selected: " + selectedMic);
        // -------------- Fix Ended for this method(updateAudioInputSourceStatusUI)-----------
    }

    private void showAudioAdvancedBottomSheet(){
        // -------------- Fix Start for this method(showAudioAdvancedBottomSheet)-----------
        ArrayList<com.fadcam.ui.picker.OptionItem> items = new ArrayList<>();
        items.add(new com.fadcam.ui.picker.OptionItem("bitrate", getString(R.string.dialog_audio_bitrate_label)));
        items.add(new com.fadcam.ui.picker.OptionItem("sampling", getString(R.string.dialog_audio_sampling_rate_label)));
        final String resultKey = "picker_result_audio_advanced";
        getParentFragmentManager().setFragmentResultListener(resultKey, this, (k,b)->{
            String sel = b.getString(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if(sel==null) return;
            switch(sel){
                case "bitrate":
                    showBitrateInputSheet();
                    break;
                case "sampling":
                    showSamplingRateInputSheet();
                    break;
            }
        });
        String helper = getString(R.string.dialog_audio_settings_summary);
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet = com.fadcam.ui.picker.PickerBottomSheetFragment.newInstance(
                getString(R.string.dialog_audio_settings_title), items, null, resultKey, helper);
        sheet.show(getParentFragmentManager(), "audio_advanced_picker");
        // -------------- Fix Ended for this method(showAudioAdvancedBottomSheet)-----------
    }

    private void showBitrateInputSheet(){
        // -------------- Fix Start for this method(showBitrateInputSheet)-----------
        final String rk = "num_result_audio_bitrate";
        getParentFragmentManager().setFragmentResultListener(rk, this, (k,b)->{
            if(b.containsKey(com.fadcam.ui.picker.NumberInputBottomSheetFragment.RESULT_NUMBER)){
                int br = b.getInt(com.fadcam.ui.picker.NumberInputBottomSheetFragment.RESULT_NUMBER);
                prefs.setAudioBitrate(br);
                refreshAllValues();
            }
        });
    // Legacy valid range 64000 - 384000 bps (AAC). Threshold hints around common recommendations.
    String lowMsg = getString(R.string.helper_audio_bitrate_low_hint);
    String highMsg = getString(R.string.helper_audio_bitrate_high_hint);
    // Build sheet with reset & description (including recommended value marker)
    com.fadcam.ui.picker.NumberInputBottomSheetFragment sheet = com.fadcam.ui.picker.NumberInputBottomSheetFragment.newInstance(
        getString(R.string.dialog_audio_bitrate_label), 64000, 384000, prefs.getAudioBitrate(), getString(R.string.helper_audio_bitrate_detail) + " " + getString(R.string.label_recommended) + ": 192000", 128000, 320000, lowMsg, highMsg, rk);
    // Add extra args for reset
    if(sheet.getArguments()!=null){
        sheet.getArguments().putBoolean(com.fadcam.ui.picker.NumberInputBottomSheetFragment.ARG_SHOW_RESET, true);
        sheet.getArguments().putInt(com.fadcam.ui.picker.NumberInputBottomSheetFragment.ARG_DEFAULT_VALUE, 192000);
    sheet.getArguments().putString(com.fadcam.ui.picker.NumberInputBottomSheetFragment.ARG_DESCRIPTION, getString(R.string.helper_audio_bitrate_detail) + "\n" + getString(R.string.audio_bitrate_recommended_line));
    }
        sheet.show(getParentFragmentManager(), "audio_bitrate_input");
        // -------------- Fix Ended for this method(showBitrateInputSheet)-----------
    }

    private void showSamplingRateInputSheet(){
        // -------------- Fix Start for this method(showSamplingRateInputSheet)-----------
        final String rk = "picker_result_sampling_rate";
        getParentFragmentManager().setFragmentResultListener(rk, this, (k,b)->{
            String sel = b.getString(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if(sel==null) return;
            if(sel.equals("44100")){
                prefs.setAudioSamplingRate(44100);
            } else if(sel.equals("48000")) {
                prefs.setAudioSamplingRate(48000);
            }
            refreshAllValues();
        });
        ArrayList<com.fadcam.ui.picker.OptionItem> items = new ArrayList<>();
        int current = prefs.getAudioSamplingRate();
        items.add(new com.fadcam.ui.picker.OptionItem("44100", "44100 Hz" + (current==44100?"" : "")));
        items.add(new com.fadcam.ui.picker.OptionItem("48000", "48000 Hz " + getString(R.string.label_recommended)));
    String helper = getString(R.string.helper_audio_sampling_detail) + "\n" + getString(R.string.helper_audio_sampling_recommended);
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet = com.fadcam.ui.picker.PickerBottomSheetFragment.newInstance(
                getString(R.string.dialog_audio_sampling_rate_label), items, String.valueOf(current), rk, helper);
        sheet.show(getParentFragmentManager(), "audio_sampling_picker");
        // -------------- Fix Ended for this method(showSamplingRateInputSheet)-----------
    }

    private void updateDependentRowStates(boolean recordAudioEnabled){
        // -------------- Fix Start for this method(updateDependentRowStates)-----------
        float disabledAlpha = 0.45f;
        if(rowInputSource!=null){
            rowInputSource.setAlpha(recordAudioEnabled?1f:disabledAlpha);
            rowInputSource.setEnabled(recordAudioEnabled);
            if(recordAudioEnabled){
                rowInputSource.setOnClickListener(v-> showMicSelectionBottomSheet());
            } else {
                rowInputSource.setOnClickListener(null);
            }
        }
        if(rowAdvanced!=null){
            rowAdvanced.setAlpha(recordAudioEnabled?1f:disabledAlpha);
            rowAdvanced.setEnabled(recordAudioEnabled);
            if(recordAudioEnabled){
                rowAdvanced.setOnClickListener(v-> showAudioAdvancedBottomSheet());
            } else {
                rowAdvanced.setOnClickListener(null);
            }
        }
        if(rowNoiseSuppression!=null){
            rowNoiseSuppression.setAlpha(recordAudioEnabled?1f:disabledAlpha);
            rowNoiseSuppression.setEnabled(recordAudioEnabled);
            if(recordAudioEnabled){
                rowNoiseSuppression.setOnClickListener(v-> showNoiseSuppressionBottomSheet());
            } else {
                rowNoiseSuppression.setOnClickListener(null);
            }
        }
        // -------------- Fix Ended for this method(updateDependentRowStates)-----------
    }
}
