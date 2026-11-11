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
 * An open feed to the stream port of a server: the {@link PcmFeeder} in the app ({@link PcmFeederOutput}), a fake in a
 * test.
 */
public interface FeedOutput extends PcmSink {

    /** False once the feed failed or was closed. */
    boolean isRunning();

    /** Bytes that wait to be sent. */
    int getQueuedBytes();

    /**
     * Counters for the log of a device test (chunks, silence, late chunks, re-anchors, dropped bytes): numbers only,
     * never an address or a name.
     */
    default String statistics() {
        return "";
    }

    /** Stops the feed and closes the connection. May block for seconds: not on the main thread. */
    void close();
}
