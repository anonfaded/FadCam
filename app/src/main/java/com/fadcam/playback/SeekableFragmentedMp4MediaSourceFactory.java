package com.fadcam.playback;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.FileDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.extractor.ChunkIndex;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.SniffFailure;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.mp4.FragmentedMp4Extractor;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;

import com.google.common.collect.ImmutableList;

import java.io.File;
import java.io.IOException;

/**
 * Factory for creating MediaSources for fragmented MP4 files with proper seeking support.
 * 
 * <p>This factory pre-scans fMP4 files to build a fragment index, then wraps the standard
 * FragmentedMp4Extractor to provide a seekable SeekMap. This is the VLC-like approach
 * that enables seeking without requiring sidx boxes in the MP4 file.
 * 
 * <p>Usage:
 * <pre>
 * SeekableFragmentedMp4MediaSourceFactory factory = 
 *     new SeekableFragmentedMp4MediaSourceFactory(context);
 * MediaSource source = factory.createMediaSource(MediaItem.fromUri(uri));
 * player.setMediaSource(source);
 * </pre>
 */
@OptIn(markerClass = UnstableApi.class)
public class SeekableFragmentedMp4MediaSourceFactory {

    private static final String TAG = "SeekableFmp4Factory";

    private final Context context;
    private final FragmentedMp4IndexBuilder indexBuilder;

    public SeekableFragmentedMp4MediaSourceFactory(@NonNull Context context) {
        this.context = context.getApplicationContext();
        this.indexBuilder = new FragmentedMp4IndexBuilder();
    }

    /**
     * Creates a MediaSource for the given MediaItem with proper fMP4 seeking support.
     * 
     * @param mediaItem The MediaItem to create a source for.
     * @return A MediaSource with proper seeking for fMP4 files.
     */
    @NonNull
    public MediaSource createMediaSource(@NonNull MediaItem mediaItem) {
        Uri uri = mediaItem.localConfiguration != null ? 
                  mediaItem.localConfiguration.uri : null;
        
        if (uri == null) {
            throw new IllegalArgumentException("MediaItem has no URI");
        }

        // Build the fragment index for this file
        FragmentedMp4IndexBuilder.FragmentIndex fragmentIndex = null;
        if ("file".equals(uri.getScheme())) {
            String path = uri.getPath();
            if (path != null) {
                File file = new File(path);
                if (file.exists()) {
                    long startTime = System.currentTimeMillis();
                    fragmentIndex = indexBuilder.buildIndex(file);
                    long scanTime = System.currentTimeMillis() - startTime;
                    Log.d(TAG, "Index built in " + scanTime + "ms: " + 
                          fragmentIndex.fragments.size() + " fragments, " +
                          "duration=" + (fragmentIndex.durationUs / 1000000.0) + "s, " +
                          "seekable=" + fragmentIndex.isSeekable);
                }
            }
        }

        // Create the ExtractorsFactory with our index
        final FragmentedMp4IndexBuilder.FragmentIndex index = fragmentIndex;
        ExtractorsFactory extractorsFactory = () -> new Extractor[] {
            new SeekMapInjectingExtractor(index)
        };

        // Create ProgressiveMediaSource with our custom extractor
        DataSource.Factory dataSourceFactory = new FileDataSource.Factory();
        
        return new ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory)
                .createMediaSource(mediaItem);
    }

    /**
     * Checks if a file is a fragmented MP4 that needs our special handling.
     */
    public boolean isFragmentedMp4(@NonNull Uri uri) {
        if (!"file".equals(uri.getScheme())) {
            return false;
        }
        String path = uri.getPath();
        if (path == null) {
            return false;
        }
        File file = new File(path);
        return isFragmentedMp4File(file);
    }

    /**
     * Checks if a file is a fragmented MP4 by looking for moof boxes.
     */
    private boolean isFragmentedMp4File(@NonNull File file) {
        if (!file.exists() || !file.canRead()) {
            return false;
        }
        
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
            byte[] header = new byte[8];
            long position = 0;
            long fileLength = raf.length();
            
            // Scan first few boxes to check for ftyp and moof
            boolean foundFtyp = false;
            boolean foundMoof = false;
            
            while (position < Math.min(fileLength, 1024 * 1024)) { // Scan first 1MB
                raf.seek(position);
                if (raf.read(header, 0, 8) != 8) {
                    break;
                }
                
                long size = ((long)(header[0] & 0xFF) << 24) | 
                           ((long)(header[1] & 0xFF) << 16) | 
                           ((long)(header[2] & 0xFF) << 8) | 
                           ((long)(header[3] & 0xFF));
                int type = ((header[4] & 0xFF) << 24) | 
                          ((header[5] & 0xFF) << 16) | 
                          ((header[6] & 0xFF) << 8) | 
                          (header[7] & 0xFF);
                
                if (size == 1) {
                    // Extended size
                    if (raf.read(header, 0, 8) != 8) {
                        break;
                    }
                    size = ((long)(header[0] & 0xFF) << 56) |
                           ((long)(header[1] & 0xFF) << 48) |
                           ((long)(header[2] & 0xFF) << 40) |
                           ((long)(header[3] & 0xFF) << 32) |
                           ((long)(header[4] & 0xFF) << 24) |
                           ((long)(header[5] & 0xFF) << 16) |
                           ((long)(header[6] & 0xFF) << 8) |
                           ((long)(header[7] & 0xFF));
                } else if (size == 0) {
                    size = fileLength - position;
                }
                
                if (size < 8) {
                    break;
                }
                
                // Check box type
                if (type == 0x66747970) { // 'ftyp'
                    foundFtyp = true;
                } else if (type == 0x6D6F6F66) { // 'moof'
                    foundMoof = true;
                    break; // Found what we need
                }
                
                position += size;
            }
            
            return foundFtyp && foundMoof;
            
        } catch (IOException e) {
            Log.w(TAG, "Error checking file type", e);
            return false;
        }
    }

    /**
     * Extractor that wraps FragmentedMp4Extractor and injects a pre-built SeekMap.
     */
    private static class SeekMapInjectingExtractor implements Extractor {
        
        private static final String TAG_EXTRACTOR = "SeekMapInjExtractor";
        
        private final FragmentedMp4Extractor delegate;
        @Nullable
        private final FragmentedMp4IndexBuilder.FragmentIndex fragmentIndex;
        @Nullable
        private ChunkIndex seekableChunkIndex;
        private ExtractorOutput wrappedOutput;

        SeekMapInjectingExtractor(@Nullable FragmentedMp4IndexBuilder.FragmentIndex fragmentIndex) {
            this.delegate = new FragmentedMp4Extractor(new DefaultSubtitleParserFactory());
            this.fragmentIndex = fragmentIndex;
            
            // Build ChunkIndex from fragment index
            if (fragmentIndex != null && fragmentIndex.isSeekable) {
                int count = fragmentIndex.fragments.size();
                int[] sizes = fragmentIndex.getSizes();
                long[] offsets = fragmentIndex.getOffsets();
                long[] durationsUs = fragmentIndex.getDurationsUs();
                long[] timesUs = fragmentIndex.getTimesUs();
                
                this.seekableChunkIndex = new ChunkIndex(sizes, offsets, durationsUs, timesUs);
                Log.i(TAG_EXTRACTOR, "Created ChunkIndex with " + count + " fragments, " +
                      "duration=" + (seekableChunkIndex.getDurationUs() / 1000000.0) + "s");
            } else {
                Log.w(TAG_EXTRACTOR, "No fragment index available, seeking will not work");
            }
        }

        @Override
        public boolean sniff(@NonNull ExtractorInput input) throws IOException {
            return delegate.sniff(input);
        }

        @NonNull
        @Override
        public ImmutableList<SniffFailure> getSniffFailureDetails() {
            return delegate.getSniffFailureDetails();
        }

        @Override
        public void init(@NonNull ExtractorOutput output) {
            Log.d(TAG_EXTRACTOR, "init() called, wrapping output to intercept seekMap");
            // Wrap the output to intercept seekMap calls
            this.wrappedOutput = new SeekMapInterceptingOutput(output, seekableChunkIndex);
            delegate.init(wrappedOutput);
        }

        @Override
        public int read(@NonNull ExtractorInput input, @NonNull PositionHolder seekPosition) 
                throws IOException {
            return delegate.read(input, seekPosition);
        }

        @Override
        public void seek(long position, long timeUs) {
            Log.d(TAG_EXTRACTOR, "seek() called: position=" + position + ", timeUs=" + timeUs + 
                  " (" + (timeUs / 1000000.0) + "s)");
            delegate.seek(position, timeUs);
        }

        @Override
        public void release() {
            delegate.release();
        }
    }

    /**
     * ExtractorOutput wrapper that intercepts seekMap() to provide our pre-built index.
     */
    private static class SeekMapInterceptingOutput implements ExtractorOutput {
        
        private static final String TAG_OUTPUT = "SeekMapIntercept";
        
        private final ExtractorOutput delegate;
        @Nullable
        private final ChunkIndex seekableChunkIndex;

        SeekMapInterceptingOutput(@NonNull ExtractorOutput delegate, 
                                   @Nullable ChunkIndex seekableChunkIndex) {
            this.delegate = delegate;
            this.seekableChunkIndex = seekableChunkIndex;
        }

        @NonNull
        @Override
        public TrackOutput track(int id, int type) {
            return delegate.track(id, type);
        }

        @Override
        public void endTracks() {
            delegate.endTracks();
        }

        @Override
        public void seekMap(@NonNull SeekMap seekMap) {
            Log.i(TAG_OUTPUT, "seekMap() called: isSeekable=" + seekMap.isSeekable() + 
                  ", duration=" + (seekMap.getDurationUs() / 1000000.0) + "s");
            
            if (seekableChunkIndex != null && !seekMap.isSeekable()) {
                // Replace UNSEEKABLE with our pre-built ChunkIndex
                Log.i(TAG_OUTPUT, "*** REPLACING UNSEEKABLE SeekMap with pre-built ChunkIndex ***");
                Log.i(TAG_OUTPUT, "ChunkIndex: duration=" + (seekableChunkIndex.getDurationUs() / 1000000.0) + "s");
                delegate.seekMap(seekableChunkIndex);
            } else if (seekMap.isSeekable()) {
                // Delegate already has a seekable map (has sidx)
                Log.d(TAG_OUTPUT, "Using delegate's seekable SeekMap (file has sidx)");
                delegate.seekMap(seekMap);
            } else {
                // No index and delegate is unseekable - pass through
                Log.w(TAG_OUTPUT, "No pre-built index, using original UNSEEKABLE SeekMap");
                delegate.seekMap(seekMap);
            }
        }
    }
}
