package com.fadcam.streaming.model;

/**
 * Stream quality preset configuration.
 * Uses standard camera resolutions with reduced bitrates and FPS caps for streaming.
 * Orientation is NOT stored here - users can select portrait/landscape separately.
 * Resolution comes from normal recording settings.
 */
public class StreamQuality {
    public enum Preset {
        // Streaming presets: bitrate + max FPS caps ONLY (resolution from normal recording)
        // This prevents high fps from using excessive bandwidth for streaming
        LOW("Low", 15, 1_000_000, "1 Mbps, max 15fps - Good for very poor connections"),
        MEDIUM("Medium", 24, 2_500_000, "2.5 Mbps, max 24fps - Balanced quality"),
        HIGH("High", 30, 5_000_000, "5 Mbps, max 30fps - HD streaming (default)"),
        ULTRA("Ultra", 30, 8_000_000, "8 Mbps, max 30fps - Maximum quality");
        
        private final String displayName;
        private final int fps;
        private final int bitrate;
        private final String description;
        
        Preset(String displayName, int fps, int bitrate, String description) {
            this.displayName = displayName;
            this.fps = fps;
            this.bitrate = bitrate;
            this.description = description;
        }
        
        public String getDisplayName() {
            return displayName;
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
        
        public String getBitrateString() {
            return (bitrate / 1_000_000) + " Mbps";
        }
        
        /**
         * Get preset from bitrate only (approximate match).
         */
        public static Preset fromSpecs(int bitrate) {
            if (bitrate <= 1_500_000) return LOW;
            if (bitrate <= 3_000_000) return MEDIUM;
            if (bitrate >= 8_000_000) return ULTRA;
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
            "{\"preset\": \"%s\", \"bitrate\": \"%s\", \"fps_cap\": %d, \"note\": \"resolution uses normal recording settings\"}",
            currentPreset.name().toLowerCase(),
            currentPreset.getBitrateString(),
            currentPreset.getFps()
        );
    }
}
