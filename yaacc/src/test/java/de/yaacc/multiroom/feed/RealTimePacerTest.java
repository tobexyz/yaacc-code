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
package de.yaacc.multiroom.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * The pacing math at 48000:16:2 (192,000 bytes per second), chunks of 20 ms (3,840 bytes), a look-ahead of 3 chunks
 * (60 ms) and a stall limit of 2 chunks. The clock is moved by the test.
 */
public class RealTimePacerTest {

    private static final int BYTES_PER_SECOND = 192_000;
    private static final int CHUNK = 3_840;
    private static final long CHUNK_NANOS = 20_000_000L;
    private static final long LOOK_AHEAD_NANOS = 3 * CHUNK_NANOS;

    private final ManualFeedClock clock = new ManualFeedClock();

    private RealTimePacer pacer() {
        return new RealTimePacer(clock, BYTES_PER_SECOND, CHUNK, 3, 2);
    }

    /** Writes one chunk as early as the pacer allows (the clock is moved to that time) and returns the wait. */
    private long writeChunkWhenAllowed(RealTimePacer pacer) {
        long wait = pacer.nanosUntilWritable(CHUNK);
        clock.advance(wait);
        pacer.onWritten(CHUNK);
        return wait;
    }

    // ---- the format arithmetic

    @Test
    public void bytesPerSecondAndChunkSizeComeFromTheSampleFormat() {
        RealTimePacer pacer = new RealTimePacer(clock, new PcmFormat(48000, 16, 2), 20);

        assertEquals(CHUNK, pacer.getChunkBytes());
        assertEquals(BYTES_PER_SECOND, pacer.getBytesPerSecond());
    }

    @Test
    public void aChunkIsWholeFramesAtOtherRates() {
        RealTimePacer pacer = new RealTimePacer(clock, new PcmFormat(44100, 16, 2), 20);

        assertEquals(882 * 4, pacer.getChunkBytes());
    }

    // ---- look-ahead

    @Test
    public void theFirstChunkMayBeWrittenAtOnceAndAnchorsTheClock() {
        RealTimePacer pacer = pacer();

        assertEquals(0, pacer.nanosUntilWritable(CHUNK));
        pacer.onWritten(CHUNK);

        assertEquals(CHUNK_NANOS, pacer.leadNanos());
    }

    @Test
    public void threeChunksGoOutAtOnceAndTheFourthWaitsTwentyMilliseconds() {
        RealTimePacer pacer = pacer();

        for (int i = 0; i < 3; i++) {
            assertEquals("chunk " + i, 0, pacer.nanosUntilWritable(CHUNK));
            pacer.onWritten(CHUNK);
        }

        assertEquals(CHUNK_NANOS, pacer.nanosUntilWritable(CHUNK));
        assertEquals(LOOK_AHEAD_NANOS, pacer.leadNanos());
    }

    @Test
    public void theLeadNeverExceedsTheLookAheadOverTenThousandChunks() {
        RealTimePacer pacer = pacer();

        for (int i = 0; i < 10_000; i++) {
            writeChunkWhenAllowed(pacer);
            assertTrue("lead " + pacer.leadNanos(), pacer.leadNanos() <= LOOK_AHEAD_NANOS);
        }

        assertEquals(LOOK_AHEAD_NANOS, pacer.getMaxLeadNanos());
        assertEquals(0, pacer.getLateChunks());
        assertEquals(0, pacer.getReanchors());
    }

    @Test
    public void theWritesFollowAbsoluteDeadlinesAndDoNotDriftWhenEveryWriteIsLate() {
        RealTimePacer pacer = pacer();
        long start = clock.nanoTime();

        for (int i = 0; i < 3_000; i++) {
            long wait = pacer.nanosUntilWritable(CHUNK);
            clock.advance(wait + 5_000_000L); // the thread wakes 5 ms late every time
            pacer.onWritten(CHUNK);
        }

        // 3000 chunks = 60 s of audio; with absolute deadlines the clock is at most the late slack ahead of
        // (audio time - look-ahead), not 3000 * 5 ms = 15 s behind
        long elapsed = clock.nanoTime() - start;
        assertTrue("elapsed " + elapsed, elapsed <= 3_000 * CHUNK_NANOS - LOOK_AHEAD_NANOS + 15_000_000L);
        assertTrue("elapsed " + elapsed, elapsed >= 3_000 * CHUNK_NANOS - LOOK_AHEAD_NANOS);
        assertEquals(0, pacer.getReanchors());
    }

    @Test
    public void neverFasterThanRealTimeInTheLongRun() {
        RealTimePacer pacer = pacer();
        long start = clock.nanoTime();
        long bytes = 0;

        for (int i = 0; i < 5_000; i++) {
            writeChunkWhenAllowed(pacer);
            bytes += CHUNK;
            long elapsedNanos = clock.nanoTime() - start;
            long allowedBytes = (elapsedNanos + LOOK_AHEAD_NANOS) * BYTES_PER_SECOND / 1_000_000_000L;
            assertTrue("bytes " + bytes + " allowed " + allowedBytes, bytes <= allowedBytes);
        }
    }

    // ---- partial chunks

    @Test
    public void partialChunksAreAccountedByTheirBytes() {
        RealTimePacer pacer = pacer();

        // 3 x 1280 bytes are exactly one chunk (3840): 20 ms
        for (int i = 0; i < 3; i++) {
            pacer.onWritten(1280);
        }
        assertEquals(CHUNK_NANOS, pacer.leadNanos());

        // 7 more chunks' worth in odd pieces keep the lead below the limit when the writer waits as told
        for (int i = 0; i < 700; i++) {
            long wait = pacer.nanosUntilWritable(1000);
            clock.advance(wait);
            pacer.onWritten(1000);
            assertTrue(pacer.leadNanos() <= LOOK_AHEAD_NANOS);
        }
        assertEquals(LOOK_AHEAD_NANOS, pacer.getMaxLeadNanos());
    }

    @Test
    public void aWriteLargerThanTheLookAheadWaitsUntilItsEndFitsTheLimit() {
        RealTimePacer pacer = pacer();
        pacer.onWritten(CHUNK);

        // after the first chunk (20 ms sent) another 10 chunks end 220 ms after the anchor; they may end 60 ms ahead
        // of the clock at most, so they may be written when 160 ms have passed
        assertEquals(11 * CHUNK_NANOS - LOOK_AHEAD_NANOS, pacer.nanosUntilWritable(10 * CHUNK));
    }

    // ---- stalls

    @Test
    public void aLateWriteWithinTwoChunksIsCaughtUpWithoutReanchoring() {
        RealTimePacer pacer = pacer();
        pacer.onWritten(CHUNK);

        // due after 20 ms, written after 50 ms: 1.5 chunks late is a late chunk, but under the stall limit of two
        clock.advance(CHUNK_NANOS + CHUNK_NANOS * 3 / 2);
        pacer.onWritten(CHUNK);

        assertEquals(1, pacer.getLateChunks());
        assertEquals(0, pacer.getReanchors());
        assertEquals(0, pacer.nanosUntilWritable(CHUNK));
    }

    @Test
    public void aStallOfMoreThanTwoChunksReanchorsInsteadOfBursting() {
        RealTimePacer pacer = pacer();
        for (int i = 0; i < 10; i++) {
            writeChunkWhenAllowed(pacer);
        }

        clock.advance(5_000_000_000L); // the producer stalls for 5 s
        pacer.onWritten(CHUNK);

        assertEquals(1, pacer.getReanchors());
        assertEquals(1, pacer.getLateChunks());
        // 250 chunks are "owed", but only the look-ahead's worth may go out now: no burst, no debt
        int immediately = 0;
        while (pacer.nanosUntilWritable(CHUNK) == 0) {
            pacer.onWritten(CHUNK);
            immediately++;
            assertTrue("burst of " + immediately, immediately <= 3);
        }
        assertEquals(2, immediately);
        assertEquals(CHUNK_NANOS, pacer.nanosUntilWritable(CHUNK));
    }

    @Test
    public void afterAStallTheLeadIsBoundedAgain() {
        RealTimePacer pacer = pacer();
        pacer.onWritten(CHUNK);
        clock.advance(60_000_000_000L); // a minute

        for (int i = 0; i < 100; i++) {
            writeChunkWhenAllowed(pacer);
            assertTrue(pacer.leadNanos() <= LOOK_AHEAD_NANOS);
        }
        assertEquals(1, pacer.getReanchors());
    }

    @Test
    public void repeatedStallsNeverAccumulateADebt() {
        RealTimePacer pacer = pacer();
        long start = clock.nanoTime();
        long bytes = 0;

        for (int round = 0; round < 50; round++) {
            for (int i = 0; i < 20; i++) {
                writeChunkWhenAllowed(pacer);
                bytes += CHUNK;
            }
            clock.advance(300_000_000L); // a 300 ms stall between the bursts
        }

        long elapsed = clock.nanoTime() - start;
        long allowed = (elapsed + LOOK_AHEAD_NANOS) * BYTES_PER_SECOND / 1_000_000_000L;
        assertTrue("bytes " + bytes + " allowed " + allowed, bytes <= allowed);
        assertEquals("every burst after the first starts with a re-anchor", 49, pacer.getReanchors());
    }

    // ---- reset and arithmetic

    @Test
    public void afterAResetTheNextWriteIsDueAtOnceAndAnchorsAgain() {
        RealTimePacer pacer = pacer();
        for (int i = 0; i < 3; i++) {
            pacer.onWritten(CHUNK);
        }
        clock.advance(10_000_000_000L);

        pacer.reset();

        assertEquals(0, pacer.nanosUntilWritable(CHUNK));
        assertEquals(0, pacer.nanosUntilDue());
        pacer.onWritten(CHUNK);
        assertEquals(CHUNK_NANOS, pacer.leadNanos());
        assertEquals(0, pacer.getLateChunks());
        assertEquals(0, pacer.getReanchors());
    }

    @Test
    public void theDueTimeIsTheEndOfTheAudioSentSoFar() {
        RealTimePacer pacer = pacer();
        pacer.onWritten(CHUNK);
        pacer.onWritten(CHUNK);

        assertEquals(2 * CHUNK_NANOS, pacer.nanosUntilDue());
        clock.advance(CHUNK_NANOS);
        assertEquals(CHUNK_NANOS, pacer.nanosUntilDue());
        clock.advance(5 * CHUNK_NANOS);
        assertEquals(0, pacer.nanosUntilDue());
    }

    @Test
    public void daysOfAudioDoNotOverflowTheArithmetic() {
        // chunks of one second and a look-ahead of three: one million writes are 11.5 days of audio
        RealTimePacer pacer = new RealTimePacer(clock, BYTES_PER_SECOND, BYTES_PER_SECOND, 3, 2);
        long start = clock.nanoTime();

        for (int i = 0; i < 1_000_000; i++) {
            long wait = pacer.nanosUntilWritable(BYTES_PER_SECOND);
            clock.advance(wait);
            pacer.onWritten(BYTES_PER_SECOND);
        }

        // three chunks went out at once, then one per second
        assertEquals((1_000_000L - 3) * 1_000_000_000L, clock.nanoTime() - start);
        assertEquals(3_000_000_000L, pacer.leadNanos());
        assertEquals(0, pacer.getReanchors());
        assertEquals(0, pacer.getLateChunks());
    }

    @Test
    public void rateThatDoesNotDivideTheNanosecondsStaysExact() {
        // 44100 Hz stereo 16 bit = 176,400 B/s: 882 frames * 4 = 3528 bytes = exactly 20 ms
        RealTimePacer pacer = new RealTimePacer(clock, new PcmFormat(44100, 16, 2), 20);
        long start = clock.nanoTime();

        for (int i = 0; i < 100_000; i++) {
            long wait = pacer.nanosUntilWritable(pacer.getChunkBytes());
            clock.advance(wait);
            pacer.onWritten(pacer.getChunkBytes());
        }

        assertEquals(100_000L * 20_000_000L - 3 * 20_000_000L, clock.nanoTime() - start);
    }

    // ---- any origin of the clock (only differences count)

    private void runWholeScenario(long clockStart) {
        ManualFeedClock origin = new ManualFeedClock(clockStart);
        RealTimePacer pacer = new RealTimePacer(origin, BYTES_PER_SECOND, CHUNK, 3, 2);

        // paced writes: three at once, then one per chunk; the lead stays within the look-ahead
        for (int i = 0; i < 2_000; i++) {
            long wait = pacer.nanosUntilWritable(CHUNK);
            if (i < 3) {
                assertEquals(clockStart + " chunk " + i, 0, wait);
            }
            origin.advance(wait);
            pacer.onWritten(CHUNK);
            assertTrue(clockStart + " lead " + pacer.leadNanos(), pacer.leadNanos() <= LOOK_AHEAD_NANOS);
        }
        assertEquals(clockStart + " elapsed", (2_000 - 3) * CHUNK_NANOS, origin.nanoTime() - clockStart);
        assertEquals(LOOK_AHEAD_NANOS, pacer.getMaxLeadNanos());
        assertEquals(0, pacer.getLateChunks());
        assertEquals(0, pacer.getReanchors());

        // a stall re-anchors, then the writes are bounded again
        origin.advance(3_000_000_000L);
        pacer.onWritten(CHUNK);
        assertEquals(1, pacer.getReanchors());
        int immediately = 0;
        while (pacer.nanosUntilWritable(CHUNK) == 0) {
            pacer.onWritten(CHUNK);
            immediately++;
        }
        assertEquals(2, immediately);
        assertEquals(CHUNK_NANOS, pacer.nanosUntilWritable(CHUNK));
        assertTrue(pacer.nanosUntilDue() >= 0);
    }

    @Test
    public void aClockThatStartsAtZeroWorks() {
        runWholeScenario(0L);
    }

    @Test
    public void aClockThatStartsNegativeAndCrossesZeroWorks() {
        runWholeScenario(-20_000_000_000L);
    }

    @Test
    public void aClockThatWrapsAroundLongMaxValueWorks() {
        // System.nanoTime() may be any value and wraps: the pacer only ever looks at differences
        runWholeScenario(Long.MAX_VALUE - 5_000_000_000L);
        runWholeScenario(Long.MIN_VALUE + 1);
    }

    @Test
    public void theArgumentsAreChecked() {
        assertRefused(0, CHUNK, 3, 2);
        assertRefused(BYTES_PER_SECOND, 0, 3, 2);
        assertRefused(BYTES_PER_SECOND, CHUNK, 0, 2);
        assertRefused(BYTES_PER_SECOND, CHUNK, 3, 0);
        assertFalse(pacer().nanosUntilWritable(CHUNK) < 0);
    }

    private void assertRefused(int bytesPerSecond, int chunkBytes, int lookAhead, int stall) {
        try {
            new RealTimePacer(clock, bytesPerSecond, chunkBytes, lookAhead, stall);
            fail("arguments must be refused");
        } catch (IllegalArgumentException expected) {
            // refused
        }
    }
}
