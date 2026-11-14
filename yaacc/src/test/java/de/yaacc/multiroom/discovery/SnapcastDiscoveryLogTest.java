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

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import de.yaacc.multiroom.control.MonotonicClock;

/** What the discovery saw, bounded and cleaned, for the report (task 11.6). Plain JVM with a clock the test moves. */
public class SnapcastDiscoveryLogTest {

    private static final String CTRL = "_snapcast-ctrl._tcp";
    private static final String TCP = "_snapcast-tcp._tcp";

    private final AtomicLong now = new AtomicLong(5_000L);
    private final MonotonicClock clock = now::get;
    private SnapcastDiscoveryLog log;

    @Before
    public void setUp() {
        log = new SnapcastDiscoveryLog(clock);
    }

    @Test
    public void anEmptyLogHasNothing() {
        SnapcastDiscoveryLog.Snapshot snapshot = log.snapshot();
        assertTrue(snapshot.events().isEmpty());
        assertFalse(snapshot.hasScanned());
        assertEquals(0, snapshot.stats(CTRL).found());
    }

    @Test
    public void eventsComeOldestFirstWithTheTimeSinceTheScanStarted() {
        log.scanStarted();
        now.addAndGet(1_500L);
        log.record(SnapcastDiscoveryLog.Kind.FOUND, CTRL, "Snapcast", 0, "");

        List<SnapcastDiscoveryLog.Event> events = log.snapshot().events();
        assertEquals(1, events.size());
        assertEquals(1_500L, events.get(0).atMillis());
        assertEquals(SnapcastDiscoveryLog.Kind.FOUND, events.get(0).kind());
        assertEquals("Snapcast", events.get(0).name());
        assertTrue(log.snapshot().hasScanned());
    }

    @Test
    public void theCountersFollowTheEventsPerType() {
        log.scanStarted();
        log.record(SnapcastDiscoveryLog.Kind.BROWSE_REQUESTED, CTRL, "", 0, "");
        log.record(SnapcastDiscoveryLog.Kind.BROWSE_STARTED, CTRL, "", 0, "");
        log.record(SnapcastDiscoveryLog.Kind.FOUND, CTRL, "A", 0, "");
        log.record(SnapcastDiscoveryLog.Kind.FOUND, CTRL, "B", 0, "");
        log.record(SnapcastDiscoveryLog.Kind.LOST, CTRL, "B", 0, "");
        log.record(SnapcastDiscoveryLog.Kind.RESOLVED, CTRL, "A", 0, "192.168.1.5:1705");
        log.record(SnapcastDiscoveryLog.Kind.RESOLVED_REFUSED, CTRL, "B", 0, "8.8.8.8:1705");
        log.record(SnapcastDiscoveryLog.Kind.RESOLVE_FAILED, TCP, "C", 3, "");
        log.record(SnapcastDiscoveryLog.Kind.BROWSE_START_FAILED, TCP, "", 4, "");

        SnapcastDiscoveryLog.TypeStats ctrl = log.snapshot().stats(CTRL);
        assertTrue(ctrl.requested());
        assertTrue(ctrl.started());
        assertEquals(2, ctrl.found());
        assertEquals(1, ctrl.lost());
        assertEquals(1, ctrl.resolved());
        assertEquals(1, ctrl.refused());
        SnapcastDiscoveryLog.TypeStats tcp = log.snapshot().stats(TCP);
        assertEquals(1, tcp.resolveFailed());
        assertEquals(3, tcp.lastResolveFailure());
        assertEquals(4, tcp.startFailure());
    }

    @Test
    public void aNewScanStartsWithAFreshLog() {
        log.scanStarted();
        log.record(SnapcastDiscoveryLog.Kind.FOUND, CTRL, "A", 0, "");
        log.scanStarted();

        assertTrue(log.snapshot().events().isEmpty());
        assertEquals(0, log.snapshot().stats(CTRL).found());
        assertTrue(log.snapshot().hasScanned());
    }

    @Test
    public void onlyTheLastEventsAreKeptAndTheCountersStayExact() {
        log.scanStarted();
        for (int i = 0; i < SnapcastDiscoveryLog.CAPACITY + 25; i++) {
            log.record(SnapcastDiscoveryLog.Kind.FOUND, CTRL, "n" + i, 0, "");
        }
        SnapcastDiscoveryLog.Snapshot snapshot = log.snapshot();

        assertEquals(SnapcastDiscoveryLog.CAPACITY, snapshot.events().size());
        assertEquals(25, snapshot.droppedEvents());
        assertEquals("n25", snapshot.events().get(0).name());
        assertEquals(SnapcastDiscoveryLog.CAPACITY + 25, snapshot.stats(CTRL).found());
    }

    @Test
    public void namesFromTheNetworkAreCleanedAndCut() {
        log.scanStarted();
        StringBuilder longName = new StringBuilder("evil\u0007\n‮");
        for (int i = 0; i < 200; i++) {
            longName.append('x');
        }
        log.record(SnapcastDiscoveryLog.Kind.FOUND, CTRL, longName.toString(), 0, "line1\nline2");

        SnapcastDiscoveryLog.Event event = log.snapshot().events().get(0);
        assertTrue(event.name().length() <= 64);
        assertFalse(event.name().contains("\n"));
        assertFalse(event.name().contains("\u0007"));
        assertFalse(event.name().contains("‮"));
        assertFalse(event.detail().contains("\n"));
    }

    @Test
    public void aTypeThatWeDoNotBrowseIsNotCounted() {
        log.scanStarted();
        log.record(SnapcastDiscoveryLog.Kind.FOUND_IGNORED, "_evil._tcp", "x", 0, "");

        assertEquals(1, log.snapshot().events().size());
        assertEquals(1, log.snapshot().ignoredTypes());
        assertEquals(0, log.snapshot().stats("_evil._tcp").found());
    }

    @Test
    public void aFloodOfDifferentIgnoredTypesDoesNotGrowTheCounters() {
        log.scanStarted();
        for (int i = 0; i < 1000; i++) {
            log.record(SnapcastDiscoveryLog.Kind.FOUND_IGNORED, "_t" + i + "._tcp", "x", 0, "");
        }
        assertEquals(SnapcastDiscoveryLog.CAPACITY, log.snapshot().events().size());
        assertEquals(1000, log.snapshot().ignoredTypes());
    }

    @Test
    public void theSnapshotDoesNotChangeWhenTheLogDoes() {
        log.scanStarted();
        log.record(SnapcastDiscoveryLog.Kind.FOUND, CTRL, "A", 0, "");
        SnapcastDiscoveryLog.Snapshot before = log.snapshot();
        log.record(SnapcastDiscoveryLog.Kind.FOUND, CTRL, "B", 0, "");

        assertEquals(1, before.events().size());
        assertEquals(1, before.stats(CTRL).found());
    }

    // ---- Group 11Cb S6: a late callback of an earlier scan does not touch the new one

    @Test
    public void aLateStopOfAnEarlierScanIsIgnored() {
        log.scanStarted();
        int first = log.scanId();
        log.record(first, SnapcastDiscoveryLog.Kind.BROWSE_REQUESTED, CTRL, "", 0, "");
        log.record(first, SnapcastDiscoveryLog.Kind.BROWSE_STARTED, CTRL, "", 0, "");
        log.scanStarted(); // the page was hidden and shown again
        int second = log.scanId();
        log.record(second, SnapcastDiscoveryLog.Kind.BROWSE_REQUESTED, CTRL, "", 0, "");
        log.record(second, SnapcastDiscoveryLog.Kind.BROWSE_STARTED, CTRL, "", 0, "");

        log.record(first, SnapcastDiscoveryLog.Kind.BROWSE_STOPPED, CTRL, "", 0, ""); // the confirmation of the first stop

        assertTrue(log.snapshot().stats(CTRL).started());
        assertTrue(log.snapshot().stats(CTRL).requested());
        assertEquals(2, log.snapshot().events().size());
    }

    @Test
    public void theStopOfTheCurrentScanIsRecorded() {
        log.scanStarted();
        int id = log.scanId();
        log.record(id, SnapcastDiscoveryLog.Kind.BROWSE_STARTED, CTRL, "", 0, "");
        log.record(id, SnapcastDiscoveryLog.Kind.BROWSE_STOPPED, CTRL, "", 0, "");

        assertFalse(log.snapshot().stats(CTRL).started());
        assertEquals(2, log.snapshot().events().size());
    }
}
