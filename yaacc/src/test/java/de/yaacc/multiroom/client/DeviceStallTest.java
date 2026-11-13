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
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * The review's C1 (Fix Group 8j): a flac stream that meets a stall of the source shorter than 300 ms stayed shifted by the
 * stall for the rest of the session (40 to about 240 ms) or went silent for good (about 250 to 300 ms). The sweep runs
 * stalls of 20 ms to 60 s on pcm, on flac chunks of exactly 20 ms, and on flac blocks of several sizes, plain, with the
 * header sent again, with a reconnect in the middle and with a new clock epoch. After every one of them the sound is
 * aligned within a few milliseconds <i>of where it was before</i> (the sync error against the media target does not change)
 * and continuous.
 *
 * <p>Group 8k (review cycle 5): the blocks are stamped the way Snapcast 0.35.0 does it (a running clock advanced by the
 * encoded duration, so the stamps are one block apart, see {@link DeviceRig.FlacStamping#SERVER_CLOCK}), the chunks carry
 * real FLAC frame headers, and the decoder may hold blocks back, at random or in a step. The model of Group 8j (blocks
 * stamped by the pcm chunk that completed them: not the server's rule) is kept as a second source.
 */
public class DeviceStallTest {

    enum Source {
        PCM,
        /** Flac chunks that carry exactly 20 ms (one block of 960 frames), stamped by the running clock of the server. */
        FLAC_20MS,
        /** The model of Group 8j (not the server's rule): blocks of 1152 stamped with the pcm chunk that completed them. */
        FLAC_BLOCKS,
        /** The 1152 frame blocks of that model and a decoder that holds a chunk back and then gives two at once. */
        FLAC_BLOCKS_BURSTY,
        /** Snapcast's stamping (PcmStream::chunkEncoded): the running clock advanced by the encoded duration. */
        REAL_1152, REAL_1152_LAG1, REAL_1152_LAG2, REAL_4096_LAG1, REAL_576, REAL_2304_LAG1,
        /** 44.1 kHz (chunks of 882 frames: the block of 1152 is 26.12 ms, not a whole number of microseconds). */
        REAL_44100,
        /** One channel (the sink of the tests can not tell the media position of a mono stream: no sync error is judged). */
        REAL_MONO
    }

    private static boolean realStamping(Source source) {
        return source.name().startsWith("REAL_");
    }

    private static boolean blocks(Source source) {
        return source != Source.PCM && source != Source.FLAC_20MS;
    }

    enum Variant {
        PLAIN, HEADER_AGAIN, RECONNECT,
        /** The clock filter finds a new epoch at the restart (review S29: a decoder that holds a block back kept it). */
        EPOCH
    }

    private static final long[] STALLS = {20, 40, 60, 100, 150, 240, 250, 300, 400, 600, 1000, 3000};

    private static DeviceModel phone() {
        DeviceModel m = new DeviceModel();
        m.startThresholdFrames = 1920;
        m.thresholdSettable = true;
        m.timestampPeriodMicros = 20_000;
        m.outputLatencyMicros = 35_000;
        return m;
    }

    static DeviceRig rig(Source source) {
        ClientEngineConfig config = new ClientEngineConfig().maxAheadMs(250);
        DeviceRig rig = source == Source.REAL_44100 ? new DeviceRig(phone(), config, 44100, 882)
                : source == Source.REAL_MONO ? new DeviceRig(phone(), config, 48000, 960, 1) : new DeviceRig(phone(), config);
        rig.flac = source != Source.PCM;
        rig.flacHeaders = rig.flac;
        switch (source) {
            case FLAC_BLOCKS:
                rig.flacBlockFrames = 1152;
                break;
            case FLAC_BLOCKS_BURSTY:
                rig.flacBlockFrames = 1152;
                rig.decoderLag = 2;
                break;
            case REAL_1152:
                rig.flacBlockFrames = 1152;
                break;
            case REAL_1152_LAG1:
                rig.flacBlockFrames = 1152;
                rig.decoderLag = 1;
                break;
            case REAL_1152_LAG2:
                rig.flacBlockFrames = 1152;
                rig.decoderLag = 2;
                break;
            case REAL_4096_LAG1:
                rig.flacBlockFrames = 4096;
                rig.decoderLag = 1;
                break;
            case REAL_576:
                rig.flacBlockFrames = 576;
                break;
            case REAL_44100:
                rig.flacBlockFrames = 1152;
                break;
            case REAL_MONO:
                rig.flacBlockFrames = 1152;
                break;
            case REAL_2304_LAG1:
                rig.flacBlockFrames = 2304;
                rig.decoderLag = 1;
                break;
            default:
                break;
        }
        if (realStamping(source) || source == Source.FLAC_20MS) {
            rig.flacStamping = DeviceRig.FlacStamping.SERVER_CLOCK;
        }
        return rig;
    }

    /** One stall; returns what is wrong, or null. */
    private static String stall(Source source, long stallMs, Variant variant) {
        DeviceRig rig = rig(source);
        rig.connect();
        rig.run(10_000);
        String name = source + " stall " + stallMs + " ms " + variant;
        DeviceSimSink.Counters c = rig.sink.counters();
        if (c.playedAudibleFrames == 0) {
            return name + ": no sound before the stall";
        }
        double before = rig.syncErrorMs();
        long skippedBefore = rig.status().stepSkippedFrames;
        rig.sending = false;
        if (variant == Variant.RECONNECT) {
            rig.run(stallMs / 2);
            rig.engine.onConnectionLost(de.yaacc.multiroom.protocol.SnapcastConnection.LossReason.IO_ERROR.ordinal(), 0);
            rig.run(stallMs - stallMs / 2);
        } else {
            rig.run(stallMs);
        }
        if (variant == Variant.HEADER_AGAIN || variant == Variant.RECONNECT) {
            rig.resendHeader();
        }
        if (variant == Variant.EPOCH) {
            rig.offsets.epoch++;
        }
        rig.sending = true;
        long silenceBefore = c.playedSilentFramesAfterAudio;
        rig.run(1_500 + 400); // the first chunk after the stall plays one server buffer (1 s) after it was recorded
        rig.run(1_500);
        double after = rig.syncErrorMs();
        // after a re-anchor (a reconnect, a stall of seconds) a flac stream is only as exact as one block of the encoder
        // the model of 8j has stamps that are not contiguous with the frames (they wander in a band of a chunk), so the audio a
        // decoder holds back can not be placed by its position exactly, and a re-anchor is only as exact as one block
        double allowed = (source == Source.FLAC_BLOCKS || source == Source.FLAC_BLOCKS_BURSTY)
                && (variant == Variant.RECONNECT || stallMs >= 3000 || source == Source.FLAC_BLOCKS_BURSTY) ? 25 : 5;
        if (source != Source.REAL_MONO && Math.abs(after - before) > allowed) {
            return name + ": sync error " + String.format("%.1f", before) + " ms before, " + String.format("%.1f", after)
                    + " ms after; " + rig.report() + "; skipped " + (rig.status().stepSkippedFrames - skippedBefore);
        }
        long silenceAt = c.playedSilentFramesAfterAudio;
        rig.run(6_000);
        long silent = c.playedSilentFramesAfterAudio - silenceAt;
        if (silent > 480) {
            return name + ": not continuous afterwards: " + silent / 48 + " ms of silence; " + rig.report();
        }
        long hole = (silenceAt - silenceBefore) / 48;
        // the hole of the stall itself plays as silence; a re-anchor may add a little, never seconds
        // a lost connection flushes the output, and an epoch change empties the queue of the scheduler: the audio that waited
        // (a second) is gone, and the stream starts anew
        if (hole > stallMs + (variant == Variant.RECONNECT || variant == Variant.EPOCH ? 1_600 : 400)) {
            return name + ": " + hole + " ms of silence around a stall of " + stallMs + " ms; " + rig.report();
        }
        if (!rig.status().aligned) {
            return name + ": not aligned; " + rig.report();
        }
        return null;
    }

    private static void sweep(Source source, Variant variant, long... stalls) {
        List<String> problems = new ArrayList<>();
        for (long stall : stalls) {
            String problem = stall(source, stall, variant);
            if (problem != null) {
                problems.add(problem);
            }
        }
        assertTrue(problems.size() + " of " + stalls.length + " failed:\n" + String.join("\n", problems), problems.isEmpty());
    }

    @Test
    public void pcmSurvivesEveryStall() {
        for (Variant variant : Variant.values()) {
            sweep(Source.PCM, variant, STALLS);
        }
    }

    @Test
    public void flacChunksOfTwentyMillisecondsSurviveEveryStall() {
        for (Variant variant : Variant.values()) {
            sweep(Source.FLAC_20MS, variant, STALLS);
        }
    }

    @Test
    public void flacBlocksOfThe1152FrameStreamSurviveEveryStall() {
        for (Variant variant : Variant.values()) {
            sweep(Source.FLAC_BLOCKS, variant, STALLS);
        }
    }

    @Test
    public void longStallsOfTensOfSecondsToo() {
        for (Source source : Source.values()) {
            sweep(source, Variant.PLAIN, 10_000, 60_000);
            sweep(source, Variant.HEADER_AGAIN, 10_000);
        }
    }

    /** The reviewer's reproduction: 299 ms of stall went silent for good and 40 to 240 ms stayed shifted. */
    @Test
    public void theReviewersBandBetween40And300MillisecondsOnFlac() {
        for (long stall : new long[]{40, 60, 80, 120, 160, 200, 240, 270, 290, 296, 299, 300}) {
            String problem = stall(Source.FLAC_20MS, stall, Variant.PLAIN);
            assertTrue(problem, problem == null);
        }
    }

    @Test
    public void flacBlocksWithABurstyDecoderSurviveTheStalls() {
        sweep(Source.FLAC_BLOCKS_BURSTY, Variant.PLAIN, 20, 40, 100, 240, 300, 1000, 3000);
        sweep(Source.FLAC_BLOCKS_BURSTY, Variant.HEADER_AGAIN, 60, 600);
    }

    // ---- no false positives: a long steady stream is never shifted by the guard

    private static void steadyHours(Source source, int hours) {
        DeviceRig rig = rig(source);
        rig.stepMs = 10;
        rig.connect();
        rig.run(20_000);
        double sync = rig.syncErrorMs();
        long hourMs = 3_600_000L;
        for (int h = 0; h < hours; h++) {
            rig.run(hourMs);
            EngineStatus s = rig.status();
            assertTrue(source + " hour " + h + ": " + s.flacShiftFills + " fills of " + s.flacShiftFilledMs + " ms, "
                    + s.streamResumes + " resumes; " + rig.report(), s.flacShiftFills == 0 && s.streamResumes == 0 && s.flushes == 0);
        }
        DeviceSimSink.Counters c = rig.sink.counters();
        assertTrue(source + ": " + rig.report(), c.playedSilentFramesAfterAudio <= 480);
        assertTrue(source + ": sync error " + rig.syncErrorMs() + " vs " + sync, Math.abs(rig.syncErrorMs() - sync) < 3);
        assertTrue(source + ": smoothed drift " + rig.status().driftSmoothedMicros, Math.abs(rig.status().driftSmoothedMicros) < 5_000);
    }

    @Test
    public void threeHoursOfTheOwnersFlacBlocksAreNeverShifted() {
        steadyHours(Source.FLAC_BLOCKS, 3);
    }

    @Test
    public void threeHoursOfFlacBlocksWithABurstyDecoderAreNeverShifted() {
        steadyHours(Source.FLAC_BLOCKS_BURSTY, 3);
    }

    @Test
    public void anHourOfFlacChunksOfTwentyMillisecondsIsNeverShifted() {
        steadyHours(Source.FLAC_20MS, 1);
    }

    // ---- Group 8k: the server's real stamping (PcmStream::chunkEncoded) and what a decoder does to a flac stream

    @Test
    public void realStampingEveryStallOnEveryBlockSizeAndDecoder() {
        for (Source source : new Source[]{Source.REAL_1152, Source.REAL_1152_LAG1, Source.REAL_1152_LAG2, Source.REAL_4096_LAG1,
                Source.REAL_576, Source.REAL_2304_LAG1}) {
            sweep(source, Variant.PLAIN, STALLS);
        }
    }

    @Test
    public void realStampingStallsWithTheHeaderAgainAndAReconnect() {
        for (Source source : new Source[]{Source.REAL_1152, Source.REAL_1152_LAG1}) {
            sweep(source, Variant.HEADER_AGAIN, STALLS);
            sweep(source, Variant.RECONNECT, STALLS);
        }
    }

    /** The review's S30: 44.1 kHz and mono. */
    @Test
    public void realStampingAt44100HzAndInMono() {
        sweep(Source.REAL_44100, Variant.PLAIN, STALLS);
        sweep(Source.REAL_44100, Variant.RECONNECT, 300, 3000);
        sweep(Source.REAL_MONO, Variant.PLAIN, STALLS);
        sweep(Source.REAL_MONO, Variant.HEADER_AGAIN, 100, 1000);
    }

    @Test
    public void anHourAt44100HzAndAnHourOfMonoAreNeverShifted() {
        // 1152 frames at 44.1 kHz are 26.122449 ms: the stamps are whole microseconds and must not drift (the server adds
        // the duration in nanoseconds), nor may the guard see a "slow clock"
        DeviceRig rig = rig(Source.REAL_44100);
        noStallRun(rig, "44.1 kHz", 3_600_000L, 3);
        assertTrue(rig.report(), rig.status().flacReanchors == 0);
        noStallRun(rig(Source.REAL_MONO), "mono", 3_600_000L, 1e12); // the media position of a mono stream is not readable
    }

    /** The review's S29: an epoch change with a decoder that holds a block back left one block (+24 ms, +85 ms at 4096) of error. */
    @Test
    public void realStampingANewClockEpochAfterAStallWithALaggingDecoder() {
        for (Source source : new Source[]{Source.REAL_1152, Source.REAL_1152_LAG1, Source.REAL_1152_LAG2, Source.REAL_4096_LAG1,
                Source.REAL_2304_LAG1}) {
            sweep(source, Variant.EPOCH, 20, 300, 1000, 3000);
        }
        sweep(Source.PCM, Variant.EPOCH, 300, 3000);
    }

    @Test
    public void realStampingLongStallsToo() {
        for (Source source : new Source[]{Source.REAL_1152, Source.REAL_1152_LAG1, Source.REAL_4096_LAG1}) {
            sweep(source, Variant.PLAIN, 10_000, 60_000);
        }
    }

    /**
     * The true sync error of a stream without any stall: what the decoder does must not move the sound against the other
     * clients. Checked every 10 s; {@code sync0} is the error once the stream plays (the anchor itself).
     */
    private static void noStallRun(DeviceRig rig, String name, long totalMs, double tolerance) {
        rig.stepMs = 10;
        rig.connect();
        rig.run(20_000);
        double sync0 = rig.syncErrorMs();
        long done = 20_000;
        while (done < totalMs) {
            rig.run(10_000);
            done += 10_000;
            double error = rig.syncErrorMs() - sync0;
            assertTrue(name + " at " + done / 1000 + " s: sync error moved by " + error + " ms (from " + sync0 + "); fills "
                    + rig.status().flacShiftFills + " of " + rig.status().flacShiftFilledMs + " ms; " + rig.report(),
                    Math.abs(error) < tolerance);
        }
        EngineStatus s = rig.status();
        assertTrue(name + ": " + s.flacShiftFills + " fills, " + s.streamResumes + " resumes, " + s.flushes + " flushes; "
                + rig.report(), s.flacShiftFills == 0 && s.streamResumes == 0 && s.flushes == 0);
        assertTrue(name + ": " + rig.report(), rig.sink.counters().playedSilentFramesAfterAudio <= 480);
    }

    private static DeviceRig holdingDecoder(int block, int permille, int maxCalls) {
        DeviceRig rig = rig(block == 4096 ? Source.REAL_4096_LAG1 : Source.REAL_1152);
        rig.decoderLag = 0;
        rig.decoderHoldPermille = permille;
        rig.decoderHoldMaxCalls = maxCalls;
        return rig;
    }

    /** The review's W1: a decoder that now and then holds a block for one or two calls turned into +24 ms for the rest of the hour. */
    @Test
    public void aDecoderThatHoldsABlockNowAndThenNeverMovesTheSound() {
        for (int calls = 2; calls <= 4; calls++) {
            noStallRun(holdingDecoder(1152, 10, calls), "holds of 1 to " + calls + " calls on 1 % of the chunks", 3_600_000L, 3);
        }
    }

    @Test
    public void aDecoderThatHoldsABlockOnEveryTenthChunkNeverMovesTheSound() {
        noStallRun(holdingDecoder(1152, 100, 3), "holds of 1 to 3 calls on 10 % of the chunks", 600_000L, 3);
        noStallRun(holdingDecoder(4096, 20, 1), "blocks of 4096, holds on 2 %", 1_200_000L, 3);
    }

    /** The review's W1: a latency step of the decoder from 0 to one block at 60 s gave +24 ms (+85 ms with 4096 frame blocks). */
    @Test
    public void aStepInTheLatencyOfTheDecoderNeverMovesTheSound() {
        for (Source source : new Source[]{Source.REAL_1152, Source.REAL_4096_LAG1}) {
            DeviceRig rig = rig(source);
            rig.decoderLag = 0;
            rig.decoderLagStepAtMs = 70_000;
            rig.decoderLagAfter = 1;
            noStallRun(rig, source + " with a latency step at 70 s", 240_000L, 3);
        }
        DeviceRig two = rig(Source.REAL_1152);
        two.decoderLag = 1;
        two.decoderLagStepAtMs = 70_000;
        two.decoderLagAfter = 0;
        noStallRun(two, "a latency step back to 0", 240_000L, 3);
    }

    /** The coordinator's: noise on the stamps of 20 ms chunks (the server's clock has none; a stream that has it must not be filled). */
    @Test
    public void noiseOnTheStampsOfTwentyMillisecondChunksIsNotAStall() {
        for (long noise : new long[]{3_000, 8_000, 15_000}) {
            DeviceRig rig = rig(Source.FLAC_20MS);
            rig.chunkJitterMicros = noise;
            noStallRun(rig, "stamps off by up to " + noise / 1000 + " ms", 3L * 3_600_000L, 3);
        }
    }

    /** Arrival jitter (the network) and the stamps untouched: the position from the header does not care. */
    @Test
    public void arrivalJitterNeverMovesTheSound() {
        DeviceRig rig = rig(Source.REAL_1152_LAG1);
        rig.arrivalJitterMicros = 40_000;
                noStallRun(rig, "arrival jitter 40 ms", 1_800_000L, 3);
    }

    // ---- Group 8k, the owner's trace of the 8j build: four fills of about one block in 15 minutes of a normal album

    /**
     * The owner's phone: a decoder that holds back a block now and then, a network that delays chunks, a track change
     * somewhere. 15 minutes and 3 hours without any stall of the source: not one fill, not one re-anchor, and the sound is
     * where it was against the stamps of the server (the 8j guard filled 4 times, about 26 ms each, in 15 minutes).
     */
    private static DeviceRig phoneLike(int block, int permille, int maxCalls, long arrivalJitterMicros) {
        DeviceRig rig = holdingDecoder(block, permille, maxCalls);
        rig.arrivalJitterMicros = arrivalJitterMicros;
        return rig;
    }

    @Test
    public void fifteenMinutesOfARealisticDecoderAndNetworkWithoutAStallProduceNoFill() {
        noStallRun(phoneLike(1152, 10, 2, 30_000), "15 min, holds on 1 %, arrival jitter 30 ms", 900_000L, 3);
        noStallRun(phoneLike(1152, 30, 3, 30_000), "15 min, holds on 3 % up to 3 calls", 900_000L, 3);
        noStallRun(phoneLike(4096, 10, 1, 30_000), "15 min, blocks of 4096", 900_000L, 3);
    }

    @Test
    public void threeHoursOfARealisticDecoderAndNetworkWithoutAStallProduceNoFill() {
        noStallRun(phoneLike(1152, 10, 2, 30_000), "3 h, holds on 1 %, arrival jitter 30 ms", 3L * 3_600_000L, 3);
    }

    /** The reviewer's cases, now with the true sync error against the stamps under 3 ms, in the status as well. */
    @Test
    public void theReviewersCasesEndWithTheTrueSyncErrorUnder3Ms() {
        for (int calls = 2; calls <= 4; calls++) {
            DeviceRig rig = holdingDecoder(1152, 10, calls);
            noStallRun(rig, "holds of 1 to " + calls + " calls", 1_800_000L, 3);
            assertTrue(rig.status().flacGuardState == 1 && rig.status().flacShiftFills == 0);
        }
    }

    /** Without readable frame headers the guard is inactive: a decoder that holds blocks never causes a fill. */
    @Test
    public void withoutFrameHeaderPositionsTheGuardNeverFillsWhateverTheDecoderDoes() {
        DeviceRig rig = new DeviceRig(phone(), new ClientEngineConfig().maxAheadMs(250));
        rig.flac = true;
        rig.flacBlockFrames = 1152;
        rig.flacHeaders = false; // raw samples: the positions are counted from the decoded frames
        rig.flacStamping = DeviceRig.FlacStamping.SERVER_CLOCK;
        rig.decoderHoldPermille = 20;
        rig.decoderHoldMaxCalls = 3;
        rig.arrivalJitterMicros = 30_000;
        rig.stepMs = 10;
        rig.connect();
        rig.run(3_600_000L);
        EngineStatus s = rig.status();
        assertEquals(rig.report(), 2, s.flacPositionSource);
        assertEquals("inactive", 2, s.flacGuardState);
        assertEquals("never fills from the frame count", 0, s.flacShiftFills);
        String text = SnapcastClientStatusText.of(DeviceRestartTest.viewOf(s), 0);
        assertTrue(text, text.contains("flac stall guard: inactive (no frame header positions"));
    }

    @Test
    public void theGuardStateIsInTheStatusOnOffAndInactive() {
        DeviceRig on = rig(Source.REAL_1152);
        on.connect();
        on.run(5_000);
        assertEquals(1, on.status().flacGuardState);
        assertTrue(SnapcastClientStatusText.of(DeviceRestartTest.viewOf(on.status()), 0).contains("flac stall guard: on (positions from frame headers)"));
        DeviceRig off = new DeviceRig(phone(), new ClientEngineConfig().maxAheadMs(250).flacTimeGuard(false));
        off.flac = true;
        off.flacHeaders = true;
        off.flacBlockFrames = 1152;
        off.flacStamping = DeviceRig.FlacStamping.SERVER_CLOCK;
        off.connect();
        off.run(5_000);
        assertEquals(0, off.status().flacGuardState);
        assertTrue(SnapcastClientStatusText.of(DeviceRestartTest.viewOf(off.status()), 0).contains("flac stall guard: off (switched off)"));
        // off: a stall of the source is not filled, the breaker and the engine do what they did before the guard
        off.stallSource(100);
        off.run(3_000);
        assertEquals(0, off.status().flacShiftFills);
    }

    /** What the guard saw is in the status and the trace for every fill, and the important events survive the main ring. */
    @Test
    public void everyFillIsExplainedInTheStatusAndKeptInTheImportantEvents() {
        DeviceRig rig = rig(Source.REAL_1152);
        rig.stepMs = 10;
        rig.connect();
        rig.run(10_000);
        rig.stallSource(240);
        rig.run(2_000);
        EngineStatus s = rig.status();
        assertTrue(rig.report(), s.flacShiftFills >= 1);
        assertEquals(240, s.flacLastFillMs + (s.flacShiftFilledMs - s.flacLastFillMs), 30);
        assertTrue(s.flacLastFillReason == 1 || s.flacLastFillReason == 2);
        assertTrue("skew " + s.flacLastFillSkewUs, s.flacLastFillSkewUs > 100_000L);
        assertTrue("chunks since the anchor " + s.flacLastFillChunks, s.flacLastFillChunks > 100);
        String text = SnapcastClientStatusText.of(DeviceRestartTest.viewOf(s), 0);
        assertTrue(text, text.contains("last fill: ") && text.contains("us over its base") && text.contains("chunks after the anchor"));
        // 20 minutes more of a busy stream: the main ring forgot the fill, the important events did not
        rig.run(20 * 60_000L);
        String main = rig.engine.trace().text();
        assertTrue(main, main.contains("(") && main.contains("older events left out"));
        String important = rig.engine.trace().importantText();
        assertTrue(important, important.contains("flac stream shifted against its timestamps, silence inserted (ms)"));
        assertTrue(important, important.contains("flac fill: what the guard saw"));
        String report = SnapcastClientTraceReport.of(DeviceRestartTest.viewOf(rig.status()), "test", 0);
        assertTrue(report, report.indexOf("important events") >= 0 && report.indexOf("important events") < report.indexOf("event trace (oldest first"));
    }

    // ---- the livelock breaker (the review's C1, second part): without the guard nothing may be thrown away for ever

    /**
     * The guard off, as it was: a flac stream of 299 ms of stall went silent for good, one chunk skipped per chunk that
     * arrived. The breaker drops the timeline when more than maxSkipMs was skipped without coming back on its time.
     */
    @Test
    public void withoutTheGuardTheEngineDoesNotSkipEveryChunkForEver() {
        for (long stall : new long[]{270, 290, 299}) {
            DeviceRig rig = new DeviceRig(phone(), new ClientEngineConfig().maxAheadMs(250).flacTimeGuard(false));
            rig.flac = true;
            rig.flacHeaders = true; // the switch is what keeps the guard off here (without headers it is inactive anyway)
            rig.flacStamping = DeviceRig.FlacStamping.SERVER_CLOCK;
            rig.connect();
            rig.run(10_000);
            rig.stallSource(stall);
            rig.run(8_000);
            DeviceSimSink.Counters c = rig.sink.counters();
            long audible = c.playedAudibleFrames;
            rig.run(5_000);
            assertTrue("stall " + stall + ": sound after the stall: " + rig.report(), c.playedAudibleFrames - audible > 4_500L * 48);
            EngineStatus s = rig.status();
            assertTrue("stall " + stall + ": the stream was placed anew: " + rig.report(), s.timelineResyncs >= 1 && s.streamResumes >= 1);
            assertTrue("no more than about a second skipped in a row: " + s.stepSkippedFrames, s.stepSkippedFrames <= 3L * 48_000);
            assertTrue(rig.report(), s.aligned);
        }
    }

    @Test
    public void theGuardFillsInsteadOfSkippingWhenItIsOn() {
        DeviceRig rig = new DeviceRig(phone(), new ClientEngineConfig().maxAheadMs(250));
        rig.flac = true;
        rig.flacHeaders = true;
        rig.flacStamping = DeviceRig.FlacStamping.SERVER_CLOCK;
        rig.connect();
        rig.run(10_000);
        rig.stallSource(299);
        rig.run(4_000);
        EngineStatus s = rig.status();
        assertTrue(rig.report(), s.flacShiftFills >= 1 && s.flacShiftFilledMs >= 280 && s.flacShiftFilledMs <= 310);
        assertTrue(rig.report(), s.stepSkippedFrames == 0 && s.timelineResyncs == 0);
        assertTrue(rig.engine.trace().text(), rig.engine.trace().text().contains("flac stream shifted against its timestamps"));
    }
}
