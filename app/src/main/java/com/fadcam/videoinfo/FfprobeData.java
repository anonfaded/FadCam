package com.fadcam.videoinfo;

/**
 * Raw ffprobe values (Full-only builds). Null fields mean "not available";
 * the caller formats/overlays them onto its own metadata model.
 */
public class FfprobeData {
    public Long durationMs;
    public Long bitrate;
    public Integer width;
    public Integer height;
    public String codec;
    public String frameRate;
}
