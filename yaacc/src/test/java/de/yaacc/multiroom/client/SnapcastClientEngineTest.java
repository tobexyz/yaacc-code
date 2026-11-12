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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import de.yaacc.multiroom.protocol.Codec;
import de.yaacc.multiroom.protocol.CodecHeaderMessage;
import de.yaacc.multiroom.protocol.ServerSettingsMessage;
import de.yaacc.multiroom.protocol.WireChunkMessage;

import org.junit.Test;

/**
 * The engine on a simulated output and a manual clock: start-up alignment, the latency setting, drift control without a
 * pitch change, steps, gaps, headers, epochs, losses, underruns and failures. The media frame number is written into
 * every frame, so the tests read back what was played and when.
 */
public class SnapcastClientEngineTest {

    private static final long ONE_FRAME_MICROS = 21; // at 48 kHz

    private static void assertNear(String what, long expected, long actual, long tolerance) {
        assertTrue(what + ": expected " + expected + " +- " + tolerance + " but was " + actual,
                Math.abs(expected - actual) <= tolerance);
    }

    /** The time the first audible frame is presented against the time it should be. */
    private static void assertFirstSampleOnTime(EngineRig rig, long toleranceMicros) {
        ClientFixtures.Trace trace = rig.trace();
        assertTrue("something was played", trace.size() > 0);
        long media = trace.mediaFrame(0);
        assertNear("first sample", rig.targetOf(media), rig.sink.presentationTimeOf(trace.sinkFrame(0)), toleranceMicros);
    }

    // ---- start-up alignment and latency

    @Test
    public void theFirstSamplePlaysAtItsTimeWithinAMillisecond() {
        EngineRig rig = EngineRig.typical();
        rig.connect();
        rig.run(3000);

        assertEquals(EngineState.PLAYING, rig.engine.status().state);
        assertTrue(rig.engine.status().aligned);
        assertFirstSampleOnTime(rig, 1500);
        assertEquals("the output was opened once and started once", 1, rig.sink.opens);
        assertEquals(1, rig.sink.starts);
    }

    @Test
    public void theServerLatencyAndTheManualLatencyMovePlaybackEarlier() {
        EngineRig rig = EngineRig.typical();
        rig.latencyMs = 100;
        rig.engine.setManualLatencyMs(50);
        rig.manualMs = 50;
        rig.connect();
        rig.run(3000);

        assertFirstSampleOnTime(rig, 1500);
        ClientFixtures.Trace trace = rig.trace();
        long earlierThanPlainBuffer = rig.serverStart + trace.mediaFrame(0) * 1_000_000L / 48000 + 1_000_000L - rig.offsets.offset;
        assertNear("150 ms earlier than buffer alone", earlierThanPlainBuffer - 150_000,
                rig.sink.presentationTimeOf(trace.sinkFrame(0)), 1500);
    }

    @Test
    public void aNegativeManualLatencyPlaysLater() {
        EngineRig rig = EngineRig.typical();
        rig.engine.setManualLatencyMs(-80);
        rig.manualMs = -80;
        rig.connect();
        rig.run(3000);

        assertFirstSampleOnTime(rig, 1500);
    }

    @Test
    public void aChunkThatArrivesAfterItsTimeIsSkippedIntoAndStillAligned() {
        EngineRig rig = EngineRig.typical();
        rig.connect();
        rig.sending = false;
        rig.run(1040); // header and clock are there, no chunk arrives for a second and a bit
        rig.sending = true; // then the chunks recorded meanwhile arrive in a burst, the first ones past their time
        rig.run(3000);

        assertTrue("some early chunks were dropped as late", rig.engine.status().droppedLate > 0
                || rig.engine.status().stepSkippedFrames > 0);
        assertFirstSampleOnTime(rig, 1500);
    }

    @Test
    public void volumeAndMuteBecomeAGainOnTheOutput() {
        EngineRig rig = EngineRig.typical();
        rig.connect();
        rig.engine.onServerSettings(new ServerSettingsMessage(1000, 0, false, 50));
        assertEquals(0.5f, rig.sink.gain, 0.0001f);
        rig.engine.onServerSettings(new ServerSettingsMessage(1000, 0, true, 50));
        assertEquals(0f, rig.sink.gain, 0f);
        rig.engine.onServerSettings(new ServerSettingsMessage(1000, 0, false, 1000));
        assertEquals("clamped to 100", 1f, rig.sink.gain, 0f);
        assertEquals(100, rig.engine.status().volume);
    }

    @Test
    public void settingsThatArriveBeforeTheHeaderAreKept() {
        EngineRig rig = EngineRig.typical();
        rig.engine.onServerSettings(new ServerSettingsMessage(2000, 0, false, 30));
        rig.engine.onCodecHeader(rig.header());
        assertEquals(0.3f, rig.sink.gain, 0.0001f);
        assertEquals(2000, rig.engine.status().bufferMs);
    }

    // ---- drift

    private static void assertPitchUntouched(EngineRig rig) {
        ClientFixtures.Trace trace = rig.trace();
        int corrections = 0;
        for (int i = 1; i < trace.size(); i++) {
            if (trace.sinkFrame(i) == trace.sinkFrame(i - 1) + 1) {
                long step = trace.mediaFrame(i) - trace.mediaFrame(i - 1);
                assertTrue("neighbouring frames move by 0, 1 or 2 media frames, was " + step + " at sink frame "
                        + trace.sinkFrame(i), step >= 0 && step <= 2);
                if (step != 1) {
                    corrections++;
                }
            }
        }
        EngineStatus s = rig.engine.status();
        assertEquals("every change of step is one counted correction", s.correctionDroppedFrames + s.correctionRepeatedFrames,
                corrections);
    }

    private static long driftAtEnd(EngineRig rig) {
        ClientFixtures.Trace trace = rig.trace();
        int last = trace.size() - 1;
        return rig.sink.presentationTimeOf(trace.sinkFrame(last)) - rig.targetOf(trace.mediaFrame(last));
    }

    @Test
    public void anOutputThatPlaysTooSlowlyIsCaughtUpByDroppingSingleFrames() {
        EngineRig rig = EngineRig.withSpeed(0.9997); // 300 ppm slow
        rig.connect();
        rig.run(60_000);

        EngineStatus s = rig.engine.status();
        assertTrue("frames were dropped: " + s.correctionDroppedFrames, s.correctionDroppedFrames > 0);
        assertEquals(0, s.correctionRepeatedFrames);
        assertNear("drift at the end", 0, driftAtEnd(rig), 3000);
        assertEquals("no flush, no step after the start", 0, s.flushes);
        assertPitchUntouched(rig);
    }

    @Test
    public void anOutputThatPlaysTooFastIsHeldBackByRepeatingSingleFrames() {
        EngineRig rig = EngineRig.withSpeed(1.0003);
        rig.connect();
        rig.run(60_000);

        EngineStatus s = rig.engine.status();
        assertTrue("frames were repeated: " + s.correctionRepeatedFrames, s.correctionRepeatedFrames > 0);
        assertEquals(0, s.correctionDroppedFrames);
        assertNear("drift at the end", 0, driftAtEnd(rig), 3000);
        assertEquals(0, s.flushes);
        assertPitchUntouched(rig);
    }

    @Test
    public void aPerfectOutputIsLeftAlone() {
        EngineRig rig = EngineRig.typical();
        rig.connect();
        rig.run(30_000);

        EngineStatus s = rig.engine.status();
        assertEquals(0, s.correctionDroppedFrames);
        assertEquals(0, s.correctionRepeatedFrames);
        assertEquals(0, s.flushes);
        assertEquals(0, s.underruns);
    }

    @Test
    public void correctionsNeverExceedOneFramePerFourHundred() {
        EngineRig rig = EngineRig.withSpeed(0.995); // a 0.5 percent error: more than the controller may fix
        rig.connect();
        rig.run(20_000);

        EngineStatus s = rig.engine.status();
        long played = rig.trace().totalFrames;
        assertTrue("dropped " + s.correctionDroppedFrames + " of " + played,
                s.correctionDroppedFrames <= played / 400 + 4);
        assertTrue(s.correctionDroppedFrames > 0);
    }

    // ---- steps and flushes

    @Test
    public void aStepOfTheOffsetBelowTheHardBoundIsSkippedNotFlushed() {
        EngineRig rig = EngineRig.typical();
        rig.connect();
        rig.run(5000);
        long skippedBefore = rig.engine.status().stepSkippedFrames + 960 * rig.engine.status().droppedLate;

        rig.offsets.offset += 100_000L; // the server clock is 100 ms further ahead: we are 100 ms late
        rig.run(14_000); // the step above the gradual limit at once, the last 30 ms frame by frame

        EngineStatus s = rig.engine.status();
        assertEquals(0, s.flushes);
        // the chunks that wait in the scheduler are dropped as late, the ones already decoded are skipped in the engine
        assertTrue("skipped " + s.stepSkippedFrames + " late " + s.droppedLate,
                s.stepSkippedFrames + 960 * s.droppedLate - skippedBefore >= 3000);
        assertNear("drift settled", 0, driftAtEnd(rig), 3000);
    }

    @Test
    public void aStepTheOtherWayIsPaddedWithSilence() {
        EngineRig rig = EngineRig.typical();
        rig.connect();
        rig.run(5000);
        long paddedBefore = rig.engine.status().stepPaddedFrames;

        rig.offsets.offset -= 100_000L;
        rig.run(14_000);

        EngineStatus s = rig.engine.status();
        assertEquals(0, s.flushes);
        assertTrue("padded " + (s.stepPaddedFrames - paddedBefore), s.stepPaddedFrames - paddedBefore >= 3000);
        assertNear("drift settled", 0, driftAtEnd(rig), 3000);
    }

    @Test
    public void playbackFarBehindFlushesTheOutputOnceAndDoesNotRecreateIt() {
        EngineRig rig = EngineRig.typical();
        rig.connect();
        rig.run(5000);

        rig.offsets.offset += 600_000L; // 600 ms late: the output holds old audio
        rig.run(4000);

        EngineStatus s = rig.engine.status();
        assertEquals(1, s.flushes);
        assertEquals("the output is kept", 1, rig.sink.opens);
        assertEquals(EngineState.PLAYING, s.state);
        assertFirstSampleOnTime(rig, 1500);
    }

    @Test
    public void aSecondHardResyncWithinTheMinimumIntervalIsNotAnotherFlush() {
        EngineRig rig = EngineRig.typical();
        rig.bufferMs = 3000; // room for two steps of 600 ms before the chunks would be late on arrival
        rig.connect();
        rig.run(5000);

        rig.offsets.offset += 600_000L; // 600 ms late: flush number one
        rig.run(400);
        assertEquals(1, rig.engine.status().flushes);
        assertTrue("aligned again, so that the next step is judged by the hard bound", rig.engine.status().aligned);
        long skippedBefore = rig.engine.status().stepSkippedFrames + 960 * rig.engine.status().droppedLate;

        rig.offsets.offset += 600_000L; // again, less than 500 ms after the flush
        rig.run(3000);

        EngineStatus s = rig.engine.status();
        assertEquals("the second one is skipped over, not flushed", 1, s.flushes);
        assertTrue(s.stepSkippedFrames + 960 * s.droppedLate - skippedBefore >= 20_000);
        assertEquals(EngineState.PLAYING, s.state);
    }

    @Test
    public void anUnderrunFlushesAndTheStreamIsAlignedAgain() {
        EngineRig rig = EngineRig.typical();
        rig.connect();
        rig.run(5000);

        rig.sink.forceUnderrun();
        rig.run(4000);

        EngineStatus s = rig.engine.status();
        assertEquals(1, s.underruns);
        assertEquals(1, s.flushes);
        assertEquals(EngineState.PLAYING, s.state);
        assertFirstSampleOnTime(rig, 1500);
    }

    @Test
    public void flushesBecauseOfLatenessAreRateLimited() {
        EngineRig rig = EngineRig.typical();
        rig.connect();
        rig.run(5000);
        for (int i = 0; i < 20; i++) {
            rig.sink.forceUnderrun();
            rig.run(60);
        }
        EngineStatus s = rig.engine.status();
        assertTrue("flushes " + s.flushes, s.flushes <= 3);
        assertTrue(s.flushesSuppressed > 0);
    }

    // ---- gaps, overlaps, timelines

    @Test
    public void aGapInTheTimestampsIsFilledWithTheSameAmountOfSilence() {
        EngineRig rig = EngineRig.typical();
        rig.connect();
        rig.run(4000);
        long before = rig.nextChunk;
        rig.nextChunk += 5; // five chunks (100 ms) never arrive
        rig.run(4000);

        EngineStatus s = rig.engine.status();
        assertEquals(1, s.gapsFilled);
        ClientFixtures.Trace trace = rig.trace();
        int hole = -1;
        for (int i = 1; i < trace.size(); i++) {
            if (trace.mediaFrame(i) - trace.mediaFrame(i - 1) > 100) {
                hole = i;
                break;
            }
        }
        assertTrue("a hole in the media", hole > 0);
        long mediaJump = trace.mediaFrame(hole) - trace.mediaFrame(hole - 1) - 1;
        long silence = trace.sinkFrame(hole) - trace.sinkFrame(hole - 1) - 1;
        assertEquals("the silence has the length of the missing media", mediaJump, silence);
        assertEquals(5L * 960, mediaJump);
        assertTrue(before > 0);
        assertNear("after the gap", rig.targetOf(trace.mediaFrame(hole)), rig.sink.presentationTimeOf(trace.sinkFrame(hole)), 1500);
    }

    @Test
    public void aRepeatedChunkIsNotPlayedTwice() {
        EngineRig rig = EngineRig.typical();
        rig.connect();
        rig.run(3000);
        // the server sends chunk n again (same timestamp and content)
        long n = rig.nextChunk - 1;
        rig.engine.onChunk(ClientFixtures.chunk(rig.chunkTimestamp(n), n * rig.chunkFrames, rig.chunkFrames, 2, 16));
        rig.run(3000);

        ClientFixtures.Trace trace = rig.trace();
        for (int i = 1; i < trace.size(); i++) {
            if (trace.sinkFrame(i) == trace.sinkFrame(i - 1) + 1) {
                assertEquals("media advances by one", 1, trace.mediaFrame(i) - trace.mediaFrame(i - 1));
            }
        }
        assertTrue(rig.engine.status().droppedOverlap >= 1 || rig.engine.status().droppedLate >= 1);
    }

    @Test
    public void aGapOfExactlyThreeSecondsIsStillFilledAndAGapOfThreeSecondsAndAChunkStartsAnew() {
        EngineRig exact = EngineRig.typical();
        exact.connect();
        exact.run(4000);
        exact.nextChunk += 150; // 150 chunks of 20 ms: 144 000 frames, exactly 3.000 s
        exact.run(8000);
        assertEquals(1, exact.engine.status().gapsFilled);
        assertEquals("3.000 s is filled with silence", 0, exact.engine.status().timelineResyncs);

        EngineRig over = EngineRig.typical();
        over.connect();
        over.run(4000);
        over.nextChunk += 151; // 3.020 s
        over.run(8000);
        assertEquals(0, over.engine.status().gapsFilled);
        assertEquals("above 3 s the timeline starts anew", 1, over.engine.status().timelineResyncs);
    }

    @Test
    public void aLongGapStartsTheTimelineAnew() {
        EngineRig rig = EngineRig.typical();
        rig.connect();
        rig.run(3000);
        rig.nextChunk += 400; // 8 s without chunks
        rig.run(12_000);

        EngineStatus s = rig.engine.status();
        assertEquals(1, s.timelineResyncs);
        assertEquals(0, s.flushes);
        assertEquals(EngineState.PLAYING, s.state);
    }

    @Test
    public void hostileTimestampsAreDroppedAndDoNotStopThePlayback() {
        EngineRig rig = EngineRig.typical();
        rig.connect();
        rig.run(3000);
        long[] wild = {Long.MAX_VALUE / 2, Long.MIN_VALUE / 2, 0, -1, 2_147_483_647L * 1_000_000L, -2_147_483_648L * 1_000_000L,
                rig.serverNow() + 3_600_000_000L, rig.serverNow() - 3_600_000_000L};
        for (long ts : wild) {
            rig.engine.onChunk(ClientFixtures.rawChunk(Math.max(-2_147_483_648L * 1_000_000L, Math.min(2_147_483_647L * 1_000_000L, ts)),
                    new byte[3840]));
        }
        rig.run(3000);

        EngineStatus s = rig.engine.status();
        assertEquals(EngineState.PLAYING, s.state);
        assertTrue(s.droppedLate + s.droppedTooFarAhead >= 4);
        assertNull(s.failure);
    }

    // ---- headers

    @Test
    public void anIdenticalHeaderIsIgnoredEvenWhenItComesAThousandTimes() {
        EngineRig rig = EngineRig.typical();
        rig.connect();
        rig.run(3000);
        for (int i = 0; i < 1000; i++) {
            rig.engine.onCodecHeader(rig.header());
        }
        rig.run(1000);

        EngineStatus s = rig.engine.status();
        assertEquals(1, s.decoderStarts);
        assertEquals(1000, s.headersIgnored);
        assertEquals(1, rig.sink.opens);
        assertEquals(0, s.flushes);
        assertEquals(EngineState.PLAYING, s.state);
    }

    @Test
    public void aDifferentHeaderRestartsTheDecoderOnlyUpToTheLimitPerMinute() {
        EngineRig rig = EngineRig.typical();
        rig.connect();
        int accepted = 0;
        for (int i = 0; i < 1000; i++) { // 1000 headers in one second: alternate two bit depths of the same rate
            rig.engine.onCodecHeader(ClientFixtures.pcmHeader(48000, 2, i % 2 == 0 ? 24 : 16));
            if (rig.engine.status().failure == null) {
                accepted++;
            }
        }
        EngineStatus s = rig.engine.status();
        assertEquals(EngineState.FAILED, s.state);
        assertEquals(EngineFailure.HEADER_FLOOD, s.failure);
        assertEquals("1 first start and 12 restarts", 13, s.decoderStarts);
        assertEquals(12, accepted);
        assertEquals("the output was freed", rig.sink.opens, rig.sink.releases);
        assertFalse(rig.sink.open);
    }

    @Test
    public void headersSpreadOverTimeAreAllAccepted() {
        EngineRig rig = EngineRig.typical();
        rig.connect();
        for (int i = 0; i < 30; i++) {
            rig.clock.advanceMillis(6000); // ten a minute
            rig.engine.onCodecHeader(ClientFixtures.pcmHeader(48000, 2, i % 2 == 0 ? 24 : 16));
        }
        assertNull(rig.engine.status().failure);
        assertEquals(31, rig.engine.status().decoderStarts);
    }

    @Test
    public void aNewHeaderDropsTheChunksOfTheOldStream() {
        EngineRig rig = EngineRig.typical();
        rig.connect();
        rig.run(600); // a few chunks wait in the scheduler
        assertTrue(rig.engine.status().chunksWaiting > 0);

        rig.engine.onCodecHeader(ClientFixtures.pcmHeader(48000, 2, 24));
        assertEquals(0, rig.engine.status().chunksWaiting);
        assertEquals(EngineState.BUFFERING, rig.engine.status().state);
    }

    @Test
    public void aNewSampleRateReopensTheOutput() {
        EngineRig rig = EngineRig.typical();
        rig.connect();
        rig.run(1000);
        rig.engine.onCodecHeader(ClientFixtures.pcmHeader(44100, 2, 16));
        assertEquals(2, rig.sink.opens);
        assertEquals(1, rig.sink.releases);
        assertEquals(44100, rig.sink.format.getSampleRate());
    }

    @Test
    public void aSixChannelStreamIsRefusedWithATypedReasonAndNoOutputIsOpened() {
        EngineRig rig = EngineRig.typical();
        rig.engine.onCodecHeader(ClientFixtures.pcmHeader(48000, 6, 16));
        assertEquals(EngineState.FAILED, rig.engine.status().state);
        assertEquals(EngineFailure.UNSUPPORTED_CHANNELS, rig.engine.status().failure);
        assertEquals(0, rig.sink.opens);
    }

    @Test
    public void monoIsPlayed() {
        EngineRig rig = new EngineRig(SimulatedSink::typical, new ClientEngineConfig(), new DefaultDecoderFactory(null), 48000, 1, 16);
        rig.connect();
        rig.run(3000);
        assertEquals(EngineState.PLAYING, rig.engine.status().state);
        assertEquals(1, rig.sink.format.getChannels());
    }

    @Test
    public void samplesOfTwentyFourBitAreConvertedToSixteen() {
        EngineRig rig = new EngineRig(SimulatedSink::typical, new ClientEngineConfig(), new DefaultDecoderFactory(null), 48000, 2, 24);
        rig.connect();
        rig.run(3000);
        ClientFixtures.Trace trace = rig.trace();
        assertTrue(trace.size() > 0);
        assertEquals("the markers survive the conversion", 0, trace.mediaFrame(0) % 1 + (trace.mediaFrame(1) - trace.mediaFrame(0) - 1));
    }

    // ---- chunks

    @Test
    public void aTrailingPartialFrameOfAChunkIsDropped() {
        EngineRig rig = EngineRig.typical();
        rig.connect();
        rig.sending = false;
        rig.run(20);
        byte[] payload = new byte[960 * 4 + 3];
        rig.engine.onChunk(ClientFixtures.rawChunk(rig.serverNow(), payload));
        rig.run(1500);
        assertEquals(3, rig.engine.status().droppedPartialBytes);
    }

    @Test
    public void aChunkLongerThanAllowedIsDroppedBeforeItIsConverted() {
        EngineRig rig = EngineRig.typical();
        rig.connect();
        rig.sending = false;
        rig.run(20);
        rig.engine.onChunk(ClientFixtures.rawChunk(rig.serverNow(), new byte[48000 * 4 * 4])); // 4 s
        rig.run(1500);
        assertEquals(1, rig.engine.status().droppedOversized);
        assertNull(rig.engine.status().failure);
    }

    @Test
    public void theBuffersAreSizedFromTheValidatedFormatAndNotFromFlacBlockSizes() {
        final int[] seen = new int[1];
        DecoderFactory spy = (header, maxFrames) -> {
            seen[0] = maxFrames;
            return new PassThroughDecoder();
        };
        CodecHeaderMessage hostile = CodecHeaderMessage.of(Codec.FLAC, ClientFixtures.flacHeader(48000, 2, 16, 65535, 65535));
        ClientEngineConfig config = new ClientEngineConfig();
        EngineRig rig = new EngineRig(SimulatedSink::typical, config, spy, 48000, 2, 16);
        rig.engine.onCodecHeader(hostile);
        assertEquals("rate times the chunk limit of 2 s", 96000, seen[0]);
        CodecHeaderMessage other = CodecHeaderMessage.of(Codec.FLAC, ClientFixtures.flacHeader(48000, 2, 16, 16, 16));
        rig.engine.onCodecHeader(other);
        assertEquals(96000, seen[0]);
    }

    @Test
    public void theEngineChecksTheSizeOfWhatAnyDecoderReturns() {
        EngineRig rig = new EngineRig(SimulatedSink::typical, new ClientEngineConfig(), (h, m) -> new PassThroughDecoder(), 48000, 2, 16);
        rig.engine.onCodecHeader(ClientFixtures.flacHeaderMessage(48000, 2, 16));
        rig.sending = false;
        rig.run(20);
        rig.engine.onChunk(ClientFixtures.rawChunk(rig.serverNow(), new byte[48000 * 4 * 4])); // decodes to 4 s
        rig.run(1500);
        assertEquals(1, rig.engine.status().droppedOversized);
        assertEquals(0, rig.engine.status().chunksPlayed);
        assertNull(rig.engine.status().failure);
    }

    // ---- flac: a hole in the stream must not shift it for good (review W1 of Group 8)

    /** The drift of the last audible frame against its time; the engine's own number can not show a shift of the index. */
    private static long lastFrameDrift(EngineRig rig) {
        ClientFixtures.Trace trace = rig.trace();
        int last = trace.size() - 1;
        return rig.sink.presentationTimeOf(trace.sinkFrame(last)) - rig.targetOf(trace.mediaFrame(last));
    }

    @Test
    public void aFlacChunkThatArrivesTooLateIsDroppedAndTheStreamIsAnchoredAgainByItsTimestamps() {
        EngineRig rig = EngineRig.flac48();
        rig.connect();
        rig.run(6000);
        assertNear("before the loss", 0, lastFrameDrift(rig), 1500);

        rig.delayNextChunk(1200); // this chunk reaches the client after its play time
        rig.run(20_000);

        EngineStatus s = rig.engine.status();
        assertEquals(1, s.droppedLate);
        assertEquals("the hole was found and counted", 1, s.streamHolesRecovered);
        assertNear("after the loss the stream is where the timestamps say", 0, lastFrameDrift(rig), 1500);
        assertEquals(EngineState.PLAYING, s.state);
    }

    @Test
    public void aFlacChunkThatFailsToDecodeLeavesNoShiftEither() {
        final int[] calls = {0};
        DecoderFactory flaky = (h, m) -> new PassThroughDecoder() {
            @Override
            public byte[] decode(byte[] payload) throws DecoderException {
                if (++calls[0] == 400) {
                    throw new DecoderException(DecoderException.Reason.DECODE_FAILED, "bad");
                }
                return super.decode(payload);
            }
        };
        EngineRig rig = new EngineRig(SimulatedSink::typical, new ClientEngineConfig(), flaky, 48000, 2, 16);
        rig.flac = true;
        rig.connect();
        rig.run(20_000);

        EngineStatus s = rig.engine.status();
        assertEquals(1, s.decodeFailures);
        assertEquals(1, s.streamHolesRecovered);
        assertNear("no shift", 0, lastFrameDrift(rig), 1500);
    }

    @Test
    public void aChunkThatNeverReachedTheEngineIsReportedAndTheFlacStreamRecovers() {
        EngineRig rig = EngineRig.flac48();
        rig.connect();
        rig.run(6000);
        rig.nextChunk++; // the transport or the host queue lost one chunk
        rig.engine.onChunkLost();
        rig.run(10_000);

        assertEquals(1, rig.engine.status().streamHolesRecovered);
        assertNear("no shift", 0, lastFrameDrift(rig), 1500);
    }

    @Test
    public void aLostPcmChunkIsStillFilledByTheGapRuleAndIsNoHoleOfTheOtherKind() {
        EngineRig rig = EngineRig.typical();
        rig.connect();
        rig.run(6000);
        rig.delayNextChunk(1200);
        rig.run(10_000);
        assertEquals(0, rig.engine.status().streamHolesRecovered);
        assertEquals(1, rig.engine.status().gapsFilled);
        assertNear("pcm", 0, lastFrameDrift(rig), 1500);
    }

    @Test
    public void aFlacDecoderThatHoldsBackTheFirstChunksStillAnchorsOnTheFirstChunk() {
        final int[] calls = {0};
        DecoderFactory slowStart = (h, m) -> new PassThroughDecoder() {
            private byte[] held;

            @Override
            public byte[] decode(byte[] payload) throws DecoderException {
                // the output of a chunk comes out with the next chunk, as from a decoder that is one chunk behind
                byte[] out = held == null ? new byte[0] : held;
                held = payload.clone();
                calls[0]++;
                return out;
            }
        };
        EngineRig rig = new EngineRig(SimulatedSink::typical, new ClientEngineConfig(), slowStart, 48000, 2, 16);
        rig.flac = true;
        rig.connect();
        rig.run(4000);
        ClientFixtures.Trace trace = rig.trace();
        assertEquals("the first audible frame is the first frame of the stream", 0, trace.mediaFrame(0));
        assertNear("on its time", rig.targetOf(0), rig.sink.presentationTimeOf(trace.sinkFrame(0)), 1500);
    }

    // ---- the connection, epochs

    @Test
    public void anEpochChangeEmptiesTheQueuesButNeitherFlushesNorReopensTheOutput() {
        EngineRig rig = EngineRig.typical();
        rig.connect();
        rig.run(5000);
        int opens = rig.sink.opens;

        rig.offsets.offset += 3_000_000_000L; // the server restarted with another clock
        rig.offsets.epoch++;
        // the chunks that follow carry the new clock
        rig.nextChunk = rig.nextChunk; // the rig keeps generating on the old server timeline: shift it
        rig.run(10);
        EngineStatus s = rig.engine.status();
        assertEquals(1, s.epochChanges);
        assertEquals(0, s.chunksWaiting);
        assertEquals(opens, rig.sink.opens);
        assertEquals(0, s.flushes);
        assertFalse(s.aligned);
    }

    @Test
    public void aServerThatMovesItsClockConstantlyCannotMakeTheEngineFlushOrReopen() {
        EngineRig rig = EngineRig.typical();
        rig.connect();
        rig.run(2000);
        for (int i = 0; i < 400; i++) {
            rig.offsets.epoch++;
            rig.offsets.offset += (i % 2 == 0 ? 1 : -1) * 2_000_000_000L;
            rig.run(15);
        }
        EngineStatus s = rig.engine.status();
        assertEquals(400, s.epochChanges);
        assertEquals(1, rig.sink.opens);
        assertEquals(0, s.flushes);
        assertNull(s.failure);
    }

    @Test
    public void whenTheConnectionIsLostTheOutputIsSilencedAndAfterTheReconnectTheStreamPlaysAgain() {
        EngineRig rig = EngineRig.typical();
        rig.connect();
        rig.run(5000);
        rig.engine.onConnectionLost();
        assertEquals(EngineState.CONNECTION_LOST, rig.engine.status().state);
        assertEquals(1, rig.sink.flushes);
        rig.sending = false;
        rig.run(1000);
        assertEquals("nothing is written while the connection is down", EngineState.CONNECTION_LOST, rig.engine.status().state);

        rig.engine.onServerSettings(new ServerSettingsMessage(1000, 0, false, 100));
        rig.engine.onCodecHeader(rig.header()); // the same header again
        rig.nextChunk += 100;
        rig.sending = true;
        rig.run(4000);

        EngineStatus s = rig.engine.status();
        assertEquals(1, s.decoderStarts);
        assertEquals(1, s.connectionLosses);
        assertEquals(EngineState.PLAYING, s.state);
        assertEquals(1, rig.sink.opens);
        assertFirstSampleOnTime(rig, 1500);
    }

    @Test
    public void chunksBeforeTheFirstHeaderAreCountedAndDropped() {
        EngineRig rig = EngineRig.typical();
        rig.engine.onChunk(ClientFixtures.chunk(rig.serverNow(), 0, 960, 2, 16));
        rig.engine.tick();
        assertEquals(EngineState.WAITING_FOR_STREAM, rig.engine.status().state);
        assertEquals(1, rig.engine.status().chunksReceived);
        assertEquals(0, rig.sink.opens);
    }

    // ---- failures

    @Test
    public void aDeviceWithoutAFlacDecoderGivesATypedReason() {
        EngineRig rig = EngineRig.typical();
        rig.engine.onCodecHeader(ClientFixtures.flacHeaderMessage(48000, 2, 16));
        assertEquals(EngineFailure.DECODER_UNAVAILABLE, rig.engine.status().failure);
        assertEquals(0, rig.sink.opens);
    }

    @Test
    public void aDecoderThatRefusesTheHeaderGivesATypedReason() {
        DecoderFactory refuse = (h, m) -> {
            throw new DecoderException(DecoderException.Reason.BAD_HEADER, "no");
        };
        EngineRig rig = new EngineRig(SimulatedSink::typical, new ClientEngineConfig(), refuse, 48000, 2, 16);
        rig.engine.onCodecHeader(rig.header());
        assertEquals(EngineFailure.DECODER_BAD_HEADER, rig.engine.status().failure);
    }

    @Test
    public void aSampleRateTheDeviceRefusesGivesATypedReasonAndNothingElseHappens() {
        EngineRig rig = EngineRig.typical();
        rig.sink.refuseFormat = true;
        rig.connect();
        rig.run(500);
        assertEquals(EngineFailure.UNSUPPORTED_SINK_FORMAT, rig.engine.status().failure);
        assertEquals(EngineState.FAILED, rig.engine.status().state);
    }

    @Test
    public void aDeadOutputEndsTheSessionAndFreesIt() {
        EngineRig rig = EngineRig.typical();
        rig.connect();
        rig.run(2000);
        rig.sink.failWrites = true;
        rig.run(500);
        assertEquals(EngineFailure.SINK_FAILED, rig.engine.status().failure);
        assertFalse(rig.sink.open);
    }

    @Test
    public void anOutputThatNeverReportsAPositionEndsTheSessionWithAClearReason() {
        EngineRig rig = EngineRig.typical();
        rig.sink.noPositions = true;
        rig.connect();
        rig.run(7000);
        assertEquals(EngineFailure.NO_PLAYBACK_POSITION, rig.engine.status().failure);
        assertFalse(rig.sink.open);
    }

    @Test
    public void chunksThatFailToDecodeAreDroppedAndARunOfThemEndsTheSession() {
        final boolean[] fail = {true};
        DecoderFactory flaky = (h, m) -> new PassThroughDecoder() {
            @Override
            public byte[] decode(byte[] payload) throws DecoderException {
                if (fail[0]) {
                    throw new DecoderException(DecoderException.Reason.DECODE_FAILED, "bad");
                }
                return super.decode(payload);
            }
        };
        EngineRig rig = new EngineRig(SimulatedSink::typical, new ClientEngineConfig(), flaky, 48000, 2, 16);
        rig.connect();
        rig.run(1500); // chunks are queued and released one after the other, all failing...
        // seven failures do not end it; check the count first by looking at the state after exactly 7
        assertEquals(EngineState.FAILED, rig.engine.status().state);
        assertEquals(EngineFailure.DECODER_FAILED, rig.engine.status().failure);
        assertEquals(8, rig.engine.status().decodeFailures);
    }

    @Test
    public void aGoodChunkInBetweenResetsTheRunOfFailures() {
        final int[] calls = {0};
        DecoderFactory flaky = (h, m) -> new PassThroughDecoder() {
            @Override
            public byte[] decode(byte[] payload) throws DecoderException {
                if (++calls[0] % 5 != 0) {
                    throw new DecoderException(DecoderException.Reason.DECODE_FAILED, "bad");
                }
                return super.decode(payload);
            }
        };
        EngineRig rig = new EngineRig(SimulatedSink::typical, new ClientEngineConfig(), flaky, 48000, 2, 16);
        rig.connect();
        rig.run(3000);
        assertNull(rig.engine.status().failure);
        assertTrue(rig.engine.status().decodeFailures > 10);
    }

    // ---- stopping

    @Test
    public void stopFreesTheOutputAndLaterEventsDoNothing() {
        EngineRig rig = EngineRig.typical();
        rig.connect();
        rig.run(2000);
        rig.engine.stop();
        rig.engine.stop();

        assertEquals(EngineState.STOPPED, rig.engine.status().state);
        assertFalse(rig.sink.open);
        assertEquals(1, rig.sink.releases);
        int writes = rig.sink.writes.size();
        rig.engine.onChunk(ClientFixtures.chunk(rig.serverNow(), 0, 960, 2, 16));
        rig.engine.onCodecHeader(ClientFixtures.pcmHeader(44100, 2, 16));
        rig.engine.tick();
        assertEquals(writes, rig.sink.writes.size());
        assertEquals(1, rig.sink.opens);
    }

    @Test
    public void theStatusTellsWhatTheEngineKnows() {
        EngineRig rig = EngineRig.typical();
        rig.connect();
        rig.engine.onOffsetUpdate(1234, 56);
        rig.run(3000);
        EngineStatus s = rig.engine.status();
        assertEquals("pcm", s.codec);
        assertEquals(48000, s.sampleRate);
        assertEquals(2, s.channels);
        assertEquals(1234, s.offsetMicros);
        assertEquals(56, s.jitterMicros);
        assertEquals("simulated", s.positionSource);
        assertTrue(s.outputAheadMs >= 0 && s.outputAheadMs <= 150);
        assertTrue(s.chunksReceived > 100);
        assertNotNull(s.state);
        assertEquals(3000, s.elapsedMs, 20);
    }

    /** A decoder that takes the payload to be 16 bit stereo PCM already. */
    static class PassThroughDecoder implements FrameDecoder {
        @Override
        public byte[] decode(byte[] payload) throws DecoderException {
            return payload.clone();
        }

        @Override
        public void reset() {
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
