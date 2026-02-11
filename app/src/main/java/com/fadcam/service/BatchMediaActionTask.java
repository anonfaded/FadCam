package com.fadcam.service;

import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.Constants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class BatchMediaActionTask {

    public enum ActionType {
        EXPORT_STANDARD_MP4,
        MERGE_VIDEOS
    }

    public enum OutputMode {
        DEFAULT_FADITOR,
        CUSTOM_TREE_URI
    }

    @NonNull public final ActionType actionType;
    @NonNull public final List<Uri> inputUris;
    @NonNull public final OutputMode outputMode;
    @Nullable public final Uri customTreeUri;
    public final boolean keepOriginals;

    public BatchMediaActionTask(
            @NonNull ActionType actionType,
            @NonNull List<Uri> inputUris,
            @NonNull OutputMode outputMode,
            @Nullable Uri customTreeUri,
            boolean keepOriginals
    ) {
        this.actionType = actionType;
        this.inputUris = Collections.unmodifiableList(new ArrayList<>(inputUris));
        this.outputMode = outputMode;
        this.customTreeUri = customTreeUri;
        this.keepOriginals = keepOriginals;
    }

    @Nullable
    public static BatchMediaActionTask fromIntent(@Nullable Intent intent) {
        if (intent == null) return null;

        ActionType actionType;
        String action = intent.getAction();
        if (Constants.INTENT_ACTION_BATCH_EXPORT_STANDARD_MP4.equals(action)) {
            actionType = ActionType.EXPORT_STANDARD_MP4;
        } else if (Constants.INTENT_ACTION_BATCH_MERGE_VIDEOS.equals(action)) {
            actionType = ActionType.MERGE_VIDEOS;
        } else {
            return null;
        }

        ArrayList<String> uriStrings = intent.getStringArrayListExtra(Constants.EXTRA_BATCH_INPUT_URIS);
        if (uriStrings == null || uriStrings.isEmpty()) return null;

        List<Uri> inputUris = new ArrayList<>();
        for (String uriString : uriStrings) {
            if (uriString == null || uriString.trim().isEmpty()) continue;
            try {
                inputUris.add(Uri.parse(uriString));
            } catch (Exception ignored) {
            }
        }
        if (inputUris.isEmpty()) return null;

        String modeString = intent.getStringExtra(Constants.EXTRA_BATCH_OUTPUT_MODE);
        OutputMode outputMode = Constants.BATCH_OUTPUT_MODE_CUSTOM_TREE_URI.equals(modeString)
                ? OutputMode.CUSTOM_TREE_URI
                : OutputMode.DEFAULT_FADITOR;

        Uri customTreeUri = null;
        String customTreeUriString = intent.getStringExtra(Constants.EXTRA_BATCH_CUSTOM_TREE_URI);
        if (customTreeUriString != null && !customTreeUriString.trim().isEmpty()) {
            try {
                customTreeUri = Uri.parse(customTreeUriString);
            } catch (Exception ignored) {
            }
        }

        return new BatchMediaActionTask(
                actionType,
                inputUris,
                outputMode,
                customTreeUri,
                true
        );
    }
}
