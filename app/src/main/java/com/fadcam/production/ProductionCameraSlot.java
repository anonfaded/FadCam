package com.fadcam.production;

import androidx.annotation.NonNull;

/** Producer camera slot backed by a one-use WebRTC guest invitation. */
public final class ProductionCameraSlot {
    public enum Status { EMPTY, WAITING, CONNECTED, ERROR }

    private final int slot;
    private final String guestName;
    private final String inviteToken;
    private final String activeStreamId;
    private final String streamUrl;
    private final boolean inviteConsumed;
    private final Status status;

    public ProductionCameraSlot(int slot, String guestName, String inviteToken,
                                String streamUrl, Status status) {
        this(slot, guestName, inviteToken, "", streamUrl, false, status);
    }

    public ProductionCameraSlot(int slot, String guestName, String inviteToken,
                                String activeStreamId, String streamUrl,
                                boolean inviteConsumed, Status status) {
        this.slot = ProductionControlState.clampCameraSlot(slot);
        this.guestName = guestName == null ? "" : guestName.trim();
        this.inviteToken = inviteToken == null ? "" : inviteToken.trim();
        this.activeStreamId = activeStreamId == null ? "" : activeStreamId.trim();
        this.streamUrl = streamUrl == null ? "" : streamUrl.trim();
        this.inviteConsumed = inviteConsumed;
        this.status = status == null ? Status.EMPTY : status;
    }

    public int getSlot() { return slot; }
    public String getGuestName() { return guestName; }
    public String getInviteToken() { return inviteToken; }
    public String getActiveStreamId() { return activeStreamId; }
    public String getStreamUrl() { return streamUrl; }
    public boolean isInviteConsumed() { return inviteConsumed; }
    public Status getStatus() { return status; }

    @NonNull public ProductionCameraSlot withGuestName(String value) {
        return new ProductionCameraSlot(slot, value, inviteToken, activeStreamId, streamUrl, inviteConsumed, status);
    }

    @NonNull public ProductionCameraSlot withStreamUrl(String value) {
        String url = value == null ? "" : value.trim();
        return new ProductionCameraSlot(slot, guestName, inviteToken, activeStreamId, url, inviteConsumed,
                url.isEmpty() ? Status.WAITING : Status.CONNECTED);
    }

    @NonNull public ProductionCameraSlot withStatus(Status value) {
        return new ProductionCameraSlot(slot, guestName, inviteToken, activeStreamId, streamUrl, inviteConsumed, value);
    }
}
