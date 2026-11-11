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

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.util.Arrays;

/**
 * Reads frames from a stream (the socket of the connection). The size of a frame is checked against its cap right
 * after the 26 byte header and before one byte of the body is read; the body buffer starts small and grows with the
 * bytes that really arrive, so a header that promises 8 MiB and then stalls costs 64 KiB; a frame of a type that is not
 * handled is read past in a small buffer.
 *
 * <p><b>Time.</b> The socket's read timeout bounds one read; this class adds a deadline for a whole frame, counted from
 * its first byte, so a sender that drips a byte inside every read timeout still ends. A read timeout before the first
 * byte of a frame is passed on as the {@link SocketTimeoutException} that it is (nothing was consumed, the caller may
 * read again); inside a frame it is a TIMEOUT error. A clean end between two frames is an {@link EOFException}, an end
 * inside a frame is TRUNCATED. One reader belongs to one thread.
 */
public final class FrameReader {

    private static final int INITIAL_BODY = 64 * 1024;
    private static final int SKIP_BUFFER = 4096;

    private final InputStream in;
    private final MicroClock clock;
    private final long deadlineMicros;
    private final byte[] headerBuffer = new byte[SnapcastProtocolLimits.HEADER_SIZE];
    private byte[] skipBuffer;
    private long frameStartMicros;
    private boolean frameStarted;

    /**
     * @param messageDeadlineMillis how long a frame may take from its first byte to its last
     */
    public FrameReader(InputStream in, MicroClock clock, long messageDeadlineMillis) {
        this.in = in;
        this.clock = clock;
        this.deadlineMicros = messageDeadlineMillis * 1000L;
    }

    /**
     * Reads the next frame.
     *
     * @throws SocketTimeoutException    when the read timed out before the first byte of a frame
     * @throws EOFException              when the stream ended before the first byte of a frame
     * @throws IOException               for any other failure of the stream
     * @throws SnapcastProtocolException for a frame that is too large, too slow, cut short or wrong
     */
    public Frame read() throws IOException, SnapcastProtocolException {
        frameStarted = false;
        int have = 0;
        while (have < headerBuffer.length) {
            int count = readSome(headerBuffer, have, headerBuffer.length - have);
            have += count;
        }
        MessageHeader header = SnapcastMessageCodec.parseHeader(headerBuffer);
        int size = header.getSize();
        if (MessageType.of(header.getType()) == null) {
            skip(size);
            return new Frame(header, null);
        }
        byte[] body = new byte[Math.min(size, INITIAL_BODY)];
        have = 0;
        while (have < size) {
            if (have == body.length) {
                body = Arrays.copyOf(body, (int) Math.min((long) size, body.length * 2L));
            }
            have += readSome(body, have, body.length - have);
        }
        return new Frame(header, SnapcastMessageCodec.decodeBody(header, body));
    }

    private void skip(int size) throws IOException, SnapcastProtocolException {
        if (skipBuffer == null) {
            skipBuffer = new byte[SKIP_BUFFER];
        }
        int left = size;
        while (left > 0) {
            left -= readSome(skipBuffer, 0, Math.min(skipBuffer.length, left));
        }
    }

    /** One read of at least one byte, with the timeout, end of stream and deadline rules of this class. */
    private int readSome(byte[] buffer, int offset, int length) throws IOException, SnapcastProtocolException {
        int count;
        try {
            count = in.read(buffer, offset, length);
        } catch (SocketTimeoutException e) {
            if (!frameStarted) {
                throw e;
            }
            throw new SnapcastProtocolException(SnapcastProtocolException.Kind.TIMEOUT, "read timed out inside a message");
        }
        if (count < 0) {
            if (!frameStarted) {
                throw new EOFException("connection closed");
            }
            throw new SnapcastProtocolException(SnapcastProtocolException.Kind.TRUNCATED, "connection closed inside a message");
        }
        if (count == 0) {
            throw new IOException("no progress");
        }
        long now = clock.nowMicros();
        if (!frameStarted) {
            frameStarted = true;
            frameStartMicros = now;
        } else if (now - frameStartMicros > deadlineMicros) {
            throw new SnapcastProtocolException(SnapcastProtocolException.Kind.DEADLINE, "message took too long");
        }
        return count;
    }
}
