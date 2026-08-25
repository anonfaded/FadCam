package com.fadcam.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
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
import androidx.fragment.app.Fragment;

import com.fadcam.Constants;
import com.fadcam.FLog;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.utils.StorageDestinationManager;
import com.fadcam.utils.StorageInfoCache;

import java.util.ArrayList;

/**
 * Recording destination selector.
 *
 * <p>All three destinations are live: phone local storage, SD/memory card,
 * and USB external storage. SD/USB selections use Android's Storage Access
 * Framework and persist the user's write permission, so the recording
 * pipeline can write its normal FadCam folder tree to the selected volume.</p>
 */
public class StorageSettingsFragment extends Fragment {

    private static final String TAG = "StorageSettingsFragment";
    private static final String RESULT_KEY = "picker_result_storage";

    private SharedPreferencesManager prefs;
    private TextView valueStorageMode;
    private String pendingDestination = StorageDestinationManager.DESTINATION_SD;

    private ActivityResultLauncher<Intent> openDocumentTreeLauncher;

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

        openDocumentTreeLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
                        refreshValue();
                        return;
                    }
                    Intent data = result.getData();
                    Uri uri = data.getData();
                    if (uri == null) {
                        refreshValue();
                        return;
                    }
                    activateExternalDestination(uri, data.getFlags());
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

    @Override
    public void onResume() {
        super.onResume();
        if (valueStorageMode != null) refreshValue();
    }

    private void refreshValue() {
        if (valueStorageMode == null) return;
        String destination = StorageDestinationManager.getDestination(requireContext(), prefs);
        valueStorageMode.setText(StorageDestinationManager.getDisplayLabel(requireContext(), prefs));
        if (StorageDestinationManager.isExternalDestination(destination)
                && !StorageDestinationManager.isWritableTree(requireContext(), prefs.getCustomStorageUri())) {
            StorageDestinationManager.setInternal(prefs);
            valueStorageMode.setText("Phone local storage");
        }
    }

    private void showStorageOptionsSheet() {
        getParentFragmentManager().setFragmentResultListener(RESULT_KEY, this, (key, bundle) -> {
            String selected = bundle.getString(com.fadcam.ui.picker.PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if (selected == null) return;
            if (StorageDestinationManager.DESTINATION_INTERNAL.equals(selected)) {
                StorageDestinationManager.setInternal(prefs);
                StorageInfoCache.clearCache();
                sendStorageChangedBroadcast();
                refreshValue();
                return;
            }
            if (StorageDestinationManager.DESTINATION_SD.equals(selected)
                    || StorageDestinationManager.DESTINATION_USB.equals(selected)) {
                pendingDestination = selected;
                launchExternalPicker(selected);
            }
        });

        String current = StorageDestinationManager.getDestination(requireContext(), prefs);
        ArrayList<com.fadcam.ui.picker.OptionItem> items = new ArrayList<>();
        items.add(new com.fadcam.ui.picker.OptionItem(
                StorageDestinationManager.DESTINATION_INTERNAL,
                "Phone local storage"));
        items.add(new com.fadcam.ui.picker.OptionItem(
                StorageDestinationManager.DESTINATION_SD,
                "SD card / memory card"));
        items.add(new com.fadcam.ui.picker.OptionItem(
                StorageDestinationManager.DESTINATION_USB,
                "USB external storage"));

        String helper = "Choose where new recordings are written. The selected destination is used by camera, dual-camera and screen recording outputs.\n\n"
                + "SD card and USB use Android's secure folder picker. FadCam stores the permission and performs a real write test before activating the destination.\n\n"
                + "Android does not expose a universal USB-vs-SD flag on every phone, so the picker starts on the best matching removable volume when available. The returned volume is validated again before saving it.\n\n"
                + "Current: " + StorageDestinationManager.getDisplayLabel(requireContext(), prefs);

        com.fadcam.ui.picker.PickerBottomSheetFragment sheet =
                com.fadcam.ui.picker.PickerBottomSheetFragment.newInstance(
                        "Recording storage", items, current, RESULT_KEY, helper);
        sheet.show(getParentFragmentManager(), "storage_picker");
    }

    private void launchExternalPicker(@NonNull String destination) {
        try {
            Intent intent = StorageDestinationManager.createPickerIntent(requireContext(), destination);
            openDocumentTreeLauncher.launch(intent);
        } catch (Exception e) {
            FLog.e(TAG, "Failed to launch storage picker", e);
            Toast.makeText(requireContext(), "Unable to open storage picker", Toast.LENGTH_SHORT).show();
        }
    }

    private void activateExternalDestination(@NonNull Uri uri, int resultFlags) {
        if (!StorageDestinationManager.isCompatibleExternalSelection(
                requireContext(), pendingDestination, uri)) {
            Toast.makeText(
                    requireContext(),
                    pendingDestination.equals(StorageDestinationManager.DESTINATION_USB)
                            ? "Select a writable USB/removable storage volume"
                            : "Select a writable SD/memory-card volume",
                    Toast.LENGTH_LONG).show();
            refreshValue();
            return;
        }

        final int persistableFlags = resultFlags
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if ((persistableFlags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0) {
            FLog.e(TAG, "Selected storage did not return a persistable write grant");
            Toast.makeText(requireContext(), "FadCam could not obtain write access to that storage", Toast.LENGTH_LONG).show();
            return;
        }

        try {
            requireContext().getContentResolver().takePersistableUriPermission(uri, persistableFlags);
        } catch (SecurityException e) {
            FLog.e(TAG, "Selected storage did not grant persistable access", e);
            Toast.makeText(requireContext(), "FadCam could not keep access to that storage", Toast.LENGTH_LONG).show();
            return;
        }

        if (!StorageDestinationManager.setExternal(
                requireContext(), prefs, pendingDestination, uri.toString())) {
            Toast.makeText(requireContext(), "The selected storage is not writable", Toast.LENGTH_LONG).show();
            return;
        }

        StorageInfoCache.clearCache();
        sendStorageChangedBroadcast();
        refreshValue();
        Toast.makeText(
                requireContext(),
                pendingDestination.equals(StorageDestinationManager.DESTINATION_USB)
                        ? "USB storage is now the recording destination"
                        : "SD card is now the recording destination",
                Toast.LENGTH_SHORT).show();
    }

    private void sendStorageChangedBroadcast() {
        try {
            requireContext().sendBroadcast(new Intent(Constants.ACTION_STORAGE_LOCATION_CHANGED));
            refreshRecordsAndHome();
        } catch (Exception e) {
            FLog.e(TAG, "Storage destination change broadcast failed", e);
        }
    }

    private void refreshRecordsAndHome() {
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (!(getActivity() instanceof com.fadcam.MainActivity)) return;
                com.fadcam.MainActivity activity = (com.fadcam.MainActivity) getActivity();
                for (androidx.fragment.app.Fragment fragment : activity.getSupportFragmentManager().getFragments()) {
                    if (fragment instanceof com.fadcam.ui.RecordsFragment) {
                        ((com.fadcam.ui.RecordsFragment) fragment).refreshList();
                    }
                    if (fragment instanceof com.fadcam.ui.HomeFragment) {
                        ((com.fadcam.ui.HomeFragment) fragment).refreshStats();
                    }
                }
            }, 200);
        } catch (Exception e) {
            FLog.e(TAG, "Failed to refresh storage-dependent screens", e);
        }
    }
}
