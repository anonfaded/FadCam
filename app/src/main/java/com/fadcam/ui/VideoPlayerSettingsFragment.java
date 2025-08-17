package com.fadcam.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.fadcam.R;

/**
 * Displays Video Player Settings as a full screen in Settings tab, matching the app's settings design.
 * Rows open the same bottom pickers: Playback Speed and Quick Speed.
 */
public class VideoPlayerSettingsFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_video_player_settings, container, false);
        // -------------- Fix Start for this method(onCreateView)-----------
        LinearLayout rowPlayback = root.findViewById(R.id.row_playback_speed);
    LinearLayout rowQuick = root.findViewById(R.id.row_quick_speed);
        TextView subPlayback = root.findViewById(R.id.sub_playback_speed);
        TextView subQuick = root.findViewById(R.id.sub_quick_speed);
    LinearLayout rowKeepOn = root.findViewById(R.id.row_keep_screen_on);
    TextView subKeepOn = root.findViewById(R.id.sub_keep_screen_on);
    LinearLayout rowBgPlayback = root.findViewById(R.id.row_background_playback);
    TextView subBgPlayback = root.findViewById(R.id.sub_background_playback);
    TextView subMute = root.findViewById(R.id.sub_mute_playback);

        // Subtitles
    // Show saved default playback speed (defaults to Normal 1x)
    float defaultPlayback = com.fadcam.SharedPreferencesManager.getInstance(requireContext())
        .sharedPreferences.getFloat("pref_default_playback_speed", 1.0f);
    subPlayback.setText(getPlaybackLabel(defaultPlayback));
        float quick = com.fadcam.SharedPreferencesManager.getInstance(requireContext()).getQuickSpeed();
        subQuick.setText(formatSpeed(quick));
    boolean muted = com.fadcam.SharedPreferencesManager.getInstance(requireContext()).isPlaybackMuted();
    subMute.setText(muted? getString(R.string.universal_enable): getString(R.string.universal_disable));
    boolean keepOn = com.fadcam.SharedPreferencesManager.getInstance(requireContext()).isPlayerKeepScreenOn();
    if (subKeepOn != null) subKeepOn.setText(keepOn? getString(R.string.universal_enable): getString(R.string.universal_disable));
    boolean bg = com.fadcam.SharedPreferencesManager.getInstance(requireContext()).isBackgroundPlaybackEnabled();
    if (subBgPlayback != null) subBgPlayback.setText(bg? getString(R.string.universal_enable): getString(R.string.universal_disable));

        rowPlayback.setOnClickListener(v -> showPlaybackSpeedPicker());
        rowQuick.setOnClickListener(v -> showQuickSpeedPicker());
    if (rowKeepOn != null) rowKeepOn.setOnClickListener(v -> showKeepScreenOnSwitchSheet());
    if (rowBgPlayback != null) rowBgPlayback.setOnClickListener(v -> showBackgroundPlaybackSwitchSheet());
        // Mute switch row via picker with switch
        View rowMute = root.findViewById(R.id.row_mute_playback);
        if(rowMute!=null){ rowMute.setOnClickListener(v -> showMuteSwitchSheet()); }
    View back = root.findViewById(R.id.back_button);
    if(back!=null){ back.setOnClickListener(v -> OverlayNavUtil.dismiss(requireActivity())); }
        // -------------- Fix Ended for this method(onCreateView)-----------
        return root;
    }

    private void showKeepScreenOnSwitchSheet(){
        // -------------- Fix Start for this method(showKeepScreenOnSwitchSheet)-----------
        final String RK = "rk_vps_keep_screen_on_switch";
        boolean enabled = com.fadcam.SharedPreferencesManager.getInstance(requireContext()).isPlayerKeepScreenOn();
        getParentFragmentManager().setFragmentResultListener(RK, this, (k,b)->{
            if(b.containsKey(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SWITCH_STATE)){
                boolean state = b.getBoolean(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SWITCH_STATE);
                com.fadcam.SharedPreferencesManager.getInstance(requireContext()).setPlayerKeepScreenOn(state);
                View root = getView();
                if(root!=null){
                    TextView sub = root.findViewById(R.id.sub_keep_screen_on);
                    if(sub!=null){ sub.setText(state? getString(R.string.universal_enable): getString(R.string.universal_disable)); }
                }
            }
        });
        String helper = getString(R.string.keep_screen_on_helper_picker);
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet = com.fadcam.ui.picker.PickerBottomSheetFragment.newInstanceWithSwitch(
                getString(R.string.keep_screen_on_title), new java.util.ArrayList<>(), null, RK, helper,
                getString(R.string.keep_screen_on_title), enabled);
        sheet.show(getParentFragmentManager(), "vps_keep_screen_on_switch_sheet");
        // -------------- Fix Ended for this method(showKeepScreenOnSwitchSheet)-----------
    }

    private void showBackgroundPlaybackSwitchSheet(){
        final String RK = "rk_vps_background_playback_switch";
        boolean enabled = com.fadcam.SharedPreferencesManager.getInstance(requireContext()).isBackgroundPlaybackEnabled();
        getParentFragmentManager().setFragmentResultListener(RK, this, (k,b)->{
            if(b.containsKey(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SWITCH_STATE)){
                boolean state = b.getBoolean(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SWITCH_STATE);
                com.fadcam.SharedPreferencesManager.getInstance(requireContext()).setBackgroundPlaybackEnabled(state);
                View root = getView();
                if(root!=null){
                    TextView sub = root.findViewById(R.id.sub_background_playback);
                    if(sub!=null){ sub.setText(state? getString(R.string.universal_enable): getString(R.string.universal_disable)); }
                }
            }
        });
        String helper = getString(R.string.background_playback_helper_picker);
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet = com.fadcam.ui.picker.PickerBottomSheetFragment.newInstanceWithSwitch(
                getString(R.string.background_playback_title), new java.util.ArrayList<>(), null, RK, helper,
                getString(R.string.background_playback_title), enabled);
        sheet.show(getParentFragmentManager(), "vps_background_playback_switch_sheet");
    }

    private void showMuteSwitchSheet(){
        // -------------- Fix Start for this method(showMuteSwitchSheet)-----------
        final String RK = "rk_vps_mute_switch";
        boolean enabled = com.fadcam.SharedPreferencesManager.getInstance(requireContext()).isPlaybackMuted();
        getParentFragmentManager().setFragmentResultListener(RK, this, (k,b)->{
            if(b.containsKey(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SWITCH_STATE)){
                boolean state = b.getBoolean(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SWITCH_STATE);
                com.fadcam.SharedPreferencesManager.getInstance(requireContext()).setPlaybackMuted(state);
                android.widget.Toast.makeText(requireContext(), state? getString(R.string.mute_on_toast): getString(R.string.mute_off_toast), android.widget.Toast.LENGTH_SHORT).show();
        // Update subtitle inline
        View root = getView();
        if(root!=null){
            TextView sub = root.findViewById(R.id.sub_mute_playback);
            if(sub!=null){ sub.setText(state? getString(R.string.universal_enable): getString(R.string.universal_disable)); }
        }
            }
        });
    String helper = getString(R.string.mute_playback_helper_picker);
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet = com.fadcam.ui.picker.PickerBottomSheetFragment.newInstanceWithSwitch(
                getString(R.string.mute_playback_title), new java.util.ArrayList<>(), null, RK, helper, getString(R.string.mute_playback_title), enabled);
        sheet.show(getParentFragmentManager(), "vps_mute_switch_sheet");
        // -------------- Fix Ended for this method(showMuteSwitchSheet)-----------
    }

    private void showPlaybackSpeedPicker(){
        // -------------- Fix Start for this method(showPlaybackSpeedPicker)-----------
        final String RK = "rk_vps_playback_speed";
        float[] speedValues = new float[]{0.5f,1.0f,1.5f,2.0f,3.0f,4.0f,6.0f,8.0f,10.0f};
        CharSequence[] labels = new CharSequence[]{"0.5x","1x (Normal)","1.5x","2x","3x","4x","6x","8x","10x"};
        java.util.ArrayList<com.fadcam.ui.picker.OptionItem> items = new java.util.ArrayList<>();
        for(int i=0;i<speedValues.length;i++){
            items.add(new com.fadcam.ui.picker.OptionItem("spd_"+speedValues[i], String.valueOf(labels[i]), null, null, null, null, null, null, "speed", null, null, null));
        }
    float current = com.fadcam.SharedPreferencesManager.getInstance(requireContext())
        .sharedPreferences.getFloat("pref_default_playback_speed", 1.0f);
    String selectedId = "spd_"+current;
    com.fadcam.ui.picker.PickerBottomSheetFragment sheet = com.fadcam.ui.picker.PickerBottomSheetFragment.newInstance(
        getString(R.string.playback_speed_title), items, selectedId, RK, getString(R.string.playback_speed_helper_settings));
        getParentFragmentManager().setFragmentResultListener(RK, this, (k,b)->{
            String sel = b.getString(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if(sel!=null && sel.startsWith("spd_")){
                try{
                    float val = Float.parseFloat(sel.substring(4));
                    com.fadcam.SharedPreferencesManager.getInstance(requireContext()).sharedPreferences
                        .edit().putFloat("pref_default_playback_speed", val).apply();
                    android.widget.Toast.makeText(requireContext(), getString(R.string.toast_default_playback_speed_set, val+"x"), android.widget.Toast.LENGTH_SHORT).show();
                    View root = getView();
                    if(root!=null){
                        TextView subPlayback2 = root.findViewById(R.id.sub_playback_speed);
            if(subPlayback2!=null) subPlayback2.setText(getPlaybackLabel(val));
                    }
                }catch(NumberFormatException ignored){}
            }
        });
        sheet.show(getParentFragmentManager(), "vps_playback_speed_picker");
        // -------------- Fix Ended for this method(showPlaybackSpeedPicker)-----------
    }

    private void showQuickSpeedPicker(){
        // -------------- Fix Start for this method(showQuickSpeedPicker)-----------
        final String RK = "rk_vps_quick_speed";
        float[] speedValues = new float[]{0.5f,1.0f,1.5f,2.0f,3.0f,4.0f,6.0f,8.0f,10.0f};
        CharSequence[] labels = new CharSequence[]{"0.5x","1x (Normal)","1.5x","2x","3x","4x","6x","8x","10x"};
        java.util.ArrayList<com.fadcam.ui.picker.OptionItem> items = new java.util.ArrayList<>();
        for(int i=0;i<speedValues.length;i++){
            String title = Math.abs(speedValues[i]-2.0f)<0.001f? getString(R.string.quick_speed_option_default): String.valueOf(labels[i]);
            items.add(new com.fadcam.ui.picker.OptionItem("spd_"+speedValues[i], title, null, null, null, null, null, null, "bolt", null, null, null));
        }
        float current = com.fadcam.SharedPreferencesManager.getInstance(requireContext()).getQuickSpeed();
        String selectedId = "spd_"+current;
    com.fadcam.ui.picker.PickerBottomSheetFragment sheet = com.fadcam.ui.picker.PickerBottomSheetFragment.newInstance(
        getString(R.string.quick_speed_title), items, selectedId, RK, getString(R.string.quick_speed_helper));
        getParentFragmentManager().setFragmentResultListener(RK, this, (k,b)->{
            String sel = b.getString(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if(sel!=null && sel.startsWith("spd_")){
                try{
                    float val = Float.parseFloat(sel.substring(4));
                    com.fadcam.SharedPreferencesManager.getInstance(requireContext()).setQuickSpeed(val);
                    View root = getView();
                    if(root!=null){
                        TextView subQuick = root.findViewById(R.id.sub_quick_speed);
                        if(subQuick!=null) subQuick.setText(formatSpeed(val));
                    }
                }catch(NumberFormatException ignored){}
            }
        });
        sheet.show(getParentFragmentManager(), "vps_quick_speed_picker");
        // -------------- Fix Ended for this method(showQuickSpeedPicker)-----------
    }

    private String formatSpeed(float v){
        try { java.text.DecimalFormat df = new java.text.DecimalFormat("#.#"); return df.format(v)+"x"; } catch (Exception e){ return v+"x"; }
    }

    private String getPlaybackLabel(float v){
    if (Math.abs(v - 0.5f) < 0.001f) return "0.5x";
    if (Math.abs(v - 1.0f) < 0.001f) return "1x (Normal)";
    if (Math.abs(v - 1.5f) < 0.001f) return "1.5x";
    if (Math.abs(v - 2.0f) < 0.001f) return "2x";
    if (Math.abs(v - 3.0f) < 0.001f) return "3x";
    if (Math.abs(v - 4.0f) < 0.001f) return "4x";
    if (Math.abs(v - 6.0f) < 0.001f) return "6x";
    if (Math.abs(v - 8.0f) < 0.001f) return "8x";
    if (Math.abs(v - 10.0f) < 0.001f) return "10x";
    return formatSpeed(v);
    }
}
