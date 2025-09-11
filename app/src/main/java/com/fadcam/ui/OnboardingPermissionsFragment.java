package com.fadcam.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Toast;
import android.widget.TextView;

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
 * OnboardingPermissionsFragment handles permission requests and enforces policy for AppIntro.
 */
public class OnboardingPermissionsFragment extends Fragment implements SlidePolicy {
    private static final int PERMISSIONS_REQUEST_CODE = 101;
    private boolean permissionsGranted = false;
    private MaterialButton grantButton;
    private int permissionRequestCount = 0; // Track how many times requested
    private boolean permanentlyDenied = false;
    private TextView permissionStatusText; // Text view to show status instead of toasts
    private boolean statusToastShown = false; // Flag to prevent multiple toasts

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.onboarding_permissions_slide, container, false);
        grantButton = view.findViewById(R.id.permissions_grant_button);
        permissionStatusText = view.findViewById(R.id.permission_status_text);
        if (permissionStatusText != null) {
            permissionStatusText.setVisibility(View.GONE); // Hide initially
        }
        grantButton.setEnabled(true);
        grantButton.setAlpha(1f);
        grantButton.setOnClickListener(v -> {
            if (permissionsGranted) {
                // Do nothing, or optionally show a toast
                showPermissionStatus(R.string.permissions_granted, true);
            } else {
                // Always show the runtime permission dialog, even if permanently denied
                requestAllPermissionsAlways();
            }
        });
        checkPermissionsAndUpdateUI();
        if (!permissionsGranted && !permanentlyDenied) {
            requestAllPermissionsAlways();
        }

        View openSettingsLink = view.findViewById(R.id.open_settings_link);
        if (openSettingsLink != null) {
            openSettingsLink.setOnClickListener(v2 -> {
                showManualPermissionDialog();
                showPermissionStatus(R.string.open_settings, false);
            });
        }

        MaterialButton batteryOptButton = view.findViewById(R.id.disable_battery_optimization_button);
        if (batteryOptButton != null) {
            android.os.PowerManager pm = (android.os.PowerManager) requireContext().getSystemService(android.content.Context.POWER_SERVICE);
            boolean isIgnoring = false;
            if (pm != null) {
                isIgnoring = pm.isIgnoringBatteryOptimizations(requireContext().getPackageName());
            }
            batteryOptButton.setEnabled(!isIgnoring);
            batteryOptButton.setAlpha(isIgnoring ? 0.5f : 1f);
            batteryOptButton.setOnClickListener(v3 -> {
                Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(android.net.Uri.parse("package:" + requireContext().getPackageName()));
                startActivity(intent);
            });
        }
        return view;
    }

    private void showPermissionStatus(int stringResId, boolean success) {
        if (permissionStatusText != null) {
            permissionStatusText.setText(stringResId);
            permissionStatusText.setTextColor(ContextCompat.getColor(requireContext(), 
                success ? R.color.green : R.color.redPastel));
            permissionStatusText.setVisibility(View.VISIBLE);
        } else if (!statusToastShown) {
            Toast.makeText(requireContext(), stringResId, Toast.LENGTH_SHORT).show();
            statusToastShown = true;
        }
    }

    private void requestAllPermissionsAlways() {
        // Always request, system will ignore already granted
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
        requestPermissions(permissions.toArray(new String[0]), PERMISSIONS_REQUEST_CODE);
        permissionRequestCount++;
    }

    private boolean isAnyPermissionPermanentlyDenied() {
        // Check for each permission if it's denied and shouldShowRequestPermissionRationale is false
        if (getActivity() == null) return false;
        boolean denied = false;
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED &&
                !ActivityCompat.shouldShowRequestPermissionRationale(getActivity(), Manifest.permission.CAMERA)) denied = true;
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED &&
                !ActivityCompat.shouldShowRequestPermissionRationale(getActivity(), Manifest.permission.RECORD_AUDIO)) denied = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(getActivity(), Manifest.permission.READ_MEDIA_VIDEO)) denied = true;
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(getActivity(), Manifest.permission.POST_NOTIFICATIONS)) denied = true;
        } else {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(getActivity(), Manifest.permission.WRITE_EXTERNAL_STORAGE)) denied = true;
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(getActivity(), Manifest.permission.READ_EXTERNAL_STORAGE)) denied = true;
        }
        return denied;
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
        permissionsGranted = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                && ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED)
                || (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsGranted = permissionsGranted && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        }
        permanentlyDenied = isAnyPermissionPermanentlyDenied();
        if (permissionsGranted) {
            grantButton.setEnabled(false);
            grantButton.setAlpha(0.5f);
            grantButton.setText(R.string.permissions_granted);
            showPermissionStatus(R.string.permissions_granted, true);
        } else {
            grantButton.setEnabled(true);
            grantButton.setAlpha(1f);
            grantButton.setText(R.string.grant_permissions);
        }
        

        // Update battery optimization button status
        updateBatteryOptimizationButton();
    }

    @Override
    public void onResume() {
        super.onResume();
        checkPermissionsAndUpdateUI();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            checkPermissionsAndUpdateUI();
        }
    }

    @Override
    public boolean isPolicyRespected() {
        // Only allow Done if all permissions are granted
        return permissionsGranted;
    }

    @Override
    public void onUserIllegallyRequestedNextPage() {
        showPermissionStatus(R.string.permissions_note, false);
        if (grantButton != null) {
            grantButton.performClick();
        }
    }

    /**
     * Called to refresh language-specific UI elements when the language has changed
     * without recreating the entire fragment
     */
    public void refreshLanguage() {
        View view = getView();
        if (view == null) return;
        
        // Update all text elements with their string resources
        TextView titleText = view.findViewById(R.id.permissionsTitle);
        if (titleText != null) {
            titleText.setText(R.string.permissions_required);
        }
        
        TextView descText = view.findViewById(R.id.permissionsDescription);
        if (descText != null) {
            descText.setText(R.string.permissions_description);
        }
        
        if (grantButton != null) {
            if (permissionsGranted) {
                grantButton.setText(R.string.permissions_granted);
            } else {
                grantButton.setText(R.string.grant_permissions);
            }
        }
        
        MaterialButton batteryOptButton = view.findViewById(R.id.disable_battery_optimization_button);
        if (batteryOptButton != null) {
            batteryOptButton.setText(R.string.disable_battery_optimization);
        }
        
        TextView settingsLink = view.findViewById(R.id.open_settings_link);
        if (settingsLink != null) {
            settingsLink.setText(R.string.open_settings);
        }
        
        TextView noteText = view.findViewById(R.id.permissionsNote);
        if (noteText != null) {
            noteText.setText(R.string.permissions_note);
        }
        
        // Find and update text in permission icons section
        // These are in nested LinearLayouts inside permissionsListContainer
        LinearLayout permissionsListContainer = view.findViewById(R.id.permissionsListContainer);
        if (permissionsListContainer != null && permissionsListContainer.getChildCount() >= 3) {
            // Camera text
            LinearLayout cameraLayout = (LinearLayout) permissionsListContainer.getChildAt(0);
            if (cameraLayout != null && cameraLayout.getChildCount() >= 2) {
                TextView cameraText = (TextView) cameraLayout.getChildAt(1);
                if (cameraText != null) {
                    cameraText.setText(R.string.onboarding_camera);
                }
            }
            
            // Mic text
            LinearLayout micLayout = (LinearLayout) permissionsListContainer.getChildAt(1);
            if (micLayout != null && micLayout.getChildCount() >= 2) {
                TextView micText = (TextView) micLayout.getChildAt(1);
                if (micText != null) {
                    micText.setText(R.string.onboarding_microphone);
                }
            }
            
            // Storage text
            LinearLayout storageLayout = (LinearLayout) permissionsListContainer.getChildAt(2);
            if (storageLayout != null && storageLayout.getChildCount() >= 2) {
                TextView storageText = (TextView) storageLayout.getChildAt(1);
                if (storageText != null) {
                    storageText.setText(R.string.onboarding_storage);
                }
            }
        }
        
        TextView andText = view.findViewById(R.id.and_text);
        if (andText != null) {
            andText.setText(R.string.onboarding_and);
        }
        
        TextView orText = view.findViewById(R.id.or_text);
        if (orText != null) {
            orText.setText(R.string.onboarding_or);
        }
        
        // If we had shown a status message, update it
        if (permissionStatusText != null && permissionStatusText.getVisibility() == View.VISIBLE) {
            // Re-check permissions and update UI
            checkPermissionsAndUpdateUI();
        }
        
        // Force layout refresh on all controls
        if (view instanceof ViewGroup) {
            ViewGroup root = (ViewGroup) view;
            root.invalidate();
            root.requestLayout();
        }
    }

    /**
     * Updates the battery optimization button state based on current status
     */
    private void updateBatteryOptimizationButton() {
        View view = getView();
        if (view == null) return;
        
        MaterialButton batteryOptButton = view.findViewById(R.id.disable_battery_optimization_button);
        if (batteryOptButton != null) {
            android.os.PowerManager pm = (android.os.PowerManager) requireContext().getSystemService(android.content.Context.POWER_SERVICE);
            boolean isIgnoring = false;
            if (pm != null) {
                isIgnoring = pm.isIgnoringBatteryOptimizations(requireContext().getPackageName());
            }
            
            if (isIgnoring) {
                batteryOptButton.setEnabled(false);
                batteryOptButton.setAlpha(0.5f);
                batteryOptButton.setText(R.string.permissions_granted);
            } else {
                batteryOptButton.setEnabled(true);
                batteryOptButton.setAlpha(1f);
                batteryOptButton.setText(R.string.disable_battery_optimization);
            }
        }
    }
} 