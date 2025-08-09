package com.fadcam.ui;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.fadcam.MainActivity;
import com.fadcam.ui.OverlayNavUtil;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.Constants;

/**
 * WatermarkSettingsFragment
 * EXACT migration of legacy watermark spinner + location watermark toggle logic.
 */
public class WatermarkSettingsFragment extends Fragment {

    private static final String TAG = "WatermarkSettings";

    private SharedPreferencesManager prefs;
    private Spinner watermarkSpinner;
    private TextView valueLocationWatermark;
    private LocationHelper locationHelper;
    private ActivityResultLauncher<String> permissionLauncher;
    private Runnable pendingGrantAction;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings_watermark, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // -------------- Fix Start for this method(onViewCreated)-----------
        prefs = SharedPreferencesManager.getInstance(requireContext());
        watermarkSpinner = view.findViewById(R.id.watermark_spinner);
        valueLocationWatermark = view.findViewById(R.id.value_location_watermark);
        setupWatermarkSpinner(watermarkSpinner);
        view.findViewById(R.id.row_location_watermark).setOnClickListener(v -> toggleLocationWatermark());
        View back = view.findViewById(R.id.back_button);
        if (back != null) {
            back.setOnClickListener(v -> OverlayNavUtil.dismiss(requireActivity()));
        }
        permissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) {
                onPermissionGrantedPostRequest();
            } else {
                onPermissionDeniedPostRequest();
            }
        });
        refreshLocationValue();
        // -------------- Fix Ended for this method(onViewCreated)-----------
    }

    // Removed duplicate manual back handling; centralized via OverlayNavUtil

    private void refreshLocationValue() {
        // -------------- Fix Start for this method(refreshLocationValue)-----------
        if (valueLocationWatermark != null) {
            valueLocationWatermark.setText(prefs.isLocalisationEnabled() ? "Enabled" : "Disabled");
        }
        // -------------- Fix Ended for this method(refreshLocationValue)-----------
    }

    private void toggleLocationWatermark() {
        // -------------- Fix Start for this method(toggleLocationWatermark)-----------
        boolean currently = prefs.isLocalisationEnabled();
        if (!currently) {
            ensurePermissionThen(() -> {
                prefs.setLocationEnabled(true);
                startLocationHelperIfNeeded();
                refreshLocationValue();
                Log.d(TAG, "Location watermark enabled via toggle.");
            });
        } else {
            prefs.setLocationEnabled(false);
            stopLocationIfAllDisabled();
            refreshLocationValue();
            Log.d(TAG, "Location watermark disabled via toggle.");
        }
        // -------------- Fix Ended for this method(toggleLocationWatermark)-----------
    }

    private void ensurePermissionThen(Runnable onGranted) {
        Context ctx = getContext();
        if (ctx == null) return;
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            onGranted.run();
        } else {
            showPermissionDialog(onGranted);
        }
    }

    private void showPermissionDialog(Runnable proceed) {
        // -------------- Fix Start for this method(showPermissionDialog)-----------
        new AlertDialog.Builder(requireContext(), com.google.android.material.R.style.MaterialAlertDialog_Material3)
                .setTitle(R.string.location_permission_title)
                .setMessage(R.string.location_permission_description)
                .setPositiveButton("Grant", (d, w) -> {
                    pendingGrantAction = proceed;
                    permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION);
                })
                .setNegativeButton(R.string.universal_cancel, (d, w) -> { })
                .show();
        // -------------- Fix Ended for this method(showPermissionDialog)-----------
    }

    private void onPermissionGrantedPostRequest() {
        if (pendingGrantAction != null) {
            pendingGrantAction.run();
            pendingGrantAction = null;
        }
        Toast.makeText(requireContext(), R.string.location_permission_title, Toast.LENGTH_SHORT).show();
    }

    private void onPermissionDeniedPostRequest() {
        pendingGrantAction = null;
        Toast.makeText(requireContext(), R.string.location_permission_denied, Toast.LENGTH_SHORT).show();
    }

    private void startLocationHelperIfNeeded() {
        if (locationHelper == null) {
            locationHelper = new LocationHelper(requireContext());
        }
    }

    private void stopLocationIfAllDisabled() {
        if (!prefs.isLocalisationEnabled() && !prefs.isLocationEmbeddingEnabled()) {
            if (locationHelper != null) {
                locationHelper.stopLocationUpdates();
                locationHelper = null;
            }
        }
    }

    private void setupWatermarkSpinner(Spinner spinner) {
        // -------------- Fix Start for this method(setupWatermarkSpinner)-----------
        if (spinner == null) return;
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(requireContext(), R.array.watermark_options, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        String savedWatermark = prefs.getWatermarkOption();
        int watermarkIndex = getWatermarkIndex(savedWatermark);
        spinner.setSelection(watermarkIndex);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selectedWatermarkValue = getWatermarkValue(position);
                // -------------- Fix Start for this block(save watermark option)-----------
                // SharedPreferencesManager does not expose getSharedPreferences(); use its public field
                prefs.sharedPreferences.edit().putString(Constants.PREF_WATERMARK_OPTION, selectedWatermarkValue).apply();
                // -------------- Fix Ended for this block(save watermark option)-----------
                Log.d(TAG, "Watermark preference saved: " + selectedWatermarkValue);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });
        // -------------- Fix Ended for this method(setupWatermarkSpinner)-----------
    }

    private int getWatermarkIndex(String value) {
        // -------------- Fix Start for this method(getWatermarkIndex)-----------
        String[] values = getResources().getStringArray(R.array.watermark_values);
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) return i;
        }
        Log.w(TAG, "Watermark value '" + value + "' not found, defaulting to 0.");
        return 0;
        // -------------- Fix Ended for this method(getWatermarkIndex)-----------
    }

    private String getWatermarkValue(int index) {
        // -------------- Fix Start for this method(getWatermarkValue)-----------
        String[] values = getResources().getStringArray(R.array.watermark_values);
        if (index >= 0 && index < values.length) return values[index];
        Log.e(TAG, "Invalid index for watermark values: " + index);
        return values[0];
        // -------------- Fix Ended for this method(getWatermarkValue)-----------
    }
}
