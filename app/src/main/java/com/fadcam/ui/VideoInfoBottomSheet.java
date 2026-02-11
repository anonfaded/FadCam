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

import com.arthenica.ffmpegkit.FFprobeKit;
import com.arthenica.ffmpegkit.MediaInformation;
import com.arthenica.ffmpegkit.MediaInformationSession;
import com.arthenica.ffmpegkit.StreamInformation;
import com.fadcam.R;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.io.IOException;
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
     * Extracts comprehensive video metadata using FFprobeKit for reliable
     * metadata extraction from fragmented MP4 and all video formats.
     */
    private VideoMetadata extractVideoMetadata() {
        VideoMetadata metadata = new VideoMetadata();

        try {
            // Get file path for FFprobe - handle content:// URIs using SAF protocol
            String filePath = getFFprobePath();
            Log.d(TAG, "Extracting metadata with FFprobe for: " + filePath);

            // Use FFprobeKit to get accurate metadata
            MediaInformationSession session = FFprobeKit.getMediaInformation(filePath);
            MediaInformation info = session.getMediaInformation();

            if (info != null) {
                // Duration from container (in seconds, converted to ms)
                String durationStr = info.getDuration();
                if (durationStr != null) {
                    try {
                        double durationSec = Double.parseDouble(durationStr);
                        long durationMs = (long) (durationSec * 1000);
                        metadata.duration = formatVideoDuration(durationMs);
                        Log.d(TAG, "FFprobe duration: " + durationSec + "s -> " + metadata.duration);
                    } catch (NumberFormatException e) {
                        Log.w(TAG, "Failed to parse duration: " + durationStr);
                    }
                }

                // Bitrate from container
                String bitrateStr = info.getBitrate();
                if (bitrateStr != null) {
                    try {
                        long bitrate = Long.parseLong(bitrateStr);
                        metadata.bitrate = formatBitrate(bitrate);
                        Log.d(TAG, "FFprobe bitrate: " + bitrate + " -> " + metadata.bitrate);
                    } catch (NumberFormatException e) {
                        Log.w(TAG, "Failed to parse bitrate: " + bitrateStr);
                    }
                }

                // Get video stream info
                List<StreamInformation> streams = info.getStreams();
                if (streams != null) {
                    for (StreamInformation stream : streams) {
                        String codecType = stream.getType();
                        if ("video".equals(codecType)) {
                            // Resolution
                            Long width = stream.getWidth();
                            Long height = stream.getHeight();
                            if (width != null && height != null && width > 0 && height > 0) {
                                String resolutionName = getResolutionName(width.intValue(), height.intValue());
                                metadata.resolution = width + " x " + height + " (" + resolutionName + ")";
                                Log.d(TAG, "FFprobe resolution: " + metadata.resolution);
                            }

                            // Codec
                            String codecName = stream.getCodec();
                            if (codecName != null) {
                                if (codecName.contains("h264") || codecName.contains("avc")) {
                                    metadata.codec = "H.264 (AVC)";
                                } else if (codecName.contains("hevc") || codecName.contains("h265")) {
                                    metadata.codec = "H.265 (HEVC)";
                                } else if (codecName.contains("vp8")) {
                                    metadata.codec = "VP8";
                                } else if (codecName.contains("vp9")) {
                                    metadata.codec = "VP9";
                                } else if (codecName.contains("av1")) {
                                    metadata.codec = "AV1";
                                } else {
                                    metadata.codec = codecName.toUpperCase();
                                }
                                Log.d(TAG, "FFprobe codec: " + codecName + " -> " + metadata.codec);
                            }

                            // Frame rate from r_frame_rate or avg_frame_rate
                            String frameRateStr = stream.getAverageFrameRate();
                            if (frameRateStr == null || frameRateStr.isEmpty()) {
                                frameRateStr = stream.getRealFrameRate();
                            }
                            if (frameRateStr != null && !frameRateStr.isEmpty()) {
                                try {
                                    // Frame rate is often in format "30/1" or "30000/1001"
                                    if (frameRateStr.contains("/")) {
                                        String[] parts = frameRateStr.split("/");
                                        double num = Double.parseDouble(parts[0]);
                                        double den = Double.parseDouble(parts[1]);
                                        if (den > 0) {
                                            double fps = num / den;
                                            metadata.frameRate = String.format(Locale.US, "%.2f fps", fps);
                                            Log.d(TAG, "FFprobe frame rate: " + frameRateStr + " -> " + metadata.frameRate);
                                        }
                                    } else {
                                        double fps = Double.parseDouble(frameRateStr);
                                        metadata.frameRate = String.format(Locale.US, "%.2f fps", fps);
                                        Log.d(TAG, "FFprobe frame rate: " + fps + " fps");
                                    }
                                } catch (NumberFormatException e) {
                                    Log.w(TAG, "Failed to parse frame rate: " + frameRateStr);
                                }
                            }
                            break; // Only process first video stream
                        }
                    }
                }
            } else {
                Log.w(TAG, "FFprobeKit returned null MediaInformation");
            }

            // Use MediaMetadataRetriever as fallback for missing metadata and for location
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(getContext(), videoUri);
                
                // Fallback for duration if FFprobe didn't get it
                if (getString(R.string.video_info_unknown).equals(metadata.duration)) {
                    String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                    if (durationStr != null) {
                        try {
                            long durationMs = Long.parseLong(durationStr);
                            metadata.duration = formatVideoDuration(durationMs);
                            Log.d(TAG, "MediaMetadataRetriever duration fallback: " + metadata.duration);
                        } catch (NumberFormatException e) {
                            Log.w(TAG, "Failed to parse duration from MediaMetadataRetriever: " + durationStr);
                        }
                    }
                }
                
                // Fallback for resolution if FFprobe didn't get it
                if (getString(R.string.video_info_unknown).equals(metadata.resolution)) {
                    String widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                    String heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                    if (widthStr != null && heightStr != null) {
                        try {
                            int width = Integer.parseInt(widthStr);
                            int height = Integer.parseInt(heightStr);
                            String resolutionName = getResolutionName(width, height);
                            metadata.resolution = width + " x " + height + " (" + resolutionName + ")";
                            Log.d(TAG, "MediaMetadataRetriever resolution fallback: " + metadata.resolution);
                        } catch (NumberFormatException e) {
                            Log.w(TAG, "Failed to parse resolution from MediaMetadataRetriever");
                        }
                    }
                }
                
                // Fallback for bitrate if FFprobe didn't get it
                if (getString(R.string.video_info_unknown).equals(metadata.bitrate)) {
                    String bitrateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
                    if (bitrateStr != null) {
                        try {
                            long bitrate = Long.parseLong(bitrateStr);
                            metadata.bitrate = formatBitrate(bitrate);
                            Log.d(TAG, "MediaMetadataRetriever bitrate fallback: " + metadata.bitrate);
                        } catch (NumberFormatException e) {
                            Log.w(TAG, "Failed to parse bitrate from MediaMetadataRetriever: " + bitrateStr);
                        }
                    }
                }
                
                // Location data (FFprobe doesn't handle GPS well)
                metadata.location = extractLocationData(retriever);
            } catch (Exception e) {
                Log.w(TAG, "Could not extract data with MediaMetadataRetriever", e);
                if (getString(R.string.video_info_unknown).equals(metadata.location) || metadata.location == null) {
                    metadata.location = getString(R.string.video_info_no_location);
                }
            } finally {
                try {
                    retriever.release();
                } catch (Exception e) {
                    Log.e(TAG, "Error releasing MediaMetadataRetriever", e);
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Error extracting video metadata with FFprobe", e);
        }

        return metadata;
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

    /**
     * Gets the file path for display purposes.
     */
    private String getFilePath() {
        if ("file".equals(videoUri.getScheme()) && videoUri.getPath() != null) {
            return videoUri.getPath();
        }
        // For content:// URIs, try to extract the path from the URI
        String path = videoUri.getPath();
        if (path != null && path.contains(":")) {
            // SAF URIs often have format /tree/primary:FadCam/document/primary:FadCam/file.mp4
            // or /document/primary:Android/data/.../file.mp4
            int lastColonIndex = path.lastIndexOf(':');
            if (lastColonIndex >= 0 && lastColonIndex < path.length() - 1) {
                String relativePath = path.substring(lastColonIndex + 1);
                String resolved = resolveRelativePathOnVolumes(relativePath);
                if (resolved != null) {
                    return resolved;
                }
            }
        }
        return videoUri.toString();
    }

    /**
     * Gets the path for FFprobeKit. For content:// URIs, tries multiple approaches:
     * 1. SAF protocol prefix (saf:)
     * 2. Reconstructed file path (for Download/Documents folders)
     * 3. File descriptor path via ParcelFileDescriptor
     */
    private String getFFprobePath() {
        if ("file".equals(videoUri.getScheme()) && videoUri.getPath() != null) {
            return videoUri.getPath();
        }
        
        // For content:// URIs, try to get actual file path first (more reliable)
        String reconstructedPath = tryGetActualFilePath();
        if (reconstructedPath != null) {
            java.io.File file = new java.io.File(reconstructedPath);
            if (file.exists() && file.canRead()) {
                Log.d(TAG, "Using reconstructed file path for FFprobe: " + reconstructedPath);
                return reconstructedPath;
            }
        }
        
        // Fall back to SAF protocol for FFprobeKit
        // Format: saf:<content-uri>
        Log.d(TAG, "Using SAF protocol for FFprobe: saf:" + videoUri.toString());
        return "saf:" + videoUri.toString();
    }
    
    /**
     * Attempts to reconstruct the actual file path from a SAF content:// URI.
     * Works for common paths like Download, Documents, external storage.
     */
    @Nullable
    private String tryGetActualFilePath() {
        String path = videoUri.getPath();
        if (path == null || !path.contains(":")) {
            return null;
        }
        
        // SAF URIs often have format /tree/primary:FadCam/document/primary:FadCam/file.mp4
        // or /document/primary:Download/FadCam/file.mp4
        int lastColonIndex = path.lastIndexOf(':');
        if (lastColonIndex >= 0 && lastColonIndex < path.length() - 1) {
            String relativePath = path.substring(lastColonIndex + 1);
            return resolveRelativePathOnVolumes(relativePath);
        }
        return null;
    }

    @Nullable
    private String resolveRelativePathOnVolumes(@NonNull String relativePath) {
        if (getContext() == null) return null;
        try {
            java.io.File[] externalDirs = requireContext().getExternalFilesDirs(null);
            if (externalDirs != null) {
                for (java.io.File dir : externalDirs) {
                    if (dir == null) continue;
                    String dirPath = dir.getAbsolutePath();
                    int androidIdx = dirPath.indexOf("/Android/");
                    if (androidIdx > 0) {
                        String volumeRoot = dirPath.substring(0, androidIdx + 1);
                        java.io.File file = new java.io.File(volumeRoot + relativePath);
                        if (file.exists()) {
                            return file.getAbsolutePath();
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
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
