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

import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.DeviceInfo;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.preference.PreferenceManager;

import org.fourthline.cling.support.model.DIDLObject;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import de.yaacc.R;
import de.yaacc.util.YaaccLogger;

/**
 * Media3 Player wrapper for AVTransportPlayer.
 * Allows using PlayerNotificationManager with remote UPnP playback.
 */
@UnstableApi
public class AVTransportPlayerWrapper implements Player {

    private final AVTransportPlayer avTransportPlayer;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private SharedPreferences preferences;
    private int state;

    public AVTransportPlayerWrapper(AVTransportPlayer avTransportPlayer, Listener listener) {
        this.avTransportPlayer = avTransportPlayer;
        if (listener != null) {
            this.listeners.add(listener);
        }
        this.preferences = PreferenceManager.getDefaultSharedPreferences(avTransportPlayer.getContext());
    }

    public void notifyPlaybackStateChanged() {
        new Handler(Looper.getMainLooper()).post(() -> {
            for (Listener listener : listeners) {
                listener.onIsPlayingChanged(avTransportPlayer.isPlaying());
                listener.onMediaItemTransition(getCurrentMediaItem(), Player.MEDIA_ITEM_TRANSITION_REASON_AUTO);
            }
        });
    }

    public void notifyVolumeChanged() {
        new Handler(Looper.getMainLooper()).post(() -> {
            for (Listener listener : listeners) {
                listener.onVolumeChanged(getVolume());
            }
        });
    }

    @Override
    public Looper getApplicationLooper() {
        return Looper.getMainLooper();
    }

    @Override
    public void addListener(Listener listener) {
        YaaccLogger.d("AVTransportPlayerWrapper", "addListener called: " + (listener != null ? listener.getClass().getSimpleName() : "null"));
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    @Override
    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    @Override
    public void setMediaItems(List<MediaItem> mediaItems) {
        // Not needed - AVTransportPlayer manages its own playlist
    }

    @Override
    public void setMediaItems(List<MediaItem> mediaItems, boolean resetPosition) {
        // Not needed
    }

    @Override
    public void setMediaItems(List<MediaItem> mediaItems, int startIndex, long startPositionMs) {
        // Not needed
    }

    @Override
    public void setMediaItem(MediaItem mediaItem) {
        // Not needed
    }

    @Override
    public void setMediaItem(MediaItem mediaItem, long startPositionMs) {
        // Not needed
    }

    @Override
    public void setMediaItem(MediaItem mediaItem, boolean resetPosition) {
        // Not needed
    }

    @Override
    public void addMediaItem(MediaItem mediaItem) {
        // Not needed
    }

    @Override
    public void addMediaItem(int index, MediaItem mediaItem) {
        // Not needed
    }

    @Override
    public void addMediaItems(List<MediaItem> mediaItems) {
        // Not needed
    }

    @Override
    public void addMediaItems(int index, List<MediaItem> mediaItems) {
        // Not needed
    }

    @Override
    public void moveMediaItem(int currentIndex, int newIndex) {
        // Not needed
    }

    @Override
    public void moveMediaItems(int fromIndex, int toIndex, int newIndex) {
        // Not needed
    }

    @Override
    public void replaceMediaItem(int index, MediaItem mediaItem) {

    }

    @Override
    public void replaceMediaItems(int fromIndex, int toIndex, List<MediaItem> mediaItems) {

    }

    @Override
    public void removeMediaItem(int index) {
        // Not needed
    }

    @Override
    public void removeMediaItems(int fromIndex, int toIndex) {
        // Not needed
    }

    @Override
    public void clearMediaItems() {
        // Not needed
    }

    @Override
    public boolean isCommandAvailable(int command) {
        return true;
    }

    @Override
    public boolean canAdvertiseSession() {
        return true;
    }

    @Override
    public Commands getAvailableCommands() {
        return new Commands.Builder().addAllCommands().build();
    }

    @Override
    public void prepare() {
        // Not needed
    }

    @Override
    public int getPlaybackState() {
        if (avTransportPlayer == null || avTransportPlayer.isProcessingCommand()) {
            YaaccLogger.d("AVTransportPlayerWrapper", "getPlaybackState: IDLE (processing)");
            return Player.STATE_IDLE;
        }

        boolean playing = avTransportPlayer.isPlaying();
        int state = playing ? Player.STATE_READY : Player.STATE_IDLE;
        YaaccLogger.d("AVTransportPlayerWrapper", "getPlaybackState: " + state + " (playing=" + playing + ")");
        return state;
    }

    @Override
    public int getPlaybackSuppressionReason() {
        return PLAYBACK_SUPPRESSION_REASON_NONE;
    }

    @Override
    public PlaybackException getPlayerError() {
        return null;
    }

    @Override
    public void play() {
        YaaccLogger.d("AVTransportPlayerWrapper", "play() called");
        avTransportPlayer.play();
        // Notify listeners that playback started
        for (Listener listener : listeners) {
            YaaccLogger.d("AVTransportPlayerWrapper", "Notifying listener: playWhenReady=true");
            listener.onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST);
            listener.onPlaybackStateChanged(Player.STATE_READY);
        }
    }

    @Override
    public void pause() {
        YaaccLogger.d("AVTransportPlayerWrapper", "pause() called");
        avTransportPlayer.pause();
        // Notify listeners that playback paused
        for (Listener listener : listeners) {
            YaaccLogger.d("AVTransportPlayerWrapper", "Notifying listener: playWhenReady=false");
            listener.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST);
        }
    }

    @Override
    public void setPlayWhenReady(boolean playWhenReady) {
        if (playWhenReady) {
            play();
        } else {
            pause();
        }
    }

    @Override
    public boolean getPlayWhenReady() {
        boolean playing = avTransportPlayer.isPlaying();
        YaaccLogger.d("AVTransportPlayerWrapper", "getPlayWhenReady: " + playing);
        return playing;
    }

    @Override
    public void setRepeatMode(int repeatMode) {
        preferences.edit().putBoolean(avTransportPlayer.getContext().getString(R.string.settings_replay_playlist_chkbx), repeatMode == REPEAT_MODE_ALL).apply();

    }

    @Override
    public int getRepeatMode() {
        if (preferences.getBoolean(avTransportPlayer.getContext().getString(R.string.settings_replay_playlist_chkbx), false)) {
            return REPEAT_MODE_ALL;
        }
        ;

        return REPEAT_MODE_OFF;
    }

    @Override
    public void setShuffleModeEnabled(boolean shuffleModeEnabled) {
        preferences.edit().putBoolean(avTransportPlayer.getContext().getString(R.string.settings_music_player_shuffle_chkbx), shuffleModeEnabled).apply();
    }

    @Override
    public boolean getShuffleModeEnabled() {
        return avTransportPlayer.isShufflePlay();
    }

    @Override
    public boolean isLoading() {
        return false;
    }

    @Override
    public void seekToDefaultPosition() {
        seekTo(0);
    }

    @Override
    public void seekToDefaultPosition(int mediaItemIndex) {
        seekTo(mediaItemIndex, 0);
    }

    @Override
    public void seekTo(long positionMs) {
        avTransportPlayer.seekTo(positionMs);
    }

    @Override
    public void seekTo(int mediaItemIndex, long positionMs) {
        avTransportPlayer.seekTo(positionMs);
    }

    @Override
    public long getSeekBackIncrement() {
        return 10000;
    }

    @Override
    public void seekBack() {
        seekTo(Math.max(0, getCurrentPosition() - getSeekBackIncrement()));
    }

    @Override
    public long getSeekForwardIncrement() {
        return 10000;
    }

    @Override
    public void seekForward() {
        seekTo(getCurrentPosition() + getSeekForwardIncrement());
    }

    @Override
    public boolean hasPrevious() {
        return avTransportPlayer.getCurrentItemIndex() > 0;
    }

    @Override
    public boolean hasPreviousWindow() {
        return false;
    }

    @Override
    public boolean hasPreviousMediaItem() {
        return avTransportPlayer.getCurrentItemIndex() > 0;
    }

    @Override
    public void previous() {
        avTransportPlayer.previous();
    }

    @Override
    public void seekToPreviousWindow() {

    }

    @Override
    public boolean hasNextMediaItem() {
        return avTransportPlayer.getItems() != null &&
                avTransportPlayer.getCurrentItemIndex() < avTransportPlayer.getItems().size() - 1;
    }

    @Override
    public void next() {
        avTransportPlayer.next();
    }

    @Override
    public void seekToNextWindow() {

    }

    @Override
    public void seekToPreviousMediaItem() {
        avTransportPlayer.previous();
    }

    @Override
    public long getMaxSeekToPreviousPosition() {
        return 0;
    }

    @Override
    public void seekToNextMediaItem() {
        avTransportPlayer.next();
    }

    @Override
    public void seekToPrevious() {
        seekToPreviousMediaItem();
    }

    @Override
    public boolean hasNext() {
        return avTransportPlayer.getCurrentItemIndex() < avTransportPlayer.getItems().size() - 1;
    }

    @Override
    public boolean hasNextWindow() {
        return false;
    }

    @Override
    public void seekToNext() {
        seekToNextMediaItem();
    }

    @Override
    public void setPlaybackParameters(PlaybackParameters playbackParameters) {
        // Not supported
    }

    @Override
    public void setPlaybackSpeed(float speed) {
        // Not supported
    }

    @Override
    public PlaybackParameters getPlaybackParameters() {
        return PlaybackParameters.DEFAULT;
    }

    @Override
    public void stop() {
        avTransportPlayer.stop();
    }

    @Override
    public void release() {
        // Handled by AVTransportPlayer
    }

    @Override
    public Timeline getCurrentTimeline() {
        return Timeline.EMPTY;
    }

    @Override
    public int getCurrentPeriodIndex() {
        return 0;
    }

    @Override
    public int getCurrentWindowIndex() {
        return 0;
    }

    @Override
    public int getCurrentMediaItemIndex() {
        return avTransportPlayer.getCurrentItemIndex();
    }

    @Override
    public int getNextWindowIndex() {
        return 0;
    }

    @Override
    public int getNextMediaItemIndex() {
        return hasNextMediaItem() ? getCurrentMediaItemIndex() + 1 : -1;
    }

    @Override
    public int getPreviousWindowIndex() {
        return 0;
    }

    @Override
    public int getPreviousMediaItemIndex() {
        return hasPreviousMediaItem() ? getCurrentMediaItemIndex() - 1 : -1;
    }

    @Override
    public MediaItem getCurrentMediaItem() {
        if (avTransportPlayer.getItems() != null &&
                avTransportPlayer.getCurrentItemIndex() >= 0 &&
                avTransportPlayer.getCurrentItemIndex() < avTransportPlayer.getItems().size()) {
            PlayableItem item = avTransportPlayer.getItems().get(avTransportPlayer.getCurrentItemIndex());
            if (item != null && item.getItem() != null) {
                // Use getAlbumArt() which includes cover.jpg fallback
                URI albumArtJavaUri = avTransportPlayer.getAlbumArt();
                MediaItem mediaItem = new MediaItem.Builder()
                        .setUri(item.getUri())
                        .setMediaMetadata(new MediaMetadata.Builder()
                                .setTitle(item.getTitle())
                                .setArtworkUri(albumArtJavaUri != null ? Uri.parse(albumArtJavaUri.toString()) : null)
                                .build())
                        .build();
                YaaccLogger.v(getClass().getName(), "getCurrentMediaItem: " + item.getTitle());
                return mediaItem;
            }
        }
        YaaccLogger.d(getClass().getName(), "getCurrentMediaItem: null");
        return null;
    }

    @Override
    public int getMediaItemCount() {
        return avTransportPlayer.getItems() != null ? avTransportPlayer.getItems().size() : 0;
    }

    @Override
    public MediaItem getMediaItemAt(int index) {
        if (avTransportPlayer.getItems() != null &&
                index >= 0 && index < avTransportPlayer.getItems().size()) {
            PlayableItem item = avTransportPlayer.getItems().get(index);
            if (item != null && item.getItem() != null) {
                DIDLObject.Property<URI> albumArtUriProperty = item.getItem().getFirstProperty(DIDLObject.Property.UPNP.ALBUM_ART_URI.class);
                URI albumArtUri = (albumArtUriProperty == null) ? null : albumArtUriProperty.getValue();
                return new MediaItem.Builder()
                        .setUri(item.getUri())
                        .setMediaMetadata(new MediaMetadata.Builder()
                                .setTitle(item.getTitle())
                                .setArtworkUri(albumArtUri != null ? Uri.parse(albumArtUri.toString()) : null)
                                .build())
                        .build();
            }
        }
        return null;
    }

    @Override
    public long getDuration() {
        // Parse duration string to milliseconds
        String duration = avTransportPlayer.getDuration();
        if (duration == null || duration.equals("00:00:00")) {
            return androidx.media3.common.C.TIME_UNSET;
        }
        // Format is "HH:MM:SS"
        try {
            String[] parts = duration.split(":");
            if (parts.length == 3) {
                long hours = Long.parseLong(parts[0]);
                long minutes = Long.parseLong(parts[1]);
                long seconds = Long.parseLong(parts[2]);
                long durationMs = (hours * 3600 + minutes * 60 + seconds) * 1000;
                android.util.Log.d("AVTransportPlayerWrapper", "Duration: " + duration + " = " + durationMs + "ms");
                return durationMs;
            }
        } catch (Exception e) {
            android.util.Log.e("AVTransportPlayerWrapper", "Failed to parse duration: " + duration, e);
        }
        return androidx.media3.common.C.TIME_UNSET;
    }

    @Override
    public long getCurrentPosition() {
        return avTransportPlayer.getCurrentPosition();
    }

    @Override
    public long getBufferedPosition() {
        return getCurrentPosition();
    }

    @Override
    public int getBufferedPercentage() {
        return 0;
    }

    @Override
    public long getTotalBufferedDuration() {
        return 0;
    }

    @Override
    public boolean isCurrentWindowDynamic() {
        return false;
    }

    @Override
    public boolean isCurrentMediaItemDynamic() {
        return false;
    }

    @Override
    public boolean isCurrentWindowLive() {
        return false;
    }

    @Override
    public boolean isCurrentMediaItemLive() {
        return false;
    }

    @Override
    public long getCurrentLiveOffset() {
        return 0;
    }

    @Override
    public boolean isCurrentWindowSeekable() {
        return false;
    }

    @Override
    public boolean isCurrentMediaItemSeekable() {
        return true;
    }

    @Override
    public boolean isPlayingAd() {
        return false;
    }

    @Override
    public int getCurrentAdGroupIndex() {
        return -1;
    }

    @Override
    public int getCurrentAdIndexInAdGroup() {
        return -1;
    }

    @Override
    public long getContentDuration() {
        return getDuration();
    }

    @Override
    public long getContentPosition() {
        return getCurrentPosition();
    }

    @Override
    public long getContentBufferedPosition() {
        return getBufferedPosition();
    }

    @Override
    public AudioAttributes getAudioAttributes() {
        return AudioAttributes.DEFAULT;
    }

    @Override
    public void setAudioAttributes(AudioAttributes audioAttributes, boolean handleAudioFocus) {
        // Not supported for remote playback
    }

    @Override
    public void setVolume(float volume) {
        avTransportPlayer.setVolume((int) (volume * 100));
    }

    @Override
    public float getVolume() {
        return avTransportPlayer.getVolume() / 100f;
    }

    @Override
    public void clearVideoSurface() {
        // Not supported
    }

    @Override
    public void clearVideoSurface(android.view.Surface surface) {
        // Not supported
    }

    @Override
    public void setVideoSurface(android.view.Surface surface) {
        // Not supported
    }

    @Override
    public void setVideoSurfaceHolder(SurfaceHolder surfaceHolder) {
        // Not supported
    }

    @Override
    public void clearVideoSurfaceHolder(SurfaceHolder surfaceHolder) {
        // Not supported
    }

    @Override
    public void setVideoSurfaceView(SurfaceView surfaceView) {
        // Not supported
    }

    @Override
    public void clearVideoSurfaceView(SurfaceView surfaceView) {
        // Not supported
    }

    @Override
    public void setVideoTextureView(TextureView textureView) {
        // Not supported
    }

    @Override
    public void clearVideoTextureView(TextureView textureView) {
        // Not supported
    }

    @Override
    public VideoSize getVideoSize() {
        return VideoSize.UNKNOWN;
    }

    @Override
    public Size getSurfaceSize() {
        return Size.UNKNOWN;
    }

    @Override
    public CueGroup getCurrentCues() {
        return CueGroup.EMPTY_TIME_ZERO;
    }

    @Override
    public DeviceInfo getDeviceInfo() {
        int currentVolume = avTransportPlayer.getVolume();
        YaaccLogger.d("AVTransportPlayerWrapper", "getDeviceInfo() - PLAYBACK_TYPE_REMOTE, volume=" + currentVolume);
        return new DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
                .setMaxVolume(100)
                .setMinVolume(0)
                .build();
    }

    @Override
    public int getDeviceVolume() {
        int volume = avTransportPlayer.getVolume();
        YaaccLogger.d("AVTransportPlayerWrapper", "getDeviceVolume() returning: " + volume);
        return volume;
    }

    @Override
    public boolean isDeviceMuted() {
        return avTransportPlayer.getMute();
    }

    @Override
    public void setDeviceVolume(int volume) {
        YaaccLogger.d("AVTransportPlayerWrapper", "setDeviceVolume(" + volume + ")");
        avTransportPlayer.setVolume(volume);
        notifyVolumeChanged();
    }

    @Override
    public void setDeviceVolume(int volume, int flags) {
        setDeviceVolume(volume);
    }

    @Override
    public void increaseDeviceVolume() {
        setDeviceVolume(Math.min(100, getDeviceVolume() + 10));
    }

    @Override
    public void increaseDeviceVolume(int flags) {
        increaseDeviceVolume();
    }

    @Override
    public void decreaseDeviceVolume() {
        setDeviceVolume(Math.max(0, getDeviceVolume() - 10));
    }

    @Override
    public void decreaseDeviceVolume(int flags) {
        decreaseDeviceVolume();
    }

    @Override
    public void setDeviceMuted(boolean muted) {
        avTransportPlayer.setMute(muted);
    }

    @Override
    public void setDeviceMuted(boolean muted, int flags) {
        setDeviceMuted(muted);
    }

    @Override
    public Tracks getCurrentTracks() {
        return Tracks.EMPTY;
    }

    @Override
    public TrackSelectionParameters getTrackSelectionParameters() {
        return TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT;
    }

    @Override
    public void setTrackSelectionParameters(TrackSelectionParameters parameters) {
        // Not supported
    }

    @Override
    public MediaMetadata getMediaMetadata() {
        MediaItem item = getCurrentMediaItem();
        return item != null ? item.mediaMetadata : MediaMetadata.EMPTY;
    }

    @Override
    public MediaMetadata getPlaylistMetadata() {
        return MediaMetadata.EMPTY;
    }

    @Override
    public void setPlaylistMetadata(MediaMetadata mediaMetadata) {
        // Not supported
    }

    @Override
    public Object getCurrentManifest() {
        return null;
    }

    @Override
    public boolean isPlaying() {
        boolean playing = avTransportPlayer.isPlaying();
        YaaccLogger.d("AVTransportPlayerWrapper", "isPlaying() called - returning " + playing);
        return playing;
    }


}
