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
 * server except the numbers it influenced. Immutable; the fields are public on purpose (a data holder).
 */
public final class EngineStatus {

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
    public long flushes;
    public long flushesSuppressed;
    public long underruns;
    public long headersIgnored;
    public long decoderStarts;
    public long epochChanges;
    public long connectionLosses;
    public long elapsedMs;
}
