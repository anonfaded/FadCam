package com.fadcam.production;

import androidx.annotation.NonNull;

/** A producer camera input slot. A slot can be backed by a remote HLS/HTTP feed. */
public final class ProductionCameraSlot {
    public enum Status {
        EMPTY,
        WAITING,
        CONNECTED,
        ERROR
    }

    private final int slot;
    private final String guestName;
    private final String inviteToken;
    private final String streamUrl;
    private final Status status;

    public ProductionCameraSlot(int slot, String guestName, String inviteToken,
                                String streamUrl, Status status) {
        this.slot = ProductionControlState.clampCameraSlot(slot);
        this.guestName = guestName == null ? "" : guestName.trim();
        this.inviteToken = inviteToken == null ? "" : inviteToken.trim();
        this.streamUrl = streamUrl == null ? "" : streamUrl.trim();
        this.status = status == null ? Status.EMPTY : status;
    }

    public int getSlot() { return slot; }
    public String getGuestName() { return guestName; }
    public String getInviteToken() { return inviteToken; }
    public String getStreamUrl() { return streamUrl; }
    public Status getStatus() { return status; }

    @NonNull
    public ProductionCameraSlot withGuestName(String value) {
        return new ProductionCameraSlot(slot, value, inviteToken, streamUrl, status);
    }

    @NonNull
    public ProductionCameraSlot withStreamUrl(String value) {
        String url = value == null ? "" : value.trim();
        return new ProductionCameraSlot(slot, guestName, inviteToken, url,
                url.isEmpty() ? Status.WAITING : Status.CONNECTED);
    }

    @NonNull
    public ProductionCameraSlot withStatus(Status value) {
        return new ProductionCameraSlot(slot, guestName, inviteToken, streamUrl, value);
    }
}
