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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;

public class SnapcastClientRuntimeTest {

    @After
    public void reset() {
        SnapcastClientRuntime.phase(SnapcastClientRuntime.Phase.IDLE, SnapcastClientRuntime.Ending.NONE, 0);
    }

    @Test
    public void anEndingThatIsSetWhileTheEngineThreadPublishesIsNeverLost() throws Exception {
        for (int round = 0; round < 300; round++) {
            SnapcastClientRuntime.phase(SnapcastClientRuntime.Phase.RUNNING, SnapcastClientRuntime.Ending.NONE, 1);
            final CountDownLatch go = new CountDownLatch(1);
            final EngineStatus status = new EngineStatus();
            Thread engineThread = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    return;
                }
                for (int i = 0; i < 2000; i++) {
                    SnapcastClientRuntime.engine(status);
                }
            });
            engineThread.start();
            go.countDown();
            for (int i = 0; i < 50; i++) {
                Thread.yield();
            }
            SnapcastClientRuntime.phase(SnapcastClientRuntime.Phase.STOPPED, SnapcastClientRuntime.Ending.USER, 1);
            engineThread.join();
            assertEquals("round " + round, SnapcastClientRuntime.Phase.STOPPED, SnapcastClientRuntime.get().phase);
            assertFalse("round " + round, SnapcastClientRuntime.isActive());
        }
    }

    @Test
    public void theViewKeepsTheEngineStatusAcrossAPhaseChangeExceptAtANewStart() {
        EngineStatus status = new EngineStatus();
        SnapcastClientRuntime.phase(SnapcastClientRuntime.Phase.RUNNING, SnapcastClientRuntime.Ending.NONE, 5);
        SnapcastClientRuntime.engine(status);
        SnapcastClientRuntime.phase(SnapcastClientRuntime.Phase.FAILED, SnapcastClientRuntime.Ending.CONNECTION_GAVE_UP, 5);
        assertSame(status, SnapcastClientRuntime.get().engine);
        assertTrue(!SnapcastClientRuntime.isActive());
        SnapcastClientRuntime.phase(SnapcastClientRuntime.Phase.CONNECTING, SnapcastClientRuntime.Ending.NONE, 9);
        assertEquals(null, SnapcastClientRuntime.get().engine);
        assertTrue(SnapcastClientRuntime.isActive());
    }

    @Test
    public void aLateConnectedOrLostFromAnotherThreadDoesNotRaiseAFinishedSessionAgain() {
        SnapcastClientRuntime.phase(SnapcastClientRuntime.Phase.RUNNING, SnapcastClientRuntime.Ending.NONE, 1);
        SnapcastClientRuntime.phaseWhileActive(SnapcastClientRuntime.Phase.RECONNECTING, SnapcastClientRuntime.Ending.NONE, 1);
        assertEquals(SnapcastClientRuntime.Phase.RECONNECTING, SnapcastClientRuntime.get().phase);
        SnapcastClientRuntime.phase(SnapcastClientRuntime.Phase.FAILED, SnapcastClientRuntime.Ending.CONNECTION_GAVE_UP, 1);
        SnapcastClientRuntime.phaseWhileActive(SnapcastClientRuntime.Phase.RUNNING, SnapcastClientRuntime.Ending.NONE, 1);
        assertEquals(SnapcastClientRuntime.Phase.FAILED, SnapcastClientRuntime.get().phase);
        assertEquals(SnapcastClientRuntime.Ending.CONNECTION_GAVE_UP, SnapcastClientRuntime.get().ending);
        assertFalse(SnapcastClientRuntime.isActive());
    }
}
