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
    static final int CHUNK_MS = 20;

    final ManualMicroClock clock = new ManualMicroClock(10_000_000L);
    final ClientFixtures.FakeOffsets offsets = new ClientFixtures.FakeOffsets(OFFSET);
    final SimulatedSink sink;
    final SnapcastClientEngine engine;
    final int rate;
    final int channels;
    final int bits;
    final long serverStart;
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
        this.engine = new SnapcastClientEngine(config, sink, decoders, offsets, clock);
    }

    /** Settings and header, as the server sends them after the hello. */
    void connect() {
        engine.onServerSettings(new ServerSettingsMessage(bufferMs, latencyMs, false, 100));
        engine.onCodecHeader(ClientFixtures.pcmHeader(rate, channels, bits));
    }

    long serverNow() {
        return clock.nowMicros() + OFFSET;
    }

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
            engine.onChunk(chunk);
            nextChunk++;
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
        return ClientFixtures.pcmHeader(rate, channels, bits);
    }
}
