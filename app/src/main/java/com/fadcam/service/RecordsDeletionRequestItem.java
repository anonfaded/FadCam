package com.fadcam.service;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RecordsDeletionRequestItem {

    private static final Gson GSON = new Gson();
    private static final Type LIST_TYPE = new TypeToken<List<RecordsDeletionRequestItem>>() { }
            .getType();

    @NonNull public final String uriString;
    @NonNull public final String displayName;
    public final long sizeBytes;
    public final boolean safSource;

    public RecordsDeletionRequestItem(
            @NonNull String uriString,
            @NonNull String displayName,
            long sizeBytes,
            boolean safSource
    ) {
        this.uriString = uriString;
        this.displayName = displayName;
        this.sizeBytes = Math.max(0L, sizeBytes);
        this.safSource = safSource;
    }

    @Nullable
    public Uri toUri() {
        try {
            return Uri.parse(uriString);
        } catch (Exception ignored) {
            return null;
        }
    }

    @NonNull
    public static String toJson(@NonNull List<RecordsDeletionRequestItem> items) {
        return GSON.toJson(items, LIST_TYPE);
    }

    @NonNull
    public static List<RecordsDeletionRequestItem> fromJson(@Nullable String json) {
        if (json == null || json.trim().isEmpty()) {
            return Collections.emptyList();
        }
        try {
            List<RecordsDeletionRequestItem> items = GSON.fromJson(json, LIST_TYPE);
            return items == null ? Collections.emptyList() : items;
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    @NonNull
    public static List<String> toUriStrings(@NonNull List<RecordsDeletionRequestItem> items) {
        List<String> uris = new ArrayList<>(items.size());
        for (RecordsDeletionRequestItem item : items) {
            uris.add(item.uriString);
        }
        return uris;
    }
}
