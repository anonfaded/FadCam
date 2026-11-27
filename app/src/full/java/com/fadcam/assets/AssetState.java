package com.fadcam.assets;

/**
 * Live state of one {@link Asset}.
 *
 * <p>State is computed on every read by {@link AssetRegistry}. Nothing caches a
 * boolean, so a file the user deletes in a file manager, or an OS cleaner removes,
 * shows up as {@link #MISSING} immediately instead of leaving a stale "Ready" row.
 */
public enum AssetState {

    /** Present inside the APK. Legacy builds keep working with no download. */
    BUNDLED,

    /** Installed in app storage, every file present, hash verified at install time. */
    READY,

    /** Not installed. A download is needed before the feature can run. */
    MISSING,

    /** A download is in flight. */
    DOWNLOADING,

    /** Installed, but a file is missing or its size no longer matches. */
    CORRUPT,

    /** A download or install failed. Reason is surfaced separately. */
    FAILED,

    /** Installed, but the bundled manifest expects a newer asset set. */
    OUTDATED;

    /** True when the asset can be used right now without a download. */
    public boolean isUsable() {
        return this == BUNDLED || this == READY;
    }

    /** True when the user can retry or repair from this state. */
    public boolean needsDownload() {
        return this == MISSING || this == CORRUPT || this == FAILED || this == OUTDATED;
    }
}
