package com.fadcam.production;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * Persists the six producer camera slots.
 *
 * The invite token is intentionally opaque. The current Android app does not
 * contain a public guest-ingest/signalling endpoint, so the generated link is
 * an invitation identity, while the producer can connect the slot to the
 * visitor's actual HLS/HTTP stream URL. This prevents the UI from pretending
 * that a nonexistent transport is connected.
 */
public final class ProductionCameraSlotManager {
    private static final String PREFS = "fadcam_production_camera_slots";
    private static final int SLOT_COUNT = 6;
    private static final SecureRandom RANDOM = new SecureRandom();

    private ProductionCameraSlotManager() {}

    @NonNull
    public static ProductionCameraSlot get(Context context, int slot) {
        int normalized = ProductionControlState.clampCameraSlot(slot);
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String token = prefs.getString(key(normalized, "token"), "");
        if (token.isEmpty()) {
            token = newToken();
            prefs.edit().putString(key(normalized, "token"), token).apply();
        }
        String name = prefs.getString(key(normalized, "name"), "");
        String url = prefs.getString(key(normalized, "url"), "");
        ProductionCameraSlot.Status status = url.isEmpty()
                ? (prefs.getBoolean(key(normalized, "waiting"), false)
                    ? ProductionCameraSlot.Status.WAITING
                    : ProductionCameraSlot.Status.EMPTY)
                : ProductionCameraSlot.Status.CONNECTED;
        return new ProductionCameraSlot(normalized, name, token, url, status);
    }

    @NonNull
    public static ProductionCameraSlot[] getAll(Context context) {
        ProductionCameraSlot[] slots = new ProductionCameraSlot[SLOT_COUNT];
        for (int i = 0; i < SLOT_COUNT; i++) slots[i] = get(context, i + 1);
        return slots;
    }

    @NonNull
    public static ProductionCameraSlot markInvited(Context context, int slot, String guestName) {
        ProductionCameraSlot current = get(context, slot);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(key(current.getSlot(), "name"), guestName == null ? "" : guestName.trim())
                .putBoolean(key(current.getSlot(), "waiting"), true)
                .apply();
        return get(context, current.getSlot());
    }

    @NonNull
    public static ProductionCameraSlot connect(Context context, int slot, String streamUrl) {
        ProductionCameraSlot current = get(context, slot);
        String url = streamUrl == null ? "" : streamUrl.trim();
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(key(current.getSlot(), "url"), url)
                .putBoolean(key(current.getSlot(), "waiting"), url.isEmpty())
                .apply();
        return get(context, current.getSlot());
    }

    public static void clear(Context context, int slot) {
        int normalized = ProductionControlState.clampCameraSlot(slot);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(key(normalized, "name"))
                .remove(key(normalized, "url"))
                .putBoolean(key(normalized, "waiting"), false)
                .apply();
    }

    @NonNull
    public static String inviteLink(Context context, int slot) {
        ProductionCameraSlot current = get(context, slot);
        return String.format(Locale.US, "fadcam://guest-camera?slot=%d&token=%s",
                current.getSlot(), current.getInviteToken());
    }

    private static String key(int slot, String suffix) {
        return "slot_" + slot + "_" + suffix;
    }

    private static String newToken() {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        StringBuilder out = new StringBuilder(24);
        for (byte b : bytes) out.append(String.format(Locale.US, "%02x", b & 0xff));
        return out.toString();
    }
}
