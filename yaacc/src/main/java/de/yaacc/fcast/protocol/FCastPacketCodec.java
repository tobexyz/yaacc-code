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
package de.yaacc.fcast.protocol;

import java.nio.charset.StandardCharsets;

/**
 * The framing of FCast protocol v3: a little-endian 32 bit size that counts the opcode and the body, one opcode byte, then
 * the body (UTF-8 JSON, may be absent). A whole packet is 32000 bytes at most (the limit of the Android receiver, the
 * desktop receiver skips what is larger). Pure; the reading side is {@link FCastFrameReader}.
 */
public final class FCastPacketCodec {
    /** The most bytes of a packet after the size field: the opcode and the body. */
    public static final int MAX_PACKET = 32000;
    /** The most bytes of a body. */
    public static final int MAX_BODY = MAX_PACKET - 1;

    /** The most bytes of a packet of version 4 (after the size field). */
    public static final int MAX_PACKET_V4 = 512 * 1024;

    private FCastPacketCodec() {
    }

    /**
     * A packet with a binary body (version 4).
     *
     * @throws FCastProtocolException {@code TOO_LARGE} if it is over {@code maxPacket}
     */
    public static byte[] encodeBinary(int opcode, byte[] body, int maxPacket) throws FCastProtocolException {
        if (opcode < 0 || opcode > 255) {
            throw new FCastProtocolException(FCastProtocolException.Kind.UNSUPPORTED, "opcode " + opcode + " is not a byte");
        }
        byte[] data = body == null ? new byte[0] : body;
        if (data.length > maxPacket - 1) {
            throw new FCastProtocolException(FCastProtocolException.Kind.TOO_LARGE,
                    "a body of " + data.length + " bytes does not fit into a packet (at most " + (maxPacket - 1) + ")");
        }
        int size = 1 + data.length;
        byte[] packet = new byte[4 + size];
        packet[0] = (byte) size;
        packet[1] = (byte) (size >> 8);
        packet[2] = (byte) (size >> 16);
        packet[3] = (byte) (size >> 24);
        packet[4] = (byte) opcode;
        System.arraycopy(data, 0, packet, 5, data.length);
        return packet;
    }

    /**
     * @param opcode the wire value of the opcode (0 to 255)
     * @param body   the JSON text, or null for a packet without body
     * @return the bytes to write
     * @throws FCastProtocolException {@code TOO_LARGE} if the packet would be over the limit (nothing is cut: a message that
     *                                does not fit is refused by the caller)
     */
    public static byte[] encode(int opcode, String body) throws FCastProtocolException {
        if (opcode < 0 || opcode > 255) {
            throw new FCastProtocolException(FCastProtocolException.Kind.UNSUPPORTED, "opcode " + opcode + " is not a byte");
        }
        byte[] data = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_BODY) {
            throw new FCastProtocolException(FCastProtocolException.Kind.TOO_LARGE,
                    "a body of " + data.length + " bytes does not fit into a packet (at most " + MAX_BODY + ")");
        }
        int size = 1 + data.length;
        byte[] packet = new byte[4 + size];
        packet[0] = (byte) size;
        packet[1] = (byte) (size >> 8);
        packet[2] = (byte) (size >> 16);
        packet[3] = (byte) (size >> 24);
        packet[4] = (byte) opcode;
        System.arraycopy(data, 0, packet, 5, data.length);
        return packet;
    }
}
