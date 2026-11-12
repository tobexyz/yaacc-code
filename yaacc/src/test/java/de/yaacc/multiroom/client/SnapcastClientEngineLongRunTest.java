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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Hours of healthy playback on the simulated output (a manual clock; the audio is analysed on the way in and not kept):
 * nothing may fire only because time has passed. Review C1 of Group 8: the timeline was cut every 10 minutes.
 */
public class SnapcastClientEngineLongRunTest {

    private static final long MINUTE = 60_000L;

    private static void assertHealthyAndSeamless(EngineRig rig, long minutes, String what) {
        EngineStatus s = rig.engine.status();
        assertNull(what, s.failure);
        assertEquals(what + ": state", EngineState.PLAYING, s.state);
        assertEquals(what + ": timeline resyncs", 0, s.timelineResyncs);
        assertEquals(what + ": flushes", 0, s.flushes);
        assertEquals(what + ": underruns", 0, s.underruns);
        assertEquals(what + ": no hole in the audio (frames of silence)", 0, rig.sink.longestHole);
        assertEquals(what + ": no jump in the media numbers", 0, rig.sink.badSteps);
        long expected = minutes * 60L * 48000L;
        assertTrue(what + ": played " + rig.sink.audibleFrames + " of about " + expected,
                rig.sink.audibleFrames > expected - 3L * 48000L);
    }

    @Test
    public void fortyMinutesOfPerfectPlaybackHaveNoResyncAndNoHole() {
        EngineRig rig = EngineRig.longRun(1.0);
        rig.connect();
        rig.run(40 * MINUTE);
        assertHealthyAndSeamless(rig, 40, "40 minutes");
        assertEquals("a perfect output needs no correction", 0, rig.engine.status().correctionDroppedFrames
                + rig.engine.status().correctionRepeatedFrames);
    }

    @Test
    public void threeHoursOfPerfectPlaybackHaveNoResyncAndNoHole() {
        EngineRig rig = EngineRig.longRun(1.0);
        rig.connect();
        rig.run(180 * MINUTE);
        assertHealthyAndSeamless(rig, 180, "3 hours");
    }

    @Test
    public void threeHoursWithAnOutputClockThatRunsSlowAreHeldByDroppingSingleFramesWithoutAHole() {
        EngineRig rig = EngineRig.longRun(0.99985); // 150 ppm slow: 1.6 s late after 3 hours if left alone
        rig.connect();
        rig.run(180 * MINUTE);
        assertHealthyAndSeamless(rig, 180, "slow output");
        EngineStatus s = rig.engine.status();
        assertTrue("frames were dropped: " + s.correctionDroppedFrames, s.correctionDroppedFrames > 70_000);
        assertEquals(0, s.correctionRepeatedFrames);
        assertTrue("drift " + s.driftMicros, Math.abs(s.driftMicros) <= 3000);
    }

    @Test
    public void threeHoursWithAnOutputClockThatRunsFastAreHeldByRepeatingSingleFrames() {
        EngineRig rig = EngineRig.longRun(1.00015);
        rig.connect();
        rig.run(180 * MINUTE);
        assertHealthyAndSeamless(rig, 180, "fast output");
        EngineStatus s = rig.engine.status();
        assertTrue(s.correctionRepeatedFrames > 70_000);
        assertEquals(0, s.correctionDroppedFrames);
        assertTrue("drift " + s.driftMicros, Math.abs(s.driftMicros) <= 3000);
    }

    @Test
    public void aServerRestartAfterAQuarterOfAnHourIsAnEpochAndTheStreamIsAnchoredAgain() {
        EngineRig rig = EngineRig.longRun(1.0);
        rig.connect();
        rig.run(15 * MINUTE);
        assertEquals(0, rig.engine.status().timelineResyncs);

        rig.restartServer(EngineRig.OFFSET + 5_000_000_000L);
        rig.run(20_000);

        EngineStatus s = rig.engine.status();
        assertEquals(1, s.epochChanges);
        assertEquals(0, s.timelineResyncs);
        assertEquals(0, s.flushes);
        assertEquals(EngineState.PLAYING, s.state);
        assertTrue(s.aligned);
        assertTrue("drift " + s.driftMicros, Math.abs(s.driftMicros) <= 3000);
        assertEquals("the output was never re-created", 1, rig.sink.opens);
    }

    @Test
    public void aGapOfEightSecondsAfterHalfAnHourStartsTheTimelineAnewExactlyOnce() {
        EngineRig rig = EngineRig.longRun(1.0);
        rig.connect();
        rig.run(30 * MINUTE);
        assertEquals("nothing happened before the gap", 0, rig.engine.status().timelineResyncs);

        rig.nextChunk += 400; // the source was silent for 8 s
        rig.run(14_000);

        EngineStatus s = rig.engine.status();
        assertEquals(1, s.timelineResyncs);
        assertEquals(EngineState.PLAYING, s.state);
        assertTrue(s.aligned);
        assertNull(s.failure);
    }

    @Test
    public void aGapOfOneSecondAfterAnHourIsFilledWithSilenceAndNothingElse() {
        EngineRig rig = EngineRig.longRun(1.0);
        rig.connect();
        rig.run(60 * MINUTE);
        rig.nextChunk += 50; // 1 s
        rig.run(8_000);

        EngineStatus s = rig.engine.status();
        assertEquals(0, s.timelineResyncs);
        assertEquals(1, s.gapsFilled);
        assertEquals(0, s.flushes);
        assertEquals("the hole is the gap and nothing more", 48000, rig.sink.longestHole, 100);
    }

    @Test
    public void theTimelineIsRebasedInPlaceWithoutAnyGlitch() {
        ClientEngineConfig config = new ClientEngineConfig();
        config.rebaseFrames(480_000); // every 10 s instead of every few days
        EngineRig rig = new EngineRig(c -> {
            SimulatedSink sink = new SimulatedSink(c, 24000, 40_000, 1.0, 100_000);
            sink.keepWrites = false;
            return sink;
        }, config, new DefaultDecoderFactory(null), 48000, 2, 16);
        rig.connect();
        rig.run(3 * MINUTE);

        EngineStatus s = rig.engine.status();
        assertTrue("rebased " + s.timelineRebases, s.timelineRebases >= 15);
        assertEquals(0, s.timelineResyncs);
        assertEquals(0, s.flushes);
        assertEquals(0, rig.sink.longestHole);
        assertEquals(0, rig.sink.badSteps);
        assertTrue("drift " + s.driftMicros, Math.abs(s.driftMicros) <= 1500);
    }

    @Test
    public void aSlowOutputIsStillHeldAcrossManyRebases() {
        ClientEngineConfig config = new ClientEngineConfig();
        config.rebaseFrames(480_000);
        EngineRig rig = new EngineRig(c -> {
            SimulatedSink sink = new SimulatedSink(c, 24000, 40_000, 0.9998, 100_000);
            sink.keepWrites = false;
            return sink;
        }, config, new DefaultDecoderFactory(null), 48000, 2, 16);
        rig.connect();
        rig.run(10 * MINUTE);
        EngineStatus s = rig.engine.status();
        assertTrue(s.timelineRebases >= 50);
        assertEquals(0, s.timelineResyncs);
        assertEquals(0, rig.sink.badSteps);
        assertTrue("drift " + s.driftMicros, Math.abs(s.driftMicros) <= 3000);
    }

    @Test
    public void frameCountsPastTwoToThe31And2ToThe32DoNotMatter() {
        // after 3.2 hours at 192 kHz (or 12 hours at 48 kHz) the output has counted 2^31 frames, after twice that 2^32
        for (long target : new long[]{1L << 31, 1L << 32}) {
            EngineRig rig = new EngineRig(c -> {
                SimulatedSink sink = new SimulatedSink(c, 24000, 40_000, 1.0, 100_000);
                sink.keepWrites = false;
                return sink;
            }, new ClientEngineConfig(), new DefaultDecoderFactory(null), 48000, 2, 16);
            rig.connect();
            rig.run(20_000);
            assertEquals(EngineState.PLAYING, rig.engine.status().state);
            long delta = target - 100_000L - rig.sink.framesWritten();
            rig.sink.shiftFrameCounts(delta); // the same instant: the output and the engine have counted that far
            rig.engine.debugAdvanceFrameCounts(delta);
            rig.run(60_000);
            EngineStatus s = rig.engine.status();
            assertTrue("past " + target, rig.sink.framesWritten() > target + 100_000L);
            assertEquals("start " + target, EngineState.PLAYING, s.state);
            assertEquals(0, s.timelineResyncs);
            assertEquals(0, s.flushes);
            assertEquals(0, s.underruns);
            assertEquals(0, rig.sink.longestHole);
            assertEquals(0, rig.sink.badSteps);
            assertTrue("drift " + s.driftMicros, Math.abs(s.driftMicros) <= 1500);
        }
    }
}
