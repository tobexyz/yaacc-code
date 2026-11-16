/*
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
package de.yaacc.browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Task 12.15: the renderer and the proxy checkbox only count as running while the master switch of the local UPnP service
 * ("UPnP Server", settings_local_server_chkbx) is on. With the master switch off the service is stopped and the content
 * handlers refuse, so nothing is served although the sub-checkboxes stay ticked.
 */
public class IdleFactsTest {

    private static AutoCloseRule.Keep keep(boolean master, boolean receiver, boolean proxy) {
        return AutoCloseRule.reasonToStay(IdleFacts.of(master, receiver, proxy, false, false, false, false));
    }

    @Test
    public void masterOffWithTheRendererTickedMayClose() {
        // the owner's log: 'staying open: RENDERER' with the master switch off
        assertEquals(AutoCloseRule.Keep.NONE, keep(false, true, false));
        assertTrue(AutoCloseRule.mayCloseNow(IdleFacts.of(false, true, false, false, false, false, false)));
    }

    @Test
    public void masterOffWithTheProxyTickedMayClose() {
        assertEquals(AutoCloseRule.Keep.NONE, keep(false, false, true));
    }

    @Test
    public void masterOffWithEverySubSwitchTickedMayClose() {
        assertEquals(AutoCloseRule.Keep.NONE, keep(false, true, true));
    }

    @Test
    public void masterOnOnlyKeepsTheAppAsTheLocalServer() {
        assertEquals(AutoCloseRule.Keep.LOCAL_SERVER, keep(true, false, false));
    }

    @Test
    public void masterOnWithTheRendererKeepsTheApp() {
        // the master switch is the first reason in the rule's order, so the log names it
        assertEquals(AutoCloseRule.Keep.LOCAL_SERVER, keep(true, true, false));
    }

    @Test
    public void masterOnWithTheProxyKeepsTheApp() {
        assertEquals(AutoCloseRule.Keep.LOCAL_SERVER, keep(true, false, true));
    }

    @Test
    public void aPlayerKeepsTheAppWhateverTheMasterSwitchSays() {
        assertEquals(AutoCloseRule.Keep.PLAYER,
                AutoCloseRule.reasonToStay(IdleFacts.of(false, false, false, true, false, false, false)));
    }

    @Test
    public void aSnapcastClientSessionKeepsTheAppWithTheMasterSwitchOff() {
        assertEquals(AutoCloseRule.Keep.SNAPCAST_CLIENT,
                AutoCloseRule.reasonToStay(IdleFacts.of(false, true, true, false, true, false, false)));
    }

    @Test
    public void aSnapcastFeedKeepsTheAppWithTheMasterSwitchOff() {
        assertEquals(AutoCloseRule.Keep.SNAPCAST_FEED,
                AutoCloseRule.reasonToStay(IdleFacts.of(false, true, true, false, false, true, false)));
    }

    @Test
    public void autostartTickedStillKeepsTheAppWithTheMasterSwitchOff() {
        assertEquals(AutoCloseRule.Keep.AUTOSTART,
                AutoCloseRule.reasonToStay(IdleFacts.of(false, true, true, false, false, false, true)));
    }

    @Test
    public void autostartComesAfterTheRunningThings() {
        assertEquals(AutoCloseRule.Keep.LOCAL_SERVER,
                AutoCloseRule.reasonToStay(IdleFacts.of(true, false, false, false, false, false, true)));
        assertEquals(AutoCloseRule.Keep.SNAPCAST_CLIENT,
                AutoCloseRule.reasonToStay(IdleFacts.of(false, false, false, false, true, false, true)));
    }

    @Test
    public void nothingTickedMayClose() {
        assertEquals(AutoCloseRule.Keep.NONE,
                AutoCloseRule.reasonToStay(IdleFacts.of(false, false, false, false, false, false, false)));
    }
}
