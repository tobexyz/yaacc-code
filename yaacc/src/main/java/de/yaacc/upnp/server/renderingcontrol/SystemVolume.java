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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package de.yaacc.upnp.server.renderingcontrol;

import android.content.Context;
import android.media.AudioManager;

import java.util.concurrent.CopyOnWriteArrayList;

import de.yaacc.util.YaaccLogger;

/**
 * The volume and the mute state of the music stream of the device: the one value the app's own volume controls
 * ({@code UpnpClient.setVolume/setMute}), the renderer's RenderingControl service ({@code SetVolume},
 * {@code SetMute}, {@code GetVolume}, {@code GetMute}) and the hardware volume keys share. The volume is
 * 0 to 100 (percent of the stream's range, rounded to the nearest step the stream has).
 * <p>
 * A change made through this class is told to the in-process listeners ({@link #addChangeListener}), so a
 * control point that is subscribed to the renderer hears about a change made in the app without waiting for
 * the system's volume broadcast. Changes made elsewhere (hardware keys, the system's panel) are not seen here;
 * {@link SystemVolumeBroadcastWatcher} catches those.
 */
public class SystemVolume {

    private static final CopyOnWriteArrayList<Runnable> CHANGE_LISTENERS = new CopyOnWriteArrayList<>();

    private final Context context;

    public SystemVolume(Context context) {
        this.context = context;
    }

    /**
     * @param listener called (on the thread that made the change) after a volume or mute change through
     *                 {@link #setPercent} or {@link #setMuted} of any instance
     */
    public static void addChangeListener(Runnable listener) {
        CHANGE_LISTENERS.addIfAbsent(listener);
    }

    public static void removeChangeListener(Runnable listener) {
        CHANGE_LISTENERS.remove(listener);
    }

    /** @return true if the music stream is muted; false when the device has no audio manager */
    public boolean isMuted() {
        AudioManager audioManager = audioManager();
        return audioManager != null && audioManager.isStreamMute(AudioManager.STREAM_MUSIC);
    }

    /** @return the volume in the range of 0-100; 0 when the device has no audio manager */
    public int getPercent() {
        AudioManager audioManager = audioManager();
        if (audioManager == null) {
            return 0;
        }
        int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if (maxVolume <= 0) {
            return 0;
        }
        return Math.round(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / maxVolume);
    }

    public void setMuted(boolean mute) {
        AudioManager audioManager = audioManager();
        if (audioManager != null) {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    mute ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, 0);
            notifyChanged();
        }
    }

    /**
     * @param desired the volume in the range of 0-100, values outside of it are cut to it
     */
    public void setPercent(int desired) {
        int percent = Math.max(0, Math.min(100, desired));
        AudioManager audioManager = audioManager();
        if (audioManager != null) {
            int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(percent * maxVolume / 100f),
                    AudioManager.FLAG_SHOW_UI);
            notifyChanged();
        }
    }

    private AudioManager audioManager() {
        return (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    private static void notifyChanged() {
        for (Runnable listener : CHANGE_LISTENERS) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                // the change is made; whoever listens failed
                YaaccLogger.w(SystemVolume.class.getName(), "Volume change listener failed", e);
            }
        }
    }
}
