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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

public class SnapcastClientTraceReportTest {

    @After
    public void reset() {
        SnapcastClientRuntime.phase(SnapcastClientRuntime.Phase.IDLE, SnapcastClientRuntime.Ending.NONE, 0);
    }

    @Test
    public void theSummaryIsOneLineWithDeviceStreamAndOutput() {
        EngineStatus status = new EngineStatus();
        status.codec = "pcm";
        status.sampleRate = 48000;
        status.channels = 2;
        status.sourceBits = 16;
        status.outputSummary = "track buffer 19200 frames, start threshold 960, underruns 5081, timestamp ever ok yes";
        status.flushes = 15;
        status.flushesHardResync = 15;
        String line = SnapcastClientTraceReport.summary(status, "Model X / API 34");
        assertFalse(line, line.contains("\n"));
        assertTrue(line, line.contains("Model X / API 34"));
        assertTrue(line, line.contains("codec pcm, 48000 Hz, 2 ch, 16 bit"));
        assertTrue(line, line.contains("track buffer 19200 frames"));
        assertTrue(line, line.contains("timestamp ever ok yes"));
        assertTrue(line, line.contains("flushes 15 (hard resync 15)"));
    }

    @Test
    public void withoutAnEngineTheReportSaysSo() {
        assertTrue(SnapcastClientTraceReport.summary(null, "d").contains("no engine yet"));
        SnapcastClientRuntime.engine(new EngineStatus()); // an engine that has no trace yet
        String report = SnapcastClientTraceReport.of(SnapcastClientRuntime.get(), "d", 0);
        assertTrue(report, report.contains("(empty)"));
    }

    @Test
    public void theReportHasSummaryStatusAndTrace() {
        EngineStatus status = new EngineStatus();
        status.trace = new EventTrace(0);
        status.trace.add(1_500_000L, TraceKind.PLAY, 2880, 960);
        SnapcastClientRuntime.phase(SnapcastClientRuntime.Phase.RUNNING, SnapcastClientRuntime.Ending.NONE, 0);
        SnapcastClientRuntime.engine(status);
        String report = SnapcastClientTraceReport.of(SnapcastClientRuntime.get(), "d", 0);
        assertTrue(report, report.startsWith("device d"));
        assertTrue(report, report.contains("flushes by cause"));
        assertTrue(report, report.contains("1500 ms  play()  written frames=2880  start threshold=960"));
    }
}
