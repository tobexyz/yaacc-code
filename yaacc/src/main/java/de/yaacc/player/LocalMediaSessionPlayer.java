/*
 * Copyright (C) 2026 Tobias Schoene www.yaacc.de
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */
package de.yaacc.player;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.v4.media.session.MediaSessionCompat;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.Timeline;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ShuffleOrder;
import androidx.media3.session.MediaSession;
import androidx.media3.ui.PlayerNotificationManager;

import org.fourthline.cling.support.model.DIDLObject;

import java.net.URI;
import java.util.List;
import java.util.Locale;

import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.util.YaaccLogger;
import jakarta.annotation.Nullable;

/**
 * Local music player using ExoPlayer from PlayerService.
 */
@UnstableApi
public class LocalMediaSessionPlayer extends AbstractPlayer {
    private ExoPlayer exoPlayer;
    private MediaSession mediaSession;
    private PlayerNotificationManager notificationManager;
    private PlayerService playerService;
    private URI albumArtUri;
    private PlayableItem pendingItem; // Queue item if service not ready
    private int pendingIndex;
    private volatile boolean pendingPlay;
    // The item ExoPlayer plays after the current one (it knows the shuffle order); kept for the UI threads
    private volatile int nextItemIndex = PlayOrder.NONE;
    // True while applyPlayMode sets the modes of ExoPlayer: their callbacks are our own echo (main thread only)
    private boolean applyingPlayMode;

    public LocalMediaSessionPlayer(UpnpClient upnpClient, String name, String shortName) {
        this(upnpClient);
        setName(name);
        setShortName(shortName);
    }

    public LocalMediaSessionPlayer(UpnpClient upnpClient) {
        super(upnpClient);
        // Don't initialize ExoPlayer here - wait until service connected
    }

    /**
     * Gives ExoPlayer the repeat mode of the play mode and its shuffle mode. Main thread.
     *
     * @param shuffleOrder the order the items are played in with shuffle (the index of the item at each
     *                     position, the current item first), null to leave ExoPlayer's order as it is
     */
    private void applyPlayMode(ExoPlayer player, int[] shuffleOrder) {
        PlayMode mode = getPlayMode();
        applyingPlayMode = true;
        try {
            player.setRepeatMode(RepeatPolicy.repeatMode(mode));
            player.setShuffleModeEnabled(mode.isShuffle());
            // ExoPlayer's own order is random but starts anywhere; ours starts with the current item, so the
            // list is played through once with repeat off. It has to have one entry per media item.
            if (mode.isShuffle() && shuffleOrder != null && shuffleOrder.length > 0
                    && shuffleOrder.length == player.getMediaItemCount()) {
                player.setShuffleOrder(new ShuffleOrder.DefaultShuffleOrder(shuffleOrder, System.nanoTime()));
            }
        } finally {
            applyingPlayMode = false;
        }
        refreshNextItemIndex();
    }

    /**
     * A media controller (system controls, Bluetooth, notification, Wear) changed the repeat mode of
     * ExoPlayer through the media session: the play mode of this player follows, so the buttons and the
     * renderer's CurrentPlayMode show it. Main thread (ExoPlayer's listener thread).
     */
    private void mirrorExoRepeatMode(int exoRepeatMode) {
        PlayMode adopted = RepeatPolicy.adoptExoRepeatMode(getPlayMode(), exoRepeatMode, applyingPlayMode);
        if (adopted != null) {
            YaaccLogger.d(getClass().getName(), "ExoPlayer repeat mode changed from outside: " + adopted);
            // Goes the normal way: PROPERTY_PLAY_MODE, and onPlayModeChanged applies the mode again
            setPlayMode(adopted);
        }
    }

    /**
     * Like {@link #mirrorExoRepeatMode} for the shuffle mode. ExoPlayer has just chosen a random order of
     * its own; setPlayMode creates ours (current item first) and onPlayModeChanged installs it.
     */
    private void mirrorExoShuffleMode(boolean exoShuffleModeEnabled) {
        PlayMode adopted = RepeatPolicy.adoptExoShuffleMode(getPlayMode(), exoShuffleModeEnabled, applyingPlayMode);
        if (adopted != null) {
            YaaccLogger.d(getClass().getName(), "ExoPlayer shuffle mode changed from outside: " + adopted);
            setPlayMode(adopted);
        }
    }

    private void refreshNextItemIndex() {
        ExoPlayer player = exoPlayer;
        if (player != null) {
            nextItemIndex = player.getNextMediaItemIndex();
        }
    }

    private void initializeExoPlayer() {
        if (exoPlayer != null) {
            return; // Already initialized
        }
        YaaccLogger.d(getClass().getName(), "Initializing ExoPlayer");
        // Create ExoPlayer with audio attributes
        exoPlayer = new ExoPlayer.Builder(getContext()).build();

        androidx.media3.common.AudioAttributes audioAttributes =
                new androidx.media3.common.AudioAttributes.Builder()
                        .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                        .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                        .build();
        exoPlayer.setAudioAttributes(audioAttributes, true);

        // The repeat and shuffle mode follow the play mode of this player (applied again before every start).
        // The repeat mode was always ALL so that next/previous show; that repeated a single song, or a TTS
        // sentence sent by Home Assistant, for ever although the player was set to play the list once.
        applyPlayMode(exoPlayer, null);

        exoPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_ENDED && RepeatPolicy.stopsAtEnd(getPlayMode())) {
                    // The end of the playlist without repeat: report STOPPED (the transport state of the media
                    // renderer), otherwise a controller waits for ever for the end of the song.
                    YaaccLogger.d(getClass().getName(), "End of the playlist reached, repeat is off: stopping");
                    new Handler(Looper.getMainLooper()).post(() -> LocalMediaSessionPlayer.this.stop());
                }
            }

            @Override
            public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
                ExoPlayer player = exoPlayer;
                if (mediaItem != null && player != null
                        && reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                    // Auto, repeat and seek: the end of an item moves on by itself (and in the shuffle
                    // order), not only a seek, and our index is the media item index (ExoPlayer's list
                    // is ours). A changed playlist is not a move: its first item is only ExoPlayer's pick
                    int index = player.getCurrentMediaItemIndex();
                    if (getCurrentItemIndex() != index) {
                        setCurrentIndex(index);
                        List<PlayableItem> items = getItems();
                        if (index >= 0 && index < items.size()) {
                            firePropertyChange(PROPERTY_ITEM, null, items.get(index));
                        }
                    }
                    YaaccLogger.d(getClass().getName(), "Media item changed: " + mediaItem.mediaMetadata.title);
                }
                refreshNextItemIndex();
            }

            @Override
            public void onShuffleModeEnabledChanged(boolean shuffleModeEnabled) {
                mirrorExoShuffleMode(shuffleModeEnabled);
                refreshNextItemIndex();
            }

            @Override
            public void onRepeatModeChanged(int repeatMode) {
                mirrorExoRepeatMode(repeatMode);
                refreshNextItemIndex();
            }

            @Override
            public void onTimelineChanged(Timeline timeline, int reason) {
                refreshNextItemIndex();
            }
        });

        // Create MediaSession with session activity for notification
        PendingIntent sessionActivity = PendingIntent.getActivity(
                getContext(),
                0,
                new Intent(getContext(), MusicPlayerActivity.class)
                        .putExtra(PLAYER_ID, getId()),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        mediaSession = new MediaSession.Builder(getContext(), exoPlayer)
                .setId("local_audio_" + getId() + "_" + System.currentTimeMillis())
                .setSessionActivity(sessionActivity)
                .build();

        // Create Media3 notification manager
        notificationManager = new PlayerNotificationManager.Builder(
                getContext(),
                getNotificationId(),
                Yaacc.NOTIFICATION_CHANNEL_ID)
                .setMediaDescriptionAdapter(new PlayerNotificationManager.MediaDescriptionAdapter() {
                    @Override
                    public CharSequence getCurrentContentTitle(Player player) {
                        return getCurrentItemTitle();
                    }

                    @Override
                    public PendingIntent createCurrentContentIntent(Player player) {
                        return getNotificationIntent();
                    }

                    @Override
                    public CharSequence getCurrentContentText(Player player) {
                        return getName();
                    }

                    @Override
                    public android.graphics.Bitmap getCurrentLargeIcon(Player player,
                                                                       PlayerNotificationManager.BitmapCallback callback) {
                        return null;
                    }
                })
                .build();

        // Enable next/previous actions
        notificationManager.setUseNextAction(true);
        notificationManager.setUsePreviousAction(true);
        notificationManager.setUseNextActionInCompactView(true);
        notificationManager.setUsePreviousActionInCompactView(true);

        notificationManager.setPlayer(exoPlayer);
        notificationManager.setMediaSessionToken(mediaSession.getPlatformToken());

        YaaccLogger.d(getClass().getName(), "ExoPlayer, MediaSession and notification initialized");
    }

    /**
     * Get the Media3 MediaSession (not the legacy MediaSessionCompat).
     * This is registered with PlayerService for system integration.
     */
    public MediaSession getMedia3Session() {
        return mediaSession;
    }

    @Override
    public void onServiceConnected(ComponentName className, IBinder binder) {
        super.onServiceConnected(className, binder);

        if (binder instanceof PlayerService.PlayerServiceBinder) {
            playerService = ((PlayerService.PlayerServiceBinder) binder).getService();

            // Initialize ExoPlayer now that we have proper ID
            initializeExoPlayer();

            // Register our MediaSession with the service
            playerService.registerMediaSession(mediaSession);
            YaaccLogger.d(getClass().getName(), "MediaSession registered with PlayerService");

            // If there's a pending item, play it now
            if (pendingItem != null) {
                YaaccLogger.d(getClass().getName(), "Playing pending item: " + pendingItem.getTitle());
                PlayableItem item = pendingItem;
                int idx = pendingIndex;
                pendingItem = null;
                pendingIndex = -1;
                pendingPlay = false;
                startItem(item, null, idx);
            } else if (pendingPlay) {
                YaaccLogger.d(getClass().getName(), "Pending play flag set, calling play()");
                pendingPlay = false;
                play();
            }
        }
    }

    @Override
    public int getIconResourceId() {
        return R.drawable.ic_baseline_library_music_32;
    }

    @Override
    public void setItems(PlayableItem... playableItems) {
        super.setItems(playableItems);
        if (exoPlayer == null) {
            new Handler(Looper.getMainLooper()).post(this::initializeExoPlayer);
        }
        // Add all items to ExoPlayer playlist
        if (exoPlayer != null && playableItems.length > 0) {
            new Handler(Looper.getMainLooper()).post(() -> {
                if (exoPlayer != null) {
                    exoPlayer.clearMediaItems();
                    for (PlayableItem item : playableItems) {
                        exoPlayer.addMediaItem(toMediaItem(item));
                    }
                    if (exoPlayer != null) {
                        exoPlayer.prepare();
                    }
                    YaaccLogger.d(getClass().getName(), "Added " + playableItems.length + " items to ExoPlayer");
                }
            });
        }
    }

    @Override
    protected void startItem(PlayableItem playableItem, Object loadedItem, int index) {
        YaaccLogger.d(getClass().getName(), "startItem called for: " + playableItem.getTitle());

        if (exoPlayer == null) {
            YaaccLogger.w(getClass().getName(), "ExoPlayer not ready, queuing item");
            pendingItem = playableItem;
            pendingIndex = index;
            pendingPlay = true;
            return;
        }
        
        DIDLObject.Property<URI> albumArtUriProperty = playableItem.getItem() == null ? null :
                playableItem.getItem().getFirstProperty(DIDLObject.Property.UPNP.ALBUM_ART_URI.class);
        albumArtUri = (albumArtUriProperty == null) ? null : albumArtUriProperty.getValue();

        // Notify listeners that track changed (for UI updates)
        firePropertyChange(PROPERTY_ITEM, null, playableItem);

        // ExoPlayer must be called from main thread - move ALL ExoPlayer access inside Handler
        new Handler(Looper.getMainLooper()).post(() -> {
            ExoPlayer player = exoPlayer; // Store local reference to avoid race condition
            if (player != null) {
                boolean needsSetItems = player.getMediaItemCount() != getItems().size();
                if (needsSetItems) {
                    // Clear and rebuild playlist
                    player.clearMediaItems();
                    for (PlayableItem item : getItems()) {
                        player.addMediaItem(toMediaItem(item));
                    }
                    player.prepare();
                } else if (player.getPlaybackState() == Player.STATE_IDLE) {
                    // Player is IDLE after stop() - need to prepare again
                    // This happens when play() is called after stop()
                    YaaccLogger.d(getClass().getName(), "Player in IDLE state, re-preparing before play");
                    player.prepare();
                }
                // Now seek and play: the play order starts with the item that starts
                resetPlayOrder(index);
                applyPlayMode(player, getPlayOrder());
                player.seekTo(index, 0);
                player.play();
                setPlaying(true);
                showNotificationInternal();
                YaaccLogger.d(getClass().getName(), "Started playing: " + playableItem.getTitle() + " at index " + index);
            }
        });
    }

    @Override
    protected Object loadItem(PlayableItem playableItem) {
        YaaccLogger.d(getClass().getName(), "loadItem called for: " + playableItem.getTitle());
        return playableItem; // Return non-null to indicate ready
    }
    
    @Override
    protected void doPostLoadItem(PlayableItem playableItem) {
        // ExoPlayer handles track changes automatically, don't start timer
        YaaccLogger.d(getClass().getName(), "doPostLoadItem - ExoPlayer handles track changes");
    }

    /** The media item of ExoPlayer for a playable item: uri, title and album art. */
    private MediaItem toMediaItem(PlayableItem item) {
        MediaItem.Builder builder = new MediaItem.Builder().setUri(item.getUri());
        if (item.getTitle() != null) {
            MediaMetadata.Builder metadataBuilder = new MediaMetadata.Builder().setTitle(item.getTitle());
            // Add album art if available
            DIDLObject.Property<URI> albumArtProp = item.getItem() == null ? null :
                    item.getItem().getFirstProperty(DIDLObject.Property.UPNP.ALBUM_ART_URI.class);
            if (albumArtProp != null && albumArtProp.getValue() != null) {
                metadataBuilder.setArtworkUri(android.net.Uri.parse(albumArtProp.getValue().toString()));
            }
            builder.setMediaMetadata(metadataBuilder.build());
        }
        return builder.build();
    }

    /**
     * Items added while ExoPlayer has the playlist (add to playlist during playback) are appended to it at once;
     * before, they only reached ExoPlayer with the next start of an item, so it never played them. With shuffle
     * our play order, which places the new items at random behind the current one, is installed again.
     */
    @Override
    public void addItems(List<PlayableItem> playableItems) {
        int sizeBefore = getItems().size();
        super.addItems(playableItems);
        if (playableItems == null || playableItems.isEmpty()) {
            return;
        }
        List<PlayableItem> added = new java.util.ArrayList<>(playableItems);
        int[] order = getPlayMode().isShuffle() ? getPlayOrder() : null;
        new Handler(Looper.getMainLooper()).post(() -> {
            ExoPlayer player = exoPlayer;
            if (player != null && ExoPlaylistSync.canAppend(player.getMediaItemCount(), sizeBefore)) {
                for (PlayableItem item : added) {
                    player.addMediaItem(toMediaItem(item));
                }
                applyPlayMode(player, order);
                YaaccLogger.d(getClass().getName(), "Appended " + added.size() + " items to ExoPlayer");
            }
        });
    }

    @Override
    protected void onPlayModeChanged(PlayMode oldMode, PlayMode newMode) {
        // The order is only handed over when shuffle was switched: it starts with the current item then
        int[] order = oldMode.isShuffle() != newMode.isShuffle() ? getPlayOrder() : null;
        new Handler(Looper.getMainLooper()).post(() -> {
            ExoPlayer player = exoPlayer;
            if (player != null) {
                applyPlayMode(player, order);
            }
        });
    }

    @Override
    public String getNextItemTitle() {
        if (exoPlayer == null) {
            return super.getNextItemTitle();
        }
        int next = nextItemIndex;
        List<PlayableItem> items = getItems();
        return next >= 0 && next < items.size() ? items.get(next).getTitle() : "";
    }

    @Override
    public void next() {
        // Let ExoPlayer handle next track
        if (exoPlayer != null) {
            new Handler(Looper.getMainLooper()).post(() -> {
                if (exoPlayer != null && exoPlayer.hasNextMediaItem()) {
                    exoPlayer.seekToNextMediaItem();
                    //done by listener setCurrentIndex(getCurrentItemIndex() + 1);
                }
            });
        }
    }


    @Override
    public void previous() {
        // Let ExoPlayer handle previous track
        if (exoPlayer != null) {
            new Handler(Looper.getMainLooper()).post(() -> {
                if (exoPlayer != null && exoPlayer.hasPreviousMediaItem()) {
                    exoPlayer.seekToPreviousMediaItem();
                    //done by listener setCurrentIndex(getCurrentItemIndex() - 1);
                }
            });
        }
    }

    // The playlist screen edits the list; ExoPlayer holds the same list (media item index = list index), and
    // its position of an edited item may be before the current one when shuffle is on, so the media item
    // itself is moved or removed instead of rebuilding the part behind the current item.

    @Override
    protected void onItemMoved(int fromIndex, int toIndex) {
        new Handler(Looper.getMainLooper()).post(() -> {
            ExoPlayer player = exoPlayer;
            if (player != null && player.getMediaItemCount() == getItems().size()
                    && fromIndex < player.getMediaItemCount() && toIndex < player.getMediaItemCount()) {
                player.moveMediaItem(fromIndex, toIndex);
                refreshNextItemIndex();
            }
        });
    }

    @Override
    protected void onItemRemoved(int index) {
        int[] order = getPlayMode().isShuffle() ? getPlayOrder() : null;
        new Handler(Looper.getMainLooper()).post(() -> {
            ExoPlayer player = exoPlayer;
            if (player != null && index < player.getMediaItemCount()
                    && player.getMediaItemCount() == getItems().size() + 1) {
                player.removeMediaItem(index);
                // ExoPlayer keeps the relative order of the rest; ours is installed to be sure
                applyPlayMode(player, order);
            }
        });
    }

    @Override
    protected void onPlayOrderMoved() {
        int[] order = getPlayOrder();
        new Handler(Looper.getMainLooper()).post(() -> {
            ExoPlayer player = exoPlayer;
            if (player != null) {
                applyPlayMode(player, order);
            }
        });
    }

    @Override
    protected void stopItem(PlayableItem playableItem) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (exoPlayer != null) {
                exoPlayer.stop();
                // Note: ExoPlayer remains in STOPPED state but retains media items and position
                // Ready for next play() call (no need to re-prepare unless playlist changed)
            }
            setPlaying(false);
        });
    }

    @Override
    protected void doPause() {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (exoPlayer != null) {
                exoPlayer.pause();
            }
            setPlaying(false);
        });
    }

    @Override
    protected void doResume() {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (exoPlayer != null) {
                exoPlayer.play();
            }
            setPlaying(true);
        });
    }

    @Override
    public long getCurrentPosition() {
        if (exoPlayer != null) {
            return exoPlayer.getCurrentPosition();
        }
        return 0;
    }

    @Override
    public void seekTo(long millisecondsFromStart) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (exoPlayer != null) {
                exoPlayer.seekTo(millisecondsFromStart);
            }
        });
    }

    @Override
    public String getDuration() {
        if (exoPlayer != null) {
            long duration = exoPlayer.getDuration();
            if (duration > 0) {
                return formatTime(duration);
            }
        }
        return "00:00:00";
    }

    @Override
    public String getElapsedTime() {
        if (exoPlayer != null) {
            return formatTime(exoPlayer.getCurrentPosition());
        }
        return "00:00:00";
    }

    private String formatTime(long millis) {
        long seconds = millis / 1000;
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, secs);
    }

    @Override
    public URI getAlbumArt() {
        return albumArtUri;
    }

    @Override
    protected int getNotificationId() {
        return de.yaacc.util.NotificationId.LOCAL_BACKGROUND_MUSIC_PLAYER.getId();
    }

    @Override
    public PendingIntent getNotificationIntent() {
        android.content.Intent intent = new android.content.Intent(getContext(), MusicPlayerActivity.class);
        intent.putExtra(PLAYER_ID, getId());
        return PendingIntent.getActivity(getContext(), 0, intent,
                PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    public MediaSessionCompat getMediaSession() {
        // Return null - we use Media3 MediaSession, not legacy MediaSessionCompat
        // This prevents AbstractPlayer from trying to use MediaSessionCompat in notification
        return null;
    }

    @Override
    protected void showNotificationInternal() {
        // PlayerNotificationManager handles notification automatically
        // No manual notification needed
        YaaccLogger.d(getClass().getName(), "Notification handled by PlayerNotificationManager");
    }

    @Override
    public void onDestroy() {
        YaaccLogger.d(getClass().getName(), "onDestroy called");

        // Release notification manager on main thread
        if (notificationManager != null) {
            new Handler(Looper.getMainLooper()).post(() -> {
                if (notificationManager != null) {
                    notificationManager.setPlayer(null);
                }
            });
        }

        // Unregister MediaSession from service
        if (playerService != null && mediaSession != null) {
            playerService.unregisterMediaSession(mediaSession);
        }

        // Stop and release ExoPlayer (capture references before nulling)
        final ExoPlayer playerToRelease = exoPlayer;
        final MediaSession sessionToRelease = mediaSession;

        if (playerToRelease != null) {
            new Handler(Looper.getMainLooper()).post(() -> {
                playerToRelease.stop();
                playerToRelease.clearMediaItems();
                playerToRelease.release();
                YaaccLogger.d(getClass().getName(), "ExoPlayer stopped and released");
            });
        }

        // Release MediaSession
        if (sessionToRelease != null) {
            sessionToRelease.release();
            YaaccLogger.d(getClass().getName(), "MediaSession released");
        }

        exoPlayer = null;
        mediaSession = null;
        notificationManager = null;
        super.onDestroy();
    }
}
