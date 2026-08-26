package com.fadcam.production;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.security.SecureRandom;
import java.util.Locale;

/** Persistent six-slot guest camera lifecycle backed by VDO.Ninja WebRTC. */
public final class ProductionCameraSlotManager {
    private static final String PREFS = "fadcam_production_camera_slots";
    private static final int SLOT_COUNT = 6;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String VDO_BASE = "https://vdo.ninja/?";

    private ProductionCameraSlotManager() {}

    @NonNull public static ProductionCameraSlot get(Context context, int slot) {
        int s = ProductionControlState.clampCameraSlot(slot);
        SharedPreferences p = prefs(context);
        String token = p.getString(key(s, "token"), "");
        if (token.isEmpty()) {
            token = newToken();
            p.edit().putString(key(s, "token"), token).putBoolean(key(s, "consumed"), false).apply();
        }
        String active = p.getString(key(s, "active_stream_id"), "");
        String name = p.getString(key(s, "name"), "");
        String url = p.getString(key(s, "url"), "");
        boolean waiting = p.getBoolean(key(s, "waiting"), false);
        boolean consumed = p.getBoolean(key(s, "consumed"), false);
        ProductionCameraSlot.Status status = !active.isEmpty()
                ? ProductionCameraSlot.Status.CONNECTED
                : (waiting ? ProductionCameraSlot.Status.WAITING : ProductionCameraSlot.Status.EMPTY);
        return new ProductionCameraSlot(s, name, token, active, url, consumed, status);
    }

    @NonNull public static ProductionCameraSlot[] getAll(Context context) {
        ProductionCameraSlot[] result = new ProductionCameraSlot[SLOT_COUNT];
        for (int i = 0; i < SLOT_COUNT; i++) result[i] = get(context, i + 1);
        return result;
    }

    /** Creates a fresh invitation while preserving an already-connected guest. */
    @NonNull public static ProductionCameraSlot createInvite(Context context, int slot, String guestName) {
        int s = ProductionControlState.clampCameraSlot(slot);
        SharedPreferences p = prefs(context);
        if (!p.getString(key(s, "active_stream_id"), "").isEmpty()) return get(context, s);
        String token = p.getString(key(s, "token"), "");
        if (token.isEmpty()) token = newToken();
        p.edit().putString(key(s, "token"), token)
                .putString(key(s, "name"), guestName == null ? "" : guestName.trim())
                .putBoolean(key(s, "waiting"), true)
                .putBoolean(key(s, "consumed"), false).apply();
        return get(context, s);
    }

    /** Marks a slot as waiting without rotating its current invitation. */
    @NonNull public static ProductionCameraSlot markInvited(Context context, int slot, String guestName) {
        return createInvite(context, slot, guestName);
    }

    /** Moves the current one-use invite into the active state and rotates the next invite. */
    @NonNull public static ProductionCameraSlot markConsumed(Context context, int slot) {
        int s = ProductionControlState.clampCameraSlot(slot);
        SharedPreferences p = prefs(context);
        ProductionCameraSlot current = get(context, s);
        if (current.isInviteConsumed() && !current.getActiveStreamId().isEmpty()) return current;
        String active = current.getInviteToken();
        if (active.isEmpty()) return current;
        p.edit().putString(key(s, "active_stream_id"), active)
                .putString(key(s, "token"), newToken())
                .putBoolean(key(s, "consumed"), true)
                .putBoolean(key(s, "waiting"), false).apply();
        return get(context, s);
    }

    /** Ends the active guest and leaves the already-rotated invite ready for the next guest. */
    public static void markDisconnected(Context context, int slot) {
        int s = ProductionControlState.clampCameraSlot(slot);
        prefs(context).edit().remove(key(s, "active_stream_id"))
                .remove(key(s, "url"))
                .putBoolean(key(s, "waiting"), false)
                .putBoolean(key(s, "consumed"), false).apply();
    }

    @NonNull public static ProductionCameraSlot connect(Context context, int slot, String streamUrl) {
        ProductionCameraSlot current = get(context, slot);
        String url = streamUrl == null ? "" : streamUrl.trim();
        prefs(context).edit().putString(key(current.getSlot(), "url"), url)
                .putBoolean(key(current.getSlot(), "waiting"), url.isEmpty()).apply();
        return get(context, current.getSlot());
    }

    public static void clear(Context context, int slot) {
        int s = ProductionControlState.clampCameraSlot(slot);
        prefs(context).edit().remove(key(s, "name"))
                .remove(key(s, "active_stream_id"))
                .remove(key(s, "url"))
                .putString(key(s, "token"), newToken())
                .putBoolean(key(s, "consumed"), false)
                .putBoolean(key(s, "waiting"), false).apply();
    }

    /** Android app-to-app link. The component uses the installed FadCam variant package. */
    @NonNull public static String inviteLink(Context context, int slot) {
        ProductionCameraSlot c = get(context, slot);
        String component = context.getPackageName() + "/.TorchToggleActivity";
        return String.format(Locale.US,
                "intent://guest-camera?slot=%d&token=%s#Intent;scheme=fadcam;action=android.intent.action.VIEW;component=%s;end",
                c.getSlot(), c.getInviteToken(), component);
    }

    @NonNull public static String vdoPushLink(Context context, int slot) {
        ProductionCameraSlot c = get(context, slot);
        return VDO_BASE + "push=" + c.getInviteToken()
                + "&mobile&autostart&webcam&secure&label=Camera%20" + c.getSlot()
                + "&quality=2&denoise&echocancellation&autogain&noisegate=1";
    }

    @NonNull public static String viewerLink(Context context, int slot) {
        ProductionCameraSlot c = get(context, slot);
        String id = c.getActiveStreamId().isEmpty() ? c.getInviteToken() : c.getActiveStreamId();
        return VDO_BASE + "view=" + id + "&cleanviewer&videobitrate=2500&audiobitrate=96";
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
    private static String key(int slot, String suffix) { return "slot_" + slot + "_" + suffix; }
    private static String newToken() { return randomHex(18); }
    private static String randomHex(int bytes) {
        byte[] data = new byte[bytes];
        RANDOM.nextBytes(data);
        StringBuilder out = new StringBuilder(bytes * 2);
        for (byte b : data) out.append(String.format(Locale.US, "%02x", b & 0xff));
        return out.toString();
    }
}
