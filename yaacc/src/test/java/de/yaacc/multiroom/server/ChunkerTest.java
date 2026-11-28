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
package de.yaacc.multiroom.server;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * The timeline of a stream (design, "Timeline and chunks"): the PCM that a producer sends is cut into chunks of about 20 ms and each is
 * stamped with the stream clock, which starts with the first data and adds the exact duration of everything cut since; a read that is
 * late restarts the clock.
 */
public class ChunkerTest {

    private static byte[] pcm(int bytes, int fill) {
        byte[] b = new byte[bytes];
        java.util.Arrays.fill(b, (byte) fill);
        return b;
    }

    @Test
    public void aChunkIsTwentyMillisecondsOfFrames() {
        assertEquals(960, new Chunker(48000, 4).chunkFrames());
        assertEquals(882, new Chunker(44100, 4).chunkFrames());
        assertEquals(3840, new Chunker(48000, 4).chunkBytes());
        assertEquals(220, new Chunker(11025, 4).chunkFrames()); // not whole: the frames are cut down, the stamps do not drift (below)
    }

    @Test
    public void dataInOddPiecesMakesWholeChunksAndKeepsTheRest() {
        Chunker c = new Chunker(48000, 4);
        List<Chunker.Chunk> out = new ArrayList<>();
        out.addAll(c.feed(pcm(1000, 1), 0, 1000, 1_000_000L));
        assertEquals(0, out.size());
        out.addAll(c.feed(pcm(3000, 2), 0, 3000, 1_000_100L)); // 4000 bytes: one chunk of 3840 and 160 left
        assertEquals(1, out.size());
        assertEquals(3840, out.get(0).pcm.length);
        assertEquals((byte) 1, out.get(0).pcm[0]);
        assertEquals((byte) 2, out.get(0).pcm[3839]);
        out.addAll(c.feed(pcm(3680, 3), 0, 3680, 1_020_000L)); // the 160 and these make the second
        assertEquals(2, out.size());
        assertEquals((byte) 2, out.get(1).pcm[0]);
    }

    @Test
    public void theClockStartsWithTheFirstDataAndAdvancesByTheDurationOfEachChunk() {
        Chunker c = new Chunker(44100, 4);
        List<Chunker.Chunk> all = new ArrayList<>();
        long now = 5_000_000L;
        for (int i = 0; i < 3; i++) {
            all.addAll(c.feed(pcm(c.chunkBytes(), i), 0, c.chunkBytes(), now));
            now += 20_000L;
        }
        assertEquals(5_000_000L, all.get(0).timestampMicros);
        assertEquals(5_020_000L, all.get(1).timestampMicros);
        assertEquals(5_040_000L, all.get(2).timestampMicros);
    }

    @Test
    public void aChunkThatIsNotAWholeNumberOfMicrosecondsDoesNotDrift() {
        Chunker c = new Chunker(11025, 4); // 220 frames = 19954.65 us
        long start = 10_000_000L;
        long now = start;
        long last = 0;
        for (int i = 0; i < 5000; i++) {
            List<Chunker.Chunk> out = c.feed(pcm(c.chunkBytes(), 0), 0, c.chunkBytes(), now);
            last = out.get(0).timestampMicros;
            now += 19_955L;
        }
        long expected = start + 4999L * 220L * 1_000_000L / 11025L;
        assertEquals("no rounding error builds up over 100 seconds", expected, last);
    }

    @Test
    public void aSmallJitterOfTheReadsKeepsTheTimeline() {
        Chunker c = new Chunker(48000, 4);
        long t0 = 1_000_000L;
        c.feed(pcm(c.chunkBytes(), 0), 0, c.chunkBytes(), t0);
        List<Chunker.Chunk> early = c.feed(pcm(c.chunkBytes(), 0), 0, c.chunkBytes(), t0 + 5_000L);
        assertEquals(t0 + 20_000L, early.get(0).timestampMicros);
        List<Chunker.Chunk> late = c.feed(pcm(c.chunkBytes(), 0), 0, c.chunkBytes(), t0 + 40_000L + 15_000L);
        assertEquals(t0 + 40_000L, late.get(0).timestampMicros);
        assertEquals(0, c.resyncs());
    }

    @Test
    public void aReadThatIsLateRestartsTheClockFromNow() {
        Chunker c = new Chunker(48000, 4);
        long t0 = 1_000_000L;
        c.feed(pcm(c.chunkBytes(), 0), 0, c.chunkBytes(), t0);
        long late = t0 + 20_000L + 500_000L;
        List<Chunker.Chunk> out = c.feed(pcm(c.chunkBytes(), 0), 0, c.chunkBytes(), late);
        assertEquals("the chunk ends now, so it began one chunk ago", late - 20_000L, out.get(0).timestampMicros);
        assertEquals(1, c.resyncs());
        List<Chunker.Chunk> next = c.feed(pcm(c.chunkBytes(), 0), 0, c.chunkBytes(), late + 20_000L);
        assertEquals("the new timeline goes on from there", late, next.get(0).timestampMicros);
    }

    @Test
    public void theReaderIsToldHowLongToWaitForTheDeadlineOfTheChunk() {
        Chunker c = new Chunker(48000, 4);
        long t0 = 1_000_000L;
        c.feed(pcm(c.chunkBytes(), 0), 0, c.chunkBytes(), t0);
        assertEquals("a chunk is read at once but is due 20 ms after its stamp", 15_000L, c.waitMicros(t0 + 5_000L));
        assertEquals(0L, c.waitMicros(t0 + 30_000L));
    }

    @Test
    public void resetForgetsWhatWasPendingAndTheClock() {
        Chunker c = new Chunker(48000, 4);
        c.feed(pcm(1000, 0), 0, 1000, 1_000_000L);
        c.reset();
        List<Chunker.Chunk> out = c.feed(pcm(c.chunkBytes(), 7), 0, c.chunkBytes(), 9_000_000L);
        assertEquals(1, out.size());
        assertEquals(9_000_000L, out.get(0).timestampMicros);
        assertEquals((byte) 7, out.get(0).pcm[0]);
    }

    @Test
    public void aBigReadMakesSeveralChunksWithConsecutiveStamps() {
        Chunker c = new Chunker(48000, 4);
        List<Chunker.Chunk> out = c.feed(pcm(c.chunkBytes() * 5 + 100, 0), 0, c.chunkBytes() * 5 + 100, 2_000_000L);
        assertEquals(5, out.size());
        for (int i = 0; i < 5; i++) {
            assertEquals(2_000_000L + i * 20_000L, out.get(i).timestampMicros);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void aSampleRateThatMakesNoChunkIsRefused() {
        new Chunker(10, 4);
    }

    @Test(expected = IllegalArgumentException.class)
    public void aFrameOfNoBytesIsRefused() {
        new Chunker(48000, 0);
    }

    @Test
    public void silenceIsAChunkOfZeros() {
        assertTrue(Chunker.isSilent(pcm(3840, 0)));
        byte[] almost = pcm(3840, 0);
        almost[1000] = 1;
        assertTrue(!Chunker.isSilent(almost));
        assertArrayEquals(new byte[0], new byte[0]);
    }
}
