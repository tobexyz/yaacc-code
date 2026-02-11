package de.yaacc.player;

import android.content.Context;
import android.net.Uri;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import de.yaacc.util.YaaccLogger;

/**
 * Wrapper for ExoPlayer that handles playlist management and state updates.
 * Only handles audio/video content - images are handled separately.
 */
public class YaaccExoPlayer implements Player.Listener {
    private final ExoPlayer exoPlayer;
    private final MediaSession mediaSession;
    private final Context context;

    public YaaccExoPlayer(Context context, ExoPlayer exoPlayer, MediaSession mediaSession) {
        this.context = context;
        this.exoPlayer = exoPlayer;
        this.mediaSession = mediaSession;
        this.exoPlayer.addListener(this);
    }

    /**
     * Set playlist from PlayableItems, filtering out images.
     */
    public void setPlaylist(List<de.yaacc.player.PlayableItem> items) {
        // Filter out images - they're handled separately
        List<MediaItem> mediaItems = items.stream()
            .filter(item -> !isImage(item))
            .map(this::toMediaItem)
            .collect(Collectors.toList());
        
        if (mediaItems.isEmpty()) {
            YaaccLogger.w(getClass().getName(), "No audio/video items in playlist");
            return;
        }
        
        exoPlayer.setMediaItems(mediaItems);
        exoPlayer.prepare();
        YaaccLogger.d(getClass().getName(), "Playlist set with " + mediaItems.size() + " items");
    }

    /**
     * Add single item to playlist.
     */
    public void addItem(de.yaacc.player.PlayableItem item) {
        if (isImage(item)) {
            YaaccLogger.w(getClass().getName(), "Cannot add image to audio/video playlist");
            return;
        }
        
        MediaItem mediaItem = toMediaItem(item);
        exoPlayer.addMediaItem(mediaItem);
        YaaccLogger.d(getClass().getName(), "Added item: " + item.getTitle());
    }

    /**
     * Convert PlayableItem to MediaItem.
     */
    private MediaItem toMediaItem(de.yaacc.player.PlayableItem item) {
        MediaItem.Builder builder = new MediaItem.Builder()
            .setUri(item.getUri())
            .setMediaId(String.valueOf(item.hashCode()));
        
        // Add metadata if available
        if (item.getTitle() != null) {
            builder.setMediaMetadata(
                new androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(item.getTitle())
                    .build()
            );
        }
        
        return builder.build();
    }

    /**
     * Check if item is an image.
     */
    private boolean isImage(de.yaacc.player.PlayableItem item) {
        String mimeType = item.getMimeType();
        return mimeType != null && mimeType.startsWith("image/");
    }

    // Player.Listener callbacks

    @Override
    public void onPlaybackStateChanged(int playbackState) {
        String state = getStateName(playbackState);
        YaaccLogger.d(getClass().getName(), "Playback state changed: " + state);
        updateMediaSessionState(playbackState);
    }

    @Override
    public void onIsPlayingChanged(boolean isPlaying) {
        YaaccLogger.d(getClass().getName(), "Is playing changed: " + isPlaying);
    }

    @Override
    public void onPlayerError(PlaybackException error) {
        YaaccLogger.e(getClass().getName(), "Player error: " + error.getMessage(), error);
        // TODO: Notify UI of error
    }

    @Override
    public void onMediaItemTransition(androidx.media3.common.MediaItem mediaItem, int reason) {
        if (mediaItem != null) {
            YaaccLogger.d(getClass().getName(), "Media item transition: " + mediaItem.mediaId);
        }
    }

    /**
     * Update MediaSession playback state based on ExoPlayer state.
     */
    private void updateMediaSessionState(int playbackState) {
        // MediaSession automatically syncs with ExoPlayer
        // This is just for logging/monitoring
        YaaccLogger.d(getClass().getName(), "MediaSession state updated");
    }

    private String getStateName(int state) {
        switch (state) {
            case Player.STATE_IDLE: return "IDLE";
            case Player.STATE_BUFFERING: return "BUFFERING";
            case Player.STATE_READY: return "READY";
            case Player.STATE_ENDED: return "ENDED";
            default: return "UNKNOWN";
        }
    }

    public void release() {
        exoPlayer.removeListener(this);
        YaaccLogger.d(getClass().getName(), "Released");
    }
}
