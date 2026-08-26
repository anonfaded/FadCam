package com.fadcam.utils;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import com.fadcam.FLog;
import com.fadcam.SharedPreferencesManager;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Single source of truth for the recording destination selected by the user.
 * External destinations use a persistable SAF tree URI, which the recording
 * pipeline can use for all output categories.
 */
public final class StorageDestinationManager {

    private static final String TAG = "StorageDestinationManager";
    private static final String PREF_DESTINATION = "storage_destination_type";
    private static final String WRITE_PROBE_NAME = ".fadcam_write_probe";

    public static final String DESTINATION_INTERNAL = "internal";
    public static final String DESTINATION_SD = "sd_card";
    public static final String DESTINATION_USB = "usb";

    private StorageDestinationManager() {
    }

    @NonNull
    public static String getDestination(@NonNull Context context, @NonNull SharedPreferencesManager prefs) {
        String stored = prefs.sharedPreferences.getString(PREF_DESTINATION, null);
        String uri = prefs.getCustomStorageUri();
        if (isExternalDestination(stored) && isWritableTree(context, uri)) {
            return stored;
        }
        if (SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(prefs.getStorageMode())
                && isWritableTree(context, uri)) {
            return classifyLegacyExternal(context, uri);
        }
        return DESTINATION_INTERNAL;
    }

    public static void setInternal(@NonNull SharedPreferencesManager prefs) {
        prefs.setCustomStorageUri(null);
        prefs.setStorageMode(SharedPreferencesManager.STORAGE_MODE_INTERNAL);
        prefs.sharedPreferences.edit().putString(PREF_DESTINATION, DESTINATION_INTERNAL).apply();
    }

    public static boolean setExternal(
            @NonNull Context context,
            @NonNull SharedPreferencesManager prefs,
            @NonNull String destination,
            @NonNull String uriString
    ) {
        if (!isExternalDestination(destination) || !isWritableTree(context, uriString)) return false;
        prefs.setCustomStorageUri(uriString);
        prefs.setStorageMode(SharedPreferencesManager.STORAGE_MODE_CUSTOM);
        prefs.sharedPreferences.edit().putString(PREF_DESTINATION, destination).apply();
        return true;
    }

    public static boolean isExternalDestination(@Nullable String destination) {
        return DESTINATION_SD.equals(destination) || DESTINATION_USB.equals(destination);
    }

    public static boolean isWritableTree(@NonNull Context context, @Nullable String uriString) {
        if (uriString == null || uriString.trim().isEmpty()) return false;
        try {
            DocumentFile tree = DocumentFile.fromTreeUri(context, Uri.parse(uriString));
            return tree != null && tree.exists() && tree.isDirectory() && tree.canWrite();
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Performs a real, reversible write against the selected SAF tree. This
     * catches providers that report canWrite() but fail when an app actually
     * creates a file, before the destination is committed as active storage.
     */
    public static boolean probeWritableTree(@NonNull Context context, @Nullable String uriString) {
        if (!isWritableTree(context, uriString)) return false;
        DocumentFile probe = null;
        try {
            DocumentFile tree = DocumentFile.fromTreeUri(context, Uri.parse(uriString));
            if (tree == null) return false;
            probe = tree.findFile(WRITE_PROBE_NAME);
            if (probe != null) {
                if (!probe.delete()) return false;
                probe = null;
            }
            probe = tree.createFile("application/octet-stream", WRITE_PROBE_NAME);
            if (probe == null || !probe.canWrite()) return false;
            try (OutputStream output = context.getContentResolver().openOutputStream(probe.getUri(), "w")) {
                if (output == null) return false;
                output.write("FadCam".getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
            return true;
        } catch (Exception e) {
            FLog.w(TAG, "Storage write probe failed", e);
            return false;
        } finally {
            try {
                if (probe != null && probe.exists()) probe.delete();
            } catch (Exception ignored) {
            }
        }
    }

    @NonNull
    public static String getDisplayLabel(
            @NonNull Context context,
            @NonNull SharedPreferencesManager prefs
    ) {
        String destination = getDestination(context, prefs);
        if (DESTINATION_INTERNAL.equals(destination)) return "Phone local storage";
        String folder = "External folder";
        String uri = prefs.getCustomStorageUri();
        if (uri != null) {
            try {
                DocumentFile tree = DocumentFile.fromTreeUri(context, Uri.parse(uri));
                if (tree != null && tree.getName() != null && !tree.getName().trim().isEmpty()) {
                    folder = tree.getName();
                }
            } catch (Exception ignored) {
            }
        }
        return (DESTINATION_USB.equals(destination) ? "USB storage" : "SD card") + " • " + folder;
    }

    /**
     * Opens the system folder picker at a likely removable volume. Android
     * intentionally does not expose a universal USB-vs-SD property, so the
     * selected URI is validated again after the user returns from the picker.
     */
    @NonNull
    public static Intent createPickerIntent(@NonNull Context context, @NonNull String requestedDestination) {
        StorageVolume preferred = findPreferredRemovableVolume(context, requestedDestination);
        if (preferred != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                Intent intent = preferred.createOpenDocumentTreeIntent();
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                return intent;
            } catch (Exception e) {
                FLog.w(TAG, "Could not open picker on preferred volume", e);
            }
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        return intent;
    }

    /**
     * Requires a writable removable volume. Android's StorageVolume metadata is
     * not consistent across OEM USB/OTG implementations, so an unresolved
     * non-primary document provider is accepted after the real write probe.
     */
    public static boolean isCompatibleExternalSelection(
            @NonNull Context context,
            @NonNull String requestedDestination,
            @NonNull Uri uri
    ) {
        if (!isExternalDestination(requestedDestination)
                || !isWritableTree(context, uri.toString())
                || !probeWritableTree(context, uri.toString())) {
            return false;
        }

        VolumeInfo info = resolveVolume(context, uri);
        if (info == null) {
            return !isPrimaryTreeUri(uri);
        }
        if (!info.removable) return false;
        if (info.kind == Kind.USB && DESTINATION_SD.equals(requestedDestination)) return false;
        if (info.kind == Kind.SD && DESTINATION_USB.equals(requestedDestination)) return false;
        return true;
    }

    private static boolean isPrimaryTreeUri(@NonNull Uri uri) {
        try {
            String documentId = DocumentsContract.getTreeDocumentId(uri);
            return documentId != null && documentId.toLowerCase(Locale.ROOT).startsWith("primary:");
        } catch (Exception ignored) {
            return false;
        }
    }

    @Nullable
    private static StorageVolume findPreferredRemovableVolume(
            @NonNull Context context,
            @NonNull String requestedDestination
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null;
        StorageManager manager = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
        if (manager == null) return null;
        try {
            List<StorageVolume> volumes = manager.getStorageVolumes();
            StorageVolume fallback = null;
            for (StorageVolume volume : volumes) {
                if (volume == null || volume.isPrimary() || !volume.isRemovable()) continue;
                if (!Environment.MEDIA_MOUNTED.equals(volume.getState())) continue;
                if (fallback == null) fallback = volume;
                Kind kind = classifyVolumeDescription(context, volume);
                if (DESTINATION_USB.equals(requestedDestination) && kind == Kind.USB) return volume;
                if (DESTINATION_SD.equals(requestedDestination) && kind == Kind.SD) return volume;
            }
            return fallback;
        } catch (Exception e) {
            FLog.w(TAG, "Unable to enumerate removable storage volumes", e);
            return null;
        }
    }

    @Nullable
    private static VolumeInfo resolveVolume(@NonNull Context context, @NonNull Uri treeUri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null;
        String documentId;
        try {
            documentId = DocumentsContract.getTreeDocumentId(treeUri);
        } catch (Exception e) {
            return null;
        }
        if (documentId == null) return null;
        String volumeId = documentId.contains(":")
                ? documentId.substring(0, documentId.indexOf(':'))
                : documentId;
        StorageManager manager = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
        if (manager == null) return null;
        try {
            for (StorageVolume volume : manager.getStorageVolumes()) {
                if (volume == null) continue;
                String uuid = volume.getUuid();
                if (uuid != null && uuid.equalsIgnoreCase(volumeId)) {
                    return new VolumeInfo(volume.isRemovable(), classifyVolumeDescription(context, volume));
                }
                if ("primary".equalsIgnoreCase(volumeId) && volume.isPrimary()) {
                    return new VolumeInfo(volume.isRemovable(), Kind.PRIMARY);
                }
            }
        } catch (Exception e) {
            FLog.w(TAG, "Unable to resolve storage volume for URI", e);
        }
        return null;
    }

    @NonNull
    private static Kind classifyVolumeDescription(@NonNull Context context, @NonNull StorageVolume volume) {
        String description = "";
        try {
            description = String.valueOf(volume.getDescription(context)).toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
        }
        if (containsAny(description, "usb", "flash", "thumb drive", "pendrive", "pen drive", "otg")) {
            return Kind.USB;
        }
        if (containsAny(description, "sd", "memory card", "microsd", "micro sd", "card")) {
            return Kind.SD;
        }
        return Kind.UNKNOWN;
    }

    @NonNull
    private static String classifyLegacyExternal(@NonNull Context context, @NonNull String uriString) {
        try {
            VolumeInfo info = resolveVolume(context, Uri.parse(uriString));
            if (info != null && info.kind == Kind.USB) return DESTINATION_USB;
        } catch (Exception ignored) {
        }
        return DESTINATION_SD;
    }

    private static boolean containsAny(@NonNull String value, @NonNull String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) return true;
        }
        return false;
    }

    private enum Kind {
        PRIMARY,
        SD,
        USB,
        UNKNOWN
    }

    private static final class VolumeInfo {
        final boolean removable;
        final Kind kind;

        VolumeInfo(boolean removable, Kind kind) {
            this.removable = removable;
            this.kind = kind;
        }
    }
}
