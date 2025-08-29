package com.fadcam.ui.faditor.models;

/**
 * Represents video file properties and encoding information.
 * Used to determine optimal processing methods and compatibility.
 */
public class VideoMetadata {
    
    private String codec;
    private int width;
    private int height;
    private long duration;
    private int bitrate;
    private float frameRate;
    private String colorFormat;
    private String containerFormat;
    private boolean hasAudio;
    private String audioCodec;
    
    public VideoMetadata() {
        // Default constructor
    }
    
    public VideoMetadata(String codec, int width, int height, long duration, 
                        int bitrate, float frameRate) {
        this.codec = codec;
        this.width = width;
        this.height = height;
        this.duration = duration;
        this.bitrate = bitrate;
        this.frameRate = frameRate;
    }
    
    // Getters and setters
    public String getCodec() {
        return codec;
    }
    
    public void setCodec(String codec) {
        this.codec = codec;
    }
    
    public int getWidth() {
        return width;
    }
    
    public void setWidth(int width) {
        this.width = width;
    }
    
    public int getHeight() {
        return height;
    }
    
    public void setHeight(int height) {
        this.height = height;
    }
    
    public long getDuration() {
        return duration;
    }
    
    public void setDuration(long duration) {
        this.duration = duration;
    }
    
    public int getBitrate() {
        return bitrate;
    }
    
    public void setBitrate(int bitrate) {
        this.bitrate = bitrate;
    }
    
    public float getFrameRate() {
        return frameRate;
    }
    
    public void setFrameRate(float frameRate) {
        this.frameRate = frameRate;
    }
    
    public String getColorFormat() {
        return colorFormat;
    }
    
    public void setColorFormat(String colorFormat) {
        this.colorFormat = colorFormat;
    }
    
    public String getContainerFormat() {
        return containerFormat;
    }
    
    public void setContainerFormat(String containerFormat) {
        this.containerFormat = containerFormat;
    }
    
    public boolean hasAudio() {
        return hasAudio;
    }
    
    public void setHasAudio(boolean hasAudio) {
        this.hasAudio = hasAudio;
    }
    
    public String getAudioCodec() {
        return audioCodec;
    }
    
    public void setAudioCodec(String audioCodec) {
        this.audioCodec = audioCodec;
    }
    
    /**
     * Check if lossless trimming is compatible with this video
     */
    public boolean isLosslessTrimCompatible() {
        // H.264 and H.265 in MP4 containers typically support lossless trimming
        return (codec != null && (codec.contains("avc") || codec.contains("hevc")) &&
                containerFormat != null && containerFormat.equalsIgnoreCase("mp4"));
    }
    
    /**
     * Check if this operation requires re-encoding
     */
    public boolean requiresReencoding(EditOperation operation) {
        if (operation.getType() == EditOperation.Type.TRIM) {
            return !isLosslessTrimCompatible();
        }
        return true; // Other operations may require re-encoding
    }
    
    /**
     * Get resolution as a formatted string
     */
    public String getResolutionString() {
        return width + "x" + height;
    }
    
    /**
     * Get file size estimate in MB for given duration
     */
    public long getEstimatedSizeMB(long durationMs) {
        if (bitrate <= 0 || durationMs <= 0) {
            return 0;
        }
        // Convert bitrate (bits/sec) to MB for given duration
        long durationSec = durationMs / 1000;
        return (bitrate * durationSec) / (8 * 1024 * 1024);
    }
    
    /**
     * Check if this is a high-resolution video (1080p or higher)
     */
    public boolean isHighResolution() {
        return height >= 1080;
    }
    
    /**
     * Check if this is a 4K video
     */
    public boolean is4K() {
        return height >= 2160;
    }
    
    /**
     * Get aspect ratio as a float
     */
    public float getAspectRatio() {
        return height > 0 ? (float) width / height : 0f;
    }
    
    /**
     * Get a human-readable duration string
     */
    public String getDurationString() {
        long seconds = duration / 1000;
        long minutes = seconds / 60;
        seconds = seconds % 60;
        long hours = minutes / 60;
        minutes = minutes % 60;
        
        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format("%d:%02d", minutes, seconds);
        }
    }
    
    /**
     * Check if the video format is commonly supported
     */
    public boolean isCommonFormat() {
        if (codec == null || containerFormat == null) {
            return false;
        }
        
        String codecLower = codec.toLowerCase();
        String containerLower = containerFormat.toLowerCase();
        
        return (codecLower.contains("avc") || codecLower.contains("h264") || codecLower.contains("hevc")) &&
               (containerLower.equals("mp4") || containerLower.equals("mov"));
    }
    
    /**
     * Validate that all required metadata is present
     */
    public boolean isComplete() {
        return codec != null && !codec.isEmpty() &&
               width > 0 && height > 0 &&
               duration > 0 &&
               containerFormat != null && !containerFormat.isEmpty();
    }
    
    @Override
    public String toString() {
        return "VideoMetadata{" +
                "codec='" + codec + '\'' +
                ", resolution=" + getResolutionString() +
                ", duration=" + duration +
                ", bitrate=" + bitrate +
                ", frameRate=" + frameRate +
                ", hasAudio=" + hasAudio +
                '}';
    }
}