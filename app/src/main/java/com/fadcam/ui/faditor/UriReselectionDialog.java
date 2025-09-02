package com.fadcam.ui.faditor;

import android.app.Dialog;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import com.fadcam.R;
import com.fadcam.ui.faditor.utils.VideoFilePicker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dialog for re-selecting video files that have expired URI permissions.
 * Uses VideoFilePicker to allow users to re-grant access to files.
 */
public class UriReselectionDialog extends DialogFragment {

    private static final String ARG_URIS_REQUIRING_RESELECTION = "uris_requiring_reselection";

    /**
     * Interface for handling URI re-selection results
     */
    public interface UriReselectionCallback {
        void onUriReselected(Uri oldUri, Uri newUri);
        void onUriSkipped(Uri uri);
        void onReselectionCompleted();
        void onReselectionCancelled();
    }

    private UriReselectionCallback callback;
    private List<Uri> urisRequiringReselection;
    private Map<Uri, String> uriDisplayNames;
    private int currentIndex = 0;
    private VideoFilePicker videoFilePicker;

    /**
     * Create a new instance of UriReselectionDialog
     */
    public static UriReselectionDialog newInstance(List<Uri> urisRequiringReselection) {
        UriReselectionDialog dialog = new UriReselectionDialog();
        Bundle args = new Bundle();
        args.putParcelableArrayList(ARG_URIS_REQUIRING_RESELECTION,
            new ArrayList<>(urisRequiringReselection));
        dialog.setArguments(args);
        return dialog;
    }

    /**
     * Set the callback for handling re-selection results
     */
    public void setCallback(UriReselectionCallback callback) {
        this.callback = callback;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (getArguments() != null) {
            urisRequiringReselection = getArguments()
                .getParcelableArrayList(ARG_URIS_REQUIRING_RESELECTION);
        }

        if (urisRequiringReselection == null) {
            urisRequiringReselection = new ArrayList<>();
        }

        uriDisplayNames = new HashMap<>();
        for (Uri uri : urisRequiringReselection) {
            uriDisplayNames.put(uri, getDisplayNameForUri(uri));
        }

        // Initialize video file picker
        videoFilePicker = new VideoFilePicker(this);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.uri_reselection_dialog, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        TextView titleView = view.findViewById(R.id.uri_reselection_title);
        TextView messageView = view.findViewById(R.id.uri_reselection_message);
        LinearLayout fileListContainer = view.findViewById(R.id.uri_reselection_file_list);
        Button selectButton = view.findViewById(R.id.uri_reselection_select_button);
        Button skipButton = view.findViewById(R.id.uri_reselection_skip_button);
        Button continueButton = view.findViewById(R.id.uri_reselection_continue_button);

        // Set title and message
        if (titleView != null) {
            titleView.setText("Re-select Video Files");
        }

        if (messageView != null) {
            messageView.setText("Some video files in this project have expired permissions and need to be re-selected.");
        }

        // Populate file list
        if (fileListContainer != null) {
            populateFileList(fileListContainer);
        }

        // Set up button listeners
        if (selectButton != null) {
            selectButton.setText("Select File");
            selectButton.setOnClickListener(v -> selectCurrentFile());
        }

        if (skipButton != null) {
            skipButton.setText("Skip");
            skipButton.setOnClickListener(v -> skipCurrentFile());
        }

        if (continueButton != null) {
            continueButton.setText("Continue");
            continueButton.setOnClickListener(v -> continueToNext());
        }

        // Update UI for first file
        updateCurrentFileDisplay(view);
    }

    private void populateFileList(LinearLayout container) {
        container.removeAllViews();

        for (int i = 0; i < urisRequiringReselection.size(); i++) {
            Uri uri = urisRequiringReselection.get(i);
            String displayName = uriDisplayNames.get(uri);

            View itemView = createFileListItem(displayName, i == currentIndex);
            container.addView(itemView);
        }
    }

    private View createFileListItem(String displayName, boolean isCurrent) {
        TextView textView = new TextView(requireContext());
        textView.setText(displayName);
        textView.setTextColor(getResources().getColor(
            isCurrent ? R.color.colorPrimary : android.R.color.darker_gray));
        textView.setTextSize(14f);
        textView.setPadding(16, 8, 16, 8);

        if (isCurrent) {
            textView.setTypeface(textView.getTypeface(), android.graphics.Typeface.BOLD);
        }

        return textView;
    }

    private void updateCurrentFileDisplay(View view) {
        if (currentIndex >= urisRequiringReselection.size()) {
            // All files processed
            if (callback != null) {
                callback.onReselectionCompleted();
            }
            dismiss();
            return;
        }

        // Update file list to highlight current file
        LinearLayout fileListContainer = view.findViewById(R.id.uri_reselection_file_list);
        if (fileListContainer != null) {
            populateFileList(fileListContainer);
        }

        // Update button states
        Button selectButton = view.findViewById(R.id.uri_reselection_select_button);
        Button skipButton = view.findViewById(R.id.uri_reselection_skip_button);
        Button continueButton = view.findViewById(R.id.uri_reselection_continue_button);

        if (selectButton != null) {
            selectButton.setEnabled(true);
        }

        if (skipButton != null) {
            skipButton.setEnabled(true);
        }

        if (continueButton != null) {
            continueButton.setEnabled(false); // Only enabled after selecting/skipping
        }
    }

    private void selectCurrentFile() {
        if (currentIndex >= urisRequiringReselection.size()) {
            return;
        }

        Uri currentUri = urisRequiringReselection.get(currentIndex);

        videoFilePicker.pickVideo(new VideoFilePicker.VideoSelectionCallback() {
            @Override
            public void onVideoSelected(Uri newUri) {
                if (callback != null) {
                    callback.onUriReselected(currentUri, newUri);
                }

                Toast.makeText(requireContext(),
                    "File selected successfully",
                    Toast.LENGTH_SHORT).show();

                currentIndex++;
                View view = getView();
                if (view != null) {
                    updateCurrentFileDisplay(view);
                }
            }

            @Override
            public void onSelectionCancelled() {
                // User cancelled file selection, stay on current file
            }

            @Override
            public void onError(String errorMessage) {
                Toast.makeText(requireContext(),
                    "Error selecting file: " + errorMessage,
                    Toast.LENGTH_LONG).show();
            }
        });
    }

    private void skipCurrentFile() {
        if (currentIndex >= urisRequiringReselection.size()) {
            return;
        }

        Uri currentUri = urisRequiringReselection.get(currentIndex);

        if (callback != null) {
            callback.onUriSkipped(currentUri);
        }

                Toast.makeText(requireContext(),
                    "File skipped",
                    Toast.LENGTH_SHORT).show();        currentIndex++;
        View view = getView();
        if (view != null) {
            updateCurrentFileDisplay(view);
        }
    }

    private void continueToNext() {
        currentIndex++;
        View view = getView();
        if (view != null) {
            updateCurrentFileDisplay(view);
        }
    }

    private String getDisplayNameForUri(Uri uri) {
        if (uri == null) {
            return "Unknown file";
        }

        // Try to extract filename from URI
        String path = uri.getPath();
        if (path != null) {
            String[] segments = path.split("/");
            if (segments.length > 0) {
                return segments[segments.length - 1];
            }
        }

        // Fallback to URI string
        return uri.toString();
    }

    @Override
    public void onCancel(@NonNull android.content.DialogInterface dialog) {
        super.onCancel(dialog);
        if (callback != null) {
            callback.onReselectionCancelled();
        }
    }

    /**
     * Show the dialog using the fragment manager
     */
    public void show(FragmentManager fragmentManager) {
        show(fragmentManager, "UriReselectionDialog");
    }
}
