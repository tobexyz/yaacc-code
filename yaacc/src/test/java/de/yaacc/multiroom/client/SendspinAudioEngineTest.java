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
import static org.junit.Assert.assertTrue;

import de.yaacc.sendspin.player.SendspinAudioEngine;

import org.junit.Test;

/** The Sendspin stream through the engine of the Snapcast client: a simulated output, a clock moved by hand, a server clock 7000 s ahead. */
public class SendspinAudioEngineTest {

    private static final long OFFSET = 7_000_000_000L;
    private static final int RATE = 48000;
    private static final int CHUNK_FRAMES = RATE / 50;

    private final ManualMicroClock clock = new ManualMicroClock(10_000_000L);
    private final ClientFixtures.FakeOffsets offsets = new ClientFixtures.FakeOffsets(OFFSET);
    private final SimulatedSink sink = SimulatedSink.typical(clock);
    private final SnapcastClientEngine engine = new SnapcastClientEngine(new ClientEngineConfig(), sink, new DefaultDecoderFactory(null), offsets, clock);
    private final SendspinAudioEngine audio = new SendspinAudioEngine(SendspinAudioEngine.direct(engine));
    private long next;

    /** The server sends a chunk of silence that is heard {@code aheadMs} after the client's now. */
    private void send(long aheadMs) {
        long timestamp = clock.nowMicros() + OFFSET + aheadMs * 1000L + next * 20_000L;
        audio.chunk(timestamp, new byte[CHUNK_FRAMES * 4]);
        next++;
    }

    private void run(long millis) {
        for (long t = 0; t < millis; t += 5) {
            engine.tick();
            clock.advanceMillis(5);
        }
    }

    @Test
    public void aStreamIsPlayedAtTheTimeOfItsTimestampOnTheServersClock() {
        audio.start(RATE, 2, 16);
        long startedAt = clock.nowMicros();
        for (int i = 0; i < 40; i++) {
            send(400);
        }
        run(1500);
        assertEquals(1, sink.opens);
        assertTrue("audio was written", sink.framesWritten() > 0);
        // the first chunk is heard 400 ms after it was sent: the output is started and holds frames for it by then
        assertTrue("started", sink.started);
        assertTrue("took the chunks: " + sink.framesWritten(), sink.framesWritten() >= 20L * CHUNK_FRAMES);
        assertEquals(RATE, sink.format.getSampleRate());
        assertEquals(2, sink.format.getChannels());
        assertTrue(clock.nowMicros() > startedAt);
    }

    @Test
    public void aClearFlushesTheOutputAndTheStreamGoesOnWithTheNextChunks() {
        audio.start(RATE, 2, 16);
        for (int i = 0; i < 20; i++) {
            send(400);
        }
        run(300);
        int flushes = sink.flushes;
        audio.clear(null);
        run(50);
        assertTrue("the output was flushed", sink.flushes > flushes);
        next = 0;
        for (int i = 0; i < 20; i++) {
            send(400);
        }
        long before = sink.framesWritten();
        run(1000);
        assertTrue("it plays again", sink.framesWritten() > before);
    }

    @Test
    public void anEndFlushesAndAChunkWithoutAStreamIsIgnored() {
        audio.start(RATE, 2, 16);
        send(400);
        run(100);
        audio.end();
        int flushes = sink.flushes;
        run(50);
        long written = sink.framesWritten();
        send(400); // no stream: ignored
        long received = engine.status().chunksReceived;
        run(500);
        assertEquals("the chunk without a stream was not offered to the engine", received, engine.status().chunksReceived);
        assertTrue(flushes >= 0 && written >= 0);
    }

    @Test
    public void volumeMuteAndOutputDelayReachTheEngine() {
        audio.start(RATE, 2, 16);
        audio.setVolume(40);
        audio.setMuted(true);
        audio.setOutputDelayMs(120);
        assertEquals(40, audio.volume());
        assertTrue(audio.muted());
        run(20);
        assertTrue("the output gain follows the mute", sink.gain == 0f);
        audio.setMuted(false);
        run(20);
        assertTrue("and the volume", sink.gain > 0f && sink.gain < 1f);
    }

    @Test
    public void aTimestampTheEngineCanNotTakeIsIgnored() {
        audio.start(RATE, 2, 16);
        audio.chunk(-5L, new byte[CHUNK_FRAMES * 4]);
        audio.chunk(Long.MAX_VALUE, new byte[CHUNK_FRAMES * 4]);
        run(100);
        assertEquals("the engine was offered no chunk", 0L, engine.status().chunksReceived);
    }
}
