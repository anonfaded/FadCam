package com.fadcam.assets;

import androidx.annotation.NonNull;

/**
 * A heavy payload that can live either inside the APK or in app-private storage.
 *
 * <p>Full builds only. Lite never compiles or references any of this.
 */
public enum Asset {

    /** Native OpenCV runtime, needed by MOG2 motion detection. */
    OPENCV("opencv", "OpenCV", "5.1.0", "Apache-2.0", true,
            "Learns what the background looks like frame by frame, then flags what changes. "
                    + "Far more reliable than comparing raw pixels, which trips on light and "
                    + "shadow.",
            "Motion Lab"),

    /** Native ffmpeg runtime, needed by Faditor, remux, repair and batch export. */
    FFMPEG("ffmpeg", "ffmpeg-kit", "6.0.2 LTS", "GPL-3.0", true,
            "Reads and rewrites video files: cutting, re-encoding, merging and fixing "
                    + "container metadata.",
            "Faditor editor", "Records batch merge", "Video info", "Video player"),

    /** RF-DETR Nano detection graphs, needed by Motion Lab object detection. */
    MODELS("models", "RF-DETR Nano", "1.0", "Apache-2.0", false,
            "Recognises people, vehicles and animals in a single frame and reports where they "
                    + "are, so recordings can react to what is in the scene.",
            "Motion Lab object filtering", "Digital Forensics");

    /** Stable key used in the manifest and on disk. */
    public final String id;
    /** Real project name, shown as the row title. */
    public final String title;
    /** Component version, shown beside the name. */
    public final String version;

    /**
     * The version as displayed, always prefixed with "v" so it reads as a version rather than
     * as part of the name. One place, so no surface can render it differently.
     */
    @NonNull
    public String displayVersion() {
        return "v" + version;
    }
    /** SPDX identifier, shown in the UI. */
    public final String license;
    /** One sentence on what the component actually does for the user. */
    public final String purpose;
    /**
     * The FadCam features that stop working without this component, in the app's own wording.
     * Verified against the call sites, not guessed: see the classes named in each comment.
     */
    public final String[] usedBy;
    /**
     * True when the download is a zip of shared libraries.
     *
     * <p>Shared libraries compress by 50-63%, so they ship zipped. The
     * {@code .tflite} models compress by ~9.5%, which is not worth making the user
     * hold a 46 MB temp file plus a 46 MB copy while unzipping, so they ship raw.
     */
    public final boolean zipped;

    Asset(String id, String title, String version, String license, boolean zipped,
          String purpose, String... usedBy) {
        this.id = id;
        this.title = title;
        this.version = version;
        this.license = license;
        this.zipped = zipped;
        this.purpose = purpose;
        this.usedBy = usedBy;
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
