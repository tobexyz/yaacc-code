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
package de.yaacc.multiroom.control;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.util.Arrays;

/**
 * Reads newline-terminated lines from a stream at the byte level (not {@code readLine()}, which would buffer a line of
 * any length): a line is capped at {@code maxLineBytes} and must be finished within {@code lineDeadlineMillis} of its
 * first byte, so a server can neither fill the memory nor keep the reader busy with a line that never ends.
 */
final class SnapcastLineReader {

    private final InputStream in;
    private final int maxLineBytes;
    private final long lineDeadlineMillis;
    private final MonotonicClock clock;
    private final byte[] buffer = new byte[8192];
    private int position;
    private int limit;
    private byte[] line = new byte[256];
    private int lineLength;
    private long lineStart;

    SnapcastLineReader(InputStream in, int maxLineBytes, long lineDeadlineMillis, MonotonicClock clock) {
        this.in = in;
        this.maxLineBytes = maxLineBytes;
        this.lineDeadlineMillis = lineDeadlineMillis;
        this.clock = clock;
    }

    /**
     * @return the next line without its terminator ({@code \n} or {@code \r\n}), or null at the end of the stream (a
     * last line without a terminator is dropped)
     * @throws SocketTimeoutException when nothing arrived within the read timeout and no line is under way (idle)
     * @throws SnapcastException      LIMIT when the line is longer than the cap; TIMEOUT when a line under way stalls or
     *                                takes longer than the deadline
     * @throws IOException            the stream failed
     */
    byte[] readLine() throws IOException, SnapcastException {
        lineLength = 0;
        while (true) {
            if (position >= limit) {
                if (!fill()) {
                    return null;
                }
            }
            int newline = -1;
            for (int i = position; i < limit; i++) {
                if (buffer[i] == '\n') {
                    newline = i;
                    break;
                }
            }
            int end = newline >= 0 ? newline : limit;
            append(end - position);
            position = newline >= 0 ? newline + 1 : limit;
            if (newline >= 0) {
                int length = lineLength;
                if (length > 0 && line[length - 1] == '\r') {
                    length--;
                }
                lineLength = 0;
                return Arrays.copyOf(line, length);
            }
            if (lineLength > 0 && clock.nowMillis() - lineStart > lineDeadlineMillis) {
                throw SnapcastException.timeout("line took too long");
            }
        }
    }

    private void append(int count) throws SnapcastException {
        if (count == 0) {
            return;
        }
        if (lineLength + count > maxLineBytes) {
            throw SnapcastException.limit("line too long");
        }
        if (lineLength == 0) {
            lineStart = clock.nowMillis();
        }
        if (lineLength + count > line.length) {
            line = Arrays.copyOf(line, Math.min(maxLineBytes, Math.max(line.length * 2, lineLength + count)));
        }
        System.arraycopy(buffer, position, line, lineLength, count);
        lineLength += count;
    }

    /** @return false at the end of the stream */
    private boolean fill() throws IOException, SnapcastException {
        int read;
        try {
            read = in.read(buffer, 0, buffer.length);
        } catch (SocketTimeoutException e) {
            if (lineLength > 0) {
                throw SnapcastException.timeout("line stalled");
            }
            throw e;
        }
        if (read < 0) {
            return false;
        }
        position = 0;
        limit = read;
        return true;
    }
}
