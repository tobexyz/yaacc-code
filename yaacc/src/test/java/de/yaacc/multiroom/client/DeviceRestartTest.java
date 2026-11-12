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
 * Fix Group 8h, the restart failure of the owner's two traces (Nothing A142, flac): the source stopped for 7 to 9 seconds
 * with the connection up, the header was sent again ("header ignored (same stream)"), the chunks came back with
 * timestamps that continue on the server clock, and the engine believed the stream was 9.86 s (first trace, pause about
 * 9 s) and 7.62 s (second trace, pause about 7 s) late: 'late over the hard limit' three times, a hard resync, and then
 * every chunk was skipped for good (1.3 million frames in 26 s), aligned=0, no sound.
 *
 * <p>The arithmetic: a flac chunk is placed after the previous one by counting frames (the decoder's output is not
 * aligned with the chunks), so a pause in the timestamps was invisible; the frames after it were due the pause earlier
 * than the output could play them. After any pause the next chunks must be audible about the server buffer after they were
 * recorded, aligned, and the engine may not throw away more than a second of audio in a row.
 */
public class DeviceRestartTest {

    private static DeviceModel phone() {
        DeviceModel m = new DeviceModel();
        m.startThresholdFrames = 1920;
        m.thresholdSettable = true;
        m.timestampPeriodMicros = 20_000;
        m.outputLatencyMicros = 35_000;
        m.spuriousUnderrunPeriodFrames = 2048;
        return m;
    }

    private enum Variant {
        /** Nothing but the pause. */
        PLAIN,
        /** The server sends the same header again when the stream starts. */
        HEADER_AGAIN,
        /** The connection is lost during the pause and comes back (the header again, the same stream). */
        RECONNECT,
        /** The clock filter finds a new epoch at the restart (the offset itself is unchanged). */
        EPOCH
    }

    private static final long[] PAUSES = {1_000, 3_000, 10_000, 60_000};

    /** Runs one pause and returns a description of what is wrong, or null. */
    private static String pauseAndResume(boolean flac, long pauseMs, Variant variant) {
        DeviceRig rig = new DeviceRig(phone());
        rig.flac = flac;
        rig.connect();
        rig.run(8000);
        String name = (flac ? "flac" : "pcm") + " pause " + pauseMs + " ms " + variant;
        if (rig.sink.counters().playedAudibleFrames == 0) {
            return name + ": no sound before the pause: " + rig.report();
        }
        long skippedBefore = rig.status().stepSkippedFrames + 960 * rig.status().droppedLate;
        rig.sending = false;
        rig.run(pauseMs / 2);
        if (variant == Variant.RECONNECT) {
            rig.engine.onConnectionLost(de.yaacc.multiroom.protocol.SnapcastConnection.LossReason.IO_ERROR.ordinal(), 0);
            rig.run(pauseMs - pauseMs / 2 - 200);
            rig.resendHeader();
            rig.run(200);
        } else {
            rig.run(pauseMs - pauseMs / 2);
        }
        if (variant == Variant.HEADER_AGAIN) {
            rig.resendHeader();
        }
        if (variant == Variant.EPOCH) {
            rig.offsets.epoch++;
        }
        rig.sending = true;
        long audibleAt = rig.millisToAudible(3000);
        EngineStatus s = rig.status();
        if (audibleAt < 0 || audibleAt > 1500) {
            return name + ": first sound after " + audibleAt + " ms (limit 1500); last hard resync drift "
                    + s.lastHardResyncDriftMicros / 1000 + " ms; " + rig.report() + "; skipped " + s.stepSkippedFrames;
        }
        rig.run(6000);
        s = rig.status();
        long skipped = s.stepSkippedFrames + 960 * s.droppedLate - skippedBefore;
        if (skipped > rig.rate) {
            return name + ": threw away " + skipped + " frames (more than a second): " + rig.report();
        }
        if (!s.aligned || Math.abs(s.driftSmoothedMicros) > 3000) {
            return name + ": not aligned 6 s after the restart: aligned=" + s.aligned + " drift " + s.driftSmoothedMicros + " us";
        }
        DeviceSimSink.Counters c = rig.sink.counters();
        long silence = c.playedSilentFramesAfterAudio;
        long audible = c.playedAudibleFrames;
        rig.run(5000);
        if (c.playedAudibleFrames - audible < 5000L * rig.rate / 1000 * 95 / 100
                || c.playedSilentFramesAfterAudio - silence > 480) {
            return name + ": not continuous after the restart: " + rig.report();
        }
        return null;
    }

    private static void check(Variant variant) {
        List<String> problems = new ArrayList<>();
        for (boolean flac : new boolean[]{false, true}) {
            for (long pause : PAUSES) {
                String problem = pauseAndResume(flac, pause, variant);
                if (problem != null) {
                    problems.add(problem);
                }
            }
        }
        assertTrue(problems.size() + " of 8 failed:\n" + String.join("\n", problems), problems.isEmpty());
    }

    @Test
    public void aPauseOfTheSourceIsFollowedBySoundWithinTheServerBuffer() {
        check(Variant.PLAIN);
    }

    @Test
    public void aPauseWithTheHeaderSentAgainIsFollowedBySound() {
        check(Variant.HEADER_AGAIN);
    }

    @Test
    public void aPauseWithAReconnectIsFollowedBySound() {
        check(Variant.RECONNECT);
    }

    @Test
    public void aPauseWithANewClockEpochIsFollowedBySound() {
        check(Variant.EPOCH);
    }

    /** The first trace: pause about 9 s, 1 036 020 to 1 302 123 frames skipped and no sound. */
    @Test
    public void theOwnersFirstTraceNineSecondsOfSilenceOnFlac() {
        assertEquals(null, pauseAndResume(true, 9_300, Variant.HEADER_AGAIN));
    }

    /** The second trace: pause about 7 s, drift stayed at +7.61 to +7.63 s. */
    @Test
    public void theOwnersSecondTraceSevenSecondsOfSilenceOnFlac() {
        assertEquals(null, pauseAndResume(true, 7_000, Variant.HEADER_AGAIN));
    }

    /** What the engine believes right after the restart without the fix: late by about the pause. */
    @Test
    public void theLatenessIsNotThePauseAnyMore() {
        for (long pause : new long[]{7_000, 9_300}) {
            DeviceRig rig = new DeviceRig(phone());
            rig.flac = true;
            rig.connect();
            rig.run(8000);
            rig.sending = false;
            rig.run(pause);
            rig.resendHeader();
            rig.sending = true;
            rig.run(4000);
            long worst = Math.abs(rig.status().lastHardResyncDriftMicros);
            assertTrue("pause " + pause + ": hard resync drift " + worst, worst < 500_000);
            assertTrue(rig.report(), rig.status().stepSkippedFrames < 48000);
        }
    }

    @Test
    public void theRestartIsInTheTraceAndTheStatusWithTheTimeToTheFirstAudio() {
        DeviceRig rig = new DeviceRig(phone());
        rig.flac = true;
        rig.connect();
        rig.run(8000);
        rig.sending = false;
        rig.run(7000);
        rig.resendHeader();
        rig.sending = true;
        rig.run(4000);
        EngineStatus s = rig.status();
        assertEquals(1, s.streamResumes);
        assertTrue("gap " + s.lastResumeGapMs, s.lastResumeGapMs >= 6500 && s.lastResumeGapMs <= 7500);
        assertTrue("first audio after " + s.lastResumeToAudioMs, s.lastResumeToAudioMs >= 0 && s.lastResumeToAudioMs <= 1500);
        String trace = rig.engine.trace().text();
        assertTrue(trace, trace.contains("stream resumed"));
        assertTrue(trace, trace.contains("first audio written after the resume"));
        assertTrue(trace, trace.contains("header ignored (same stream)"));
        String text = SnapcastClientStatusText.of(viewOf(s), 0);
        assertTrue(text, text.contains("stream restarts after a pause: 1, last gap "));
    }

    private static SnapcastClientRuntime.View viewOf(EngineStatus s) {
        SnapcastClientRuntime.phase(SnapcastClientRuntime.Phase.RUNNING, SnapcastClientRuntime.Ending.NONE, 0);
        SnapcastClientRuntime.engine(s);
        return SnapcastClientRuntime.get();
    }

    /** The timestamps of a pcm stream go back by more than the gap that is filled (a restarted stream): not dropped for good. */
    @Test
    public void pcmTimestampsThatGoBackAreNotDroppedForGood() {
        DeviceRig rig = new DeviceRig(phone());
        rig.connect();
        rig.run(8000);
        rig.nextChunk -= 600; // 12 s back: the server's chunks repeat older times
        rig.run(8000);
        DeviceSimSink.Counters c = rig.sink.counters();
        long audible = c.playedAudibleFrames;
        rig.run(4000);
        assertTrue("sound goes on: " + rig.report(), c.playedAudibleFrames - audible > 3500L * 48);
        assertTrue(rig.report(), rig.status().aligned);
    }

    // ---- the pause every five minutes for hours

    private static void longRunWithPauses(boolean flac, long hours) {
        DeviceModel m = phone();
        m.spuriousUnderrunPeriodFrames = 0;
        DeviceRig rig = new DeviceRig(m);
        rig.flac = flac;
        rig.stepMs = 10;
        rig.connect();
        rig.run(8000);
        long cycles = hours * 12;
        for (int i = 0; i < cycles; i++) {
            rig.run(5 * 60_000L - 8000 - 10_000);
            long skippedBefore = rig.status().stepSkippedFrames;
            rig.sending = false;
            rig.run(10_000);
            rig.resendHeader();
            rig.sending = true;
            long at = rig.millisToAudible(3000);
            assertTrue("cycle " + i + ": first sound after " + at + " ms: " + rig.report(), at >= 0 && at <= 1500);
            rig.run(8000);
            assertTrue("cycle " + i + ": skipped " + (rig.status().stepSkippedFrames - skippedBefore),
                    rig.status().stepSkippedFrames - skippedBefore <= rig.rate);
            assertTrue("cycle " + i + ": " + rig.report(), rig.status().aligned);
        }
        EngineStatus s = rig.status();
        assertTrue(rig.report(), s.stepSkippedFrames < cycles * rig.rate);
    }

    @Test
    public void threeHoursOfFlacWithAPauseEveryFiveMinutes() {
        longRunWithPauses(true, 3);
    }

    @Test
    public void anHourOfPcmWithAPauseEveryFiveMinutes() {
        longRunWithPauses(false, 1);
    }
}
