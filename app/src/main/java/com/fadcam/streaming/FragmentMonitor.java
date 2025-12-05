package com.fadcam.streaming;

import android.os.FileObserver;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Monitors an fMP4 file and extracts fragments (moof+mdat boxes) as they're written.
 * 
 * This watches the recording file in real-time and notifies RemoteStreamManager
 * whenever a complete fMP4 fragment is written. Fragments are typically 2 seconds
 * based on FragmentedMp4Muxer configuration.
 * 
 * Architecture:
 * - Uses FileObserver to detect file modifications
 * - Reads file incrementally looking for MP4 box boundaries
 * - Extracts moov (header), moof+mdat (fragments) as complete units
 * - Passes fragment bytes to RemoteStreamManager for buffering
 */
public class FragmentMonitor extends FileObserver {
    private static final String TAG = "FragmentMonitor";
    
    private final File recordingFile;
    private long lastReadPosition = 0;
    private int fragmentCount = 0;
    private boolean moovReceived = false;
    private byte[] moovBox = null;
    
    public FragmentMonitor(File recordingFile) {
        super(recordingFile.getAbsolutePath(), MODIFY | CLOSE_WRITE);
        this.recordingFile = recordingFile;
        Log.i(TAG, "FragmentMonitor created for: " + recordingFile.getName());
    }
    
    @Override
    public void onEvent(int event, @Nullable String path) {
        if ((event & MODIFY) != 0) {
            processNewData();
        } else if ((event & CLOSE_WRITE) != 0) {
            Log.d(TAG, "File closed, processing final data");
            processNewData();
        }
    }
    
    /**
     * Process newly written data from the file.
     */
    private void processNewData() {
        try (RandomAccessFile raf = new RandomAccessFile(recordingFile, "r")) {
            long fileSize = raf.length();
            
            if (fileSize <= lastReadPosition) {
                return; // No new data
            }
            
            raf.seek(lastReadPosition);
            long remainingBytes = fileSize - lastReadPosition;
            
            // Read new data into buffer
            byte[] buffer = new byte[(int) Math.min(remainingBytes, 10 * 1024 * 1024)]; // Max 10MB at once
            int bytesRead = raf.read(buffer);
            
            if (bytesRead > 0) {
                parseBoxes(buffer, bytesRead);
                lastReadPosition += bytesRead;
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error processing file data", e);
        }
    }
    
    /**
     * Parse MP4 boxes from buffer.
     */
    private void parseBoxes(byte[] buffer, int length) {
        int offset = 0;
        
        while (offset + 8 <= length) {
            // Read box size (4 bytes, big-endian)
            int boxSize = ByteBuffer.wrap(buffer, offset, 4).order(ByteOrder.BIG_ENDIAN).getInt();
            
            // Read box type (4 bytes ASCII)
            String boxType = new String(buffer, offset + 4, 4);
            
            if (boxSize <= 0 || offset + boxSize > length) {
                // Incomplete box, wait for more data
                break;
            }
            
            // Extract box data
            byte[] boxData = new byte[boxSize];
            System.arraycopy(buffer, offset, boxData, 0, boxSize);
            
            handleBox(boxType, boxData);
            
            offset += boxSize;
        }
    }
    
    /**
     * Handle extracted MP4 box.
     */
    private void handleBox(String boxType, byte[] boxData) {
        switch (boxType) {
            case "ftyp":
                Log.d(TAG, "Found ftyp box (" + boxData.length + " bytes)");
                break;
                
            case "moov":
                // Header with track info
                moovBox = boxData;
                moovReceived = true;
                Log.i(TAG, "Received moov box (" + boxData.length + " bytes) - initialization complete");
                
                // Send initialization segment (ftyp + moov) to manager
                RemoteStreamManager.getInstance().onInitializationData(moovBox);
                break;
                
            case "moof":
                // Fragment metadata - wait for associated mdat
                Log.d(TAG, "Found moof box (" + boxData.length + " bytes)");
                break;
                
            case "mdat":
                // Fragment media data
                if (moovReceived) {
                    fragmentCount++;
                    Log.i(TAG, "📦 Fragment #" + fragmentCount + " received (" + boxData.length + " bytes)");
                    
                    // TODO: Combine previous moof + this mdat and send to manager
                    // For now, just log
                }
                break;
        }
    }
    
    /**
     * Reset monitoring state.
     */
    public void reset() {
        lastReadPosition = 0;
        fragmentCount = 0;
        moovReceived = false;
        moovBox = null;
        Log.d(TAG, "FragmentMonitor reset");
    }
}
