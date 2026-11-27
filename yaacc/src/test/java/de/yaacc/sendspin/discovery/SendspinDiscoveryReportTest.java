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
package de.yaacc.sendspin.discovery;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import de.yaacc.sendspin.hub.Endpoint;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The text a user can read and copy to tell "nothing announces itself" from "the app does not hear it" (task 11.12). */
public class SendspinDiscoveryReportTest {

    private static final SendspinDiscoveryReport.Facts FACTS = new SendspinDiscoveryReport.Facts("Pixel 7, Android 15", true, true, true,
            Boolean.TRUE, SendspinDiscoveryReport.LockState.HELD, true);

    @Test
    public void theReportStatesTheFactsOfThePhoneAndWhatWasBrowsed() {
        SendspinDiscoveryLog players = new SendspinDiscoveryLog();
        players.scanStarted();
        players.record(1, SendspinDiscoveryLog.Kind.BROWSE_STARTED, "", 0, "");
        String text = SendspinDiscoveryReport.build(FACTS, Collections.<SendspinDirectory.Entry>emptyList(),
                Collections.<SendspinDirectory.Entry>emptyList(), players.snapshot(), new SendspinDiscoveryLog().snapshot());
        assertTrue(text.contains("Sendspin discovery report"));
        assertTrue(text.contains("Pixel 7, Android 15"));
        assertTrue(text.contains("_sendspin._tcp"));
        assertTrue(text.contains("_sendspin-server._tcp"));
        assertTrue(text.contains("Wi-Fi: connected"));
        assertTrue(text.contains("multicast lock: held"));
        assertTrue("a scan ran and nothing was found: the reading says what to check", text.contains("Nothing was announced"));
    }

    @Test
    public void theEntriesAndTheirOriginAreListedAndTheNothingHintGoesWhenSomethingWasFound() {
        SendspinDiscoveryLog players = new SendspinDiscoveryLog();
        players.scanStarted();
        players.record(1, SendspinDiscoveryLog.Kind.FOUND, "Nokia", 0, "");
        players.record(1, SendspinDiscoveryLog.Kind.RESOLVED, "Nokia", 0, "192.168.0.221:8928");
        List<SendspinDirectory.Entry> found = Arrays.asList(new SendspinDirectory.Entry(new Endpoint("192.168.0.221", 8928, "/sendspin"), "Nokia", "ABC"));
        String text = SendspinDiscoveryReport.build(FACTS, found, Collections.<SendspinDirectory.Entry>emptyList(), players.snapshot(),
                new SendspinDiscoveryLog().snapshot());
        assertTrue(text.contains("Nokia"));
        assertTrue(text.contains("192.168.0.221:8928"));
        assertFalse(text.contains("Nothing was announced"));
        assertTrue(text.contains("found 1"));
        assertTrue(text.contains("resolved 1"));
    }

    @Test
    public void aFailureCodeIsDescribedAndTheSwitchOffIsSaid() {
        SendspinDiscoveryLog players = new SendspinDiscoveryLog();
        players.scanStarted();
        players.record(1, SendspinDiscoveryLog.Kind.BROWSE_START_FAILED, "", 3, "");
        SendspinDiscoveryReport.Facts off = new SendspinDiscoveryReport.Facts("x", false, false, false, null,
                SendspinDiscoveryReport.LockState.NOT_HELD, false);
        String text = SendspinDiscoveryReport.build(off, Collections.<SendspinDirectory.Entry>emptyList(),
                Collections.<SendspinDirectory.Entry>emptyList(), players.snapshot(), new SendspinDiscoveryLog().snapshot());
        assertTrue(text.contains("search for players (the switch): off"));
        assertTrue(text.contains("Wi-Fi: unknown"));
        assertTrue("the failure code is in the event list", text.contains("browse start failed"));
        assertFalse("no hint when the switches are off", text.contains("Nothing was announced"));
    }

    @Test
    public void textFromTheNetworkIsCleanedAndCut() {
        SendspinDiscoveryLog log = new SendspinDiscoveryLog();
        log.scanStarted();
        StringBuilder hostile = new StringBuilder("evil\n\u0000name");
        for (int i = 0; i < 200; i++) {
            hostile.append('x');
        }
        log.record(1, SendspinDiscoveryLog.Kind.FOUND, hostile.toString(), 0, "");
        String name = log.snapshot().getEvents().get(0).getName();
        assertTrue(name.length() <= 64);
        assertFalse(name.contains("\n"));
        assertFalse(name.contains("\u0000"));
    }

    @Test
    public void theLogKeepsTheLastHundredEventsAndAllTheCounts() {
        SendspinDiscoveryLog log = new SendspinDiscoveryLog();
        log.scanStarted();
        for (int i = 0; i < 150; i++) {
            log.record(1, SendspinDiscoveryLog.Kind.FOUND, "p" + i, 0, "");
        }
        SendspinDiscoveryLog.Snapshot snapshot = log.snapshot();
        org.junit.Assert.assertEquals(100, snapshot.getEvents().size());
        org.junit.Assert.assertEquals(150, snapshot.count(SendspinDiscoveryLog.Kind.FOUND));
        org.junit.Assert.assertEquals("p50", snapshot.getEvents().get(0).getName());
    }
}
