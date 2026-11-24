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
package de.yaacc.sendspin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import de.yaacc.sendspin.client.ControllerSession;
import de.yaacc.sendspin.hub.Connector;
import de.yaacc.sendspin.hub.Endpoint;
import de.yaacc.sendspin.hub.HubClock;
import de.yaacc.sendspin.secure.Identity;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicInteger;

/** The sessions of the controller: one for each server, made again only when the guidance of the protocol allows. */
public class SendspinControllersTest {

    private final AtomicInteger opened = new AtomicInteger();
    private final long[] now = {1_000_000_000L};
    private final AtomicInteger changes = new AtomicInteger();
    private SendspinControllers controllers;

    private final Connector failing = (endpoint, random) -> {
        opened.incrementAndGet();
        throw new IOException("no server");
    };

    private SendspinControllers make() {
        controllers = new SendspinControllers(new SendspinStoreTest.MapStore(), () -> Identity.generate(new SecureRandom()), () -> true,
                failing, new HubClock() {
                    @Override
                    public long nowMicros() {
                        return now[0];
                    }

                    @Override
                    public void sleepMicros(long micros) {
                        // no waiting in a test
                    }
                }, new SecureRandom(), s -> changes.incrementAndGet());
        return controllers;
    }

    @After
    public void tearDown() {
        if (controllers != null) {
            controllers.closeAll();
        }
    }

    private static void awaitEnded(ControllerSession session) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (session.phase() != ControllerSession.Phase.ENDED && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(ControllerSession.Phase.ENDED, session.phase());
    }

    @Test
    public void aServerIsAddedWithTheServerPortByDefaultAndListed() {
        make();
        assertEquals(SendspinPlayers.AddResult.ADDED, controllers.servers().add("music.local"));
        assertEquals(SendspinPlayers.AddResult.ALREADY_THERE, controllers.servers().add("music.local:8927"));
        assertEquals(8927, controllers.servers().list().get(0).port());
        assertEquals(SendspinPlayers.AddResult.NOT_AN_ADDRESS, controllers.servers().add("http://x"));
        for (int i = 1; i < SendspinSettings.MAX_SERVERS; i++) {
            assertEquals(SendspinPlayers.AddResult.ADDED, controllers.servers().add("s" + i + ".local"));
        }
        assertEquals(SendspinPlayers.AddResult.TOO_MANY, controllers.servers().add("one-more.local"));
    }

    @Test
    public void oneSessionIsMadeForEachServerAndTheSameOneIsReturnedWhileItLives() throws Exception {
        make();
        Endpoint endpoint = SendspinAddress.parseServer("music.local");
        assertNull(controllers.session(endpoint));
        ControllerSession first = controllers.acquire(endpoint, false);
        assertNotNull(first);
        awaitEnded(first); // the connector fails
        assertEquals(1, opened.get());
        assertSame("a failed session is not made again at once", first, controllers.acquire(endpoint, false));
        assertEquals(1, opened.get());
    }

    @Test
    public void aFailedSessionIsMadeAgainAfterThePauseOrWhenTheUserAsks() throws Exception {
        make();
        Endpoint endpoint = SendspinAddress.parseServer("music.local");
        ControllerSession first = controllers.acquire(endpoint, false);
        awaitEnded(first);
        now[0] += SendspinControllers.RETRY_PAUSE_MILLIS * 1000L + 1;
        ControllerSession second = controllers.acquire(endpoint, false);
        assertTrue("a new session after the pause", second != first);
        awaitEnded(second);
        ControllerSession third = controllers.acquire(endpoint, true);
        assertTrue("a user action does not wait", third != second);
        awaitEnded(third);
        assertEquals(3, opened.get());
    }

    @Test
    public void guidanceThatSaysDoNotComeBackIsKeptUntilTheUserActs() {
        assertTrue(SendspinControllers.mayRetry(ControllerSession.EndReason.FAILED));
        assertTrue(SendspinControllers.mayRetry(ControllerSession.EndReason.SERVER_CLOSED));
        assertEquals(false, SendspinControllers.mayRetry(ControllerSession.EndReason.PAIRING_REQUIRED));
        assertEquals(false, SendspinControllers.mayRetry(ControllerSession.EndReason.UNAUTHORIZED));
        assertEquals(false, SendspinControllers.mayRetry(ControllerSession.EndReason.UNPAIRED));
        assertEquals(false, SendspinControllers.mayRetry(ControllerSession.EndReason.CLOSED_BY_APP));
        assertEquals(false, SendspinControllers.mayRetry(ControllerSession.EndReason.PROTOCOL_ERROR));
    }

    @Test
    public void removingAServerEndsItsSessionAndForgetsTheAddress() throws Exception {
        make();
        controllers.servers().add("music.local");
        Endpoint endpoint = controllers.servers().list().get(0);
        controllers.acquire(endpoint, false);
        controllers.remove(endpoint);
        assertNull(controllers.session(endpoint));
        assertTrue(controllers.servers().list().isEmpty());
    }

    @Test
    public void everyChangeOfASessionIsPassedOn() throws Exception {
        make();
        ControllerSession session = controllers.acquire(SendspinAddress.parseServer("music.local"), false);
        awaitEnded(session);
        assertTrue("the phases were reported: " + changes.get(), changes.get() >= 2);
    }
}
