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
import java.util.Arrays;

/**
 * A piece of the audio stream and the server time at which it was recorded. The payload is encoded audio (pcm or
 * flac); it is kept as a private copy.
 */
public final class WireChunkMessage implements SnapcastBinaryMessage {

    /** The payload that fits into a message of the largest allowed size (8 bytes of timestamp and 4 of length). */
    public static final int MAX_PAYLOAD = SnapcastProtocolLimits.MAX_BULK_BODY - 12;

    private final int timestampSec;
    private final int timestampUsec;
    private final byte[] payload;

    public WireChunkMessage(int timestampSec, int timestampUsec, byte[] payload) {
        if (payload == null || payload.length > MAX_PAYLOAD) {
            throw new IllegalArgumentException("payload missing or larger than a message may be");
        }
        this.timestampSec = timestampSec;
        this.timestampUsec = timestampUsec;
        this.payload = payload.clone();
    }

    public int getTimestampSec() {
        return timestampSec;
    }

    public int getTimestampUsec() {
        return timestampUsec;
    }

    /** The server time of the recording in microseconds. */
    public long timestampMicros() {
        return timestampSec * 1_000_000L + timestampUsec;
    }

    /** A copy of the payload. */
    public byte[] getPayload() {
        return payload.clone();
    }

    public int getPayloadLength() {
        return payload.length;
    }

    @Override
    public MessageType type() {
        return MessageType.WIRE_CHUNK;
    }

    byte[] encodeBody() {
        return ByteBuffer.allocate(12 + payload.length).order(ByteOrder.LITTLE_ENDIAN).putInt(timestampSec)
                .putInt(timestampUsec).putInt(payload.length).put(payload).array();
    }

    static WireChunkMessage decode(ByteReader reader) throws SnapcastProtocolException {
        int sec = reader.i32();
        int usec = reader.i32();
        return new WireChunkMessage(sec, usec, reader.lengthPrefixed());
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof WireChunkMessage)) {
            return false;
        }
        WireChunkMessage that = (WireChunkMessage) other;
        return timestampSec == that.timestampSec && timestampUsec == that.timestampUsec
                && Arrays.equals(payload, that.payload);
    }

    @Override
    public int hashCode() {
        return (timestampSec * 31 + timestampUsec) * 31 + Arrays.hashCode(payload);
    }
}
