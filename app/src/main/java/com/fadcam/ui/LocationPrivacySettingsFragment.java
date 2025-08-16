package com.fadcam.ui;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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

/**
 * LocationPrivacySettingsFragment
 * Extracted from legacy SettingsFragment: now ONLY location embed toggle (watermark moved to WatermarkSettingsFragment)
 * EXACT preference keys & permission flow preserved.
 */
public class LocationPrivacySettingsFragment extends Fragment {

    private SharedPreferencesManager prefs;
    private TextView valueEmbed;
    private LocationHelper locationHelper; // start/stop as in legacy

    private ActivityResultLauncher<String> permissionLauncher;
    private Runnable pendingGrantAction;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings_location_privacy, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // -------------- Fix Start for this method(onViewCreated)-----------
        prefs = SharedPreferencesManager.getInstance(requireContext());
        valueEmbed = view.findViewById(R.id.value_location_embed);
        view.findViewById(R.id.row_location_embed).setOnClickListener(v -> toggleLocationEmbed());
    View back = view.findViewById(R.id.back_button);
    if(back!=null){ back.setOnClickListener(v -> OverlayNavUtil.dismiss(requireActivity())); }
        permissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if(granted){ onPermissionGrantedPostRequest(); } else { onPermissionDeniedPostRequest(); }
        });
        refreshValues();
        // -------------- Fix Ended for this method(onViewCreated)-----------
    }

    // Removed duplicate manual back handling; centralized via OverlayNavUtil

    private void refreshValues(){
        // -------------- Fix Start for this method(refreshValues)-----------
        valueEmbed.setText(prefs.isLocationEmbeddingEnabled()? "Enabled" : "Disabled");
        // -------------- Fix Ended for this method(refreshValues)-----------
    }

    private void toggleLocationEmbed(){
        // -------------- Fix Start for this method(toggleLocationEmbed)-----------
        boolean currently = prefs.isLocationEmbeddingEnabled();
        if(!currently){
            ensurePermissionThen(() -> {
                prefs.setLocationEmbeddingEnabled(true);
                startLocationHelperIfNeeded();
                refreshValues();
            }, true);
        } else {
            prefs.setLocationEmbeddingEnabled(false);
            stopLocationIfAllDisabled();
            refreshValues();
        }
        // -------------- Fix Ended for this method(toggleLocationEmbed)-----------
    }

    private void startLocationHelperIfNeeded(){
        if(locationHelper==null){ locationHelper = new LocationHelper(requireContext()); }
    }

    private void stopLocationIfAllDisabled(){
        if(!prefs.isLocalisationEnabled() && !prefs.isLocationEmbeddingEnabled()){
            if(locationHelper!=null){ locationHelper.stopLocationUpdates(); locationHelper=null; }
        }
    }

    private void ensurePermissionThen(Runnable onGranted, boolean forEmbedding){
        Context ctx = getContext(); if(ctx==null) return;
        if(ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)== PackageManager.PERMISSION_GRANTED){
            onGranted.run();
        } else {
            showPermissionDialog(forEmbedding, onGranted);
        }
    }

    private void showPermissionDialog(boolean embedding, Runnable proceed){
        // -------------- Fix Start for this method(showPermissionDialog)-----------
        new AlertDialog.Builder(requireContext(), com.google.android.material.R.style.MaterialAlertDialog_Material3)
                .setTitle(R.string.location_permission_title)
                .setMessage(R.string.location_permission_description)
                .setPositiveButton("Grant", (d,w)->{
                    pendingGrantAction = proceed;
                    permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION);
                })
                .setNegativeButton(R.string.universal_cancel, (d,w)->{})
                .show();
        // -------------- Fix Ended for this method(showPermissionDialog)-----------
    }

    private void onPermissionGrantedPostRequest(){
        if(pendingGrantAction!=null){ pendingGrantAction.run(); pendingGrantAction=null; }
        Toast.makeText(requireContext(), R.string.location_permission_title, Toast.LENGTH_SHORT).show();
    }
    private void onPermissionDeniedPostRequest(){
        pendingGrantAction=null;
        Toast.makeText(requireContext(), R.string.location_permission_denied, Toast.LENGTH_SHORT).show();
    }
}
