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
    private java.util.List<de.yaacc.sendspin.discovery.SendspinDirectory.Entry> found = java.util.Collections.emptyList();

    private final Connector failing = (endpoint, random) -> {
        opened.incrementAndGet();
        throw new IOException("no server");
    };

    private SendspinControllers make() {
        controllers = new SendspinControllers(new SendspinStoreTest.MapStore(), () -> Identity.generate(new SecureRandom()), () -> true,
                () -> found, failing, new HubClock() {
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

    private static de.yaacc.sendspin.discovery.SendspinDirectory.Entry entry(String host, int port, String name) {
        return new de.yaacc.sendspin.discovery.SendspinDirectory.Entry(new Endpoint(host, port, "/sendspin"), name);
    }

    @Test
    public void serversTheNetworkAnnouncedAreListedWithTheAddedOnesAndNamedByTheirAnnouncement() {
        found = java.util.Arrays.asList(entry("10.0.0.5", 8927, "Music Assistant"), entry("10.0.0.6", 9000, ""));
        make();
        controllers.servers().add("music.local");
        controllers.servers().add("10.0.0.5:8927"); // announced and added: listed once
        java.util.List<Endpoint> listed = controllers.listed();
        assertEquals(3, listed.size());
        assertTrue(listed.contains(SendspinAddress.parseServer("music.local")));
        assertTrue(listed.contains(new Endpoint("10.0.0.5", 8927, "/sendspin")));
        assertTrue(listed.contains(new Endpoint("10.0.0.6", 9000, "/sendspin")));
        assertEquals("Music Assistant", controllers.foundName(new Endpoint("10.0.0.5", 8927, "/sendspin")));
        assertNull(controllers.foundName(SendspinAddress.parseServer("music.local")));
    }

    @Test
    public void theNumberOfListedServersIsLimited() {
        java.util.List<de.yaacc.sendspin.discovery.SendspinDirectory.Entry> many = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add(entry("10.0.1." + (i + 1), 8927, "s" + i));
        }
        found = many;
        make();
        assertEquals(SendspinControllers.MAX_LISTED, controllers.listed().size());
    }

    @Test
    public void aFoundServerCanBeForgottenOnlyByTheSearchNotRemoved() {
        found = java.util.Collections.singletonList(entry("10.0.0.5", 8927, "Music Assistant"));
        make();
        controllers.remove(new Endpoint("10.0.0.5", 8927, "/sendspin")); // nothing added: only the session goes
        assertEquals("it is still announced", 1, controllers.listed().size());
    }
}
