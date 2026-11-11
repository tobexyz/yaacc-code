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

/** The base header (26 bytes, little endian) in front of every message. Immutable. */
public final class MessageHeader {

    private final int type;
    private final int id;
    private final int refersTo;
    private final int sentSec;
    private final int sentUsec;
    private final int receivedSec;
    private final int receivedUsec;
    private final int size;

    /**
     * @param type     the message type (0 to 65535)
     * @param id       the id of the message (0 to 65535)
     * @param refersTo the id of the request this answers (0 to 65535)
     * @param size     the size of the typed message that follows (0 or more)
     */
    public MessageHeader(int type, int id, int refersTo, int sentSec, int sentUsec, int receivedSec, int receivedUsec,
                         int size) {
        if (!isU16(type) || !isU16(id) || !isU16(refersTo) || size < 0) {
            throw new IllegalArgumentException("header field out of range");
        }
        this.type = type;
        this.id = id;
        this.refersTo = refersTo;
        this.sentSec = sentSec;
        this.sentUsec = sentUsec;
        this.receivedSec = receivedSec;
        this.receivedUsec = receivedUsec;
        this.size = size;
    }

    private static boolean isU16(int value) {
        return value >= 0 && value <= 0xffff;
    }

    /**
     * Reads the 26 bytes at the start of {@code buffer}. The size is checked here against {@code Integer.MAX_VALUE}
     * and against the cap for the type, before the caller reserves a byte for the body.
     *
     * @throws SnapcastProtocolException TRUNCATED, NEGATIVE_SIZE or TOO_LARGE
     */
    static MessageHeader parse(byte[] buffer) throws SnapcastProtocolException {
        return parse(buffer, SnapcastProtocolLimits.MAX_BULK_BODY);
    }

    /** As above with a lower cap for the body of a WireChunk. */
    static MessageHeader parse(byte[] buffer, int maxChunkBody) throws SnapcastProtocolException {
        if (buffer == null || buffer.length < SnapcastProtocolLimits.HEADER_SIZE) {
            throw new SnapcastProtocolException(SnapcastProtocolException.Kind.TRUNCATED, "header cut short");
        }
        ByteBuffer in = ByteBuffer.wrap(buffer, 0, SnapcastProtocolLimits.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        int type = in.getShort() & 0xffff;
        int id = in.getShort() & 0xffff;
        int refersTo = in.getShort() & 0xffff;
        int sentSec = in.getInt();
        int sentUsec = in.getInt();
        int receivedSec = in.getInt();
        int receivedUsec = in.getInt();
        long size = in.getInt() & 0xffffffffL;
        if (size > Integer.MAX_VALUE) {
            throw new SnapcastProtocolException(SnapcastProtocolException.Kind.NEGATIVE_SIZE, "size above 2^31 - 1");
        }
        if (size > SnapcastProtocolLimits.maxBody(type, maxChunkBody)) {
            throw new SnapcastProtocolException(SnapcastProtocolException.Kind.TOO_LARGE, "message larger than the cap");
        }
        return new MessageHeader(type, id, refersTo, sentSec, sentUsec, receivedSec, receivedUsec, (int) size);
    }

    void writeTo(ByteBuffer out) {
        out.putShort((short) type);
        out.putShort((short) id);
        out.putShort((short) refersTo);
        out.putInt(sentSec);
        out.putInt(sentUsec);
        out.putInt(receivedSec);
        out.putInt(receivedUsec);
        out.putInt(size);
    }

    public int getType() {
        return type;
    }

    public int getId() {
        return id;
    }

    public int getRefersTo() {
        return refersTo;
    }

    public int getSentSec() {
        return sentSec;
    }

    public int getSentUsec() {
        return sentUsec;
    }

    public int getReceivedSec() {
        return receivedSec;
    }

    public int getReceivedUsec() {
        return receivedUsec;
    }

    public int getSize() {
        return size;
    }

    /** The sent time as microseconds (seconds times a million plus the microseconds, as long arithmetic). */
    public long sentMicros() {
        return sentSec * 1_000_000L + sentUsec;
    }

    public long receivedMicros() {
        return receivedSec * 1_000_000L + receivedUsec;
    }
}
