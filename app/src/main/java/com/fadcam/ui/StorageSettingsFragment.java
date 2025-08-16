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
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.Fragment;

import com.fadcam.Constants;
import com.fadcam.MainActivity;
import com.fadcam.ui.OverlayNavUtil;
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
    view.findViewById(R.id.row_storage_mode).setOnClickListener(v -> showStorageOptionsSheet());
    View back = view.findViewById(R.id.back_button);
    if(back!=null){ back.setOnClickListener(v -> OverlayNavUtil.dismiss(requireActivity())); }
        refreshValue();
        // -------------- Fix Ended for this method(onViewCreated)-----------
    }

    // Removed duplicate manual back handling; centralized via OverlayNavUtil

    private void refreshValue(){
        // -------------- Fix Start for this method(refreshValue)-----------
        String mode = prefs.getStorageMode();
        if(SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(mode)){
            String uri = prefs.getCustomStorageUri();
            String label = buildDisplayPath(uri);
            valueStorageMode.setText(getString(R.string.storage_value_custom_prefix)+" "+label);
        } else {
            valueStorageMode.setText(getString(R.string.storage_value_internal));
        }
        // -------------- Fix Ended for this method(refreshValue)-----------
    }

    private void showStorageOptionsSheet(){
        // -------------- Fix Start for this method(showStorageOptionsSheet)-----------
        final String rk = "picker_result_storage";
        getParentFragmentManager().setFragmentResultListener(rk, this, (k,b)->{
            String sel = b.getString(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if(sel==null) return;
            String mode = prefs.getStorageMode();
            boolean custom = SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(mode);
            switch(sel){
                case "use_internal":
                case "switch_internal":
                    if(!SharedPreferencesManager.STORAGE_MODE_INTERNAL.equals(mode)){
                        prefs.setStorageMode(SharedPreferencesManager.STORAGE_MODE_INTERNAL);
                        prefs.setCustomStorageUri(null);
                        sendStorageChangedBroadcast();
                    }
                    break;
                case "select_custom":
                case "change_custom":
                    launchDirectoryPicker(); return; // wait for result
                case "clear_custom":
                    prefs.setCustomStorageUri(null);
                    prefs.setStorageMode(SharedPreferencesManager.STORAGE_MODE_INTERNAL);
                    sendStorageChangedBroadcast();
                    break;
            }
            refreshValue();
        });
        String mode = prefs.getStorageMode();
        boolean custom = SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(mode);
        java.util.ArrayList<com.fadcam.ui.picker.OptionItem> items = new java.util.ArrayList<>();
        String selectedId = null;
        if(custom){
            items.add(new com.fadcam.ui.picker.OptionItem("switch_internal", getString(R.string.storage_option_switch_internal)));
            items.add(new com.fadcam.ui.picker.OptionItem("change_custom", getString(R.string.storage_option_change_custom)));
            items.add(new com.fadcam.ui.picker.OptionItem("clear_custom", getString(R.string.storage_option_clear_custom)));
            selectedId = "change_custom"; // highlight change option as current mode indicator
        } else {
            items.add(new com.fadcam.ui.picker.OptionItem("use_internal", getString(R.string.storage_option_use_internal)));
            items.add(new com.fadcam.ui.picker.OptionItem("select_custom", getString(R.string.storage_option_select_custom)));
            selectedId = "use_internal";
        }
        String helper = getString(R.string.storage_helper_primary)+"\n"+getString(R.string.storage_helper_security);
        if(custom){
            String uri = prefs.getCustomStorageUri();
            String displayName = buildDisplayPath(uri);
            if(uri!=null){
                helper += "\n\n" + getString(R.string.storage_helper_current_custom_prefix) + " " + displayName;
                helper += "\n" + getString(R.string.storage_helper_current_custom_uri_prefix) + " " + decodeTreeUriToReadablePath(uri);
            }
        }
        com.fadcam.ui.picker.PickerBottomSheetFragment sheet = com.fadcam.ui.picker.PickerBottomSheetFragment.newInstance(
                getString(R.string.storage_sheet_title), items, selectedId, rk, helper);
        sheet.show(getParentFragmentManager(), "storage_picker");
        // -------------- Fix Ended for this method(showStorageOptionsSheet)-----------
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
    if(uriString==null) return getString(R.string.storage_value_none);
        try{
            Uri treeUri = Uri.parse(uriString);
            DocumentFile pickedDir = DocumentFile.fromTreeUri(requireContext(), treeUri);
            if(pickedDir!=null){
                String name = pickedDir.getName();
                if(name!=null && !name.isEmpty()) return name;
        return getString(R.string.storage_value_selected_folder);
            }
    }catch(Exception e){ Log.e(TAG, "buildDisplayPath error", e); }
    return getString(R.string.storage_value_selected_folder);
        // -------------- Fix Ended for this method(buildDisplayPath)-----------
    }

    private String decodeTreeUriToReadablePath(String uriString){
        // -------------- Fix Start for this method(decodeTreeUriToReadablePath)-----------
        if(uriString==null) return "";
        try{
            // Typical SAF tree URI: content://com.android.externalstorage.documents/tree/primary%3ADownload%2FFadCam
            int idx = uriString.indexOf("tree/");
            if(idx>=0){
                String after = uriString.substring(idx + 5); // skip 'tree/'
                // Decode percent encodings
                String decoded = java.net.URLDecoder.decode(after, "UTF-8");
                // Map primary: to /storage/emulated/0/
                if(decoded.startsWith("primary:")){
                    decoded = decoded.replaceFirst("primary:", "/storage/emulated/0/");
                }
                // Ensure leading slash if not present
                if(!decoded.startsWith("/")) decoded = "/" + decoded;
                return decoded;
            }
        }catch(Exception e){ Log.e(TAG, "decodeTreeUriToReadablePath error", e); }
        return uriString;
        // -------------- Fix Ended for this method(decodeTreeUriToReadablePath)-----------
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
