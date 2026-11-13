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
package de.yaacc.multiroom.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * {@link StreamingSink} on a scripted {@link TrackPort}: the position code that no engine test reaches through the
 * simulated device (review of cycle 4: mutants S3, S4, S6 survived).
 */
public class StreamingSinkTest {

    private static final class ScriptedTrack implements TrackPort {
        final ManualMicroClock clock;
        long frames;      // the frame position of the next timestamp (low 32 bits are reported)
        long timeMicros;  // the time of that position on the clock
        boolean available;
        long head;

        ScriptedTrack(ManualMicroClock clock) {
            this.clock = clock;
        }

        @Override
        public int bufferFrames() {
            return 19200;
        }

        @Override
        public int startThresholdFrames() {
            return 1920;
        }

        @Override
        public int requestStartThresholdFrames(int frames) {
            return 1920;
        }

        @Override
        public int write(byte[] data, int offset, int length) {
            return length;
        }

        @Override
        public void play() {
        }

        @Override
        public void pauseAndFlush() {
        }

        @Override
        public boolean timestamp(long[] out) {
            if (!available) {
                return false;
            }
            out[0] = frames & 0xffffffffL;
            out[1] = timeMicros * 1000L;
            return true;
        }

        @Override
        public long nanoTime() {
            return clock.nowMicros() * 1000L;
        }

        @Override
        public long headPosition() {
            return head & 0xffffffffL;
        }

        @Override
        public int underrunCount() {
            return 0;
        }

        @Override
        public void setGain(float gain) {
        }

        @Override
        public void release() {
        }
    }

    private final ManualMicroClock clock = new ManualMicroClock(50_000_000L);
    private final ScriptedTrack track = new ScriptedTrack(clock);

    private StreamingSink opened() throws Exception {
        StreamingSink sink = new StreamingSink(clock, format -> track);
        sink.open(new SinkFormat(48000, 2));
        sink.start();
        return sink;
    }

    private PlaybackPosition poll(StreamingSink sink) {
        clock.advanceMillis(60); // the sink polls at most every 20 ms
        return sink.position();
    }

    /** S4: the frame position is the low 32 bits of a counter that wraps; the sink counts on across the wrap. */
    @Test
    public void theThirtyTwoBitFramePositionIsCountedOnAcrossTheWrap() throws Exception {
        StreamingSink sink = opened();
        track.available = true;
        track.frames = 0xFFFFFF00L;
        track.timeMicros = clock.nowMicros() + 60_000L;
        PlaybackPosition before = poll(sink);
        assertNotNull(before);
        assertEquals(0xFFFFFF00L, before.getFrames());
        track.frames = 0x1_0000_0100L; // reported as 0x100: the counter has wrapped
        track.timeMicros = clock.nowMicros() + 60_000L;
        PlaybackPosition after = poll(sink);
        assertEquals("frames go on past 2^32", 0x1_0000_0100L, after.getFrames());
        track.frames = 0x1_0000_0100L + 48000;
        track.timeMicros = clock.nowMicros() + 60_000L;
        assertEquals(0x1_0000_0100L + 48000, poll(sink).getFrames());
    }

    /** S3: a timestamp from before the flush says nothing about the new stream. */
    @Test
    public void aTimestampOlderThanTheFlushIsIgnored() throws Exception {
        StreamingSink sink = opened();
        track.available = true;
        track.frames = 5000;
        track.timeMicros = clock.nowMicros() + 60_000L;
        assertNotNull(poll(sink));
        long oldTime = track.timeMicros;
        clock.advanceMillis(500);
        sink.flush();
        sink.start();
        track.timeMicros = oldTime; // the platform still returns the pair of before the flush
        assertNull("a pair older than the flush is no position", poll(sink));
        track.frames = 960;
        track.timeMicros = clock.nowMicros() + 20_000L;
        PlaybackPosition fresh = poll(sink);
        assertNotNull(fresh);
        assertEquals(960, fresh.getFrames());
    }

    /** S6 and S19: a baseline of the old output must not be subtracted from a new one. */
    @Test
    public void theBaselineOfAFlushedOutputIsNotCarriedToTheNextOutput() throws Exception {
        StreamingSink sink = opened();
        track.head = 4410; // a device whose head position is not reset by the flush
        sink.flush();
        // a new output (the server changed the format): its counters start at 0
        track.head = 0;
        sink.open(new SinkFormat(44100, 2));
        sink.start();
        track.available = true;
        track.frames = 1000;
        track.timeMicros = clock.nowMicros() + 60_000L;
        PlaybackPosition position = poll(sink);
        assertNotNull(position);
        assertEquals("not 1000 minus the old 4410", 1000, position.getFrames());
    }

    /** The baseline of a flush is subtracted (a device that does not reset its counters on flush). */
    @Test
    public void theBaselineOfAFlushIsSubtractedFromTheSameOutput() throws Exception {
        StreamingSink sink = opened();
        track.available = true;
        track.head = 4410;
        sink.flush(); // the baseline is the head position at the flush
        sink.start();
        track.frames = 4410 + 960;
        track.timeMicros = clock.nowMicros() + 20_000L;
        assertEquals(960, poll(sink).getFrames());
    }
}
