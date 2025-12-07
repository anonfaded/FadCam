package com.fadcam.streaming.model;

/**
 * Stream quality preset configuration.
 * Defines resolution, FPS, and bitrate combinations.
 */
public class StreamQuality {
    public enum Preset {
        LOW("Low", 854, 480, 15, 2_000_000, "2 Mbps - Good for slow connections"),
        MEDIUM("Medium", 1280, 720, 24, 4_000_000, "4 Mbps - Balanced quality"),
        HIGH("High", 1920, 1080, 30, 8_000_000, "8 Mbps - HD streaming (default)"),
        ULTRA("Ultra", 1920, 1080, 30, 12_000_000, "12 Mbps - Maximum quality");
        
        private final String displayName;
        private final int width;
        private final int height;
        private final int fps;
        private final int bitrate;
        private final String description;
        
        Preset(String displayName, int width, int height, int fps, int bitrate, String description) {
            this.displayName = displayName;
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.bitrate = bitrate;
            this.description = description;
        }
        
        public String getDisplayName() {
            return displayName;
        }
        
        public int getWidth() {
            return width;
        }
        
        public int getHeight() {
            return height;
        }
        
        public int getFps() {
            return fps;
        }
        
        public int getBitrate() {
            return bitrate;
        }
        
        public String getDescription() {
            return description;
        }
        
        public String getResolutionString() {
            return width + "x" + height;
        }
        
        public String getBitrateString() {
            return (bitrate / 1_000_000) + " Mbps";
        }
        
        /**
         * Get preset from resolution and bitrate (approximate match).
         */
        public static Preset fromSpecs(int width, int height, int bitrate) {
            if (width <= 854) return LOW;
            if (width <= 1280) return MEDIUM;
            if (bitrate >= 10_000_000) return ULTRA;
            return HIGH;
        }
    }
    
    public enum StreamOrientation {
        PORTRAIT("Portrait", 0),
        LANDSCAPE("Landscape", 1),
        AUTO("Auto (use app setting)", -1);
        
        private final String displayName;
        private final int value;
        
        StreamOrientation(String displayName, int value) {
            this.displayName = displayName;
            this.value = value;
        }
        
        public String getDisplayName() {
            return displayName;
        }
        
        public int getValue() {
            return value;
        }
        
        public static StreamOrientation fromValue(int value) {
            for (StreamOrientation orientation : StreamOrientation.values()) {
                if (orientation.value == value) {
                    return orientation;
                }
            }
            return AUTO;
        }
    }
    
    private Preset currentPreset;
    private StreamOrientation streamOrientation;
    
    public StreamQuality() {
        this.currentPreset = Preset.HIGH; // Default
        this.streamOrientation = StreamOrientation.AUTO; // Default: use app setting
    }
    
    public Preset getCurrentPreset() {
        return currentPreset;
    }
    
    public void setPreset(Preset preset) {
        this.currentPreset = preset;
    }
    
    public StreamOrientation getStreamOrientation() {
        return streamOrientation;
    }
    
    public void setStreamOrientation(StreamOrientation orientation) {
        this.streamOrientation = orientation;
    }
    
    public String toJson() {
        return String.format(
            "{\"preset\": \"%s\", \"resolution\": \"%s\", \"fps\": %d, \"bitrate\": \"%s\", \"stream_orientation\": \"%s\"}",
            currentPreset.name().toLowerCase(),
            currentPreset.getResolutionString(),
            currentPreset.getFps(),
            currentPreset.getBitrateString(),
            streamOrientation.getDisplayName()
        );
    }
}
