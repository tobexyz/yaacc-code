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
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import de.yaacc.multiroom.client.SnapcastClientServerSwitch.Action;
import de.yaacc.multiroom.client.SnapcastClientServerSwitch.Wait;

/**
 * Task 12.10: what choosing another server does. Off: it only changes the next start. On and a session on another server: the session
 * ends and starts again on the new one (once, state stays on). The same server: nothing is restarted. Pure decisions, table tests.
 */
public class SnapcastClientServerSwitchTest {

    private static final String A = "192.168.1.5";
    private static final String B = "192.168.1.9";

    @After
    public void resetFlag() {
        SnapcastClientRuntime.serviceDestroyed();
    }

    @Test
    public void whileOffTheChoiceOnlyChangesTheNextStart() {
        assertEquals(Action.STORE_ONLY, SnapcastClientServerSwitch.decide(false, false, false, A, B));
        assertEquals("also with a session that is still ending", Action.STORE_ONLY, SnapcastClientServerSwitch.decide(false, true, false, A, B));
        assertEquals(Action.STORE_ONLY, SnapcastClientServerSwitch.decide(false, false, false, null, B));
    }

    @Test
    public void whileOnAndConnectedAnotherServerRestartsTheSession() {
        assertEquals(Action.RESTART, SnapcastClientServerSwitch.decide(true, true, false, A, B));
        assertEquals("a restart that waits is moved to the newer server", Action.RESTART, SnapcastClientServerSwitch.decide(true, false, true, A, B));
        assertEquals(Action.RESTART, SnapcastClientServerSwitch.decide(true, true, false, null, B));
    }

    @Test
    public void theSameServerIsNeverRestarted() {
        assertEquals(Action.STORE_ONLY, SnapcastClientServerSwitch.decide(true, true, false, A, A));
        assertEquals("the same target in another spelling", Action.STORE_ONLY, SnapcastClientServerSwitch.decide(true, true, false, A, A + ":1704"));
        assertEquals("a restart that waits for the same server is left alone", Action.STORE_ONLY, SnapcastClientServerSwitch.decide(true, false, true, A, A));
    }

    @Test
    public void switchedOnWithoutASessionStartsOnTheChosenServer() {
        // the client is on but not connected (it gave up, or its start was refused): the choice is a start
        assertEquals(Action.START, SnapcastClientServerSwitch.decide(true, false, false, A, B));
        assertEquals(Action.START, SnapcastClientServerSwitch.decide(true, false, false, A, A));
    }

    @Test
    public void theWaitForTheEndOfTheOldSessionIsBounded() {
        assertEquals(Wait.START, SnapcastClientServerSwitch.waitStep(true, 0, 8000));
        assertEquals(Wait.START, SnapcastClientServerSwitch.waitStep(true, 9000, 8000));
        assertEquals(Wait.WAIT, SnapcastClientServerSwitch.waitStep(false, 0, 8000));
        assertEquals(Wait.WAIT, SnapcastClientServerSwitch.waitStep(false, 7999, 8000));
        assertEquals(Wait.GIVE_UP, SnapcastClientServerSwitch.waitStep(false, 8000, 8000));
        assertEquals(Wait.GIVE_UP, SnapcastClientServerSwitch.waitStep(false, 60000, 8000));
    }

    @Test
    public void aSessionIsGoneOnlyWhenTheServiceInstanceIsGoneToo() {
        // the service answers a start with "busy" while it still tears the old session down: wait for its destroy
        SnapcastClientRuntime.serviceCreated();
        assertTrue(SnapcastClientRuntime.isServiceAlive());
        SnapcastClientRuntime.serviceDestroyed();
        assertFalse(SnapcastClientRuntime.isServiceAlive());
    }
}
