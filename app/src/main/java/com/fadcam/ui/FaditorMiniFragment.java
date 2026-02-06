package com.fadcam.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;
import com.fadcam.ui.faditor.FaditorEditorActivity;

/**
 * Entry-point fragment for the Faditor Mini tab.
 *
 * <p>Phase 1: Shows a video picker button. When a video is selected,
 * launches {@link FaditorEditorActivity} for full-screen editing.</p>
 *
 * <p>Phase 4: Will become a project browser grid showing saved projects.</p>
 */
public class FaditorMiniFragment extends BaseFragment {

    private static final String TAG = "FaditorMiniFragment";

    /** Launcher for the system video file picker. */
    private ActivityResultLauncher<Intent> videoPickerLauncher;

    /** Launcher for the editor Activity result. */
    private ActivityResultLauncher<Intent> editorLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Register video picker result handler
        videoPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK
                            && result.getData() != null
                            && result.getData().getData() != null) {
                        Uri videoUri = result.getData().getData();
                        // Take persistable read permission so the editor can access the URI
                        try {
                            requireContext().getContentResolver().takePersistableUriPermission(
                                    videoUri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } catch (SecurityException e) {
                            Log.w(TAG, "Could not take persistable URI permission", e);
                        }
                        launchEditor(videoUri);
                    }
                }
        );

        // Register editor result handler (for future result passing)
        editorLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK) {
                        Log.d(TAG, "Editor returned successfully");
                        // Phase 4: Refresh project list here
                    }
                }
        );
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_faditor_mini, container, false);

        // Setup the "Select Video" button
        View selectButton = view.findViewById(R.id.btn_select_video);
        if (selectButton != null) {
            selectButton.setOnClickListener(v -> openVideoPicker());
        }

        return view;
    }

    /**
     * Open the system file picker to select a video file.
     */
    private void openVideoPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);

        try {
            videoPickerLauncher.launch(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to open video picker", e);
            Toast.makeText(requireContext(),
                    R.string.faditor_error_picker, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Launch the full-screen editor with the selected video.
     */
    private void launchEditor(@NonNull Uri videoUri) {
        Intent intent = new Intent(requireContext(), FaditorEditorActivity.class);
        intent.setData(videoUri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        editorLauncher.launch(intent);
    }
}
