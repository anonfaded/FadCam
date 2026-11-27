package com.fadcam.assets;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Thrown when a feature needs an asset that is missing, incomplete or unloadable.
 *
 * <p>Deliberately a checked exception at the call sites that matter: the UI layer
 * catches it and renders a download banner with a reason. It must never reach the
 * user as a crash, and it must never be swallowed into a silent no-op — every failure
 * maps to a visible state with a human sentence.
 */
public final class AssetException extends RuntimeException {

    @NonNull
    private final Asset asset;

    public AssetException(@NonNull Asset asset, @NonNull String message) {
        super(message);
        this.asset = asset;
    }

    public AssetException(@NonNull Asset asset, @NonNull String message, @Nullable Throwable cause) {
        super(message, cause);
        this.asset = asset;
    }

    /** The asset the user needs to download or repair. */
    @NonNull
    public Asset asset() {
        return asset;
    }
}
