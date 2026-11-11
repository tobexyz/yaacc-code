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
package de.yaacc.multiroom.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The time sync message: a latency as seconds and microseconds. A request carries zero; the answer carries the time
 * the request needed to reach the server (the server's receive time minus our send time, so it contains the
 * difference of the two clocks).
 */
public final class TimeMessage implements SnapcastBinaryMessage {

    private final int latencySec;
    private final int latencyUsec;

    public TimeMessage(int latencySec, int latencyUsec) {
        this.latencySec = latencySec;
        this.latencyUsec = latencyUsec;
    }

    /** The message for a number of microseconds; beyond the range of 32 bit seconds the value is cut to the range. */
    public static TimeMessage ofMicros(long micros) {
        long seconds = Math.floorDiv(micros, 1_000_000L);
        if (seconds > Integer.MAX_VALUE) {
            return new TimeMessage(Integer.MAX_VALUE, 999_999);
        }
        if (seconds < Integer.MIN_VALUE) {
            return new TimeMessage(Integer.MIN_VALUE, 0);
        }
        return new TimeMessage((int) seconds, (int) Math.floorMod(micros, 1_000_000L));
    }

    public int getLatencySec() {
        return latencySec;
    }

    public int getLatencyUsec() {
        return latencyUsec;
    }

    public long latencyMicros() {
        return latencySec * 1_000_000L + latencyUsec;
    }

    @Override
    public MessageType type() {
        return MessageType.TIME;
    }

    byte[] encodeBody() {
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(latencySec).putInt(latencyUsec).array();
    }

    static TimeMessage decode(ByteReader reader) throws SnapcastProtocolException {
        int sec = reader.i32();
        return new TimeMessage(sec, reader.i32());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TimeMessage && latencySec == ((TimeMessage) other).latencySec
                && latencyUsec == ((TimeMessage) other).latencyUsec;
    }

    @Override
    public int hashCode() {
        return latencySec * 31 + latencyUsec;
    }
}
