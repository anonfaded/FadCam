package com.fadcam.playback;

import android.content.Context;
import android.net.Uri;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.MediaMetadata;

/**
 * Singleton holder for a shared ExoPlayer instance so Activity and Service control the same player.
 */
public final class PlayerHolder {
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
            player = new ExoPlayer.Builder(context.getApplicationContext()).build();
        }
        return player;
    }

    public synchronized void setMediaIfNeeded(Uri uri) {
        if (player == null || uri == null) return;
        if (currentUri == null || !uri.equals(currentUri) || player.getMediaItemCount() == 0) {
            // -------------- Fix Start for this method(setMediaIfNeeded)-----------
            String title = null;
            try { title = uri.getLastPathSegment(); } catch (Exception ignored) {}
            MediaItem item;
            if (title != null && !title.isEmpty()) {
                MediaMetadata md = new MediaMetadata.Builder().setTitle(title).build();
                item = new MediaItem.Builder().setUri(uri).setMediaMetadata(md).build();
            } else {
                item = MediaItem.fromUri(uri);
            }
            player.setMediaItem(item);
            // -------------- Fix Ended for this method(setMediaIfNeeded)-----------
            currentUri = uri;
            player.prepare();
        }
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
