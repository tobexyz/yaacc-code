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
package de.yaacc.multiroom.discovery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import de.yaacc.multiroom.control.MonotonicClock;

/**
 * The merge of mDNS sightings and manual servers: one entry per control endpoint, a lost service stays for a grace
 * period, manual entries are never dropped by discovery, at most 16 servers. Plain JVM with a clock the test moves.
 */
public class SnapcastDirectoryTest {

    private static final long GRACE = 30_000L;

    private final AtomicLong now = new AtomicLong(1_000L);
    private final MonotonicClock clock = now::get;
    private SnapcastDirectory directory;
    private int notifications;

    @Before
    public void setUp() {
        directory = new SnapcastDirectory(clock, GRACE);
        directory.addListener(() -> notifications++);
    }

    private static InetAddress ip(int a, int b, int c, int d) throws Exception {
        return InetAddress.getByAddress(new byte[]{(byte) a, (byte) b, (byte) c, (byte) d});
    }

    private static SnapcastEndpoint manual(String text) {
        SnapcastEndpoint endpoint = SnapcastEndpoint.parse(text);
        assertNotNull(text, endpoint);
        return endpoint;
    }

    private static SnapcastServerKey key(String text) {
        return SnapcastEndpoint.parse(text).toKey();
    }

    @Test
    public void twoSightingsOfOneServerGiveOneEntry() throws Exception {
        assertTrue(directory.serviceFound("Snapcast", ip(192, 168, 1, 5), 1705));
        assertTrue(directory.serviceFound("Snapcast", ip(192, 168, 1, 5), 1705));
        assertTrue(directory.serviceFound("Snapcast (2)", ip(192, 168, 1, 5), 1705));

        assertEquals(1, directory.servers().size());
        assertEquals(key("192.168.1.5:1705"), directory.servers().get(0).getKey());
    }

    @Test
    public void aServerFoundByMdnsAndTypedByHandIsOneEntry() throws Exception {
        directory.serviceFound("Snapcast", ip(192, 168, 1, 5), 1705);
        directory.setManualServers(Collections.singletonList(manual("192.168.1.5")));

        List<SnapcastDirectory.Entry> servers = directory.servers();
        assertEquals(1, servers.size());
        assertTrue(servers.get(0).isManual());
        assertTrue(servers.get(0).isDiscovered());
    }

    @Test
    public void theOrderOfManualAndDiscoveryDoesNotMatter() throws Exception {
        directory.setManualServers(Collections.singletonList(manual("[::ffff:192.168.1.5]:1705")));
        directory.serviceFound("Snapcast", ip(192, 168, 1, 5), 1705);

        assertEquals(1, directory.servers().size());
    }

    @Test
    public void serversOnDifferentPortsAreDifferentServers() throws Exception {
        directory.serviceFound("A", ip(192, 168, 1, 5), 1705);
        directory.serviceFound("B", ip(192, 168, 1, 5), 1706);

        assertEquals(2, directory.servers().size());
    }

    @Test
    public void aLostServiceStaysForTheGraceTimeAndThenGoes() throws Exception {
        directory.serviceFound("Snapcast", ip(10, 0, 0, 2), 1705);
        directory.serviceLost("Snapcast");

        SnapcastDirectory.Entry entry = directory.servers().get(0);
        assertTrue("marked as lost, still listed", entry.isLost());

        now.addAndGet(GRACE - 1);
        assertEquals(1, directory.servers().size());
        now.addAndGet(1);
        assertTrue(directory.servers().isEmpty());
        assertNull(directory.find(key("10.0.0.2:1705")));
    }

    @Test
    public void aServiceThatComesBackInTheGraceTimeIsNotLostAnyMore() throws Exception {
        directory.serviceFound("Snapcast", ip(10, 0, 0, 2), 1705);
        directory.serviceLost("Snapcast");
        now.addAndGet(GRACE - 1);

        directory.serviceFound("Snapcast", ip(10, 0, 0, 2), 1705);
        now.addAndGet(GRACE * 3);

        assertEquals(1, directory.servers().size());
        assertFalse(directory.servers().get(0).isLost());
    }

    @Test
    public void aServerStaysWhileOneOfItsTwoServiceNamesIsStillThere() throws Exception {
        directory.serviceFound("Snapcast", ip(10, 0, 0, 2), 1705);
        directory.serviceFound("Snapcast (2)", ip(10, 0, 0, 2), 1705);

        directory.serviceLost("Snapcast");
        now.addAndGet(GRACE * 2);

        assertEquals(1, directory.servers().size());
        assertFalse(directory.servers().get(0).isLost());
    }

    @Test
    public void aManualServerIsNeverDroppedByDiscoveryLoss() throws Exception {
        directory.setManualServers(Collections.singletonList(manual("10.0.0.2")));
        directory.serviceFound("Snapcast", ip(10, 0, 0, 2), 1705);
        directory.serviceLost("Snapcast");
        directory.discoveryRestarted();
        now.addAndGet(GRACE * 10);

        assertEquals(1, directory.servers().size());
        SnapcastDirectory.Entry entry = directory.servers().get(0);
        assertTrue(entry.isManual());
        assertFalse(entry.isDiscovered());
        assertFalse(entry.isLost());
    }

    @Test
    public void aManualServerWithAHostNameIsListedWithoutAnAddress() {
        directory.setManualServers(Collections.singletonList(manual("snap.local:1999")));

        SnapcastDirectory.Entry entry = directory.servers().get(0);
        assertEquals("snap.local:1999", entry.getKey().asString());
        assertNull(entry.getEndpoint().getAddress());
    }

    @Test
    public void replacingTheManualListRemovesTheOnesThatAreGone() throws Exception {
        directory.setManualServers(Arrays.asList(manual("10.0.0.2"), manual("10.0.0.3")));
        directory.setManualServers(Collections.singletonList(manual("10.0.0.3")));

        assertEquals(1, directory.servers().size());
        assertEquals(key("10.0.0.3"), directory.servers().get(0).getKey());
    }

    @Test
    public void aServerThatIsNoLongerManualButStillAnnouncedStaysAsDiscovered() throws Exception {
        directory.serviceFound("Snapcast", ip(10, 0, 0, 2), 1705);
        directory.setManualServers(Collections.singletonList(manual("10.0.0.2")));
        directory.setManualServers(Collections.<SnapcastEndpoint>emptyList());

        assertEquals(1, directory.servers().size());
        assertFalse(directory.servers().get(0).isManual());
    }

    @Test
    public void restartingTheDiscoveryGivesUnseenServersTheGraceTimeOnly() throws Exception {
        directory.serviceFound("A", ip(10, 0, 0, 2), 1705);
        directory.serviceFound("B", ip(10, 0, 0, 3), 1705);

        directory.discoveryRestarted();
        directory.serviceFound("A", ip(10, 0, 0, 2), 1705);
        now.addAndGet(GRACE);

        List<SnapcastDirectory.Entry> servers = directory.servers();
        assertEquals(1, servers.size());
        assertEquals(key("10.0.0.2"), servers.get(0).getKey());
    }

    @Test
    public void aServiceNameThatMovesToAnotherAddressReplacesTheOldEntry() throws Exception {
        directory.serviceFound("Snapcast", ip(10, 0, 0, 2), 1705);
        directory.serviceFound("Snapcast", ip(10, 0, 0, 9), 1705);

        assertTrue(directory.find(key("10.0.0.2")).isLost());
        assertFalse(directory.find(key("10.0.0.9")).isLost());
    }

    @Test
    public void addressesOutsideTheLocalRangesAreNotListed() throws Exception {
        assertFalse(directory.serviceFound("Evil", ip(8, 8, 8, 8), 1705));
        assertFalse(directory.serviceFound("Multicast", ip(224, 0, 0, 251), 1705));
        assertFalse(directory.serviceFound("Port", ip(10, 0, 0, 2), 0));
        assertFalse(directory.serviceFound(null, ip(10, 0, 0, 2), 1705));

        assertTrue(directory.servers().isEmpty());
    }

    @Test
    public void atMostSixteenServersAreListed() throws Exception {
        for (int i = 1; i <= 16; i++) {
            assertTrue(directory.serviceFound("S" + i, ip(10, 0, 0, i), 1705));
        }
        assertFalse("the 17th is refused", directory.serviceFound("S17", ip(10, 0, 0, 17), 1705));
        assertEquals(16, directory.servers().size());
        assertNull(directory.find(key("10.0.0.17")));
    }

    @Test
    public void aLostServerMakesRoomForANewOne() throws Exception {
        for (int i = 1; i <= 16; i++) {
            directory.serviceFound("S" + i, ip(10, 0, 0, i), 1705);
        }
        directory.serviceLost("S3");

        assertTrue(directory.serviceFound("S17", ip(10, 0, 0, 17), 1705));
        assertEquals(16, directory.servers().size());
        assertNull(directory.find(key("10.0.0.3")));
    }

    @Test
    public void manualEntriesTakePlaceOverDiscoveredOnesButStayWithinTheCap() throws Exception {
        for (int i = 1; i <= 16; i++) {
            directory.serviceFound("S" + i, ip(10, 0, 0, i), 1705);
        }
        List<SnapcastEndpoint> manualList = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            manualList.add(manual("192.168.5." + i));
        }

        int accepted = directory.setManualServers(manualList);

        assertEquals(16, accepted);
        assertEquals(16, directory.servers().size());
        for (SnapcastDirectory.Entry entry : directory.servers()) {
            assertTrue(entry.isManual());
        }
        assertFalse("nothing found by mDNS fits any more", directory.serviceFound("S99", ip(10, 0, 0, 99), 1705));
    }

    @Test
    public void namesFromTheNetworkAreDisplayTextOnly() throws Exception {
        directory.serviceFound("Living\nroom ‮\u001b[31mevil", ip(10, 0, 0, 2), 1705);

        String name = directory.servers().get(0).getDisplayName();
        assertFalse(name.contains("\n"));
        assertFalse(name.contains("‮"));
        assertFalse(name.contains("\u001b"));
        assertEquals("the key stays the endpoint, whatever the name says", "10.0.0.2:1705",
                directory.servers().get(0).getKey().asString());
    }

    @Test
    public void aLongServiceNameIsCutAndAHugeOneIsRefused() throws Exception {
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            longName.append('x');
        }
        assertTrue(directory.serviceFound(longName.toString(), ip(10, 0, 0, 2), 1705));
        assertEquals(64, directory.servers().get(0).getDisplayName().length());

        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            huge.append('x');
        }
        assertFalse(directory.serviceFound(huge.toString(), ip(10, 0, 0, 3), 1705));
        assertEquals(1, directory.servers().size());
    }

    @Test
    public void manyServiceNamesForOneServerDoNotGrowTheDirectory() throws Exception {
        for (int i = 0; i < 1000; i++) {
            directory.serviceFound("name" + i, ip(10, 0, 0, 2), 1705);
        }

        assertEquals(1, directory.servers().size());
        assertTrue(directory.knownServiceNameCount() <= SnapcastDirectory.MAX_SERVICE_NAMES);
    }

    @Test
    public void listenersHearOfChangesButNotOfNothing() throws Exception {
        directory.serviceFound("Snapcast", ip(10, 0, 0, 2), 1705);
        int afterFirst = notifications;
        assertTrue(afterFirst > 0);

        directory.serviceFound("Snapcast", ip(10, 0, 0, 2), 1705);
        directory.serviceLost("unknown name");
        assertEquals("no change, no call", afterFirst, notifications);

        directory.serviceLost("Snapcast");
        assertTrue(notifications > afterFirst);
        int afterLost = notifications;
        now.addAndGet(GRACE);
        directory.servers();
        assertTrue("the expiry is a change too", notifications > afterLost);
    }

    @Test
    public void expireRemovesTheLostServersAndReportsIt() throws Exception {
        directory.serviceFound("Snapcast", ip(10, 0, 0, 2), 1705);
        directory.serviceLost("Snapcast");
        assertFalse(directory.expire());
        now.addAndGet(GRACE);
        assertTrue(directory.expire());
        assertFalse(directory.expire());
    }

    // ---- remembered servers (the server of a selected group, known again after a restart)

    @Test
    public void aRememberedServerIsListedWithoutBeingAnnounced() {
        directory.setRememberedServers(Collections.singletonList(manual("192.168.1.5:1705")));

        List<SnapcastDirectory.Entry> servers = directory.servers();
        assertEquals(1, servers.size());
        assertTrue(servers.get(0).isRemembered());
        assertFalse(servers.get(0).isManual());
        assertFalse(servers.get(0).isDiscovered());
        assertFalse(servers.get(0).isLost());
    }

    @Test
    public void aRememberedServerDoesNotExpireByTimeAlone() {
        directory.setRememberedServers(Collections.singletonList(manual("192.168.1.5:1705")));

        now.addAndGet(GRACE * 100);

        assertEquals("nothing is scanned outside the page, so nothing may age it out", 1, directory.servers().size());
    }

    @Test
    public void whenDiscoveryRestartsARememberedServerThatIsNotSeenGoesAfterTheGrace() {
        directory.setRememberedServers(Collections.singletonList(manual("192.168.1.5:1705")));

        directory.discoveryRestarted();
        now.addAndGet(GRACE - 1);
        assertEquals(1, directory.servers().size());
        now.addAndGet(1);

        assertTrue(directory.servers().isEmpty());
    }

    @Test
    public void aRememberedServerThatIsAnnouncedInTheGraceStays() throws Exception {
        directory.setRememberedServers(Collections.singletonList(manual("192.168.1.5:1705")));
        directory.discoveryRestarted();

        directory.serviceFound("Snapcast", ip(192, 168, 1, 5), 1705);
        now.addAndGet(GRACE * 3);

        List<SnapcastDirectory.Entry> servers = directory.servers();
        assertEquals(1, servers.size());
        assertTrue(servers.get(0).isDiscovered());
        assertTrue(servers.get(0).isRemembered());
    }

    @Test
    public void aServerThatIsNoLongerRememberedGoesWhenItIsNotAnnounced() {
        directory.setRememberedServers(Collections.singletonList(manual("192.168.1.5:1705")));

        directory.setRememberedServers(Collections.<SnapcastEndpoint>emptyList());

        assertTrue(directory.servers().isEmpty());
    }

    @Test
    public void aServerThatIsNoLongerRememberedStaysWhileItIsAnnounced() throws Exception {
        directory.serviceFound("Snapcast", ip(192, 168, 1, 5), 1705);
        directory.setRememberedServers(Collections.singletonList(manual("192.168.1.5:1705")));

        directory.setRememberedServers(Collections.<SnapcastEndpoint>emptyList());

        assertEquals(1, directory.servers().size());
        assertFalse(directory.servers().get(0).isRemembered());
    }

    @Test
    public void rememberingAManualServerDoesNotMakeItDisappearWithTheList() {
        directory.setManualServers(Collections.singletonList(manual("192.168.1.5")));
        directory.setRememberedServers(Collections.singletonList(manual("192.168.1.5")));

        directory.setRememberedServers(Collections.<SnapcastEndpoint>emptyList());

        assertEquals(1, directory.servers().size());
        assertTrue(directory.servers().get(0).isManual());
    }

    @Test
    public void aServerThatLeavesTheManualListStaysWhileItIsRemembered() {
        directory.setManualServers(Collections.singletonList(manual("192.168.1.5")));
        directory.setRememberedServers(Collections.singletonList(manual("192.168.1.5")));

        directory.setManualServers(Collections.<SnapcastEndpoint>emptyList());

        assertEquals(1, directory.servers().size());
        assertFalse(directory.servers().get(0).isManual());
    }

    @Test
    public void rememberedServersAreCappedAndNeverTakeTheRoomOfTheOthers() throws Exception {
        for (int i = 1; i <= SnapcastDirectory.MAX_SERVERS; i++) {
            directory.serviceFound("S" + i, ip(10, 0, 0, i), 1705);
        }
        List<SnapcastEndpoint> remembered = new ArrayList<>();
        for (int i = 100; i < 140; i++) {
            remembered.add(manual("10.0.1." + i));
        }

        directory.setRememberedServers(remembered);

        assertEquals(SnapcastDirectory.MAX_SERVERS, directory.servers().size());
        for (SnapcastDirectory.Entry entry : directory.servers()) {
            assertTrue("the announced servers were not pushed out", entry.isDiscovered());
        }
    }

    @Test
    public void aRememberedServerTakesTheRoomOfALostEntryWhenTheListIsFull() throws Exception {
        for (int i = 1; i <= SnapcastDirectory.MAX_SERVERS; i++) {
            directory.serviceFound("S" + i, ip(10, 0, 0, i), 1705);
        }
        directory.serviceLost("S3");

        directory.setRememberedServers(Collections.singletonList(manual("10.0.1.100")));

        assertEquals(SnapcastDirectory.MAX_SERVERS, directory.servers().size());
        boolean lostIsGone = true;
        boolean rememberedIsThere = false;
        for (SnapcastDirectory.Entry entry : directory.servers()) {
            lostIsGone &= !entry.getKey().equals(key("10.0.0.3:1705"));
            rememberedIsThere |= entry.getKey().equals(key("10.0.1.100:1705")) && entry.isRemembered();
        }
        assertTrue(lostIsGone);
        assertTrue(rememberedIsThere);
    }

    @Test
    public void rememberedServersAreNotAcceptedWithoutAnAddress() {
        // a host name has to be looked up; only an address that was seen (and filtered) is remembered
        directory.setRememberedServers(Arrays.asList(manual("snap.local"), manual("8.8.8.8"), null));

        assertTrue(directory.servers().isEmpty());
    }

    // ---- the quiet snapshot (for code that holds a lock of its own)

    @Test
    public void theSnapshotListsTheServersAndLeavesOutTheExpiredOnesWithoutTellingAnyone() throws Exception {
        directory.serviceFound("A", ip(192, 168, 1, 5), 1705);
        directory.serviceFound("B", ip(192, 168, 1, 6), 1705);
        directory.serviceLost("B");
        int told = notifications;

        assertEquals(2, directory.snapshot().size());
        now.addAndGet(GRACE);
        List<SnapcastDirectory.Entry> snapshot = directory.snapshot();

        assertEquals(1, snapshot.size());
        assertEquals(key("192.168.1.5:1705"), snapshot.get(0).getKey());
        assertEquals("a snapshot never calls a listener", told, notifications);
        assertEquals("the entry is removed (and the listeners told) by the next real call", 1, directory.servers().size());
        assertTrue(notifications > told);
    }

    @Test
    public void theSnapshotIsSortedLikeTheList() throws Exception {
        directory.serviceFound("B", ip(192, 168, 1, 9), 1705);
        directory.serviceFound("A", ip(192, 168, 1, 5), 1705);

        List<SnapcastDirectory.Entry> snapshot = directory.snapshot();

        assertEquals(key("192.168.1.5:1705"), snapshot.get(0).getKey());
        assertEquals(key("192.168.1.9:1705"), snapshot.get(1).getKey());
    }

    // ---- discovery switched off (settings_snapcast_discovery)

    @Test
    public void whenDiscoveryIsStoppedTheServersOnlyItKnewGoAtOnce() throws Exception {
        directory.serviceFound("A", ip(10, 0, 0, 2), 1705);
        directory.serviceFound("B", ip(10, 0, 0, 3), 1705);
        directory.serviceLost("B"); // in its grace period

        directory.discoveryStopped();

        assertTrue(directory.servers().isEmpty());
        assertNull(directory.find(key("10.0.0.2")));
    }

    @Test
    public void whenDiscoveryIsStoppedManualServersStayAndAreNoLongerDiscovered() throws Exception {
        directory.setManualServers(Collections.singletonList(manual("10.0.0.2")));
        directory.serviceFound("A", ip(10, 0, 0, 2), 1705);

        directory.discoveryStopped();

        assertEquals(1, directory.servers().size());
        SnapcastDirectory.Entry entry = directory.servers().get(0);
        assertTrue(entry.isManual());
        assertFalse(entry.isDiscovered());
    }

    @Test
    public void whenDiscoveryIsStoppedARememberedServerStaysEvenAfterTheGraceTime() throws Exception {
        directory.setRememberedServers(Collections.singletonList(manual("10.0.0.2:1705")));
        directory.discoveryRestarted(); // a scan began: the remembered server got its grace period
        directory.discoveryStopped();   // and the scan was switched off before it could confirm anything
        now.addAndGet(10 * GRACE);

        assertEquals(1, directory.servers().size());
        assertTrue(directory.servers().get(0).isRemembered());
        assertFalse(directory.servers().get(0).isLost());
    }

    @Test
    public void whenDiscoveryIsStoppedALateLostEventForAnOldServiceDoesNothing() throws Exception {
        directory.setManualServers(Collections.singletonList(manual("10.0.0.2")));
        directory.serviceFound("A", ip(10, 0, 0, 2), 1705);
        directory.discoveryStopped();
        int before = notifications;

        directory.serviceLost("A");

        assertEquals(before, notifications);
        assertEquals(1, directory.servers().size());
    }

    @Test
    public void stoppingDiscoveryTellsTheListenersOnlyWhenSomethingChanged() throws Exception {
        directory.setManualServers(Collections.singletonList(manual("10.0.0.2")));
        int before = notifications;
        directory.discoveryStopped();
        assertEquals("nothing was discovered", before, notifications);

        directory.serviceFound("A", ip(10, 0, 0, 3), 1705);
        before = notifications;
        directory.discoveryStopped();
        assertEquals(before + 1, notifications);
    }

    // ---- ports of discovered services (security review S18)

    @Test
    public void aDiscoveredServiceOnAPortBelow1024IsRefused() throws Exception {
        assertFalse(directory.serviceFound("Web", ip(10, 0, 0, 2), 80));
        assertFalse(directory.serviceFound("Ssh", ip(10, 0, 0, 2), 22));
        assertFalse(directory.serviceFound("Edge", ip(10, 0, 0, 2), 1023));

        assertTrue(directory.servers().isEmpty());
    }

    @Test
    public void thePortsFrom1024UpAreAccepted() throws Exception {
        assertTrue(directory.serviceFound("Low", ip(10, 0, 0, 2), 1024));
        assertTrue(directory.serviceFound("Default", ip(10, 0, 0, 3), 1705));
        assertTrue(directory.serviceFound("High", ip(10, 0, 0, 4), 65535));
    }

    @Test
    public void aServerTheUserTypedInMayUseAnyPort() {
        assertEquals(1, directory.setManualServers(Collections.singletonList(manual("10.0.0.2:80"))));
        assertEquals(1, directory.servers().size());
    }
}
