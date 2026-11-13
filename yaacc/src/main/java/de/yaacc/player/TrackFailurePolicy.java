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

/**
 * What a player does when ExoPlayer can not read a track (11.12): skip it, or stop. Pure; the player code only applies the
 * answer. A track that can not be read is skipped, the next item starts; a failing last item stops the player; the third
 * failure in a row stops it too (a dead server or a whole album of unreadable files must not turn into a loop of toasts); a
 * track that starts successfully resets the count.
 *
 * <p><b>Known limit:</b> a track that reaches {@code READY} and then fails right away resets the count each time, so with repeat
 * (all, or shuffle with repeat) a list of such tracks can cycle, with a toast for every track, until the user stops it. It
 * needs every track to start and then fail (a dead server or an unsupported container fails before {@code READY} and ends
 * after three). A cheap bound, if it is ever wanted: reset only after some playback progress, or a hard cap of skips per run.
 *
 * <p>The methods are synchronized: ExoPlayer's listener (main thread) and the command thread of the player both call them.
 */
final class TrackFailurePolicy {

    /** The failures in a row at which the player stops. */
    static final int MAX_IN_A_ROW = 3;

    enum Decision {
        /** Go on with the next item. */
        SKIP,
        /** Stop the player. */
        STOP
    }

    private int inARow;

    /**
     * A track failed.
     *
     * @param hasNext true when an item follows (with repeat the list has no end)
     */
    synchronized Decision onFailure(boolean hasNext) {
        inARow++;
        return hasNext && inARow < MAX_IN_A_ROW ? Decision.SKIP : Decision.STOP;
    }

    /** A track started to play. */
    synchronized void onTrackStarted() {
        inARow = 0;
    }

    /** A new run of the player (stop, a new queue). */
    synchronized void reset() {
        inARow = 0;
    }
}
