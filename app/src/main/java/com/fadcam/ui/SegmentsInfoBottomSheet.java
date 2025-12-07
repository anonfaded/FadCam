package com.fadcam.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;
import com.fadcam.streaming.RemoteStreamManager;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.util.Locale;

/**
 * Bottom sheet explaining video segments/fragments with current buffer stats.
 */
public class SegmentsInfoBottomSheet extends BottomSheetDialogFragment {
    
    private TextView bufferedCountText;
    private TextView bufferSizeText;
    private TextView oldestSequenceText;
    private TextView latestSequenceText;
    
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_segments_info, container, false);
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        bufferedCountText = view.findViewById(R.id.buffered_count_text);
        bufferSizeText = view.findViewById(R.id.buffer_size_text);
        oldestSequenceText = view.findViewById(R.id.oldest_sequence_text);
        latestSequenceText = view.findViewById(R.id.latest_sequence_text);
        
        loadSegmentsData();
    }
    
    private void loadSegmentsData() {
        RemoteStreamManager manager = RemoteStreamManager.getInstance();
        
        // Buffered count
        int bufferedCount = manager.getBufferedCount();
        bufferedCountText.setText(String.valueOf(bufferedCount));
        
        // Buffer size
        long bufferSizeBytes = manager.getBufferSizeBytes();
        double bufferSizeMB = bufferSizeBytes / (1024.0 * 1024.0);
        bufferSizeText.setText(String.format(Locale.US, "%.1f MB", bufferSizeMB));
        
        // Sequence range
        int oldest = manager.getOldestSequenceNumber();
        int latest = manager.getLatestSequenceNumber();
        
        oldestSequenceText.setText("#" + oldest);
        latestSequenceText.setText("#" + latest);
    }
}
