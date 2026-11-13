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

import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * The review's C1 (Fix Group 8j): a flac stream that meets a stall of the source shorter than 300 ms stayed shifted by the
 * stall for the rest of the session (40 to about 240 ms) or went silent for good (about 250 to 300 ms). The sweep runs
 * stalls of 20 ms to 60 s on pcm, on flac chunks that carry exactly 20 ms, and on flac blocks of 1152 frames (the owner's
 * stream: chunks 20, 20, 20, 20, 40 ms apart, stamped by the pcm chunk that completed the block), plain, with the header sent
 * again, and with a reconnect in the middle. After every one of them the sound is aligned within a few milliseconds
 * <i>of where it was before</i> (the sync error against the media target does not change) and continuous.
 */
public class DeviceStallTest {

    private enum Source {
        PCM, FLAC_20MS, FLAC_BLOCKS,
        /** The 1152 frame blocks and a decoder that holds a chunk back and then gives two at once. */
        FLAC_BLOCKS_BURSTY
    }

    private enum Variant {
        PLAIN, HEADER_AGAIN, RECONNECT
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

    private static DeviceRig rig(Source source) {
        DeviceRig rig = new DeviceRig(phone(), new ClientEngineConfig().maxAheadMs(250));
        rig.flac = source != Source.PCM;
        rig.flacBlockFrames = source == Source.FLAC_BLOCKS || source == Source.FLAC_BLOCKS_BURSTY ? 1152 : 0;
        rig.decoderLag = source == Source.FLAC_BLOCKS_BURSTY ? 2 : 0;
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
        if (variant != Variant.PLAIN) {
            rig.resendHeader();
        }
        rig.sending = true;
        long silenceBefore = c.playedSilentFramesAfterAudio;
        rig.run(1_500 + 400); // the first chunk after the stall plays one server buffer (1 s) after it was recorded
        rig.run(1_500);
        double after = rig.syncErrorMs();
        // after a re-anchor (a reconnect, a stall of seconds) a flac stream is only as exact as one block of the encoder
        double allowed = (source == Source.FLAC_BLOCKS || source == Source.FLAC_BLOCKS_BURSTY) && (variant == Variant.RECONNECT || stallMs >= 3000) ? 25 : 5;
        if (Math.abs(after - before) > allowed) {
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
        // a lost connection flushes the output: the audio that waited (a second) is gone, and the stream starts anew
        if (hole > stallMs + (variant == Variant.RECONNECT ? 1_600 : 400)) {
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
