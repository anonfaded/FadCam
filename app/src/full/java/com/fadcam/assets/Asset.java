package com.fadcam.assets;

/**
 * A heavy payload that can live either inside the APK or in app-private storage.
 *
 * <p>Full builds only. Lite never compiles or references any of this.
 */
public enum Asset {

    /** Native OpenCV runtime, needed by MOG2 motion detection. */
    OPENCV("opencv", "Motion detection engine", "OpenCV", "Apache-2.0", true),

    /** Native ffmpeg runtime, needed by Faditor, remux, repair and batch export. */
    FFMPEG("ffmpeg", "Video processing", "ffmpeg-kit", "GPL-3.0", true),

    /** RF-DETR Nano detection graphs, needed by Motion Lab object detection. */
    MODELS("models", "AI detection models", "RF-DETR Nano", "Apache-2.0", false);

    /** Stable key used in the manifest and on disk. */
    public final String id;
    /** Row title in the download manager. */
    public final String title;
    /** Component name shown next to version and size. */
    public final String componentName;
    /** SPDX identifier, shown in the UI. */
    public final String license;
    /**
     * True when the download is a zip of shared libraries.
     *
     * <p>Shared libraries compress by 50-63%, so they ship zipped. The
     * {@code .tflite} models compress by ~9.5%, which is not worth making the user
     * hold a 46 MB temp file plus a 46 MB copy while unzipping, so they ship raw.
     */
    public final boolean zipped;

    Asset(String id, String title, String componentName, String license, boolean zipped) {
        this.id = id;
        this.title = title;
        this.componentName = componentName;
        this.license = license;
        this.zipped = zipped;
    }

    /** Returns the asset with this manifest id, or null when unknown. */
    public static Asset fromId(String id) {
        if (id == null) {
            return null;
        }
        for (Asset a : values()) {
            if (a.id.equals(id)) {
                return a;
            }
        }
        return null;
    }
}
