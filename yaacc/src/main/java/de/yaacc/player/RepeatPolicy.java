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
package de.yaacc.player;

import androidx.media3.common.Player;

/**
 * What the repeat mode of a {@link PlayMode} means for ExoPlayer, the engine of the local music player and
 * the model of the media session of the AVTransport player. Plain JVM.
 */
final class RepeatPolicy {

    private RepeatPolicy() {
    }

    /** @return the ExoPlayer repeat mode for the play mode: off, all or one */
    static int repeatMode(PlayMode mode) {
        return repeatMode(mode.getRepeat());
    }

    static int repeatMode(PlayMode.RepeatMode repeat) {
        switch (repeat) {
            case ALL:
                return Player.REPEAT_MODE_ALL;
            case ONE:
                return Player.REPEAT_MODE_ONE;
            default:
                return Player.REPEAT_MODE_OFF;
        }
    }

    /** @return the repeat mode of an ExoPlayer repeat mode constant (a value out of range is off) */
    static PlayMode.RepeatMode fromExoRepeatMode(int exoRepeatMode) {
        switch (exoRepeatMode) {
            case Player.REPEAT_MODE_ALL:
                return PlayMode.RepeatMode.ALL;
            case Player.REPEAT_MODE_ONE:
                return PlayMode.RepeatMode.ONE;
            default:
                return PlayMode.RepeatMode.OFF;
        }
    }

    /**
     * Decides what a change of the ExoPlayer repeat mode means for the play mode. A media controller
     * (system controls, Bluetooth, notification, Wear) can change the repeat mode of the engine directly;
     * the play mode follows so that the buttons and the renderer's {@code CurrentPlayMode} do not go stale.
     * Only the repeat part is taken over, the shuffle part of the mode is not touched.
     *
     * @param current       the play mode of the player now
     * @param exoRepeatMode the repeat mode ExoPlayer reports
     * @param ownChange     true while the player itself applies its play mode to ExoPlayer: the callback is
     *                      the echo of our own call and must not change anything
     * @return the play mode to take over; null to keep the current one (own change, or no difference)
     */
    static PlayMode adoptExoRepeatMode(PlayMode current, int exoRepeatMode, boolean ownChange) {
        if (ownChange) {
            return null;
        }
        PlayMode.RepeatMode repeat = fromExoRepeatMode(exoRepeatMode);
        return repeat == current.getRepeat() ? null : current.withRepeat(repeat);
    }

    /**
     * Like {@link #adoptExoRepeatMode} for the shuffle mode of ExoPlayer; the repeat part of the mode is
     * not touched.
     *
     * @return the play mode to take over; null to keep the current one (own change, or no difference)
     */
    static PlayMode adoptExoShuffleMode(PlayMode current, boolean exoShuffleModeEnabled, boolean ownChange) {
        if (ownChange || exoShuffleModeEnabled == current.isShuffle()) {
            return null;
        }
        return current.withShuffle(exoShuffleModeEnabled);
    }

    /**
     * @return true if the player has to stop (and report STOPPED) when the playlist has ended: only with
     * repeat off (repeat all and repeat one never end)
     */
    static boolean stopsAtEnd(PlayMode mode) {
        return mode.getRepeat() == PlayMode.RepeatMode.OFF;
    }
}
