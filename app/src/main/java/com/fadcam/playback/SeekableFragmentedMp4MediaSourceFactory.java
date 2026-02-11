package com.fadcam.playback;

import android.content.Context;
import android.media.MediaMetadataRetriever;
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
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.FileDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.source.ClippingMediaSource;
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
import java.io.InputStream;

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
     * <p>For {@code file://} URIs, builds a full fragment index using
     * {@link FragmentedMp4IndexBuilder} for precise seeking. For {@code content://} URIs
     * (SAF / custom storage), uses a {@link ClippingMediaSource} with the duration
     * obtained from {@link MediaMetadataRetriever}, because
     * {@link FragmentedMp4IndexBuilder} requires {@link java.io.RandomAccessFile} which
     * is unavailable for content URIs.
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

        String scheme = uri.getScheme();

        if ("content".equals(scheme)) {
            // For content:// URIs, use ClippingMediaSource approach since
            // FragmentedMp4IndexBuilder requires RandomAccessFile (file:// only).
            return createContentUriMediaSource(mediaItem, uri);
        }

        // Build the fragment index for file:// URIs
        FragmentedMp4IndexBuilder.FragmentIndex fragmentIndex = null;
        if ("file".equals(scheme)) {
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
     * Creates a MediaSource for a {@code content://} URI fMP4 file.
     *
     * <p>First tries to reconstruct the real file path from the SAF URI
     * (e.g. {@code /storage/emulated/0/Download/FadCam/file.mp4}). If the
     * file is accessible on disk, uses the full
     * {@link FragmentedMp4IndexBuilder} approach (same quality as
     * {@code file://} URIs). Otherwise falls back to the
     * {@link ClippingMediaSource} approach with duration from
     * {@link MediaMetadataRetriever}.
     */
    @NonNull
    private MediaSource createContentUriMediaSource(@NonNull MediaItem mediaItem,
                                                     @NonNull Uri uri) {
        // Try to reconstruct path — if available, use full index-based approach
        String reconstructedPath = tryReconstructFilePath(uri);
        if (reconstructedPath != null) {
            File file = new File(reconstructedPath);
            Log.d(TAG, "Content URI → reconstructed path: " + reconstructedPath);

            long startTime = System.currentTimeMillis();
            FragmentedMp4IndexBuilder.FragmentIndex fragmentIndex = indexBuilder.buildIndex(file);
            long scanTime = System.currentTimeMillis() - startTime;
            Log.d(TAG, "Index built from content URI in " + scanTime + "ms: " +
                  fragmentIndex.fragments.size() + " fragments, " +
                  "duration=" + (fragmentIndex.durationUs / 1_000_000.0) + "s, " +
                  "seekable=" + fragmentIndex.isSeekable);

            // Build MediaItem from file URI for FileDataSource compatibility
            Uri fileUri = Uri.fromFile(file);
            MediaItem fileItem = MediaItem.fromUri(fileUri);

            final FragmentedMp4IndexBuilder.FragmentIndex index = fragmentIndex;
            ExtractorsFactory extractorsFactory = () -> new Extractor[] {
                new SeekMapInjectingExtractor(index)
            };

            DataSource.Factory dataSourceFactory = new FileDataSource.Factory();
            return new ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory)
                    .createMediaSource(fileItem);
        }

        // Fallback 1: Use /proc/self/fd/ trick for full index-based seeking
        // This works for any content:// URI including SD card files
        Log.d(TAG, "Path reconstruction failed, trying FD-based index building");
        android.os.ParcelFileDescriptor pfd = null;
        try {
            pfd = context.getContentResolver().openFileDescriptor(uri, "r");
            if (pfd != null) {
                String fdPath = "/proc/self/fd/" + pfd.getFd();
                File fdFile = new File(fdPath);
                if (fdFile.exists() && fdFile.canRead()) {
                    long startTime = System.currentTimeMillis();
                    FragmentedMp4IndexBuilder.FragmentIndex fragmentIndex =
                            indexBuilder.buildIndex(fdFile);
                    long scanTime = System.currentTimeMillis() - startTime;
                    Log.d(TAG, "Index built from FD in " + scanTime + "ms: " +
                          fragmentIndex.fragments.size() + " fragments, " +
                          "duration=" + (fragmentIndex.durationUs / 1_000_000.0) + "s, " +
                          "seekable=" + fragmentIndex.isSeekable);

                    if (fragmentIndex.isSeekable && !fragmentIndex.fragments.isEmpty()) {
                        final FragmentedMp4IndexBuilder.FragmentIndex index = fragmentIndex;
                        ExtractorsFactory extractorsFactory = () -> new Extractor[] {
                            new SeekMapInjectingExtractor(index)
                        };
                        DataSource.Factory ds = new DefaultDataSource.Factory(context);
                        // Keep PFD open for the lifetime of playback
                        final android.os.ParcelFileDescriptor keptPfd = pfd;
                        pfd = null; // Prevent closing in finally
                        return new ProgressiveMediaSource.Factory(ds, extractorsFactory)
                                .createMediaSource(mediaItem);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "FD-based index building failed: " + e.getMessage());
        } finally {
            if (pfd != null) {
                try { pfd.close(); } catch (Exception ignored) {}
            }
        }

        // Fallback 2: ClippingMediaSource with DefaultDataSource
        Log.d(TAG, "Could not build index, using ClippingMediaSource fallback");
        long durationUs = getDurationFromRetriever(uri);

        // DefaultDataSource handles content:// URIs natively
        DataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(context);

        ExtractorsFactory extractorsFactory = () -> new Extractor[] {
            new SeekMapInjectingExtractor(null)
        };

        MediaSource baseSource = new ProgressiveMediaSource.Factory(
                dataSourceFactory, extractorsFactory)
                .createMediaSource(mediaItem);

        if (durationUs > 0) {
            Log.d(TAG, "Content URI fMP4: ClippingMediaSource duration=" +
                  (durationUs / 1_000_000.0) + "s");
            return new ClippingMediaSource(
                    baseSource,
                    /* startPositionUs= */ 0,
                    /* endPositionUs= */ durationUs,
                    /* enableInitialDiscontinuity= */ false,
                    /* allowDynamicClippingUpdates= */ true,
                    /* relativeToDefaultPosition= */ false
            );
        }

        Log.w(TAG, "Content URI fMP4: could not determine duration, using raw source");
        return baseSource;
    }

    /**
     * Attempts to reconstruct a real file system path from a SAF
     * {@code content://} URI.
     *
     * <p>SAF URIs on primary/external storage often contain a colon-separated
     * relative path. For example:
     * <pre>
     * content://...document/primary:Download/FadCam/file.mp4
     * content://...document/XXXX-XXXX:FadCam/file.mp4  (SD card)
     * </pre>
     * This method checks ALL mounted storage volumes (internal + SD cards)
     * instead of hardcoding {@code /storage/emulated/0/}.
     *
     * @return the reconstructed absolute path if the file exists and is
     *         readable, or {@code null} otherwise.
     */
    @Nullable
    private String tryReconstructFilePath(@NonNull Uri uri) {
        String path = uri.getPath();
        if (path == null || !path.contains(":")) return null;

        int lastColonIndex = path.lastIndexOf(':');
        if (lastColonIndex < 0 || lastColonIndex >= path.length() - 1) return null;

        String relativePath = path.substring(lastColonIndex + 1);

        // Try all mounted storage volumes (internal + SD cards)
        try {
            File[] externalDirs = context.getExternalFilesDirs(null);
            if (externalDirs != null) {
                for (File dir : externalDirs) {
                    if (dir == null) continue;
                    // externalDirs paths: /storage/XXXX-XXXX/Android/data/com.fadcam/files
                    String dirPath = dir.getAbsolutePath();
                    int androidIdx = dirPath.indexOf("/Android/");
                    if (androidIdx > 0) {
                        String volumeRoot = dirPath.substring(0, androidIdx + 1);
                        String reconstructed = volumeRoot + relativePath;
                        File file = new File(reconstructed);
                        if (file.exists() && file.canRead()) {
                            return reconstructed;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error resolving storage volumes: " + e.getMessage());
        }
        return null;
    }

    /**
     * Retrieves the media duration using {@link MediaMetadataRetriever}, which
     * correctly parses fragmented MP4 structure for both {@code file://} and
     * {@code content://} URIs. For content:// URIs, tries the reconstructed
     * file path first since {@code setDataSource(context, contentUri)} often
     * returns 0 for fMP4 files.
     *
     * @return Duration in microseconds, or {@link C#TIME_UNSET} on failure.
     */
    private long getDurationFromRetriever(@NonNull Uri uri) {
        MediaMetadataRetriever retriever = null;
        try {
            retriever = new MediaMetadataRetriever();
            String scheme = uri.getScheme();

            if ("content".equals(scheme)) {
                // Reconstructed path works much better for fMP4 files
                String reconstructedPath = tryReconstructFilePath(uri);
                if (reconstructedPath != null) {
                    Log.d(TAG, "Retriever: using reconstructed path: " + reconstructedPath);
                    retriever.setDataSource(reconstructedPath);
                } else {
                    retriever.setDataSource(context, uri);
                }
            } else if ("file".equals(scheme)) {
                retriever.setDataSource(uri.getPath());
            } else {
                retriever.setDataSource(uri.toString());
            }
            String durationStr = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationStr != null) {
                long durationMs = Long.parseLong(durationStr);
                long durationUs = durationMs * 1000;
                Log.d(TAG, "Retriever duration: " + durationMs + "ms for " +
                      uri.getLastPathSegment());
                return durationUs;
            }
        } catch (Exception e) {
            Log.w(TAG, "Error getting duration via Retriever for: " + uri, e);
        } finally {
            if (retriever != null) {
                try { retriever.release(); } catch (Exception ignored) {}
            }
        }
        return C.TIME_UNSET;
    }

    /**
     * Checks if a URI points to a fragmented MP4 that needs our special handling.
     * Supports both {@code file://} and {@code content://} (SAF / custom storage) URIs.
     */
    public boolean isFragmentedMp4(@NonNull Uri uri) {
        String scheme = uri.getScheme();
        if ("file".equals(scheme)) {
            String path = uri.getPath();
            if (path == null) return false;
            File file = new File(path);
            return isFragmentedMp4File(file);
        } else if ("content".equals(scheme)) {
            return isFragmentedMp4ContentUri(uri);
        }
        return false;
    }

    /**
     * Checks if a {@code content://} URI points to a fragmented MP4 by reading
     * the first &leq;1&thinsp;MB through ContentResolver and looking for
     * {@code ftyp} + {@code moof} box types.
     */
    private boolean isFragmentedMp4ContentUri(@NonNull Uri uri) {
        InputStream is = null;
        try {
            is = context.getContentResolver().openInputStream(uri);
            if (is == null) return false;

            // Read up to 1MB (enough to find ftyp near the start and the first moof)
            byte[] data = new byte[1024 * 1024];
            int totalRead = 0;
            while (totalRead < data.length) {
                int n = is.read(data, totalRead, data.length - totalRead);
                if (n == -1) break;
                totalRead += n;
            }

            boolean foundFtyp = false;
            boolean foundMoof = false;
            int position = 0;

            while (position + 8 <= totalRead) {
                long size = ((long)(data[position] & 0xFF) << 24) |
                            ((long)(data[position + 1] & 0xFF) << 16) |
                            ((long)(data[position + 2] & 0xFF) << 8) |
                            ((long)(data[position + 3] & 0xFF));
                int type = ((data[position + 4] & 0xFF) << 24) |
                           ((data[position + 5] & 0xFF) << 16) |
                           ((data[position + 6] & 0xFF) << 8) |
                           (data[position + 7] & 0xFF);

                // Handle extended size (size == 1)
                if (size == 1 && position + 16 <= totalRead) {
                    size = ((long)(data[position + 8] & 0xFF) << 56) |
                           ((long)(data[position + 9] & 0xFF) << 48) |
                           ((long)(data[position + 10] & 0xFF) << 40) |
                           ((long)(data[position + 11] & 0xFF) << 32) |
                           ((long)(data[position + 12] & 0xFF) << 24) |
                           ((long)(data[position + 13] & 0xFF) << 16) |
                           ((long)(data[position + 14] & 0xFF) << 8) |
                           ((long)(data[position + 15] & 0xFF));
                } else if (size == 0) {
                    // Size 0 means box extends to EOF — impossible to skip, stop scanning
                    break;
                }

                if (size < 8) break; // Invalid box

                if (type == 0x66747970) {        // 'ftyp'
                    foundFtyp = true;
                } else if (type == 0x6D6F6F66) { // 'moof'
                    foundMoof = true;
                    break;
                }

                if (size > totalRead - position) break; // Box extends past buffer
                position += (int) size;
            }

            boolean result = foundFtyp && foundMoof;
            Log.d(TAG, "isFragmentedMp4ContentUri: " + result +
                  " (ftyp=" + foundFtyp + ", moof=" + foundMoof +
                  ", scanned " + totalRead + " bytes) for " + uri.getLastPathSegment());
            return result;

        } catch (Exception e) {
            Log.w(TAG, "Error checking content URI for fMP4: " + uri, e);
            return false;
        } finally {
            if (is != null) {
                try { is.close(); } catch (Exception ignored) {}
            }
        }
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
