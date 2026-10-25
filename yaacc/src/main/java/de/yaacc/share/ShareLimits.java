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
package de.yaacc.share;

/**
 * The numbers that bound a share, in one place: how long it lives, how often the registry is
 * swept, how much is sent per read and how many responses one share serves at the same time.
 */
public final class ShareLimits {

    /** How long a share stays available after its player ended (a renderer may still buffer or seek). */
    public static final long IDLE_GRACE_MILLIS = 10L * 60L * 1000L;

    /** How long a share lives at most, whatever happens. */
    public static final long ABSOLUTE_MAX_MILLIS = 6L * 60L * 60L * 1000L;

    /** How often the registry drops expired entries without any request. */
    public static final long SWEEP_INTERVAL_MILLIS = 60L * 1000L;

    /** Bytes per read when streaming a file (the response buffer is this big). */
    public static final int CHUNK_BYTES = 64 * 1024;

    /**
     * Concurrent responses per share. A renderer opens a few connections (probe, seek, buffer);
     * more than this is not playback.
     */
    public static final int MAX_CONCURRENT_RESPONSES_PER_SHARE = 4;

    /**
     * How long a response may make no progress before the watchdog takes its slot away: a reader
     * that stalls or trickles must not hold one of the few slots of a share. (The server-wide
     * socket timeout is 5 minutes and does not fire while a peer trickles.)
     */
    public static final long WRITE_IDLE_MILLIS = 60L * 1000L;

    /**
     * Progress is counted in steps of this many bytes within {@link #WRITE_IDLE_MILLIS}; less is a
     * trickle (about 270 bytes per second), not playback.
     */
    public static final int MIN_PROGRESS_BYTES = 16 * 1024;

    private ShareLimits() {
    }
}
