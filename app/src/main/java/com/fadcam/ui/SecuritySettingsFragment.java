package com.fadcam.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ImageView; // layout icons

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentResultListener;
import androidx.fragment.app.Fragment;

import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.ui.OverlayNavUtil;

import com.guardanis.applock.AppLock;
import com.guardanis.applock.dialogs.LockCreationDialogBuilder;
import com.guardanis.applock.dialogs.UnlockDialogBuilder;

import java.util.ArrayList;
import java.util.List; // legacy leftover (may not be used)

/**
 * SecuritySettingsFragment
 * EXACT migration of legacy AppLock (Tab Lock) logic from monolithic SettingsFragment.
 */
public class SecuritySettingsFragment extends Fragment {

    private SharedPreferencesManager sharedPreferencesManager;
    private TextView valueTabLock;
    private TextView valueCloakRecents;
    private TextView valuePrivacyBlackMode;
    private Boolean pendingToggleDesiredState; // track attempted switch state for rollback on cancel

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings_security, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        sharedPreferencesManager = SharedPreferencesManager.getInstance(requireContext());
        valueTabLock = view.findViewById(R.id.value_tab_lock_status);
    valueCloakRecents = view.findViewById(R.id.value_cloak_recents_status);
        valuePrivacyBlackMode = view.findViewById(R.id.value_privacy_black_status);
        refreshAppLockValue();
    refreshCloakRecentsValue();
        refreshPrivacyBlackModeValue();

        View back = view.findViewById(R.id.back_button);
        if (back != null) {
            back.setOnClickListener(v -> OverlayNavUtil.dismiss(requireActivity()));
        }

        // Configure row click opens the config dialog (replaces legacy configure button)
        View rowTabLock = view.findViewById(R.id.row_tab_lock);
        if (rowTabLock != null) {
            rowTabLock.setOnClickListener(v -> showAppLockConfigDialog());
        }
        View rowCloak = view.findViewById(R.id.row_cloak_recents);
        if (rowCloak != null) {
            rowCloak.setOnClickListener(v -> showCloakRecentsConfig());
        }
        View rowPrivacyBlack = view.findViewById(R.id.row_privacy_black);
        if (rowPrivacyBlack != null) {
            rowPrivacyBlack.setOnClickListener(v -> showPrivacyBlackConfig());
        }
    }

    // Removed duplicate manual back handling; centralized via OverlayNavUtil

    private void refreshAppLockValue() {
        if (valueTabLock != null) {
            boolean enabled = sharedPreferencesManager.isAppLockEnabled();
            // Use existing universal enable/disable strings for consistency
            valueTabLock.setText(enabled ? getString(R.string.universal_enable) : getString(R.string.universal_disable));
        }
    }

    private void refreshCloakRecentsValue() {
        if (valueCloakRecents != null) {
            boolean enabled = sharedPreferencesManager.isCloakRecentsEnabled();
            valueCloakRecents.setText(enabled ? getString(R.string.universal_enable) : getString(R.string.universal_disable));
        }
    }

    private void refreshPrivacyBlackModeValue() {
        if (valuePrivacyBlackMode != null) {
            boolean enabled = sharedPreferencesManager.isPrivacyBlackModeEnabled();
            valuePrivacyBlackMode.setText(enabled ? getString(R.string.universal_enable) : getString(R.string.universal_disable));
        }
    }

    // Method to show the AppLock configuration dialog
    private void showAppLockConfigDialog() {
        boolean isEnrolled = AppLock.isEnrolled(requireContext());
        boolean isEnabled = sharedPreferencesManager.isAppLockEnabled();
        ArrayList<com.fadcam.ui.picker.OptionItem> items = new ArrayList<>();
        // Contextual helper text
        String helper;
        if(!isEnrolled){
            helper = getString(R.string.applock_helper_create);
            // Single action: Set PIN & Enable (no switch shown yet because enabling requires PIN)
        } else if(isEnabled){
            helper = getString(R.string.applock_helper_enabled);
        } else { // enrolled but disabled
            helper = getString(R.string.applock_helper_manage_disabled);
        }

        boolean showSwitch = isEnrolled; // Only show switch after a PIN exists
        String switchTitle = getString(R.string.setting_applock_title);
        String resultKey = "applock_sheet_result";

        // Build option list based on state
        if(!isEnrolled){
            items.add(new com.fadcam.ui.picker.OptionItem("set_pin_enable", getString(R.string.applock_set_pin_enable)));
        } else {
            items.add(new com.fadcam.ui.picker.OptionItem("change_pin", getString(R.string.applock_change_pin)));
            items.add(new com.fadcam.ui.picker.OptionItem("remove_pin", getString(R.string.applock_remove_pin)));
        }

        getParentFragmentManager().setFragmentResultListener(resultKey, this, (requestKey, bundle) -> {
            if(bundle.containsKey(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SWITCH_STATE)){
                boolean newState = bundle.getBoolean(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SWITCH_STATE);
                handleEnableDisable(newState);
            }
            if(bundle.containsKey(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID)){
                String action = bundle.getString(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
                if(action!=null){
                    switch(action){
                        case "set_pin_enable":
                            // Create PIN then auto-enable
                            showPinCreationDialog(true);
                            break;
                        case "change_pin":
                            verifyPinThenExecute(() -> showPinCreationDialog(isEnabled), R.string.applock_verify_to_change);
                            break;
                        case "remove_pin":
                            verifyPinThenExecute(() -> {
                                AppLock appLock = AppLock.getInstance(requireContext());
                                appLock.invalidateEnrollments();
                                setAppLockEnabled(false);
                                Toast.makeText(requireContext(), R.string.applock_pin_removed, Toast.LENGTH_SHORT).show();
                                // Re-open sheet with updated state for clarity
                                row_tab_lock_postRefreshOpen();
                            }, R.string.applock_verify_to_remove);
                            break;
                    }
                }
            }
        });

        com.fadcam.ui.picker.PickerBottomSheetFragment sheet;
    if(showSwitch){
        // Dependent option ids that should be disabled when switch is OFF
        java.util.ArrayList<String> deps = new java.util.ArrayList<>();
        for(com.fadcam.ui.picker.OptionItem oi: items){ deps.add(oi.id); }
        sheet = com.fadcam.ui.picker.PickerBottomSheetFragment.newInstanceWithSwitchDependencies(
            getString(R.string.applock_dialog_title),
            items,
            null,
            resultKey,
            helper,
            switchTitle,
            isEnabled,
            deps
        );
        } else {
            sheet = com.fadcam.ui.picker.PickerBottomSheetFragment.newInstance(
                    getString(R.string.applock_dialog_title),
                    items,
                    null,
                    resultKey,
                    helper
            );
        }
        sheet.show(getParentFragmentManager(), "applock_sheet");
    }

    private void showCloakRecentsConfig() {
        String resultKey = "cloak_recents_sheet_result";
        String helper = getString(R.string.setting_cloak_recents_helper);
        boolean enabled = sharedPreferencesManager.isCloakRecentsEnabled();

        // no additional items yet, just the master switch
        java.util.ArrayList<com.fadcam.ui.picker.OptionItem> items = new java.util.ArrayList<>();

        getParentFragmentManager().setFragmentResultListener(resultKey, this, (requestKey, bundle) -> {
            if(bundle.containsKey(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SWITCH_STATE)){
                boolean state = bundle.getBoolean(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SWITCH_STATE);
                sharedPreferencesManager.setCloakRecentsEnabled(state);
                refreshCloakRecentsValue();
                Toast.makeText(requireContext(), state ? R.string.setting_enabled_msg : R.string.setting_disabled_msg, Toast.LENGTH_SHORT).show();
                try {
                    android.app.Activity act = requireActivity();
                    if (act instanceof com.fadcam.MainActivity) {
                        ((com.fadcam.MainActivity) act).applyCloakPreferenceNow(state);
                    }
                } catch (Throwable ignored) {}
            }
        });

        com.fadcam.ui.picker.PickerBottomSheetFragment sheet = com.fadcam.ui.picker.PickerBottomSheetFragment
                .newInstanceWithSwitch(getString(R.string.setting_cloak_recents_title), items, null, resultKey, helper,
                        getString(R.string.setting_cloak_recents_switch), enabled);
        sheet.show(getParentFragmentManager(), "cloak_recents_sheet");
    }

    private void showPrivacyBlackConfig() {
        String resultKey = "privacy_black_sheet_result";
        String helper = getString(R.string.privacy_black_mode_helper);
        boolean enabled = sharedPreferencesManager.isPrivacyBlackModeEnabled();

        java.util.ArrayList<com.fadcam.ui.picker.OptionItem> items = new java.util.ArrayList<>();
        items.add(new com.fadcam.ui.picker.OptionItem("exit_methods", getString(R.string.privacy_black_gestures_title), null, null, null, R.drawable.ic_arrow_right, false, false, null));

        getParentFragmentManager().setFragmentResultListener(resultKey, this, (requestKey, bundle) -> {
            if(bundle.containsKey(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SWITCH_STATE)){
                boolean state = bundle.getBoolean(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SWITCH_STATE);
                sharedPreferencesManager.setPrivacyBlackModeEnabled(state);
                refreshPrivacyBlackModeValue();
                Toast.makeText(requireContext(), state ? R.string.setting_enabled_msg : R.string.setting_disabled_msg, Toast.LENGTH_SHORT).show();
            }
            if(bundle.containsKey(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID)){
                String action = bundle.getString(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
                if ("exit_methods".equals(action)) {
                    showPrivacyBlackGesturesConfig();
                }
            }
        });

        com.fadcam.ui.picker.PickerBottomSheetFragment sheet = com.fadcam.ui.picker.PickerBottomSheetFragment
                .newInstanceWithSwitch(getString(R.string.privacy_black_mode_title), items, null, resultKey, helper,
                        getString(R.string.privacy_black_mode_enable_title), enabled, true);
        sheet.show(getParentFragmentManager(), "privacy_black_sheet");
    }

    private void showPrivacyBlackGesturesConfig() {
        String resultKey = "privacy_black_gestures_result";
        String helper = getString(R.string.privacy_black_gestures_helper);

        java.util.ArrayList<com.fadcam.ui.picker.OptionItem> items = new java.util.ArrayList<>();
        items.add(new com.fadcam.ui.picker.OptionItem("gesture_swipe", getString(R.string.privacy_black_gesture_swipe), null, null, null, null, true, sharedPreferencesManager.isPrivacyBlackSwipeUpEnabled(), null));
        items.add(new com.fadcam.ui.picker.OptionItem("gesture_triple", getString(R.string.privacy_black_gesture_triple), null, null, null, null, true, sharedPreferencesManager.isPrivacyBlackTripleTapEnabled(), null));
        items.add(new com.fadcam.ui.picker.OptionItem("gesture_long", getString(R.string.privacy_black_gesture_long), null, null, null, null, true, sharedPreferencesManager.isPrivacyBlackLongPressEnabled(), null));

        getParentFragmentManager().setFragmentResultListener(resultKey, this, (requestKey, bundle) -> {
            if(bundle.containsKey(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID)){
                String action = bundle.getString(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
                
                int enabledCount = 0;
                if (sharedPreferencesManager.isPrivacyBlackSwipeUpEnabled()) enabledCount++;
                if (sharedPreferencesManager.isPrivacyBlackTripleTapEnabled()) enabledCount++;
                if (sharedPreferencesManager.isPrivacyBlackLongPressEnabled()) enabledCount++;

                boolean blocked = false;
                if ("gesture_swipe".equals(action)) {
                    boolean current = sharedPreferencesManager.isPrivacyBlackSwipeUpEnabled();
                    if (current && enabledCount <= 1) {
                        blocked = true;
                    } else {
                        sharedPreferencesManager.setPrivacyBlackSwipeUpEnabled(!current);
                    }
                } else if ("gesture_triple".equals(action)) {
                    boolean current = sharedPreferencesManager.isPrivacyBlackTripleTapEnabled();
                    if (current && enabledCount <= 1) {
                        blocked = true;
                    } else {
                        sharedPreferencesManager.setPrivacyBlackTripleTapEnabled(!current);
                    }
                } else if ("gesture_long".equals(action)) {
                    boolean current = sharedPreferencesManager.isPrivacyBlackLongPressEnabled();
                    if (current && enabledCount <= 1) {
                        blocked = true;
                    } else {
                        sharedPreferencesManager.setPrivacyBlackLongPressEnabled(!current);
                    }
                }

                if (blocked) {
                    Toast.makeText(requireContext(), R.string.privacy_black_min_one_method, Toast.LENGTH_SHORT).show();
                    // Refresh current sheet to revert the visual switch toggle by dismissing and showing again
                    androidx.fragment.app.Fragment f = getParentFragmentManager().findFragmentByTag("privacy_black_gestures_sheet");
                    if (f instanceof androidx.fragment.app.DialogFragment) {
                        ((androidx.fragment.app.DialogFragment) f).dismiss();
                    }
                    showPrivacyBlackGesturesConfig();
                }
            }
        });

        com.fadcam.ui.picker.PickerBottomSheetFragment sheet = com.fadcam.ui.picker.PickerBottomSheetFragment
                .newInstance(getString(R.string.privacy_black_gestures_title), items, null, resultKey, helper, true);
        sheet.show(getParentFragmentManager(), "privacy_black_gestures_sheet");
    }

    private void row_tab_lock_postRefreshOpen(){
        refreshAppLockValue();
        // Re-open sheet after critical structural change (PIN removed) to reflect new UI state
        row_tab_lock_postDelayed();
    }
    private void row_tab_lock_postDelayed(){
        View view = getView();
        if(view!=null){ view.postDelayed(this::showAppLockConfigDialog, 150); }
    }

    private void handleEnableDisable(boolean desired){
        boolean currently = sharedPreferencesManager.isAppLockEnabled();
        if(desired == currently) return; // no change
        boolean isEnrolled = AppLock.isEnrolled(requireContext());
    if(desired){
            if(isEnrolled){
        pendingToggleDesiredState = desired;
        verifyPinThenExecute(() -> setAppLockEnabled(true), R.string.applock_verify_to_enable);
            } else {
                showPinCreationDialog(true);
            }
        } else {
        pendingToggleDesiredState = desired;
        verifyPinThenExecute(() -> setAppLockEnabled(false), R.string.applock_verify_to_disable);
        }
    }

    private void verifyPinThenExecute(Runnable action, int titleResId) {
        Toast.makeText(requireContext(), getString(titleResId), Toast.LENGTH_SHORT).show();
        new UnlockDialogBuilder(requireActivity())
                .onUnlocked(() -> {
                    if (action != null) action.run();
                    pendingToggleDesiredState = null; // finalized
                    refreshAppLockValue();
                })
                .onCanceled(() -> {
                    Toast.makeText(requireContext(), R.string.applock_verification_canceled, Toast.LENGTH_SHORT).show();
                    // Revert UI (switch already flipped visually) by closing & reopening sheet with actual state
                    revertSwitchUIAfterCancel();
                })
                .show();
    }

    private void revertSwitchUIAfterCancel(){
        pendingToggleDesiredState = null; // discard
        // Dismiss existing sheet if present
        androidx.fragment.app.Fragment existing = getParentFragmentManager().findFragmentByTag("applock_sheet");
        if(existing instanceof com.fadcam.ui.picker.PickerBottomSheetFragment){
            ((com.fadcam.ui.picker.PickerBottomSheetFragment) existing).dismiss();
        }
        // Reopen with correct current state
        View root = getView();
        if(root!=null){ root.postDelayed(this::showAppLockConfigDialog, 120); }
    }

    private void showPinCreationDialog(boolean enableAfterCreation) {
        new LockCreationDialogBuilder(requireActivity())
                .onCanceled(() -> { /* no-op */ })
                .onLockCreated(() -> {
                    // Always enable immediately after creating a PIN when invoked from sheet
                    if(enableAfterCreation) setAppLockEnabled(true); else setAppLockEnabled(true);
                    Toast.makeText(requireContext(), R.string.applock_pin_created, Toast.LENGTH_SHORT).show();
                    refreshAppLockValue();
                    // After first creation, reopen config for further actions (change/remove)
                    row_tab_lock_postDelayed();
                })
                .show();
    }

    private void setAppLockEnabled(boolean enabled) {
        sharedPreferencesManager.setAppLockEnabled(enabled);
        String message = enabled ? getString(R.string.applock_enable) + " " + getString(R.string.universal_ok)
                : getString(R.string.applock_disable) + " " + getString(R.string.universal_ok);
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
        refreshAppLockValue();
    }
}
