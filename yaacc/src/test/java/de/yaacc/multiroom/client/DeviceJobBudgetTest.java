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
 * "The breaks are regular": which periodic job on the engine thread, and how slow, leaves a hole in the output? The jobs
 * that run about every 0.25 / 0.5 / 1 / 2 s are emulated by blocking the engine thread (the status publication every
 * 250 ms, the position poll, the trace line per second, the notification and the time sync events). The queue in front of
 * the output is the write-ahead (120 ms by default): a job that keeps the thread away longer than that, once per period,
 * is a regular break of the length of the job minus the write-ahead.
 */
public class DeviceJobBudgetTest {

    private static DeviceModel phone() {
        DeviceModel m = new DeviceModel();
        m.startThresholdFrames = 1920;
        m.thresholdSettable = true;
        m.timestampPeriodMicros = 20_000;
        m.outputLatencyMicros = 35_000;
        return m;
    }

    private static int realUnderruns(int aheadMs, long periodMs, long costMs) {
        DeviceRig rig = new DeviceRig(phone(), new ClientEngineConfig().maxAheadMs(aheadMs).adaptiveWriteAhead(false));
        rig.connect();
        rig.run(6000);
        int before = rig.sink.counters().realUnderruns;
        rig.addJob(periodMs, costMs);
        rig.run(20_000);
        return rig.sink.counters().realUnderruns - before;
    }

    @Test
    public void aJobUnderTheWriteAheadNeverLeavesAHole() {
        for (long period : new long[]{250, 500, 1000, 2000}) {
            assertEquals("period " + period + " ms, job 60 ms", 0, realUnderruns(120, period, 60));
        }
    }

    @Test
    public void aJobLongerThanTheWriteAheadLeavesAHoleEveryPeriodAndALongerWriteAheadAbsorbsIt() {
        for (long period : new long[]{250, 500, 1000, 2000}) {
            int shortAhead = realUnderruns(120, period, 200);
            int longAhead = realUnderruns(300, period, 200);
            assertTrue("period " + period + " ms: a 200 ms job with 120 ms write-ahead: " + shortAhead + " holes in 20 s",
                    shortAhead >= 20_000 / (period + 400) - 1 && shortAhead > 0);
            assertEquals("period " + period + " ms: the same job with 300 ms write-ahead", 0, longAhead);
        }
    }

    /** A slow getTimestamp (every poll takes 150 ms) is seen in the status and the trace, and breaks the output. */
    @Test
    public void aSlowPositionPollIsMeasured() {
        DeviceModel m = phone();
        m.timestampCostMicros = 150_000;
        DeviceRig rig = new DeviceRig(m);
        rig.connect();
        rig.run(15_000);
        assertTrue(rig.report(), rig.status().maxPositionPollMicros >= 150_000);
        assertTrue(rig.engine.trace().text(), rig.engine.trace().text().contains("position poll us=150000"));
        assertTrue(rig.report(), rig.sink.counters().realUnderruns > 0);
    }

    /** A slow write call is the longest write in the status. */
    @Test
    public void aSlowWriteIsMeasured() {
        DeviceModel m = phone();
        m.writeCostMicros = 30_000;
        DeviceRig rig = new DeviceRig(m);
        rig.connect();
        rig.run(8000);
        assertTrue(rig.status().maxWriteMicros >= 30_000);
        assertTrue(rig.status().maxTickMicros >= 30_000);
    }
}
