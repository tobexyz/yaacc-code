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
package de.yaacc.upnp.server.openhome;

import de.yaacc.openhome.OhQueue;

/**
 * What the OpenHome services ask of the player of the renderer. The renderer core calls these methods on the thread of the
 * control point's request; an implementation hands the work to the player's own thread.
 */
public interface OhPlayback {
    // Called on the threads of the control points' requests: an implementation hands the work over and returns (see OpenHomeRenderer).

    void play();

    void pause();

    void stop();

    void next();

    void previous();

    void setRepeat(boolean repeat);

    void setShuffle(boolean shuffle);

    void seekSecondAbsolute(long second);

    void seekSecondRelative(int seconds);

    /** Plays the track with this id (it is in the queue). */
    void seekToTrack(long id);

    /** A track was put into the queue at {@code index}. */
    void trackInserted(int index, OhQueue.Entry entry);

    /** The track with the id was taken out of the queue; it was at {@code index}. */
    void trackDeleted(int index, long id);

    void queueCleared();

    /** @param percent 0 to 100 */
    void setVolume(int percent);

    void setMute(boolean mute);
}
