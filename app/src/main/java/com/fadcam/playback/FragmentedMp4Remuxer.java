package com.fadcam.playback;

import com.fadcam.Log;
import com.fadcam.FLog;
import android.content.Context;
import android.net.Uri;
import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.File;
import java.io.IOException;

/**
 * Utility class to remux fragmented MP4 files to add proper seeking support.
 * 
 * Fragmented MP4 files created by Media3's FragmentedMp4Muxer lack sidx (segment index)
 * boxes which ExoPlayer needs for seeking. This utility remuxes the file using FFmpeg
 * with the +faststart flag to move the moov atom to the beginning and enable seeking.
 * 
 * This is a workaround for ExoPlayer's inability to seek in fMP4 without sidx boxes.
 */
public class FragmentedMp4Remuxer {
    private static final String TAG = "FMp4Remuxer";
    
    public interface RemuxCallback {
        void onRemuxComplete(boolean success, String outputPath);
        void onRemuxProgress(int percent);
    }
    
    private final Context context;
    
    public FragmentedMp4Remuxer(Context context) {
        this.context = context.getApplicationContext();
    }
    
    /**
     * Checks if a file is a fragmented MP4 that needs remuxing.
     *
     * <p>Inspects the first 64 KB of the file for the presence of a
     * {@code moof} (movie fragment) box, which is the definitive marker
     * of a fragmented MP4 container. Fragmented MP4 files recorded by
     * FadCam (or any other recorder using Media3's FragmentedMp4Muxer)
     * lack sidx boxes, so ExoPlayer cannot seek within them.</p>
     *
     * @param file The file to check.
     * @return true if the file appears to be a fragmented MP4.
     */
    public boolean needsRemux(File file) {
        if (file == null || !file.exists() || !file.canRead()) return false;
        String name = file.getName().toLowerCase();
        if (!name.endsWith(".mp4")) return false;

        // Scan the first 64 KB of the file for the 'moof' box type.
        // MP4 box structure: [4-byte size][4-byte type] — we look for ASCII "moof" (0x6D6F6F66).
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
            int scanLimit = (int) Math.min(raf.length(), 65536);
            byte[] buf = new byte[scanLimit];
            raf.readFully(buf);

            // Search for the 'moof' box type in the buffer
            byte m = (byte) 'm', o = (byte) 'o', f = (byte) 'f';
            for (int i = 0; i <= buf.length - 4; i++) {
                if (buf[i] == m && buf[i + 1] == o && buf[i + 2] == o && buf[i + 3] == f) {
                    // Verify this looks like a valid box: the 4 bytes before 'moof'
                    // should be the box size (> 8). If i >= 4, check it.
                    if (i >= 4) {
                        int boxSize = ((buf[i - 4] & 0xFF) << 24)
                                    | ((buf[i - 3] & 0xFF) << 16)
                                    | ((buf[i - 2] & 0xFF) << 8)
                                    | (buf[i - 1] & 0xFF);
                        if (boxSize >= 8) {
                            FLog.d(TAG, "Detected fragmented MP4 (moof box at offset "
                                    + (i - 4) + ", size=" + boxSize + "): " + file.getName());
                            return true;
                        }
                    } else {
                        // 'moof' at very start — unlikely but still fragmented
                        FLog.d(TAG, "Detected fragmented MP4 (moof at offset 0): " + file.getName());
                        return true;
                    }
                }
            }
            FLog.d(TAG, "Not a fragmented MP4 (no moof in first " + scanLimit + " bytes): " + file.getName());
            return false;
        } catch (IOException e) {
            FLog.w(TAG, "Could not check file for fMP4 structure: " + file.getName(), e);
            // Fallback: assume FadCam-named files are fragmented
            return name.startsWith("fadcam_");
        }
    }
    
    /**
     * Gets the path for the remuxed version of a file.
     * The remuxed file is stored in a cache directory with a clean name.
     *
     * @param originalFile The original file.
     * @return Path to the remuxed file (may not exist yet).
     */
    public File getRemuxedFile(File originalFile) {
        File cacheDir = new File(context.getCacheDir(), "remuxed");
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        
        String name = originalFile.getName();
        String baseName = name.substring(0, name.lastIndexOf('.'));
        
        // Use a hash of the full path to handle duplicate filenames from different directories
        String hash = String.valueOf(Math.abs(originalFile.getAbsolutePath().hashCode() % 10000));
        return new File(cacheDir, baseName + "-remuxed-" + hash + ".mp4");
    }
    
    /**
     * Checks if a remuxed version of the file already exists.
     *
     * @param originalFile The original file.
     * @return true if a valid remuxed version exists.
     */
    public boolean hasRemuxedVersion(File originalFile) {
        File remuxed = getRemuxedFile(originalFile);
        if (!remuxed.exists()) {
            return false;
        }
        
        // Check if remuxed file is newer than original
        if (remuxed.lastModified() < originalFile.lastModified()) {
            // Original was modified, delete stale remuxed version
            remuxed.delete();
            return false;
        }
        
        // Check if remuxed file has reasonable size
        if (remuxed.length() < originalFile.length() * 0.9) {
            // Remuxed file is too small, probably corrupted
            remuxed.delete();
            return false;
        }
        
        return true;
    }
    
    /**
     * Remuxes a fragmented MP4 file to add proper seeking support.
     * This is a synchronous operation that blocks until complete.
     *
     * @param inputFile The input fragmented MP4 file.
     * @return The remuxed file, or null if remuxing failed.
     */
    public File remuxSync(File inputFile) {
        File outputFile = getRemuxedFile(inputFile);
        
        // Check if already remuxed
        if (hasRemuxedVersion(inputFile)) {
            FLog.d(TAG, "Using existing remuxed file: " + outputFile.getName());
            return outputFile;
        }
        
        FLog.i(TAG, "Remuxing: " + inputFile.getName() + " -> " + outputFile.getName());
        
        String inputPath = inputFile.getAbsolutePath();
        String outputPath = outputFile.getAbsolutePath();
        
        // Delete any existing output file
        if (outputFile.exists()) {
            outputFile.delete();
        }
        
        // FFmpeg command to remux with faststart
        // -i input: input file
        // -c copy: copy streams without re-encoding (fast)
        // -movflags +faststart: move moov atom to beginning for seeking
        // -y: overwrite output
        String ffmpegCmd = String.format(
            "-i \"%s\" -c copy -movflags +faststart -y \"%s\"",
            inputPath, outputPath
        );
        
        FLog.d(TAG, "FFmpeg command: " + ffmpegCmd);
        
        try {
            FFmpegSession session = FFmpegKit.execute(ffmpegCmd);
            
            if (ReturnCode.isSuccess(session.getReturnCode())) {
                FLog.i(TAG, "Remux successful: " + outputFile.getName() + 
                           " (" + outputFile.length() / 1024 + " KB)");
                return outputFile;
            } else {
                FLog.e(TAG, "Remux failed with code: " + session.getReturnCode());
                FLog.e(TAG, "FFmpeg output: " + session.getOutput());
                
                // Clean up failed output
                if (outputFile.exists()) {
                    outputFile.delete();
                }
                return null;
            }
        } catch (Exception e) {
            FLog.e(TAG, "Remux exception", e);
            if (outputFile.exists()) {
                outputFile.delete();
            }
            return null;
        }
    }
    
    /**
     * Remuxes a fragmented MP4 file asynchronously.
     *
     * @param inputFile The input fragmented MP4 file.
     * @param callback Callback for completion and progress.
     */
    public void remuxAsync(File inputFile, RemuxCallback callback) {
        File outputFile = getRemuxedFile(inputFile);
        
        // Check if already remuxed
        if (hasRemuxedVersion(inputFile)) {
            FLog.d(TAG, "Using existing remuxed file: " + outputFile.getName());
            if (callback != null) {
                callback.onRemuxComplete(true, outputFile.getAbsolutePath());
            }
            return;
        }
        
        FLog.i(TAG, "Async remuxing: " + inputFile.getName());
        
        String inputPath = inputFile.getAbsolutePath();
        String outputPath = outputFile.getAbsolutePath();
        
        // Delete any existing output file
        if (outputFile.exists()) {
            outputFile.delete();
        }
        
        String ffmpegCmd = String.format(
            "-i \"%s\" -c copy -movflags +faststart -y \"%s\"",
            inputPath, outputPath
        );
        
        FFmpegKit.executeAsync(ffmpegCmd, session -> {
            boolean success = ReturnCode.isSuccess(session.getReturnCode());
            
            if (success) {
                FLog.i(TAG, "Async remux successful: " + outputFile.getName());
            } else {
                FLog.e(TAG, "Async remux failed: " + session.getReturnCode());
                if (outputFile.exists()) {
                    outputFile.delete();
                }
            }
            
            if (callback != null) {
                callback.onRemuxComplete(success, success ? outputPath : null);
            }
        }, log -> {
            // Log callback - could parse for progress
            FLog.v(TAG, "FFmpeg: " + log.getMessage());
        }, statistics -> {
            // Progress callback
            if (callback != null && statistics.getTime() > 0) {
                // Estimate progress based on time processed
                // This is rough since we don't know total duration here
                callback.onRemuxProgress((int) (statistics.getTime() / 1000));
            }
        });
    }
    
    /**
     * Cleans up cached remuxed files.
     *
     * @param maxAgeDays Maximum age in days for cached files.
     */
    public void cleanupCache(int maxAgeDays) {
        File cacheDir = new File(context.getCacheDir(), "remuxed");
        if (!cacheDir.exists()) {
            return;
        }
        
        long maxAgeMs = maxAgeDays * 24L * 60 * 60 * 1000;
        long now = System.currentTimeMillis();
        
        File[] files = cacheDir.listFiles();
        if (files == null) return;
        
        int deleted = 0;
        for (File file : files) {
            if (now - file.lastModified() > maxAgeMs) {
                if (file.delete()) {
                    deleted++;
                }
            }
        }
        
        if (deleted > 0) {
            FLog.i(TAG, "Cleaned up " + deleted + " cached remuxed files");
        }
    }
    
    /**
     * Gets the total size of all cached remuxed files in bytes.
     *
     * @return Size in bytes, or 0 if cache directory doesn't exist.
     */
    public long getTotalCacheSize() {
        File cacheDir = new File(context.getCacheDir(), "remuxed");
        if (!cacheDir.exists()) return 0;
        
        long totalSize = 0;
        File[] files = cacheDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    totalSize += file.length();
                }
            }
        }
        return totalSize;
    }
    
    /**
     * Gets the size of cached files matching a specific filename prefix.
     * Used to calculate cache size for a specific project/video.
     *
     * @param prefix The filename prefix to match (e.g., "clip_001").
     * @return Size in bytes of matching cached files.
     */
    public long getCacheSizeForPrefix(String prefix) {
        File cacheDir = new File(context.getCacheDir(), "remuxed");
        if (!cacheDir.exists()) return 0;
        
        long totalSize = 0;
        File[] files = cacheDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && file.getName().startsWith(prefix)) {
                    totalSize += file.length();
                }
            }
        }
        return totalSize;
    }
    
    /**
     * Delete all cached remuxed files matching a specific filename prefix.
     * Call this when a project or clip is deleted to clean up orphaned cache files.
     *
     * @param prefix The filename prefix to match (e.g., "clip_001").
     * @return Number of files deleted.
     */
    public int deleteCacheForPrefix(String prefix) {
        File cacheDir = new File(context.getCacheDir(), "remuxed");
        if (!cacheDir.exists()) return 0;
        
        int deleted = 0;
        File[] files = cacheDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && file.getName().startsWith(prefix)) {
                    if (file.delete()) {
                        FLog.d(TAG, "Deleted cache file: " + file.getName());
                        deleted++;
                    }
                }
            }
        }
        
        if (deleted > 0) {
            FLog.i(TAG, "Deleted " + deleted + " cached files for prefix: " + prefix);
        }
        
        return deleted;
    }
    
    /**
     * Clear all remuxed cache files.
     *
     * @return Number of files deleted.
     */
    public int clearAllCache() {
        File cacheDir = new File(context.getCacheDir(), "remuxed");
        if (!cacheDir.exists()) return 0;
        
        int deleted = 0;
        File[] files = cacheDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && file.delete()) {
                    deleted++;
                }
            }
        }
        
        // Try to delete the cache directory itself if empty
        if (cacheDir.listFiles() != null && cacheDir.listFiles().length == 0) {
            cacheDir.delete();
        }
        
        FLog.i(TAG, "Cleared cache: deleted " + deleted + " files");
        return deleted;
    }
}
