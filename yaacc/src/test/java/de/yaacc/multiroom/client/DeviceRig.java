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

import de.yaacc.multiroom.protocol.ServerSettingsMessage;
import de.yaacc.multiroom.protocol.WireChunkMessage;

/**
 * Like {@link EngineRig} (a server that records a chunk every 20 ms, a manual clock, the production engine) but the
 * output is a {@link DeviceSimSink}: the production position logic on a configurable simulated device. PCM stream.
 */
final class DeviceRig {

    static final long OFFSET = 7_000_000_000L;
    static final int CHUNK_MS = 20;

    final ManualMicroClock clock = new ManualMicroClock(10_000_000L);
    final ClientFixtures.FakeOffsets offsets = new ClientFixtures.FakeOffsets(OFFSET);
    final DeviceModel model;
    final DeviceSimSink sink;
    final SnapcastClientEngine engine;
    final int rate = 48000;
    final int channels = 2;
    final int bits = 16;
    final int chunkFrames = rate * CHUNK_MS / 1000;
    int bufferMs = 1000;
    int stepMs = 5;
    long nextChunk;
    /** False: the engine thread does not run (a CPU stall) while the chunks still arrive. */
    boolean ticking = true;
    /** False: the server sends nothing (an idle stream); the clock of the chunks goes on. */
    boolean sending = true;
    long serverStart;

    DeviceRig(DeviceModel model) {
        this(model, new ClientEngineConfig());
    }

    DeviceRig(DeviceModel model, ClientEngineConfig config) {
        this.model = model;
        this.sink = new DeviceSimSink(clock, model);
        this.serverStart = clock.nowMicros() + OFFSET;
        this.sink.setIntent(this::targetOf);
        this.engine = new SnapcastClientEngine(config, sink, new DefaultDecoderFactory(null), offsets, clock);
    }

    /** The client time at which the media frame (frames since chunk 0) is meant to be heard. */
    long targetOf(long mediaFrame) {
        return serverStart + mediaFrame * 1_000_000L / rate + bufferMs * 1000L - OFFSET;
    }

    void connect() {
        engine.onServerSettings(new ServerSettingsMessage(bufferMs, 0, false, 100));
        engine.onCodecHeader(ClientFixtures.pcmHeader(rate, channels, bits));
    }

    long serverNow() {
        return clock.nowMicros() + OFFSET;
    }

    void step() {
        while (serverStart + nextChunk * CHUNK_MS * 1000L + CHUNK_MS * 1000L <= serverNow()) {
            if (!sending) {
                nextChunk++;
                continue;
            }
            WireChunkMessage chunk = ClientFixtures.chunk(serverStart + nextChunk * CHUNK_MS * 1000L, nextChunk * chunkFrames,
                    chunkFrames, channels, bits);
            engine.onChunk(chunk);
            nextChunk++;
        }
        if (ticking) {
            engine.tick();
        }
        clock.advanceMillis(stepMs);
    }

    void run(long millis) {
        for (long t = 0; t < millis; t += stepMs) {
            step();
        }
    }

    /** The engine thread is blocked for this long; the chunks arrive meanwhile. */
    void stallEngine(long millis) {
        ticking = false;
        run(millis);
        ticking = true;
    }

    EngineStatus status() {
        return engine.status();
    }

    /** What a listener would hear after the first audio: how much silence, in how many pieces. */
    String report() {
        EngineStatus s = engine.status();
        return sink.outcome() + " | engine: state " + s.state + ", flushes " + s.flushes + " (hard resync "
                + s.flushesHardResync + ", underrun " + s.flushesUnderrun + ", writer behind " + s.flushesWriterBehind
                + "), position " + s.positionSource + ", drift " + s.driftMicros / 1000 + " ms";
    }
}
