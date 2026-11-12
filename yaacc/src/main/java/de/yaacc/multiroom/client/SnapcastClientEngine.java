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

import de.yaacc.multiroom.protocol.ChunkScheduler;
import de.yaacc.multiroom.protocol.Codec;
import de.yaacc.multiroom.protocol.CodecHeaderMessage;
import de.yaacc.multiroom.protocol.MicroClock;
import de.yaacc.multiroom.protocol.OffsetSource;
import de.yaacc.multiroom.protocol.ServerSettingsMessage;
import de.yaacc.multiroom.protocol.SnapcastProtocolLimits;
import de.yaacc.multiroom.protocol.WireChunkMessage;

import java.util.ArrayDeque;

/**
 * The core of the Snapcast client: turns what the connection reports (settings, codec headers, chunks, offset updates,
 * a lost connection) into writes to an {@link AudioSink} so that every sample is presented at the time the server
 * wants it. Everything it touches is an interface ({@link AudioSink}, {@link DecoderFactory}, {@link OffsetSource},
 * {@link MicroClock}), so it runs on the JVM with fakes. It is <b>not thread safe</b>: one thread (the engine thread of
 * {@link EngineHost}) makes every call.
 *
 * <p><b>Time.</b> The play time of the stream sample with index {@code s} (counted from the anchor, the timestamp of the
 * first chunk after a start or a resync) is, on the client clock,
 * {@code anchor + s / rate + bufferMs - (server latency + manual latency) - offset}. The output reports when it
 * presents which frame ({@link PlaybackTracker}); from that the engine knows when the next frame it writes will be
 * presented, and the difference to the play time is the drift (positive: late). Because the report is of the
 * output's own pipeline, the latency of the output is already in it; only what the platform can not see (a Bluetooth
 * speaker) needs the manual latency.
 *
 * <p><b>What it does with the drift.</b> The sink is kept fed all the time (silence when there is no audio due), so
 * the output never runs dry and always has a position. Before audio is first played (and after a gap, a step or a
 * resync) it is aligned exactly: silence is written until the next frame would be presented on time, or frames are
 * skipped when it would be late. While playing, a drift above the dead band is corrected by dropping or repeating single
 * frames, at most one per {@code correctionIntervalFrames} ({@link DriftController}); the pitch never changes. A
 * drift above the gradual limit is a step (the offset or the settings moved), also done exactly; playback late by more
 * than the hard bound means the output holds old audio, so it is flushed.
 *
 * <p><b>Rules for a hostile server</b> (decisions of Group 7 and 8): an identical codec header is ignored, a different one
 * restarts the decoder at most {@code maxDecoderRestartsPerMinute} times a minute (more ends the session); the
 * settings are global and not paired with a header (a coalesced value may overtake an earlier header); an epoch
 * change of the clock empties the queues but does not flush or re-create the output; chunk timestamps are never trusted
 * (saturating maths in the scheduler, a limit on how far a timeline may run from its anchor); buffers are sized from the
 * validated rate, channels and bit depth only; a chunk with a trailing partial frame loses it; at most two channels
 * are played; decoding failures drop the chunk and a run of them ends the session.
 *
 * <p><b>Known limits.</b> For flac the engine assumes the first decoded sample is at the timestamp of the first chunk
 * and that decoded frames follow without gaps (no per-chunk continuity check, because the decoded size of a chunk does
 * not follow its duration). Not yet seen on a device.
 */
public final class SnapcastClientEngine {

    /** The most microseconds {@code framesFor} multiplies by the rate (about 3 hours; more is clamped, not wrapped). */
    /** Holes remembered one by one (about 1.3 seconds of a stream that loses every second chunk, per 64). */
    private static final int MAX_HOLES = 64;
    /** The scheduler drops chunks stamped more than this after the corrected clock; no hole can be later. */
    private static final long HOLE_FUTURE_LIMIT_MICROS = 5_000_000L;
    private static final long MAX_FRAMES_FOR_MICROS = 10_000_000_000L;
    private static final long RESTART_WINDOW_MICROS = 60_000_000L;

    /** Decoded audio that waits for its time. */
    private static final class Block {
        final byte[] pcm;
        final int frames;
        long start;
        int consumed;
        boolean exact;

        Block(byte[] pcm, int frames, long start, boolean exact) {
            this.pcm = pcm;
            this.frames = frames;
            this.start = start;
            this.exact = exact;
        }

        int remaining() {
            return frames - consumed;
        }
    }

    private final ClientEngineConfig cfg;
    private final AudioSink sink;
    private final DecoderFactory decoders;
    private final OffsetSource offsets;
    private final MicroClock clock;
    private final ChunkScheduler scheduler;
    private final long startedAtMicros;

    private EngineState state = EngineState.WAITING_FOR_STREAM;
    private EngineFailure failure;
    private boolean terminal;

    private CodecHeaderMessage header;
    private FrameDecoder decoder;
    private SinkFormat format;
    private boolean sinkOpen;
    private boolean sinkStarted;
    private long sinkStartedAtMicros;
    private int rate;
    private int outFrameBytes;
    private int blockFrames;
    private int maxChunkFrames;
    private long maxFifoFrames;
    private long prefillFrames;
    private long maxAheadMicros;

    private PlaybackTracker tracker = new PlaybackTracker(48000);
    private DriftController drift;
    private final ArrayDeque<Block> fifo = new ArrayDeque<>();
    private long fifoFrames;
    private boolean anchorValid;
    private boolean candidateValid;
    private long candidateServerMicros;
    /** Every hole not yet acted on, as the timestamp of the chunk before it (sorted; at most {@link #MAX_HOLES}). */
    private final java.util.TreeSet<Long> holes = new java.util.TreeSet<>();
    /** More holes than fit: from this timestamp on every chunk is placed by its own timestamp until {@code overflowUntil}. */
    private long overflowFrom = Long.MAX_VALUE;
    private long overflowUntil = Long.MIN_VALUE;
    private long holeOverflows;
    private boolean offeredAny;
    private long lastOfferedMicros;
    private long anchorServerMicros;
    private long nextExpectedFrame;
    private boolean aligned;
    private long writtenFrames;
    private byte[] pending;
    private int pendingOffset;
    private byte[] silence = new byte[0];
    private byte[] outData;
    private int outOffset;
    private int outLength;

    private int seenEpoch;
    private long lastFlushAtMicros = Long.MIN_VALUE / 2;
    private int baselineUnderruns;
    private long lastPositionPollMicros = Long.MIN_VALUE / 2;
    private long lastDriftRevision = -1;
    private long lastAudioWrittenMicros = Long.MIN_VALUE / 2;
    private int consecutiveDecodeFailures;
    private final ArrayDeque<Long> restartTimes = new ArrayDeque<>();
    private final ArrayDeque<Long> decoderFailureTimes = new ArrayDeque<>();
    private boolean connectionLost;

    private int bufferMs = 1000;
    private int serverLatencyMs;
    private int manualLatencyMs;
    private int volume = 100;
    private boolean muted;
    private long reportedOffset;
    private long reportedJitter;
    private long lastDrift;

    private long chunksReceived;
    private long chunksPlayed;
    private long droppedBeforeHeader;
    private long droppedOversized;
    private long droppedFifoFull;
    private long droppedOverlap;
    private long decodeFailures;
    private long gapsFilled;
    private long correctionDropped;
    private long correctionRepeated;
    private long stepSkipped;
    private long stepPadded;
    private long timelineResyncs;
    private long timelineRebases;
    private long streamHolesRecovered;
    private long flushes;
    private final long[] flushesByReason = new long[FlushReason.values().length];
    private long lastHardResyncDriftMicros;
    private final EventTrace trace;
    private long lastSummaryTraceMicros = Long.MIN_VALUE / 2;
    private long lastPositionTraceMicros = Long.MIN_VALUE / 2;
    private boolean alignStartTraced;
    private boolean primeTraced;
    // underrun counter judgement (Fix Group 8g)
    private long suspectSinceMicros = -1;
    private int spuriousInARow;
    private boolean counterUnreliable;
    private long underrunsCorroborated;
    private long underrunsSpurious;
    private long underrunRisesSinceTrace;
    private long lastRefusedTraceMicros = Long.MIN_VALUE / 2;
    private boolean stepTraced;
    /** Consecutive fresh position reports that said the output is more than hardResyncMs late. */
    private int hardLateReports;
    private long hardLateRevision = -1;
    private long flushesSuppressed;
    private long underruns;
    private long headersIgnored;
    private long decoderStarts;
    private long epochChanges;
    private long connectionLosses;
    private long droppedPartialBytesFromOldDecoders;

    public SnapcastClientEngine(ClientEngineConfig config, AudioSink sink, DecoderFactory decoders, OffsetSource offsets,
                                MicroClock clock) {
        this.cfg = config;
        this.sink = sink;
        this.decoders = decoders;
        this.offsets = offsets;
        this.clock = clock;
        this.scheduler = new ChunkScheduler(offsets, clock,
                new ChunkScheduler.Config().lateToleranceMs(cfg.lateToleranceMs).releaseLeadMs(cfg.releaseLeadMs));
        this.seenEpoch = offsets.epoch();
        this.startedAtMicros = clock.nowMicros();
        this.trace = new EventTrace(startedAtMicros);
        sink.attachTrace(trace);
        this.drift = new DriftController(cfg.deadBandMicros, cfg.correctionIntervalFrames);
        updateScheduler();
    }

    // ---- events

    /** The settings are global: they apply to whatever plays, whenever they arrive (no pairing with a header). */
    public void onServerSettings(ServerSettingsMessage settings) {
        if (terminal) {
            return;
        }
        bufferMs = settings.getBufferMs();
        serverLatencyMs = settings.getLatencyMs();
        volume = Math.max(0, Math.min(100, settings.getVolume()));
        muted = settings.isMuted();
        updateScheduler();
        applyGain();
    }

    /** Set by the user: positive plays earlier (like the latency of the server). */
    public void setManualLatencyMs(int milliseconds) {
        manualLatencyMs = Math.max(-SnapcastProtocolLimits.MAX_LATENCY_MS, Math.min(SnapcastProtocolLimits.MAX_LATENCY_MS, milliseconds));
        updateScheduler();
    }

    public void onOffsetUpdate(long offsetMicros, long jitterMicros) {
        reportedOffset = offsetMicros;
        reportedJitter = jitterMicros;
    }

    public void onCodecHeader(CodecHeaderMessage newHeader) {
        if (terminal) {
            return;
        }
        long now = clock.nowMicros();
        try {
            if (header != null && header.equals(newHeader)) {
                headersIgnored++;
                trace.add(now, TraceKind.HEADER_IGNORED);
                if (connectionLost) { // the same stream again after a reconnect
                    connectionLost = false;
                    if (decoder != null) {
                        decoder.reset();
                        trace.add(now, TraceKind.DECODER_RESET, 2);
                    }
                    scheduler.codecHeaderAccepted();
                    state = EngineState.BUFFERING;
                }
                return;
            }
            if (header != null && !allowRestart(now)) {
                fail(EngineFailure.HEADER_FLOOD);
                return;
            }
            applyHeader(newHeader, now);
        } catch (RuntimeException unexpected) {
            fail(EngineFailure.INTERNAL);
        }
    }

    public void onChunk(WireChunkMessage chunk) {
        if (terminal) {
            return;
        }
        chunksReceived++;
        if (header == null) {
            droppedBeforeHeader++;
            return;
        }
        try {
            long dropsBefore = schedulerDrops();
            ChunkScheduler.Decision decision = scheduler.offer(chunk);
            if (schedulerDrops() != dropsBefore && decision.getAction() != ChunkScheduler.Action.DROP_TOO_FAR_AHEAD) {
                // this chunk was dropped; one stamped more than 5 s ahead can not be a chunk of the stream, so it is no hole
                markHoleAfter(chunk.timestampMicros());
            }
            if (schedulerDrops() != dropsBefore) {
                trace.add(clock.nowMicros(), TraceKind.CHUNK_DROPPED, decision.getAction().ordinal(), schedulerDrops());
            }
            lastOfferedMicros = chunk.timestampMicros();
            offeredAny = true;
            if (decision.getAction() == ChunkScheduler.Action.PLAY_AT) {
                drainDue(); // anything older goes first
                process(chunk);
            }
        } catch (RuntimeException unexpected) {
            fail(EngineFailure.INTERNAL);
        }
    }

    /** As {@link #onConnectionLost()}, and the reason (a {@code LossReason} ordinal and our own limit code) goes in the trace. */
    public void onConnectionLost(int reasonOrdinal, int ownLimitCode) {
        if (!terminal) {
            trace.add(clock.nowMicros(), TraceKind.CONNECTION_LOST, reasonOrdinal, ownLimitCode);
        }
        onConnectionLost();
    }

    /** The connection to the server was lost: silence the output; a reconnect brings the header again. */
    public void onConnectionLost() {
        if (terminal) {
            return;
        }
        connectionLosses++;
        forgetHoles();
        connectionLost = true;
        state = EngineState.CONNECTION_LOST;
        scheduler.reset();
        if (decoder != null) {
            decoder.reset();
            trace.add(clock.nowMicros(), TraceKind.DECODER_RESET, 1);
        }
        if (sinkOpen) {
            flushSink(clock.nowMicros(), FlushReason.CONNECTION_LOST);
        } else {
            dropTimeline();
        }
    }

    /** One cycle: releases due chunks, polls the output, writes what is due. Call about every {@code tickMs}. */
    public void tick() {
        if (terminal) {
            return;
        }
        try {
            int epoch = offsets.epoch();
            if (epoch != seenEpoch) {
                seenEpoch = epoch;
                epochChanges++;
                forgetHoles();
                dropTimeline(); // the scheduler empties its own queue; the output is neither flushed nor re-created
            }
            if (header != null && !connectionLost) {
                drainDue();
                if (!terminal && sinkOpen) {
                    pump(clock.nowMicros());
                }
            }
            updateState(clock.nowMicros());
        } catch (RuntimeException unexpected) {
            fail(EngineFailure.INTERNAL);
        }
    }

    /** Ends the session on request: the output and the decoder are freed. Safe to call twice. */
    public void stop() {
        if (terminal) {
            return;
        }
        terminal = true;
        state = EngineState.STOPPED;
        release();
    }

    /** Ends the session with a failure that the caller found (a watchdog, an exception on the engine thread). */
    public void abort(EngineFailure reason) {
        fail(reason);
    }

    /** A chunk was lost before it reached the engine (the host queue was full): a flac stream can not find the hole. */
    public void onChunkLost() {
        if (!terminal && header != null && header.getCodec() != Codec.PCM && offeredAny) {
            markHoleAfter(lastOfferedMicros); // the lost chunk would have followed the last one offered
        }
    }

    /** For tests: the output has counted {@code delta} frames more (to cross 2^31 and 2^32 without playing for hours). */
    void debugAdvanceFrameCounts(long delta) {
        writtenFrames += delta;
        tracker.debugShift(delta);
    }

    public boolean isTerminal() {
        return terminal;
    }

    // ---- headers, sink, decoder

    private boolean allowRestart(long now) {
        while (!restartTimes.isEmpty() && now - restartTimes.peekFirst() > RESTART_WINDOW_MICROS) {
            restartTimes.pollFirst();
        }
        if (restartTimes.size() >= cfg.maxDecoderRestartsPerMinute) {
            return false;
        }
        restartTimes.addLast(now);
        return true;
    }

    private void applyHeader(CodecHeaderMessage newHeader, long now) {
        if (newHeader.getChannels() > 2) {
            fail(EngineFailure.UNSUPPORTED_CHANNELS);
            return;
        }
        closeDecoder();
        forgetHoles();
        int newRate = newHeader.getSampleRate(); // validated by the protocol layer: 8000 to 192000
        int maxFrames = (int) Math.min(Integer.MAX_VALUE, (long) newRate * cfg.maxChunkMs / 1000L);
        try {
            decoder = decoders.create(newHeader, maxFrames);
        } catch (DecoderException e) {
            fail(e.getReason() == DecoderException.Reason.UNAVAILABLE ? EngineFailure.DECODER_UNAVAILABLE
                    : EngineFailure.DECODER_BAD_HEADER);
            return;
        }
        decoderStarts++;
        SinkFormat wanted = new SinkFormat(newRate, newHeader.getChannels());
        if (sinkOpen && !wanted.equals(format)) {
            sink.release();
            sinkOpen = false;
        }
        if (!sinkOpen) {
            try {
                sink.open(wanted);
            } catch (AudioSinkException e) {
                fail(e.getReason() == AudioSinkException.Reason.FORMAT_REFUSED ? EngineFailure.UNSUPPORTED_SINK_FORMAT
                        : EngineFailure.SINK_FAILED);
                return;
            }
            sinkOpen = true;
            sinkStarted = false;
            format = wanted;
            baselineUnderruns = sink.underrunCount();
            counterUnreliable = false; // judged per output
            spuriousInARow = 0;
            suspectSinceMicros = -1;
            applyGain();
        }
        header = newHeader;
        rate = newRate;
        outFrameBytes = wanted.getFrameBytes();
        blockFrames = Math.max(1, (int) ((long) rate * cfg.blockMs / 1000L));
        maxChunkFrames = maxFrames;
        maxFifoFrames = (long) rate * cfg.maxFifoMs / 1000L;
        // an output that plays only after a threshold is queued needs at least that much before it can report a position
        prefillFrames = Math.max(Math.max(1, (long) rate * cfg.prefillMs / 1000L), sink.startThresholdFrames());
        maxAheadMicros = cfg.maxAheadMs * 1000L;
        silence = new byte[blockFrames * outFrameBytes];
        tracker = new PlaybackTracker(rate, cfg.positionOutlierMs * 1000L);
        scheduler.reset();
        scheduler.codecHeaderAccepted();
        updateScheduler();
        connectionLost = false;
        consecutiveDecodeFailures = 0;
        // a new stream: whatever the output holds belongs to the old one
        flushSink(now, FlushReason.HEADER);
        trace.add(now, TraceKind.HEADER_ACCEPTED, newRate, newHeader.getChannels(), newHeader.getBitsPerSample());
        state = EngineState.BUFFERING;
    }

    private void closeDecoder() {
        if (decoder != null) {
            droppedPartialBytesFromOldDecoders += decoder.droppedPartialBytes();
            decoder.close();
            decoder = null;
        }
    }

    private void release() {
        closeDecoder();
        if (sinkOpen) {
            sink.release();
            sinkOpen = false;
        }
        fifo.clear();
        fifoFrames = 0;
        pending = null;
    }

    private void fail(EngineFailure reason) {
        if (terminal) {
            return;
        }
        terminal = true;
        failure = reason;
        state = EngineState.FAILED;
        try {
            release();
        } catch (RuntimeException ignored) {
            // the output is gone either way; the failure above is the one to report
        }
    }

    private void updateScheduler() {
        scheduler.setBufferMs(bufferMs);
        scheduler.setLatencyMs(totalLatencyMs());
    }

    private int totalLatencyMs() {
        return Math.max(-SnapcastProtocolLimits.MAX_LATENCY_MS,
                Math.min(SnapcastProtocolLimits.MAX_LATENCY_MS, serverLatencyMs + manualLatencyMs));
    }

    private void applyGain() {
        if (sinkOpen) {
            sink.setGain(GainMapper.gain(volume, muted));
        }
    }

    // ---- chunks

    private long schedulerDrops() {
        return scheduler.droppedLate() + scheduler.droppedTooFarAhead() + scheduler.droppedQueueFull();
    }

    /**
     * Remembers a hole after the chunk with this timestamp: the first chunk above it must be placed by its timestamp.
     * Every hole is kept, up to {@value #MAX_HOLES}; beyond that the holes are not told apart any more (the earliest
     * start of the overflow and the latest hole of it are kept) and every chunk in that range is placed by its own
     * timestamp, which is right but costs the decoded audio waiting at each one. Counted in {@code holeOverflows}.
     */
    private void markHoleAfter(long micros) {
        if (micros != Long.MIN_VALUE) {
            // Timestamps are the server's. While the clock is not synchronised nothing can be told, and no real chunk is
            // later than the corrected server time plus 5 s (what the scheduler accepts): a later mark could only be
            // made by a server that wants the stream to be re-anchored at every chunk (review S15 of Group 8).
            if (!offsets.isSynchronized()) {
                return;
            }
            micros = Math.min(micros, clock.nowMicros() + offsets.offsetMicros() + HOLE_FUTURE_LIMIT_MICROS);
        }
        if (holes.size() >= MAX_HOLES && !holes.contains(micros)) {
            holeOverflows++;
            overflowFrom = Math.min(overflowFrom, micros);
            overflowUntil = Math.max(overflowUntil, micros);
            return;
        }
        holes.add(micros);
        trace.add(clock.nowMicros(), TraceKind.HOLE_MARKED, holes.size());
    }

    /** The whole stream starts over (a header, a lost connection, a new epoch): the holes of the old one mean nothing. */
    private void forgetHoles() {
        holes.clear();
        overflowFrom = Long.MAX_VALUE;
        overflowUntil = Long.MIN_VALUE;
    }

    private void drainDue() {
        long dropsBefore = schedulerDrops();
        java.util.List<ChunkScheduler.ScheduledChunk> dueChunks = scheduler.releaseDue();
        if (schedulerDrops() != dropsBefore) {
            markHoleAfter(Long.MIN_VALUE); // chunks that waited became late: they were older than every chunk now due
        }
        for (ChunkScheduler.ScheduledChunk due : dueChunks) {
            if (terminal) {
                return;
            }
            process(due.getChunk());
        }
    }

    private void process(WireChunkMessage chunk) {
        if (decoder == null) {
            return;
        }
        boolean pcmCodec = header.getCodec() == Codec.PCM;
        if (!holes.isEmpty() || overflowFrom != Long.MAX_VALUE) {
            // a flac chunk before this one never got here: the frame counting can not see the hole, the timestamps can.
            // Every hole below this chunk is dealt with by one re-anchoring here; holes above it stay for their own chunk.
            long timestamp = chunk.timestampMicros();
            boolean after = false;
            java.util.SortedSet<Long> below = holes.headSet(timestamp);
            if (!below.isEmpty()) {
                below.clear();
                after = true;
            }
            if (overflowFrom < timestamp) {
                after = true;
                if (timestamp > overflowUntil) {
                    overflowFrom = Long.MAX_VALUE;
                    overflowUntil = Long.MIN_VALUE;
                }
            }
            if (after && !pcmCodec && anchorValid) {
                recoverHole();
            }
        }
        byte[] pcm;
        try {
            pcm = decoder.decode(chunk.getPayload());
            consecutiveDecodeFailures = 0;
        } catch (DecoderException | RuntimeException e) {
            boolean oversized = e instanceof DecoderException
                    && ((DecoderException) e).getReason() == DecoderException.Reason.OVERSIZED;
            if (oversized) {
                droppedOversized++;
            } else {
                decodeFailures++;
            }
            if (!pcmCodec && anchorValid) {
                recoverHole();
            }
            // an oversized chunk counts as a failure for the limit: it makes the platform decoder start anew, and a real
            // server never sends one (security review W5 of Group 8)
            if (noteDecoderFailure() || (!oversized && ++consecutiveDecodeFailures >= cfg.maxConsecutiveDecodeFailures)) {
                fail(EngineFailure.DECODER_FAILED);
            }
            return;
        }
        int frames = pcm.length / outFrameBytes;
        if (frames == 0) {
            if (!pcmCodec && !anchorValid && !candidateValid) {
                // a decoder that is a chunk behind: the stream starts at the first chunk, not at the first output
                candidateValid = true;
                candidateServerMicros = chunk.timestampMicros();
            }
            return;
        }
        if (frames > maxChunkFrames) {
            droppedOversized++;
            if (!pcmCodec && anchorValid) {
                recoverHole();
            }
            if (noteDecoderFailure()) {
                fail(EngineFailure.DECODER_FAILED);
            }
            return;
        }
        if (fifoFrames + frames > maxFifoFrames) {
            droppedFifoFull++;
            if (!pcmCodec && anchorValid) {
                recoverHole();
            }
            return;
        }
        long start;
        boolean exact = false;
        if (!anchorValid) {
            newAnchor(chunk, pcmCodec);
            start = 0;
            exact = true;
        } else {
            if (nextExpectedFrame > cfg.rebaseFrames) {
                rebaseTimeline();
            }
            long difference = chunk.timestampMicros() - anchorServerMicros;
            // only a guard against overflow of difference * rate; continuity is judged by the gap between chunks below
            if (Math.abs(difference) > cfg.maxAnchorDifferenceMicros) {
                timelineResyncs++;
                dropTimeline();
                newAnchor(chunk, pcmCodec);
                start = 0;
                exact = true;
            } else if (pcmCodec) {
                long declared = Math.floorDiv(difference * rate, 1_000_000L);
                long gap = declared - nextExpectedFrame;
                long tolerance = (long) cfg.continuityToleranceMs * rate / 1000L;
                if (Math.abs(gap) <= tolerance) {
                    start = nextExpectedFrame;
                } else if (gap > tolerance) {
                    if (gap * 1000L / rate > cfg.maxGapFillMs) {
                        timelineResyncs++;
                        dropTimeline();
                        newAnchor(chunk, pcmCodec);
                        start = 0;
                    } else {
                        gapsFilled++;
                        start = declared;
                    }
                    exact = true;
                } else {
                    long overlap = -gap;
                    droppedOverlap++;
                    if (overlap >= frames) {
                        return;
                    }
                    int cut = (int) overlap;
                    byte[] trimmed = new byte[(frames - cut) * outFrameBytes];
                    System.arraycopy(pcm, cut * outFrameBytes, trimmed, 0, trimmed.length);
                    pcm = trimmed;
                    frames -= cut;
                    start = nextExpectedFrame;
                }
            } else {
                start = nextExpectedFrame;
            }
        }
        fifo.addLast(new Block(pcm, frames, start, exact));
        fifoFrames += frames;
        nextExpectedFrame = start + frames;
        chunksPlayed++;
    }

    /** Records a chunk the decoder could not take; true when there were more than the limit in the last 60 s. */
    private boolean noteDecoderFailure() {
        long now = clock.nowMicros();
        while (!decoderFailureTimes.isEmpty() && now - decoderFailureTimes.peekFirst() > RESTART_WINDOW_MICROS) {
            decoderFailureTimes.pollFirst();
        }
        decoderFailureTimes.addLast(now);
        return decoderFailureTimes.size() > cfg.maxDecoderFailuresPerMinute;
    }

    private void newAnchor(WireChunkMessage chunk, boolean pcmCodec) {
        anchorValid = true;
        anchorServerMicros = !pcmCodec && candidateValid ? candidateServerMicros : chunk.timestampMicros();
        candidateValid = false;
        nextExpectedFrame = 0;
    }

    /**
     * A flac chunk is missing (dropped, failed, lost): the frames that follow would be placed one chunk too early for good.
     * The timeline is dropped so that the next chunk is placed exactly by its timestamp (the output is left alone).
     */
    private void recoverHole() {
        streamHolesRecovered++;
        trace.add(clock.nowMicros(), TraceKind.HOLE_RECOVERED, streamHolesRecovered);
        dropTimeline();
    }

    /**
     * Moves the anchor forward by whole seconds of frames, in place and exactly (a whole number of seconds is a whole
     * number of microseconds and of frames), so the frame counts stay small however long the session runs. The play
     * time of every frame and the continuity test are unchanged by it; nothing is dropped or realigned.
     */
    private void rebaseTimeline() {
        long seconds = nextExpectedFrame / rate;
        if (seconds < 1) {
            return;
        }
        long shiftFrames = seconds * rate;
        anchorServerMicros += seconds * 1_000_000L;
        nextExpectedFrame -= shiftFrames;
        for (Block block : fifo) {
            block.start -= shiftFrames;
        }
        timelineRebases++;
    }

    /** Forgets the timeline: queued audio, the anchor and the alignment. The output is left as it is. */
    private void dropTimeline() {
        fifo.clear();
        fifoFrames = 0;
        anchorValid = false;
        candidateValid = false;
        nextExpectedFrame = 0;
        aligned = false;
        alignStartTraced = false;
        stepTraced = false;
        pending = null;
        drift.reset();
        lastDriftRevision = -1;
        hardLateReports = 0;
        suspectSinceMicros = -1;
    }

    // ---- the output

    private void pump(long now) {
        pollPosition(now);
        checkUnderruns(now);
        checkStall(now);
        if (terminal) {
            return;
        }
        if (!tracker.isValid() && sinkStarted && now - sinkStartedAtMicros > cfg.positionTimeoutMs * 1000L) {
            fail(EngineFailure.NO_PLAYBACK_POSITION);
            return;
        }
        if (pending != null && !writePending(now)) {
            return;
        }
        for (int i = 0; i < cfg.maxBlocksPerTick && !terminal; i++) {
            if (tracker.isValid()) {
                long ahead = tracker.presentationMicros(writtenFrames) - now;
                if (ahead >= maxAheadMicros) {
                    break;
                }
                if (aligned && ahead < -cfg.underrunSlackMs * 1000L) {
                    underruns++; // we were too late with the data: the output ran dry without saying so
                    flushForRecovery(now, FlushReason.WRITER_BEHIND);
                    continue;
                }
            } else if (writtenFrames >= prefillFrames && !priming(now)) {
                break;
            }
            if (!nextOutput(now)) {
                continue; // the state changed (a skip, a flush); look again
            }
            if (!writeOutput(now)) {
                break;
            }
        }
    }

    /**
     * True while the output has not given any position although it has been playing for a while: then it is probably
     * still waiting for more data (a start threshold we do not know), so more silence is queued until it is full. The
     * silence is aligned over later like the prefill.
     */
    private boolean priming(long now) {
        boolean prime = sinkStarted && now - sinkStartedAtMicros >= cfg.primeAfterMs * 1000L;
        if (prime && !primeTraced) {
            primeTraced = true;
            trace.add(now, TraceKind.PRIME, writtenFrames, sink.startThresholdFrames());
        }
        return prime;
    }

    private void pollPosition(long now) {
        boolean calm = aligned && hardLateReports == 0 && suspectSinceMicros < 0 && !tracker.stepLooksStalled();
        long interval = (calm ? cfg.positionPollSlowMs : cfg.positionPollFastMs) * 1000L;
        if (now - lastPositionPollMicros >= interval) {
            lastPositionPollMicros = now;
            long heldBefore = tracker.outlierCount();
            PlaybackPosition position = sink.position();
            if (tracker.update(position, writtenFrames, now)) {
                if (now - lastPositionTraceMicros >= 250_000L) {
                    lastPositionTraceMicros = now;
                    trace.add(now, TraceKind.POSITION, position.getFrames(), tracker.ageMicros(now) / 1000L,
                            writtenFrames - position.getFrames());
                }
            } else if (tracker.outlierCount() != heldBefore && position != null) {
                trace.add(now, TraceKind.POSITION_HELD, position.getFrames(), (now - position.getPresentedAtMicros()) / 1000L);
            } else if (position != null && (position.getFrames() > writtenFrames || position.getFrames() < 0)
                    && now - lastRefusedTraceMicros >= 1_000_000L) {
                lastRefusedTraceMicros = now; // more frames played than written: the counter is not the one we think
                trace.add(now, TraceKind.POSITION_REFUSED, position.getFrames(), writtenFrames,
                        (now - position.getPresentedAtMicros()) / 1000L);
            }
            traceSummary(now);
        }
    }

    /** Once per second: the drift, the position reports and the queues. */
    private void traceSummary(long now) {
        if (now - lastSummaryTraceMicros < 1_000_000L) {
            return;
        }
        lastSummaryTraceMicros = now;
        long ahead = tracker.isValid() && rate > 0 ? (tracker.presentationMicros(writtenFrames) - now) / 1000L : -1;
        trace.add(now, TraceKind.DRIFT, lastDrift, aligned ? 1 : 0, ahead);
        if (underrunRisesSinceTrace > 0) {
            trace.add(now, TraceKind.UNDERRUN, underrunRisesSinceTrace, sink.underrunCount(), aligned ? 1 : 0);
            underrunRisesSinceTrace = 0;
        }
        trace.add(now, TraceKind.POLLS, tracker.acceptedCount(), tracker.rejectedCount(), tracker.outlierCount());
        trace.add(now, TraceKind.QUEUE, rate > 0 ? fifoFrames * 1000L / rate : 0, ahead, scheduler.queuedChunks());
    }

    /**
     * The platform's underrun counter is a hint, never a reason to flush by itself (a Nothing A142 on Android 16 raises it
     * every 2048 frames with 110 ms queued): a rise is confirmed by the position (the output stood still, or holds
     * less than {@code underrunAheadMs}) within {@code underrunConfirmMs}, otherwise it is counted as spurious and
     * nothing is flushed. After {@code unreliableAfterRises} unconfirmed rises in a row the counter is no longer used for
     * this output; a real starvation is then found by the stalled position or by writing too late.
     */
    private void checkUnderruns(long now) {
        int count = sink.underrunCount();
        if (count > baselineUnderruns) {
            int delta = count - baselineUnderruns;
            underruns += delta;
            underrunRisesSinceTrace += delta;
            baselineUnderruns = count;
            if (aligned && !counterUnreliable && suspectSinceMicros < 0) {
                suspectSinceMicros = now;
            }
        }
        if (suspectSinceMicros < 0) {
            return;
        }
        if (!aligned || counterUnreliable) {
            suspectSinceMicros = -1;
        } else if (starvationEvidence(now)) {
            suspectSinceMicros = -1;
            spuriousInARow = 0;
            underrunsCorroborated++;
            trace.add(now, TraceKind.UNDERRUN_JUDGED, 1, 0, aheadMs(now));
            flushForRecovery(now, FlushReason.UNDERRUN);
        } else if (now - suspectSinceMicros >= cfg.underrunConfirmMs * 1000L) {
            suspectSinceMicros = -1;
            underrunsSpurious++;
            spuriousInARow++;
            trace.add(now, TraceKind.UNDERRUN_JUDGED, 2, spuriousInARow, aheadMs(now));
            if (spuriousInARow >= cfg.unreliableAfterRises) {
                counterUnreliable = true;
                trace.add(now, TraceKind.UNDERRUN_JUDGED, 3, spuriousInARow, aheadMs(now));
            }
        }
    }

    private boolean starvationEvidence(long now) {
        if (!tracker.isValid()) {
            return false;
        }
        return tracker.stalled(now) || tracker.presentationMicros(writtenFrames) - now <= cfg.underrunAheadMs * 1000L;
    }

    private long aheadMs(long now) {
        return tracker.isValid() && rate > 0 ? (tracker.presentationMicros(writtenFrames) - now) / 1000L : -1;
    }

    /** The output stood still although it holds data (it froze): flush and realign, as for any other lost output. */
    private void checkStall(long now) {
        if (aligned && tracker.isValid() && tracker.stalled(now)) {
            flushForRecovery(now, FlushReason.POSITION_STALL);
        }
    }

    private void flushForRecovery(long now, FlushReason reason) {
        if (now - lastFlushAtMicros < cfg.minFlushIntervalMs * 1000L) {
            flushesSuppressed++;
            dropTimeline();
            return;
        }
        flushSink(now, reason);
    }

    private void flushSink(long now, FlushReason reason) {
        lastFlushAtMicros = now;
        trace.add(now, TraceKind.FLUSH, reason.ordinal(), writtenFrames, lastDrift);
        if (sinkStarted || writtenFrames > 0) {
            flushes++; // a fresh output that never ran is not a flush worth reporting
            flushesByReason[reason.ordinal()]++;
        }
        if (sinkOpen && (sinkStarted || writtenFrames > 0)) {
            sink.flush();
            baselineUnderruns = sink.underrunCount();
        }
        sinkStarted = false;
        primeTraced = false;
        writtenFrames = 0;
        tracker.reset(now);
        lastPositionPollMicros = Long.MIN_VALUE / 2;
        switch (reason) {
            case HARD_RESYNC:
            case UNDERRUN:
            case WRITER_BEHIND:
            case POSITION_STALL:
                keepTimeline(); // only the output was lost: the decoded audio is still on its timeline
                break;
            default:
                dropTimeline(); // another stream or a new connection: whatever is queued belongs to the old one
                break;
        }
    }

    /**
     * After a flush of the output alone: the decoded audio and the anchor stay, so the next write continues the stream
     * where the output left it. Only the alignment is redone (from the first position the new output gives): the audio that
     * was in the output is lost, the rest is padded or skipped exactly to its time, which costs the least gap a flush can.
     */
    private void keepTimeline() {
        aligned = false;
        alignStartTraced = false;
        stepTraced = false;
        pending = null;
        drift.reset();
        lastDriftRevision = -1;
        hardLateReports = 0;
        suspectSinceMicros = -1;
    }

    private long targetMicros(long streamFrame) {
        long serverPlay = anchorServerMicros + Math.floorDiv(streamFrame * 1_000_000L, (long) rate)
                + bufferMs * 1000L - totalLatencyMs() * 1000L;
        return serverPlay - offsets.offsetMicros();
    }

    private int framesFor(long micros) {
        long frames = (Math.min(micros, MAX_FRAMES_FOR_MICROS) * rate + 500_000L) / 1_000_000L;
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, frames));
    }

    /** Decides the next output (silence, a skip, or audio); true when {@code out*} holds something to write. */
    private boolean nextOutput(long now) {
        if (!tracker.isValid()) {
            outSilence(blockFrames);
            return true;
        }
        Block head = fifo.peekFirst();
        if (head == null) {
            outSilence(blockFrames);
            return true;
        }
        long raw = tracker.presentationMicros(writtenFrames) - targetMicros(head.start + head.consumed);
        lastDrift = raw;
        boolean exact = head.exact || !aligned;
        if (exact) {
            if (!aligned && !alignStartTraced && (raw < -cfg.alignToleranceMicros || raw > cfg.alignToleranceMicros)) {
                alignStartTraced = true;
                trace.add(now, TraceKind.ALIGN_START, raw, writtenFrames, fifoFrames * 1000L / Math.max(1, rate));
            }
            if (raw < -cfg.alignToleranceMicros) {
                if (-raw > cfg.maxPadMs * 1000L) {
                    dropTimeline(); // too far ahead to wait for
                    return false;
                }
                int pad = Math.min(blockFrames, framesFor(-raw));
                if (!stepTraced) {
                    stepTraced = true;
                    trace.add(now, TraceKind.ALIGN_STEP, pad, 0, raw);
                }
                stepPadded += pad;
                outSilence(pad);
                return true;
            }
            if (raw > cfg.alignToleranceMicros) {
                boolean suspicious = aligned && raw > cfg.hardResyncMs * 1000L;
                if (suspicious) {
                    // One report that says "late" may be a stale or odd one: act only when several fresh ones agree.
                    if (tracker.revision() != hardLateRevision) {
                        hardLateRevision = tracker.revision();
                        hardLateReports++;
                        trace.add(now, TraceKind.HARD_LATE, raw, hardLateReports);
                    }
                    if (hardLateReports >= cfg.hardResyncReports && now - lastFlushAtMicros >= cfg.minFlushIntervalMs * 1000L) {
                        lastHardResyncDriftMicros = raw;
                        flushSink(now, FlushReason.HARD_RESYNC); // the output holds old audio that is late
                        return false;
                    }
                } else {
                    hardLateReports = 0;
                }
                if (suspicious) {
                    // not confirmed yet: play on as the position was before, do not skip audio on one report
                    return writeNext(head, now);
                }
                int skip = Math.min(framesFor(raw), head.remaining());
                if (!stepTraced) {
                    stepTraced = true;
                    trace.add(now, TraceKind.ALIGN_STEP, 0, skip, raw);
                }
                head.consumed += skip;
                fifoFrames -= skip;
                stepSkipped += skip;
                if (head.remaining() == 0) {
                    fifo.pollFirst();
                }
                return false;
            }
            head.exact = false;
            hardLateReports = 0;
            if (!aligned) {
                trace.add(now, TraceKind.ALIGNED, raw, stepPadded, stepSkipped);
                aligned = true;
                drift.reset();
                lastDriftRevision = tracker.revision();
            }
        } else {
            long limit = cfg.gradualLimitMs * 1000L;
            if (raw > limit || raw < -limit) {
                head.exact = true; // a step: done exactly, not frame by frame
                return false;
            }
            hardLateReports = 0;
            if (tracker.revision() != lastDriftRevision) {
                lastDriftRevision = tracker.revision();
                drift.addMeasurement(raw);
            }
        }
        return writeNext(head, now);
    }

    /** Outputs the next block of the head of the queue (with the gradual correction). */
    private boolean writeNext(Block head, long now) {
        int n = Math.min(blockFrames, head.remaining());
        int correction = drift.plan(n, rate);
        int fb = outFrameBytes;
        if (correction == 0) {
            outData = head.pcm;
            outOffset = head.consumed * fb;
            outLength = n * fb;
        } else {
            byte[] corrected = FrameCorrector.apply(head.pcm, head.consumed * fb, n, fb, correction);
            int used = n - corrected.length / fb; // positive: dropped
            if (used > 0) {
                correctionDropped += used;
            } else {
                correctionRepeated -= used;
            }
            drift.applied(used * 1_000_000L / rate);
            outData = corrected;
            outOffset = 0;
            outLength = corrected.length;
        }
        head.consumed += n;
        fifoFrames -= n;
        if (head.remaining() == 0) {
            fifo.pollFirst();
        }
        lastAudioWrittenMicros = now;
        return true;
    }

    private void outSilence(int frames) {
        outData = silence;
        outOffset = 0;
        outLength = Math.min(frames, blockFrames) * outFrameBytes;
    }

    /** @return false when the output is full (the rest waits in {@code pending}) */
    private boolean writeOutput(long now) {
        int took = write(outData, outOffset, outLength, now);
        if (terminal) {
            return false;
        }
        if (took < outLength) {
            int rest = outLength - took;
            pending = new byte[rest];
            System.arraycopy(outData, outOffset + took, pending, 0, rest);
            pendingOffset = 0;
            return false;
        }
        return true;
    }

    private boolean writePending(long now) {
        int left = pending.length - pendingOffset;
        int took = write(pending, pendingOffset, left, now);
        if (terminal) {
            return false;
        }
        if (took < left) {
            pendingOffset += took;
            return false;
        }
        pending = null;
        return true;
    }

    private int write(byte[] data, int offset, int length, long now) {
        try {
            int took = sink.write(data, offset, length);
            took = Math.max(0, Math.min(length, took)) / outFrameBytes * outFrameBytes;
            if (took > 0) {
                writtenFrames += took / outFrameBytes;
                if (!sinkStarted) {
                    trace.add(now, TraceKind.FIRST_WRITE, took / outFrameBytes, sink.startThresholdFrames());
                    sink.start();
                    sinkStarted = true;
                    sinkStartedAtMicros = now;
                    trace.add(now, TraceKind.PLAY, writtenFrames, sink.startThresholdFrames());
                }
            }
            return took;
        } catch (AudioSinkException e) {
            fail(EngineFailure.SINK_FAILED);
            return 0;
        }
    }

    // ---- state and status

    private void updateState(long now) {
        if (terminal || connectionLost) {
            return;
        }
        if (header == null) {
            state = EngineState.WAITING_FOR_STREAM;
        } else if (aligned && now - lastAudioWrittenMicros < 2_000_000L) {
            state = EngineState.PLAYING;
        } else if (!fifo.isEmpty() || (!aligned && anchorValid)) {
            state = EngineState.ALIGNING;
        } else {
            state = EngineState.BUFFERING;
        }
    }

    /** The event trace (thread safe; it outlives the engine's session in the last status). */
    public EventTrace trace() {
        return trace;
    }

    /** A snapshot; call it from the engine thread. */
    public EngineStatus status() {
        long now = clock.nowMicros();
        EngineStatus s = new EngineStatus();
        s.state = state;
        s.failure = failure;
        if (header != null) {
            s.codec = header.getCodec().wireName();
            s.sampleRate = header.getSampleRate();
            s.channels = header.getChannels();
            s.sourceBits = header.getBitsPerSample();
        }
        s.clockSynchronized = offsets.isSynchronized();
        s.offsetMicros = reportedOffset;
        s.jitterMicros = reportedJitter;
        s.epoch = offsets.epoch();
        if (tracker.isValid() && rate > 0) {
            s.outputAheadMs = (tracker.presentationMicros(writtenFrames) - now) / 1000L;
            s.positionAgeMs = tracker.ageMicros(now) / 1000L;
        }
        s.decodedWaitingMs = rate > 0 ? fifoFrames * 1000L / rate : 0;
        s.chunksWaiting = scheduler.queuedChunks();
        s.driftMicros = drift.isReady() ? drift.smoothedMicros() : lastDrift;
        s.aligned = aligned;
        s.positionSource = sink.positionSource();
        s.manualLatencyMs = manualLatencyMs;
        s.serverLatencyMs = serverLatencyMs;
        s.bufferMs = bufferMs;
        s.volume = volume;
        s.muted = muted;
        s.chunksReceived = chunksReceived;
        s.chunksPlayed = chunksPlayed;
        s.droppedLate = scheduler.droppedLate();
        s.droppedTooFarAhead = scheduler.droppedTooFarAhead();
        s.droppedQueueFull = scheduler.droppedQueueFull();
        s.droppedOversized = droppedOversized;
        s.droppedFifoFull = droppedFifoFull;
        s.droppedOverlap = droppedOverlap;
        s.droppedPartialBytes = droppedPartialBytesFromOldDecoders + (decoder != null ? decoder.droppedPartialBytes() : 0);
        s.decodeFailures = decodeFailures;
        s.gapsFilled = gapsFilled;
        s.correctionDroppedFrames = correctionDropped;
        s.correctionRepeatedFrames = correctionRepeated;
        s.stepSkippedFrames = stepSkipped;
        s.stepPaddedFrames = stepPadded;
        s.timelineResyncs = timelineResyncs;
        s.timelineRebases = timelineRebases;
        s.streamHolesRecovered = streamHolesRecovered;
        s.holeOverflows = holeOverflows;
        s.flushes = flushes;
        s.flushesHardResync = flushesByReason[FlushReason.HARD_RESYNC.ordinal()];
        s.flushesUnderrun = flushesByReason[FlushReason.UNDERRUN.ordinal()];
        s.flushesWriterBehind = flushesByReason[FlushReason.WRITER_BEHIND.ordinal()];
        s.flushesHeader = flushesByReason[FlushReason.HEADER.ordinal()];
        s.flushesConnectionLost = flushesByReason[FlushReason.CONNECTION_LOST.ordinal()];
        s.flushesPositionStall = flushesByReason[FlushReason.POSITION_STALL.ordinal()];
        s.lastHardResyncDriftMicros = lastHardResyncDriftMicros;
        s.underrunsCorroborated = underrunsCorroborated;
        s.underrunsSpurious = underrunsSpurious;
        s.underrunCounterUnreliable = counterUnreliable;
        s.trace = trace;
        s.outputSummary = sink.describe();
        s.positionReportsAccepted = tracker.acceptedCount();
        s.positionReportsRejected = tracker.rejectedCount() + tracker.outlierCount();
        s.flushesSuppressed = flushesSuppressed;
        s.underruns = underruns;
        s.headersIgnored = headersIgnored;
        s.decoderStarts = decoderStarts;
        s.epochChanges = epochChanges;
        s.connectionLosses = connectionLosses;
        s.elapsedMs = (now - startedAtMicros) / 1000L;
        return s;
    }
}
