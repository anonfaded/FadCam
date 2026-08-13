package com.fadcam.utils;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import androidx.annotation.Nullable;
import com.fadcam.FLog;
import com.fadcam.SharedPreferencesManager;

/**
 * Shared audio-input device resolution for FadCam recording paths.
 *
 * <p>Single source of truth for "which external input device should we record from",
 * used by both video mode and screen recording (cast). Resolution order:
 * <ol>
 *   <li>If the saved source is not WIRED → {@code null} (system default routing —
 *       the reliable legacy behavior; never force a device).</li>
 *   <li>Enumerate current input devices; match by saved product NAME first
 *       (USB mics of different types can coexist, e.g. TYPE_USB_DEVICE vs
 *       TYPE_USB_HEADSET), then by saved TYPE.</li>
 *   <li>Fallback: first available external device (USB / wired / BT).</li>
 *   <li>Otherwise {@code null} → system default routing (logged).</li>
 * </ol>
 *
 * <p>Everything is logged so a failing device can be diagnosed from the debug log
 * (issue #334: cast mode intermittently lost the USB mic after video/cast settings
 * were split — cast was inheriting video's device selection and force-routing to a
 * device matched unreliably).
 */
public final class AudioDeviceResolver {

    private static final String TAG = "AudioDeviceResolver";

    private AudioDeviceResolver() {}

    /** Whether a device type is a usable external INPUT device for recording. */
    public static boolean isExternalInputDevice(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
                return true;
            default:
                return false;
        }
    }

    /**
     * Resolves the preferred input device for a recording session.
     *
     * @param context     Application context (for AudioManager).
     * @param source      SharedPreferencesManager.AUDIO_INPUT_SOURCE_* value.
     * @param savedType   Saved AudioDeviceInfo.TYPE_* (-1 = any type).
     * @param savedName   Saved device product name (null = any name).
     * @return The device to force-route to, or {@code null} for system default routing.
     */
    @Nullable
    public static AudioDeviceInfo resolvePreferredInput(
            Context context, String source, int savedType, String savedName) {
        if (!SharedPreferencesManager.AUDIO_INPUT_SOURCE_WIRED.equals(source)) {
            FLog.i(TAG, "resolvePreferredInput: source=" + source + " → system default routing (no forced device)");
            return null;
        }

        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) {
            FLog.w(TAG, "resolvePreferredInput: AudioManager unavailable → default routing");
            return null;
        }

        AudioDeviceInfo[] devices;
        try {
            devices = am.getDevices(AudioManager.GET_DEVICES_INPUTS);
        } catch (Exception e) {
            FLog.w(TAG, "resolvePreferredInput: getDevices failed → default routing", e);
            return null;
        }
        if (devices == null || devices.length == 0) {
            FLog.w(TAG, "resolvePreferredInput: no input devices enumerated → default routing");
            return null;
        }

        // Log candidates for diagnostics.
        StringBuilder candidates = new StringBuilder();
        for (AudioDeviceInfo d : devices) {
            if (d == null) continue;
            if (candidates.length() > 0) candidates.append(", ");
            candidates.append(d.getType()).append(":").append(d.getProductName());
        }
        FLog.i(TAG, "resolvePreferredInput: input devices present → [" + candidates + "]");

        // 1) Exact product-name match (most reliable — a saved USB mic keeps its
        //    name even when its type presentation differs between USB_DEVICE/USB_HEADSET).
        if (savedName != null && !savedName.isEmpty()) {
            for (AudioDeviceInfo d : devices) {
                if (d == null) continue;
                CharSequence pn = d.getProductName();
                if (pn != null && savedName.contentEquals(pn)) {
                    FLog.i(TAG, "resolvePreferredInput: matched by NAME '" + savedName
                            + "' → " + pn + " (type=" + d.getType() + ")");
                    return d;
                }
            }
            FLog.w(TAG, "resolvePreferredInput: saved name '" + savedName + "' not found among current devices");
        }

        // 2) Type match.
        if (savedType != -1) {
            for (AudioDeviceInfo d : devices) {
                if (d != null && d.getType() == savedType && isExternalInputDevice(savedType)) {
                    FLog.i(TAG, "resolvePreferredInput: matched by TYPE " + savedType
                            + " → " + d.getProductName());
                    return d;
                }
            }
            FLog.w(TAG, "resolvePreferredInput: saved type " + savedType + " not found among current devices");
        }

        // 3) Fallback: first available external input device.
        for (AudioDeviceInfo d : devices) {
            if (d != null && isExternalInputDevice(d.getType())) {
                FLog.w(TAG, "resolvePreferredInput: fallback to first external device → "
                        + d.getProductName() + " (type=" + d.getType() + ")");
                return d;
            }
        }

        FLog.w(TAG, "resolvePreferredInput: no external device found → system default routing");
        return null;
    }
}
