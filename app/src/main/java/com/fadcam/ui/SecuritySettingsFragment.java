package com.fadcam.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.Constants;
import com.fadcam.MainActivity;
import com.fadcam.ui.OverlayNavUtil;

import com.guardanis.applock.AppLock;
import com.guardanis.applock.dialogs.LockCreationDialogBuilder;
import com.guardanis.applock.dialogs.UnlockDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * SecuritySettingsFragment
 * EXACT migration of legacy AppLock (Tab Lock) logic from monolithic SettingsFragment.
 */
public class SecuritySettingsFragment extends Fragment {

    private SharedPreferencesManager sharedPreferencesManager;
    private TextView valueTabLock;

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
        refreshAppLockValue();

        View back = view.findViewById(R.id.back_button);
        if (back != null) {
            back.setOnClickListener(v -> OverlayNavUtil.dismiss(requireActivity()));
        }

        // Configure row click opens the config dialog (replaces legacy configure button)
        View row = view.findViewById(R.id.row_tab_lock);
        if (row != null) {
            row.setOnClickListener(v -> showAppLockConfigDialog());
        }
        // -------------- Fix Ended for this method(onViewCreated)-----------
    }

    // Removed duplicate manual back handling; centralized via OverlayNavUtil

    private void refreshAppLockValue() {
        // -------------- Fix Start for this method(refreshAppLockValue)-----------
        if (valueTabLock != null) {
            boolean enabled = sharedPreferencesManager.isAppLockEnabled();
            valueTabLock.setText(enabled ? "Enabled" : "Disabled");
        }
        // -------------- Fix Ended for this method(refreshAppLockValue)-----------
    }

    // Method to show the AppLock configuration dialog
    private void showAppLockConfigDialog() {
        // -------------- Fix Start for this method(showAppLockConfigDialog)-----------
        boolean isEnrolled = AppLock.isEnrolled(requireContext());
        boolean isEnabled = sharedPreferencesManager.isAppLockEnabled();
        List<String> options = new ArrayList<>();
        if (isEnabled) {
            options.add(getString(R.string.applock_disable));
        } else {
            options.add(getString(R.string.applock_enable));
        }
        if (!isEnrolled) {
            options.add(getString(R.string.applock_set_pin));
        } else {
            options.add(getString(R.string.applock_change_pin));
            options.add(getString(R.string.applock_remove_pin));
        }

        // Theme parity with legacy (Snow Veil detection)
        String currentTheme = sharedPreferencesManager.sharedPreferences.getString(Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);
        boolean isSnowVeilTheme = "Snow Veil".equals(currentTheme);
        int color = ContextCompat.getColor(requireContext(), isSnowVeilTheme ? android.R.color.black : android.R.color.white);

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(requireContext(), android.R.layout.simple_list_item_1, options) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView text1 = view.findViewById(android.R.id.text1);
                if (text1 != null) text1.setTextColor(color);
                return view;
            }
        };

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.applock_dialog_title)
                .setAdapter(adapter, (dialogInterface, which) -> {
                    String selectedOption = options.get(which);
                    if (selectedOption.equals(getString(R.string.applock_enable))) {
                        if (isEnrolled) {
                            verifyPinThenExecute(() -> setAppLockEnabled(true), R.string.applock_verify_to_enable);
                        } else {
                            showPinCreationDialog(true);
                        }
                    } else if (selectedOption.equals(getString(R.string.applock_disable))) {
                        verifyPinThenExecute(() -> setAppLockEnabled(false), R.string.applock_verify_to_disable);
                    } else if (selectedOption.equals(getString(R.string.applock_set_pin)) || selectedOption.equals(getString(R.string.applock_change_pin))) {
                        if (isEnrolled) {
                            verifyPinThenExecute(() -> showPinCreationDialog(isEnabled), R.string.applock_verify_to_change);
                        } else {
                            showPinCreationDialog(isEnabled);
                        }
                    } else if (selectedOption.equals(getString(R.string.applock_remove_pin))) {
                        verifyPinThenExecute(() -> {
                            AppLock appLock = AppLock.getInstance(requireContext());
                            appLock.invalidateEnrollments();
                            setAppLockEnabled(false);
                            Toast.makeText(requireContext(), R.string.applock_pin_removed, Toast.LENGTH_SHORT).show();
                        }, R.string.applock_verify_to_remove);
                    }
                })
                .show();
        // -------------- Fix Ended for this method(showAppLockConfigDialog)-----------
    }

    private void verifyPinThenExecute(Runnable action, int titleResId) {
        // -------------- Fix Start for this method(verifyPinThenExecute)-----------
        Toast.makeText(requireContext(), getString(titleResId), Toast.LENGTH_SHORT).show();
        new UnlockDialogBuilder(requireActivity())
                .onUnlocked(() -> {
                    if (action != null) action.run();
                    refreshAppLockValue();
                })
                .onCanceled(() -> Toast.makeText(requireContext(), R.string.applock_verification_canceled, Toast.LENGTH_SHORT).show())
                .show();
        // -------------- Fix Ended for this method(verifyPinThenExecute)-----------
    }

    private void showPinCreationDialog(boolean enableAfterCreation) {
        // -------------- Fix Start for this method(showPinCreationDialog)-----------
        new LockCreationDialogBuilder(requireActivity())
                .onCanceled(() -> { /* no-op */ })
                .onLockCreated(() -> {
                    if (enableAfterCreation) {
                        setAppLockEnabled(true);
                    }
                    Toast.makeText(requireContext(), R.string.applock_pin_created, Toast.LENGTH_SHORT).show();
                    refreshAppLockValue();
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
