package com.servalabs.cam.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.servalabs.cam.R;
import com.servalabs.cam.streaming.RemoteStreamManager;
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

    @Override
    public int getTheme() {
        return R.style.CustomBottomSheetDialogTheme;
    }

    @Override
    public android.app.Dialog onCreateDialog(Bundle savedInstanceState) {
        android.app.Dialog dialog = super.onCreateDialog(savedInstanceState);
        dialog.setOnShowListener(d -> {
            View bottomSheet = ((com.google.android.material.bottomsheet.BottomSheetDialog) dialog)
                .findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                bottomSheet.setBackgroundResource(R.drawable.picker_bottom_sheet_dark_gradient_bg);
            }
        });
        if (dialog.getWindow() != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            dialog.getWindow().setNavigationBarColor(android.graphics.Color.BLACK);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                dialog.getWindow().setNavigationBarContrastEnforced(false);
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                int flags = dialog.getWindow().getDecorView().getSystemUiVisibility();
                dialog.getWindow().getDecorView().setSystemUiVisibility(
                    flags & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                );
            }
        }
        return dialog;
    }
}
