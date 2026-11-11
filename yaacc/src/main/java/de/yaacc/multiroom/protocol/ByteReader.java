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

import java.nio.charset.StandardCharsets;

/**
 * Reads little endian fields from the body of one message. Every read is checked against what is left, so a length
 * that comes from the server is never used to reserve memory or to move a position before it was compared with the
 * rest of the message.
 */
final class ByteReader {

    private final byte[] data;
    private int position;

    ByteReader(byte[] data) {
        this.data = data;
    }

    int remaining() {
        return data.length - position;
    }

    private void need(int count) throws SnapcastProtocolException {
        if (data.length - position < count) {
            throw new SnapcastProtocolException(SnapcastProtocolException.Kind.TRUNCATED, "message cut short");
        }
    }

    int i32() throws SnapcastProtocolException {
        need(4);
        int value = (data[position] & 0xff) | ((data[position + 1] & 0xff) << 8) | ((data[position + 2] & 0xff) << 16)
                | ((data[position + 3] & 0xff) << 24);
        position += 4;
        return value;
    }

    /** An unsigned 32 bit value. */
    long u32() throws SnapcastProtocolException {
        return i32() & 0xffffffffL;
    }

    /** The next {@code length} bytes; BAD_LENGTH when that is more than is left. */
    byte[] bytes(long length) throws SnapcastProtocolException {
        if (length < 0 || length > data.length - position) {
            throw new SnapcastProtocolException(SnapcastProtocolException.Kind.BAD_LENGTH, "length beyond the message");
        }
        byte[] out = new byte[(int) length];
        System.arraycopy(data, position, out, 0, out.length);
        position += out.length;
        return out;
    }

    /** A u32 length and that many bytes. */
    byte[] lengthPrefixed() throws SnapcastProtocolException {
        return bytes(u32());
    }

    /** A u32 length and that many bytes of UTF-8 text (malformed sequences become U+FFFD). */
    String text() throws SnapcastProtocolException {
        return new String(lengthPrefixed(), StandardCharsets.UTF_8);
    }
}
