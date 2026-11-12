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

import org.junit.Test;

/**
 * "peep....peep": the same perfect stream on simulated devices that differ in the ways real audio outputs may differ.
 * What counts is what is <i>played</i>: after the first audio there must be (nearly) no silence and no flush. Each test
 * is one mechanism by which the output could play "a little, silence, repeat".
 */
public class DeviceContinuityTest {

    /** Silence after the first audio that a listener would not notice (the fine corrections), in frames. */
    private static final long TOLERATED_SILENCE_FRAMES = 480;

    private static void assertContinuous(DeviceRig rig, long runMillis) {
        rig.connect();
        rig.run(runMillis);
        DeviceSimSink.Counters c = rig.sink.counters();
        String report = rig.report();
        assertTrue("audio never played: " + report, c.playedAudibleFrames > 0);
        assertTrue("not continuous: " + report, c.playedSilentFramesAfterAudio <= TOLERATED_SILENCE_FRAMES);
        assertEquals("flushed while playing: " + report, 0, rig.status().flushes);
        assertTrue("out of sync with the server clock by " + c.maxSyncErrorAfterSettleMicros / 1000 + " ms: " + report,
                c.syncSamples > 0 && c.maxSyncErrorAfterSettleMicros < 30_000);
        assertTrue("more than 95 percent of the last seconds must be audio: " + report,
                c.playedAudibleFrames > (runMillis - 2500) * 48L * 95 / 100);
    }

    // ---- control: a benign device

    @Test
    public void benignDeviceIsContinuous() {
        assertContinuous(new DeviceRig(new DeviceModel()), 20_000);
    }

    // ---- mechanism 1: the track starts only when the whole buffer is filled (default since API 31) and the engine
    // writes only a short prefill until the first position arrives

    @Test
    public void strictStartThresholdWithSlowTimestamps() {
        DeviceModel m = new DeviceModel();
        m.startThresholdFrames = m.bufferFrames;
        m.timestampPeriodMicros = 500_000;
        assertContinuous(new DeviceRig(m), 20_000);
    }

    /** The output waits for a full buffer but does not say so (older Android): the engine queues silence until it starts. */
    @Test
    public void unknownStartThresholdIsFoundByQueueingUntilItStarts() {
        DeviceModel m = new DeviceModel();
        m.startThresholdFrames = m.bufferFrames;
        m.reportedThresholdFrames = 1;
        assertContinuous(new DeviceRig(m), 20_000);
    }

    /** The control: without the priming the same device never starts (no position, the session ends with a failure). */
    @Test
    public void unknownStartThresholdWithoutPrimingNeverStarts() {
        DeviceModel m = new DeviceModel();
        m.startThresholdFrames = m.bufferFrames;
        m.reportedThresholdFrames = 1;
        DeviceRig rig = new DeviceRig(m, new ClientEngineConfig().primeAfterMs(1_000_000).hardResyncReports(1)
                .positionOutlierMs(1_000_000));
        rig.connect();
        rig.run(20_000);
        assertEquals(rig.report(), EngineState.FAILED, rig.status().state);
        assertEquals(rig.report(), EngineFailure.NO_PLAYBACK_POSITION, rig.status().failure);
        assertEquals(rig.report(), 0, rig.sink.counters().playedAudibleFrames);
    }

    /** Where the threshold can be set (Android 12), the engine asks for a short one and starts quickly. */
    @Test
    public void settableThresholdIsLoweredAtOpen() {
        DeviceModel m = new DeviceModel();
        m.startThresholdFrames = m.bufferFrames;
        m.thresholdSettable = true;
        DeviceRig rig = new DeviceRig(m);
        assertContinuous(rig, 20_000);
        assertTrue(rig.report(), rig.sink.startThresholdFrames() <= 48000 * StreamingSink.WANTED_START_THRESHOLD_MS / 1000);
    }

    @Test
    public void strictStartThresholdWithFastTimestamps() {
        DeviceModel m = new DeviceModel();
        m.startThresholdFrames = m.bufferFrames;
        assertContinuous(new DeviceRig(m), 20_000);
    }

    // ---- mechanism 2: the timestamp is refreshed rarely (about every 500 ms), in jumps

    @Test
    public void timestampRefreshedEvery500Ms() {
        DeviceModel m = new DeviceModel();
        m.timestampPeriodMicros = 500_000;
        assertContinuous(new DeviceRig(m), 20_000);
    }

    /** The frame count is the one of the last refresh but the time is that of the poll: the position lags by up to a period. */
    @Test
    public void timestampWithPollTimeAndStaleFrames() {
        DeviceModel m = new DeviceModel();
        m.timestampPeriodMicros = 200_000; // longer than ~250 ms looks like an output that stands still (then it is realigned)
        m.timestampTimeIsPollTime = true;
        assertContinuous(new DeviceRig(m), 20_000);
    }

    @Test
    public void timestampWithPollTimeAndStaleFramesStrictThreshold() {
        DeviceModel m = new DeviceModel();
        m.timestampPeriodMicros = 200_000;
        m.timestampTimeIsPollTime = true;
        m.startThresholdFrames = m.bufferFrames;
        assertContinuous(new DeviceRig(m), 20_000);
    }

    // ---- mechanism 3: the first timestamp comes late

    @Test
    public void firstTimestampAfterOneSecond() {
        DeviceModel m = new DeviceModel();
        m.timestampFirstAfterMicros = 1_000_000;
        assertContinuous(new DeviceRig(m), 20_000);
    }

    @Test
    public void firstTimestampAfterOneSecondStrictThreshold() {
        DeviceModel m = new DeviceModel();
        m.timestampFirstAfterMicros = 1_000_000;
        m.startThresholdFrames = m.bufferFrames;
        assertContinuous(new DeviceRig(m), 20_000);
    }

    /** The timestamp comes only after the head position was taken over (3 s): it is tried again and used when it works. */
    @Test
    public void lateTimestampIsTakenUpAgainAfterTheHeadPosition() {
        DeviceModel m = new DeviceModel();
        m.timestampFirstAfterMicros = 3_000_000;
        m.timestampAvailableOnlyAfterRun = true;
        DeviceRig rig = new DeviceRig(m);
        assertContinuous(rig, 30_000);
        assertEquals("timestamp", rig.sink.positionSource());
    }

    // ---- mechanism 4: no timestamp at all, only the head position (coarse)

    @Test
    public void noTimestampsHeadPositionOnly() {
        DeviceModel m = new DeviceModel();
        m.timestampsNever = true;
        assertContinuous(new DeviceRig(m), 20_000);
    }

    @Test
    public void noTimestampsCoarseHeadPosition() {
        DeviceModel m = new DeviceModel();
        m.timestampsNever = true;
        m.headGranularityFrames = 1920; // 40 ms steps
        m.startThresholdFrames = m.bufferFrames;
        assertContinuous(new DeviceRig(m), 20_000);
    }

    // ---- mechanism 5: the clock of the timestamp is not the clock of System.nanoTime

    @Test
    public void timestampClockBaseOffByFiveSeconds() {
        DeviceModel m = new DeviceModel();
        m.timestampBaseDeltaNanos = -5_000_000_000L;
        assertContinuous(new DeviceRig(m), 20_000);
    }

    @Test
    public void timestampClockBaseOffByHours() {
        DeviceModel m = new DeviceModel();
        m.timestampBaseDeltaNanos = 3_600_000_000_000L;
        assertContinuous(new DeviceRig(m), 20_000);
    }

    // ---- mechanism 6: the frame counters go on after a flush (no reset)

    @Test
    public void counterNotResetAfterFlush() {
        DeviceModel m = new DeviceModel();
        m.resetOnFlush = false;
        DeviceRig rig = new DeviceRig(m);
        rig.connect();
        rig.run(3000);
        rig.engine.onCodecHeader(ClientFixtures.pcmHeader(rig.rate, rig.channels, rig.bits)); // same header: ignored
        rig.engine.onConnectionLost();
        rig.engine.onCodecHeader(ClientFixtures.pcmHeader(rig.rate, rig.channels, rig.bits));
        rig.run(20_000);
        DeviceSimSink.Counters c = rig.sink.counters();
        assertTrue(rig.report(), c.playedAudibleFrames > 0);
        // after the recovery the stream must be continuous again: the last 10 seconds without a further flush
        long flushesBefore = rig.status().flushes;
        long silence = c.playedSilentFramesAfterAudio;
        rig.run(10_000);
        assertEquals(rig.report(), flushesBefore, rig.status().flushes);
        assertTrue(rig.report(), c.playedSilentFramesAfterAudio - silence <= TOLERATED_SILENCE_FRAMES);
    }

    // ---- mechanism 7: after an underrun the track plays only when refilled; the counter does not count it

    @Test
    public void stallAfterUnderrunWithoutCount() {
        DeviceModel m = new DeviceModel();
        m.startThresholdFrames = m.bufferFrames;
        m.countUnderruns = false;
        assertContinuous(new DeviceRig(m), 20_000);
    }

    // ---- mechanism 8: the output plays a little fast or slow

    @Test
    public void outputFastBy500Ppm() {
        DeviceModel m = new DeviceModel();
        m.speed = 1.0005;
        m.timestampPeriodMicros = 500_000;
        assertContinuous(new DeviceRig(m), 60_000);
    }

    // ---- mechanism 9: a large output latency

    @Test
    public void outputLatency400Ms() {
        DeviceModel m = new DeviceModel();
        m.outputLatencyMicros = 400_000;
        m.timestampPeriodMicros = 500_000;
        assertContinuous(new DeviceRig(m), 20_000);
    }

    // ---- mechanism 10: one odd report now and then (the frame count lags, the time is current)

    @Test
    public void oddReportNowAndThenDoesNotFlush() {
        DeviceModel m = new DeviceModel();
        m.timestampPeriodMicros = 200_000;
        m.glitchEveryRefreshes = 7;
        assertContinuous(new DeviceRig(m), 30_000);
    }

    /**
     * Even with the other protections off (a lone odd report is taken at once, one report is enough to flush) the odd
     * report alone does not cut the stream: a step needs the median of the last measurements, and the flush for
     * lateness needs fresh reports that agree.
     */
    @Test
    public void anOddReportDoesNotFlushEvenWithTheOtherProtectionsOff() {
        DeviceModel m = new DeviceModel();
        m.timestampPeriodMicros = 200_000;
        m.glitchEveryRefreshes = 7;
        DeviceRig rig = new DeviceRig(m, new ClientEngineConfig().hardResyncReports(1).positionOutlierMs(1_000_000));
        rig.connect();
        rig.run(30_000);
        assertEquals(rig.report(), 0, rig.status().flushes);
        assertEquals(rig.report(), 0, rig.status().stepsWhilePlaying);
    }

    // ---- the event trace of a run

    @Test
    public void theTraceTellsTheStoryOfAStartOnAStrictDevice() {
        DeviceModel m = new DeviceModel();
        m.startThresholdFrames = m.bufferFrames;
        DeviceRig rig = new DeviceRig(m, new ClientEngineConfig().prefillMs(20).primeAfterMs(100));
        rig.connect();
        rig.run(6000);
        String text = rig.engine.trace().text();
        for (String expected : new String[]{"header accepted", "first write", "play()", "position", "align start", "aligned", "drift",
                "queues", "position reports"}) {
            assertTrue(expected + " missing in:\n" + text, text.contains(expected));
        }
        assertTrue(rig.engine.trace().size() <= EventTrace.CAPACITY);
        assertEquals(text, rig.status().trace.text());
        assertTrue(rig.status().outputSummary, rig.status().outputSummary.contains("track buffer 19200"));
        assertTrue(rig.status().outputSummary, rig.status().outputSummary.contains("timestamp ever ok yes"));
    }

    @Test
    public void aFlushIsInTheTraceWithItsReasonAndTheHeadFallbackIsToo() {
        DeviceModel m = new DeviceModel();
        m.timestampsNever = true;
        DeviceRig rig = new DeviceRig(m);
        rig.connect();
        rig.run(4000);
        rig.engine.onConnectionLost();
        rig.engine.onCodecHeader(ClientFixtures.pcmHeader(rig.rate, rig.channels, rig.bits));
        String text = rig.engine.trace().text();
        assertTrue(text, text.contains("reason=connection_lost"));
        assertTrue(text, text.contains("position from head position now"));
    }

    // ---- Group 8g: the underrun counter of the owner's phone (Nothing A142, Android 16) rises without any starvation

    /**
     * Trace of the phone (shortened, ms since the engine started): idle phase 74131-81847 with nothing queued by the
     * server, the engine writing silence, output ahead 110-128 ms, position advancing in real time, and still
     * "underrun new=1" every ~42.6 ms (2048 frames), total 1014 -> 1195 in 7.7 s. In the active phase after "aligned"
     * (drift -6 us, ahead 110-170 ms) the first increment caused "flush reason=underrun" at 91113, 91825, 92574, 93339,
     * 94183 ms: 450 ms of tone, a gap, again: "peep....peep".
     */
    private static DeviceModel spuriousCounterPhone() {
        DeviceModel m = new DeviceModel();
        m.startThresholdFrames = 1920;
        m.thresholdSettable = true;
        m.timestampPeriodMicros = 20_000;
        m.outputLatencyMicros = 35_000;
        m.spuriousUnderrunPeriodFrames = 2048;
        return m;
    }

    @Test
    public void aCounterThatRisesWithoutStarvationNeverFlushes() {
        DeviceRig rig = new DeviceRig(spuriousCounterPhone());
        assertContinuous(rig, 30_000);
        assertTrue(rig.report(), rig.sink.counters().spuriousUnderruns > 100);
        assertEquals(rig.report(), 0, rig.sink.counters().realUnderruns);
        EngineStatus s = rig.status();
        assertEquals(0, s.flushesUnderrun);
        assertTrue("the counter was judged unreliable: " + s.underrunCounterUnreliable, s.underrunCounterUnreliable);
        assertTrue(s.underrunsSpurious >= 5);
        assertEquals(0, s.underrunsCorroborated);
    }

    @Test
    public void theTraceStaysReadableWhileTheCounterRisesEvery42Ms() {
        DeviceRig rig = new DeviceRig(spuriousCounterPhone());
        rig.connect();
        rig.run(30_000);
        String text = rig.engine.trace().text();
        int rises = 0;
        for (String line : text.split("\n")) {
            if (line.contains("  underrun  ")) {
                rises++;
            }
        }
        assertTrue("one summary line per second, not one per rise: " + rises, rises <= 31);
        assertTrue(text, text.contains("underrun counter judged  verdict=spurious (no flush)"));
        assertTrue(text, text.contains("verdict=counter unreliable, no longer used"));
        assertTrue("the start is still in the trace: " + rig.engine.trace().size(), text.contains("aligned"));
    }

    @Test
    public void aCounterThatRisesWithoutStarvationAlsoWhileTheStreamIsIdle() {
        DeviceRig rig = new DeviceRig(spuriousCounterPhone());
        rig.connect();
        rig.run(6000);
        rig.sending = false; // the server has nothing to send: the engine writes silence
        rig.run(8000);
        rig.sending = true;
        rig.run(15_000);
        EngineStatus s = rig.status();
        assertEquals(rig.report(), 0, s.flushesUnderrun);
        assertEquals(rig.report(), 0, rig.sink.counters().realUnderruns);
        DeviceSimSink.Counters c = rig.sink.counters();
        // after the idle phase the tone plays again and goes on without a pause (silence after audio stays the idle phase's)
        long silenceBefore = c.playedSilentFramesAfterAudio;
        long flushesBefore = s.flushes;
        rig.run(10_000);
        assertEquals(rig.report(), flushesBefore, rig.status().flushes);
        assertTrue(rig.report(), c.playedSilentFramesAfterAudio - silenceBefore <= TOLERATED_SILENCE_FRAMES);
    }

    /** A real dry-out (the engine thread blocked 300 ms) with a counter that cannot be trusted: found by the position. */
    @Test
    public void aRealDryOutIsFoundWithoutTheCounter() {
        DeviceRig rig = new DeviceRig(spuriousCounterPhone());
        rig.connect();
        rig.run(12_000); // long enough to judge the counter unreliable
        assertTrue(rig.report(), rig.status().underrunCounterUnreliable);
        long flushes = rig.status().flushes;
        rig.stallEngine(300);
        rig.run(4000);
        assertTrue("the dry-out has been seen: " + rig.report(), rig.sink.counters().realUnderruns >= 1);
        assertTrue("and repaired by a flush: " + rig.report(), rig.status().flushes > flushes);
        assertTrue(rig.report(), rig.status().flushes <= flushes + 2);
        // after the repair the tone is continuous again
        DeviceSimSink.Counters c = rig.sink.counters();
        long silence = c.playedSilentFramesAfterAudio;
        long flushesAfter = rig.status().flushes;
        rig.run(10_000);
        assertEquals(rig.report(), flushesAfter, rig.status().flushes);
        assertTrue(rig.report(), c.playedSilentFramesAfterAudio - silence <= TOLERATED_SILENCE_FRAMES);
    }

    /** The same dry-out on a device whose counter is true: corroborated by the position, flushed as before. */
    @Test
    public void aRealDryOutWithATrueCounterIsCorroboratedAndRepaired() {
        DeviceModel m = new DeviceModel();
        m.timestampPeriodMicros = 20_000;
        DeviceRig rig = new DeviceRig(m);
        rig.connect();
        rig.run(8000);
        long flushes = rig.status().flushes;
        rig.stallEngine(300);
        rig.run(4000);
        assertTrue(rig.report(), rig.sink.counters().realUnderruns >= 1);
        assertTrue(rig.report(), rig.status().flushes > flushes);
        assertTrue(rig.report(), rig.status().flushes <= flushes + 2);
        assertTrue(rig.report(), rig.status().underrunsCorroborated >= 1 || rig.status().flushesWriterBehind >= 1);
        assertEquals(rig.report(), 0, rig.status().underrunsSpurious);
        DeviceSimSink.Counters c = rig.sink.counters();
        long silence = c.playedSilentFramesAfterAudio;
        long flushesAfter = rig.status().flushes;
        rig.run(10_000);
        assertEquals(rig.report(), flushesAfter, rig.status().flushes);
        assertTrue(rig.report(), c.playedSilentFramesAfterAudio - silence <= TOLERATED_SILENCE_FRAMES);
    }

    /** No counter at all: a dry-out is repaired from the position alone. */
    @Test
    public void aRealDryOutWithoutAnyCounterIsRepaired() {
        DeviceModel m = new DeviceModel();
        m.timestampPeriodMicros = 20_000;
        m.countUnderruns = false;
        DeviceRig rig = new DeviceRig(m);
        rig.connect();
        rig.run(8000);
        long flushes = rig.status().flushes;
        rig.stallEngine(300);
        rig.run(4000);
        assertTrue(rig.report(), rig.sink.counters().realUnderruns >= 1);
        assertTrue(rig.report(), rig.status().flushes > flushes);
        assertTrue(rig.report(), rig.status().flushes <= flushes + 2);
    }

    /** The output stands still for 400 ms with data queued (a route hiccup): the position stalls, the engine realigns. */
    @Test
    public void aFrozenOutputWithDataQueuedIsFoundByThePositionStall() {
        DeviceModel m = new DeviceModel();
        m.timestampPeriodMicros = 20_000;
        m.countUnderruns = false;
        DeviceRig rig = new DeviceRig(m);
        rig.connect();
        rig.run(8000);
        long flushes = rig.status().flushes;
        rig.sink.freezeOutput(400_000);
        rig.run(4000);
        assertTrue(rig.report(), rig.status().flushes > flushes);
        assertTrue(rig.report(), rig.status().flushesPositionStall >= 1);
        DeviceSimSink.Counters c = rig.sink.counters();
        long silence = c.playedSilentFramesAfterAudio;
        long flushesAfter = rig.status().flushes;
        rig.run(10_000);
        assertEquals(rig.report(), flushesAfter, rig.status().flushes);
        assertTrue(rig.report(), c.playedSilentFramesAfterAudio - silence <= TOLERATED_SILENCE_FRAMES);
    }

    /** A short hiccup of 60 ms (under the limits) must not flush: the position is only slow for a moment. */
    @Test
    public void aShortHiccupDoesNotFlush() {
        DeviceModel m = new DeviceModel();
        m.timestampPeriodMicros = 20_000;
        DeviceRig rig = new DeviceRig(m);
        rig.connect();
        rig.run(8000);
        long flushes = rig.status().flushes;
        rig.sink.freezeOutput(40_000);
        rig.run(6000);
        assertEquals(rig.report(), flushes, rig.status().flushes);
    }

    @Test
    public void aLegitimateFlushCostsAGapOfAboutTheOutputAheadAndNoMore() {
        DeviceModel m = new DeviceModel();
        m.timestampPeriodMicros = 20_000;
        m.outputLatencyMicros = 35_000;
        m.startThresholdFrames = 1920;
        m.thresholdSettable = true;
        DeviceRig rig = new DeviceRig(m);
        rig.connect();
        rig.run(8000);
        DeviceSimSink.Counters c = rig.sink.counters();
        long silence = c.playedSilentFramesAfterAudio;
        rig.sink.freezeOutput(400_000); // makes the engine flush once
        rig.run(5000);
        long gapFrames = c.playedSilentFramesAfterAudio - silence;
        // the gap is what the output holds ahead (~120 ms) plus its start-up (threshold 40 ms + latency 35 ms) plus slack
        assertTrue("gap " + gapFrames * 1000 / 48000 + " ms: " + rig.report(), gapFrames <= 48000 * 400L / 1000);
    }
}
