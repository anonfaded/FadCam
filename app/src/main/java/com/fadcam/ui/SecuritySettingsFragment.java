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
    private Boolean pendingToggleDesiredState; // track attempted switch state for rollback on cancel

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings_security, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // -------------- Fix Start for this method(onViewCreated)-----------
        sharedPreferencesManager = SharedPreferencesManager.getInstance(requireContext());
        valueTabLock = view.findViewById(R.id.value_tab_lock_status);
    valueCloakRecents = view.findViewById(R.id.value_cloak_recents_status);
        refreshAppLockValue();
    refreshCloakRecentsValue();

        View back = view.findViewById(R.id.back_button);
        if (back != null) {
            back.setOnClickListener(v -> OverlayNavUtil.dismiss(requireActivity()));
        }

        // Configure row click opens the config dialog (replaces legacy configure button)
        View row = view.findViewById(R.id.row_tab_lock);
        if (row != null) {
            row.setOnClickListener(v -> showAppLockConfigDialog());
        }
        View rowCloak = view.findViewById(R.id.row_cloak_recents);
        if (rowCloak != null) {
            rowCloak.setOnClickListener(v -> showCloakRecentsConfig());
        }
        // -------------- Fix Ended for this method(onViewCreated)-----------
    }

    // Removed duplicate manual back handling; centralized via OverlayNavUtil

    private void refreshAppLockValue() {
        // -------------- Fix Start for this method(refreshAppLockValue)-----------
        if (valueTabLock != null) {
            boolean enabled = sharedPreferencesManager.isAppLockEnabled();
            // Use existing universal enable/disable strings for consistency
            valueTabLock.setText(enabled ? getString(R.string.universal_enable) : getString(R.string.universal_disable));
        }
        // -------------- Fix Ended for this method(refreshAppLockValue)-----------
    }

    private void refreshCloakRecentsValue() {
        // -------------- Fix Start for this method(refreshCloakRecentsValue)-----------
        if (valueCloakRecents != null) {
            boolean enabled = sharedPreferencesManager.isCloakRecentsEnabled();
            valueCloakRecents.setText(enabled ? getString(R.string.universal_enable) : getString(R.string.universal_disable));
        }
        // -------------- Fix Ended for this method(refreshCloakRecentsValue)-----------
    }

    // Method to show the AppLock configuration dialog
    private void showAppLockConfigDialog() {
        // -------------- Fix Start for this method(showAppLockConfigDialog)-----------
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
        // -------------- Fix Ended for this method(showAppLockConfigDialog)-----------
    }

    private void showCloakRecentsConfig() {
        // -------------- Fix Start for this method(showCloakRecentsConfig)-----------
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
                // -------------- Fix Start: apply cloak instantly without restart -----------
                try {
                    android.app.Activity act = requireActivity();
                    if (act instanceof com.fadcam.MainActivity) {
                        ((com.fadcam.MainActivity) act).applyCloakPreferenceNow(state);
                    }
                } catch (Throwable ignored) {}
                // -------------- Fix End: apply cloak instantly without restart -----------
            }
        });

        com.fadcam.ui.picker.PickerBottomSheetFragment sheet = com.fadcam.ui.picker.PickerBottomSheetFragment
                .newInstanceWithSwitch(getString(R.string.setting_cloak_recents_title), items, null, resultKey, helper,
                        getString(R.string.setting_cloak_recents_switch), enabled);
        sheet.show(getParentFragmentManager(), "cloak_recents_sheet");
        // -------------- Fix Ended for this method(showCloakRecentsConfig)-----------
    }

    private void row_tab_lock_postRefreshOpen(){
        // -------------- Fix Start for this method(row_tab_lock_postRefreshOpen)-----------
        refreshAppLockValue();
        // Re-open sheet after critical structural change (PIN removed) to reflect new UI state
        row_tab_lock_postDelayed();
        // -------------- Fix Ended for this method(row_tab_lock_postRefreshOpen)-----------
    }
    private void row_tab_lock_postDelayed(){
        // -------------- Fix Start for this method(row_tab_lock_postDelayed)-----------
        View view = getView();
        if(view!=null){ view.postDelayed(this::showAppLockConfigDialog, 150); }
        // -------------- Fix Ended for this method(row_tab_lock_postDelayed)-----------
    }

    private void handleEnableDisable(boolean desired){
        // -------------- Fix Start for this method(handleEnableDisable)-----------
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
        // -------------- Fix Ended for this method(handleEnableDisable)-----------
    }

    private void verifyPinThenExecute(Runnable action, int titleResId) {
        // -------------- Fix Start for this method(verifyPinThenExecute)-----------
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
        // -------------- Fix Ended for this method(verifyPinThenExecute)-----------
    }

    private void revertSwitchUIAfterCancel(){
        // -------------- Fix Start for this method(revertSwitchUIAfterCancel)-----------
        pendingToggleDesiredState = null; // discard
        // Dismiss existing sheet if present
        androidx.fragment.app.Fragment existing = getParentFragmentManager().findFragmentByTag("applock_sheet");
        if(existing instanceof com.fadcam.ui.picker.PickerBottomSheetFragment){
            ((com.fadcam.ui.picker.PickerBottomSheetFragment) existing).dismiss();
        }
        // Reopen with correct current state
        View root = getView();
        if(root!=null){ root.postDelayed(this::showAppLockConfigDialog, 120); }
        // -------------- Fix Ended for this method(revertSwitchUIAfterCancel)-----------
    }

    private void showPinCreationDialog(boolean enableAfterCreation) {
        // -------------- Fix Start for this method(showPinCreationDialog)-----------
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
        // -------------- Fix Ended for this method(showPinCreationDialog)-----------
    }

    private void setAppLockEnabled(boolean enabled) {
        // -------------- Fix Start for this method(setAppLockEnabled)-----------
        sharedPreferencesManager.setAppLockEnabled(enabled);
        String message = enabled ? getString(R.string.applock_enable) + " " + getString(R.string.universal_ok)
                : getString(R.string.applock_disable) + " " + getString(R.string.universal_ok);
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
        refreshAppLockValue();
        // -------------- Fix Ended for this method(setAppLockEnabled)-----------
    }
}
