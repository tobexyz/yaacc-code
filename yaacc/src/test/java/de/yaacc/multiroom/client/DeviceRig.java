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
    /** True: the stream is a 440 Hz sine instead of the frame markers. */
    boolean sine;
    /** Each chunk's timestamp is off by up to this many microseconds (either way); the audio is not. */
    long chunkJitterMicros;
    private final java.util.Random random = new java.util.Random(42);

    final ManualMicroClock clock = new ManualMicroClock(10_000_000L);
    final ClientFixtures.FakeOffsets offsets = new ClientFixtures.FakeOffsets(OFFSET);
    final DeviceModel model;
    final DeviceSimSink sink;
    final SnapcastClientEngine engine;
    final int rate;
    final int channels = 2;
    final int bits = 16;
    final int chunkFrames;
    int bufferMs = 1000;
    int stepMs = 5;
    long nextChunk;
    /** False: the engine thread does not run (a CPU stall) while the chunks still arrive. */
    boolean ticking = true;
    /** False: the server sends nothing (an idle stream); the clock of the chunks goes on. */
    boolean sending = true;
    long serverStart;
    /** A periodic job on the engine thread that takes {@code costMs} every {@code periodMs} (nothing else runs meanwhile). */
    private final java.util.List<long[]> jobs = new java.util.ArrayList<>(); // {period ms, cost ms, next due ms}

    void addJob(long periodMs, long costMs) {
        jobs.add(new long[]{periodMs, costMs, clock.nowMicros() / 1000 + periodMs});
    }

    /**
     * &gt; 0 (flac only): the encoder collects the pcm chunks into blocks of this many frames (the owner's stream decodes to
     * blocks of 1152) and emits a chunk when a block is complete, stamped with the start of the pcm chunk that completed
     * it: the chunks are then spaced 20, 20, 20, 20, 40 ms, each up to a block later than its first sample.
     */
    int flacBlockFrames;
    /** 0: the decoder hands back what it was given; 1: it holds one chunk back; 2: it alternates between holding and a burst of two. */
    int decoderLag;
    private long emittedMedia;
    private long encoderPending;
    private boolean wasSending = true;

    /** True: a flac stream (the decoder of the tests passes the 16 bit samples through). Set before connect(). */
    boolean flac;

    DeviceRig(DeviceModel model) {
        this(model, new ClientEngineConfig());
    }

    DeviceRig(DeviceModel model, ClientEngineConfig config) {
        this(model, config, 48000, 960);
    }

    DeviceRig(DeviceModel model, ClientEngineConfig config, int rate, int chunkFrames) {
        this.rate = rate;
        this.chunkFrames = chunkFrames;
        this.model = model;
        this.sink = new DeviceSimSink(clock, model);
        this.serverStart = clock.nowMicros() + OFFSET;
        this.sink.setIntent(this::targetOf);
        DefaultDecoderFactory pcmFactory = new DefaultDecoderFactory(null);
        this.engine = new SnapcastClientEngine(config, sink,
                (h, m) -> h.getCodec() == de.yaacc.multiroom.protocol.Codec.PCM ? pcmFactory.create(h, m)
                        : new LaggingDecoder(() -> decoderLag), offsets, clock);
    }

    /** The server time of the first frame of a chunk, exact (the server computes it from the frame count). */
    long chunkTime(long chunk) {
        return serverStart + chunk * chunkFrames * 1_000_000L / rate;
    }

    /** The client time at which the media frame (frames since chunk 0) is meant to be heard. */
    long targetOf(long mediaFrame) {
        return serverStart + mediaFrame * 1_000_000L / rate + bufferMs * 1000L - OFFSET;
    }

    de.yaacc.multiroom.protocol.CodecHeaderMessage header() {
        return flac ? ClientFixtures.flacHeaderMessage(rate, channels, bits) : ClientFixtures.pcmHeader(rate, channels, bits);
    }

    /** The server sends the header again (identical), as it does when a stream starts anew. */
    void resendHeader() {
        engine.onCodecHeader(header());
    }

    /** The first sample of audible output after {@code fromMillis} ms from now, as ms from now; -1 when none within the limit. */
    long millisToAudible(long limitMillis) {
        long before = sink.counters().playedAudibleFrames;
        for (long t = 0; t <= limitMillis; t += stepMs) {
            if (sink.counters().playedAudibleFrames > before) {
                return t;
            }
            step();
        }
        return -1;
    }

    /** The source delivers nothing for this long (the timestamps go on); the chunks come back after it. */
    void stallSource(long millis) {
        sending = false;
        run(millis);
        sending = true;
    }

    /** The last played frame against its media target, in milliseconds (positive: late). */
    double syncErrorMs() {
        return sink.counters().lastSyncErrorMicros / 1000.0;
    }

    /** The stream is a 440 Hz sine; the sink then checks the steps between neighbouring samples. */
    void useSine() {
        sine = true;
        sink.setSineSlope(12000 * 2 * Math.PI * 440 / rate);
    }

    void connect() {
        engine.onServerSettings(new ServerSettingsMessage(bufferMs, 0, false, 100));
        engine.onCodecHeader(header());
        engine.onOffsetUpdate(offsets.offset, 300); // the connection reports the clock estimate to the engine
    }

    long serverNow() {
        return clock.nowMicros() + OFFSET;
    }

    void step() {
        for (long[] job : jobs) {
            if (clock.nowMicros() / 1000 >= job[2]) {
                clock.advanceMillis(job[1]);
                job[2] += job[0];
            }
        }
        while (chunkTime(nextChunk + 1) <= serverNow()) {
            if (!sending) {
                wasSending = false;
                nextChunk++;
                continue;
            }
            if (flac && flacBlockFrames > 0) {
                if (!wasSending) { // the source starts anew: the encoder has nothing collected, the content goes on
                    wasSending = true;
                    emittedMedia = nextChunk * chunkFrames;
                    encoderPending = 0;
                }
                encoderPending += chunkFrames;
                if (encoderPending >= flacBlockFrames) {
                    long stamp = chunkTime(nextChunk);
                    WireChunkMessage block = sine
                            ? ClientFixtures.sineChunk(stamp, emittedMedia, flacBlockFrames, channels, rate)
                            : ClientFixtures.chunk(stamp, emittedMedia, flacBlockFrames, channels, bits);
                    engine.onChunk(block);
                    emittedMedia += flacBlockFrames;
                    encoderPending -= flacBlockFrames;
                }
                nextChunk++;
                continue;
            }
            wasSending = true;
            long stamp = chunkTime(nextChunk);
            if (chunkJitterMicros > 0) {
                stamp += random.nextInt((int) (2 * chunkJitterMicros + 1)) - chunkJitterMicros;
            }
            WireChunkMessage chunk = sine
                    ? ClientFixtures.sineChunk(stamp, nextChunk * chunkFrames, chunkFrames, channels, rate)
                    : ClientFixtures.chunk(stamp, nextChunk * chunkFrames, chunkFrames, channels, bits);
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

    /** A flac decoder of the tests: passes 16 bit samples through, optionally holding chunks back as a platform codec does. */
    private static final class LaggingDecoder implements FrameDecoder {
        private final java.util.function.IntSupplier lag;
        private byte[] held = new byte[0];
        private int calls;

        LaggingDecoder(java.util.function.IntSupplier lag) {
            this.lag = lag;
        }

        @Override
        public byte[] decode(byte[] payload) {
            calls++;
            int mode = lag.getAsInt();
            if (mode == 0) {
                return payload.clone();
            }
            if (mode == 1) {
                byte[] out = held;
                held = payload.clone();
                return out;
            }
            // alternate: hold on odd calls, on even calls give both
            if (calls % 2 == 1) {
                held = ClientFixtures.concat(held, payload);
                return new byte[0];
            }
            byte[] out = ClientFixtures.concat(held, payload);
            held = new byte[0];
            return out;
        }

        @Override
        public void reset() {
            held = new byte[0];
        }

        @Override
        public long droppedPartialBytes() {
            return 0;
        }

        @Override
        public void close() {
        }
    }
}
