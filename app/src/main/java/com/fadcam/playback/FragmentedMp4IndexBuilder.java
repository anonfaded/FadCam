package com.fadcam.playback;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Scans a fragmented MP4 file to find all moof (movie fragment) boxes and their
 * timestamps, building a seek index similar to sidx box data.
 * 
 * <p>This is the VLC-like approach - scan all fragments to enable seeking in fMP4
 * files that lack sidx boxes (which Media3's FragmentedMp4Muxer doesn't write).
 * 
 * <p>Usage:
 * <pre>
 * FragmentedMp4IndexBuilder builder = new FragmentedMp4IndexBuilder();
 * FragmentIndex index = builder.buildIndex(new File(path));
 * // Use index.getSeekPosition(timeUs) to find byte offset for seeking
 * </pre>
 */
public class FragmentedMp4IndexBuilder {

    private static final String TAG = "Fmp4IndexBuilder";

    // MP4 box type constants
    private static final int TYPE_ftyp = 0x66747970; // 'ftyp'
    private static final int TYPE_moov = 0x6D6F6F76; // 'moov'
    private static final int TYPE_moof = 0x6D6F6F66; // 'moof'
    private static final int TYPE_mdat = 0x6D646174; // 'mdat'
    private static final int TYPE_mvhd = 0x6D766864; // 'mvhd' (movie header)
    private static final int TYPE_trak = 0x7472616B; // 'trak' (track)
    private static final int TYPE_mdia = 0x6D646961; // 'mdia' (media)
    private static final int TYPE_mdhd = 0x6D646864; // 'mdhd' (media header)
    private static final int TYPE_traf = 0x74726166; // 'traf' (track fragment)
    private static final int TYPE_tfdt = 0x74666474; // 'tfdt' (track fragment decode time)
    private static final int TYPE_trun = 0x7472756E; // 'trun' (track fragment run)

    /**
     * Represents a single fragment in the MP4 file.
     */
    public static class FragmentEntry {
        /** Byte offset of the moof box start. */
        public final long position;
        /** Size of moof + mdat in bytes. */
        public final long size;
        /** Presentation time in microseconds. */
        public final long timeUs;
        /** Duration in microseconds. */
        public long durationUs;

        FragmentEntry(long position, long size, long timeUs) {
            this.position = position;
            this.size = size;
            this.timeUs = timeUs;
            this.durationUs = 0;
        }
    }

    /**
     * Index of all fragments in the file with timing information.
     */
    public static class FragmentIndex {
        /** List of all fragments in the file. */
        public final List<FragmentEntry> fragments;
        /** Total duration in microseconds. */
        public final long durationUs;
        /** Timescale from moov/mvhd. */
        public final long timescale;
        /** Whether this index is valid for seeking. */
        public final boolean isSeekable;

        FragmentIndex(List<FragmentEntry> fragments, long durationUs, long timescale) {
            this.fragments = fragments;
            this.durationUs = durationUs;
            this.timescale = timescale;
            this.isSeekable = !fragments.isEmpty();
        }

        /**
         * Gets the byte position to seek to for a given time.
         * 
         * @param timeUs The target time in microseconds.
         * @return The byte position of the moof box to seek to, or 0 if not found.
         */
        public long getSeekPosition(long timeUs) {
            if (fragments.isEmpty()) {
                return 0;
            }

            // Binary search for the fragment at or before timeUs
            int left = 0;
            int right = fragments.size() - 1;
            int result = 0;

            while (left <= right) {
                int mid = (left + right) / 2;
                FragmentEntry fragment = fragments.get(mid);
                
                if (fragment.timeUs <= timeUs) {
                    result = mid;
                    left = mid + 1;
                } else {
                    right = mid - 1;
                }
            }

            return fragments.get(result).position;
        }

        /**
         * Gets the time in microseconds for a given fragment index.
         */
        public long getTimeUs(int fragmentIndex) {
            if (fragmentIndex >= 0 && fragmentIndex < fragments.size()) {
                return fragments.get(fragmentIndex).timeUs;
            }
            return 0;
        }

        /**
         * Gets the fragment index for a given time.
         */
        public int getFragmentIndex(long timeUs) {
            if (fragments.isEmpty()) {
                return 0;
            }

            int left = 0;
            int right = fragments.size() - 1;
            int result = 0;

            while (left <= right) {
                int mid = (left + right) / 2;
                FragmentEntry fragment = fragments.get(mid);
                
                if (fragment.timeUs <= timeUs) {
                    result = mid;
                    left = mid + 1;
                } else {
                    right = mid - 1;
                }
            }

            return result;
        }

        /**
         * Gets arrays suitable for creating a ChunkIndex.
         */
        public int[] getSizes() {
            int[] sizes = new int[fragments.size()];
            for (int i = 0; i < fragments.size(); i++) {
                sizes[i] = (int) fragments.get(i).size;
            }
            return sizes;
        }

        public long[] getOffsets() {
            long[] offsets = new long[fragments.size()];
            for (int i = 0; i < fragments.size(); i++) {
                offsets[i] = fragments.get(i).position;
            }
            return offsets;
        }

        public long[] getDurationsUs() {
            long[] durations = new long[fragments.size()];
            for (int i = 0; i < fragments.size(); i++) {
                durations[i] = fragments.get(i).durationUs;
            }
            return durations;
        }

        public long[] getTimesUs() {
            long[] times = new long[fragments.size()];
            for (int i = 0; i < fragments.size(); i++) {
                times[i] = fragments.get(i).timeUs;
            }
            return times;
        }
    }

    /**
     * Builds a fragment index for the given file.
     * 
     * @param file The fragmented MP4 file to scan.
     * @return A FragmentIndex with all fragments, or an empty index if scanning fails.
     */
    @NonNull
    public FragmentIndex buildIndex(@NonNull File file) {
        List<FragmentEntry> fragments = new ArrayList<>();
        long timescale = 1000; // Default timescale
        int videoTrackId = 0; // Video track ID from moov (0 = unknown)

        if (!file.exists() || !file.canRead()) {
            Log.e(TAG, "File does not exist or is not readable: " + file.getPath());
            return new FragmentIndex(fragments, 0, timescale);
        }

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long fileLength = raf.length();
            long position = 0;
            byte[] header = new byte[16]; // Buffer for atom headers

            Log.d(TAG, "Starting fragment scan: file=" + file.getName() + ", length=" + fileLength);

            // First pass: find moov and get timescale and video track ID
            while (position < fileLength) {
                raf.seek(position);
                if (raf.read(header, 0, 8) != 8) {
                    break;
                }

                long atomSize = readUint32(header, 0);
                int atomType = readInt32(header, 4);

                // Handle extended size
                if (atomSize == 1) {
                    if (raf.read(header, 8, 8) != 8) {
                        break;
                    }
                    atomSize = readInt64(header, 8);
                } else if (atomSize == 0) {
                    atomSize = fileLength - position;
                }

                if (atomSize < 8) {
                    Log.w(TAG, "Invalid atom size at position " + position);
                    break;
                }

                if (atomType == TYPE_moov) {
                    MoovInfo moovInfo = readMoovInfo(raf, position, atomSize);
                    timescale = moovInfo.videoTimescale;
                    videoTrackId = moovInfo.videoTrackId;
                    Log.d(TAG, "Found moov, timescale=" + timescale + ", videoTrackId=" + videoTrackId);
                }

                position += atomSize;
            }

            // Second pass: find all moof boxes
            position = 0;
            // Track cumulative decode time - since Media3's FragmentedMp4Muxer doesn't write tfdt,
            // we need to calculate it from trun sample durations
            long cumulativeDecodeTimeUs = 0;

            while (position < fileLength) {
                raf.seek(position);
                if (raf.read(header, 0, 8) != 8) {
                    break;
                }

                long atomSize = readUint32(header, 0);
                int atomType = readInt32(header, 4);

                // Handle extended size
                if (atomSize == 1) {
                    if (raf.read(header, 8, 8) != 8) {
                        break;
                    }
                    atomSize = readInt64(header, 8);
                } else if (atomSize == 0) {
                    atomSize = fileLength - position;
                }

                if (atomSize < 8) {
                    break;
                }

                if (atomType == TYPE_moof) {
                    // Found moof - read fragment duration from video track's trun
                    long fragmentDurationUnits = readMoofFragmentDuration(raf, position, atomSize, timescale, videoTrackId);
                    long fragmentDurationUs = (fragmentDurationUnits * 1000000L) / timescale;
                    
                    Log.d(TAG, "Found moof at " + position + ": fragmentDurationUnits=" + fragmentDurationUnits +
                          ", fragmentDurationUs=" + fragmentDurationUs);

                    // Find mdat size following moof
                    long mdatSize = 0;
                    long mdatPos = position + atomSize;
                    if (mdatPos < fileLength) {
                        raf.seek(mdatPos);
                        if (raf.read(header, 0, 8) == 8) {
                            long mdatAtomSize = readUint32(header, 0);
                            int mdatAtomType = readInt32(header, 4);
                            if (mdatAtomType == TYPE_mdat) {
                                if (mdatAtomSize == 1) {
                                    if (raf.read(header, 8, 8) == 8) {
                                        mdatAtomSize = readInt64(header, 8);
                                    }
                                }
                                mdatSize = mdatAtomSize;
                            }
                        }
                    }

                    long fragmentSize = atomSize + mdatSize;
                    
                    // Create fragment entry with current cumulative time
                    FragmentEntry entry = new FragmentEntry(position, fragmentSize, cumulativeDecodeTimeUs);
                    entry.durationUs = fragmentDurationUs > 0 ? fragmentDurationUs : 2000000; // Default 2s
                    fragments.add(entry);

                    Log.d(TAG, "Fragment #" + fragments.size() + " at " + position + ": timeUs=" + cumulativeDecodeTimeUs + 
                          " (" + (cumulativeDecodeTimeUs / 1000000.0) + "s), durationUs=" + entry.durationUs +
                          ", size=" + fragmentSize);

                    // Update cumulative time for next fragment
                    cumulativeDecodeTimeUs += entry.durationUs;
                }

                position += atomSize;
            }

        } catch (IOException e) {
            Log.e(TAG, "Error scanning file: " + e.getMessage(), e);
        }

        // Calculate total duration
        long totalDurationUs = 0;
        if (!fragments.isEmpty()) {
            FragmentEntry last = fragments.get(fragments.size() - 1);
            totalDurationUs = last.timeUs + last.durationUs;
        }

        Log.d(TAG, "Index built: " + fragments.size() + " fragments, totalDuration=" + 
              (totalDurationUs / 1000000.0) + "s");

        return new FragmentIndex(fragments, totalDurationUs, timescale);
    }

    /**
     * Reads the timescale and video track ID from the moov box.
     * Prefers video track's mdhd timescale since trun durations are in track timescale.
     */
    private MoovInfo readMoovInfo(RandomAccessFile raf, long moovPosition, long moovSize) 
            throws IOException {
        long position = moovPosition + 8; // Skip moov header
        long moovEnd = moovPosition + moovSize;
        byte[] header = new byte[32];
        long videoTimescale = 0;
        long firstTrackTimescale = 0;
        long mvhdTimescale = 0;
        int videoTrackId = 0;
        int trackNumber = 0; // Counter for track ID (1-based)

        Log.d(TAG, "Scanning moov at " + moovPosition + ", size=" + moovSize);

        while (position < moovEnd) {
            raf.seek(position);
            if (raf.read(header, 0, 8) != 8) {
                break;
            }

            long atomSize = readUint32(header, 0);
            int atomType = readInt32(header, 4);
            String atomName = atomTypeToString(atomType);

            if (atomSize == 1) {
                if (raf.read(header, 8, 8) != 8) {
                    break;
                }
                atomSize = readInt64(header, 8);
            }

            if (atomSize < 8) {
                break;
            }

            Log.d(TAG, "  Found atom: " + atomName + " at " + position + ", size=" + atomSize);

            if (atomType == TYPE_mvhd) {
                // Read mvhd for movie timescale (fallback)
                raf.seek(position + 8);
                if (raf.read(header, 0, 24) == 24) {
                    int version = header[0] & 0xFF;
                    if (version == 0) {
                        mvhdTimescale = readUint32(header, 12);
                    } else {
                        mvhdTimescale = readUint32(header, 20);
                    }
                    Log.d(TAG, "  mvhd version=" + version + ", timescale=" + mvhdTimescale);
                }
            } else if (atomType == TYPE_trak) {
                // Track IDs are 1-based and assigned in order of trak appearance
                trackNumber++;
                // Read track's mdhd timescale and check if it's video
                TrackInfo trackInfo = readTrakInfo(raf, position, atomSize);
                if (trackInfo != null) {
                    trackInfo.trackId = trackNumber;
                    if (firstTrackTimescale == 0) {
                        firstTrackTimescale = trackInfo.timescale;
                    }
                    if (trackInfo.isVideo && videoTimescale == 0) {
                        videoTimescale = trackInfo.timescale;
                        videoTrackId = trackNumber;
                        Log.d(TAG, "  Found VIDEO track: id=" + videoTrackId + ", timescale=" + videoTimescale);
                    }
                }
            }

            position += atomSize;
        }

        // Prefer video track timescale, fall back to first track, then mvhd
        long timescale = videoTimescale > 0 ? videoTimescale : 
                      (firstTrackTimescale > 0 ? firstTrackTimescale : 
                      (mvhdTimescale > 0 ? mvhdTimescale : 1000));
        Log.d(TAG, "Using timescale: " + timescale + " (video=" + videoTimescale + 
              ", firstTrack=" + firstTrackTimescale + ", mvhd=" + mvhdTimescale + 
              "), videoTrackId=" + videoTrackId);
        
        MoovInfo moovInfo = new MoovInfo();
        moovInfo.videoTimescale = timescale;
        moovInfo.videoTrackId = videoTrackId;
        return moovInfo;
    }

    /**
     * Info about a track from moov/trak.
     */
    private static class TrackInfo {
        long timescale;
        boolean isVideo;
        int trackId; // 1-based track ID (order in moov)
    }

    /**
     * Info about the movie from moov box.
     */
    private static class MoovInfo {
        long videoTimescale;
        int videoTrackId; // Track ID of the video track
    }

    /**
     * Reads track info (timescale and type) from a trak box.
     */
    private TrackInfo readTrakInfo(RandomAccessFile raf, long trakPosition, long trakSize) 
            throws IOException {
        long position = trakPosition + 8; // Skip trak header  
        long trakEnd = trakPosition + trakSize;
        byte[] header = new byte[32];
        TrackInfo info = new TrackInfo();

        while (position < trakEnd) {
            raf.seek(position);
            if (raf.read(header, 0, 8) != 8) {
                break;
            }

            long atomSize = readUint32(header, 0);
            int atomType = readInt32(header, 4);

            if (atomSize == 1) {
                if (raf.read(header, 8, 8) != 8) {
                    break;
                }
                atomSize = readInt64(header, 8);
            }

            if (atomSize < 8) {
                break;
            }

            if (atomType == TYPE_mdia) {
                // Search mdia for mdhd (timescale) and hdlr (handler type)
                readMdiaInfo(raf, position, atomSize, info);
            }

            position += atomSize;
        }

        return info.timescale > 0 ? info : null;
    }

    /**
     * Reads mdia info - timescale from mdhd and handler type from hdlr.
     */
    private void readMdiaInfo(RandomAccessFile raf, long mdiaPosition, long mdiaSize, TrackInfo info) 
            throws IOException {
        long position = mdiaPosition + 8; // Skip mdia header
        long mdiaEnd = mdiaPosition + mdiaSize;
        byte[] header = new byte[32];

        // hdlr handler type constant for video
        final int HANDLER_vide = 0x76696465; // 'vide'

        while (position < mdiaEnd) {
            raf.seek(position);
            if (raf.read(header, 0, 8) != 8) {
                break;
            }

            long atomSize = readUint32(header, 0);
            int atomType = readInt32(header, 4);

            if (atomSize == 1) {
                if (raf.read(header, 8, 8) != 8) {
                    break;
                }
                atomSize = readInt64(header, 8);
            }

            if (atomSize < 8) {
                break;
            }

            if (atomType == TYPE_mdhd) {
                // Read mdhd for timescale
                raf.seek(position + 8);
                if (raf.read(header, 0, 24) == 24) {
                    int version = header[0] & 0xFF;
                    if (version == 0) {
                        info.timescale = readUint32(header, 12);
                    } else {
                        info.timescale = readUint32(header, 20);
                    }
                    Log.d(TAG, "    mdhd timescale=" + info.timescale);
                }
            } else if (atomType == 0x68646C72) { // 'hdlr'
                // Read hdlr for handler type
                raf.seek(position + 8); // Skip box header
                if (raf.read(header, 0, 16) == 16) {
                    // hdlr: version(1) + flags(3) + pre_defined(4) + handler_type(4)
                    int handlerType = readInt32(header, 8);
                    info.isVideo = (handlerType == HANDLER_vide);
                    Log.d(TAG, "    hdlr handler=" + atomTypeToString(handlerType) + ", isVideo=" + info.isVideo);
                }
            }

            position += atomSize;
        }
    }

    /**
     * Reads the total duration of samples in a moof box from trun boxes.
     * Media3's FragmentedMp4Muxer doesn't write tfdt, so we calculate duration from trun sample_duration.
     * 
     * <p>NOTE: Each moof may contain multiple traf boxes (one per track). We use the VIDEO
     * track's duration since we're using video timescale.
     *
     * @param raf The file to read from.
     * @param moofPosition The position of the moof box.
     * @param moofSize The size of the moof box.
     * @param timescale The timescale (video timescale).
     * @param targetVideoTrackId The video track ID from moov (0 if unknown).
     * @return Total fragment duration in timescale units.
     */
    private long readMoofFragmentDuration(RandomAccessFile raf, long moofPosition, long moofSize, 
            long timescale, int targetVideoTrackId) throws IOException {
        long position = moofPosition + 8; // Skip moof header
        long moofEnd = moofPosition + moofSize;
        byte[] header = new byte[24];
        long videoTrackDuration = 0;
        long firstTrackDuration = 0;

        while (position < moofEnd) {
            raf.seek(position);
            if (raf.read(header, 0, 8) != 8) {
                break;
            }

            long atomSize = readUint32(header, 0);
            int atomType = readInt32(header, 4);

            if (atomSize == 1) {
                if (raf.read(header, 8, 8) != 8) {
                    break;
                }
                atomSize = readInt64(header, 8);
            }

            if (atomSize < 8) {
                break;
            }

            if (atomType == TYPE_traf) {
                // Read tfhd to get track_id, then trun for duration
                TrafInfo trafInfo = readTrafInfo(raf, position, atomSize);
                if (trafInfo != null) {
                    if (firstTrackDuration == 0) {
                        firstTrackDuration = trafInfo.duration;
                    }
                    // Match against the video track ID we found in moov
                    if (trafInfo.trackId == targetVideoTrackId && videoTrackDuration == 0) {
                        videoTrackDuration = trafInfo.duration;
                        Log.d(TAG, "  Using video traf (track " + trafInfo.trackId + ") duration: " + trafInfo.duration + " units");
                    }
                }
            }

            position += atomSize;
        }

        // Prefer video track duration, fall back to first track
        long result = videoTrackDuration > 0 ? videoTrackDuration : firstTrackDuration;
        if (result > 0 && videoTrackDuration == 0) {
            Log.d(TAG, "  No video track found (targetId=" + targetVideoTrackId + "), using first traf duration: " + result + " units");
        }
        return result;
    }

    /**
     * Info from a traf box.
     */
    private static class TrafInfo {
        int trackId;
        long duration;
    }

    /**
     * Reads track_id from tfhd and duration from trun inside a traf.
     */
    private TrafInfo readTrafInfo(RandomAccessFile raf, long trafPosition, long trafSize) 
            throws IOException {
        long position = trafPosition + 8; // Skip traf header
        long trafEnd = trafPosition + trafSize;
        byte[] header = new byte[32];
        TrafInfo info = new TrafInfo();

        // Type constants
        final int TYPE_tfhd = 0x74666864; // 'tfhd'

        while (position < trafEnd) {
            raf.seek(position);
            if (raf.read(header, 0, 8) != 8) {
                break;
            }

            long atomSize = readUint32(header, 0);
            int atomType = readInt32(header, 4);

            if (atomSize == 1) {
                if (raf.read(header, 8, 8) != 8) {
                    break;
                }
                atomSize = readInt64(header, 8);
            }

            if (atomSize < 8) {
                break;
            }

            if (atomType == TYPE_tfhd) {
                // Read tfhd: version(1) + flags(3) + track_id(4)
                raf.seek(position + 8);
                if (raf.read(header, 0, 8) == 8) {
                    info.trackId = readInt32(header, 4);
                }
            } else if (atomType == TYPE_trun) {
                // Parse trun box for sample durations
                long trunDuration = readTrunDuration(raf, position, atomSize);
                info.duration = trunDuration;
            }

            position += atomSize;
        }

        return info.trackId > 0 ? info : null;
    }

    /**
     * Reads sample count and durations from a trun box.
     * 
     * trun box structure:
     * - 1 byte version
     * - 3 bytes flags (tr_flags)
     * - 4 bytes sample_count
     * - if (tr_flags & 0x001) 4 bytes data_offset
     * - if (tr_flags & 0x004) 4 bytes first_sample_flags
     * - then for each sample (sample_count times):
     *   - if (tr_flags & 0x100) 4 bytes sample_duration
     *   - if (tr_flags & 0x200) 4 bytes sample_size
     *   - if (tr_flags & 0x400) 4 bytes sample_flags
     *   - if (tr_flags & 0x800) 4 bytes sample_composition_time_offset
     */
    private long readTrunDuration(RandomAccessFile raf, long trunPosition, long trunSize) 
            throws IOException {
        // Read trun header: version (1) + flags (3) + sample_count (4)
        raf.seek(trunPosition + 8); // Skip box header
        byte[] data = new byte[8];
        if (raf.read(data, 0, 8) != 8) {
            return 0;
        }

        int version = data[0] & 0xFF;
        int flags = ((data[1] & 0xFF) << 16) | ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        long sampleCount = readUint32(data, 4);

        Log.d(TAG, "    trun: version=" + version + ", flags=0x" + Integer.toHexString(flags) + 
              ", sampleCount=" + sampleCount);

        // Check if sample_duration is present
        boolean hasSampleDuration = (flags & 0x100) != 0;
        boolean hasDataOffset = (flags & 0x001) != 0;
        boolean hasFirstSampleFlags = (flags & 0x004) != 0;
        boolean hasSampleSize = (flags & 0x200) != 0;
        boolean hasSampleFlags = (flags & 0x400) != 0;
        boolean hasSampleCTO = (flags & 0x800) != 0;

        if (!hasSampleDuration) {
            // No per-sample duration - use default (typically from tfhd)
            // For Media3 fMP4, default is typically fragment_duration / sample_count
            // We'll use 2 seconds as fallback which matches the muxer config
            Log.d(TAG, "    trun has no sample_duration flag, using default duration");
            return 0;
        }

        // Calculate offset to sample table
        int offset = 8; // Already read version/flags/sample_count
        if (hasDataOffset) offset += 4;
        if (hasFirstSampleFlags) offset += 4;

        // Read all sample durations
        int bytesPerSample = 0;
        if (hasSampleDuration) bytesPerSample += 4;
        if (hasSampleSize) bytesPerSample += 4;
        if (hasSampleFlags) bytesPerSample += 4;
        if (hasSampleCTO) bytesPerSample += 4;

        // Read sample data in chunks
        long totalDuration = 0;
        int durationOffsetInSample = 0; // duration is first if present

        // Limit how many samples we read at once
        int maxSamplesPerRead = 256;
        byte[] sampleData = new byte[maxSamplesPerRead * bytesPerSample];

        raf.seek(trunPosition + 8 + offset);
        long remainingSamples = sampleCount;
        
        while (remainingSamples > 0) {
            int samplesToRead = (int) Math.min(remainingSamples, maxSamplesPerRead);
            int bytesToRead = samplesToRead * bytesPerSample;
            int bytesRead = raf.read(sampleData, 0, bytesToRead);
            
            if (bytesRead < bytesToRead) {
                break;
            }

            for (int i = 0; i < samplesToRead; i++) {
                int sampleOffset = i * bytesPerSample + durationOffsetInSample;
                long sampleDuration = readUint32(sampleData, sampleOffset);
                totalDuration += sampleDuration;
            }

            remainingSamples -= samplesToRead;
        }

        Log.d(TAG, "    trun total duration: " + totalDuration + " units (" + 
              sampleCount + " samples)");
        return totalDuration;
    }

    /**
     * Converts atom type int to readable string.
     */
    private static String atomTypeToString(int type) {
        byte[] bytes = new byte[4];
        bytes[0] = (byte) ((type >> 24) & 0xFF);
        bytes[1] = (byte) ((type >> 16) & 0xFF);
        bytes[2] = (byte) ((type >> 8) & 0xFF);
        bytes[3] = (byte) (type & 0xFF);
        return new String(bytes);
    }

    // Utility methods for reading bytes

    private static long readUint32(byte[] data, int offset) {
        return ((long) (data[offset] & 0xFF) << 24) |
               ((long) (data[offset + 1] & 0xFF) << 16) |
               ((long) (data[offset + 2] & 0xFF) << 8) |
               ((long) (data[offset + 3] & 0xFF));
    }

    private static int readInt32(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24) |
               ((data[offset + 1] & 0xFF) << 16) |
               ((data[offset + 2] & 0xFF) << 8) |
               (data[offset + 3] & 0xFF);
    }

    private static long readInt64(byte[] data, int offset) {
        return ((long) (data[offset] & 0xFF) << 56) |
               ((long) (data[offset + 1] & 0xFF) << 48) |
               ((long) (data[offset + 2] & 0xFF) << 40) |
               ((long) (data[offset + 3] & 0xFF) << 32) |
               ((long) (data[offset + 4] & 0xFF) << 24) |
               ((long) (data[offset + 5] & 0xFF) << 16) |
               ((long) (data[offset + 6] & 0xFF) << 8) |
               ((long) (data[offset + 7] & 0xFF));
    }
}
