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
 * The rules of Fix Groups 8f to 8i that the review of cycle 4 found unpinned (mutation survivors E5, E6, E8, E10, E21,
 * E4, E19): each test fails when its rule is taken out of the engine.
 */
public class RuleGuardTest {

    private static DeviceModel phone() {
        DeviceModel m = new DeviceModel();
        m.startThresholdFrames = 1920;
        m.thresholdSettable = true;
        m.timestampPeriodMicros = 250_000;
        m.outputLatencyMicros = 35_000;
        return m;
    }

    /**
     * E5: one late report decides nothing: a step needs the median of at least three reports. The odd report comes at each
     * refresh of the position from number 5 on (the alignment itself is at about the 4th report).
     */
    @Test
    public void aLoneLateReportRightAfterTheAlignmentNeverCausesAStep() {
        for (int refresh = 5; refresh <= 16; refresh++) {
            DeviceModel m = phone();
            m.glitchAtRefresh = refresh;
            m.glitchLagMicros = 90_000; // below the outlier gate of the tracker, above the step limit of 50 ms
            DeviceRig rig = new DeviceRig(m);
            rig.connect();
            rig.run(9_000);
            assertTrue("aligned: " + rig.report(), rig.status().aligned);
            assertEquals("odd report number " + refresh + ": " + rig.report(), 0, rig.status().stepsWhilePlaying);
        }
    }

    /** E6: a hard-resync flush needs fresh reports that agree (3), not one. */
    @Test
    public void aLoneReportThatIsFarLateNeverFlushesWithTheDefaultRules() {
        DeviceModel m = phone();
        m.timestampPeriodMicros = 500_000; // the odd report (400 ms behind) is still ahead of the one before it
        m.glitchEveryRefreshes = 7;
        m.glitchLagMicros = 400_000; // over the hard limit of 300 ms
        DeviceRig rig = new DeviceRig(m, new ClientEngineConfig().positionOutlierMs(1_000_000)); // the outlier gate off
        rig.connect();
        rig.run(60_000);
        assertTrue("odd reports reached the engine: " + rig.report(), rig.engine.trace().text().contains("late over the hard limit"));
        assertEquals(rig.report(), 0, rig.status().flushes);
        assertEquals(rig.report(), 0, rig.status().flushesHardResync);
    }

    /** E8: a stream that is late by 1.5 s is not skipped, it is placed anew. */
    @Test
    public void aStreamLateByMoreThanTheSkipBoundIsReAnchoredAndNotSkipped() {
        DeviceRig rig = new DeviceRig(phone());
        rig.bufferMs = 4000;
        rig.connect();
        rig.run(8_000);
        long skippedBefore = rig.status().stepSkippedFrames;
        long resyncsBefore = rig.status().timelineResyncs;
        rig.offsets.offset += 1_500_000L; // the server clock is 1.5 s further ahead: the stream is 1.5 s late
        rig.run(4_000);
        EngineStatus s = rig.status();
        assertTrue("re-anchored: " + rig.report(), s.timelineResyncs > resyncsBefore);
        assertTrue("skipped " + (s.stepSkippedFrames - skippedBefore) + " frames; " + rig.report(),
                s.stepSkippedFrames - skippedBefore < 24_000);
        assertTrue(rig.engine.trace().text(), rig.engine.trace().text().contains("stream resumed"));
    }

    /**
     * E10: pcm chunks whose timestamps go back by seconds never reach the timeline: the scheduler releases chunks in order
     * of their play time and drops a chunk that arrives after later ones were released as late. The stream goes on, nothing
     * is placed twice. (The branch of the engine that re-anchors on an overlap above the gap limit is a safety net behind
     * that contract; no input reaches it, so a mutant that removes it is equivalent.)
     */
    @Test
    public void pcmTimestampsThatGoBackBySecondsAreDroppedAsLateAndTheSoundGoesOn() {
        DeviceRig rig = new DeviceRig(phone());
        rig.connect();
        rig.run(8_000);
        rig.nextChunk -= 250; // 5 s back
        long droppedBefore = rig.status().droppedLate;
        rig.run(8_000);
        DeviceSimSink.Counters c = rig.sink.counters();
        long audible = c.playedAudibleFrames;
        rig.run(3_000);
        assertTrue("dropped as late: " + rig.report(), rig.status().droppedLate - droppedBefore >= 200);
        assertTrue("sound goes on: " + rig.report(), c.playedAudibleFrames - audible > 2_850L * 48);
        assertTrue(rig.report(), rig.status().aligned);
    }

    /**
     * E21: the skip that aligns a block is the lateness, not the whole block (blocks of 4608 frames = 96 ms). The clock
     * settles at different moments, so the first block is late by different small amounts; what is skipped is what it is
     * late by (the trace says by how much at the start of the alignment).
     */
    @Test
    public void aSmallLatenessIsSkippedAsLittleAsItIsAndNotAsMuchAsTheBlock() {
        boolean skipped = false;
        for (int settleMs = 800; settleMs <= 1000; settleMs += 7) {
            DeviceRig rig = new DeviceRig(phone(), new ClientEngineConfig(), 48000, 4608);
            rig.offsets.settled = false;
            rig.connect();
            rig.run(settleMs);
            rig.offsets.settled = true;
            rig.run(6_000);
            String trace = rig.engine.trace().text();
            int at = trace.indexOf("align start  drift us=");
            assertTrue(trace, at >= 0);
            int from = at + "align start  drift us=".length();
            int to = from;
            while (to < trace.length() && (Character.isDigit(trace.charAt(to)) || trace.charAt(to) == '-')) {
                to++;
            }
            long lateMicros = Long.parseLong(trace.substring(from, to));
            long skippedFrames = rig.status().stepSkippedFrames;
            skipped |= skippedFrames > 0;
            assertTrue("settled at " + settleMs + " ms: late by " + lateMicros + " us, skipped " + skippedFrames + " frames; "
                    + rig.report(), skippedFrames <= Math.max(0, lateMicros) * 48 / 1000 + 100);
            assertTrue("settled at " + settleMs + " ms: sync error " + rig.syncErrorMs(), Math.abs(rig.syncErrorMs()) < 3);
        }
        assertTrue("some start was late, so that frames were skipped to align", skipped);
    }

    /** E4: a raised write-ahead is not lowered again by a new header (the same format again, a reconnect). */
    @Test
    public void aRaisedWriteAheadSurvivesANewHeader() {
        DeviceModel m = phone();
        m.timestampPeriodMicros = 20_000;
        m.mixerMinQueuedFrames = 6144 + 480;
        DeviceRig rig = new DeviceRig(m, new ClientEngineConfig().maxAheadMs(120));
        rig.connect();
        rig.run(20_000);
        int raised = rig.status().writeAheadEffectiveMs;
        assertTrue("raised " + raised, raised > 120);
        rig.engine.onConnectionLost();
        rig.engine.onCodecHeader(ClientFixtures.pcmHeader(rig.rate, rig.channels, rig.bits));
        rig.engine.onCodecHeader(ClientFixtures.pcmHeader(48000, 1, 16)); // another format: the output is made anew
        rig.run(2_000);
        assertEquals("never lowered in a session", raised, rig.status().writeAheadEffectiveMs);
    }

    /** E19: a dry-out that the position confirms raises the write-ahead too (the thread was kept away). */
    @Test
    public void aConfirmedDryOutRaisesTheWriteAhead() {
        DeviceRig rig = new DeviceRig(phone(), new ClientEngineConfig().maxAheadMs(120));
        rig.connect();
        rig.run(8_000);
        int before = rig.status().writeAheadEffectiveMs;
        rig.stallEngine(400);
        rig.run(4_000);
        assertTrue(rig.report(), rig.status().underrunsCorroborated >= 1);
        assertTrue("raised from " + before + " to " + rig.status().writeAheadEffectiveMs,
                rig.status().writeAheadEffectiveMs > before);
    }

    /** E20: a rise of the counter while the stream is not aligned is not judged (nothing is raised during the start). */
    @Test
    public void risesBeforeTheStreamIsAlignedRaiseNothing() {
        DeviceModel m = phone();
        m.mixerMinQueuedFrames = 6144 + 480;
        DeviceRig rig = new DeviceRig(m, new ClientEngineConfig().maxAheadMs(120));
        rig.offsets.settled = false; // never aligned in this test: the clock never settles
        rig.connect();
        rig.run(10_000);
        assertTrue(rig.report(), !rig.status().aligned);
        assertTrue("rises happened: " + rig.sink.counters().mixerGlitches, rig.sink.counters().mixerGlitches > 5);
        assertEquals(rig.report(), 0, rig.status().writeAheadRaises);
        assertEquals(0, rig.status().underrunRisesThin);
    }
}
