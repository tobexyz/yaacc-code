/*
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
package de.yaacc.share;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;

/** {@link BudgetedCopier}: the budget is asked before every chunk is written. Plain JVM. */
public class BudgetedCopierTest {

    private static final long GIB = 1024L * 1024L * 1024L;
    private static final long UNKNOWN = -1L;

    private static byte[] data(int size) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) (i * 31);
        }
        return bytes;
    }

    private static BudgetedCopier.Result copy(byte[] in, ByteArrayOutputStream out, long declared,
                                              long max, long space) throws IOException {
        return BudgetedCopier.copy(new ByteArrayInputStream(in), out, declared, max, () -> space,
                () -> false, new byte[100]);
    }

    @Test
    public void copiesEverythingAndReportsTheBytes() throws IOException {
        byte[] in = data(1_050);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        BudgetedCopier.Result result = copy(in, out, in.length, 10_000, 10 * GIB);

        assertEquals(BudgetedCopier.Outcome.COPIED, result.outcome());
        assertEquals(1_050L, result.bytes());
        assertArrayEquals(in, out.toByteArray());
    }

    @Test
    public void anUnknownDeclaredSizeIsFine() throws IOException {
        byte[] in = data(250);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        BudgetedCopier.Result result = copy(in, out, UNKNOWN, 10_000, 10 * GIB);

        assertEquals(BudgetedCopier.Outcome.COPIED, result.outcome());
        assertArrayEquals(in, out.toByteArray());
    }

    @Test
    public void aDeclaredSizeOverTheBudgetCopiesNothing() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        BudgetedCopier.Result result = copy(data(500), out, 5_000, 1_000, 10 * GIB);

        assertEquals(BudgetedCopier.Outcome.TOO_BIG, result.outcome());
        assertEquals(0, out.size());
    }

    @Test
    public void aStreamLongerThanItDeclaredStopsAtTheBudgetBeforeWritingThePastTheCap() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        BudgetedCopier.Result result = copy(data(1_000), out, UNKNOWN, 450, 10 * GIB);

        assertEquals(BudgetedCopier.Outcome.TOO_BIG, result.outcome());
        assertEquals(400L, result.bytes());
        assertEquals("the chunk that would cross the cap is not written", 400, out.size());
    }

    @Test
    public void aStreamLongerThanTheAbsoluteCapIsTooBigEvenIfTheBudgetIsLarger() throws IOException {
        InputStream endless = new InputStream() {
            @Override
            public int read() {
                return 1;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                return len;
            }
        };
        long chunk = 100;
        AtomicInteger writes = new AtomicInteger();
        java.io.OutputStream sink = new java.io.OutputStream() {
            @Override
            public void write(int b) {
            }

            @Override
            public void write(byte[] b, int off, int len) {
                writes.incrementAndGet();
            }
        };

        BudgetedCopier.Result result = BudgetedCopier.copy(endless, sink, UNKNOWN, 100L * GIB,
                () -> 100 * GIB, () -> false, new byte[(int) chunk]);

        assertEquals(BudgetedCopier.Outcome.TOO_BIG, result.outcome());
        assertEquals(SharedContentPolicy.MAX_COPY_BYTES / chunk, writes.get());
    }

    @Test
    public void tooLittleFreeSpaceStopsTheCopyBeforeTheChunkIsWritten() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long justUnderTheReserve = ShareLimits.COPY_FREE_SPACE_RESERVE_BYTES - 1;

        BudgetedCopier.Result result = copy(data(500), out, 500, 10_000, justUnderTheReserve);

        assertEquals(BudgetedCopier.Outcome.NO_SPACE, result.outcome());
        assertEquals(0, out.size());
    }

    @Test
    public void spaceIsAskedBeforeEveryChunk() throws IOException {
        AtomicInteger asked = new AtomicInteger();

        BudgetedCopier.copy(new ByteArrayInputStream(data(1_050)), new ByteArrayOutputStream(), 1_050, 10_000,
                () -> {
                    asked.incrementAndGet();
                    return 10 * GIB;
                }, () -> false, new byte[100]);

        assertEquals(11, asked.get());
    }

    @Test
    public void spaceRunningOutMidwayStopsTheCopy() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AtomicInteger asked = new AtomicInteger();

        BudgetedCopier.Result result = BudgetedCopier.copy(new ByteArrayInputStream(data(1_000)), out, UNKNOWN,
                10_000, () -> asked.incrementAndGet() <= 3 ? 10 * GIB : 0L, () -> false, new byte[100]);

        assertEquals(BudgetedCopier.Outcome.NO_SPACE, result.outcome());
        assertEquals(300, out.size());
    }

    @Test
    public void theStopFlagEndsTheCopy() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AtomicInteger checks = new AtomicInteger();

        BudgetedCopier.Result result = BudgetedCopier.copy(new ByteArrayInputStream(data(1_000)), out, UNKNOWN,
                10_000, () -> 10 * GIB, () -> checks.incrementAndGet() > 2, new byte[100]);

        assertEquals(BudgetedCopier.Outcome.CANCELLED, result.outcome());
        assertEquals(200, out.size());
    }

    @Test
    public void anEmptyStreamIsNotAShare() throws IOException {
        BudgetedCopier.Result result = copy(new byte[0], new ByteArrayOutputStream(), UNKNOWN, 10_000, 10 * GIB);

        assertEquals(BudgetedCopier.Outcome.EMPTY, result.outcome());
    }

    @Test
    public void aNoBudgetMeansTooBig() throws IOException {
        assertEquals(BudgetedCopier.Outcome.TOO_BIG,
                copy(data(10), new ByteArrayOutputStream(), UNKNOWN, 0, 10 * GIB).outcome());
    }

    @Test(expected = IOException.class)
    public void aReadErrorIsPassedOn() throws IOException {
        InputStream failing = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("provider died");
            }
        };

        BudgetedCopier.copy(failing, new ByteArrayOutputStream(), UNKNOWN, 1_000, () -> 10 * GIB,
                () -> false, new byte[10]);
    }
}
