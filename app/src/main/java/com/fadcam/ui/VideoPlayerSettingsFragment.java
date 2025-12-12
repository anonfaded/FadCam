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
    public View onCreateView(
        @NonNull LayoutInflater inflater,
        @Nullable ViewGroup container,
        @Nullable Bundle savedInstanceState
    ) {
        View root = inflater.inflate(
            R.layout.fragment_video_player_settings,
            container,
            false
        );
        LinearLayout rowPlayback = root.findViewById(R.id.row_playback_speed);
        LinearLayout rowQuick = root.findViewById(R.id.row_quick_speed);
        TextView subPlayback = root.findViewById(R.id.sub_playback_speed);
        TextView subQuick = root.findViewById(R.id.sub_quick_speed);
        LinearLayout rowKeepOn = root.findViewById(R.id.row_keep_screen_on);
        TextView subKeepOn = root.findViewById(R.id.sub_keep_screen_on);
        LinearLayout rowBgPlayback = root.findViewById(
            R.id.row_background_playback
        );
        LinearLayout rowBgTimer = root.findViewById(
            R.id.row_background_playback_timer
        );
        TextView subBgPlayback = root.findViewById(
            R.id.sub_background_playback
        );
        TextView subBgTimer = root.findViewById(
            R.id.sub_background_playback_timer
        );
        TextView subMute = root.findViewById(R.id.sub_mute_playback);
        TextView subAudioWaveform = root.findViewById(R.id.sub_audio_waveform);
        // New: Controller timeout row
        LinearLayout rowControllerTimeout = root.findViewById(
            R.id.row_controller_timeout
        );
        TextView subControllerTimeout = root.findViewById(
            R.id.sub_controller_timeout
        );

        // Subtitles
        // Show saved default playback speed (defaults to Normal 1x)
        float defaultPlayback = com.fadcam.SharedPreferencesManager.getInstance(
            requireContext()
        ).sharedPreferences.getFloat("pref_default_playback_speed", 1.0f);
        subPlayback.setText(getPlaybackLabel(defaultPlayback));
        float quick = com.fadcam.SharedPreferencesManager.getInstance(
            requireContext()
        ).getQuickSpeed();
        subQuick.setText(formatSpeed(quick));
        // Seek amount subtitle
        int seekSec = com.fadcam.SharedPreferencesManager.getInstance(
            requireContext()
        ).getPlayerSeekSeconds();
        TextView subSeek = root.findViewById(R.id.sub_seek_amount);
        if (subSeek != null) subSeek.setText(
            getString(R.string.seek_amount_subtitle, seekSec)
        );
        boolean muted = com.fadcam.SharedPreferencesManager.getInstance(
            requireContext()
        ).isPlaybackMuted();
        subMute.setText(
            muted
                ? getString(R.string.universal_enable)
                : getString(R.string.universal_disable)
        );
        boolean waveformEnabled =
            com.fadcam.SharedPreferencesManager.getInstance(
                requireContext()
            ).isAudioWaveformEnabled();
        if (subAudioWaveform != null) subAudioWaveform.setText(
            waveformEnabled
                ? getString(R.string.universal_enable)
                : getString(R.string.universal_disable)
        );
        boolean keepOn = com.fadcam.SharedPreferencesManager.getInstance(
            requireContext()
        ).isPlayerKeepScreenOn();
        if (subKeepOn != null) subKeepOn.setText(
            keepOn
                ? getString(R.string.universal_enable)
                : getString(R.string.universal_disable)
        );
        boolean bg = com.fadcam.SharedPreferencesManager.getInstance(
            requireContext()
        ).isBackgroundPlaybackEnabled();
        if (subBgPlayback != null) subBgPlayback.setText(
            bg
                ? getString(R.string.universal_enable)
                : getString(R.string.universal_disable)
        );
        // Initialize timer subtitle from saved preference so current selection is visible
        if (subBgTimer != null) {
            int savedSeconds = com.fadcam.SharedPreferencesManager.getInstance(
                requireContext()
            ).getBackgroundPlaybackTimerSeconds();
            if (savedSeconds == 0) subBgTimer.setText(
                getString(R.string.timer_off_short)
            );
            else if (savedSeconds < 60) subBgTimer.setText(
                getString(R.string.timer_seconds_short, savedSeconds)
            );
            else if (savedSeconds < 3600) subBgTimer.setText(
                getString(R.string.timer_minutes_short, savedSeconds / 60)
            );
            else subBgTimer.setText(
                getString(R.string.timer_hours_short, savedSeconds / 3600)
            );
        }
        // Set timer row enabled/disabled visual state and click behavior
        if (rowBgTimer != null) {
            rowBgTimer.setEnabled(bg);
            rowBgTimer.setAlpha(bg ? 1f : 0.4f);
        }
        if (subBgTimer != null) {
            /* subtitle already updated elsewhere */
        }

        // Initialize controller timeout subtitle from prefs
        if (subControllerTimeout != null) {
            int ctrlSeconds = com.fadcam.SharedPreferencesManager.getInstance(
                requireContext()
            ).getPlayerControlsTimeoutSeconds();
            if (ctrlSeconds == 0) {
                subControllerTimeout.setText(
                    getString(R.string.timer_off_short)
                );
            } else if (ctrlSeconds < 60) {
                subControllerTimeout.setText(
                    getString(R.string.timer_seconds_short, ctrlSeconds)
                );
            } else if (ctrlSeconds < 3600) {
                subControllerTimeout.setText(
                    getString(R.string.timer_minutes_short, ctrlSeconds / 60)
                );
            } else {
                subControllerTimeout.setText(
                    getString(R.string.timer_hours_short, ctrlSeconds / 3600)
                );
            }
        }

        rowPlayback.setOnClickListener(v -> showPlaybackSpeedPicker());
        rowQuick.setOnClickListener(v -> showQuickSpeedPicker());
        View rowSeek = root.findViewById(R.id.row_seek_amount);
        if (rowSeek != null) {
            rowSeek.setOnClickListener(v -> showSeekAmountPicker());
        }
        if (rowKeepOn != null) rowKeepOn.setOnClickListener(v ->
            showKeepScreenOnSwitchSheet()
        );
        if (rowBgPlayback != null) rowBgPlayback.setOnClickListener(v ->
            showBackgroundPlaybackSwitchSheet()
        );
        if (rowBgTimer != null) rowBgTimer.setOnClickListener(v -> {
            boolean bgEnabled = com.fadcam.SharedPreferencesManager.getInstance(
                requireContext()
            ).isBackgroundPlaybackEnabled();
            if (!bgEnabled) {
                // subtle bounce to indicate disabled
                rowBgTimer
                    .animate()
                    .scaleX(0.98f)
                    .scaleY(0.98f)
                    .setDuration(80)
                    .withEndAction(() ->
                        rowBgTimer
                            .animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(80)
                    )
                    .start();
                android.widget.Toast.makeText(
                    requireContext(),
                    getString(R.string.timer_disabled_hint),
                    android.widget.Toast.LENGTH_SHORT
                ).show();
                return;
            }
            showBackgroundPlaybackTimerPicker();
        });
        // Mute switch row via picker with switch
        View rowMute = root.findViewById(R.id.row_mute_playback);
        if (rowMute != null) {
            rowMute.setOnClickListener(v -> showMuteSwitchSheet());
        }
        // Audio waveform switch row via picker with switch
        View rowAudioWaveform = root.findViewById(R.id.row_audio_waveform);
        if (rowAudioWaveform != null) {
            rowAudioWaveform.setOnClickListener(v ->
                showAudioWaveformSwitchSheet()
            );
        }
        View back = root.findViewById(R.id.back_button);
        if (back != null) {
            back.setOnClickListener(v ->
                OverlayNavUtil.dismiss(requireActivity())
            );
        }

        // Controller timeout click handler
        if (rowControllerTimeout != null) {
            rowControllerTimeout.setOnClickListener(v ->
                showControllerTimeoutPicker()
            );
        }
        return root;
    }

    /**
     * Shows a picker that allows selecting the controller auto-hide timeout for the player.
     * Values are stored in seconds. 0 = never auto-hide.
     */
    private void showControllerTimeoutPicker() {
        final String RK = "rk_vps_controller_timeout";
        java.util.ArrayList<com.fadcam.ui.picker.OptionItem> items =
            new java.util.ArrayList<>();
        items.add(
            new com.fadcam.ui.picker.OptionItem(
                "c_off",
                getString(R.string.timer_off),
                null,
                null,
                null,
                null,
                null,
                null,
                "block",
                null,
                null,
                null
            )
        );
        items.add(
            new com.fadcam.ui.picker.OptionItem(
                "c_3s",
                "3s",
                null,
                null,
                null,
                null,
                null,
                null,
                "timer",
                null,
                null,
                null
            )
        );
        items.add(
            new com.fadcam.ui.picker.OptionItem(
                "c_5s",
                "5s",
                null,
                null,
                null,
                null,
                null,
                null,
                "timer",
                null,
                null,
                null
            )
        );
        items.add(
            new com.fadcam.ui.picker.OptionItem(
                "c_10s",
                "10s",
                null,
                null,
                null,
                null,
                null,
                null,
                "timer",
                null,
                null,
                null
            )
        );
        items.add(
            new com.fadcam.ui.picker.OptionItem(
                "c_15s",
                "15s",
                null,
                null,
                null,
                null,
                null,
                null,
                "timer",
                null,
                null,
                null
            )
        );
        items.add(
            new com.fadcam.ui.picker.OptionItem(
                "c_30s",
                "30s",
                null,
                null,
                null,
                null,
                null,
                null,
                "timer",
                null,
                null,
                null
            )
        );
        items.add(
            new com.fadcam.ui.picker.OptionItem(
                "c_custom",
                getString(R.string.timer_custom_label),
                null,
                null,
                null,
                null,
                null,
                null,
                "edit",
                null,
                null,
                null
            )
        );
        int cur = com.fadcam.SharedPreferencesManager.getInstance(
            requireContext()
        ).getPlayerControlsTimeoutSeconds();
        String selectedId = "c_off";
        if (cur == 3) selectedId = "c_3s";
        else if (cur == 5) selectedId = "c_5s";
        else if (cur == 10) selectedId = "c_10s";
        else if (cur == 15) selectedId = "c_15s";
        else if (cur == 30) selectedId = "c_30s";
        else if (cur > 0) selectedId = "c_custom";
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet =
            com.fadcam.ui.picker.PickerBottomSheetFragment.newInstance(
                getString(R.string.controls_hide_delay_title),
                items,
                selectedId,
                RK,
                getString(R.string.controls_hide_delay_helper)
            );
        getParentFragmentManager().setFragmentResultListener(
                RK,
                this,
                (rkRes, b) -> {
                    String sel = b.getString(
                        com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID
                    );
                    if (sel == null) return;
                    if ("c_custom".equals(sel)) {
                        final String RK_NUM =
                            "rk_vps_controller_timeout_custom";
                        com.fadcam.ui.picker.NumberInputBottomSheetFragment num =
                            com.fadcam.ui.picker.NumberInputBottomSheetFragment.newInstance(
                                getString(R.string.timer_custom_title),
                                1,
                                86400,
                                1,
                                getString(R.string.universal_enter_number),
                                5,
                                60,
                                getString(R.string.timer_custom_low_hint),
                                getString(R.string.timer_custom_high_hint),
                                RK_NUM
                            );
                        android.os.Bundle _b = num.getArguments() != null
                            ? num.getArguments()
                            : new android.os.Bundle();
                        _b.putString(
                            com.fadcam.ui.picker.NumberInputBottomSheetFragment.ARG_DESCRIPTION,
                            getString(R.string.controls_hide_delay_helper)
                        );
                        num.setArguments(_b);
                        getParentFragmentManager().setFragmentResultListener(
                                RK_NUM,
                                this,
                                (rkn, nb) -> {
                                    int val = nb.getInt(
                                        com.fadcam.ui.picker.NumberInputBottomSheetFragment.RESULT_NUMBER,
                                        0
                                    );
                                    if (val > 0) {
                                        com.fadcam.SharedPreferencesManager.getInstance(
                                            requireContext()
                                        ).setPlayerControlsTimeoutSeconds(val);
                                        View root = getView();
                                        if (root != null) {
                                            TextView sub = root.findViewById(
                                                R.id.sub_controller_timeout
                                            );
                                            if (sub != null) sub.setText(
                                                val < 60
                                                    ? getString(
                                                        R.string.timer_seconds_short,
                                                        val
                                                    )
                                                    : (val < 3600
                                                            ? getString(
                                                                R.string.timer_minutes_short,
                                                                val / 60
                                                            )
                                                            : getString(
                                                                R.string.timer_hours_short,
                                                                val / 3600
                                                            ))
                                            );
                                        }
                                    }
                                }
                            );
                        // Dismiss parent then show numeric
                        try {
                            sheet.dismissAllowingStateLoss();
                        } catch (Exception ignored) {}
                        new android.os.Handler(
                            android.os.Looper.getMainLooper()
                        ).postDelayed(
                                () -> {
                                    try {
                                        num.show(
                                            getParentFragmentManager(),
                                            "controller_timeout_custom_sheet"
                                        );
                                    } catch (Exception ignored) {}
                                },
                                180
                            );
                        return;
                    }
                    if (sel.startsWith("c_")) {
                        int seconds = 0;
                        switch (sel) {
                            case "c_off":
                                seconds = 0;
                                break;
                            case "c_3s":
                                seconds = 3;
                                break;
                            case "c_5s":
                                seconds = 5;
                                break;
                            case "c_10s":
                                seconds = 10;
                                break;
                            case "c_15s":
                                seconds = 15;
                                break;
                            case "c_30s":
                                seconds = 30;
                                break;
                            default:
                                seconds = 0;
                                break;
                        }
                        com.fadcam.SharedPreferencesManager.getInstance(
                            requireContext()
                        ).setPlayerControlsTimeoutSeconds(seconds);
                        View root = getView();
                        if (root != null) {
                            TextView sub = root.findViewById(
                                R.id.sub_controller_timeout
                            );
                            if (sub != null) {
                                if (seconds == 0) sub.setText(
                                    getString(R.string.timer_off_short)
                                );
                                else if (seconds < 60) sub.setText(
                                    getString(
                                        R.string.timer_seconds_short,
                                        seconds
                                    )
                                );
                                else if (seconds < 3600) sub.setText(
                                    getString(
                                        R.string.timer_minutes_short,
                                        seconds / 60
                                    )
                                );
                                else sub.setText(
                                    getString(
                                        R.string.timer_hours_short,
                                        seconds / 3600
                                    )
                                );
                            }
                        }
                    }
                }
            );
        sheet.show(getParentFragmentManager(), "vps_controller_timeout_sheet");
    }

    private void showKeepScreenOnSwitchSheet() {
        final String RK = "rk_vps_keep_screen_on_switch";
        boolean enabled = com.fadcam.SharedPreferencesManager.getInstance(
            requireContext()
        ).isPlayerKeepScreenOn();
        getParentFragmentManager().setFragmentResultListener(
                RK,
                this,
                (k, b) -> {
                    if (
                        b.containsKey(
                            com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SWITCH_STATE
                        )
                    ) {
                        boolean state = b.getBoolean(
                            com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SWITCH_STATE
                        );
                        com.fadcam.SharedPreferencesManager.getInstance(
                            requireContext()
                        ).setPlayerKeepScreenOn(state);
                        View root = getView();
                        if (root != null) {
                            TextView sub = root.findViewById(
                                R.id.sub_keep_screen_on
                            );
                            if (sub != null) {
                                sub.setText(
                                    state
                                        ? getString(R.string.universal_enable)
                                        : getString(R.string.universal_disable)
                                );
                            }
                        }
                    }
                }
            );
        String helper = getString(R.string.keep_screen_on_helper_picker);
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet =
            com.fadcam.ui.picker.PickerBottomSheetFragment.newInstanceWithSwitch(
                getString(R.string.keep_screen_on_title),
                new java.util.ArrayList<>(),
                null,
                RK,
                helper,
                getString(R.string.keep_screen_on_title),
                enabled
            );
        sheet.show(
            getParentFragmentManager(),
            "vps_keep_screen_on_switch_sheet"
        );
    }

    private void showBackgroundPlaybackSwitchSheet() {
        final String RK = "rk_vps_background_playback_switch";
        boolean enabled = com.fadcam.SharedPreferencesManager.getInstance(
            requireContext()
        ).isBackgroundPlaybackEnabled();
        getParentFragmentManager().setFragmentResultListener(
                RK,
                this,
                (k, b) -> {
                    if (
                        b.containsKey(
                            com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SWITCH_STATE
                        )
                    ) {
                        boolean state = b.getBoolean(
                            com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SWITCH_STATE
                        );
                        com.fadcam.SharedPreferencesManager.getInstance(
                            requireContext()
                        ).setBackgroundPlaybackEnabled(state);
                        View root = getView();
                        if (root != null) {
                            TextView sub = root.findViewById(
                                R.id.sub_background_playback
                            );
                            if (sub != null) {
                                sub.setText(
                                    state
                                        ? getString(R.string.universal_enable)
                                        : getString(R.string.universal_disable)
                                );
                            }
                            // Update timer row enabled state
                            LinearLayout rowTimer = root.findViewById(
                                R.id.row_background_playback_timer
                            );
                            TextView subTimer = root.findViewById(
                                R.id.sub_background_playback_timer
                            );
                            if (rowTimer != null) {
                                rowTimer.setEnabled(state);
                                rowTimer.setAlpha(state ? 1f : 0.4f);
                            }
                            if (subTimer != null) {
                                /* keep existing subtitle text */
                            }
                        }
                    }
                }
            );
        String helper = getString(R.string.background_playback_helper_picker);
        // Make the timer row (id: row_background_playback_timer) dependent on this switch
        java.util.ArrayList<String> deps = new java.util.ArrayList<>();
        deps.add("row_background_playback_timer");
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet =
            com.fadcam.ui.picker.PickerBottomSheetFragment.newInstanceWithSwitchDependencies(
                getString(R.string.background_playback_title),
                new java.util.ArrayList<>(),
                null,
                RK,
                helper,
                getString(R.string.background_playback_title),
                enabled,
                deps
            );
        sheet.show(
            getParentFragmentManager(),
            "vps_background_playback_switch_sheet"
        );
    }

    private void showBackgroundPlaybackTimerPicker() {
        final String RK = "rk_vps_background_playback_timer";
        // Preset choices in seconds: Off (0), 30s, 1m, 5m, 15m, 30m, 1h
        java.util.ArrayList<com.fadcam.ui.picker.OptionItem> items =
            new java.util.ArrayList<>();
        items.add(
            new com.fadcam.ui.picker.OptionItem(
                "t_off",
                getString(R.string.timer_off),
                null,
                null,
                null,
                null,
                null,
                null,
                "block",
                null,
                null,
                null
            )
        );
        items.add(
            new com.fadcam.ui.picker.OptionItem(
                "t_30s",
                getString(R.string.timer_30_seconds),
                null,
                null,
                null,
                null,
                null,
                null,
                "timer",
                null,
                null,
                null
            )
        );
        items.add(
            new com.fadcam.ui.picker.OptionItem(
                "t_1m",
                getString(R.string.timer_1_minute),
                null,
                null,
                null,
                null,
                null,
                null,
                "timer",
                null,
                null,
                null
            )
        );
        items.add(
            new com.fadcam.ui.picker.OptionItem(
                "t_5m",
                getString(R.string.timer_5_minutes),
                null,
                null,
                null,
                null,
                null,
                null,
                "timer",
                null,
                null,
                null
            )
        );
        items.add(
            new com.fadcam.ui.picker.OptionItem(
                "t_15m",
                getString(R.string.timer_15_minutes),
                null,
                null,
                null,
                null,
                null,
                null,
                "timer",
                null,
                null,
                null
            )
        );
        items.add(
            new com.fadcam.ui.picker.OptionItem(
                "t_30m",
                getString(R.string.timer_30_minutes),
                null,
                null,
                null,
                null,
                null,
                null,
                "timer",
                null,
                null,
                null
            )
        );
        items.add(
            new com.fadcam.ui.picker.OptionItem(
                "t_1h",
                getString(R.string.timer_1_hour),
                null,
                null,
                null,
                null,
                null,
                null,
                "timer",
                null,
                null,
                null
            )
        );
        items.add(
            new com.fadcam.ui.picker.OptionItem(
                "t_custom",
                getString(R.string.timer_custom_label),
                null,
                null,
                null,
                null,
                null,
                null,
                "edit",
                null,
                null,
                null
            )
        );

        // Determine currently saved seconds
        int cur = com.fadcam.SharedPreferencesManager.getInstance(
            requireContext()
        ).getBackgroundPlaybackTimerSeconds();
        String selectedId = "t_off";
        if (cur == 30) selectedId = "t_30s";
        else if (cur == 60) selectedId = "t_1m";
        else if (cur == 300) selectedId = "t_5m";
        else if (cur == 900) selectedId = "t_15m";
        else if (cur == 1800) selectedId = "t_30m";
        else if (cur == 3600) selectedId = "t_1h";
        else if (cur > 0) selectedId = "t_custom"; // make custom checked when user set a non-preset value
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet =
            com.fadcam.ui.picker.PickerBottomSheetFragment.newInstance(
                getString(R.string.background_playback_timer_title_short),
                items,
                selectedId,
                RK,
                getString(R.string.background_playback_timer_helper)
            );
        getParentFragmentManager().setFragmentResultListener(
                RK,
                this,
                (rkRes, b) -> {
                    String sel = b.getString(
                        com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID
                    );
                    if (sel == null) return;
                    if ("t_custom".equals(sel)) {
                        // Show numeric input sheet: min 1 second, max e.g. 86400 (24h)
                        final String RK_NUM =
                            "rk_vps_background_playback_timer_custom";
                        com.fadcam.ui.picker.NumberInputBottomSheetFragment num =
                            com.fadcam.ui.picker.NumberInputBottomSheetFragment.newInstance(
                                getString(R.string.timer_custom_title),
                                1,
                                86400,
                                60,
                                getString(R.string.universal_enter_number),
                                5,
                                3600,
                                getString(R.string.timer_custom_low_hint),
                                getString(R.string.timer_custom_high_hint),
                                RK_NUM
                            );
                        // Provide a clear description so the sheet shows what units to enter and example format
                        android.os.Bundle _b = num.getArguments() != null
                            ? num.getArguments()
                            : new android.os.Bundle();
                        _b.putString(
                            com.fadcam.ui.picker.NumberInputBottomSheetFragment.ARG_DESCRIPTION,
                            getString(R.string.timer_custom_description)
                        );
                        _b.putBoolean(
                            com.fadcam.ui.picker.NumberInputBottomSheetFragment.ARG_ENABLE_TIMER_CALC,
                            true
                        );
                        num.setArguments(_b);
                        getParentFragmentManager().setFragmentResultListener(
                                RK_NUM,
                                this,
                                (rkN, nb) -> {
                                    int minutes = nb.getInt(
                                        com.fadcam.ui.picker.NumberInputBottomSheetFragment.RESULT_NUMBER,
                                        0
                                    );
                                    if (minutes > 0) {
                                        int seconds = minutes * 60;
                                        com.fadcam.SharedPreferencesManager.getInstance(
                                            requireContext()
                                        ).setBackgroundPlaybackTimerSeconds(
                                            seconds
                                        );
                                        View root = getView();
                                        if (root != null) {
                                            TextView sub = root.findViewById(
                                                R.id.sub_background_playback_timer
                                            );
                                            if (sub != null) sub.setText(
                                                minutes < 60
                                                    ? getString(
                                                        R.string.timer_minutes_short,
                                                        minutes
                                                    )
                                                    : getString(
                                                        R.string.timer_hours_short,
                                                        minutes / 60
                                                    )
                                            );
                                        }
                                    }
                                }
                            );
                        num.show(
                            getParentFragmentManager(),
                            "vps_background_playback_timer_custom_sheet"
                        );
                        return;
                    }
                    int seconds = 0;
                    switch (sel) {
                        case "t_30s":
                            seconds = 30;
                            break;
                        case "t_1m":
                            seconds = 60;
                            break;
                        case "t_5m":
                            seconds = 300;
                            break;
                        case "t_15m":
                            seconds = 900;
                            break;
                        case "t_30m":
                            seconds = 1800;
                            break;
                        case "t_1h":
                            seconds = 3600;
                            break;
                        default:
                            seconds = 0;
                            break;
                    }
                    com.fadcam.SharedPreferencesManager.getInstance(
                        requireContext()
                    ).setBackgroundPlaybackTimerSeconds(seconds);
                    // Update subtitle in settings UI if visible (show current timer value beside the chevron)
                    View root = getView();
                    if (root != null) {
                        TextView sub = root.findViewById(
                            R.id.sub_background_playback_timer
                        );
                        if (sub != null) {
                            if (seconds == 0) sub.setText(
                                getString(R.string.timer_off_short)
                            );
                            else if (seconds < 60) sub.setText(
                                getString(R.string.timer_seconds_short, seconds)
                            );
                            else if (seconds < 3600) sub.setText(
                                getString(
                                    R.string.timer_minutes_short,
                                    seconds / 60
                                )
                            );
                            else sub.setText(
                                getString(
                                    R.string.timer_hours_short,
                                    seconds / 3600
                                )
                            );
                        }
                    }
                }
            );
        sheet.show(
            getParentFragmentManager(),
            "vps_background_playback_timer_sheet"
        );
    }

    private void showMuteSwitchSheet() {
        final String RK = "rk_vps_mute_switch";
        boolean enabled = com.fadcam.SharedPreferencesManager.getInstance(
            requireContext()
        ).isPlaybackMuted();
        getParentFragmentManager().setFragmentResultListener(
                RK,
                this,
                (k, b) -> {
                    if (
                        b.containsKey(
                            com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SWITCH_STATE
                        )
                    ) {
                        boolean state = b.getBoolean(
                            com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SWITCH_STATE
                        );
                        com.fadcam.SharedPreferencesManager.getInstance(
                            requireContext()
                        ).setPlaybackMuted(state);
                        android.widget.Toast.makeText(
                            requireContext(),
                            state
                                ? getString(R.string.mute_on_toast)
                                : getString(R.string.mute_off_toast),
                            android.widget.Toast.LENGTH_SHORT
                        ).show();
                        // Update subtitle inline
                        View root = getView();
                        if (root != null) {
                            TextView sub = root.findViewById(
                                R.id.sub_mute_playback
                            );
                            if (sub != null) {
                                sub.setText(
                                    state
                                        ? getString(R.string.universal_enable)
                                        : getString(R.string.universal_disable)
                                );
                            }
                        }
                    }
                }
            );
        String helper = getString(R.string.mute_playback_helper_picker);
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet =
            com.fadcam.ui.picker.PickerBottomSheetFragment.newInstanceWithSwitch(
                getString(R.string.mute_playback_title),
                new java.util.ArrayList<>(),
                null,
                RK,
                helper,
                getString(R.string.mute_playback_title),
                enabled
            );
        sheet.show(getParentFragmentManager(), "vps_mute_switch_sheet");
    }

    private void showPlaybackSpeedPicker() {
        final String RK = "rk_vps_playback_speed";
        float[] speedValues = new float[] {
            0.25f,
            0.5f,
            0.6f,
            0.7f,
            0.8f,
            0.9f,
            1.0f,
            1.5f,
            2.0f,
            3.0f,
            4.0f,
            6.0f,
            8.0f,
            10.0f,
        };
        CharSequence[] labels = new CharSequence[] {
            "0.25x",
            "0.5x",
            "0.6x",
            "0.7x",
            "0.8x",
            "0.9x",
            "1x (Normal)",
            "1.5x",
            "2x",
            "3x",
            "4x",
            "6x",
            "8x",
            "10x",
        };
        java.util.ArrayList<com.fadcam.ui.picker.OptionItem> items =
            new java.util.ArrayList<>();
        for (int i = 0; i < speedValues.length; i++) {
            items.add(
                new com.fadcam.ui.picker.OptionItem(
                    "spd_" + speedValues[i],
                    String.valueOf(labels[i]),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "speed",
                    null,
                    null,
                    null
                )
            );
        }
        float current = com.fadcam.SharedPreferencesManager.getInstance(
            requireContext()
        ).sharedPreferences.getFloat("pref_default_playback_speed", 1.0f);
        String selectedId = "spd_" + current;
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet =
            com.fadcam.ui.picker.PickerBottomSheetFragment.newInstance(
                getString(R.string.playback_speed_title),
                items,
                selectedId,
                RK,
                getString(R.string.playback_speed_helper_settings)
            );
        getParentFragmentManager().setFragmentResultListener(
                RK,
                this,
                (k, b) -> {
                    String sel = b.getString(
                        com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID
                    );
                    if (sel != null && sel.startsWith("spd_")) {
                        try {
                            float val = Float.parseFloat(sel.substring(4));
                            com.fadcam.SharedPreferencesManager.getInstance(
                                requireContext()
                            )
                                .sharedPreferences.edit()
                                .putFloat("pref_default_playback_speed", val)
                                .apply();
                            android.widget.Toast.makeText(
                                requireContext(),
                                getString(
                                    R.string.toast_default_playback_speed_set,
                                    val + "x"
                                ),
                                android.widget.Toast.LENGTH_SHORT
                            ).show();
                            View root = getView();
                            if (root != null) {
                                TextView subPlayback2 = root.findViewById(
                                    R.id.sub_playback_speed
                                );
                                if (subPlayback2 != null) subPlayback2.setText(
                                    getPlaybackLabel(val)
                                );
                            }
                        } catch (NumberFormatException ignored) {}
                    }
                }
            );
        sheet.show(getParentFragmentManager(), "vps_playback_speed_picker");
    }

    private void showQuickSpeedPicker() {
        final String RK = "rk_vps_quick_speed";
        float[] speedValues = new float[] {
            0.25f,
            0.5f,
            0.6f,
            0.7f,
            0.8f,
            0.9f,
            1.0f,
            1.5f,
            2.0f,
            3.0f,
            4.0f,
            6.0f,
            8.0f,
            10.0f,
        };
        CharSequence[] labels = new CharSequence[] {
            "0.25x",
            "0.5x",
            "0.6x",
            "0.7x",
            "0.8x",
            "0.9x",
            "1x (Normal)",
            "1.5x",
            "2x",
            "3x",
            "4x",
            "6x",
            "8x",
            "10x",
        };
        java.util.ArrayList<com.fadcam.ui.picker.OptionItem> items =
            new java.util.ArrayList<>();
        for (int i = 0; i < speedValues.length; i++) {
            String title = Math.abs(speedValues[i] - 2.0f) < 0.001f
                ? getString(R.string.quick_speed_option_default)
                : String.valueOf(labels[i]);
            items.add(
                new com.fadcam.ui.picker.OptionItem(
                    "spd_" + speedValues[i],
                    title,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "bolt",
                    null,
                    null,
                    null
                )
            );
        }
        float current = com.fadcam.SharedPreferencesManager.getInstance(
            requireContext()
        ).getQuickSpeed();
        String selectedId = "spd_" + current;
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet =
            com.fadcam.ui.picker.PickerBottomSheetFragment.newInstance(
                getString(R.string.quick_speed_title),
                items,
                selectedId,
                RK,
                getString(R.string.quick_speed_helper)
            );
        getParentFragmentManager().setFragmentResultListener(
                RK,
                this,
                (k, b) -> {
                    String sel = b.getString(
                        com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID
                    );
                    if (sel != null && sel.startsWith("spd_")) {
                        try {
                            float val = Float.parseFloat(sel.substring(4));
                            com.fadcam.SharedPreferencesManager.getInstance(
                                requireContext()
                            ).setQuickSpeed(val);
                            View root = getView();
                            if (root != null) {
                                TextView subQuick = root.findViewById(
                                    R.id.sub_quick_speed
                                );
                                if (subQuick != null) subQuick.setText(
                                    formatSpeed(val)
                                );
                            }
                        } catch (NumberFormatException ignored) {}
                    }
                }
            );
        sheet.show(getParentFragmentManager(), "vps_quick_speed_picker");
    }

    private void showSeekAmountPicker() {
        final String RK = "rk_vps_seek_amount";
        java.util.ArrayList<com.fadcam.ui.picker.OptionItem> items =
            new java.util.ArrayList<>();
        items.add(
            new com.fadcam.ui.picker.OptionItem(
                "s_5",
                "5s",
                null,
                null,
                null,
                null,
                null,
                null,
                "replay_10",
                null,
                null,
                null
            )
        );
        items.add(
            new com.fadcam.ui.picker.OptionItem(
                "s_10",
                "10s",
                null,
                null,
                null,
                null,
                null,
                null,
                "replay_10",
                null,
                null,
                null
            )
        );
        items.add(
            new com.fadcam.ui.picker.OptionItem(
                "s_15",
                "15s",
                null,
                null,
                null,
                null,
                null,
                null,
                "replay_10",
                null,
                null,
                null
            )
        );
        items.add(
            new com.fadcam.ui.picker.OptionItem(
                "s_30",
                "30s",
                null,
                null,
                null,
                null,
                null,
                null,
                "replay_10",
                null,
                null,
                null
            )
        );
        items.add(
            new com.fadcam.ui.picker.OptionItem(
                "s_custom",
                getString(R.string.seek_amount_custom),
                null,
                null,
                null,
                null,
                null,
                null,
                "edit",
                null,
                null,
                null
            )
        );
        int cur = com.fadcam.SharedPreferencesManager.getInstance(
            requireContext()
        ).getPlayerSeekSeconds();
        String sel = "s_" + cur;
        if (cur != 5 && cur != 10 && cur != 15 && cur != 30) sel = "s_custom";
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet =
            com.fadcam.ui.picker.PickerBottomSheetFragment.newInstance(
                getString(R.string.seek_amount_title),
                items,
                sel,
                RK,
                getString(R.string.seek_amount_helper)
            );
        getParentFragmentManager().setFragmentResultListener(
                RK,
                this,
                (k, b) -> {
                    String s = b.getString(
                        com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID
                    );
                    if (s == null) return;
                    if ("s_custom".equals(s)) {
                        final String RK_NUM = "rk_vps_seek_amount_custom";
                        com.fadcam.ui.picker.NumberInputBottomSheetFragment num =
                            com.fadcam.ui.picker.NumberInputBottomSheetFragment.newInstance(
                                getString(R.string.seek_amount_custom_title),
                                1,
                                300,
                                10,
                                getString(R.string.universal_enter_number),
                                5,
                                60,
                                getString(R.string.seek_amount_custom_low_hint),
                                getString(
                                    R.string.seek_amount_custom_high_hint
                                ),
                                RK_NUM
                            );
                        if (num.getArguments() != null) {
                            num
                                .getArguments()
                                .putString(
                                    com.fadcam.ui.picker.NumberInputBottomSheetFragment.ARG_DESCRIPTION,
                                    getString(R.string.seek_amount_helper)
                                );
                        }
                        getParentFragmentManager().setFragmentResultListener(
                                RK_NUM,
                                this,
                                (rkN, nb) -> {
                                    int val = nb.getInt(
                                        com.fadcam.ui.picker.NumberInputBottomSheetFragment.RESULT_NUMBER,
                                        0
                                    );
                                    if (val > 0) {
                                        com.fadcam.SharedPreferencesManager.getInstance(
                                            requireContext()
                                        ).setPlayerSeekSeconds(val);
                                        TextView subSeek2 = getView() != null
                                            ? getView().findViewById(
                                                R.id.sub_seek_amount
                                            )
                                            : null;
                                        if (subSeek2 != null) subSeek2.setText(
                                            getString(
                                                R.string.seek_amount_subtitle,
                                                val
                                            )
                                        );
                                    }
                                }
                            );
                        // Dismiss parent picker then show numeric input slightly later to avoid cross-sheet visual overlap
                        try {
                            sheet.dismissAllowingStateLoss();
                        } catch (Exception ignored) {}
                        new android.os.Handler(
                            android.os.Looper.getMainLooper()
                        ).postDelayed(
                                () -> {
                                    try {
                                        num.show(
                                            getParentFragmentManager(),
                                            "vps_seek_amount_custom_sheet"
                                        );
                                    } catch (Exception ignored) {}
                                },
                                180
                            );
                        return;
                    }
                    if (s.startsWith("s_")) {
                        try {
                            int v = Integer.parseInt(s.substring(2));
                            com.fadcam.SharedPreferencesManager.getInstance(
                                requireContext()
                            ).setPlayerSeekSeconds(v);
                            TextView subSeek3 = getView() != null
                                ? getView().findViewById(R.id.sub_seek_amount)
                                : null;
                            if (subSeek3 != null) subSeek3.setText(
                                getString(R.string.seek_amount_subtitle, v)
                            );
                        } catch (NumberFormatException ignored) {}
                    }
                }
            );
        sheet.show(getParentFragmentManager(), "vps_seek_amount_sheet");
    }

    private String formatSpeed(float v) {
        try {
            java.text.DecimalFormat df = new java.text.DecimalFormat("#.##");
            return df.format(v) + "x";
        } catch (Exception e) {
            return v + "x";
        }
    }

    private String getPlaybackLabel(float v) {
        if (Math.abs(v - 0.25f) < 0.001f) return "0.25x";
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

    private void showAudioWaveformSwitchSheet() {
        final String RK = "rk_vps_audio_waveform_switch";
        boolean enabled = com.fadcam.SharedPreferencesManager.getInstance(
            requireContext()
        ).isAudioWaveformEnabled();

        getParentFragmentManager().setFragmentResultListener(
                RK,
                this,
                (key, bundle) -> {
                    if (
                        bundle.containsKey(
                            com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SWITCH_STATE
                        )
                    ) {
                        boolean state = bundle.getBoolean(
                            com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SWITCH_STATE
                        );
                        com.fadcam.SharedPreferencesManager.getInstance(
                            requireContext()
                        ).setAudioWaveformEnabled(state);

                        View root = getView();
                        if (root != null) {
                            TextView sub = root.findViewById(
                                R.id.sub_audio_waveform
                            );
                            if (sub != null) {
                                sub.setText(
                                    state
                                        ? getString(R.string.universal_enable)
                                        : getString(R.string.universal_disable)
                                );
                            }
                        }
                    }
                }
            );

        java.util.ArrayList<com.fadcam.ui.picker.OptionItem> items =
            new java.util.ArrayList<>(); // No options needed, switch only
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet =
            com.fadcam.ui.picker.PickerBottomSheetFragment.newInstanceWithSwitch(
                getString(R.string.audio_waveform_title),
                items,
                "",
                RK,
                getString(R.string.waveform_helper_text),
                getString(R.string.waveform_switch_label),
                enabled
            );
        sheet.show(
            getParentFragmentManager(),
            "vps_audio_waveform_switch_sheet"
        );
    }
}
