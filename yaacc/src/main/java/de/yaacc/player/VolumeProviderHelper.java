package de.yaacc.player;

import androidx.media.VolumeProviderCompat;

import de.yaacc.util.YaaccLogger;

/**
 * Volume provider for remote playback (UPnP devices).
 * Handles volume control for Media Router routes.
 */
public class VolumeProviderHelper extends VolumeProviderCompat {
    private int currentVolume = 50;
    private AVTransportPlayer remotePlayer;

    public VolumeProviderHelper() {
        super(VOLUME_CONTROL_ABSOLUTE, 100, 50);
    }

    @Override
    public void onSetVolumeTo(int volume) {
        YaaccLogger.d(getClass().getName(), "Set volume to: " + volume);
        currentVolume = volume;
        setCurrentVolume(volume);
        
        if (remotePlayer != null) {
            remotePlayer.setVolume(volume);
        }
    }

    @Override
    public void onAdjustVolume(int direction) {
        int delta = direction > 0 ? 5 : -5;
        int newVolume = Math.max(0, Math.min(100, currentVolume + delta));
        YaaccLogger.d(getClass().getName(), "Adjust volume: " + delta + " -> " + newVolume);
        onSetVolumeTo(newVolume);
    }

    /**
     * Set the remote player for volume control.
     */
    public void setRemotePlayer(AVTransportPlayer player) {
        this.remotePlayer = player;
        if (player != null) {
            currentVolume = player.getVolume();
            setCurrentVolume(currentVolume);
        }
    }

    /**
     * Clear remote player (switch to local playback).
     */
    public void clearRemotePlayer() {
        this.remotePlayer = null;
    }
}
