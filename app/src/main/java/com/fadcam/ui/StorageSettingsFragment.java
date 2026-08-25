package com.fadcam.ui;

import com.fadcam.Log;
import com.fadcam.FLog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
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
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;

/**
 * Storage settings for production recording.
 *
 * <p>Internal storage remains the default. A SAF tree can be selected for a
 * removable SD card or another writable external folder. The selected tree is
 * persisted, validated on every visit, and the recording service already uses
 * the same preference to create its video segments through DocumentFile.</p>
 */
public class StorageSettingsFragment extends Fragment {

    private static final String TAG = "StorageSettingsFragment";

    private SharedPreferencesManager prefs;
    private TextView valueStorageMode;
    private ActivityResultLauncher<Uri> openDocumentTreeLauncher;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings_storage, container, false);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = SharedPreferencesManager.getInstance(requireContext());
        openDocumentTreeLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
            if (uri == null) {
                refreshValue();
                return;
            }
            if (!persistAndValidateTree(uri)) {
                prefs.setStorageMode(SharedPreferencesManager.STORAGE_MODE_INTERNAL);
                prefs.setCustomStorageUri(null);
                FLog.w(TAG, "Selected storage tree is not writable; keeping internal storage");
                refreshValue();
                return;
            }
            prefs.setCustomStorageUri(uri.toString());
            prefs.setStorageMode(SharedPreferencesManager.STORAGE_MODE_CUSTOM);
            sendStorageChangedBroadcast();
            refreshValue();
        });
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        com.fadcam.Utils.attachPressScaleToClickableRows(view);
        valueStorageMode = view.findViewById(R.id.value_storage_mode);
        view.findViewById(R.id.row_storage_mode).setOnClickListener(v -> showStorageOptionsSheet());
        View back = view.findViewById(R.id.back_button);
        if (back != null) back.setOnClickListener(v -> OverlayNavUtil.dismiss(requireActivity()));
        refreshValue();
    }

    private void refreshValue() {
        String mode = prefs.getStorageMode();
        if (SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(mode)) {
            String uri = prefs.getCustomStorageUri();
            if (!isPersistedTreeWritable(uri)) {
                prefs.setStorageMode(SharedPreferencesManager.STORAGE_MODE_INTERNAL);
                prefs.setCustomStorageUri(null);
                valueStorageMode.setText(getString(R.string.storage_value_internal));
                return;
            }
            String label = buildDisplayPath(uri);
            String prefix = isRemovableStorageUri(uri) ? "SD card / external" : "External folder";
            valueStorageMode.setText(prefix + ": " + label);
        } else {
            valueStorageMode.setText(getString(R.string.storage_value_internal));
        }
    }

    private void showStorageOptionsSheet() {
        final String rk = "picker_result_storage";
        getParentFragmentManager().setFragmentResultListener(rk, this, (k, b) -> {
            String sel = b.getString(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if (sel == null) return;
            String mode = prefs.getStorageMode();
            switch (sel) {
                case "use_internal":
                case "switch_internal":
                    if (!SharedPreferencesManager.STORAGE_MODE_INTERNAL.equals(mode)) {
                        prefs.setStorageMode(SharedPreferencesManager.STORAGE_MODE_INTERNAL);
                        prefs.setCustomStorageUri(null);
                        sendStorageChangedBroadcast();
                    }
                    break;
                case "select_custom":
                case "change_custom":
                    launchDirectoryPicker();
                    return;
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
        String selectedId;
        if (custom) {
            items.add(new com.fadcam.ui.picker.OptionItem("switch_internal", "Use internal storage"));
            items.add(new com.fadcam.ui.picker.OptionItem("change_custom", "Change SD card / external folder"));
            items.add(new com.fadcam.ui.picker.OptionItem("clear_custom", "Remove external storage"));
            selectedId = "change_custom";
        } else {
            items.add(new com.fadcam.ui.picker.OptionItem("use_internal", "Use internal storage"));
            items.add(new com.fadcam.ui.picker.OptionItem("select_custom", "Choose SD card / external folder"));
            selectedId = "use_internal";
        }

        String helper = "Production recordings use the selected storage until you stop them or that storage runs out of writable space.\n\n"
                + "For large productions, choose a writable SD card folder from the Android picker. FadCam will persist the permission and create its camera folders there.\n\n"
                + getString(R.string.storage_helper_security);
        if (custom) {
            String uri = prefs.getCustomStorageUri();
            helper += "\n\nCurrent destination: " + buildDisplayPath(uri);
            String readable = decodeTreeUriToReadablePath(uri);
            if (!readable.isEmpty()) helper += "\n" + readable;
        }

        com.fadcam.ui.picker.PickerBottomSheetFragment sheet =
                com.fadcam.ui.picker.PickerBottomSheetFragment.newInstance(
                        "Recording storage", items, selectedId, rk, helper);
        sheet.show(getParentFragmentManager(), "storage_picker");
    }

    private void launchDirectoryPicker() {
        try {
            openDocumentTreeLauncher.launch(null);
        } catch (Exception e) {
            FLog.e(TAG, "Error launching storage directory picker", e);
        }
    }

    private boolean persistAndValidateTree(@NonNull Uri uri) {
        try {
            int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            requireContext().getContentResolver().takePersistableUriPermission(uri, takeFlags);
            return isPersistedTreeWritable(uri.toString());
        } catch (Exception e) {
            FLog.e(TAG, "Failed to persist storage tree permission", e);
            return false;
        }
    }

    private boolean isPersistedTreeWritable(@Nullable String uriString) {
        if (uriString == null || uriString.isEmpty()) return false;
        try {
            DocumentFile tree = DocumentFile.fromTreeUri(requireContext(), Uri.parse(uriString));
            return tree != null && tree.exists() && tree.isDirectory() && tree.canWrite();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isRemovableStorageUri(@Nullable String uriString) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || uriString == null || uriString.isEmpty()) return false;
        try {
            Uri treeUri = Uri.parse(uriString);
            String documentId = android.provider.DocumentsContract.getTreeDocumentId(treeUri);
            if (documentId == null) return false;
            String volumeId = documentId.contains(":") ? documentId.substring(0, documentId.indexOf(':')) : documentId;
            StorageManager storageManager = (StorageManager) requireContext().getSystemService(Context.STORAGE_SERVICE);
            if (storageManager == null) return false;
            for (StorageVolume volume : storageManager.getStorageVolumes()) {
                String uuid = volume.getUuid();
                if (uuid != null && uuid.equalsIgnoreCase(volumeId)) return volume.isRemovable();
                if ("primary".equalsIgnoreCase(volumeId) && volume.isPrimary()) return volume.isRemovable();
            }
        } catch (Exception e) {
            FLog.d(TAG, "Could not classify storage URI as removable", e);
        }
        return false;
    }

    private String buildDisplayPath(String uriString) {
        if (uriString == null) return getString(R.string.storage_value_none);
        try {
            DocumentFile pickedDir = DocumentFile.fromTreeUri(requireContext(), Uri.parse(uriString));
            if (pickedDir != null) {
                String name = pickedDir.getName();
                if (name != null && !name.isEmpty()) return name;
            }
        } catch (Exception e) {
            FLog.e(TAG, "buildDisplayPath error", e);
        }
        return getString(R.string.storage_value_selected_folder);
    }

    private String decodeTreeUriToReadablePath(String uriString) {
        if (uriString == null) return "";
        try {
            int idx = uriString.indexOf("tree/");
            if (idx >= 0) {
                String after = uriString.substring(idx + 5);
                String decoded = java.net.URLDecoder.decode(after, "UTF-8");
                if (decoded.startsWith("primary:")) decoded = decoded.replaceFirst("primary:", "/storage/emulated/0/");
                if (!decoded.startsWith("/")) decoded = "/" + decoded;
                return decoded;
            }
        } catch (Exception e) {
            FLog.e(TAG, "decodeTreeUriToReadablePath error", e);
        }
        return uriString;
    }

    private void sendStorageChangedBroadcast() {
        if (getContext() == null) return;
        try {
            requireContext().sendBroadcast(new Intent(Constants.ACTION_STORAGE_LOCATION_CHANGED));
            refreshRecordsFragmentDirect();
        } catch (Exception e) {
            FLog.e(TAG, "Storage change broadcast failed", e);
        }
    }

    private void refreshRecordsFragmentDirect() {
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (!(getActivity() instanceof com.fadcam.MainActivity)) return;
                com.fadcam.MainActivity mainActivity = (com.fadcam.MainActivity) getActivity();
                for (androidx.fragment.app.Fragment fragment : mainActivity.getSupportFragmentManager().getFragments()) {
                    if (fragment instanceof com.fadcam.ui.RecordsFragment) {
                        ((com.fadcam.ui.RecordsFragment) fragment).refreshList();
                        break;
                    }
                }
                refreshHomeFragmentStats(mainActivity);
            }, 200);
        } catch (Exception e) {
            FLog.e(TAG, "Failed to refresh RecordsFragment after storage change", e);
        }
    }

    private void refreshHomeFragmentStats(com.fadcam.MainActivity mainActivity) {
        try {
            for (androidx.fragment.app.Fragment fragment : mainActivity.getSupportFragmentManager().getFragments()) {
                if (fragment instanceof com.fadcam.ui.HomeFragment) {
                    ((com.fadcam.ui.HomeFragment) fragment).refreshStats();
                    break;
                }
            }
        } catch (Exception e) {
            FLog.e(TAG, "Failed to refresh HomeFragment stats", e);
        }
    }
}
