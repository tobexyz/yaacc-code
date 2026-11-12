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

public class SnapcastClientStatusTextTest {

    @After
    public void reset() {
        SnapcastClientRuntime.phase(SnapcastClientRuntime.Phase.IDLE, SnapcastClientRuntime.Ending.NONE, 0);
    }

    @Test
    public void theLostChunksOfTheHostQueueTheHolesAndTheRebasesAreInTheNumbersTheOwnerReports() {
        EngineStatus status = new EngineStatus();
        status.codec = "flac";
        status.hostQueueDrops = 17;
        status.streamHolesRecovered = 3;
        status.timelineRebases = 2;
        SnapcastClientRuntime.phase(SnapcastClientRuntime.Phase.RUNNING, SnapcastClientRuntime.Ending.NONE, 0);
        SnapcastClientRuntime.engine(status);
        String text = SnapcastClientStatusText.of(SnapcastClientRuntime.get(), 0);
        assertTrue(text, text.contains("holes re-anchored (flac) 3"));
        assertTrue(text, text.contains("timeline rebases 2"));
        assertTrue(text, text.contains("chunks lost in the host queue 17"));
    }

    @Test
    public void theFlushesAreCountedByCauseWithTheDriftThatTriggeredTheLastHardResync() {
        EngineStatus status = new EngineStatus();
        status.flushesHardResync = 7;
        status.flushesUnderrun = 2;
        status.flushesWriterBehind = 3;
        status.flushesHeader = 1;
        status.flushesConnectionLost = 4;
        status.lastHardResyncDriftMicros = 395_000;
        status.positionReportsAccepted = 120;
        status.positionReportsRejected = 9;
        SnapcastClientRuntime.phase(SnapcastClientRuntime.Phase.RUNNING, SnapcastClientRuntime.Ending.NONE, 0);
        SnapcastClientRuntime.engine(status);
        String text = SnapcastClientStatusText.of(SnapcastClientRuntime.get(), 0);
        assertTrue(text, text.contains("hard resync 7 (last drift +395"));
        assertTrue(text, text.contains("underrun 2, writer behind 3, header 1, connection lost 4"));
        assertTrue(text, text.contains("position reports: taken 120, refused 9"));
    }

    @Test
    public void theUnderrunCounterIsShownWithWhatThePositionSaidAndAStaleDriftIsLabelled() {
        EngineStatus status = new EngineStatus();
        status.underruns = 1195;
        status.underrunsSpurious = 40;
        status.underrunsCorroborated = 0;
        status.underrunCounterUnreliable = true;
        status.flushesPositionStall = 2;
        status.driftMicros = 21_424;
        status.aligned = false;
        SnapcastClientRuntime.phase(SnapcastClientRuntime.Phase.RUNNING, SnapcastClientRuntime.Ending.NONE, 0);
        SnapcastClientRuntime.engine(status);
        String text = SnapcastClientStatusText.of(SnapcastClientRuntime.get(), 0);
        assertTrue(text, text.contains("1195 rises, confirmed by the position 0, spurious 40 (judged unreliable"));
        assertTrue(text, text.contains("position stall 2"));
        assertTrue(text, text.contains("last value, not current: no audio"));
        status.aligned = true;
        SnapcastClientRuntime.engine(status);
        assertFalse(SnapcastClientStatusText.of(SnapcastClientRuntime.get(), 0).contains("not current"));
    }

    @Test
    public void aFailureIsExplainedInWordsAndNeverWithTextFromTheServer() {
        EngineStatus status = new EngineStatus();
        status.failure = EngineFailure.DECODER_UNAVAILABLE;
        SnapcastClientRuntime.phase(SnapcastClientRuntime.Phase.FAILED, SnapcastClientRuntime.Ending.ENGINE_FAILED, 0);
        SnapcastClientRuntime.engine(status);
        String text = SnapcastClientStatusText.of(SnapcastClientRuntime.get(), 0);
        assertTrue(text, text.contains("no decoder"));
        assertFalse(text.contains("null"));
    }

    @Test
    public void theTwoNewEndingsAreExplained() {
        assertTrue(SnapcastClientStatusText.endingText(SnapcastClientRuntime.Ending.NO_AUDIO).contains("15 minutes"));
        assertTrue(SnapcastClientStatusText.endingText(SnapcastClientRuntime.Ending.DISABLED).contains("switched off"));
    }
}
