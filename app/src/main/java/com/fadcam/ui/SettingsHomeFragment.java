package com.fadcam.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.content.Intent;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.fadcam.R;
import com.fadcam.ui.utils.NewFeatureManager;
import android.widget.TextView;

/**
 * SettingsHomeFragment
 * Lightweight entry point replacing the monolithic SettingsFragment.
 * Shows grouped navigation rows (placeholder toasts for now).
 */
public class SettingsHomeFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_settings_home, container, false);
    // Removed redundant Location & Privacy group (location embedding moved into Video settings)
    int[] ids = new int[]{
        R.id.group_appearance,
        R.id.group_video_quick,
        R.id.group_video_player_settings,
        R.id.group_audio_quick,
        R.id.group_storage,
        R.id.group_notifications,
        R.id.group_security,
        R.id.group_widgets,
        R.id.group_about,
        R.id.group_review,
        R.id.group_watermark_quick,
        R.id.group_readme
    };
    String[] labels = new String[]{
        "Appearance",
        "Video Recording",
        "Video Player Settings",
        "Audio",
        "Storage",
        "Notifications",
        "Security",
        "Widgets",
        "App",
        "Review",
        "Watermark",
        "README"
    };
        for (int i = 0; i < ids.length; i++) {
            if(ids[i] == R.id.group_appearance){
                LinearLayout row = root.findViewById(ids[i]);
                if(row != null){
                    row.setOnClickListener(v -> openSubFragment(new AppearanceSettingsFragment()));
                }
            } else if(ids[i] == R.id.group_video_quick){
                LinearLayout row = root.findViewById(ids[i]);
                if(row != null){
                    row.setOnClickListener(v -> openSubFragment(new VideoSettingsFragment()));
                }
        } else if(ids[i] == R.id.group_video_player_settings){
                LinearLayout row = root.findViewById(ids[i]);
                if(row != null){
            // Open dedicated screen; inside that screen rows still open bottom pickers
            row.setOnClickListener(v -> openSubFragment(new VideoPlayerSettingsFragment()));
                }
            } else if(ids[i] == R.id.group_audio_quick){
                LinearLayout row = root.findViewById(ids[i]);
                if(row != null){
                    row.setOnClickListener(v -> openSubFragment(new AudioSettingsFragment()));
                }
            } else if(ids[i] == R.id.group_storage){
                LinearLayout row = root.findViewById(ids[i]);
                if(row != null){
                    row.setOnClickListener(v -> openSubFragment(new StorageSettingsFragment()));
                }
            } else if(ids[i] == R.id.group_security){
                LinearLayout row = root.findViewById(ids[i]);
                if(row != null){
                    row.setOnClickListener(v -> openSubFragment(new SecuritySettingsFragment()));
                }
            } else if(ids[i] == R.id.group_widgets){
                LinearLayout row = root.findViewById(ids[i]);
                if(row != null){
                    row.setOnClickListener(v -> openSubFragment(new ShortcutsSettingsFragment()));
                }
            } else if(ids[i] == R.id.group_notifications){
                LinearLayout row = root.findViewById(ids[i]);
                if(row != null){
                    row.setOnClickListener(v -> openSubFragment(new NotificationSettingsFragment()));
                }
            } else if(ids[i] == R.id.group_watermark_quick){
                LinearLayout row = root.findViewById(ids[i]);
                if(row != null){
                    row.setOnClickListener(v -> {
                        // Mark watermark badge as seen when clicking the row
                        NewFeatureManager.markFeatureAsSeen(requireContext(), "watermark");
                        // Hide the badge immediately for instant feedback
                        manageBadgeVisibility(root);
                        // Open watermark settings
                        openSubFragment(new WatermarkSettingsFragment());
                    });
                }
            } else if(ids[i] == R.id.group_about){
                LinearLayout row = root.findViewById(ids[i]);
                if(row != null){
                    row.setOnClickListener(v -> openSubFragment(new AboutFragment()));
                }
            } else if(ids[i] == R.id.group_review){
                LinearLayout row = root.findViewById(ids[i]);
                if(row!=null){
                    row.setOnClickListener(v -> launchReview());
                }
            } else if(ids[i] == R.id.group_readme){
                LinearLayout row = root.findViewById(ids[i]);
                if(row!=null){
                    row.setOnClickListener(v -> openReadmeDialog());
                }
            } else {
                setupNav(root, ids[i], labels[i]);
            }
        }
    wireAppInlineRows(root);
    refreshAppInlineValues(root);
    
    // Show/hide watermark NEW badge based on whether user has seen it
    manageBadgeVisibility(root);
        return root;
    }
    
    /**
     * Show or hide the watermark NEW badge based on whether the feature has been seen.
     */
    private void manageBadgeVisibility(View root) {
        try {
            TextView watermarkBadge = root.findViewById(R.id.watermark_new_badge);
            if (watermarkBadge != null) {
                boolean shouldShow = NewFeatureManager.shouldShowBadge(requireContext(), "watermark");
                watermarkBadge.setVisibility(shouldShow ? View.VISIBLE : View.GONE);
            }
        } catch (Exception e) {
            // Silently fail if badge view not found
        }
    }

    /**
     * Opens the unified Video Player Settings sheet, reusing PickerBottomSheetFragment.
     * Contains two rows: Playback Speed and Quick Speed. Helper explains the difference.
     */
    private void showVideoPlayerSettingsSheet(){
        java.util.ArrayList<com.fadcam.ui.picker.OptionItem> items = new java.util.ArrayList<>();
        // We don't compute dynamic subtitles here; keep concise labels.
        items.add(new com.fadcam.ui.picker.OptionItem("row_playback_speed", getString(R.string.playback_speed_label), null, null, null, null, null, null, "speed", null, null, null));
        items.add(new com.fadcam.ui.picker.OptionItem("row_quick_speed", getString(R.string.quick_speed_title), null, null, null, null, null, null, "bolt", null, null, null));
        // Seek amount (show current seconds)
        int seekSec = com.fadcam.SharedPreferencesManager.getInstance(requireContext()).getPlayerSeekSeconds();
        items.add(new com.fadcam.ui.picker.OptionItem("row_seek_amount", getString(R.string.seek_amount_title), getString(R.string.seek_amount_subtitle, seekSec), null, null, null, null, null, "replay_10", null, null, null));
        final String RK_SETTINGS = "rk_settings_video_player";
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet = com.fadcam.ui.picker.PickerBottomSheetFragment.newInstance(
                getString(R.string.video_player_settings_title), items, null, RK_SETTINGS, getString(R.string.video_player_settings_helper));
        getParentFragmentManager().setFragmentResultListener(RK_SETTINGS, this, (key, bundle) -> {
            String sel = bundle.getString(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if("row_playback_speed".equals(sel)){
                showPlaybackSpeedPickerFromSettings();
            } else if("row_quick_speed".equals(sel)){
                showQuickSpeedPickerFromSettings();
            } else if("row_seek_amount".equals(sel)){
                // Show seek amount picker similar to VideoPlayerSettingsFragment
                final String RK = "rk_settings_seek_amount";
                java.util.ArrayList<com.fadcam.ui.picker.OptionItem> itemsSeek = new java.util.ArrayList<>();
                itemsSeek.add(new com.fadcam.ui.picker.OptionItem("s_5", "5s", null, null, null, null, null, null, "replay_10", null, null, null));
                itemsSeek.add(new com.fadcam.ui.picker.OptionItem("s_10", "10s", null, null, null, null, null, null, "replay_10", null, null, null));
                itemsSeek.add(new com.fadcam.ui.picker.OptionItem("s_15", "15s", null, null, null, null, null, null, "replay_10", null, null, null));
                itemsSeek.add(new com.fadcam.ui.picker.OptionItem("s_30", "30s", null, null, null, null, null, null, "replay_10", null, null, null));
                itemsSeek.add(new com.fadcam.ui.picker.OptionItem("s_custom", getString(R.string.seek_amount_custom), null, null, null, null, null, null, "edit", null, null, null));
                String selId = "s_" + seekSec; if(seekSec!=5 && seekSec!=10 && seekSec!=15 && seekSec!=30) selId = "s_custom";
                com.fadcam.ui.picker.PickerBottomSheetFragment sheetSeek = com.fadcam.ui.picker.PickerBottomSheetFragment.newInstance(getString(R.string.seek_amount_title), itemsSeek, selId, RK, getString(R.string.seek_amount_helper));
                getParentFragmentManager().setFragmentResultListener(RK, this, (rk, b) -> {
                    String s = b.getString(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
                    if(s==null) return;
                        if("s_custom".equals(s)){
                        final String RK_NUM = "rk_settings_seek_amount_custom";
                        com.fadcam.ui.picker.NumberInputBottomSheetFragment num = com.fadcam.ui.picker.NumberInputBottomSheetFragment.newInstance(
                            getString(R.string.seek_amount_custom_title), 1, 300, 10, getString(R.string.universal_enter_number), 5, 60,
                            getString(R.string.seek_amount_custom_low_hint), getString(R.string.seek_amount_custom_high_hint), RK_NUM);
                        if(num.getArguments()!=null){ num.getArguments().putString(com.fadcam.ui.picker.NumberInputBottomSheetFragment.ARG_DESCRIPTION, getString(R.string.seek_amount_helper)); }
                        getParentFragmentManager().setFragmentResultListener(RK_NUM, this, (rkn, nb) -> {
                            int v = nb.getInt(com.fadcam.ui.picker.NumberInputBottomSheetFragment.RESULT_NUMBER, 0);
                            if(v>0) com.fadcam.SharedPreferencesManager.getInstance(requireContext()).setPlayerSeekSeconds(v);
                        });
                        // Dismiss parent picker first then show numeric input to avoid cross-sheet helper text
                        try{ sheetSeek.dismissAllowingStateLoss(); } catch(Exception ignored){}
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> { try{ num.show(getParentFragmentManager(), "settings_seek_amount_custom_sheet"); } catch(Exception ignored){} }, 180);
                        return;
                    }
                    if(s.startsWith("s_")){
                        try{ int v = Integer.parseInt(s.substring(2)); com.fadcam.SharedPreferencesManager.getInstance(requireContext()).setPlayerSeekSeconds(v); }catch(NumberFormatException ignored){}
                    }
                });
                sheetSeek.show(getParentFragmentManager(), "settings_seek_amount_sheet");
            }
        });
        sheet.show(getParentFragmentManager(), "video_player_settings_sheet_settings_tab");
    }

    private void showPlaybackSpeedPickerFromSettings(){
        // Mirror the player's list for consistency
    final String RK = "rk_pick_playback_speed_settings";
    float[] speedValues = new float[]{0.25f,0.5f,0.6f,0.7f,0.8f,0.9f,1.0f,1.5f,2.0f,3.0f,4.0f,6.0f,8.0f,10.0f};
    CharSequence[] labels = new CharSequence[]{"0.25x","0.5x","0.6x","0.7x","0.8x","0.9x","1x (Normal)","1.5x","2x","3x","4x","6x","8x","10x"};
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
                    // Persist as default playback speed for future videos
                    com.fadcam.SharedPreferencesManager.getInstance(requireContext()).sharedPreferences
                        .edit().putFloat("pref_default_playback_speed", val).apply();
                    android.widget.Toast.makeText(requireContext(), getString(R.string.toast_default_playback_speed_set, val+"x"), android.widget.Toast.LENGTH_SHORT).show();
                }catch(NumberFormatException ignored){}
            }
        });
        sheet.show(getParentFragmentManager(), "playback_speed_picker_settings_tab");
    }

    private void showQuickSpeedPickerFromSettings(){
        final String RK = "rk_pick_quick_speed_settings";
    float[] speedValues = new float[]{0.25f,0.5f,0.6f,0.7f,0.8f,0.9f,1.0f,1.5f,2.0f,3.0f,4.0f,6.0f,8.0f,10.0f};
    CharSequence[] labels = new CharSequence[]{"0.25x","0.5x","0.6x","0.7x","0.8x","0.9x","1x (Normal)","1.5x","2x","3x","4x","6x","8x","10x"};
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
                }catch(NumberFormatException ignored){}
            }
        });
        sheet.show(getParentFragmentManager(), "quick_speed_picker_settings_tab");
    }

    private void setupNav(View root, int id, String label){
        LinearLayout row = root.findViewById(id);
        if (row != null) {
            row.setOnClickListener(v -> Toast.makeText(requireContext(), label, Toast.LENGTH_SHORT).show());
        }
    }

    private void openSubFragment(Fragment fragment){
    OverlayNavUtil.show(requireActivity(), fragment, fragment.getClass().getSimpleName());
    }

    // Removed legacy widgets bottom sheet (replaced by full Shortcuts & Widgets screen)

    private void wireAppInlineRows(View root){
        View rowOnboarding = root.findViewById(R.id.group_onboarding);
        if(rowOnboarding!=null){ rowOnboarding.setOnClickListener(v -> showOnboardingSwitchSheet()); }
        View rowAutoUpdate = root.findViewById(R.id.group_auto_update);
        if(rowAutoUpdate!=null){ rowAutoUpdate.setOnClickListener(v -> showAutoUpdateSwitchSheet()); }
        View rowDebug = root.findViewById(R.id.group_debug_logging);
        if(rowDebug!=null){
            rowDebug.setOnClickListener(v -> {
                DebugLogBottomSheetFragment sheet = DebugLogBottomSheetFragment.newInstance();
                sheet.show(getParentFragmentManager(), "debug_log_tools");
            });
        }
    View rowBackup = root.findViewById(R.id.group_prefs_backup);
    if(rowBackup!=null){ rowBackup.setOnClickListener(v -> showPrefsBackupSheet()); }
    }

    private void refreshAppInlineValues(View root){
        android.widget.TextView vOn = root.findViewById(R.id.value_onboarding);
        android.widget.TextView vAuto = root.findViewById(R.id.value_auto_update);
        android.widget.TextView vDebug = root.findViewById(R.id.value_debug_logging);
        com.fadcam.SharedPreferencesManager prefs = com.fadcam.SharedPreferencesManager.getInstance(requireContext());
        if(vOn!=null){ vOn.setText(prefs.isShowOnboarding()? getString(R.string.universal_enable): getString(R.string.universal_disable)); }
        boolean auto = prefs.sharedPreferences.getBoolean("auto_update_check_enabled", true);
        if(vAuto!=null){ vAuto.setText(auto? getString(R.string.universal_enable): getString(R.string.universal_disable)); }
        if(vDebug!=null){ vDebug.setText(prefs.isDebugLoggingEnabled()? getString(R.string.universal_enable): getString(R.string.universal_disable)); }
    }

    private void openReadmeDialog(){
        ReadmeBottomSheetFragment sheet = ReadmeBottomSheetFragment.newInstance();
        sheet.show(getParentFragmentManager(), "readme_sheet");
    }

    private void launchReview(){
        try {
            android.content.Intent i = new android.content.Intent(requireContext(), WebViewActivity.class);
            i.putExtra("url", "https://forms.gle/DvUoc1v9kB2bkFiS6");
            startActivity(i);
        } catch (Exception e){
            android.widget.Toast.makeText(requireContext(), "Unable to open review form", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void showOnboardingSwitchSheet(){
        final String resultKey = "picker_result_onboarding";
        com.fadcam.SharedPreferencesManager prefs = com.fadcam.SharedPreferencesManager.getInstance(requireContext());
        boolean enabled = prefs.isShowOnboarding();
        getParentFragmentManager().setFragmentResultListener(resultKey, this, (k,b)->{
            if(b.containsKey(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SWITCH_STATE)){
                boolean state = b.getBoolean(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SWITCH_STATE);
                if(state){ prefs.sharedPreferences.edit().putBoolean(com.fadcam.Constants.FIRST_INSTALL_CHECKED_KEY, false).apply(); }
                prefs.setShowOnboarding(state);
                refreshAppInlineValues(requireView());
            }
        });
        String helper = getString(R.string.note_onboarding);
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet = com.fadcam.ui.picker.PickerBottomSheetFragment.newInstanceWithSwitch(
                getString(R.string.setting_onboarding_title), new java.util.ArrayList<>(), null, resultKey, helper,
                getString(R.string.setting_onboarding_title), enabled);
        sheet.show(getParentFragmentManager(), "onboarding_switch_sheet");
    }

    private void showAutoUpdateSwitchSheet(){
        final String resultKey = "picker_result_auto_update";
        com.fadcam.SharedPreferencesManager prefs = com.fadcam.SharedPreferencesManager.getInstance(requireContext());
        boolean enabled = prefs.sharedPreferences.getBoolean("auto_update_check_enabled", true);
        getParentFragmentManager().setFragmentResultListener(resultKey, this, (k,b)->{
            if(b.containsKey(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SWITCH_STATE)){
                boolean state = b.getBoolean(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SWITCH_STATE);
                prefs.sharedPreferences.edit().putBoolean("auto_update_check_enabled", state).apply();
                refreshAppInlineValues(requireView());
            }
        });
        String helper = getString(R.string.note_auto_update_check);
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet = com.fadcam.ui.picker.PickerBottomSheetFragment.newInstanceWithSwitch(
                getString(R.string.setting_auto_update_check_title), new java.util.ArrayList<>(), null, resultKey, helper,
                getString(R.string.setting_auto_update_check_title), enabled);
        sheet.show(getParentFragmentManager(), "auto_update_switch_sheet");
    }

    private void showDebugLoggingSwitchSheet(){
        final String resultKey = "picker_result_debug_logging";
        com.fadcam.SharedPreferencesManager prefs = com.fadcam.SharedPreferencesManager.getInstance(requireContext());
        boolean enabled = prefs.isDebugLoggingEnabled();
        getParentFragmentManager().setFragmentResultListener(resultKey, this, (k,b)->{
            if(b.containsKey(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SWITCH_STATE)){
                boolean state = b.getBoolean(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SWITCH_STATE);
                prefs.sharedPreferences.edit().putBoolean(com.fadcam.Constants.PREF_DEBUG_DATA, state).apply();
                refreshAppInlineValues(requireView());
            }
        });
    String helper = getString(R.string.note_debug_detailed);
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet = com.fadcam.ui.picker.PickerBottomSheetFragment.newInstanceWithSwitch(
                getString(R.string.setting_debug_title), new java.util.ArrayList<>(), null, resultKey, helper,
                getString(R.string.setting_debug_title), enabled);
        sheet.show(getParentFragmentManager(), "debug_logging_switch_sheet");
    }

    private void showPrefsBackupSheet(){
        PrefsBackupBottomSheetFragment sheet = PrefsBackupBottomSheetFragment.newInstance();
        sheet.show(getParentFragmentManager(), "prefs_backup_sheet");
    }

    @Override
    public void onResume() {
        super.onResume();
        // Refresh badge visibility when returning to this fragment
        View root = getView();
        if (root != null) {
            manageBadgeVisibility(root);
        }
        // Future: update dynamic subtitles (e.g., current theme, language) when preferences change.
    }
}
