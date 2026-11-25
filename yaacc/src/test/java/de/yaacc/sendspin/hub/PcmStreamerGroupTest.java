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
package de.yaacc.sendspin.hub;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** A group of players on one timeline: the same timestamps for everybody, one lead, members that come, go and fail. */
public class PcmStreamerGroupTest {

    private static final int RATE = 44100;
    private static final int CHUNK_BYTES = RATE / 50 * 4;

    private static final class FakeClock implements HubClock {
        long now = 1_000_000L;

        @Override
        public long nowMicros() {
            return now;
        }

        @Override
        public void sleepMicros(long micros) {
            now += Math.max(micros, 1);
        }
    }

    private static final class Player implements PcmStreamer.Output, PcmStreamer.Timing {
        final FakeClock clock;
        final List<String> kinds = new ArrayList<>();
        final List<Long> timestamps = new ArrayList<>();
        final List<Long> sendAheads = new ArrayList<>();
        final List<Long> sentAt = new ArrayList<>();
        boolean fail;
        long lead = 500_000L;
        long capacity = 32_000_000L;

        Player(FakeClock clock) {
            this.clock = clock;
        }

        private void check() throws IOException {
            if (fail) {
                throw new IOException("broken");
            }
        }

        @Override
        public void streamStart(long serverTransmittedMicros, int sampleRate) throws IOException {
            check();
            kinds.add("start");
        }

        @Override
        public void streamClear(long serverTransmittedMicros) throws IOException {
            check();
            kinds.add("clear");
        }

        @Override
        public void streamEnd() throws IOException {
            check();
            kinds.add("end");
        }

        @Override
        public void audio(long timestampMicros, long sendAheadMicros, byte[] pcm, int offset, int length) throws IOException {
            check();
            kinds.add("audio");
            timestamps.add(timestampMicros);
            sendAheads.add(sendAheadMicros);
            sentAt.add(clock.now);
        }

        @Override
        public long leadMicros() {
            return lead;
        }

        @Override
        public long outputDelayMicros() {
            return 0;
        }

        @Override
        public long capacityBytes() {
            return capacity;
        }
    }

    private final FakeClock clock = new FakeClock();
    private final PcmStreamer group = new PcmStreamer(clock, RATE);

    private Player join() {
        Player player = new Player(clock);
        group.addMember(player, player);
        return player;
    }

    private void play(int chunks) {
        for (int i = 0; i < chunks; i++) {
            assertTrue(group.write(new byte[CHUNK_BYTES], 0, CHUNK_BYTES));
        }
    }

    @Test
    public void everyMemberGetsTheSameChunksWithTheSameTimestamps() {
        Player a = join();
        Player b = join();
        play(30);
        assertEquals(30, a.timestamps.size());
        assertEquals("the same timeline: the same timestamps", a.timestamps, b.timestamps);
        assertEquals(Arrays.asList("start"), a.kinds.subList(0, 1));
        assertEquals(Arrays.asList("start"), b.kinds.subList(0, 1));
        for (int i = 1; i < a.timestamps.size(); i++) {
            assertEquals(20_000L, a.timestamps.get(i) - a.timestamps.get(i - 1));
        }
    }

    @Test
    public void theGroupSendsOneLeadAheadAndTheLeadIsTheLargestOfTheMembers() {
        Player quick = join();
        quick.lead = 300_000L;
        Player slow = join();
        slow.lead = 800_000L;
        play(10);
        for (int i = 0; i < 10; i++) {
            assertEquals(800_000L, quick.timestamps.get(i) - quick.sentAt.get(i));
            assertEquals(quick.sentAt.get(i), slow.sentAt.get(i));
        }
    }

    @Test
    public void aMemberWithASmallBufferHoldsTheWholeGroupBack() {
        Player big = join();
        Player small = join();
        small.capacity = 4 * (CHUNK_BYTES + 13); // four chunks, 80 ms: less than the lead of 500 ms
        long begin = clock.now;
        play(50);
        assertEquals(50, big.timestamps.size());
        assertEquals(big.timestamps, small.timestamps);
        // the small buffer never holds more than four chunks: at most 4 chunks ahead of the first one that is not played yet
        long worstAhead = 0;
        for (int i = 0; i < 50; i++) {
            worstAhead = Math.max(worstAhead, small.sendAheads.get(i));
        }
        assertTrue("sent no more than what fits: " + worstAhead, worstAhead <= 500_000L);
        assertTrue(clock.now > begin);
    }

    @Test
    public void aMemberWhoseOutputFailsLeavesAndTheOthersGoOn() {
        Player a = join();
        Player b = join();
        play(5);
        b.fail = true;
        play(5);
        assertEquals(10, a.timestamps.size());
        assertEquals(5, b.timestamps.size());
        assertFalse("one member is left", group.hasFailed());
        a.fail = true;
        assertFalse(group.write(new byte[CHUNK_BYTES], 0, CHUNK_BYTES));
        assertTrue("no member is left", group.hasFailed());
    }

    @Test
    public void aMemberThatJoinsWhileTheGroupPlaysIsStartedAndPlaysInSync() {
        Player a = join();
        play(10);
        Player b = join();
        play(10);
        assertEquals("start", b.kinds.get(0));
        assertEquals(10, b.timestamps.size());
        // every chunk the newcomer got is a chunk of the group's timeline, with the same timestamp
        for (int i = 0; i < 10; i++) {
            assertEquals(a.timestamps.get(10 + i), b.timestamps.get(i));
        }
        assertEquals("the first member was not started again", 1, Collections.frequency(a.kinds, "start"));
    }

    @Test
    public void aNewcomerThatNeedsMoreLeadMovesTheTimelineForEverybody() {
        Player a = join();
        play(10);
        Player b = join();
        b.lead = 2_000_000L;
        play(5);
        long last = a.timestamps.get(9);
        long first = a.timestamps.get(10);
        assertTrue("the timeline moved forward: a gap, no overlap", first >= last + 20_000L);
        assertEquals(a.timestamps.subList(10, 15), b.timestamps);
        assertEquals(2_000_000L, a.timestamps.get(10) - a.sentAt.get(10), 20_000L);
    }

    @Test
    public void aMemberThatIsRemovedGetsStreamEndAndTheOthersGoOn() {
        Player a = join();
        Player b = new Player(clock);
        PcmStreamer.Member member = group.addMember(b, b);
        play(5);
        group.removeMember(member);
        assertEquals("end", b.kinds.get(b.kinds.size() - 1));
        play(5);
        assertEquals(5, b.timestamps.size());
        assertEquals(10, a.timestamps.size());
        group.removeMember(member); // twice is harmless
    }

    @Test
    public void aDiscardClearsEveryMemberAndTheTimelineStartsAgainForAll() {
        Player a = join();
        Player b = join();
        play(10);
        group.discard();
        play(5);
        assertTrue(a.kinds.contains("clear"));
        assertTrue(b.kinds.contains("clear"));
        assertEquals(a.timestamps, b.timestamps);
        long before = a.timestamps.get(9);
        long after = a.timestamps.get(10);
        assertTrue("restarts one lead after now", after - clock.now < 600_000L && after > before - 1);
    }

    @Test
    public void endEndsEveryMemberAndBeginStartsThemAllAgain() {
        Player a = join();
        Player b = join();
        play(5);
        group.end();
        assertEquals("end", a.kinds.get(a.kinds.size() - 1));
        assertEquals("end", b.kinds.get(b.kinds.size() - 1));
        assertTrue(group.write(new byte[CHUNK_BYTES], 0, CHUNK_BYTES)); // refused silently while ended
        assertEquals(5, a.timestamps.size());
        group.begin();
        play(5);
        assertEquals(10, a.timestamps.size());
        assertEquals(2, Collections.frequency(a.kinds, "start"));
        assertEquals(a.timestamps, b.timestamps);
    }

    /** An output whose write blocks until it is aborted: a player whose socket is full. */
    private static final class Stuck implements PcmStreamer.Output, PcmStreamer.Timing {
        final java.util.concurrent.CountDownLatch inWrite = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch aborted = new java.util.concurrent.CountDownLatch(1);

        @Override
        public void streamStart(long t, int rate) {
        }

        @Override
        public void streamClear(long t) {
        }

        @Override
        public void streamEnd() {
        }

        @Override
        public void audio(long t, long ahead, byte[] pcm, int offset, int length) throws IOException {
            inWrite.countDown();
            try {
                if (!aborted.await(20, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new IOException("never aborted");
                }
            } catch (InterruptedException e) {
                throw new IOException("interrupted");
            }
            throw new IOException("the socket was closed");
        }

        @Override
        public void abort() {
            aborted.countDown();
        }

        @Override
        public long leadMicros() {
            return 0;
        }

        @Override
        public long outputDelayMicros() {
            return 0;
        }

        @Override
        public long capacityBytes() {
            return 32_000_000L;
        }
    }

    @Test(timeout = 15000)
    public void anEndWhileAWriteIsStuckBreaksTheWriteInsteadOfWaitingForIt() throws Exception {
        Stuck stuck = new Stuck();
        PcmStreamer streamer = new PcmStreamer(HubClock.SYSTEM, RATE);
        streamer.setStuckMillis(100);
        streamer.addMember(stuck, stuck);
        Thread writer = new Thread(() -> streamer.write(new byte[CHUNK_BYTES], 0, CHUNK_BYTES));
        writer.start();
        assertTrue(stuck.inWrite.await(5, java.util.concurrent.TimeUnit.SECONDS));
        Thread.sleep(250); // the write is stuck for longer than the limit
        streamer.end(); // would wait for the lock of the stuck write without the abort
        assertEquals("the output was told to break the write", 0, stuck.aborted.getCount());
        writer.join(5000);
        assertFalse(writer.isAlive());
    }

    @Test(timeout = 15000)
    public void aStuckMemberIsAbortedAndTheOthersAreLeftAlone() throws Exception {
        Stuck stuck = new Stuck();
        final java.util.concurrent.atomic.AtomicInteger healthyAborts = new java.util.concurrent.atomic.AtomicInteger();
        PcmStreamer.Output healthy = new PcmStreamer.Output() {
            @Override
            public void streamStart(long t, int rate) {
            }

            @Override
            public void streamClear(long t) {
            }

            @Override
            public void streamEnd() {
            }

            @Override
            public void audio(long t, long ahead, byte[] pcm, int offset, int length) {
            }

            @Override
            public void abort() {
                healthyAborts.incrementAndGet();
            }
        };
        PcmStreamer streamer = new PcmStreamer(HubClock.SYSTEM, RATE);
        streamer.setStuckMillis(100);
        streamer.addMember(healthy, new Player(clock));
        streamer.addMember(stuck, stuck);
        Thread writer = new Thread(() -> streamer.write(new byte[CHUNK_BYTES], 0, CHUNK_BYTES));
        writer.start();
        assertTrue(stuck.inWrite.await(5, java.util.concurrent.TimeUnit.SECONDS));
        Thread.sleep(250);
        streamer.end();
        assertEquals("the stuck one was told", 0, stuck.aborted.getCount());
        assertEquals("the healthy one keeps its connection", 0, healthyAborts.get());
        writer.join(5000);
    }

    @Test
    public void aPlayerThatGaveNoBufferSizeIsNotLimitedAndNeverWaitsForItsOwnChunks() {
        Player unlimited = join();
        unlimited.capacity = 0;
        play(50);
        assertEquals(50, unlimited.timestamps.size());
        assertEquals("no waits for the buffer", 0, group.statistics().capacityWaits);
    }

    @Test
    public void aGroupWithoutMembersFailsOnTheFirstWrite() {
        assertFalse(group.write(new byte[CHUNK_BYTES], 0, CHUNK_BYTES));
        assertTrue(group.hasFailed());
        Player later = join(); // a member that comes later makes it work again
        assertTrue(group.write(new byte[CHUNK_BYTES], 0, CHUNK_BYTES));
        assertEquals("the chunk that waited and the new one", 2, later.timestamps.size());
    }
}
