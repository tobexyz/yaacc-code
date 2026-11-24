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
import java.util.List;

/** The pacing, the timeline and the buffer accounting of a stream, on a simulated clock. */
public class PcmStreamerTest {

    private static final int RATE = 44100;
    private static final int CHUNK_FRAMES = RATE / 50; // 20 ms; 882 frames
    private static final int CHUNK_BYTES = CHUNK_FRAMES * 4;

    /** A clock that only moves when somebody sleeps. */
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

    private static final class Event {
        final String kind;
        final long time;      // when it was sent
        final long timestamp; // for audio: when it must play
        final long sendAhead;

        Event(String kind, long time, long timestamp, long sendAhead) {
            this.kind = kind;
            this.time = time;
            this.timestamp = timestamp;
            this.sendAhead = sendAhead;
        }
    }

    private static final class Recorder implements PcmStreamer.Output {
        final FakeClock clock;
        final List<Event> events = new ArrayList<>();
        boolean failNext;

        Recorder(FakeClock clock) {
            this.clock = clock;
        }

        private void check() throws IOException {
            if (failNext) {
                throw new IOException("broken");
            }
        }

        @Override
        public void streamStart(long serverTransmittedMicros, int sampleRate) throws IOException {
            check();
            assertEquals(RATE, sampleRate);
            events.add(new Event("start", clock.now, serverTransmittedMicros, 0));
        }

        @Override
        public void streamClear(long serverTransmittedMicros) throws IOException {
            check();
            events.add(new Event("clear", clock.now, serverTransmittedMicros, 0));
        }

        @Override
        public void streamEnd() throws IOException {
            check();
            events.add(new Event("end", clock.now, 0, 0));
        }

        @Override
        public void audio(long timestampMicros, long sendAheadMicros, byte[] pcm, int offset, int length) throws IOException {
            check();
            assertEquals(CHUNK_BYTES, length);
            events.add(new Event("audio", clock.now, timestampMicros, sendAheadMicros));
        }

        List<Event> audio() {
            List<Event> out = new ArrayList<>();
            for (Event e : events) {
                if (e.kind.equals("audio")) {
                    out.add(e);
                }
            }
            return out;
        }

        List<String> kinds() {
            List<String> out = new ArrayList<>();
            for (Event e : events) {
                out.add(e.kind);
            }
            return out;
        }
    }

    private static final class FixedTiming implements PcmStreamer.Timing {
        long lead = 500_000L;
        long delay = 0;
        long capacity = 32_000_000L;

        @Override
        public long leadMicros() {
            return lead;
        }

        @Override
        public long outputDelayMicros() {
            return delay;
        }

        @Override
        public long capacityBytes() {
            return capacity;
        }
    }

    private final FakeClock clock = new FakeClock();
    private final Recorder out = new Recorder(clock);
    private final FixedTiming timing = new FixedTiming();
    private final PcmStreamer streamer = new PcmStreamer(out, clock, timing, RATE);

    private void play(int chunks) {
        byte[] audio = new byte[CHUNK_BYTES];
        for (int i = 0; i < chunks; i++) {
            assertTrue(streamer.write(audio, 0, audio.length));
        }
    }

    @Test
    public void theFirstWriteStartsTheStreamAndTheTimelineRunsInChunksOf20Ms() {
        long before = clock.now;
        play(10);
        assertEquals("start", out.events.get(0).kind);
        assertEquals(before, out.events.get(0).timestamp);
        List<Event> audio = out.audio();
        assertEquals(10, audio.size());
        assertEquals("the first chunk plays one lead after the start", before + 500_000L, audio.get(0).timestamp);
        for (int i = 1; i < audio.size(); i++) {
            assertEquals(20_000L, audio.get(i).timestamp - audio.get(i - 1).timestamp);
        }
    }

    @Test
    public void aChunkIsSentWhenItIsOneLeadAheadAndNotBefore() {
        play(100);
        for (Event e : out.audio()) {
            assertEquals("each chunk goes out one lead before it plays", 500_000L, e.timestamp - e.time);
            assertEquals(500_000L, e.sendAhead);
        }
        // 100 chunks are 2 s of audio, and the clock moved by 2 s less the first lead that was sent at once: real time pacing
        assertEquals(1_000_000L + 100 * 20_000L - 20_000L, clock.now, 40_000L);
    }

    @Test
    public void thePlayerNeverHoldsMoreThanTheLeadOfAudio() {
        play(50);
        // a chunk sent at time t plays at t + lead: what is in the player's buffer at any send is the lead, never more
        for (Event e : out.audio()) {
            assertTrue(e.timestamp - e.time <= 500_000L);
        }
    }

    @Test
    public void aDiscardClearsAndTheTimelineRestartsOneLeadAfterNow() {
        play(10);
        clock.now += 100_000L;
        streamer.discard();
        assertEquals("clear", out.events.get(out.events.size() - 1).kind);
        long cleared = clock.now;
        play(1);
        Event first = out.audio().get(out.audio().size() - 1);
        assertEquals(cleared + 500_000L, first.timestamp);
        assertEquals("no stream/start for a clear", 1, countKind("start"));
    }

    private int countKind(String kind) {
        int n = 0;
        for (String k : out.kinds()) {
            if (k.equals(kind)) {
                n++;
            }
        }
        return n;
    }

    @Test
    public void aDiscardWithoutAStreamSendsNothing() {
        streamer.discard();
        assertTrue(out.events.isEmpty());
    }

    @Test
    public void endStopsTheStreamAndRefusesAudioUntilBegin() {
        play(5);
        streamer.end();
        assertEquals("end", out.events.get(out.events.size() - 1).kind);
        assertFalse(streamer.isStreaming());
        int sent = out.events.size();
        play(5); // the audio of a stopped player is dropped without an error
        assertEquals(sent, out.events.size());
        streamer.begin();
        play(2);
        assertEquals("a new stream starts with the next write", 2, countKind("start"));
        assertEquals(7, out.audio().size());
    }

    @Test
    public void aProducerThatFellBehindStartsTheTimelineAgainWithoutAMessage() {
        play(5);
        clock.now += 3_000_000L; // the decoder stalled for 3 s
        play(1);
        List<Event> audio = out.audio();
        Event late = audio.get(audio.size() - 1);
        assertEquals("a new timeline one lead ahead of now", clock.now + 500_000L, late.timestamp);
        assertEquals(1, streamer.statistics().reanchors);
        assertEquals("no stream/clear for a gap", 0, countKind("clear"));
    }

    @Test
    public void theCapacityOfThePlayerIsRespected() {
        timing.capacity = 4 * (13 + CHUNK_BYTES); // four chunks
        timing.lead = 2_000_000L;
        play(40);
        // at every send the bytes of chunks that had not finished playing stayed within the capacity
        List<Event> audio = out.audio();
        for (int i = 0; i < audio.size(); i++) {
            int inBuffer = 0;
            for (int j = 0; j < i; j++) {
                if (audio.get(j).timestamp + 20_000L > audio.get(i).time) {
                    inBuffer++;
                }
            }
            assertTrue("chunk " + i + " found " + inBuffer + " chunks in the buffer", inBuffer < 4);
        }
        assertTrue(streamer.statistics().capacityWaits > 0);
    }

    @Test
    public void theOutputDelayKeepsAChunkInThePlayersBufferLonger() {
        timing.delay = 0;
        timing.capacity = 3 * (13 + CHUNK_BYTES);
        timing.lead = 1_000_000L;
        play(20);
        long waitsWithoutDelay = streamer.statistics().capacityWaits;
        assertTrue(waitsWithoutDelay > 0);
    }

    @Test
    public void aChangedLeadIsPickedUpByTheNextTimeline() {
        play(3);
        timing.lead = 800_000L;
        streamer.discard();
        long at = clock.now;
        play(1);
        assertEquals(at + 800_000L, out.audio().get(out.audio().size() - 1).timestamp);
    }

    @Test
    public void aFailedOutputEndsTheStreamerForGood() {
        play(2);
        out.failNext = true;
        assertFalse(streamer.write(new byte[CHUNK_BYTES], 0, CHUNK_BYTES));
        assertTrue(streamer.hasFailed());
        out.failNext = false;
        assertFalse("it does not recover by itself", streamer.write(new byte[CHUNK_BYTES], 0, CHUNK_BYTES));
    }

    @Test
    public void writesOfAnyLengthAreCutIntoWholeChunks() {
        byte[] audio = new byte[CHUNK_BYTES * 3 + 100];
        assertTrue(streamer.write(audio, 0, 100));
        assertTrue(out.audio().isEmpty());
        assertTrue(streamer.write(audio, 100, CHUNK_BYTES * 3));
        assertEquals("three whole chunks, 100 bytes still waiting", 3, out.audio().size());
        assertTrue(streamer.write(audio, 0, CHUNK_BYTES - 100));
        assertEquals(4, out.audio().size());
    }

    @Test
    public void aDiscardDropsTheBytesOfAnUnfinishedChunk() {
        assertTrue(streamer.write(new byte[CHUNK_BYTES], 0, CHUNK_BYTES)); // start the stream
        assertTrue(streamer.write(new byte[100], 0, 100));
        streamer.discard();
        assertTrue(streamer.write(new byte[CHUNK_BYTES - 100], 0, CHUNK_BYTES - 100));
        assertEquals("the 100 old bytes were dropped: no chunk is complete yet", 1, out.audio().size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void aSampleRateThatDoesNotGiveWholeChunksIsRefused() {
        new PcmStreamer(out, clock, timing, 44123);
    }
}
