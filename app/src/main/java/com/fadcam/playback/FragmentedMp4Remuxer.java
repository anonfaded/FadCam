package com.fadcam.playback;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

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
     * This is a simple heuristic based on file structure.
     *
     * @param file The file to check.
     * @return true if the file appears to be a fragmented MP4.
     */
    public boolean needsRemux(File file) {
        // For now, assume all FadCam recordings are fMP4
        // A more sophisticated check would parse the file header
        String name = file.getName().toLowerCase();
        return name.startsWith("fadcam_") && name.endsWith(".mp4");
    }
    
    /**
     * Gets the path for the remuxed version of a file.
     * The remuxed file is stored in a cache directory.
     *
     * @param originalFile The original file.
     * @return Path to the remuxed file (may not exist yet).
     */
    public File getRemuxedFile(File originalFile) {
        File cacheDir = new File(context.getCacheDir(), "remuxed");
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        
        // Use original filename + hash of path to handle duplicates
        String hash = String.valueOf(originalFile.getAbsolutePath().hashCode());
        String name = originalFile.getName();
        String baseName = name.substring(0, name.lastIndexOf('.'));
        return new File(cacheDir, baseName + "_" + hash + "_seekable.mp4");
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
            Log.d(TAG, "Using existing remuxed file: " + outputFile.getName());
            return outputFile;
        }
        
        Log.i(TAG, "Remuxing: " + inputFile.getName() + " -> " + outputFile.getName());
        
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
        
        Log.d(TAG, "FFmpeg command: " + ffmpegCmd);
        
        try {
            FFmpegSession session = FFmpegKit.execute(ffmpegCmd);
            
            if (ReturnCode.isSuccess(session.getReturnCode())) {
                Log.i(TAG, "Remux successful: " + outputFile.getName() + 
                           " (" + outputFile.length() / 1024 + " KB)");
                return outputFile;
            } else {
                Log.e(TAG, "Remux failed with code: " + session.getReturnCode());
                Log.e(TAG, "FFmpeg output: " + session.getOutput());
                
                // Clean up failed output
                if (outputFile.exists()) {
                    outputFile.delete();
                }
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Remux exception", e);
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
            Log.d(TAG, "Using existing remuxed file: " + outputFile.getName());
            if (callback != null) {
                callback.onRemuxComplete(true, outputFile.getAbsolutePath());
            }
            return;
        }
        
        Log.i(TAG, "Async remuxing: " + inputFile.getName());
        
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
                Log.i(TAG, "Async remux successful: " + outputFile.getName());
            } else {
                Log.e(TAG, "Async remux failed: " + session.getReturnCode());
                if (outputFile.exists()) {
                    outputFile.delete();
                }
            }
            
            if (callback != null) {
                callback.onRemuxComplete(success, success ? outputPath : null);
            }
        }, log -> {
            // Log callback - could parse for progress
            Log.v(TAG, "FFmpeg: " + log.getMessage());
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
            Log.i(TAG, "Cleaned up " + deleted + " cached remuxed files");
        }
    }
}
