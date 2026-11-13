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

    // ---- Group 8g/8i: the underrun counter of the owner's phone (Nothing A142, Android 16): a deep-buffer output

    /**
     * The platform log of the phone: "createTrack_l ... mLatency = 528, mAfLatency = 128, frameCount = 19200,
     * mFlags = 00000008 (AUDIO_OUTPUT_FLAG_DEEP_BUFFER), mNotificationFramesAct = 2048". With the engine keeping 120 ms
     * queued (less than the 128 ms of the deep-buffer mixer) the counter rose 3 to 8 times a second (467 rises in 43 s)
     * and the owner heard regular little breaks; with a write-ahead of 250 ms the counter stayed at 0 and the breaks
     * were gone. Shortened trace of the first run: rises every ~42.6 ms (2048 frames) while 97 to 140 ms were queued.
     */
    private static DeviceModel deepBufferPhone() {
        DeviceModel m = new DeviceModel();
        m.startThresholdFrames = 1920;
        m.thresholdSettable = true;
        m.timestampPeriodMicros = 20_000;
        m.outputLatencyMicros = 35_000;
        m.mixerMinQueuedFrames = 6144 + 480; // 128 ms of the mixer and a little more
        return m;
    }

    /** Fixture of the owner's first run: write-ahead 120, the counter rises several times a second, breaks, no flush. */
    @Test
    public void ownersTraceAtWriteAhead120TheCounterRisesAndTheToneBreaks() {
        DeviceRig rig = new DeviceRig(deepBufferPhone(), new ClientEngineConfig().maxAheadMs(120).adaptiveWriteAhead(false));
        rig.connect();
        rig.run(43_000);
        DeviceSimSink.Counters c = rig.sink.counters();
        EngineStatus s = rig.status();
        double perSecond = c.mixerGlitches / 43.0;
        assertTrue("rises per second " + perSecond + " " + rig.report(), perSecond >= 2 && perSecond <= 15);
        assertTrue("the tone breaks: " + rig.report(), c.playedSilentFramesAfterAudio > 4800);
        // the real stalls (42 ms each) make the stream late; the engine skips or flushes for that lateness, never for a rise
        assertEquals("a rise is a hint, it flushes nothing: " + rig.report(), 0, s.flushesUnderrun);
        assertTrue(s.underruns >= c.mixerGlitches - 5);
        // each stall makes the stream late by its length, the engine catches up with pads and skips: the owner's
        // "pad or skip while playing" several times a second (187 in 43 s)
        assertTrue("steps while playing " + s.stepsWhilePlaying, s.stepsWhilePlaying >= 10);
        assertTrue(s.underrunRisesThin > 0);
        assertEquals(120, s.writeAheadEffectiveMs);
    }

    /** Fixture of the owner's second run: write-ahead 250, no rise at all, a continuous tone. */
    @Test
    public void ownersTraceAtWriteAhead250TheCounterStaysAtZero() {
        DeviceRig rig = new DeviceRig(deepBufferPhone(), new ClientEngineConfig().maxAheadMs(250).adaptiveWriteAhead(false));
        rig.connect();
        rig.run(43_000);
        assertEquals(rig.report(), 0, rig.sink.counters().mixerGlitches);
        assertEquals(rig.report(), 0, rig.status().underruns);
        assertTrue(rig.report(), rig.sink.counters().playedSilentFramesAfterAudio <= 480);
        assertEquals(rig.report(), 0, rig.status().flushes);
        assertEquals(rig.report(), 0, rig.status().stepsWhilePlaying);
        assertTrue("output ahead " + rig.status().outputAheadMs, rig.status().outputAheadMs >= 220);
    }

    /** The engine that starts too thin raises its write-ahead by itself until the counter stops rising, and flushes nothing. */
    @Test
    public void theEngineRaisesTheWriteAheadUntilTheCounterStopsRising() {
        DeviceRig rig = new DeviceRig(deepBufferPhone(), new ClientEngineConfig().maxAheadMs(120));
        rig.connect();
        rig.run(30_000);
        EngineStatus s = rig.status();
        assertTrue("raised: " + s.writeAheadEffectiveMs + " " + rig.report(), s.writeAheadEffectiveMs > 120 && s.writeAheadEffectiveMs <= 350);
        assertTrue(s.writeAheadRaises >= 1);
        assertEquals(120, s.writeAheadMs);
        assertEquals(rig.report(), 0, s.flushesUnderrun);
        String early = rig.engine.trace().text();
        assertTrue(early, early.contains("write-ahead raised to (ms)"));
        assertTrue(early, early.contains("underrun counter judged  verdict=thin cushion"));
        DeviceSimSink.Counters c = rig.sink.counters();
        long glitches = c.mixerGlitches;
        long silence = c.playedSilentFramesAfterAudio;
        int effective = s.writeAheadEffectiveMs;
        long stepsBefore = s.stepsWhilePlaying;
        rig.run(60_000);
        assertEquals("no more pads or skips either", stepsBefore, rig.status().stepsWhilePlaying);
        assertEquals("no more rises once the cushion is thick enough: " + rig.report(), glitches, c.mixerGlitches);
        assertEquals(silence, c.playedSilentFramesAfterAudio);
        assertEquals("never lowered during the session", effective, rig.status().writeAheadEffectiveMs);
        String text = SnapcastClientStatusText.of(viewOf(rig.status()), 0);
        assertTrue(text, text.contains("write-ahead " + effective + " ms (asked 120 ms, raised "));
    }

    /** A mixer that wants more than the engine may ever queue: the raises are bounded, spaced by the cooldown and stop at 350. */
    @Test
    public void theRaisesAreBoundedAndSpacedByTheCooldown() {
        DeviceModel m = deepBufferPhone();
        m.mixerMinQueuedFrames = 24_000; // 500 ms: out of reach
        DeviceRig rig = new DeviceRig(m, new ClientEngineConfig().maxAheadMs(120));
        rig.connect();
        rig.run(60_000);
        EngineStatus s = rig.status();
        assertEquals(350, s.writeAheadEffectiveMs);
        assertTrue("raises " + s.writeAheadRaises, s.writeAheadRaises >= 5 && s.writeAheadRaises <= 20); // 120 -> 350 in steps of 40: 6
        assertTrue(rig.report(), s.flushesUnderrun <= 2);
    }

    /** With the adaptation off the engine keeps what it was given. */
    @Test
    public void withoutTheAdaptationTheWriteAheadStays() {
        DeviceRig rig = new DeviceRig(deepBufferPhone(), new ClientEngineConfig().maxAheadMs(120).adaptiveWriteAhead(false));
        rig.connect();
        rig.run(20_000);
        assertEquals(120, rig.status().writeAheadEffectiveMs);
        assertEquals(0, rig.status().writeAheadRaises);
    }

    @Test
    public void theTraceStaysReadableWhileTheCounterRisesEvery42Ms() {
        DeviceRig rig = new DeviceRig(deepBufferPhone(), new ClientEngineConfig().maxAheadMs(120).adaptiveWriteAhead(false));
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
        assertTrue(text, text.contains("underrun counter judged  verdict=thin cushion"));
        assertTrue("the start is still in the trace", text.contains("aligned"));
    }

    @Test
    public void aThinCushionAlsoWhileTheStreamIsIdleDoesNotFlushAndTheToneIsContinuousAfterwards() {
        DeviceRig rig = new DeviceRig(deepBufferPhone(), new ClientEngineConfig().maxAheadMs(120));
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
        long silenceBefore = c.playedSilentFramesAfterAudio;
        long flushesBefore = s.flushes;
        rig.run(10_000);
        assertEquals(rig.report(), flushesBefore, rig.status().flushes);
        assertTrue(rig.report(), c.playedSilentFramesAfterAudio - silenceBefore <= TOLERATED_SILENCE_FRAMES);
    }

    /** A real dry-out (the engine thread blocked 300 ms) on that output: found by the position, flushed, then continuous. */
    @Test
    public void aRealDryOutOnTheDeepBufferOutputIsRepaired() {
        DeviceRig rig = new DeviceRig(deepBufferPhone(), new ClientEngineConfig().maxAheadMs(250));
        rig.connect();
        rig.run(12_000);
        long flushes = rig.status().flushes;
        rig.stallEngine(400);
        rig.run(4000);
        assertTrue("the dry-out has been seen: " + rig.report(), rig.sink.counters().realUnderruns >= 1);
        assertTrue("and repaired by a flush: " + rig.report(), rig.status().flushes > flushes);
        assertTrue(rig.report(), rig.status().flushes <= flushes + 2);
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
        assertEquals(rig.report(), 0, rig.status().underrunRisesThin);
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

    private static SnapcastClientRuntime.View viewOf(EngineStatus s) {
        SnapcastClientRuntime.phase(SnapcastClientRuntime.Phase.RUNNING, SnapcastClientRuntime.Ending.NONE, 0);
        SnapcastClientRuntime.engine(s);
        return SnapcastClientRuntime.get();
    }
}
