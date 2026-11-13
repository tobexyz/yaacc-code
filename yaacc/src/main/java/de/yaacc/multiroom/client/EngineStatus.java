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

/**
 * A snapshot of the engine for the status screen and the tests: plain numbers and typed values, nothing from the
 * server except the numbers it influenced. The fields are public on purpose (a data holder); by convention nobody changes
 * a snapshot after it was published, a changed one is made from {@link #copy()}.
 */
public final class EngineStatus implements Cloneable {

    public EngineState state = EngineState.WAITING_FOR_STREAM;
    public EngineFailure failure;
    /** "pcm", "flac" or "" */
    public String codec = "";
    public int sampleRate;
    public int channels;
    public int sourceBits;
    public boolean clockSynchronized;
    public long offsetMicros;
    public long jitterMicros;
    public int epoch;
    /** Audio written to the output and not yet played, in ms; -1 when the output has not reported a position. */
    public long outputAheadMs = -1;
    /** Decoded audio waiting for its time, in ms. */
    public long decodedWaitingMs;
    public int chunksWaiting;
    /** The drift (median) in microseconds, positive: late. */
    public long driftMicros;
    public boolean aligned;
    public String positionSource = "";
    public long positionAgeMs = -1;
    public int manualLatencyMs;
    public int serverLatencyMs;
    public int bufferMs;
    public int volume;
    public boolean muted;

    public long chunksReceived;
    public long chunksPlayed;
    public long droppedLate;
    public long droppedTooFarAhead;
    public long droppedQueueFull;
    public long droppedOversized;
    public long droppedFifoFull;
    public long droppedOverlap;
    public long droppedPartialBytes;
    public long decodeFailures;
    public long gapsFilled;
    public long correctionDroppedFrames;
    public long correctionRepeatedFrames;
    public long stepSkippedFrames;
    public long stepPaddedFrames;
    public long timelineResyncs;
    /** Times the timeline was shifted in place to keep the frame counts small (not a glitch). */
    public long timelineRebases;
    /** Holes in a flac stream (a chunk dropped or failed) that were recovered by anchoring anew. */
    public long streamHolesRecovered;
    /** Times more holes were pending than are remembered one by one (the stream is then placed by every chunk's timestamp). */
    public long holeOverflows;
    /** Chunks that found no room in the queue of the engine thread. */
    public long hostQueueDrops;
    public long flushes;
    public long flushesSuppressed;
    /** Flushes by what caused them: the sum is {@link #flushes} (a flush at a new header or a loss is counted there too). */
    public long flushesHardResync;
    public long flushesUnderrun;
    public long flushesWriterBehind;
    public long flushesHeader;
    public long flushesConnectionLost;
    public long flushesPositionStall;
    /** Rises of the platform's underrun counter that the position confirmed / that it did not (no starvation). */
    /** How far ahead of the playback position the engine keeps the output filled, in ms. */
    public int writeAheadMs;
    public long writes;
    /** Writes that took fewer frames than offered, the frames missing in all of them and in the worst one. */
    public long shortWrites;
    public long shortWriteFramesMissing;
    public long worstShortWriteFrames;
    public long maxWriteMicros;
    /** The longest time between two ticks of the engine thread, in ms. */
    public long maxTickGapMs;
    /** The longest one engine tick, one chunk (decode and queue) and one position poll took, in microseconds. */
    public long maxTickMicros;
    public long maxChunkMicros;
    public long maxPositionPollMicros;
    /** Blocks that had a frame dropped or repeated, and the most frames changed in one block. */
    public long correctionBlocks;
    public long correctionLargestBlockFrames;
    /** Exact pads or skips after the stream was aligned (a step in a playing stream). */
    public long stepsWhilePlaying;
    /** The frames padded or skipped in those steps. */
    public long stepsWhilePlayingFrames;
    /** True when a dropped or repeated frame is spread by interpolation (the service switches it on). */
    public boolean smoothCorrections;
    /** The median of the last drift measurements (what the corrections act on). */
    public long driftSmoothedMicros;
    /** Restarts of the stream after a pause: how many, the gap of the last one and how long its first audio took. */
    /** A flac stream found shifted against its timestamps (a stall of the source): how often, and the silence inserted. */
    public long flacShiftFills;
    public long flacShiftFilledMs;
    public long streamResumes;
    public long lastResumeGapMs;
    public long lastResumeToAudioMs = -1;
    /** The connection's counters (null before there is one): frames by type, bytes, the server's settings. */
    public de.yaacc.multiroom.protocol.SnapcastConnection.Stats connection;
    /** Milliseconds since the engine received the last chunk, -1 when none yet. */
    public long lastChunkAgoMs = -1;
    public long underrunsCorroborated;
    /** Rises of the counter that the position did not confirm (the output is not dry, but its cushion is too thin). */
    public long underrunRisesThin;
    /** The write-ahead in force (raised from {@link #writeAheadMs} when the counter rises) and how often it was raised. */
    public int writeAheadEffectiveMs;
    public long writeAheadRaises;

    /** The drift (positive: late) that caused the latest hard resync. */
    public long lastHardResyncDriftMicros;
    /** Reports of the output's position taken into account and refused (stale, from the future, going back, ...). */
    public long positionReportsAccepted;
    public long positionReportsRejected;
    public long underruns;
    /** The bounded event trace (shared, thread safe); null before the engine existed. */
    public EventTrace trace;
    /** One line about the output: buffer, start threshold, underruns, timestamp health. */
    public String outputSummary = "";
    public long headersIgnored;
    public long decoderStarts;
    public long epochChanges;
    public long connectionLosses;
    public long elapsedMs;

    /** A copy to change without touching the object others hold (all fields are numbers, enums or strings). */
    public EngineStatus copy() {
        try {
            return (EngineStatus) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new IllegalStateException(e);
        }
    }
}
