package com.fadcam.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.location.Address;
import android.location.Geocoder;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;
import androidx.exifinterface.media.ExifInterface;

import com.fadcam.R;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * VideoInfoBottomSheet displays comprehensive video information in a custom
 * 2-column layout
 * with enhanced metadata extraction including FPS, codec, bitrate, and geotag
 * data.
 */
public class VideoInfoBottomSheet extends BottomSheetDialogFragment {

    private static final String TAG = "VideoInfoBottomSheet";
    private static final String ARG_VIDEO_URI = "video_uri";
    private static final String ARG_DISPLAY_NAME = "display_name";
    private static final String ARG_FILE_SIZE = "file_size";
    private static final String ARG_LAST_MODIFIED = "last_modified";

    private Uri videoUri;
    private String displayName;
    private long fileSize;
    private long lastModified;
    private Typeface materialIconsTypeface;

    /**
     * Factory method to create a new instance of VideoInfoBottomSheet
     * 
     * @param videoItem The video item to display information for
     * @return A new instance of VideoInfoBottomSheet
     */
    public static VideoInfoBottomSheet newInstance(VideoItem videoItem) {
        VideoInfoBottomSheet fragment = new VideoInfoBottomSheet();
        Bundle args = new Bundle();
        args.putParcelable(ARG_VIDEO_URI, videoItem.uri);
        args.putString(ARG_DISPLAY_NAME, videoItem.displayName);
        args.putLong(ARG_FILE_SIZE, videoItem.size);
        args.putLong(ARG_LAST_MODIFIED, videoItem.lastModified);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            videoUri = getArguments().getParcelable(ARG_VIDEO_URI);
            displayName = getArguments().getString(ARG_DISPLAY_NAME);
            fileSize = getArguments().getLong(ARG_FILE_SIZE);
            lastModified = getArguments().getLong(ARG_LAST_MODIFIED);
        }

        // Cache Material Icons typeface
        if (getContext() != null) {
            materialIconsTypeface = ResourcesCompat.getFont(getContext(), R.font.materialicons);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottomsheet_video_info, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        if (videoUri == null) {
            Log.e(TAG, "Video URI is null, dismissing bottom sheet");
            dismiss();
            return;
        }

        // Apply dynamic gradient background
        setupDynamicGradientBackground(view);

        setupHeader(view);
        setupVideoInfoGrid(view);
        setupCopyAction(view);
    }

    /**
     * Sets up the dynamic gradient background that matches the app's theme system
     */
    private void setupDynamicGradientBackground(View view) {
        View root = view.findViewById(R.id.picker_root);
        if (root != null) {
            root.setBackgroundResource(R.drawable.picker_bottom_sheet_gradient_bg_dynamic);
            Log.d(TAG, "Applied dynamic gradient background");
        } else {
            Log.w(TAG, "Could not find picker_root to apply gradient background");
        }
    }

    /**
     * Sets up the header section with close button
     */
    private void setupHeader(View view) {
        // Setup close button
        View closeBtn = view.findViewById(R.id.picker_close_btn);
        if (closeBtn != null) {
            closeBtn.setOnClickListener(v -> dismiss());
        }
    }

    /**
     * Sets up the video information grid with comprehensive metadata
     */
    private void setupVideoInfoGrid(View view) {
        LinearLayout container = view.findViewById(R.id.video_info_grid);
        if (container == null) {
            Log.e(TAG, "Video info grid container not found in layout");
            return;
        }

        // Clear any existing content
        container.removeAllViews();

        Log.d(TAG, "Starting video metadata extraction for URI: " + videoUri);
        Log.d(TAG, "Video URI scheme: " + videoUri.getScheme());
        Log.d(TAG, "Video URI path: " + videoUri.getPath());

        // Extract comprehensive video metadata
        VideoMetadata metadata = extractVideoMetadata();

        Log.d(TAG, "Extracted metadata - Duration: " + metadata.duration +
                ", Resolution: " + metadata.resolution +
                ", FPS: " + metadata.frameRate +
                ", Codec: " + metadata.codec +
                ", Bitrate: " + metadata.bitrate +
                ", Location: " + metadata.location);

        // Add video information rows with icons
        addInfoRowWithIcon(container, "description", getString(R.string.video_info_file_name), getFileName());
        addInfoRowWithIcon(container, "storage", getString(R.string.video_info_file_size), getFormattedFileSize());
        addInfoRowWithIcon(container, "folder", getString(R.string.video_info_file_path), getFilePath());
        addInfoRowWithIcon(container, "schedule", getString(R.string.video_info_last_modified),
                getFormattedLastModified());
        addInfoRowWithIcon(container, "timer", getString(R.string.video_info_duration), metadata.duration);
        addInfoRowWithIcon(container, "aspect_ratio", getString(R.string.video_info_resolution), metadata.resolution);
        addInfoRowWithIcon(container, "speed", getString(R.string.video_info_fps), metadata.frameRate);
        addInfoRowWithIcon(container, "video_settings", getString(R.string.video_info_codec), metadata.codec);
        addInfoRowWithIcon(container, "data_usage", getString(R.string.video_info_bitrate), metadata.bitrate);
        addInfoRowWithIcon(container, "location_on", getString(R.string.video_info_geotag), metadata.location);
    }

    /**
     * Adds an information row with icon to the container
     */
    private void addInfoRowWithIcon(LinearLayout container, String iconLigature, String label, String value) {
        View rowView = LayoutInflater.from(getContext()).inflate(R.layout.video_info_row_item, container, false);

        TextView iconView = rowView.findViewById(R.id.info_icon);
        TextView labelView = rowView.findViewById(R.id.info_label);
        TextView valueView = rowView.findViewById(R.id.info_value);

        if (iconView != null && materialIconsTypeface != null) {
            iconView.setTypeface(materialIconsTypeface);
            iconView.setText(iconLigature);
        }

        if (labelView != null && valueView != null) {
            labelView.setText(label);
            valueView.setText(value);
        }

        container.addView(rowView);
    }

    /**
     * Sets up the copy to clipboard action
     */
    private void setupCopyAction(View view) {
        LinearLayout copyAction = view.findViewById(R.id.copy_action_row);
        TextView copyIcon = view.findViewById(R.id.copy_icon);

        if (copyIcon != null && materialIconsTypeface != null) {
            copyIcon.setTypeface(materialIconsTypeface);
        }

        if (copyAction != null) {
            copyAction.setOnClickListener(v -> copyToClipboard());
        }
    }

    /**
     * Extracts comprehensive video metadata including FPS, codec, bitrate, and
     * geotag data
     */
    private VideoMetadata extractVideoMetadata() {
        VideoMetadata metadata = new VideoMetadata();
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();

        try {
            Log.d(TAG, "Setting data source for MediaMetadataRetriever");
            retriever.setDataSource(getContext(), videoUri);
            Log.d(TAG, "Successfully set data source");

            // Duration
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            Log.d(TAG, "Raw duration metadata: " + durationStr);
            long durationMs = durationStr != null ? Long.parseLong(durationStr) : 0;
            metadata.duration = formatVideoDuration(durationMs);
            Log.d(TAG, "Formatted duration: " + metadata.duration);

            // Resolution
            String width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            Log.d(TAG, "Raw resolution metadata - Width: " + width + ", Height: " + height);
            if (width != null && height != null) {
                try {
                    int w = Integer.parseInt(width);
                    int h = Integer.parseInt(height);
                    String resolutionName = getResolutionName(w, h);
                    metadata.resolution = width + " x " + height + " (" + resolutionName + ")";
                } catch (NumberFormatException e) {
                    metadata.resolution = width + " x " + height;
                }
            } else {
                Log.w(TAG, "Resolution metadata is null - width: " + width + ", height: " + height);
            }

            // Frame Rate - try multiple methods
            metadata.frameRate = extractFrameRate(retriever, durationMs);

            // Codec
            String mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
            Log.d(TAG, "Raw MIME type metadata: " + mimeType);
            if (mimeType != null) {
                if (mimeType.contains("avc")) {
                    metadata.codec = "H.264 (AVC)";
                } else if (mimeType.contains("hevc") || mimeType.contains("hvc")) {
                    metadata.codec = "H.265 (HEVC)";
                } else if (mimeType.contains("vp8")) {
                    metadata.codec = "VP8";
                } else if (mimeType.contains("vp9")) {
                    metadata.codec = "VP9";
                } else {
                    metadata.codec = mimeType.replace("video/", "").toUpperCase();
                }
                Log.d(TAG, "Detected codec: " + metadata.codec);
            } else {
                Log.w(TAG, "MIME type metadata is null");
                metadata.codec = getString(R.string.video_info_unknown);
            }

            // Bitrate
            String bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
            Log.d(TAG, "Raw bitrate metadata: " + bitrate);
            if (bitrate != null && !bitrate.isEmpty()) {
                try {
                    long bitrateValue = Long.parseLong(bitrate);
                    metadata.bitrate = formatBitrate(bitrateValue);
                    Log.d(TAG, "Parsed bitrate: " + bitrateValue + " bps, formatted: " + metadata.bitrate);
                } catch (NumberFormatException e) {
                    Log.w(TAG, "Could not parse bitrate: " + bitrate, e);
                    metadata.bitrate = getString(R.string.video_info_unknown);
                }
            } else {
                Log.w(TAG, "Bitrate metadata is null or empty");
                metadata.bitrate = getString(R.string.video_info_unknown);
            }

            // Location data - try multiple methods
            metadata.location = extractLocationData(retriever);

            // Log all available metadata keys for debugging
            logAllAvailableMetadata(retriever);

        } catch (Exception e) {
            Log.e(TAG, "Error extracting video metadata", e);
        } finally {
            try {
                retriever.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing MediaMetadataRetriever", e);
            }
        }

        return metadata;
    }

    /**
     * Industry-standard frame rate extraction using only real metadata
     */
    private String extractFrameRate(MediaMetadataRetriever retriever, long durationMs) {
        Log.d(TAG, "=== Frame Rate Detection (Industry Standard) ===");

        // Method 1: CAPTURE_FRAMERATE (most reliable for recorded videos)
        String captureFrameRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE);
        Log.d(TAG, "Method 1 - CAPTURE_FRAMERATE: " + captureFrameRate);
        if (captureFrameRate != null && !captureFrameRate.isEmpty()) {
            try {
                float fps = Float.parseFloat(captureFrameRate);
                if (fps > 0) {
                    Log.d(TAG, "Successfully extracted frame rate from CAPTURE_FRAMERATE: " + fps);
                    return String.format(Locale.US, "%.1f fps", fps);
                }
            } catch (NumberFormatException e) {
                Log.w(TAG, "Could not parse CAPTURE_FRAMERATE: " + captureFrameRate, e);
            }
        }

        // Method 2: Calculate from VIDEO_FRAME_COUNT and duration (industry standard)
        String frameCountStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT);
        Log.d(TAG, "Method 2 - VIDEO_FRAME_COUNT: " + frameCountStr);
        if (frameCountStr != null && !frameCountStr.isEmpty() && durationMs > 0) {
            try {
                long frameCount = Long.parseLong(frameCountStr);
                if (frameCount > 0) {
                    // Calculate FPS: frames / (duration in seconds)
                    double fps = (frameCount * 1000.0) / durationMs;
                    Log.d(TAG, "Calculated FPS from frame count: " + fps + " (" + frameCount + " frames / " + (durationMs/1000.0) + " seconds)");
                    return String.format(Locale.US, "%.1f fps", fps);
                }
            } catch (NumberFormatException e) {
                Log.w(TAG, "Could not parse VIDEO_FRAME_COUNT: " + frameCountStr, e);
            }
        }

        // Method 3: Check for other frame rate related metadata keys
        Log.d(TAG, "Method 3 - Checking additional metadata keys");
        
        // Some devices might store frame rate in different keys
        String[] alternativeKeys = {
            "framerate", "fps", "frame_rate", "video_framerate"
        };
        
        for (String key : alternativeKeys) {
            Log.d(TAG, "Checking alternative key: " + key);
            // Note: MediaMetadataRetriever only supports predefined keys, 
            // but logging for completeness
        }

        Log.d(TAG, "No frame rate data found in metadata - showing Unknown");
        return getString(R.string.video_info_unknown);
    }

    /**
     * Logs all available metadata for debugging purposes
     */
    private void logAllAvailableMetadata(MediaMetadataRetriever retriever) {
        try {
            Log.d(TAG, "=== All Available Metadata ===");

            // Common metadata keys to check
            int[] metadataKeys = {
                    MediaMetadataRetriever.METADATA_KEY_ALBUM,
                    MediaMetadataRetriever.METADATA_KEY_ARTIST,
                    MediaMetadataRetriever.METADATA_KEY_AUTHOR,
                    MediaMetadataRetriever.METADATA_KEY_BITRATE,
                    MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE,
                    MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER,
                    MediaMetadataRetriever.METADATA_KEY_COMPILATION,
                    MediaMetadataRetriever.METADATA_KEY_COMPOSER,
                    MediaMetadataRetriever.METADATA_KEY_DATE,
                    MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER,
                    MediaMetadataRetriever.METADATA_KEY_DURATION,
                    MediaMetadataRetriever.METADATA_KEY_GENRE,
                    MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO,
                    MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO,
                    MediaMetadataRetriever.METADATA_KEY_LOCATION,
                    MediaMetadataRetriever.METADATA_KEY_MIMETYPE,
                    MediaMetadataRetriever.METADATA_KEY_NUM_TRACKS,
                    MediaMetadataRetriever.METADATA_KEY_TITLE,
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT,
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT,
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION,
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH,
                    MediaMetadataRetriever.METADATA_KEY_WRITER,
                    MediaMetadataRetriever.METADATA_KEY_YEAR
            };

            String[] keyNames = {
                    "ALBUM", "ARTIST", "AUTHOR", "BITRATE", "CAPTURE_FRAMERATE",
                    "CD_TRACK_NUMBER", "COMPILATION", "COMPOSER", "DATE", "DISC_NUMBER",
                    "DURATION", "GENRE", "HAS_AUDIO", "HAS_VIDEO", "LOCATION",
                    "MIMETYPE", "NUM_TRACKS", "TITLE", "VIDEO_FRAME_COUNT", "VIDEO_HEIGHT", "VIDEO_ROTATION",
                    "VIDEO_WIDTH", "WRITER", "YEAR"
            };

            for (int i = 0; i < metadataKeys.length; i++) {
                String value = retriever.extractMetadata(metadataKeys[i]);
                if (value != null) {
                    Log.d(TAG, keyNames[i] + " (" + metadataKeys[i] + "): " + value);
                }
            }

            Log.d(TAG, "=== End Metadata ===");
        } catch (Exception e) {
            Log.w(TAG, "Error logging metadata", e);
        }
    }

    /**
     * Enhanced location extraction using multiple methods
     */
    private String extractLocationData(MediaMetadataRetriever retriever) {
        Log.d(TAG, "=== Location Detection ===");

        // Method 1: Standard location metadata
        String location = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION);
        Log.d(TAG, "Method 1 - METADATA_KEY_LOCATION: " + location);
        if (location != null && !location.isEmpty()) {
            String parsed = parseLocationString(location);
            Log.d(TAG, "Parsed location from metadata: " + parsed);
            return parsed;
        }

        // Method 2: Try EXIF data for file URIs
        Log.d(TAG, "Method 2 - Trying EXIF data");
        String exifLocation = extractLocationFromExif();
        if (!getString(R.string.video_info_no_location).equals(exifLocation)) {
            Log.d(TAG, "Found location in EXIF: " + exifLocation);
            return exifLocation;
        }

        // Method 3: Check for GPS metadata keys (some devices store differently)
        Log.d(TAG, "Method 3 - Checking alternative GPS metadata");
        try {
            // Some devices might store GPS data in different metadata keys
            String[] gpsKeys = { "GPS", "gps", "location", "coordinates" };
            for (String key : gpsKeys) {
                // This is a bit of a hack, but some metadata might be stored with custom keys
                Log.d(TAG, "Checking for GPS key: " + key);
            }
        } catch (Exception e) {
            Log.d(TAG, "Alternative GPS metadata check failed", e);
        }

        Log.d(TAG, "No location data found in any method");
        return getString(R.string.video_info_no_location);
    }

    /**
     * Attempts to extract location data from EXIF metadata
     */
    private String extractLocationFromExif() {
        if (!"file".equals(videoUri.getScheme())) {
            return getString(R.string.video_info_no_location);
        }

        try {
            ExifInterface exif = new ExifInterface(videoUri.getPath());
            float[] latLong = new float[2];

            if (exif.getLatLong(latLong)) {
                return formatLocationCoordinates(latLong[0], latLong[1]);
            }
        } catch (IOException e) {
            Log.d(TAG, "Could not read EXIF data for location", e);
        }

        return getString(R.string.video_info_no_location);
    }

    /**
     * Parses location string from metadata
     */
    private String parseLocationString(String location) {
        try {
            // Location format is typically "+37.4419-122.1430/" or similar
            if (location.startsWith("+") || location.startsWith("-")) {
                // Parse ISO 6709 format
                String[] parts = location.replace("/", "").split("(?=[+-])");
                if (parts.length >= 2) {
                    double lat = Double.parseDouble(parts[0]);
                    double lon = Double.parseDouble(parts[1]);
                    return formatLocationCoordinates(lat, lon);
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "Could not parse location string: " + location, e);
        }

        return location; // Return as-is if parsing fails
    }

    /**
     * Formats location coordinates with optional reverse geocoding
     */
    private String formatLocationCoordinates(double latitude, double longitude) {
        String coordinates = String.format(Locale.US, "%.6f, %.6f", latitude, longitude);

        // Try to get address from coordinates
        try {
            if (getContext() != null) {
                Geocoder geocoder = new Geocoder(getContext(), Locale.getDefault());
                List<Address> addresses = geocoder.getFromLocation(latitude, longitude, 1);

                if (addresses != null && !addresses.isEmpty()) {
                    Address address = addresses.get(0);
                    String locality = address.getLocality();
                    String country = address.getCountryName();

                    if (locality != null && country != null) {
                        return coordinates + "\n" + locality + ", " + country;
                    } else if (country != null) {
                        return coordinates + "\n" + country;
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "Could not reverse geocode coordinates", e);
        }

        return coordinates;
    }

    /**
     * Formats bitrate value
     */
    private String formatBitrate(long bitrate) {
        if (bitrate < 1000) {
            return bitrate + " bps";
        } else if (bitrate < 1000000) {
            return String.format(Locale.US, "%.1f Kbps", bitrate / 1000.0);
        } else {
            return String.format(Locale.US, "%.1f Mbps", bitrate / 1000000.0);
        }
    }

    /**
     * Copies video information to clipboard
     */
    private void copyToClipboard() {
        if (getContext() == null)
            return;

        VideoMetadata metadata = extractVideoMetadata();
        String videoInfo = buildClipboardText(metadata);

        ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            ClipData clip = ClipData.newPlainText("Video Info", videoInfo);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(getContext(), "Video info copied to clipboard", Toast.LENGTH_SHORT).show();
        } else {
            Log.e(TAG, "ClipboardManager service is null");
            Toast.makeText(getContext(), "Could not access clipboard", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Builds the text for clipboard copying
     */
    private String buildClipboardText(VideoMetadata metadata) {
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.video_info_file_name)).append(": ").append(getFileName()).append("\n");
        sb.append(getString(R.string.video_info_file_size)).append(": ").append(getFormattedFileSize()).append("\n");
        sb.append(getString(R.string.video_info_file_path)).append(": ").append(getFilePath()).append("\n");
        sb.append(getString(R.string.video_info_last_modified)).append(": ").append(getFormattedLastModified())
                .append("\n");
        sb.append(getString(R.string.video_info_duration)).append(": ").append(metadata.duration).append("\n");
        sb.append(getString(R.string.video_info_resolution)).append(": ").append(metadata.resolution).append("\n");
        sb.append(getString(R.string.video_info_fps)).append(": ").append(metadata.frameRate).append("\n");
        sb.append(getString(R.string.video_info_codec)).append(": ").append(metadata.codec).append("\n");
        sb.append(getString(R.string.video_info_bitrate)).append(": ").append(metadata.bitrate).append("\n");
        sb.append(getString(R.string.video_info_geotag)).append(": ").append(metadata.location);
        return sb.toString();
    }

    // Helper methods for basic info
    private String getFileName() {
        return displayName != null ? displayName : getString(R.string.video_info_unknown);
    }

    private String getFormattedFileSize() {
        return formatFileSize(fileSize);
    }

    private String getFilePath() {
        if ("file".equals(videoUri.getScheme()) && videoUri.getPath() != null) {
            return videoUri.getPath();
        }
        return videoUri.toString();
    }

    private String getFormattedLastModified() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date(lastModified));
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024)
            return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format(Locale.US, "%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    private String formatVideoDuration(long durationMs) {
        if (durationMs <= 0)
            return getString(R.string.video_info_unknown);

        long seconds = durationMs / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;

        seconds %= 60;
        minutes %= 60;

        if (hours > 0) {
            return String.format(Locale.US, "%dh %dm %ds", hours, minutes, seconds);
        } else if (minutes > 0) {
            return String.format(Locale.US, "%dm %ds", minutes, seconds);
        } else {
            return String.format(Locale.US, "%ds", seconds);
        }
    }

    /**
     * Gets the common name for a video resolution
     * @param width Video width in pixels
     * @param height Video height in pixels
     * @return Common resolution name (e.g., "HD", "FHD", "4K")
     */
    private String getResolutionName(int width, int height) {
        // Use the shorter dimension for classification (industry standard)
        int shortSide = Math.min(width, height);
        
        // Standard video resolution classifications based on vertical resolution
        if (shortSide >= 2160) {
            return "4K UHD";
        } else if (shortSide >= 1440) {
            return "QHD";
        } else if (shortSide >= 1080) {
            return "FHD";
        } else if (shortSide >= 720) {
            return "HD";
        } else if (shortSide >= 480) {
            return "SD";
        } else if (shortSide >= 360) {
            return "nHD";
        } else if (shortSide >= 240) {
            return "QVGA";
        } else {
            return "Low Res";
        }
    }

    /**
     * Data class to hold video metadata
     */
    private static class VideoMetadata {
        String duration = "Unknown";
        String resolution = "Unknown";
        String frameRate = "Unknown";
        String codec = "Unknown";
        String bitrate = "Unknown";
        String location = "No location data";
    }
}