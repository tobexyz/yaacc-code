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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/** The byte-level line reader: cap, deadline, framing. */
public class SnapcastLineReaderTest {

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static SnapcastLineReader reader(InputStream in, int max) {
        return new SnapcastLineReader(in, max, 10_000, MonotonicClock.SYSTEM);
    }

    @Test
    public void readsLinesAndEndsWithNullAtEof() throws Exception {
        SnapcastLineReader reader = reader(new ByteArrayInputStream(bytes("one\ntwo\r\n\nthree")), 1024);

        assertArrayEquals(bytes("one"), reader.readLine());
        assertArrayEquals(bytes("two"), reader.readLine());
        assertArrayEquals("an empty line is an empty array", new byte[0], reader.readLine());
        assertNull("the last line has no newline: it is not a line", reader.readLine());
        assertNull(reader.readLine());
    }

    @Test
    public void aLineMayBeExactlyAsLongAsTheCap() throws Exception {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            text.append('a');
        }
        SnapcastLineReader reader = reader(new ByteArrayInputStream(bytes(text + "\nnext\n")), 100);

        assertEquals(100, reader.readLine().length);
        assertArrayEquals(bytes("next"), reader.readLine());
    }

    @Test
    public void aLongerLineIsRefusedWithoutReadingAllOfIt() throws Exception {
        final long[] consumed = {0};
        InputStream endless = new InputStream() {
            @Override
            public int read() {
                consumed[0]++;
                return 'x';
            }

            @Override
            public int read(byte[] b, int off, int len) {
                consumed[0] += len;
                java.util.Arrays.fill(b, off, off + len, (byte) 'x');
                return len;
            }
        };
        SnapcastLineReader reader = reader(endless, 1 << 20);

        try {
            reader.readLine();
            fail("accepted an endless line");
        } catch (SnapcastException expected) {
            assertEquals(SnapcastException.Kind.LIMIT, expected.getKind());
        }
        assertTrue("stopped soon after the cap: " + consumed[0], consumed[0] < (1 << 20) + 64 * 1024);
    }

    @Test
    public void lineOf2MiBWithNewlineIsRefused() throws Exception {
        byte[] line = new byte[2 * 1024 * 1024 + 1];
        java.util.Arrays.fill(line, (byte) 'y');
        line[line.length - 1] = '\n';
        SnapcastLineReader reader = reader(new ByteArrayInputStream(line), 1 << 20);

        try {
            reader.readLine();
            fail();
        } catch (SnapcastException expected) {
            assertEquals(SnapcastException.Kind.LIMIT, expected.getKind());
        }
    }

    @Test
    public void linesAcrossSmallReadsAreAssembled() throws Exception {
        final byte[] data = bytes("{\"a\":1}\n{\"b\":2}\n");
        InputStream oneByte = new InputStream() {
            int position;

            @Override
            public int read() {
                return position < data.length ? data[position++] : -1;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                int value = read();
                if (value < 0) {
                    return -1;
                }
                b[off] = (byte) value;
                return 1;
            }
        };
        SnapcastLineReader reader = reader(oneByte, 1024);

        assertArrayEquals(bytes("{\"a\":1}"), reader.readLine());
        assertArrayEquals(bytes("{\"b\":2}"), reader.readLine());
    }

    @Test
    public void aSlowLineHitsTheDeadlineOnAFakeClock() throws Exception {
        final long[] now = {0};
        MonotonicClock clock = () -> now[0];
        // one byte per read, 4 seconds pass with every byte, never a newline
        InputStream drip = new InputStream() {
            @Override
            public int read() {
                now[0] += 4_000;
                return 'x';
            }

            @Override
            public int read(byte[] b, int off, int len) {
                now[0] += 4_000;
                b[off] = 'x';
                return 1;
            }
        };
        SnapcastLineReader reader = new SnapcastLineReader(drip, 1 << 20, 10_000, clock);

        try {
            reader.readLine();
            fail();
        } catch (SnapcastException expected) {
            assertEquals(SnapcastException.Kind.TIMEOUT, expected.getKind());
        }
        assertTrue("gave up after a few bytes: " + now[0], now[0] <= 20_000);
    }

    @Test
    public void aTimeoutInTheMiddleOfALineIsAnError() throws Exception {
        final int[] calls = {0};
        InputStream stalls = new InputStream() {
            @Override
            public int read() throws IOException {
                return read(new byte[1], 0, 1);
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (calls[0]++ == 0) {
                    b[off] = 'x';
                    return 1;
                }
                throw new SocketTimeoutException("stalled");
            }
        };
        SnapcastLineReader reader = reader(stalls, 1024);

        try {
            reader.readLine();
            fail();
        } catch (SnapcastException expected) {
            assertEquals(SnapcastException.Kind.TIMEOUT, expected.getKind());
        }
    }

    @Test
    public void aTimeoutBetweenLinesIsOnlyIdleTime() throws Exception {
        InputStream idle = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new SocketTimeoutException("idle");
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                throw new SocketTimeoutException("idle");
            }
        };
        SnapcastLineReader reader = reader(idle, 1024);

        try {
            reader.readLine();
            fail();
        } catch (SocketTimeoutException expected) {
            // the caller loops on this
        }
    }
}
