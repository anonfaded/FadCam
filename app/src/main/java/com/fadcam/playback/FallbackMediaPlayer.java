package com.fadcam.playback;

import android.content.Context;
import android.media.MediaPlayer;
import android.net.Uri;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;

import java.io.IOException;

/**
 * A fallback media player using Android's native MediaPlayer.
 * 
 * MediaPlayer handles fragmented MP4 files correctly because it uses
 * the native media framework which scans the file structure properly.
 * 
 * This is used when ExoPlayer fails to seek in fMP4 files that lack
 * sidx (segment index) or mfra (movie fragment random access) boxes.
 */
public class FallbackMediaPlayer {
    private static final String TAG = "FallbackMediaPlayer";
    
    private MediaPlayer mediaPlayer;
    private boolean isPrepared = false;
    private boolean isReleased = false;
    private float volume = 1.0f;
    private float playbackSpeed = 1.0f;
    
    // Listeners
    private OnPreparedListener onPreparedListener;
    private OnCompletionListener onCompletionListener;
    private OnErrorListener onErrorListener;
    private OnSeekCompleteListener onSeekCompleteListener;
    
    public interface OnPreparedListener {
        void onPrepared(FallbackMediaPlayer player);
    }
    
    public interface OnCompletionListener {
        void onCompletion(FallbackMediaPlayer player);
    }
    
    public interface OnErrorListener {
        boolean onError(FallbackMediaPlayer player, int what, int extra);
    }
    
    public interface OnSeekCompleteListener {
        void onSeekComplete(FallbackMediaPlayer player);
    }
    
    public FallbackMediaPlayer() {
        mediaPlayer = new MediaPlayer();
        setupListeners();
    }
    
    private void setupListeners() {
        mediaPlayer.setOnPreparedListener(mp -> {
            isPrepared = true;
            Log.d(TAG, "MediaPlayer prepared, duration=" + mp.getDuration() + "ms");
            if (onPreparedListener != null) {
                onPreparedListener.onPrepared(this);
            }
        });
        
        mediaPlayer.setOnCompletionListener(mp -> {
            Log.d(TAG, "Playback completed");
            if (onCompletionListener != null) {
                onCompletionListener.onCompletion(this);
            }
        });
        
        mediaPlayer.setOnErrorListener((mp, what, extra) -> {
            Log.e(TAG, "MediaPlayer error: what=" + what + ", extra=" + extra);
            if (onErrorListener != null) {
                return onErrorListener.onError(this, what, extra);
            }
            return false;
        });
        
        mediaPlayer.setOnSeekCompleteListener(mp -> {
            Log.d(TAG, "Seek completed, position=" + mp.getCurrentPosition() + "ms");
            if (onSeekCompleteListener != null) {
                onSeekCompleteListener.onSeekComplete(this);
            }
        });
    }
    
    public void setDataSource(Context context, Uri uri) throws IOException {
        Log.d(TAG, "Setting data source: " + uri);
        mediaPlayer.setDataSource(context, uri);
    }
    
    public void setDataSource(String path) throws IOException {
        Log.d(TAG, "Setting data source path: " + path);
        mediaPlayer.setDataSource(path);
    }
    
    public void setSurface(Surface surface) {
        mediaPlayer.setSurface(surface);
    }
    
    public void setDisplay(SurfaceHolder holder) {
        mediaPlayer.setDisplay(holder);
    }
    
    public void prepareAsync() {
        Log.d(TAG, "Preparing async...");
        mediaPlayer.prepareAsync();
    }
    
    public void prepare() throws IOException {
        Log.d(TAG, "Preparing sync...");
        mediaPlayer.prepare();
        isPrepared = true;
    }
    
    public void start() {
        if (isPrepared && !isReleased) {
            Log.d(TAG, "Starting playback");
            mediaPlayer.start();
        }
    }
    
    public void pause() {
        if (isPrepared && !isReleased && mediaPlayer.isPlaying()) {
            Log.d(TAG, "Pausing playback");
            mediaPlayer.pause();
        }
    }
    
    public void stop() {
        if (!isReleased) {
            Log.d(TAG, "Stopping playback");
            mediaPlayer.stop();
            isPrepared = false;
        }
    }
    
    public void seekTo(long positionMs) {
        if (isPrepared && !isReleased) {
            Log.d(TAG, "Seeking to " + positionMs + "ms");
            // Use SEEK_CLOSEST for most accurate seeking (API 26+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                mediaPlayer.seekTo(positionMs, MediaPlayer.SEEK_CLOSEST);
            } else {
                mediaPlayer.seekTo((int) positionMs);
            }
        }
    }
    
    public void seekTo(int positionMs) {
        seekTo((long) positionMs);
    }
    
    public long getCurrentPosition() {
        if (isPrepared && !isReleased) {
            return mediaPlayer.getCurrentPosition();
        }
        return 0;
    }
    
    public long getDuration() {
        if (isPrepared && !isReleased) {
            return mediaPlayer.getDuration();
        }
        return 0;
    }
    
    public boolean isPlaying() {
        return isPrepared && !isReleased && mediaPlayer.isPlaying();
    }
    
    public void setVolume(float leftVolume, float rightVolume) {
        this.volume = leftVolume;
        if (!isReleased) {
            mediaPlayer.setVolume(leftVolume, rightVolume);
        }
    }
    
    public void setVolume(float volume) {
        setVolume(volume, volume);
    }
    
    public float getVolume() {
        return volume;
    }
    
    public void setPlaybackSpeed(float speed) {
        this.playbackSpeed = speed;
        if (isPrepared && !isReleased && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            try {
                boolean wasPlaying = mediaPlayer.isPlaying();
                mediaPlayer.setPlaybackParams(mediaPlayer.getPlaybackParams().setSpeed(speed));
                if (!wasPlaying) {
                    mediaPlayer.pause();
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to set playback speed", e);
            }
        }
    }
    
    public float getPlaybackSpeed() {
        return playbackSpeed;
    }
    
    public void setLooping(boolean looping) {
        if (!isReleased) {
            mediaPlayer.setLooping(looping);
        }
    }
    
    public void release() {
        Log.d(TAG, "Releasing MediaPlayer");
        isReleased = true;
        isPrepared = false;
        if (mediaPlayer != null) {
            try {
                mediaPlayer.release();
            } catch (Exception e) {
                Log.w(TAG, "Error releasing MediaPlayer", e);
            }
            mediaPlayer = null;
        }
    }
    
    public void reset() {
        if (!isReleased && mediaPlayer != null) {
            Log.d(TAG, "Resetting MediaPlayer");
            mediaPlayer.reset();
            isPrepared = false;
        }
    }
    
    public boolean isPrepared() {
        return isPrepared;
    }
    
    public boolean isReleased() {
        return isReleased;
    }
    
    // Listener setters
    public void setOnPreparedListener(OnPreparedListener listener) {
        this.onPreparedListener = listener;
    }
    
    public void setOnCompletionListener(OnCompletionListener listener) {
        this.onCompletionListener = listener;
    }
    
    public void setOnErrorListener(OnErrorListener listener) {
        this.onErrorListener = listener;
    }
    
    public void setOnSeekCompleteListener(OnSeekCompleteListener listener) {
        this.onSeekCompleteListener = listener;
    }
    
    /**
     * Gets the underlying MediaPlayer instance.
     * Use with caution - prefer the wrapper methods.
     */
    public MediaPlayer getMediaPlayer() {
        return mediaPlayer;
    }
}
