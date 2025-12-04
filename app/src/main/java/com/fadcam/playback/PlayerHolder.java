package com.fadcam.playback;

import android.content.Context;
import android.media.AudioManager;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;

/**
 * Singleton holder for a shared ExoPlayer instance so Activity and Service control the same player.
 * Uses Media3 ExoPlayer for better fragmented MP4 audio support.
 */
public final class PlayerHolder {
    private static final String TAG = "PlayerHolder";
    private static PlayerHolder instance;
    private ExoPlayer player;
    private Uri currentUri;

    private PlayerHolder() {}

    public static synchronized PlayerHolder getInstance() {
        if (instance == null) instance = new PlayerHolder();
        return instance;
    }

    public synchronized ExoPlayer getOrCreate(Context context) {
        if (player == null) {
            Log.d(TAG, "Creating new ExoPlayer instance - SIMPLEST CONFIG");
            
            // Create with absolute minimal configuration
            // Let ExoPlayer handle everything with defaults
            player = new ExoPlayer.Builder(context.getApplicationContext()).build();
            
            // Log audio manager state
            try {
                AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                int musicVol = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                int musicMax = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                int mode = am.getMode();
                boolean speakerOn = am.isSpeakerphoneOn();
                boolean musicActive = am.isMusicActive();
                Log.i(TAG, "AudioManager state: MUSIC=" + musicVol + "/" + musicMax + 
                          ", mode=" + mode + ", speakerOn=" + speakerOn + ", musicActive=" + musicActive);
            } catch (Exception e) {
                Log.w(TAG, "Could not log AudioManager state", e);
            }
            
            Log.d(TAG, "ExoPlayer created with DEFAULTS ONLY - no custom renderers/attributes");
            
            // Add comprehensive analytics listener for debugging audio issues
            player.addAnalyticsListener(new AnalyticsListener() {
                @Override
                public void onAudioSessionIdChanged(@NonNull EventTime eventTime, int audioSessionId) {
                    Log.i(TAG, "Audio session ID changed to: " + audioSessionId);
                }
                
                @Override
                public void onVolumeChanged(@NonNull EventTime eventTime, float volume) {
                    Log.i(TAG, "Player volume changed to: " + volume);
                }
                
                @Override
                public void onAudioSinkError(@NonNull EventTime eventTime, @NonNull Exception audioSinkError) {
                    Log.e(TAG, "AudioSink ERROR: " + audioSinkError.getMessage(), audioSinkError);
                }
                
                @Override
                public void onAudioCodecError(@NonNull EventTime eventTime, @NonNull Exception audioCodecError) {
                    Log.e(TAG, "AudioCodec ERROR: " + audioCodecError.getMessage(), audioCodecError);
                }
                
                @Override
                public void onAudioUnderrun(@NonNull EventTime eventTime, int bufferSize, long bufferSizeMs, long elapsedSinceLastFeedMs) {
                    Log.w(TAG, "Audio UNDERRUN: bufferSize=" + bufferSize + ", bufferSizeMs=" + bufferSizeMs);
                }
                
                @Override
                public void onPlayerError(@NonNull EventTime eventTime, @NonNull PlaybackException error) {
                    Log.e(TAG, "Player ERROR: " + error.getMessage(), error);
                }
                
                @Override
                public void onAudioEnabled(@NonNull EventTime eventTime, @NonNull DecoderCounters decoderCounters) {
                    Log.i(TAG, "AUDIO ENABLED - decoder initialized");
                }
                
                @Override
                public void onAudioDisabled(@NonNull EventTime eventTime, @NonNull DecoderCounters decoderCounters) {
                    Log.w(TAG, "AUDIO DISABLED - decoder shut down. decodedBufferCount=" + decoderCounters.renderedOutputBufferCount);
                }
                
                @Override
                public void onAudioDecoderInitialized(@NonNull EventTime eventTime, @NonNull String decoderName, long initializedTimestampMs, long initializationDurationMs) {
                    Log.i(TAG, "Audio decoder INITIALIZED: " + decoderName + ", initTime=" + initializationDurationMs + "ms");
                }
                
                @Override
                public void onAudioInputFormatChanged(@NonNull EventTime eventTime, @NonNull Format format, @androidx.annotation.Nullable DecoderReuseEvaluation decoderReuseEvaluation) {
                    Log.i(TAG, "Audio input format: mime=" + format.sampleMimeType + 
                               ", channels=" + format.channelCount + 
                               ", sampleRate=" + format.sampleRate +
                               ", bitrate=" + format.bitrate);
                }
                
                @Override
                public void onAudioPositionAdvancing(@NonNull EventTime eventTime, long playoutStartSystemTimeMs) {
                    Log.d(TAG, "Audio position ADVANCING - playback started at system time: " + playoutStartSystemTimeMs);
                }
                
                @Override 
                public void onAudioTrackInitialized(@NonNull EventTime eventTime, @NonNull androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig audioTrackConfig) {
                    Log.i(TAG, "AudioTrack INITIALIZED: encoding=" + audioTrackConfig.encoding + 
                               ", channelConfig=" + audioTrackConfig.channelConfig +
                               ", sampleRate=" + audioTrackConfig.sampleRate +
                               ", bufferSize=" + audioTrackConfig.bufferSize);
                }
                
                @Override
                public void onAudioTrackReleased(@NonNull EventTime eventTime, @NonNull androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig audioTrackConfig) {
                    Log.w(TAG, "AudioTrack RELEASED");
                }
                
                @Override
                public void onDroppedVideoFrames(@NonNull EventTime eventTime, int droppedFrames, long elapsedMs) {
                    Log.w(TAG, "Dropped " + droppedFrames + " video frames in " + elapsedMs + "ms");
                }
                
                @Override
                public void onRenderedFirstFrame(@NonNull EventTime eventTime, @NonNull Object output, long renderTimeMs) {
                    Log.i(TAG, "First VIDEO frame rendered at " + renderTimeMs + "ms");
                }
                
                @Override
                public void onPlaybackStateChanged(@NonNull EventTime eventTime, int state) {
                    String stateName;
                    switch (state) {
                        case Player.STATE_IDLE: stateName = "IDLE"; break;
                        case Player.STATE_BUFFERING: stateName = "BUFFERING"; break;
                        case Player.STATE_READY: stateName = "READY"; break;
                        case Player.STATE_ENDED: stateName = "ENDED"; break;
                        default: stateName = "UNKNOWN(" + state + ")"; break;
                    }
                    Log.i(TAG, "Playback state changed to: " + stateName);
                }
                
                @Override
                public void onIsPlayingChanged(@NonNull EventTime eventTime, boolean isPlaying) {
                    Log.i(TAG, "isPlaying changed to: " + isPlaying);
                }
            });
            
            Log.d(TAG, "ExoPlayer created with DEFAULT configuration - NO custom track selection");
        }
        return player;
    }

    public synchronized void setMediaIfNeeded(Uri uri) {
        if (player == null || uri == null) {
            Log.w(TAG, "setMediaIfNeeded called with null player or uri. player=" + player + ", uri=" + uri);
            return;
        }
        
        // Log current player state before changes
        Log.d(TAG, "=== setMediaIfNeeded START ===");
        Log.d(TAG, "URI: " + uri);
        Log.d(TAG, "Current player state: playWhenReady=" + player.getPlayWhenReady() + 
                   ", playbackState=" + player.getPlaybackState() +
                   ", isPlaying=" + player.isPlaying());
        
        // Always ensure volume is set correctly
        float currentVolume = player.getVolume();
        Log.d(TAG, "Current volume: " + currentVolume);
        if (currentVolume < 1.0f) {
            player.setVolume(1.0f);
            Log.d(TAG, "Volume was low, reset to 1.0");
        }
        
        // Log audio session ID
        int audioSessionId = player.getAudioSessionId();
        Log.d(TAG, "Audio session ID: " + audioSessionId);
        
        // Always stop and set media fresh to ensure proper audio renderer initialization
        // This fixes audio not playing issue that occurs when reusing player state
        try {
            player.stop();
            Log.d(TAG, "Player stopped, setting fresh media");
        } catch (Exception e) {
            Log.w(TAG, "Error stopping player", e);
        }
        
        // Build MediaItem with metadata
        String title = null;
        try { 
            title = uri.getLastPathSegment(); 
        } catch (Exception ignored) {}
        
        MediaItem item;
        if (title != null && !title.isEmpty()) {
            MediaMetadata md = new MediaMetadata.Builder().setTitle(title).build();
            item = new MediaItem.Builder().setUri(uri).setMediaMetadata(md).build();
        } else {
            item = MediaItem.fromUri(uri);
        }
        
        Log.d(TAG, "Setting media item: " + item.mediaId);
        player.setMediaItem(item);
        currentUri = uri;
        
        Log.d(TAG, "Calling player.prepare()");
        player.prepare();
        
        // Ensure volume is at max after prepare
        player.setVolume(1.0f);
        Log.d(TAG, "Media set and prepared, volume: " + player.getVolume());
        Log.d(TAG, "Audio session ID after prepare: " + player.getAudioSessionId());
        Log.d(TAG, "=== setMediaIfNeeded END ===");
    }

    public synchronized Uri getCurrentUri() { return currentUri; }

    public synchronized void release() {
        if (player != null) {
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
        currentUri = null;
    }
}
