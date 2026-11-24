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
package de.yaacc.sendspin.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import de.yaacc.sendspin.hub.Connector;
import de.yaacc.sendspin.hub.Endpoint;
import de.yaacc.sendspin.hub.HubClock;
import de.yaacc.sendspin.secure.Identity;

import org.junit.Assume;
import org.junit.Test;

import java.security.SecureRandom;

/**
 * The controller session against the reference server of {@code tools/sendspin-rig} (opt-in): {@code -Dsendspin.rig.server=host:port}
 * of a server started with {@code run-local.sh server}. Skipped without the property.
 */
public class ControllerRigTest {

    private static void await(ControllerSession session, ControllerSession.Phase phase, long millis) throws Exception {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        while (session.phase() != phase && session.phase() != ControllerSession.Phase.ENDED && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertEquals("end reason " + session.endReason(), phase, session.phase());
    }

    @Test
    public void theReferenceServerActivatesTheControllerAndTheCommandsReachIt() throws Exception {
        String rig = System.getProperty("sendspin.rig.server");
        Assume.assumeTrue("no -Dsendspin.rig.server=host:port", rig != null && !rig.isEmpty());
        int colon = rig.lastIndexOf(':');
        Endpoint endpoint = new Endpoint(rig.substring(0, colon), Integer.parseInt(rig.substring(colon + 1)), "/sendspin");
        ControllerSession session = new ControllerSession(endpoint, Identity.generate(new SecureRandom()), true, new Connector.Sockets(),
                HubClock.SYSTEM, new SecureRandom(), "YAACC rig test", null);
        session.start();
        try {
            await(session, ControllerSession.Phase.ACTIVE, 15_000);
            System.out.println("rig: server '" + session.serverName() + "', active");
            long deadline = System.nanoTime() + 10_000_000_000L;
            while ((session.controller() == null || session.group() == null) && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertNotNull("no controller state", session.controller());
            assertNotNull("no group update", session.group());
            System.out.println("rig: group '" + session.group().groupName + "' playing=" + session.group().playing + ", volume "
                    + session.controller().volume + ", commands " + session.controller().supportedCommands);
            if (session.metadata() != null) {
                System.out.println("rig: now playing '" + session.metadata().title + "' by '" + session.metadata().artist + "', position "
                        + session.positionMs() + " ms");
            }
            deadline = System.nanoTime() + 20_000_000_000L;
            while (!session.hasClockEstimate() && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertTrue("no clock estimate", session.hasClockEstimate());
            if (session.controller().supports("switch")) {
                assertTrue(session.switchGroup()); // the demo server of the rig offers only this one
                Thread.sleep(300);
                assertEquals("the session goes on", ControllerSession.Phase.ACTIVE, session.phase());
            }
            if (session.controller().supports("volume")) {
                assertTrue(session.setVolume(42));
                Thread.sleep(500);
                System.out.println("rig: volume after the command " + session.controller().volume);
            }
        } finally {
            session.close();
        }
    }
}
