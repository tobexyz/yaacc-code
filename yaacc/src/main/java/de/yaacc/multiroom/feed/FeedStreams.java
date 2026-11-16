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
package de.yaacc.multiroom.feed;

/**
 * What {@link SnapcastFeedController} needs from the stream handling of one server: {@link SnapcastStreams} in the
 * app, a fake in a test. All calls except {@link #noteAudio()} block (they make control calls): the controller makes
 * them on its worker only.
 */
public interface FeedStreams {

    /** Makes sure YAACC's stream exists on the server; see {@link SnapcastStreams#ensure()}. */
    SnapcastStreams.EnsureResult ensure();

    /** Lets a group play the stream and remembers what it played before; see {@link SnapcastStreams#useFor}. */
    SnapcastStreams.UseResult useFor(String groupId);

    /** Puts a group back on the stream it played before; see {@link SnapcastStreams#release}. */
    SnapcastStreams.ReleaseResult release(String groupId);

    /** Audio was written: restarts the idle timer. Cheap, any thread. */
    void noteAudio();

    /** Removes the stream if no audio was noted for the idle timeout; see {@link SnapcastStreams#removeIfIdle()}. */
    boolean removeIfIdle();

    /** Gives every group its previous stream back and removes the stream; see {@link SnapcastStreams#shutdown()}. */
    boolean shutdown();
}
