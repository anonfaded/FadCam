package com.fadcam.ui;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.fadcam.R;
import com.github.appintro.SlidePolicy;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

/**
 * Onboarding slide 03: permission clearance.
 *
 * <p>Each permission tile shows its own state, and the action area is split into a
 * permission block and a battery block. Completed states are compact rows with a
 * checkmark; only genuine actions stay as buttons.</p>
 */
public class OnboardingPermissionsFragment extends Fragment implements SlidePolicy {

    private static final String PREFS = "onboarding_permissions";
    private static final String KEY_REQUESTED = "requested_before";

    private boolean permissionsGranted = false;
    private boolean permanentlyDenied = false;

    private MaterialButton grantButton;
    private MaterialButton batteryButton;
    private View permissionSuccessRow;
    private View batterySuccessRow;
    private TextView openSettingsLink;
    private TextView noteText;
    private TextView cameraStatus;
    private TextView micStatus;
    private TextView storageStatus;

    private final androidx.activity.result.ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(
                    new androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
                    result -> checkPermissionsAndUpdateUI());

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.onboarding_permissions_slide, container, false);

        grantButton = view.findViewById(R.id.permissions_grant_button);
        batteryButton = view.findViewById(R.id.disable_battery_optimization_button);
        permissionSuccessRow = view.findViewById(R.id.permission_success_row);
        batterySuccessRow = view.findViewById(R.id.battery_success_row);
        openSettingsLink = view.findViewById(R.id.open_settings_link);
        noteText = view.findViewById(R.id.permissionsNote);
        cameraStatus = view.findViewById(R.id.permission_status_camera);
        micStatus = view.findViewById(R.id.permission_status_microphone);
        storageStatus = view.findViewById(R.id.permission_status_storage);

        grantButton.setOnClickListener(v -> {
            // Android only shows the dialog once per install. After a hard denial
            // the button must still lead somewhere: send the user to Settings.
            if (permanentlyDenied && hasRequestedBefore()) {
                showManualPermissionDialog();
            } else {
                requestAllPermissionsAlways();
            }
        });

        openSettingsLink.setOnClickListener(v -> showManualPermissionDialog());

        batteryButton.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + requireContext().getPackageName()));
            startActivity(intent);
        });

        checkPermissionsAndUpdateUI();
        return view;
    }

    /** Requests every permission the app needs; the system ignores ones already granted. */
    private boolean hasRequestedBefore() {
        SharedPreferences prefs = requireContext()
                .getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_REQUESTED, false);
    }

    private void markRequested() {
        requireContext().getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_REQUESTED, true).apply();
    }

    private void requestAllPermissionsAlways() {
        markRequested();
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.CAMERA);
        permissions.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO);
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        permissionLauncher.launch(permissions.toArray(new String[0]));
    }

    private boolean isGranted(String permission) {
        return ContextCompat.checkSelfPermission(requireContext(), permission)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean storageGranted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return isGranted(Manifest.permission.READ_MEDIA_VIDEO);
        }
        return isGranted(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                && isGranted(Manifest.permission.READ_EXTERNAL_STORAGE);
    }

    private boolean isAnyPermissionPermanentlyDenied() {
        if (getActivity() == null) return false;
        if (!isGranted(Manifest.permission.CAMERA)
                && !ActivityCompat.shouldShowRequestPermissionRationale(
                getActivity(), Manifest.permission.CAMERA)) return true;
        if (!isGranted(Manifest.permission.RECORD_AUDIO)
                && !ActivityCompat.shouldShowRequestPermissionRationale(
                getActivity(), Manifest.permission.RECORD_AUDIO)) return true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!isGranted(Manifest.permission.READ_MEDIA_VIDEO)
                    && !ActivityCompat.shouldShowRequestPermissionRationale(
                    getActivity(), Manifest.permission.READ_MEDIA_VIDEO)) return true;
            if (!isGranted(Manifest.permission.POST_NOTIFICATIONS)
                    && !ActivityCompat.shouldShowRequestPermissionRationale(
                    getActivity(), Manifest.permission.POST_NOTIFICATIONS)) return true;
        } else {
            if (!isGranted(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    && !ActivityCompat.shouldShowRequestPermissionRationale(
                    getActivity(), Manifest.permission.WRITE_EXTERNAL_STORAGE)) return true;
            if (!isGranted(Manifest.permission.READ_EXTERNAL_STORAGE)
                    && !ActivityCompat.shouldShowRequestPermissionRationale(
                    getActivity(), Manifest.permission.READ_EXTERNAL_STORAGE)) return true;
        }
        return false;
    }

    private void showManualPermissionDialog() {
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.permissions_required)
                .setMessage(R.string.permissions_description)
                .setPositiveButton(R.string.onboarding_open_settings, (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.parse("package:" + requireContext().getPackageName()));
                    startActivity(intent);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void checkPermissionsAndUpdateUI() {
        boolean camera = isGranted(Manifest.permission.CAMERA);
        boolean mic = isGranted(Manifest.permission.RECORD_AUDIO);
        boolean storage = storageGranted();

        permissionsGranted = camera && mic && storage;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsGranted = permissionsGranted
                    && isGranted(Manifest.permission.POST_NOTIFICATIONS);
        }
        permanentlyDenied = isAnyPermissionPermanentlyDenied();

        bindPermissionState(cameraStatus, camera);
        bindPermissionState(micStatus, mic);
        bindPermissionState(storageStatus, storage);

        grantButton.setVisibility(permissionsGranted ? View.GONE : View.VISIBLE);
        permissionSuccessRow.setVisibility(permissionsGranted ? View.VISIBLE : View.GONE);
        // Always keep the manual escape hatch while anything is missing: on some
        // devices the runtime dialog never appears again after a denial.
        openSettingsLink.setVisibility(permissionsGranted ? View.GONE : View.VISIBLE);
        noteText.setVisibility(permissionsGranted ? View.GONE : View.VISIBLE);

        updateBatteryState();

        if (getActivity() instanceof OnboardingActivity) {
            ((OnboardingActivity) getActivity()).onPermissionsStateChanged();
        }
    }

    private void bindPermissionState(TextView status, boolean granted) {
        status.setText(granted
                ? R.string.onboarding_permission_granted
                : R.string.onboarding_permission_required);
        status.setTextColor(ContextCompat.getColor(requireContext(),
                granted ? R.color.onboarding_success : R.color.dossier_muted));
    }

    private void updateBatteryState() {
        android.os.PowerManager pm = (android.os.PowerManager)
                requireContext().getSystemService(android.content.Context.POWER_SERVICE);
        boolean ignoring = pm != null
                && pm.isIgnoringBatteryOptimizations(requireContext().getPackageName());

        batteryButton.setVisibility(ignoring ? View.GONE : View.VISIBLE);
        batterySuccessRow.setVisibility(ignoring ? View.VISIBLE : View.GONE);
    }

    /** The permission request must wait until this slide is actually visible. */
    public void onSlideShown() {
        checkPermissionsAndUpdateUI();
        // Ask automatically only the first time this install reaches the slide.
        if (!permissionsGranted && !hasRequestedBefore()) {
            requestAllPermissionsAlways();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        checkPermissionsAndUpdateUI();
    }

    @Override
    public boolean isPolicyRespected() {
        return permissionsGranted;
    }

    @Override
    public void onUserIllegallyRequestedNextPage() {
        Toast.makeText(requireContext(), R.string.permissions_note, Toast.LENGTH_SHORT).show();
        if (!permissionsGranted && grantButton != null
                && grantButton.getVisibility() == View.VISIBLE) {
            grantButton.performClick();
        }
    }

    /** Refreshes language-specific text without recreating the fragment. */
    public void refreshLanguage() {
        View view = getView();
        if (view == null) return;

        TextView title = view.findViewById(R.id.permissionsTitle);
        if (title != null) title.setText(R.string.permissions_required);
        TextView description = view.findViewById(R.id.permissionsDescription);
        if (description != null) description.setText(R.string.permissions_description);
        if (grantButton != null) grantButton.setText(R.string.grant_permissions);
        if (batteryButton != null) batteryButton.setText(R.string.disable_battery_optimization);
        if (openSettingsLink != null) openSettingsLink.setText(R.string.onboarding_open_settings);
        if (noteText != null) noteText.setText(R.string.permissions_note);

        TextView cameraLabel = view.findViewById(R.id.permission_camera_label);
        if (cameraLabel != null) cameraLabel.setText(R.string.onboarding_camera);
        TextView micLabel = view.findViewById(R.id.permission_microphone_label);
        if (micLabel != null) micLabel.setText(R.string.onboarding_microphone);
        TextView storageLabel = view.findViewById(R.id.permission_storage_label);
        if (storageLabel != null) storageLabel.setText(R.string.onboarding_storage);

        checkPermissionsAndUpdateUI();

        view.invalidate();
        view.requestLayout();
    }
}
