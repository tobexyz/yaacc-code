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

import de.yaacc.multiroom.control.SnapcastOperations;

/**
 * {@link SnapcastStreams} follows the control connection it was made for: a player is created before the connection of
 * its server is certain to exist (the receivers close it when no page is visible), and a connection object can be
 * replaced. This makes the stream handling when the first call needs it, and makes a new one when the control calls
 * come from another connection object. While there is none every call answers "no" (a typed result, never an
 * exception): ensure() fails, the group is not moved, nothing is removed.
 *
 * <p>The marker of the stream name comes from the same {@link SnapcastStreams.MarkerStore} for every instance, so a
 * replaced instance still finds (and adopts) its own stream. The records of the groups that the old instance moved are
 * lost with it; the group then stays on YAACC's stream until somebody moves it, which is the price of a replaced
 * connection.
 */
public final class LinkBoundStreams implements FeedStreams {

    private final FeedOperationsSource source;
    private final String phoneName;
    private final SnapcastStreams.MarkerStore markerStore;

    // guarded by this
    private SnapcastOperations boundTo;
    private SnapcastStreams bound;

    public LinkBoundStreams(FeedOperationsSource source, String phoneName, SnapcastStreams.MarkerStore markerStore) {
        if (source == null || markerStore == null) {
            throw new IllegalArgumentException("missing argument");
        }
        this.source = source;
        this.phoneName = phoneName;
        this.markerStore = markerStore;
    }

    private synchronized SnapcastStreams current() {
        SnapcastOperations operations = source.operations();
        if (operations == null) {
            return null;
        }
        if (operations != boundTo) {
            boundTo = operations;
            bound = new SnapcastStreams(operations, phoneName, markerStore);
        }
        return bound;
    }

    @Override
    public SnapcastStreams.EnsureResult ensure() {
        SnapcastStreams streams = current();
        if (streams == null) {
            return new SnapcastStreams.EnsureResult(SnapcastStreams.Status.FAILED, null, 0, false,
                    "no connection to the server", null);
        }
        return streams.ensure();
    }

    @Override
    public SnapcastStreams.UseResult useFor(String groupId) {
        SnapcastStreams streams = current();
        return streams == null ? SnapcastStreams.UseResult.FAILED : streams.useFor(groupId);
    }

    @Override
    public SnapcastStreams.ReleaseResult release(String groupId) {
        SnapcastStreams streams = current();
        return streams == null ? SnapcastStreams.ReleaseResult.FAILED : streams.release(groupId);
    }

    @Override
    public void noteAudio() {
        SnapcastStreams streams;
        synchronized (this) {
            streams = bound;
        }
        if (streams != null) {
            streams.noteAudio();
        }
    }

    @Override
    public boolean removeIfIdle() {
        SnapcastStreams streams = current();
        return streams != null && streams.removeIfIdle();
    }

    @Override
    public boolean shutdown() {
        SnapcastStreams streams = current();
        return streams != null && streams.shutdown();
    }
}
