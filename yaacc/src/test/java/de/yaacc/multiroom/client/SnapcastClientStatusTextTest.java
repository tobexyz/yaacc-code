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
    public void aFailureIsExplainedInWordsAndNeverWithTextFromTheServer() {
        EngineStatus status = new EngineStatus();
        status.failure = EngineFailure.DECODER_UNAVAILABLE;
        SnapcastClientRuntime.phase(SnapcastClientRuntime.Phase.FAILED, SnapcastClientRuntime.Ending.ENGINE_FAILED, 0);
        SnapcastClientRuntime.engine(status);
        String text = SnapcastClientStatusText.of(SnapcastClientRuntime.get(), 0);
        assertTrue(text, text.contains("no decoder"));
        assertFalse(text.contains("null"));
    }
}
