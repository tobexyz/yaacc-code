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
package de.yaacc.multiroom.discovery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;

import de.yaacc.multiroom.control.MonotonicClock;

/**
 * The text of the discovery report and its reading: the facts a user can copy so that "the server does not announce
 * itself" can be told from "the app does not hear it" (task 11.6). Plain JVM.
 */
public class SnapcastDiscoveryReportTest {

    private static final String CTRL = "_snapcast-ctrl._tcp";
    private static final String TCP = "_snapcast-tcp._tcp";
    private static final String STREAM = "_snapcast._tcp";

    private final AtomicLong now = new AtomicLong(0L);
    private final MonotonicClock clock = now::get;
    private SnapcastDiscoveryLog log;

    @Before
    public void setUp() {
        log = new SnapcastDiscoveryLog(clock);
    }

    private static SnapcastDiscoveryReport.Facts healthy() {
        return new SnapcastDiscoveryReport.Facts()
                .device("Pixel / API 34")
                .switchOn(true).scanRunning(true)
                .wifi(SnapcastDiscoveryReport.Tri.YES)
                .multicastPermission(SnapcastDiscoveryReport.Tri.YES)
                .multicastLock(SnapcastDiscoveryReport.LockState.HELD);
    }

    private void scanWithThreeTypesStarted() {
        log.scanStarted();
        for (String type : SnapcastServiceTypes.browsed()) {
            log.record(SnapcastDiscoveryLog.Kind.BROWSE_REQUESTED, type, "", 0, "");
            log.record(SnapcastDiscoveryLog.Kind.BROWSE_STARTED, type, "", 0, "");
        }
    }

    // ---- reading

    @Test
    public void aSwitchThatIsOffComesFirst() {
        scanWithThreeTypesStarted();
        assertEquals(SnapcastDiscoveryReport.Reading.SWITCH_OFF,
                SnapcastDiscoveryReport.reading(healthy().switchOn(false), log.snapshot()));
    }

    @Test
    public void noScanSinceTheAppStarted() {
        assertEquals(SnapcastDiscoveryReport.Reading.NOT_SCANNED,
                SnapcastDiscoveryReport.reading(healthy().scanRunning(false), log.snapshot()));
    }

    @Test
    public void noWifiIsNamedBeforeAnythingElse() {
        scanWithThreeTypesStarted();
        assertEquals(SnapcastDiscoveryReport.Reading.NO_WIFI,
                SnapcastDiscoveryReport.reading(healthy().wifi(SnapcastDiscoveryReport.Tri.NO), log.snapshot()));
    }

    @Test
    public void aMissingMulticastPermissionIsNamed() {
        scanWithThreeTypesStarted();
        assertEquals(SnapcastDiscoveryReport.Reading.NO_PERMISSION,
                SnapcastDiscoveryReport.reading(healthy().multicastPermission(SnapcastDiscoveryReport.Tri.NO), log.snapshot()));
    }

    @Test
    public void aBrowseThatTheSystemRefusedIsNamed() {
        scanWithThreeTypesStarted();
        log.record(SnapcastDiscoveryLog.Kind.BROWSE_START_FAILED, CTRL, "", 0, "");

        assertEquals(SnapcastDiscoveryReport.Reading.START_FAILED, SnapcastDiscoveryReport.reading(healthy(), log.snapshot()));
    }

    @Test
    public void aRunningScanThatHearsNothingMeansNoAnnouncementReachesThePhone() {
        scanWithThreeTypesStarted();
        assertEquals(SnapcastDiscoveryReport.Reading.NOTHING_HEARD, SnapcastDiscoveryReport.reading(healthy(), log.snapshot()));
    }

    @Test
    public void aServerThatAnnouncesOnlyItsStreamIsTold() {
        scanWithThreeTypesStarted();
        log.record(SnapcastDiscoveryLog.Kind.FOUND, STREAM, "Snapcast", 0, "");

        assertEquals(SnapcastDiscoveryReport.Reading.STREAM_ONLY, SnapcastDiscoveryReport.reading(healthy(), log.snapshot()));
    }

    @Test
    public void foundButNeverResolved() {
        scanWithThreeTypesStarted();
        log.record(SnapcastDiscoveryLog.Kind.FOUND, TCP, "Snapcast", 0, "");
        log.record(SnapcastDiscoveryLog.Kind.RESOLVE_FAILED, TCP, "Snapcast", 3, "");

        assertEquals(SnapcastDiscoveryReport.Reading.RESOLVE_FAILED, SnapcastDiscoveryReport.reading(healthy(), log.snapshot()));
    }

    @Test
    public void resolvedButRefusedByTheAddressRules() {
        scanWithThreeTypesStarted();
        log.record(SnapcastDiscoveryLog.Kind.FOUND, CTRL, "Snapcast", 0, "");
        log.record(SnapcastDiscoveryLog.Kind.RESOLVED_REFUSED, CTRL, "Snapcast", 0, "8.8.8.8:1705");

        assertEquals(SnapcastDiscoveryReport.Reading.REFUSED, SnapcastDiscoveryReport.reading(healthy(), log.snapshot()));
    }

    @Test
    public void aServerThatIsListedIsFound() {
        scanWithThreeTypesStarted();
        log.record(SnapcastDiscoveryLog.Kind.FOUND, CTRL, "Snapcast", 0, "");
        log.record(SnapcastDiscoveryLog.Kind.RESOLVED, CTRL, "Snapcast", 0, "192.168.1.5:1705");

        assertEquals(SnapcastDiscoveryReport.Reading.FOUND, SnapcastDiscoveryReport.reading(healthy(), log.snapshot()));
    }

    @Test
    public void everyReadingHasATextWithoutALayoutBreak() {
        for (SnapcastDiscoveryReport.Reading reading : SnapcastDiscoveryReport.Reading.values()) {
            assertTrue(reading.name(), reading.text().length() > 20);
            assertFalse(reading.name(), reading.text().contains("\n"));
        }
    }

    // ---- the text

    @Test
    public void theReportNamesTheTypesBrowsedAndWhatEachSaw() {
        scanWithThreeTypesStarted();
        log.record(SnapcastDiscoveryLog.Kind.FOUND, TCP, "Snapcast", 0, "");
        log.record(SnapcastDiscoveryLog.Kind.RESOLVED, TCP, "Snapcast", 0, "192.168.1.5:1705");

        String report = SnapcastDiscoveryReport.of(healthy(), log.snapshot());

        for (String type : SnapcastServiceTypes.browsed()) {
            assertTrue(type, report.contains(type));
        }
        assertTrue(report, report.contains("Snapcast 0.33 and newer"));
        assertTrue(report, report.contains("Snapcast 0.32 and older"));
        assertTrue(report, report.contains("found 1"));
        assertTrue(report, report.contains("started"));
    }

    @Test
    public void theReportNamesTheFactsOfThePhone() {
        scanWithThreeTypesStarted();
        String report = SnapcastDiscoveryReport.of(healthy(), log.snapshot());

        assertTrue(report, report.contains("Pixel / API 34"));
        assertTrue(report, report.contains("switch \"Find Snapcast servers\": on"));
        assertTrue(report, report.contains("scan running now: yes"));
        assertTrue(report, report.contains("Wi-Fi: connected"));
        assertTrue(report, report.contains("multicast permission: granted"));
        assertTrue(report, report.contains("multicast lock: held"));
    }

    @Test
    public void unknownFactsAreSaidToBeUnknown() {
        String report = SnapcastDiscoveryReport.of(new SnapcastDiscoveryReport.Facts().device("x"), log.snapshot());

        assertTrue(report, report.contains("Wi-Fi: unknown"));
        assertTrue(report, report.contains("multicast permission: unknown"));
        assertTrue(report, report.contains("multicast lock: not held"));
        assertTrue(report, report.contains("switch \"Find Snapcast servers\": off"));
    }

    @Test
    public void theFailureCodesAreShownByName() {
        scanWithThreeTypesStarted();
        log.record(SnapcastDiscoveryLog.Kind.BROWSE_START_FAILED, CTRL, "", 4, "");
        log.record(SnapcastDiscoveryLog.Kind.RESOLVE_FAILED, TCP, "Snapcast", 3, "");

        String report = SnapcastDiscoveryReport.of(healthy(), log.snapshot());

        assertTrue(report, report.contains("FAILURE_MAX_LIMIT (4)"));
        assertTrue(report, report.contains("FAILURE_ALREADY_ACTIVE (3)"));
    }

    @Test
    public void theServersListedAreInTheReport() {
        scanWithThreeTypesStarted();
        SnapcastDiscoveryReport.Facts facts = healthy().servers(Arrays.asList(
                new SnapcastDiscoveryReport.ServerLine("192.168.1.5:1705", "Living room", SnapcastDiscoveryReport.Origin.FOUND),
                new SnapcastDiscoveryReport.ServerLine("10.0.0.2:1705", "", SnapcastDiscoveryReport.Origin.TYPED)));

        String report = SnapcastDiscoveryReport.of(facts, log.snapshot());

        assertTrue(report, report.contains("192.168.1.5:1705"));
        assertTrue(report, report.contains("Living room"));
        assertTrue(report, report.contains("typed in"));
        assertTrue(report, report.contains("found on the network"));
    }

    @Test
    public void noServersIsSaid() {
        String report = SnapcastDiscoveryReport.of(healthy().servers(Collections.<SnapcastDiscoveryReport.ServerLine>emptyList()),
                log.snapshot());
        assertTrue(report, report.contains("servers listed: none"));
    }

    @Test
    public void theEventsAreInTheReportWithTheirTime() {
        log.scanStarted();
        now.addAndGet(2_300L);
        log.record(SnapcastDiscoveryLog.Kind.FOUND, CTRL, "Snapcast", 0, "");

        String report = SnapcastDiscoveryReport.of(healthy(), log.snapshot());

        assertTrue(report, report.contains("+2.3 s"));
        assertTrue(report, report.contains("found"));
    }

    @Test
    public void aServiceNameWithControlCharactersCannotBreakTheReportLines() {
        log.scanStarted();
        log.record(SnapcastDiscoveryLog.Kind.FOUND, CTRL, "A\nreading: all fine‮", 0, "");

        String report = SnapcastDiscoveryReport.of(healthy(), log.snapshot());

        for (String line : report.split("\n")) {
            assertFalse(line, line.startsWith("reading: all fine"));
        }
        assertFalse(report.contains("‮"));
    }

    @Test
    public void theReportHasNoTrailingGarbageAndEndsWithANewline() {
        String report = SnapcastDiscoveryReport.of(healthy(), log.snapshot());
        assertTrue(report.endsWith("\n"));
        assertTrue(report.startsWith("YAACC Snapcast discovery report"));
    }

    // ---- Group 11Cb

    @Test
    public void aWifiThatIsUnknownIsNeverReadAsNoWifi() {
        scanWithThreeTypesStarted();
        assertEquals(SnapcastDiscoveryReport.Reading.NOTHING_HEARD,
                SnapcastDiscoveryReport.reading(healthy().wifi(SnapcastDiscoveryReport.Tri.UNKNOWN), log.snapshot()));
        log.record(SnapcastDiscoveryLog.Kind.FOUND, CTRL, "Snapcast", 0, "");
        log.record(SnapcastDiscoveryLog.Kind.RESOLVED, CTRL, "Snapcast", 0, "192.168.1.5:1705");
        assertEquals(SnapcastDiscoveryReport.Reading.FOUND,
                SnapcastDiscoveryReport.reading(healthy().wifi(SnapcastDiscoveryReport.Tri.UNKNOWN), log.snapshot()));
    }

    @Test
    public void aMulticastPermissionThatIsUnknownIsNeverReadAsMissing() {
        scanWithThreeTypesStarted();
        assertEquals(SnapcastDiscoveryReport.Reading.NOTHING_HEARD,
                SnapcastDiscoveryReport.reading(healthy().multicastPermission(SnapcastDiscoveryReport.Tri.UNKNOWN), log.snapshot()));
    }

    @Test
    public void theTransportsOfTheDefaultNetworkGiveTheWifiFact() {
        // (network, wifi, ethernet, vpn)
        assertEquals(SnapcastDiscoveryReport.Tri.NO, SnapcastDiscoveryReport.wifiOf(false, false, false, false));
        assertEquals(SnapcastDiscoveryReport.Tri.YES, SnapcastDiscoveryReport.wifiOf(true, true, false, false));
        assertEquals(SnapcastDiscoveryReport.Tri.YES, SnapcastDiscoveryReport.wifiOf(true, false, true, false));
        assertEquals("Wi-Fi under a VPN", SnapcastDiscoveryReport.Tri.YES, SnapcastDiscoveryReport.wifiOf(true, true, false, true));
        assertEquals("a VPN hides what carries it", SnapcastDiscoveryReport.Tri.UNKNOWN,
                SnapcastDiscoveryReport.wifiOf(true, false, false, true));
        assertEquals("mobile data only", SnapcastDiscoveryReport.Tri.NO, SnapcastDiscoveryReport.wifiOf(true, false, false, false));
        assertEquals("no network with a stale VPN flag", SnapcastDiscoveryReport.Tri.NO,
                SnapcastDiscoveryReport.wifiOf(false, false, false, true));
    }

    @Test
    public void aResolutionThatIsStillRunningIsNotAFailure() {
        scanWithThreeTypesStarted();
        log.record(SnapcastDiscoveryLog.Kind.FOUND, CTRL, "Snapcast", 0, "");

        assertEquals(SnapcastDiscoveryReport.Reading.RESOLVING, SnapcastDiscoveryReport.reading(healthy(), log.snapshot()));
    }

    @Test
    public void aServerThatWasFoundWinsOverABrowseThatWasRefused() {
        scanWithThreeTypesStarted();
        log.record(SnapcastDiscoveryLog.Kind.BROWSE_START_FAILED, STREAM, "", 4, ""); // the probe, not the control service
        log.record(SnapcastDiscoveryLog.Kind.FOUND, CTRL, "Snapcast", 0, "");
        log.record(SnapcastDiscoveryLog.Kind.RESOLVED, CTRL, "Snapcast", 0, "192.168.1.5:1705");

        assertEquals(SnapcastDiscoveryReport.Reading.FOUND, SnapcastDiscoveryReport.reading(healthy(), log.snapshot()));
        assertTrue(SnapcastDiscoveryReport.of(healthy(), log.snapshot()).contains("FAILURE_MAX_LIMIT (4)"));
    }

    @Test
    public void theNoteNamesWhatTheReportCarriesAboutThePhone() {
        // the report has the phone model: the screen text near the copy button says so (see the resource test)
        assertTrue(SnapcastDiscoveryReport.of(healthy(), log.snapshot()).contains("device: Pixel / API 34"));
    }
}
