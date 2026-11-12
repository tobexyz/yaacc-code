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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Fix Group 8h: the owner's 18 s run on the Nothing A142 ("better but with little breaks"): after the alignment the drift
 * jumped by +26 ms and stayed at +16 to +28 ms. Here: what the drift does when the clock estimate or the position's time
 * is off by that much, that the corrections converge in steps of a frame or two, and what the engine writes
 * (a sine through the whole path: no step larger than the tone's own slope, no missing or repeated frame).
 */
public class DeviceDriftTest {

    private static DeviceModel phone() {
        DeviceModel m = new DeviceModel();
        m.startThresholdFrames = 1920;
        m.thresholdSettable = true;
        m.timestampPeriodMicros = 20_000;
        m.outputLatencyMicros = 35_000;
        return m;
    }

    private static void assertSettled(DeviceRig rig, String why) {
        EngineStatus s = rig.status();
        assertTrue(why + ": smoothed drift " + s.driftSmoothedMicros + " us: " + rig.report(), Math.abs(s.driftSmoothedMicros) <= 2500);
    }

    // ---- A: the +26 ms step after the alignment

    /** The clock estimate moves by 26 ms after the stream was aligned (the median of a few samples settling). */
    @Test
    public void aClockEstimateThatMovesAfterTheAlignmentIsFollowedInSmallStepsUntilTheDriftIsGone() {
        DeviceRig rig = new DeviceRig(phone());
        rig.connect();
        rig.run(6000);
        assertEquals(rig.report(), 0, rig.status().correctionDroppedFrames + rig.status().correctionRepeatedFrames);
        rig.offsets.offset += 26_000; // the engine now thinks the server clock is 26 ms further ahead
        rig.run(1500);
        assertTrue("the drift is visible: " + rig.status().driftSmoothedMicros, Math.abs(rig.status().driftSmoothedMicros) > 10_000);
        rig.run(18_000);
        assertSettled(rig, "after 18 s");
        EngineStatus s = rig.status();
        assertTrue("one or two frames per block at most: " + s.correctionLargestBlockFrames, s.correctionLargestBlockFrames <= 2);
        assertEquals("no step while playing", 0, s.stepsWhilePlaying);
        assertEquals(rig.report(), 0, s.flushes);
        // 26 ms = 1250 frames corrected, not more, not an overshoot
        long net = Math.abs(s.correctionDroppedFrames - s.correctionRepeatedFrames);
        assertTrue("net correction " + net + " frames", net >= 1100 && net <= 1400);
    }

    /** The clock estimate has not settled yet: aligning is postponed, so nothing has to be corrected afterwards. */
    @Test
    public void anUnsettledClockEstimateDelaysTheAlignmentAndNothingIsCorrectedLater() {
        DeviceRig rig = new DeviceRig(phone());
        rig.offsets.settled = false;
        rig.offsets.offset += 26_000; // the early estimate is 26 ms off
        rig.connect();
        rig.run(1500);
        assertFalse("not aligned on a guess: " + rig.report(), rig.status().aligned);
        rig.offsets.offset -= 26_000; // the window filled up: the estimate is right now
        rig.offsets.settled = true;
        rig.run(15_000);
        EngineStatus s = rig.status();
        assertTrue(rig.report(), s.aligned);
        assertEquals("nothing to correct: " + rig.report(), 0, s.correctionDroppedFrames + s.correctionRepeatedFrames);
        DeviceSimSink.Counters c = rig.sink.counters();
        assertTrue("in sync with the server clock: " + c.maxSyncErrorAfterSettleMicros, c.syncSamples > 0 && c.maxSyncErrorAfterSettleMicros < 10_000);
    }

    /** A latency that the HAL adds to the reported times after the start (+20 ms): the corrections converge on it. */
    @Test
    public void aPositionTimeThatBecomesTwentyMillisecondsLateAfterTheStartIsFollowed() {
        DeviceModel m = phone();
        m.biasAfterMicros = 4_000_000; // after the stream has been aligned
        m.timestampBiasMicros = 20_000;
        DeviceRig rig = new DeviceRig(m);
        rig.connect();
        rig.run(6500);
        assertTrue("the drift jumped by about the bias: " + rig.status().driftSmoothedMicros,
                Math.abs(rig.status().driftSmoothedMicros) > 8_000);
        rig.run(20_000);
        assertSettled(rig, "after 26 s");
        assertTrue(rig.status().correctionLargestBlockFrames <= 2);
        assertEquals(0, rig.status().stepsWhilePlaying);
    }

    /** The age of the reports jitters by 0 to 42 ms (a HAL burst): the drift stays small and nothing is cut in big pieces. */
    @Test
    public void aJitteryPositionAgeKeepsTheDriftSmallWithoutBigCorrections() {
        DeviceModel m = phone();
        m.timestampNoiseMicros = 42_000;
        DeviceRig rig = new DeviceRig(m);
        rig.connect();
        rig.run(40_000);
        EngineStatus s = rig.status();
        assertEquals(rig.report(), 0, s.flushes);
        assertTrue("one or two frames per block at most: " + s.correctionLargestBlockFrames, s.correctionLargestBlockFrames <= 2);
        assertEquals(0, s.stepsWhilePlaying);
        assertTrue("smoothed drift " + s.driftSmoothedMicros, Math.abs(s.driftSmoothedMicros) <= 25_000);
        // the corrections follow the mean of the noise once, they do not chase it: 21 ms are 1000 frames
        assertTrue("dropped " + s.correctionDroppedFrames + " repeated " + s.correctionRepeatedFrames,
                s.correctionDroppedFrames + s.correctionRepeatedFrames <= 3000);
    }

    // ---- B: what is written

    /** An output that plays 500 ppm fast or slow needs a correction all the time: with the smooth correction no step exceeds the slope. */
    @Test
    public void aSineWithContinuousCorrectionsHasNoStepLargerThanItsOwnSlope() {
        for (double speed : new double[]{1.0005, 0.9995}) { // frames repeated, frames dropped
            DeviceModel m = phone();
            m.speed = speed;
            DeviceRig rig = new DeviceRig(m, new ClientEngineConfig().smoothCorrections(true));
            rig.useSine();
            rig.connect();
            rig.run(120_000);
            DeviceSimSink.Counters c = rig.sink.counters();
            EngineStatus s = rig.status();
            assertTrue("speed " + speed + ": corrections happened: " + s.correctionBlocks, s.correctionBlocks > 100);
            assertEquals("speed " + speed + ": steps over the slope: max " + c.sineMaxStep + " " + rig.report(), 0,
                    c.sineStepsOverSlope);
            assertEquals(rig.report(), 0, s.flushes);
            assertTrue(s.correctionLargestBlockFrames <= 2);
        }
    }

    /** The control: cutting single samples (the way it was) puts a step of twice the slope into the tone every correction. */
    @Test
    public void cuttingSingleSamplesPutsClicksIntoTheSine() {
        DeviceModel m = phone();
        m.speed = 0.9995;
        DeviceRig rig = new DeviceRig(m, new ClientEngineConfig().smoothCorrections(false));
        rig.useSine();
        rig.connect();
        rig.run(30_000);
        assertTrue(rig.report() + " steps " + rig.sink.counters().sineStepsOverSlope, rig.sink.counters().sineStepsOverSlope > 10);
    }

    /** Chunks of 1147 frames at 44.1 kHz (26.01 ms) with exact timestamps and +-3 ms of timestamp jitter: no frame lost or doubled. */
    @Test
    public void chunkBoundariesWithRoundedTimestampsAndJitterLoseNoFrame() {
        DeviceRig rig = new DeviceRig(phone(), new ClientEngineConfig(), 44100, 1147);
        rig.chunkJitterMicros = 3_000;
        rig.connect();
        rig.run(180_000);
        DeviceSimSink.Counters c = rig.sink.counters();
        EngineStatus s = rig.status();
        assertEquals(rig.report() + " largest jump " + c.largestMediaJump, 0, c.mediaJumps - s.correctionBlocks);
        assertTrue(rig.report(), c.playedSilentFramesAfterAudio <= 480);
        assertEquals(rig.report(), 0, s.flushes);
        assertEquals(0, s.gapsFilled);
        assertEquals(0, s.stepsWhilePlaying);
    }

    @Test
    public void aSineOverChunkBoundariesWithTimestampJitterIsContinuous() {
        DeviceRig rig = new DeviceRig(phone(), new ClientEngineConfig().smoothCorrections(true), 44100, 1147);
        rig.useSine();
        rig.chunkJitterMicros = 3_000;
        rig.connect();
        rig.run(120_000);
        DeviceSimSink.Counters c = rig.sink.counters();
        assertEquals(rig.report() + " max step " + c.sineMaxStep, 0, c.sineStepsOverSlope);
        assertTrue(rig.report(), c.playedSilentFramesAfterAudio <= 480);
    }

    /** The output takes writes only in multiples of 512 frames when it is nearly full: the rest must be queued again, not lost. */
    @Test
    public void aWriteThatTakesFewerFramesThanOfferedLosesNothing() {
        DeviceModel m = phone();
        m.bufferFrames = 9600; // 200 ms: the engine's silence while it waits for a position fills it up
        m.startThresholdFrames = 9600;
        m.reportedThresholdFrames = 1;
        m.thresholdSettable = false;
        m.writeGranularityFrames = 512;
        DeviceRig rig = new DeviceRig(m);
        rig.connect();
        rig.run(30_000);
        DeviceSimSink.Counters c = rig.sink.counters();
        EngineStatus s = rig.status();
        assertTrue("writes came back short: " + s.shortWrites, s.shortWrites > 0);
        assertTrue(s.worstShortWriteFrames > 0);
        assertEquals(rig.report() + " largest jump " + c.largestMediaJump, 0, c.mediaJumps - s.correctionBlocks);
    }

    // ---- B (d): thread scheduling against the write-ahead

    @Test
    public void aStallOfTheEngineThreadShorterThanTheWriteAheadIsInvisible() {
        DeviceRig rig = new DeviceRig(phone());
        rig.connect();
        rig.run(8000);
        long flushes = rig.status().flushes;
        long silence = rig.sink.counters().playedSilentFramesAfterAudio;
        rig.stallEngine(80);
        rig.run(6000);
        assertEquals(rig.report(), 0, rig.sink.counters().realUnderruns);
        assertEquals(rig.report(), flushes, rig.status().flushes);
        assertTrue(rig.report(), rig.sink.counters().playedSilentFramesAfterAudio - silence <= 480);
        assertTrue("longest gap " + rig.status().maxTickGapMs, rig.status().maxTickGapMs >= 80);
    }

    @Test
    public void aShortWriteAheadDriesOutOnTheSameStallAndALongOneDoesNot() {
        long[] realUnderruns = new long[2];
        int[] ahead = {40, 300};
        for (int i = 0; i < 2; i++) {
            DeviceRig rig = new DeviceRig(phone(), new ClientEngineConfig().maxAheadMs(ahead[i]));
            rig.connect();
            rig.run(8000);
            assertEquals(ahead[i], rig.status().writeAheadMs);
            rig.stallEngine(150);
            rig.run(3000);
            realUnderruns[i] = rig.sink.counters().realUnderruns;
        }
        assertTrue("write-ahead 40 ms dries on a 150 ms stall", realUnderruns[0] >= 1);
        assertEquals("write-ahead 300 ms does not", 0, realUnderruns[1]);
    }

    @Test
    public void aLongWriteAheadKeepsTheOutputThatFarAhead() {
        DeviceRig rig = new DeviceRig(phone(), new ClientEngineConfig().maxAheadMs(300));
        rig.connect();
        rig.run(12_000);
        EngineStatus s = rig.status();
        assertTrue("ahead " + s.outputAheadMs, s.outputAheadMs >= 270 && s.outputAheadMs <= 340);
        assertEquals(300, s.writeAheadMs);
        // the audio is there in time (released early enough): no silence is written into the stream instead of it
        assertTrue(rig.report(), rig.sink.counters().playedSilentFramesAfterAudio <= 480);
        assertEquals(rig.report(), 0, rig.sink.counters().mediaJumps - s.correctionBlocks);
    }

    // ---- D, E: what the trace and the status say

    @Test
    public void theTraceAndTheStatusAnswerHowTheLoopAndTheWritesBehaved() {
        DeviceModel m = phone();
        m.speed = 1.0005;
        DeviceRig rig = new DeviceRig(m);
        rig.connect();
        rig.run(10_000);
        rig.stallEngine(300);
        rig.run(5000);
        String text = rig.engine.trace().text();
        assertTrue(text, text.contains("engine loop, last second"));
        assertTrue(text, text.contains("writes, last second"));
        assertTrue(text, text.contains("corrections, last second"));
        assertTrue(text, text.contains("clock offset"));
        assertTrue("the stall shows as the longest gap: " + text, text.contains("max gap ms=3"));
        EngineStatus s = rig.status();
        assertTrue(s.maxTickGapMs >= 300);
        assertTrue(s.writes > 100);
        assertTrue(s.correctionBlocks > 0);
        String status = SnapcastClientStatusText.of(viewOf(s), 0);
        assertTrue(status, status.contains("writes: "));
        assertTrue(status, status.contains("longest gap between engine ticks"));
        assertTrue(status, status.contains("most in one block"));
        assertTrue(status, status.contains("write-ahead 120 ms"));
    }

    @Test
    public void aPadOrSkipInAPlayingStreamIsCountedAndTraced() {
        DeviceRig rig = new DeviceRig(phone());
        rig.connect();
        rig.run(8000);
        rig.offsets.offset += 120_000; // beyond the gradual limit: the engine steps
        rig.run(4000);
        EngineStatus s = rig.status();
        assertTrue("steps while playing " + s.stepsWhilePlaying + " " + rig.report(), s.stepsWhilePlaying >= 1);
        assertTrue(rig.engine.trace().text(), rig.engine.trace().text().contains("pad or skip while playing"));
    }

    private static SnapcastClientRuntime.View viewOf(EngineStatus s) {
        SnapcastClientRuntime.phase(SnapcastClientRuntime.Phase.RUNNING, SnapcastClientRuntime.Ending.NONE, 0);
        SnapcastClientRuntime.engine(s);
        return SnapcastClientRuntime.get();
    }

    // ---- Group 8i: the owner's closed loop (about 4 pads or skips a second while playing, 2.6 s of audio thrown away in 43 s)

    /**
     * Reports with a position age that jitters by 0 to 42 ms every 260 ms and a time that is consistently about 20 ms late:
     * the measured lateness is +20 +- 20 ms from the start on. The engine used to step (skip 28 ms), reset its median, take
     * the next single report (40 ms) as the drift and step again, four times a second, and never converged.
     */
    @Test
    public void aBiasedAndJitteryPositionDoesNotMakeTheEngineSkipOverAndOver() {
        DeviceModel m = phone();
        m.timestampPeriodMicros = 260_000;
        m.timestampNoiseMicros = 42_000;
        m.noiseLate = true; // the reported times are late by 0..42 ms: the drift reads +21 +- 21 ms, as in the trace
        DeviceRig rig = new DeviceRig(m, new ClientEngineConfig().smoothCorrections(true));
        rig.flac = true;
        rig.useSine();
        rig.connect();
        rig.run(240_000);
        double mean = 0; // the median of seven noisy reports moves by +-10 ms: judge the mean of a minute
        for (int i = 0; i < 60; i++) {
            rig.run(1000);
            mean += rig.status().driftSmoothedMicros / 60.0;
        }
        EngineStatus s = rig.status();
        DeviceSimSink.Counters c = rig.sink.counters();
        assertTrue("pads or skips while playing: " + s.stepsWhilePlaying + ", frames " + s.stepsWhilePlayingFrames + " " + rig.report(),
                s.stepsWhilePlaying <= 3);
        assertTrue("audio thrown away: " + s.stepSkippedFrames, s.stepSkippedFrames <= 48000 / 10);
        assertTrue("mean smoothed drift over the last minute " + mean, Math.abs(mean) < 5_000);
        assertTrue("discontinuities in the sine: " + c.sineStepsOverSlope + " max step " + c.sineMaxStep, c.sineStepsOverSlope <= 5);
        assertEquals(rig.report(), 0, s.flushes);
        // no systematic loss: what was dropped by the gentle corrections is what the bias asked for (about 20 ms)
        assertTrue("corrections " + s.correctionDroppedFrames + " / " + s.correctionRepeatedFrames,
                Math.abs(s.correctionDroppedFrames - s.correctionRepeatedFrames) <= 48 * 60);
    }

    /** A single report is never taken as the drift: after a step the next one must not trigger another. */
    @Test
    public void aStepIsNotFollowedByAStepOnASingleReport() {
        DeviceModel m = phone();
        m.timestampPeriodMicros = 260_000;
        m.timestampNoiseMicros = 42_000;
        DeviceRig rig = new DeviceRig(m);
        rig.connect();
        rig.run(8000);
        rig.offsets.offset += 120_000; // a real change of 120 ms: one step, then quiet
        rig.run(30_000);
        EngineStatus s = rig.status();
        assertTrue("steps " + s.stepsWhilePlaying + " " + rig.report(), s.stepsWhilePlaying >= 1 && s.stepsWhilePlaying <= 8);
        assertTrue("smoothed drift " + s.driftSmoothedMicros, Math.abs(s.driftSmoothedMicros) < 5_000);
    }

    @Test
    public void theStepsOfTheLastSecondAreInTheTraceAndTheStatus() {
        DeviceRig rig = new DeviceRig(phone());
        rig.connect();
        rig.run(8000);
        rig.offsets.offset += 120_000;
        rig.run(4000);
        String trace = rig.engine.trace().text();
        assertTrue(trace, trace.contains("pads and skips while playing, last second"));
        assertTrue(rig.status().stepsWhilePlayingFrames > 0);
        assertTrue(rig.status().smoothCorrections == false);
        DeviceRig smooth = new DeviceRig(phone(), new ClientEngineConfig().smoothCorrections(true));
        smooth.connect();
        smooth.run(2000);
        assertTrue(smooth.status().smoothCorrections);
    }
}
