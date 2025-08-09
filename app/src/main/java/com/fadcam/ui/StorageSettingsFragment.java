package com.fadcam.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.Fragment;

import com.fadcam.Constants;
import com.fadcam.MainActivity;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;

/**
 * StorageSettingsFragment
 * Modular extraction of storage location logic from legacy SettingsFragment.
 * Provides single row summary + dialogs keeping same preference semantics & broadcast.
 */
public class StorageSettingsFragment extends Fragment {

    private static final String TAG = "StorageSettingsFragment";

    private SharedPreferencesManager prefs;
    private TextView valueStorageMode;

    private ActivityResultLauncher<Uri> openDocumentTreeLauncher;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings_storage, container, false);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // -------------- Fix Start for this method(onCreate)-----------
        prefs = SharedPreferencesManager.getInstance(requireContext());
        openDocumentTreeLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
            if(uri!=null){
                boolean success=false;
                try{
                    final int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                    requireContext().getContentResolver().takePersistableUriPermission(uri, takeFlags);
                    prefs.setCustomStorageUri(uri.toString());
                    prefs.setStorageMode(SharedPreferencesManager.STORAGE_MODE_CUSTOM);
                    success=true;
                    sendStorageChangedBroadcast();
                }catch(Exception e){
                    Log.e(TAG, "Failed to persist custom storage URI", e);
                    prefs.setStorageMode(SharedPreferencesManager.STORAGE_MODE_INTERNAL);
                    prefs.setCustomStorageUri(null);
                }
                refreshValue();
            } else {
                // Revert if user cancelled selection when switching
                if(!SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(prefs.getStorageMode())){
                    prefs.setStorageMode(SharedPreferencesManager.STORAGE_MODE_INTERNAL);
                }
                refreshValue();
            }
        });
        // -------------- Fix Ended for this method(onCreate)-----------
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // -------------- Fix Start for this method(onViewCreated)-----------
        valueStorageMode = view.findViewById(R.id.value_storage_mode);
        view.findViewById(R.id.row_storage_mode).setOnClickListener(v -> showStorageOptionsDialog());
        View back = view.findViewById(R.id.back_button);
        if(back!=null){ back.setOnClickListener(v -> handleBack()); }
        refreshValue();
        // -------------- Fix Ended for this method(onViewCreated)-----------
    }

    private void handleBack(){
        if(getActivity()!=null){
            requireActivity().getSupportFragmentManager().popBackStack();
            if(getActivity() instanceof MainActivity){
                ((MainActivity) getActivity()).hideOverlayIfNoFragments();
            }
        }
    }

    private void refreshValue(){
        // -------------- Fix Start for this method(refreshValue)-----------
        String mode = prefs.getStorageMode();
        if(SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(mode)){
            String uri = prefs.getCustomStorageUri();
            String label = buildDisplayPath(uri);
            valueStorageMode.setText("Custom: "+label);
        } else {
            valueStorageMode.setText("Internal App Storage");
        }
        // -------------- Fix Ended for this method(refreshValue)-----------
    }

    private void showStorageOptionsDialog(){
        // -------------- Fix Start for this method(showStorageOptionsDialog)-----------
        String mode = prefs.getStorageMode();
        boolean custom = SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(mode);
        String[] items;
        if(custom){
            items = new String[]{"Switch to Internal Storage","Change Custom Folder","Clear Custom Folder"};
        } else {
            items = new String[]{"Use Internal Storage","Select Custom Location"};
        }
        new AlertDialog.Builder(requireContext(), com.google.android.material.R.style.MaterialAlertDialog_Material3)
                .setTitle("Storage Location")
                .setItems(items,(d,w)->{
                    if(!custom){
                        if(w==0){ // ensure internal
                            if(!SharedPreferencesManager.STORAGE_MODE_INTERNAL.equals(mode)){
                                prefs.setStorageMode(SharedPreferencesManager.STORAGE_MODE_INTERNAL);
                                prefs.setCustomStorageUri(null);
                                sendStorageChangedBroadcast();
                            }
                        } else if(w==1){ // choose custom
                            launchDirectoryPicker();
                            return; // wait for result
                        }
                    } else { // currently custom
                        if(w==0){ // switch to internal
                            prefs.setStorageMode(SharedPreferencesManager.STORAGE_MODE_INTERNAL);
                            prefs.setCustomStorageUri(null);
                            sendStorageChangedBroadcast();
                        } else if(w==1){ // change folder
                            launchDirectoryPicker(); return;
                        } else if(w==2){ // clear custom
                            prefs.setCustomStorageUri(null);
                            prefs.setStorageMode(SharedPreferencesManager.STORAGE_MODE_INTERNAL);
                            sendStorageChangedBroadcast();
                        }
                    }
                    refreshValue();
                })
                .setNegativeButton(R.string.universal_cancel,null)
                .show();
        // -------------- Fix Ended for this method(showStorageOptionsDialog)-----------
    }

    private void launchDirectoryPicker(){
        // -------------- Fix Start for this method(launchDirectoryPicker)-----------
        try{
            openDocumentTreeLauncher.launch(null);
        }catch(Exception e){
            Log.e(TAG, "Error launching directory picker", e);
        }
        // -------------- Fix Ended for this method(launchDirectoryPicker)-----------
    }

    private String buildDisplayPath(String uriString){
        // -------------- Fix Start for this method(buildDisplayPath)-----------
        if(uriString==null) return "(None)";
        try{
            Uri treeUri = Uri.parse(uriString);
            DocumentFile pickedDir = DocumentFile.fromTreeUri(requireContext(), treeUri);
            if(pickedDir!=null){
                String name = pickedDir.getName();
                if(name!=null && !name.isEmpty()) return name;
                return "Selected Folder";
            }
        }catch(Exception e){ Log.e(TAG, "buildDisplayPath error", e); }
        return "Custom";
        // -------------- Fix Ended for this method(buildDisplayPath)-----------
    }

    private void sendStorageChangedBroadcast(){
        // -------------- Fix Start for this method(sendStorageChangedBroadcast)-----------
        if(getContext()==null) return;
        try{
            Intent intent = new Intent(Constants.ACTION_STORAGE_LOCATION_CHANGED);
            requireContext().sendBroadcast(intent);
            Log.i(TAG, "Sent ACTION_STORAGE_LOCATION_CHANGED broadcast.");
        }catch(Exception e){ Log.e(TAG, "Broadcast error", e); }
        // -------------- Fix Ended for this method(sendStorageChangedBroadcast)-----------
    }
}
