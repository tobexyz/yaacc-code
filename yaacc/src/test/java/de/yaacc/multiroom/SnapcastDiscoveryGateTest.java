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
package de.yaacc.multiroom;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import de.yaacc.multiroom.control.MonotonicClock;
import de.yaacc.multiroom.discovery.SnapcastDirectory;
import de.yaacc.multiroom.discovery.SnapcastEndpoint;

/**
 * Discovery runs only while a receivers page is visible <b>and</b> the setting is on, and it follows the setting live:
 * switching it off stops the scan at once and removes the servers that only the scan knew; typed-in servers stay.
 * Plain JVM with a fake scan and the real directory.
 */
public class SnapcastDiscoveryGateTest {

    private final List<String> events = new ArrayList<>();
    private boolean enabled;
    private SnapcastDiscoveryGate gate;

    private final SnapcastDiscoveryGate.Discovery discovery = new SnapcastDiscoveryGate.Discovery() {
        @Override
        public void start() {
            events.add("start");
        }

        @Override
        public void stop() {
            events.add("stop");
        }
    };

    @Before
    public void setUp() {
        gate = new SnapcastDiscoveryGate(() -> enabled, discovery, () -> events.add("off"));
    }

    @Test
    public void withTheSettingOffAVisiblePageStartsNoScan() {
        gate.pageStarted();
        gate.pageStarted();

        assertFalse(events.contains("start"));
        assertFalse(gate.isRunning());
    }

    @Test
    public void withTheSettingOnTheFirstPageStartsTheScanAndTheLastOneStopsIt() {
        enabled = true;

        gate.pageStarted();
        gate.pageStarted();
        gate.pageStopped();
        assertEquals(Collections.singletonList("start"), events);
        gate.pageStopped();

        assertEquals(java.util.Arrays.asList("start", "stop"), events);
        assertFalse(gate.isRunning());
    }

    @Test
    public void switchingOnWhileAPageIsVisibleStartsTheScan() {
        gate.pageStarted();
        enabled = true;

        gate.settingChanged();

        assertTrue(gate.isRunning());
        assertEquals(1, count("start"));
    }

    @Test
    public void switchingOffWhileTheScanRunsStopsItAtOnceAndCleansTheDirectory() {
        enabled = true;
        gate.pageStarted();
        enabled = false;

        gate.settingChanged();

        assertFalse(gate.isRunning());
        assertEquals(java.util.Arrays.asList("start", "stop", "off"), events);
    }

    @Test
    public void switchingOnAgainRestartsTheScanOfAVisiblePage() {
        enabled = true;
        gate.pageStarted();
        enabled = false;
        gate.settingChanged();
        enabled = true;
        gate.settingChanged();

        assertTrue(gate.isRunning());
        assertEquals(2, count("start"));
    }

    @Test
    public void aChangeOfTheSettingWithoutAVisiblePageStartsNothing() {
        enabled = true;
        gate.settingChanged();

        assertFalse(gate.isRunning());
        assertEquals(0, count("start"));
    }

    @Test
    public void aSettingThatDidNotChangeDoesNotRestartTheScan() {
        enabled = true;
        gate.pageStarted();
        gate.settingChanged();
        gate.settingChanged();

        assertEquals(1, count("start"));
        assertEquals(0, count("stop"));
    }

    @Test
    public void theDirectoryIsCleanedOncePerSwitchOffNotAtEveryLook() {
        gate.pageStarted();
        gate.pageStopped();
        gate.pageStarted();
        gate.settingChanged();
        gate.settingChanged();

        assertEquals(1, count("off"));
    }

    @Test
    public void aStopWithoutAStartIsHarmless() {
        enabled = true;
        gate.pageStopped();
        gate.pageStopped();
        gate.pageStarted();

        assertEquals(Collections.singletonList("start"), events);
    }

    @Test
    public void aSettingThatCanNotBeReadCountsAsOff() {
        SnapcastDiscoveryGate broken = new SnapcastDiscoveryGate(() -> {
            throw new IllegalStateException("no preferences");
        }, discovery, () -> events.add("off"));

        broken.pageStarted();

        assertFalse(broken.isRunning());
        assertEquals(0, count("start"));
    }

    @Test
    public void aScanThatFailsToStartDoesNotBreakThePageCount() {
        final boolean[] fail = {true};
        SnapcastDiscoveryGate flaky = new SnapcastDiscoveryGate(() -> true, new SnapcastDiscoveryGate.Discovery() {
            @Override
            public void start() {
                if (fail[0]) {
                    throw new IllegalStateException("no nsd");
                }
                events.add("start");
            }

            @Override
            public void stop() {
                events.add("stop");
            }
        }, () -> { });

        flaky.pageStarted();
        flaky.pageStopped();
        fail[0] = false;
        flaky.pageStarted();

        assertEquals(1, count("start"));
    }

    // ---- with the real directory

    @Test
    public void theDirectoryKeepsOnlyTypedInServersWhenTheSettingIsSwitchedOff() throws Exception {
        final AtomicLong now = new AtomicLong(1_000L);
        final SnapcastDirectory directory = new SnapcastDirectory((MonotonicClock) now::get, 30_000L);
        directory.setManualServers(Collections.singletonList(SnapcastEndpoint.parse("10.0.0.9")));
        SnapcastDiscoveryGate live = new SnapcastDiscoveryGate(() -> enabled, discovery, directory::discoveryStopped);
        enabled = true;
        live.pageStarted();
        directory.serviceFound("Found", InetAddress.getByAddress(new byte[]{10, 0, 0, 2}), 1705);
        assertEquals(2, directory.servers().size());

        enabled = false;
        live.settingChanged();

        assertEquals(1, directory.servers().size());
        assertTrue(directory.servers().get(0).isManual());
    }

    private int count(String event) {
        int n = 0;
        for (String e : events) {
            if (e.equals(event)) {
                n++;
            }
        }
        return n;
    }

    // ---- Group 11Cb W1: a holder that is not a receivers page (the server list window) leases a page, once

    @Test
    public void aLeaseCountsAsAPageUntilItIsClosed() {
        enabled = true;
        SnapcastDiscoveryGate.Lease lease = gate.pageLease();

        assertTrue(gate.isRunning());
        lease.close();

        assertFalse(gate.isRunning());
    }

    @Test
    public void closingALeaseTwiceDoesNotTakeAnotherPagesCount() {
        enabled = true;
        gate.pageStarted(); // the receivers page
        SnapcastDiscoveryGate.Lease lease = gate.pageLease();

        lease.close();
        lease.close();
        lease.close();

        assertTrue("the receivers page still shows", gate.isRunning());
        gate.pageStopped();
        assertFalse(gate.isRunning());
    }

    @Test
    public void theCountNeverGoesBelowZeroAndNeverGetsStuck() {
        enabled = true;
        gate.pageStopped(); // a stop without a start
        SnapcastDiscoveryGate.Lease lease = gate.pageLease();
        lease.close();
        lease.close();
        gate.pageStopped();

        gate.pageStarted();
        assertTrue("one start is one page", gate.isRunning());
        gate.pageStopped();
        assertFalse(gate.isRunning());
    }

    @Test
    public void aLeaseOfASwitchedOffDiscoveryStartsNothingAndClosesQuietly() {
        SnapcastDiscoveryGate.Lease lease = gate.pageLease();
        lease.close();

        assertFalse(events.contains("start"));
        assertFalse(gate.isRunning());
    }
}
