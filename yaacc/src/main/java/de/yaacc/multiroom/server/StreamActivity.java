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
package de.yaacc.multiroom.server;

/**
 * Whether a stream is idle or playing, as snapserver shows it: a stream with a producer is playing from its first chunk with sound; after
 * the idle threshold (default 100 ms) of silent chunks in a row it is idle again, and the next chunk with sound makes it playing. A
 * stream with no producer is idle. Not thread safe.
 */
public final class StreamActivity {

    public enum Status {
        IDLE, PLAYING;

        /** The word of the JSON-RPC status. */
        public String json() {
            return this == PLAYING ? "playing" : "idle";
        }
    }

    private final int idleThresholdMillis;
    private final int chunkMillis;
    private boolean playing;
    private int silentMillis;

    public StreamActivity(int idleThresholdMillis, int chunkMillis) {
        this.idleThresholdMillis = idleThresholdMillis;
        this.chunkMillis = chunkMillis;
    }

    public Status status() {
        return playing ? Status.PLAYING : Status.IDLE;
    }

    /** A chunk was cut. */
    public void chunk(boolean silent) {
        if (!silent) {
            playing = true;
            silentMillis = 0;
            return;
        }
        silentMillis += chunkMillis;
        if (silentMillis >= idleThresholdMillis) {
            playing = false;
        }
    }

    public void producerGone() {
        playing = false;
        silentMillis = 0;
    }
}
