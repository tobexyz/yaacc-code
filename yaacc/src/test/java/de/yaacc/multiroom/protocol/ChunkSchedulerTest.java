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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

/**
 * What the scheduler does with a chunk. Times are microseconds. The corrected server clock is the client clock plus
 * the offset, a chunk is played at {@code timestamp + bufferMs - latency} on that clock.
 */
public class ChunkSchedulerTest {

    private static final long OFFSET = 1_000_000_000L; // the server clock is 1000 s ahead
    private static final long NOW = 5_000_000L;         // the client clock
    private static final long SERVER_NOW = NOW + OFFSET;

    private static final class FakeOffsets implements OffsetSource {
        volatile boolean synced = true;
        volatile long offset = OFFSET;
        volatile int epoch = 0;

        @Override
        public boolean isSynchronized() {
            return synced;
        }

        @Override
        public long offsetMicros() {
            return offset;
        }

        @Override
        public int epoch() {
            return epoch;
        }
    }

    private FakeOffsets offsets;
    private ManualClock clock;
    private ChunkScheduler scheduler;

    @Before
    public void setUp() {
        offsets = new FakeOffsets();
        clock = new ManualClock(NOW);
        scheduler = new ChunkScheduler(offsets, clock, new ChunkScheduler.Config());
        scheduler.setBufferMs(1000);
        scheduler.setLatencyMs(0);
        scheduler.codecHeaderAccepted();
    }

    private static WireChunkMessage chunkAt(long serverMicros, int size) {
        return new WireChunkMessage((int) Math.floorDiv(serverMicros, 1_000_000L),
                (int) Math.floorMod(serverMicros, 1_000_000L), new byte[size]);
    }

    private static WireChunkMessage chunkAt(long serverMicros) {
        return chunkAt(serverMicros, 100);
    }

    @Test
    public void aFreshChunkIsPlayedOneBufferLaterAndWaitsUntilItIsNear() {
        ChunkScheduler.Decision decision = scheduler.offer(chunkAt(SERVER_NOW));

        assertEquals(ChunkScheduler.Action.WAIT, decision.getAction());
        assertEquals(NOW + 1_000_000L, decision.getPlayAtClientMicros());
        assertEquals(1, scheduler.queuedChunks());
        assertTrue(scheduler.releaseDue().isEmpty());

        clock.advanceMillis(749); // 251 ms to go: still outside the 250 ms release lead
        assertTrue(scheduler.releaseDue().isEmpty());
        clock.advanceMillis(1); // 250 ms to go
        List<ChunkScheduler.ScheduledChunk> released = scheduler.releaseDue();

        assertEquals(1, released.size());
        assertEquals(NOW + 1_000_000L, released.get(0).getPlayAtClientMicros());
        assertEquals(0, scheduler.queuedChunks());
        assertEquals(0, scheduler.queuedBytes());
    }

    @Test
    public void aChunkThatIsDueSoonIsPlayedAtOnceWithItsTime() {
        ChunkScheduler.Decision decision = scheduler.offer(chunkAt(SERVER_NOW - 800_000L));

        assertEquals(ChunkScheduler.Action.PLAY_AT, decision.getAction());
        assertEquals(NOW + 200_000L, decision.getPlayAtClientMicros());
        assertEquals(0, scheduler.queuedChunks());
    }

    @Test
    public void aChunkWhoseTimeHasPassedIsDroppedAndOneExactlyOnTimeIsNot() {
        ChunkScheduler.Decision onTime = scheduler.offer(chunkAt(SERVER_NOW - 1_000_000L));
        ChunkScheduler.Decision late = scheduler.offer(chunkAt(SERVER_NOW - 1_000_001L));

        assertEquals(ChunkScheduler.Action.PLAY_AT, onTime.getAction());
        assertEquals(NOW, onTime.getPlayAtClientMicros());
        assertEquals(ChunkScheduler.Action.DROP_LATE, late.getAction());
        assertEquals(1, scheduler.droppedLate());
    }

    @Test
    public void theLateToleranceIsConfigurable() {
        scheduler = new ChunkScheduler(offsets, clock, new ChunkScheduler.Config().lateToleranceMs(20));
        scheduler.setBufferMs(1000);
        scheduler.codecHeaderAccepted();

        assertEquals(ChunkScheduler.Action.PLAY_AT, scheduler.offer(chunkAt(SERVER_NOW - 1_020_000L)).getAction());
        assertEquals(ChunkScheduler.Action.DROP_LATE, scheduler.offer(chunkAt(SERVER_NOW - 1_020_001L)).getAction());
    }

    @Test
    public void aChunkWaitingInTheQueueThatBecomesLateIsDroppedAtRelease() {
        scheduler.offer(chunkAt(SERVER_NOW));

        clock.advanceMillis(1_001);

        assertTrue(scheduler.releaseDue().isEmpty());
        assertEquals(1, scheduler.droppedLate());
        assertEquals(0, scheduler.queuedChunks());
    }

    @Test
    public void aChunkStampedMoreThanFiveSecondsAheadIsDropped() {
        // bufferMs + 5 s after the corrected clock: 1000 + 5000 ms
        ChunkScheduler.Decision edge = scheduler.offer(chunkAt(SERVER_NOW + 5_000_000L));
        ChunkScheduler.Decision beyond = scheduler.offer(chunkAt(SERVER_NOW + 5_000_001L));

        assertEquals(ChunkScheduler.Action.WAIT, edge.getAction());
        assertEquals(ChunkScheduler.Action.DROP_TOO_FAR_AHEAD, beyond.getAction());
        assertEquals(1, scheduler.droppedTooFarAhead());
        assertEquals(1, scheduler.queuedChunks());
    }

    @Test
    public void theLimitFollowsTheBufferAndIsNotMovedByTheClientLatency() {
        scheduler.setBufferMs(10_000);
        scheduler.setLatencyMs(-10_000); // the user delays this client by 10 s: a fresh chunk must still be accepted

        ChunkScheduler.Decision fresh = scheduler.offer(chunkAt(SERVER_NOW));

        assertEquals(ChunkScheduler.Action.WAIT, fresh.getAction());
        assertEquals(NOW + 20_000_000L, fresh.getPlayAtClientMicros());
        assertEquals(ChunkScheduler.Action.DROP_TOO_FAR_AHEAD, scheduler.offer(chunkAt(SERVER_NOW + 5_000_001L)).getAction());
    }

    @Test
    public void aPositiveLatencyPlaysEarlier() {
        scheduler.setLatencyMs(300);

        ChunkScheduler.Decision decision = scheduler.offer(chunkAt(SERVER_NOW));

        assertEquals(NOW + 700_000L, decision.getPlayAtClientMicros());
    }

    @Test
    public void bufferAndLatencyAreClampedToTheLimitsOfTheServerSettings() {
        scheduler.setBufferMs(Integer.MAX_VALUE); // 10 000
        scheduler.setLatencyMs(Integer.MAX_VALUE); // 10 000
        ChunkScheduler.Decision cancelled = scheduler.offer(chunkAt(SERVER_NOW));

        scheduler.setBufferMs(Integer.MIN_VALUE); // 0
        scheduler.setLatencyMs(Integer.MIN_VALUE); // -10 000
        ChunkScheduler.Decision delayed = scheduler.offer(chunkAt(SERVER_NOW));

        assertEquals(ChunkScheduler.Action.PLAY_AT, cancelled.getAction());
        assertEquals(NOW, cancelled.getPlayAtClientMicros());
        assertEquals(ChunkScheduler.Action.WAIT, delayed.getAction());
        assertEquals(NOW + 10_000_000L, delayed.getPlayAtClientMicros());
    }

    @Test
    public void nothingIsReleasedBeforeTheCodecHeaderWasAccepted() {
        scheduler.reset(); // a new connection: no header yet

        ChunkScheduler.Decision dueNow = scheduler.offer(chunkAt(SERVER_NOW - 900_000L));
        scheduler.offer(chunkAt(SERVER_NOW - 5_000_000L));

        assertEquals(ChunkScheduler.Action.WAIT, dueNow.getAction());
        assertEquals(2, scheduler.queuedChunks());
        assertTrue(scheduler.releaseDue().isEmpty());

        scheduler.codecHeaderAccepted();
        List<ChunkScheduler.ScheduledChunk> released = scheduler.releaseDue();

        assertEquals("only the chunk that is still on time", 1, released.size());
        assertEquals(NOW + 100_000L, released.get(0).getPlayAtClientMicros());
        assertEquals(1, scheduler.droppedLate());
    }

    @Test
    public void nothingIsPlayedWhileTheClockIsNotSynchronised() {
        offsets.synced = false;

        ChunkScheduler.Decision decision = scheduler.offer(chunkAt(SERVER_NOW - 900_000L));

        assertEquals(ChunkScheduler.Action.WAIT, decision.getAction());
        assertTrue(scheduler.releaseDue().isEmpty());
        assertEquals(1, scheduler.queuedChunks());

        offsets.synced = true;
        assertEquals(1, scheduler.releaseDue().size());
    }

    @Test
    public void chunksAreReleasedInTheOrderOfTheirTimestamps() {
        scheduler.offer(chunkAt(SERVER_NOW + 40_000L));
        scheduler.offer(chunkAt(SERVER_NOW));
        scheduler.offer(chunkAt(SERVER_NOW + 20_000L));
        clock.advanceMillis(900);

        List<ChunkScheduler.ScheduledChunk> released = scheduler.releaseDue();

        assertEquals(3, released.size());
        assertEquals(SERVER_NOW, released.get(0).getChunk().timestampMicros());
        assertEquals(SERVER_NOW + 20_000L, released.get(1).getChunk().timestampMicros());
        assertEquals(SERVER_NOW + 40_000L, released.get(2).getChunk().timestampMicros());
    }

    @Test
    public void theQueueIsBoundedByCount() {
        scheduler = new ChunkScheduler(offsets, clock, new ChunkScheduler.Config().maxQueuedChunks(3));
        scheduler.setBufferMs(1000);
        scheduler.codecHeaderAccepted();

        for (int i = 0; i < 3; i++) {
            assertEquals(ChunkScheduler.Action.WAIT, scheduler.offer(chunkAt(SERVER_NOW + i * 20_000L)).getAction());
        }
        ChunkScheduler.Decision full = scheduler.offer(chunkAt(SERVER_NOW + 60_000L));

        assertEquals(ChunkScheduler.Action.DROP_QUEUE_FULL, full.getAction());
        assertEquals(3, scheduler.queuedChunks());
        assertEquals(1, scheduler.droppedQueueFull());

        clock.advanceMillis(900);
        assertEquals(3, scheduler.releaseDue().size());
        assertEquals(ChunkScheduler.Action.WAIT, scheduler.offer(chunkAt(SERVER_NOW + 2_000_000L)).getAction());
    }

    @Test
    public void theQueueIsBoundedByBytesNotOnlyByCount() {
        scheduler = new ChunkScheduler(offsets, clock, new ChunkScheduler.Config().maxQueuedBytes(1000));
        scheduler.setBufferMs(1000);
        scheduler.codecHeaderAccepted();

        assertEquals(ChunkScheduler.Action.WAIT, scheduler.offer(chunkAt(SERVER_NOW, 600)).getAction());
        assertEquals(ChunkScheduler.Action.DROP_QUEUE_FULL, scheduler.offer(chunkAt(SERVER_NOW + 20_000L, 600)).getAction());
        assertEquals(ChunkScheduler.Action.WAIT, scheduler.offer(chunkAt(SERVER_NOW + 20_000L, 400)).getAction());
        assertEquals(1000, scheduler.queuedBytes());
        assertEquals(ChunkScheduler.Action.DROP_QUEUE_FULL, scheduler.offer(chunkAt(SERVER_NOW + 40_000L, 1)).getAction());
        assertEquals(ChunkScheduler.Action.DROP_QUEUE_FULL, scheduler.offer(chunkAt(SERVER_NOW + 40_000L, 5000)).getAction());
    }

    @Test
    public void aFloodOfEarlyChunksNeverGrowsTheQueueBeyondItsBounds() {
        for (int i = 0; i < 100_000; i++) {
            scheduler.offer(chunkAt(SERVER_NOW + (i % 4000) * 1000L, 1000));
        }

        assertTrue(scheduler.queuedChunks() <= ChunkScheduler.Config.DEFAULT_MAX_QUEUED_CHUNKS);
        assertTrue(scheduler.queuedBytes() <= ChunkScheduler.Config.DEFAULT_MAX_QUEUED_BYTES);
        assertTrue(scheduler.droppedQueueFull() > 0);
    }

    @Test
    public void aResynchronisationEmptiesTheQueueBecauseTheTimestampsBelongToTheOldClock() {
        scheduler.offer(chunkAt(SERVER_NOW));
        scheduler.offer(chunkAt(SERVER_NOW + 20_000L));
        assertEquals(2, scheduler.queuedChunks());

        offsets.epoch++;
        offsets.offset = OFFSET + 3_000_000_000L;

        assertTrue(scheduler.releaseDue().isEmpty());
        assertEquals(0, scheduler.queuedChunks());
        assertEquals(2, scheduler.flushed());
    }

    @Test
    public void resetEmptiesTheQueueAndAsksForANewHeader() {
        scheduler.offer(chunkAt(SERVER_NOW));

        scheduler.reset();

        assertEquals(0, scheduler.queuedChunks());
        assertEquals(0, scheduler.queuedBytes());
        assertEquals(ChunkScheduler.Action.WAIT, scheduler.offer(chunkAt(SERVER_NOW - 900_000L)).getAction());
        assertTrue(scheduler.releaseDue().isEmpty());
    }

    @Test
    public void extremeTimestampsAndOffsetsAreDroppedWithoutOverflow() {
        assertEquals(ChunkScheduler.Action.DROP_TOO_FAR_AHEAD,
                scheduler.offer(new WireChunkMessage(Integer.MAX_VALUE, 999_999, new byte[1])).getAction());
        assertEquals(ChunkScheduler.Action.DROP_LATE,
                scheduler.offer(new WireChunkMessage(Integer.MIN_VALUE, -999_999, new byte[1])).getAction());

        offsets.offset = Long.MAX_VALUE / 2;
        scheduler.offer(new WireChunkMessage(Integer.MAX_VALUE, 0, new byte[1]));
        offsets.offset = Long.MIN_VALUE / 2;
        scheduler.offer(new WireChunkMessage(Integer.MIN_VALUE, 0, new byte[1]));
        offsets.offset = Long.MAX_VALUE;
        scheduler.offer(new WireChunkMessage(0, 0, new byte[1]));
        offsets.offset = Long.MIN_VALUE;
        scheduler.offer(new WireChunkMessage(0, 0, new byte[1]));
        scheduler.releaseDue();
    }
}
