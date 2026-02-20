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
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.ui.PlayerNotificationManager;

import org.fourthline.cling.support.model.DIDLObject;

import java.net.URI;

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

    public LocalMediaSessionPlayer(UpnpClient upnpClient, String name, String shortName) {
        this(upnpClient);
        setName(name);
        setShortName(shortName);
    }

    public LocalMediaSessionPlayer(UpnpClient upnpClient) {
        super(upnpClient);
        // Don't initialize ExoPlayer here - wait until service connected
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

        // Enable repeat mode so next/previous buttons always show
        exoPlayer.setRepeatMode(androidx.media3.common.Player.REPEAT_MODE_ALL);

        exoPlayer.addListener(new Player.Listener() {
            @Override
            public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
                if (mediaItem != null && reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                    if (getCurrentItemIndex() != exoPlayer.getCurrentMediaItemIndex()) {

                        setCurrentIndex(exoPlayer.getCurrentMediaItemIndex());
                    }
                    YaaccLogger.d(getClass().getName(), "Media item changed: " + mediaItem.mediaMetadata.title);

                }
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
        notificationManager.setMediaSessionToken(mediaSession.getSessionCompatToken());

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
                startItem(item, null, pendingIndex);
                pendingItem = null;
                pendingIndex = -1;
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
                        MediaItem.Builder builder = new MediaItem.Builder()
                                .setUri(item.getUri());

                        if (item.getTitle() != null) {
                            MediaMetadata metadata = new MediaMetadata.Builder()
                                    .setTitle(item.getTitle())
                                    .build();
                            builder.setMediaMetadata(metadata);
                        }

                        exoPlayer.addMediaItem(builder.build());
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
                if (player.getMediaItemCount() != getItems().size()) {
                    setItems(getItems().toArray(new PlayableItem[0]));
                }
                player.setPlayWhenReady(true);
                player.seekTo(index, 0);
                player.prepare();
                player.play();
                setPlaying(true);
                showNotificationInternal(); // Show notification
                YaaccLogger.d(getClass().getName(), "Started playing: " + playableItem.getTitle());
            }
        });
    }

    @Override
    protected Object loadItem(PlayableItem playableItem) {
        YaaccLogger.d(getClass().getName(), "loadItem called for: " + playableItem.getTitle());
        return playableItem; // Return non-null to indicate ready
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

    /**
     * Sync ExoPlayer playlist with current items list after reordering.
     * Only updates items after current position (since those can be reordered).
     */
    public void syncPlaylistToExoPlayer() {
        if (exoPlayer == null || getItems().isEmpty()) {
            return;
        }

        new Handler(Looper.getMainLooper()).post(() -> {
            if (exoPlayer != null) {
                int currentIndex = exoPlayer.getCurrentMediaItemIndex();

                // Remove all items after current position
                int itemCount = exoPlayer.getMediaItemCount();
                for (int i = itemCount - 1; i > currentIndex; i--) {
                    exoPlayer.removeMediaItem(i);
                }

                // Add updated items from the list
                for (int i = currentIndex + 1; i < getItems().size(); i++) {
                    PlayableItem item = getItems().get(i);
                    MediaItem.Builder builder = new MediaItem.Builder()
                            .setUri(item.getUri());

                    if (item.getTitle() != null) {
                        MediaMetadata metadata = new MediaMetadata.Builder()
                                .setTitle(item.getTitle())
                                .build();
                        builder.setMediaMetadata(metadata);
                    }

                    exoPlayer.addMediaItem(builder.build());
                }

                YaaccLogger.d(getClass().getName(), "Synced playlist to ExoPlayer (optimized)");
            }
        });
    }

    @Override
    protected void stopItem(PlayableItem playableItem) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (exoPlayer != null) {
                exoPlayer.stop();
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
        return String.format("%02d:%02d:%02d", hours, minutes, secs);
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
