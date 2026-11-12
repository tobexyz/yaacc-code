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

import de.yaacc.multiroom.protocol.CodecHeaderMessage;
import de.yaacc.multiroom.protocol.ServerSettingsMessage;
import de.yaacc.multiroom.protocol.WireChunkMessage;

/**
 * A server, a clock and an output around the engine, stepped by hand: the server records a chunk every 20 ms of
 * server time and the chunk reaches the client when it has been recorded. The server clock is {@link #OFFSET} ahead
 * of the client clock.
 */
final class EngineRig {

    static final long OFFSET = 7_000_000_000L;
    /** The real offset of the server clock; the engine's estimate is {@code offsets.offset}. */
    long trueOffset = OFFSET;
    static final int CHUNK_MS = 20;

    final ManualMicroClock clock = new ManualMicroClock(10_000_000L);
    final ClientFixtures.FakeOffsets offsets = new ClientFixtures.FakeOffsets(OFFSET);
    final SimulatedSink sink;
    final SnapcastClientEngine engine;
    final int rate;
    final int channels;
    final int bits;
    boolean flac;
    private final java.util.ArrayDeque<Object[]> held = new java.util.ArrayDeque<>();
    long serverStart;
    final int chunkFrames;
    int bufferMs = 1000;
    int latencyMs = 0;
    int manualMs = 0;
    long nextChunk;
    boolean sending = true;
    int stepMs = 5;

    static EngineRig typical() {
        return new EngineRig(SimulatedSink::typical, new ClientEngineConfig(), new DefaultDecoderFactory(null), 48000, 2, 16);
    }

    static EngineRig withSpeed(double speed) {
        return new EngineRig(c -> new SimulatedSink(c, 24000, 40_000, speed, 100_000), new ClientEngineConfig(),
                new DefaultDecoderFactory(null), 48000, 2, 16);
    }

    EngineRig(java.util.function.Function<ManualMicroClock, SimulatedSink> sinkMaker, ClientEngineConfig config,
              DecoderFactory decoders, int rate, int channels, int bits) {
        this.sink = sinkMaker.apply(clock);
        this.rate = rate;
        this.channels = channels;
        this.bits = bits;
        this.chunkFrames = rate * CHUNK_MS / 1000;
        this.serverStart = clock.nowMicros() + OFFSET;
        this.flac = false;
        this.engine = new SnapcastClientEngine(config, sink, decoders, offsets, clock);
    }

    /** Settings and header, as the server sends them after the hello. */
    void connect() {
        engine.onServerSettings(new ServerSettingsMessage(bufferMs, latencyMs, false, 100));
        engine.onCodecHeader(header());
    }

    long serverNow() {
        return clock.nowMicros() + trueOffset;
    }

    /** A long run: nothing is kept of the audio, only analysed on the way in. */
    static EngineRig longRun(double speed) {
        EngineRig rig = withSpeed(speed);
        rig.sink.keepWrites = false;
        rig.stepMs = 10;
        return rig;
    }

    /** A flac stream: the decoder of the test passes 16 bit stereo through. */
    static EngineRig flac48() {
        EngineRig rig = new EngineRig(SimulatedSink::typical, new ClientEngineConfig(),
                (h, m) -> new SnapcastClientEngineTest.PassThroughDecoder(), 48000, 2, 16);
        rig.flac = true;
        return rig;
    }

    /** The server restarts: its clock is now {@code newTrueOffset} ahead of ours, the filter notices (a new epoch). */
    void restartServer(long newTrueOffset) {
        trueOffset = newTrueOffset;
        offsets.offset = newTrueOffset;
        offsets.epoch++;
        serverStart = clock.nowMicros() + newTrueOffset;
        nextChunk = 0;
    }

    /** The next chunk reaches the client this long after it was recorded (late chunks are dropped by the scheduler). */
    void delayNextChunk(long millis) {
        delayNext = millis;
    }

    private long delayNext;

    long chunkTimestamp(long chunk) {
        return serverStart + chunk * CHUNK_MS * 1000L;
    }

    /** The client time at which the media frame (frames since chunk 0 of the stream) should be presented. */
    long targetOf(long mediaFrame) {
        return serverStart + mediaFrame * 1_000_000L / rate + bufferMs * 1000L - (latencyMs + manualMs) * 1000L - offsets.offset;
    }

    /** One step: the server delivers what has been recorded, the engine runs, the clock moves. */
    void step() {
        while (sending && chunkTimestamp(nextChunk) + CHUNK_MS * 1000L <= serverNow()) {
            WireChunkMessage chunk = ClientFixtures.chunk(chunkTimestamp(nextChunk), nextChunk * chunkFrames, chunkFrames,
                    channels, bits);
            if (delayNext > 0) {
                held.addLast(new Object[]{clock.nowMicros() + delayNext * 1000L, chunk});
                delayNext = 0;
            } else {
                engine.onChunk(chunk);
            }
            nextChunk++;
        }
        while (!held.isEmpty() && (Long) held.peekFirst()[0] <= clock.nowMicros()) {
            engine.onChunk((WireChunkMessage) held.pollFirst()[1]);
        }
        engine.tick();
        clock.advanceMillis(stepMs);
    }

    void run(long millis) {
        for (long t = 0; t < millis; t += stepMs) {
            step();
        }
    }

    ClientFixtures.Trace trace() {
        return new ClientFixtures.Trace(sink, sink.generation());
    }

    CodecHeaderMessage header() {
        return flac ? ClientFixtures.flacHeaderMessage(rate, channels, bits) : ClientFixtures.pcmHeader(rate, channels, bits);
    }
}
