package com.fadcam.utils;

import android.content.Context;
import android.util.Log;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.fadcam.ui.VideoItem;
import com.fadcam.SharedPreferencesManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Progressive video loader that loads videos in batches for scalable performance.
 * Loads visible viewport first, then progressively loads more on scroll.
 */
public class ProgressiveVideoLoader {
    private static final String TAG = "ProgressiveVideoLoader";
    
    // Loading configuration
    private static final int INITIAL_LOAD_SIZE = 15; // Load first 15 videos immediately
    private static final int PROGRESSIVE_BATCH_SIZE = 20; // Load 20 more per batch
    private static final int PREFETCH_THRESHOLD = 5; // Start loading when 5 items from end
    
    public interface ProgressiveLoadListener {
        void onInitialBatchLoaded(List<VideoItem> initialBatch, int totalVideoCount);
        void onProgressiveBatchLoaded(List<VideoItem> newBatch, boolean hasMore);
        void onLoadingStateChanged(boolean isLoading);
    }
    
    private final Context context;
    private final ExecutorService executorService;
    private final SharedPreferencesManager sharedPreferences;
    private final ProgressiveLoadListener listener;
    
    private List<VideoItem> allVideos = new ArrayList<>();
    private int currentLoadedIndex = 0;
    private boolean isLoading = false;
    private boolean hasMoreVideos = true;
    
    public ProgressiveVideoLoader(Context context, ExecutorService executorService,
                                SharedPreferencesManager sharedPreferences, 
                                ProgressiveLoadListener listener) {
        this.context = context;
        this.executorService = executorService;
        this.sharedPreferences = sharedPreferences;
        this.listener = listener;
    }
    
    /**
     * Starts progressive loading with cached or fresh video data
     */
    public void startProgressiveLoading() {
        if (isLoading) {
            Log.d(TAG, "Already loading - ignoring request");
            return;
        }
        
        // Check if we have cached videos
        List<VideoItem> cachedVideos = VideoSessionCache.getSessionCachedVideos();
        if (!cachedVideos.isEmpty()) {
            Log.d(TAG, "Using cached videos for progressive loading: " + cachedVideos.size());
            initializeWithVideos(cachedVideos);
        } else {
            Log.d(TAG, "No cache - loading videos from storage");
            loadVideosFromStorage();
        }
    }
    
    /**
     * Initialize progressive loader with a complete list of videos
     */
    private void initializeWithVideos(List<VideoItem> videos) {
        this.allVideos = new ArrayList<>(videos);
        this.currentLoadedIndex = 0;
        this.hasMoreVideos = videos.size() > INITIAL_LOAD_SIZE;
        
        // Load initial batch
        List<VideoItem> initialBatch = getNextBatch(INITIAL_LOAD_SIZE);
        
        if (listener != null) {
            listener.onInitialBatchLoaded(initialBatch, allVideos.size());
        }
        
        Log.d(TAG, "Progressive loading initialized - loaded " + initialBatch.size() + 
                   " of " + allVideos.size() + " videos");
    }
    
    /**
     * Load next batch of videos progressively
     */
    public void loadNextBatch() {
        if (isLoading || !hasMoreVideos) {
            Log.d(TAG, "Cannot load next batch - loading: " + isLoading + ", hasMore: " + hasMoreVideos);
            return;
        }
        
        setLoadingState(true);
        
        // Load next batch in background to avoid UI blocking
        executorService.submit(() -> {
            try {
                List<VideoItem> nextBatch = getNextBatch(PROGRESSIVE_BATCH_SIZE);
                boolean hasMore = currentLoadedIndex < allVideos.size();
                
                if (listener != null) {
                    listener.onProgressiveBatchLoaded(nextBatch, hasMore);
                }
                
                Log.d(TAG, "Loaded next batch: " + nextBatch.size() + " videos, hasMore: " + hasMore);
                
            } catch (Exception e) {
                Log.e(TAG, "Error loading next batch", e);
            } finally {
                setLoadingState(false);
            }
        });
    }
    
    /**
     * Get next batch of videos from the complete list
     */
    private List<VideoItem> getNextBatch(int batchSize) {
        List<VideoItem> batch = new ArrayList<>();
        
        int endIndex = Math.min(currentLoadedIndex + batchSize, allVideos.size());
        for (int i = currentLoadedIndex; i < endIndex; i++) {
            batch.add(allVideos.get(i));
        }
        
        currentLoadedIndex = endIndex;
        hasMoreVideos = currentLoadedIndex < allVideos.size();
        
        return batch;
    }
    
    /**
     * Load complete video list from storage (fallback when no cache)
     */
    private void loadVideosFromStorage() {
        setLoadingState(true);
        
        executorService.submit(() -> {
            try {
                // This would call the existing video loading logic
                // For now, initialize with empty list
                initializeWithVideos(new ArrayList<>());
                
            } catch (Exception e) {
                Log.e(TAG, "Error loading videos from storage", e);
            } finally {
                setLoadingState(false);
            }
        });
    }
    
    /**
     * Create scroll listener for RecyclerView to trigger progressive loading
     */
    public RecyclerView.OnScrollListener createProgressiveScrollListener() {
        return new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                
                if (dy <= 0 || !hasMoreVideos || isLoading) return; // Only load on downward scroll
                
                LinearLayoutManager layoutManager = (LinearLayoutManager) recyclerView.getLayoutManager();
                if (layoutManager == null) return;
                
                int visibleItemCount = layoutManager.getChildCount();
                int totalItemCount = layoutManager.getItemCount();
                int firstVisibleItem = layoutManager.findFirstVisibleItemPosition();
                
                // Load more when approaching end
                if ((visibleItemCount + firstVisibleItem + PREFETCH_THRESHOLD) >= totalItemCount) {
                    Log.d(TAG, "Scroll threshold reached - loading next batch");
                    loadNextBatch();
                }
            }
        };
    }
    
    /**
     * Reset for fresh loading session
     */
    public void reset() {
        allVideos.clear();
        currentLoadedIndex = 0;
        hasMoreVideos = true;
        setLoadingState(false);
        Log.d(TAG, "Progressive loader reset");
    }
    
    /**
     * Update loading state
     */
    private void setLoadingState(boolean loading) {
        if (isLoading != loading) {
            isLoading = loading;
            if (listener != null) {
                listener.onLoadingStateChanged(loading);
            }
        }
    }
    
    /**
     * Check if more videos are available to load
     */
    public boolean hasMoreVideos() {
        return hasMoreVideos;
    }
    
    /**
     * Get current loading state
     */
    public boolean isLoading() {
        return isLoading;
    }
}
