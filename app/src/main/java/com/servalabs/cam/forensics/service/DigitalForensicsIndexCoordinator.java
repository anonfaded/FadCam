package com.servalabs.cam.forensics.service;

import com.servalabs.cam.Log;
import com.servalabs.cam.FLog;
import android.content.Context;
import com.servalabs.cam.ui.VideoItem;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DigitalForensicsIndexCoordinator {

    private static final String TAG = "ForensicsIndexCoord";
    private static volatile DigitalForensicsIndexCoordinator instance;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final DigitalForensicsIndexer indexer;

    private DigitalForensicsIndexCoordinator(Context context) {
        this.indexer = new DigitalForensicsIndexer(context);
    }

    public static DigitalForensicsIndexCoordinator getInstance(Context context) {
        if (instance == null) {
            synchronized (DigitalForensicsIndexCoordinator.class) {
                if (instance == null) {
                    instance = new DigitalForensicsIndexCoordinator(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    public void enqueueIndex(List<VideoItem> videoItems) {
        if (videoItems == null || videoItems.isEmpty()) {
            return;
        }
        final List<VideoItem> copy = new ArrayList<>(videoItems);
        executor.submit(() -> {
            try {
                indexer.index(copy);
            } catch (Exception e) {
                FLog.w(TAG, "Digital forensics indexing failed", e);
            }
        });
    }
}
