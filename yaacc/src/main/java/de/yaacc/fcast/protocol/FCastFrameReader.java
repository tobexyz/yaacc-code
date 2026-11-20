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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;

/**
 * Reads the packets of FCast protocol v3 from a stream (see {@link FCastPacketCodec}). The stream may deliver any number of
 * bytes per read; a packet is assembled from as many reads as it takes. Not thread safe: one reader thread.
 *
 * <p>Rules (the receivers end the connection on the same): a size of 0 ({@code EMPTY_PACKET}), a size over
 * {@link FCastPacketCodec#MAX_PACKET} ({@code OVERSIZE}; nothing of the body is read, no memory is allocated for it) and a
 * stream that ends inside a packet ({@code TRUNCATED}) are errors that end the connection; a stream that ends between two
 * packets is the normal end. A body that is not valid UTF-8 is {@code BAD_BODY}, which does not end the connection (the
 * packet was read completely). An unknown opcode is returned like any other: the caller skips it.
 */
public final class FCastFrameReader {
    private final InputStream in;

    public FCastFrameReader(InputStream in) {
        this.in = in;
    }

    /**
     * @return the next packet, or null if the stream ended before the first byte of a packet
     * @throws FCastProtocolException for the cases above
     * @throws IOException            if the stream fails (including a timeout set on a socket)
     */
    public FCastPacket read() throws IOException {
        int first = in.read();
        if (first < 0) {
            return null;
        }
        long size = first;
        for (int i = 1; i < 4; i++) {
            int b = in.read();
            if (b < 0) {
                throw new FCastProtocolException(FCastProtocolException.Kind.TRUNCATED, "the stream ended inside the size field");
            }
            size |= ((long) b) << (8 * i);
        }
        if (size == 0) {
            throw new FCastProtocolException(FCastProtocolException.Kind.EMPTY_PACKET, "a packet without an opcode (size 0)");
        }
        if (size > FCastPacketCodec.MAX_PACKET) {
            throw new FCastProtocolException(FCastProtocolException.Kind.OVERSIZE,
                    "a packet of " + size + " bytes is over the limit of " + FCastPacketCodec.MAX_PACKET);
        }
        int opcode = in.read();
        if (opcode < 0) {
            throw new FCastProtocolException(FCastProtocolException.Kind.TRUNCATED, "the stream ended before the opcode");
        }
        int bodyLength = (int) size - 1;
        if (bodyLength == 0) {
            return new FCastPacket(opcode, null);
        }
        byte[] bytes = new byte[bodyLength];
        int done = 0;
        while (done < bodyLength) {
            int n = in.read(bytes, done, bodyLength - done);
            if (n < 0) {
                throw new FCastProtocolException(FCastProtocolException.Kind.TRUNCATED,
                        "the stream ended inside a body (" + done + " of " + bodyLength + " bytes)");
            }
            done += n;
        }
        try {
            String body = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            return new FCastPacket(opcode, body);
        } catch (CharacterCodingException e) {
            throw new FCastProtocolException(FCastProtocolException.Kind.BAD_BODY, "the body is not valid UTF-8", e);
        }
    }
}
