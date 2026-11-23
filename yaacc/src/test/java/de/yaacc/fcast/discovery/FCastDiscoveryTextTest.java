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
package de.yaacc.fcast.discovery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.net.InetAddress;
import java.util.Collections;
import java.util.List;

/** Task 3.3 (the pure parts): the setting, the discovery log and the report text. */
public class FCastDiscoveryTextTest {

    // ---- setting

    @Test
    public void discoveryIsOnByDefaultAndTheStoredChoiceWins() {
        assertTrue(FCastSettings.isDiscoveryEnabled((key, def) -> def));
        assertFalse(FCastSettings.isDiscoveryEnabled((key, def) -> false));
        assertTrue(FCastSettings.isDiscoveryEnabled((key, def) -> true));
        assertEquals(FCastSettings.KEY_DISCOVERY, "settings_fcast_discovery");
    }

    @Test
    public void aDamagedValueOrNoSourceMeansOff() {
        assertFalse(FCastSettings.isDiscoveryEnabled(null));
        assertFalse(FCastSettings.isDiscoveryEnabled((key, def) -> {
            throw new ClassCastException("not a boolean");
        }));
    }

    @Test
    public void onlyTheDiscoveryKeyIsTheSwitch() {
        assertTrue(FCastSettings.isDiscoveryKey("settings_fcast_discovery"));
        assertFalse(FCastSettings.isDiscoveryKey("settings_snapcast_discovery"));
        assertFalse(FCastSettings.isDiscoveryKey(null));
    }

    // ---- log

    @Test
    public void theLogKeepsTheLastHundredEventsAndCountsAll() {
        FCastDiscoveryLog log = new FCastDiscoveryLog();
        log.scanStarted();
        for (int i = 0; i < 250; i++) {
            log.record(log.scanId(), FCastDiscoveryLog.Kind.FOUND, "n" + i, 0, "");
        }
        FCastDiscoveryLog.Snapshot snapshot = log.snapshot();
        assertEquals(100, snapshot.getEvents().size());
        assertEquals("oldest first", "n150", snapshot.getEvents().get(0).getName());
        assertEquals("n249", snapshot.getEvents().get(99).getName());
        assertEquals(250, snapshot.count(FCastDiscoveryLog.Kind.FOUND));
        assertEquals(0, snapshot.count(FCastDiscoveryLog.Kind.LOST));
        assertEquals(1, snapshot.getScans());
    }

    @Test
    public void namesFromTheNetworkAreCleanedInTheLog() {
        FCastDiscoveryLog log = new FCastDiscoveryLog();
        char[] chars = new char[500];
        java.util.Arrays.fill(chars, 'x');
        log.record(1, FCastDiscoveryLog.Kind.FOUND, "TV\u0000\n‮", 0, "");
        log.record(1, FCastDiscoveryLog.Kind.FOUND, new String(chars), 0, new String(chars));
        log.record(1, FCastDiscoveryLog.Kind.LOST, null, 0, null);
        List<FCastDiscoveryLog.Event> events = log.snapshot().getEvents();
        assertEquals("TV", events.get(0).getName());
        assertEquals(64, events.get(1).getName().length());
        assertEquals(80, events.get(1).getDetail().length());
        assertEquals("", events.get(2).getName());
    }

    @Test
    public void anEmptyLogHasASnapshot() {
        FCastDiscoveryLog.Snapshot snapshot = new FCastDiscoveryLog().snapshot();
        assertEquals(0, snapshot.getEvents().size());
        assertEquals(0, snapshot.count(FCastDiscoveryLog.Kind.RESOLVED));
    }

    // ---- report

    private static List<FCastDirectory.Entry> oneReceiver() throws Exception {
        FCastDirectory directory = new FCastDirectory(() -> 0L, 30_000);
        directory.serviceFound("_fcast._tcp|FCast-pc", "FCast-pc", InetAddress.getByAddress(new byte[]{(byte) 192, (byte) 168, 0, (byte) 239}), 46899,
                FCastServiceTxt.parse(Collections.singletonMap("v", "4".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        return directory.snapshot();
    }

    @Test
    public void theReportNamesTheStateTheReceiversAndTheEvents() throws Exception {
        FCastDiscoveryLog log = new FCastDiscoveryLog();
        log.scanStarted();
        log.record(1, FCastDiscoveryLog.Kind.BROWSE_REQUESTED, "_fcast._tcp", 0, "");
        log.record(1, FCastDiscoveryLog.Kind.FOUND, "FCast-pc", 0, "");
        log.record(1, FCastDiscoveryLog.Kind.RESOLVE_FAILED, "FCast-pc", 3, "");
        log.record(1, FCastDiscoveryLog.Kind.RESOLVED, "FCast-pc", 0, "192.168.0.239:46899");
        String report = FCastDiscoveryReport.build(new FCastDiscoveryReport.Facts(true, true, true, FCastDiscoveryReport.LockState.HELD, "Pixel 7"),
                oneReceiver(), log.snapshot());
        assertTrue(report, report.contains("device: Pixel 7"));
        assertTrue(report.contains("search for receivers (the switch): on"));
        assertTrue(report.contains("searching now: yes"));
        assertTrue(report.contains("Wi-Fi: connected"));
        assertTrue(report.contains("multicast lock: held"));
        assertTrue(report.contains("service type browsed: _fcast._tcp"));
        assertTrue(report.contains("FCast-pc  192.168.0.239:46899  [found by the network]  v4"));
        assertTrue(report.contains("FAILURE_ALREADY_ACTIVE (3)"));
        assertTrue(report.contains("resolved \"FCast-pc\" 192.168.0.239:46899"));
        assertFalse("something was found: no hint about an empty network", report.contains("Nothing was announced"));
    }

    @Test
    public void theReportExplainsAnEmptyScan() {
        FCastDiscoveryLog log = new FCastDiscoveryLog();
        log.scanStarted();
        log.record(1, FCastDiscoveryLog.Kind.BROWSE_STARTED, "_fcast._tcp", 0, "");
        String report = FCastDiscoveryReport.build(new FCastDiscoveryReport.Facts(true, false, false, FCastDiscoveryReport.LockState.FAILED, null),
                Collections.<FCastDirectory.Entry>emptyList(), log.snapshot());
        assertTrue(report, report.contains("Nothing was announced"));
        assertTrue(report.contains("UDP 5353"));
        assertTrue(report.contains("Wi-Fi: not connected"));
        assertTrue(report.contains("multicast lock: failed"));
        assertTrue(report.contains("device: ?"));
        assertTrue(report.contains("receivers known: 0"));
    }

    @Test
    public void aSwitchedOffDiscoveryGetsNoHintAboutTheNetwork() {
        String report = FCastDiscoveryReport.build(new FCastDiscoveryReport.Facts(false, false, null, FCastDiscoveryReport.LockState.NOT_HELD, "x"),
                Collections.<FCastDirectory.Entry>emptyList(), new FCastDiscoveryLog().snapshot());
        assertTrue(report.contains("search for receivers (the switch): off"));
        assertTrue(report.contains("Wi-Fi: unknown"));
        assertFalse(report.contains("Nothing was announced"));
    }
}
