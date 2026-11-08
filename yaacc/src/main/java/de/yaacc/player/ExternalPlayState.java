/*
 *
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
 * What the app has to do when the media engine changes "play when ready" by itself: the media notification, the lock screen, a
 * headset button or a phone call pause and resume the engine without a command of the app. A change the app caused itself finds
 * the player in the state it wanted already and needs nothing.
 */
final class ExternalPlayState {
    enum Change {NONE, PAUSE, RESUME}

    private ExternalPlayState() {
    }

    /**
     * @param playWhenReady   the new value of the engine
     * @param reason          the {@code PLAY_WHEN_READY_CHANGE_REASON_*} of the engine
     * @param appSaysPlaying  whether the player of the app says it plays
     * @param appSaysPaused   whether the player of the app says it is paused
     */
    static Change decide(boolean playWhenReady, int reason, boolean appSaysPlaying, boolean appSaysPaused) {
        if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
            return Change.NONE; // the end of an item: the app moves on by itself
        }
        if (!playWhenReady) {
            return appSaysPlaying ? Change.PAUSE : Change.NONE;
        }
        return !appSaysPlaying && appSaysPaused ? Change.RESUME : Change.NONE;
    }
}
