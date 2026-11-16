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
    final java.util.Random random = new java.util.Random(42);

    final ManualMicroClock clock = new ManualMicroClock(10_000_000L);
    final ClientFixtures.FakeOffsets offsets = new ClientFixtures.FakeOffsets(OFFSET);
    final DeviceModel model;
    final DeviceSimSink sink;
    final SnapcastClientEngine engine;
    final int rate;
    final int channels;
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
     * &gt; 0 (flac only): the encoder collects the pcm chunks into blocks of this many frames and emits a chunk when at
     * least one block is complete (see {@link #flacStamping} for the timestamp it gets).
     */
    int flacBlockFrames;

    /** How the simulated server stamps the chunks of a flac stream. */
    enum FlacStamping {
        /**
         * The model of Group 8j, <b>not what the server does</b>: a chunk is stamped with the start of the pcm chunk that
         * completed its block, so the stamps are 20, 20, 20, 20, 40 ms apart and each is up to a block later than its first
         * sample. Kept so that the engine is tested on it as well.
         */
        COMPLETING_CHUNK,
        /**
         * Snapcast's {@code PcmStream::chunkEncoded} (0.35.0): a running clock that starts at the wall time of the first read
         * and after every stall, and is advanced by the duration of the <i>encoded</i> frames after each chunk. The stamps are
         * contiguous with the encoder's frames (one block apart), the encoder keeps the frames it had collected over a stall,
         * and the first chunk after a stall is stamped with the start of the pcm chunk just read.
         */
        SERVER_CLOCK
    }

    FlacStamping flacStamping = FlacStamping.COMPLETING_CHUNK;
    /**
     * True: the chunks of a flac stream carry real FLAC frame headers (frame number, CRC-8) in front of the pcm samples that
     * the test decoder strips, so the engine can read the position of the encoder. False: the payload is raw pcm, as in the
     * older tests (the engine then counts frames).
     */
    boolean flacHeaders;
    /** The chunks of a flac stream arrive up to this much later than they were emitted (in order); the stamps are not touched. */
    long arrivalJitterMicros;
    /** Applied to every flac chunk before the engine gets it (a test corrupts a header here); null: none. */
    java.util.function.UnaryOperator<WireChunkMessage> chunkFilter;
    /** Added to every stamp of a flac stream from now on (the clock of the server jumps by this; negative: back). */
    long stampShiftMicros;
    /** The decoder holds back a random chunk: this many per thousand of the calls, for 1 to {@link #decoderHoldMaxCalls} calls. */
    int decoderHoldPermille;
    int decoderHoldMaxCalls = 2;
    /** The decoder holds back its next this many calls (a test sets it right before a stall ends); then it is 0 again. */
    int decoderForcedHoldCalls;
    /** At this time (ms of the manual clock) {@link #decoderLag} becomes {@link #decoderLagAfter}; negative: never. */
    long decoderLagStepAtMs = -1;
    int decoderLagAfter;
    /** 0: the decoder hands back what it was given; 1: it holds one chunk back; 2: it alternates between holding and a burst of two. */
    int decoderLag;
    private boolean wasSending = true;

    // the encoder of the simulated server
    private final java.util.ArrayDeque<long[]> segments = new java.util.ArrayDeque<>(); // {first media frame, frames}
    private long pendingFrames;
    private long encoderPosition;
    private boolean encoderStarted;
    private long clockResetMicros;
    private long framesSinceReset;
    private long lastArrivalMicros;
    private long lastWireStamp = Long.MIN_VALUE / 2;
    private final java.util.ArrayDeque<Object[]> inFlight = new java.util.ArrayDeque<>(); // {arrival, chunk}
    private final java.util.TreeMap<Long, Long> targets = new java.util.TreeMap<>(); // first media frame -> server time of it

    /** True: a flac stream (the decoder of the tests passes the 16 bit samples through). Set before connect(). */
    boolean flac;

    DeviceRig(DeviceModel model) {
        this(model, new ClientEngineConfig());
    }

    DeviceRig(DeviceModel model, ClientEngineConfig config) {
        this(model, config, 48000, 960);
    }

    DeviceRig(DeviceModel model, ClientEngineConfig config, int rate, int chunkFrames) {
        this(model, config, rate, chunkFrames, 2);
    }

    DeviceRig(DeviceModel model, ClientEngineConfig config, int rate, int chunkFrames, int channels) {
        this.channels = channels;
        this.rate = rate;
        this.chunkFrames = chunkFrames;
        this.model = model;
        this.sink = new DeviceSimSink(clock, model);
        this.serverStart = clock.nowMicros() + OFFSET;
        this.sink.setIntent(this::targetOf);
        DefaultDecoderFactory pcmFactory = new DefaultDecoderFactory(null);
        this.engine = new SnapcastClientEngine(config, sink,
                (h, m) -> h.getCodec() == de.yaacc.multiroom.protocol.Codec.PCM ? pcmFactory.create(h, m)
                        : new LaggingDecoder(), offsets, clock);
    }

    /** The server time of the first frame of a chunk, exact (the server computes it from the frame count). */
    long chunkTime(long chunk) {
        return serverStart + chunk * chunkFrames * 1_000_000L / rate;
    }

    /** The client time at which the media frame (frames since chunk 0) is meant to be heard. */
    long targetOf(long mediaFrame) {
        if (flacHeaders && flacStamping == FlacStamping.SERVER_CLOCK) {
            // what the other clients play: the stamps of the server, not the wall clock of the live source
            java.util.Map.Entry<Long, Long> entry = targets.floorEntry(mediaFrame);
            if (entry != null) {
                return entry.getValue() + (mediaFrame - entry.getKey()) * 1_000_000L / rate + bufferMs * 1000L - OFFSET;
            }
        }
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
        if (decoderLagStepAtMs >= 0 && clock.nowMicros() / 1000 >= decoderLagStepAtMs) {
            decoderLag = decoderLagAfter;
            decoderLagStepAtMs = -1;
        }
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
            if (flac && (flacBlockFrames > 0 || flacHeaders)) {
                feedEncoder(nextChunk);
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
        deliverArrived();
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

    private byte[] pcmOf(long firstMedia, int frames) {
        return (sine ? ClientFixtures.sineChunk(0, firstMedia, frames, channels, rate)
                : ClientFixtures.chunk(0, firstMedia, frames, channels, bits)).getPayload();
    }

    /** One pcm chunk has been read by the simulated server's encoder (the source delivers 20 ms at a time). */
    private void feedEncoder(long chunkIndex) {
        int block = flacBlockFrames > 0 ? flacBlockFrames : chunkFrames;
        if (!wasSending || !encoderStarted) {
            wasSending = true;
            if (flacStamping == FlacStamping.COMPLETING_CHUNK) { // the model of 8j: the encoder starts empty after a stall
                segments.clear();
                pendingFrames = 0;
            } else {
                // PcmStream: the clock restarts at the wall time of the chunk just read; the encoder keeps what it had
                clockResetMicros = chunkTime(chunkIndex);
                framesSinceReset = 0;
            }
            if (!encoderStarted) {
                clockResetMicros = chunkTime(chunkIndex);
                framesSinceReset = 0;
            }
            encoderStarted = true;
        }
        segments.addLast(new long[]{chunkIndex * chunkFrames, chunkFrames});
        pendingFrames += chunkFrames;
        if (pendingFrames < block) {
            return;
        }
        int blocks = (int) (pendingFrames / block);
        long stamp;
        if (flacStamping == FlacStamping.COMPLETING_CHUNK) {
            stamp = chunkTime(chunkIndex);
        } else {
            // the server adds the encoded duration in nanoseconds (pcm_stream.cpp: tvEncodedChunk_ += nanoseconds(duration *
            // 1e6)) and writes microseconds into the chunk, so nothing accumulates
            stamp = clockResetMicros + framesSinceReset * 1_000_000L / rate;
        }
        stamp += stampShiftMicros;
        framesSinceReset += (long) blocks * block;
        java.io.ByteArrayOutputStream payload = new java.io.ByteArrayOutputStream();
        long offsetInChunk = 0;
        for (int b = 0; b < blocks; b++) {
            if (flacHeaders) {
                byte[] header = de.yaacc.multiroom.protocol.FlacFrameBuilder.fixedHeaderAt(encoderPosition, block, rate, channels, bits);
                payload.write(header, 0, header.length);
            }
            encoderPosition += block;
            int need = block;
            while (need > 0) {
                long[] seg = segments.peekFirst();
                int take = (int) Math.min(need, seg[1]);
                byte[] pcm = pcmOf(seg[0], take);
                payload.write(pcm, 0, pcm.length);
                if (flacStamping == FlacStamping.SERVER_CLOCK) {
                    targets.put(seg[0], stamp + offsetInChunk * 1_000_000L / rate);
                }
                offsetInChunk += take;
                need -= take;
                seg[0] += take;
                seg[1] -= take;
                if (seg[1] == 0) {
                    segments.pollFirst();
                }
            }
        }
        pendingFrames -= (long) blocks * block;
        if (!targets.isEmpty() && targets.size() > 4000) {
            targets.headMap(targets.lastKey() - 30L * rate).clear();
        }
        long wireStamp = stamp;
        if (chunkJitterMicros > 0) {
            // noise on the stamps, but never out of order (the server's chunks come in order of their stamps)
            wireStamp += random.nextInt((int) (2 * chunkJitterMicros + 1)) - chunkJitterMicros;
            wireStamp = Math.max(wireStamp, lastWireStamp + 1_000L);
        }
        lastWireStamp = wireStamp;
        WireChunkMessage chunk = ClientFixtures.rawChunk(wireStamp, payload.toByteArray());
        long arrival = serverNow();
        if (arrivalJitterMicros > 0) {
            arrival += random.nextInt((int) arrivalJitterMicros + 1);
        }
        arrival = Math.max(arrival, lastArrivalMicros);
        lastArrivalMicros = arrival;
        inFlight.addLast(new Object[]{arrival, chunk});
        deliverArrived();
    }

    private void deliverArrived() {
        while (!inFlight.isEmpty() && (Long) inFlight.peekFirst()[0] <= serverNow()) {
            WireChunkMessage chunk = (WireChunkMessage) inFlight.pollFirst()[1];
            engine.onChunk(chunkFilter == null ? chunk : chunkFilter.apply(chunk));
        }
    }

    /**
     * A flac decoder of the tests: strips the frame headers of the chunk (when it has them) and passes the 16 bit samples
     * through, optionally holding chunks back as a platform codec does ({@link #decoderLag}, random holds).
     */
    private final class LaggingDecoder implements FrameDecoder {
        private byte[] held = new byte[0];
        private int calls;
        private int holdRemaining;

        @Override
        public byte[] decode(byte[] payload) {
            calls++;
            byte[] samples = strip(payload);
            int mode = decoderLag;
            if (decoderForcedHoldCalls > 0 && holdRemaining == 0) {
                holdRemaining = decoderForcedHoldCalls;
                decoderForcedHoldCalls = 0;
            }
            if (decoderHoldPermille > 0 && holdRemaining == 0 && random.nextInt(1000) < decoderHoldPermille) {
                holdRemaining = 1 + random.nextInt(Math.max(1, decoderHoldMaxCalls));
            }
            if (holdRemaining > 0) {
                holdRemaining--;
                held = ClientFixtures.concat(held, samples);
                return new byte[0];
            }
            if (mode == 0) {
                byte[] out = ClientFixtures.concat(held, samples);
                held = new byte[0];
                return out;
            }
            if (mode == 1) {
                byte[] out = held;
                held = samples;
                return out;
            }
            // alternate: hold on odd calls, on even calls give both
            if (calls % 2 == 1) {
                held = ClientFixtures.concat(held, samples);
                return new byte[0];
            }
            byte[] out = ClientFixtures.concat(held, samples);
            held = new byte[0];
            return out;
        }

        /**
         * The samples of a chunk of frames with headers; a payload that has none is taken as the samples. The length of a
         * header is worked out from its codes alone (this decoder of the tests does not check the CRC; the engine does).
         */
        private byte[] strip(byte[] payload) {
            if (payload.length < 6 || (payload[0] & 0xff) != 0xff || (payload[1] & 0xfc) != 0xf8) {
                return payload.clone();
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int at = 0;
            while (at + 6 <= payload.length && (payload[at] & 0xff) == 0xff && (payload[at + 1] & 0xfc) == 0xf8) {
                int blockCode = (payload[at + 2] & 0xf0) >>> 4;
                int rateCode = payload[at + 2] & 0x0f;
                int first = payload[at + 4] & 0xff;
                int numberBytes = first < 0x80 ? 1 : first < 0xe0 ? 2 : first < 0xf0 ? 3 : first < 0xf8 ? 4 : first < 0xfc ? 5 : first < 0xfe ? 6 : 7;
                int length = 4 + numberBytes + (blockCode == 6 ? 1 : blockCode == 7 ? 2 : 0)
                        + (rateCode == 12 ? 1 : rateCode == 13 || rateCode == 14 ? 2 : 0) + 1;
                int blockSize = blockCode == 1 ? 192 : blockCode <= 5 ? 576 << (blockCode - 2) : blockCode >= 8 ? 256 << (blockCode - 8)
                        : flacBlockFrames > 0 ? flacBlockFrames : chunkFrames;
                at += length;
                int bytes = blockSize * channels * (bits / 8);
                out.write(payload, at, Math.min(bytes, payload.length - at));
                at += bytes;
            }
            return out.toByteArray();
        }

        @Override
        public void reset() {
            held = new byte[0];
            holdRemaining = 0;
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
