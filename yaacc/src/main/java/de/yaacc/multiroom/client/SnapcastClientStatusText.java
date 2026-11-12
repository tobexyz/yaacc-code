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

import java.util.Locale;

/**
 * The live status of the spike as text: the numbers the owner reports after a listen test. English and technical on
 * purpose (it is a diagnostic dump, not a user message). Contains nothing from the server except numbers and fixed words.
 */
public final class SnapcastClientStatusText {

    private SnapcastClientStatusText() {
    }

    public static String of(SnapcastClientRuntime.View view, long nowMillis) {
        StringBuilder text = new StringBuilder();
        text.append("service: ").append(view.phase.name().toLowerCase(Locale.ROOT));
        if (view.ending != SnapcastClientRuntime.Ending.NONE) {
            text.append(" (").append(endingText(view.ending)).append(')');
        }
        text.append('\n');
        EngineStatus s = view.engine;
        if (s == null) {
            return text.toString();
        }
        text.append("engine: ").append(s.state.name().toLowerCase(Locale.ROOT)).append(s.aligned ? ", aligned" : ", not aligned").append('\n');
        if (s.failure != null) {
            text.append("error: ").append(failureText(s.failure)).append('\n');
        }
        if (!s.codec.isEmpty()) {
            text.append(String.format(Locale.ROOT, "stream: %s, %d Hz, %d ch, %d bit%n", s.codec, s.sampleRate, s.channels, s.sourceBits));
        }
        text.append(String.format(Locale.ROOT, "clock: %s, offset %s, jitter %s, epoch %d%n",
                s.clockSynchronized ? "synchronised" : "NOT synchronised", ms(s.offsetMicros), ms(s.jitterMicros), s.epoch));
        text.append(String.format(Locale.ROOT, "output: ahead %s, position age %s, source %s%n",
                s.outputAheadMs < 0 ? "-" : s.outputAheadMs + " ms", s.positionAgeMs < 0 ? "-" : s.positionAgeMs + " ms",
                s.positionSource.isEmpty() ? "-" : s.positionSource));
        if (s.outputSummary != null && !s.outputSummary.isEmpty()) {
            text.append("output details: ").append(s.outputSummary).append('\n');
        }
        text.append(String.format(Locale.ROOT, "sync: drift %s (+ = late)%s, buffer %d ms, server latency %d ms, manual latency %d ms%n",
                ms(s.driftMicros), s.aligned ? "" : " [last value, not current: no audio is being placed]",
 s.bufferMs, s.serverLatencyMs, s.manualLatencyMs));
        text.append(String.format(Locale.ROOT, "volume: %d%s%n", s.volume, s.muted ? " (muted)" : ""));
        text.append(String.format(Locale.ROOT, "chunks: received %d, queued %d, decoded waiting %d ms, played %d%n",
                s.chunksReceived, s.chunksWaiting, s.decodedWaitingMs, s.chunksPlayed));
        text.append(String.format(Locale.ROOT, "corrections: dropped %d frames, repeated %d frames%n",
                s.correctionDroppedFrames, s.correctionRepeatedFrames));
        text.append(String.format(Locale.ROOT, "steps: skipped %d frames, padded %d frames, gaps filled %d%n",
                s.stepSkippedFrames, s.stepPaddedFrames, s.gapsFilled));
        text.append(String.format(Locale.ROOT, "resyncs: timeline %d, flushes %d (suppressed %d), underruns %d, epoch changes %d%n",
                s.timelineResyncs, s.flushes, s.flushesSuppressed, s.underruns, s.epochChanges));
        text.append(String.format(Locale.ROOT, "flushes by cause: hard resync %d (last drift %s), underrun %d, writer behind %d, header %d, connection lost %d, position stall %d%n",
                s.flushesHardResync, ms(s.lastHardResyncDriftMicros), s.flushesUnderrun, s.flushesWriterBehind, s.flushesHeader,
                s.flushesConnectionLost, s.flushesPositionStall));
        text.append(String.format(Locale.ROOT, "underrun counter of the platform: %d rises, confirmed by the position %d, spurious %d%s%n",
                s.underruns, s.underrunsCorroborated, s.underrunsSpurious,
                s.underrunCounterUnreliable ? " (judged unreliable on this device: not used)" : ""));
        text.append(String.format(Locale.ROOT, "position reports: taken %d, refused %d%n", s.positionReportsAccepted,
                s.positionReportsRejected));
        text.append(String.format(Locale.ROOT, "stream: holes re-anchored (flac) %d, timeline rebases %d, chunks lost in the host queue %d%n",
                s.streamHolesRecovered, s.timelineRebases, s.hostQueueDrops));
        text.append(String.format(Locale.ROOT, "dropped: late %d, too far ahead %d, queue full %d, oversized %d, fifo full %d, overlap %d, partial bytes %d%n",
                s.droppedLate, s.droppedTooFarAhead, s.droppedQueueFull, s.droppedOversized, s.droppedFifoFull,
                s.droppedOverlap, s.droppedPartialBytes));
        text.append(String.format(Locale.ROOT, "decoder: starts %d, failures %d, headers ignored %d, connection losses %d%n",
                s.decoderStarts, s.decodeFailures, s.headersIgnored, s.connectionLosses));
        long seconds = s.elapsedMs / 1000;
        text.append(String.format(Locale.ROOT, "elapsed: %d:%02d:%02d%n", seconds / 3600, seconds / 60 % 60, seconds % 60));
        return text.toString();
    }

    private static String ms(long micros) {
        return String.format(Locale.ROOT, "%+.2f ms", micros / 1000.0);
    }

    static String endingText(SnapcastClientRuntime.Ending ending) {
        switch (ending) {
            case USER:
                return "stopped by the user";
            case BAD_ADDRESS:
                return "the address is not valid";
            case UNRESOLVED:
                return "the name did not resolve";
            case CONNECTION_GAVE_UP:
                return "the connection to the server failed repeatedly";
            case SERVER_REFUSED:
                return "the server refused this client (authorization, codec or format)";
            case ENGINE_FAILED:
                return "the audio engine failed";
            case SERVICE_ERROR:
                return "internal error of the service";
            case NO_AUDIO:
                return "no audio for 15 minutes, so the session was ended (the locks are only held while it plays)";
            case DISABLED:
                return "the option was switched off";
            default:
                return "";
        }
    }

    static String failureText(EngineFailure failure) {
        switch (failure) {
            case UNSUPPORTED_CHANNELS:
                return "the stream has more than two channels (only mono and stereo are played)";
            case UNSUPPORTED_SINK_FORMAT:
                return "the device cannot play this sample rate";
            case SINK_FAILED:
                return "the audio output failed";
            case DECODER_UNAVAILABLE:
                return "this device has no decoder for the codec of the stream";
            case DECODER_BAD_HEADER:
                return "the decoder refused the header of the stream";
            case DECODER_FAILED:
                return "chunks kept failing to decode";
            case HEADER_FLOOD:
                return "the server sent too many different codec headers";
            case NO_PLAYBACK_POSITION:
                return "the audio output never reported its position, so sync cannot be controlled";
            case STALLED:
                return "the audio thread stopped responding";
            default:
                return "internal error";
        }
    }
}
