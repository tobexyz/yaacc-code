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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Task 3.2: the directory (merge, caps, staleness, flood) and the stored lists. */
public class FCastDirectoryTest {
    private final AtomicLong now = new AtomicLong(1_000);
    private final FCastDirectory directory = new FCastDirectory(now::get, 30_000);

    private static InetAddress ip(int a, int b, int c, int d) {
        try {
            return InetAddress.getByAddress(new byte[]{(byte) a, (byte) b, (byte) c, (byte) d});
        } catch (java.net.UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String id(String name) {
        return FCastServiceType.serviceId("_fcast._tcp.", name);
    }

    private boolean found(String name, int lastOctet) {
        return directory.serviceFound(id(name), name, ip(192, 168, 0, lastOctet), 46899, FCastServiceTxt.EMPTY);
    }

    private static FCastEndpoint endpoint(String text) {
        return FCastEndpoint.parse(text);
    }

    // ---- discovered

    @Test
    public void aFoundReceiverIsListedWithItsNameAddressAndHints() {
        Map<String, byte[]> txt = new HashMap<>();
        txt.put("v", "4".getBytes(StandardCharsets.UTF_8));
        assertTrue(directory.serviceFound(id("FCast-pc"), "FCast-pc", ip(192, 168, 0, 239), 46899, FCastServiceTxt.parse(txt)));
        List<FCastDirectory.Entry> entries = directory.snapshot();
        assertEquals(1, entries.size());
        FCastDirectory.Entry entry = entries.get(0);
        assertEquals("FCast-pc", entry.getDisplayName());
        assertEquals("192.168.0.239:46899", entry.getEndpoint().toString());
        assertEquals("fcast:192.168.0.239:46899", entry.getId());
        assertEquals(Integer.valueOf(4), entry.getHints().getProtocolVersion());
        assertTrue(entry.isDiscovered());
        assertFalse(entry.isManual());
        assertFalse(entry.isOffline());
        assertEquals(entry.getEndpoint(), directory.findById("fcast:192.168.0.239:46899").getEndpoint());
    }

    @Test
    public void entriesAreSortedByNameIgnoringCase() {
        found("zebra", 3);
        found("Apple", 1);
        found("banana", 2);
        List<String> names = new ArrayList<>();
        for (FCastDirectory.Entry entry : directory.snapshot()) {
            names.add(entry.getDisplayName());
        }
        assertEquals(Arrays.asList("Apple", "banana", "zebra"), names);
    }

    @Test
    public void announcementsThatAreNotOnTheLocalNetworkOrAimAtLowPortsAreRefused() {
        assertFalse(directory.serviceFound(id("a"), "a", ip(8, 8, 8, 8), 46899, null));
        assertFalse(directory.serviceFound(id("b"), "b", ip(127, 0, 0, 1), 46899, null));
        assertFalse(directory.serviceFound(id("c"), "c", ip(100, 64, 0, 1), 46899, null));
        assertFalse(directory.serviceFound(id("d"), "d", ip(192, 168, 0, 5), 22, null));
        assertFalse(directory.serviceFound(id("e"), "e", ip(192, 168, 0, 5), 1023, null));
        assertFalse(directory.serviceFound(id("f"), "f", ip(192, 168, 0, 5), 70000, null));
        assertFalse(directory.serviceFound(id("g"), "g", null, 46899, null));
        assertFalse(directory.serviceFound(null, "h", ip(192, 168, 0, 5), 46899, null));
        assertFalse(directory.serviceFound("", "i", ip(192, 168, 0, 5), 46899, null));
        assertTrue(directory.snapshot().isEmpty());
        assertTrue(directory.serviceFound(id("ok"), "ok", ip(10, 1, 2, 3), 1024, null));
        assertTrue(directory.serviceFound(id("ok2"), "ok2", ip(172, 16, 5, 5), 46899, null));
    }

    @Test
    public void theSameServiceFoundTwiceIsOneReceiverAndTheLatestNameWins() {
        found("FCast-pc", 10);
        directory.serviceFound(id("FCast-pc"), "FCast-pc renamed", ip(192, 168, 0, 10), 46899, null);
        assertEquals(1, directory.snapshot().size());
        assertEquals("FCast-pc renamed", directory.snapshot().get(0).getDisplayName());
    }

    @Test
    public void oneReceiverUnderTwoServiceNamesIsOneEntryUntilBothAreGone() {
        found("name-a", 10);
        found("name-b", 10);
        assertEquals(1, directory.snapshot().size());
        directory.serviceLost(id("name-b"));
        assertEquals(1, directory.snapshot().size());
        directory.serviceLost(id("name-a"));
        assertTrue(directory.snapshot().isEmpty());
    }

    @Test
    public void aReceiverThatComesBackAtAnotherAddressIsOneEntryAtTheNewAddress() {
        found("FCast-pc", 10);
        found("FCast-pc", 77);
        List<FCastDirectory.Entry> entries = directory.snapshot();
        assertEquals(1, entries.size());
        assertEquals("192.168.0.77:46899", entries.get(0).getEndpoint().toString());
        assertNull("the old address is gone", directory.find(endpoint("192.168.0.10")));
    }

    @Test
    public void aLostServiceIsRemovedAndALostUnknownOneIsIgnored() {
        found("FCast-pc", 10);
        directory.serviceLost(id("nobody"));
        assertEquals(1, directory.snapshot().size());
        directory.serviceLost(id("FCast-pc"));
        assertTrue(directory.snapshot().isEmpty());
    }

    @Test
    public void namesAreCleanedCutAndAnEmptyNameShowsTheAddress() {
        directory.serviceFound(id("x"), "My\u0000 TV\n‮", ip(192, 168, 0, 1), 46899, null);
        directory.serviceFound(id("y"), "   ", ip(192, 168, 0, 2), 46899, null);
        char[] chars = new char[300];
        Arrays.fill(chars, 'n');
        directory.serviceFound(id("z"), new String(chars), ip(192, 168, 0, 3), 46899, null);
        Map<String, String> byAddress = new HashMap<>();
        for (FCastDirectory.Entry entry : directory.snapshot()) {
            byAddress.put(entry.getEndpoint().toString(), entry.getDisplayName());
        }
        assertEquals("My TV", byAddress.get("192.168.0.1:46899"));
        assertEquals("192.168.0.2:46899", byAddress.get("192.168.0.2:46899"));
        assertEquals(64, byAddress.get("192.168.0.3:46899").length());
    }

    // ---- caps (a hostile network)

    @Test
    public void aFloodOfAnnouncementsDoesNotPushOutTheKnownReceivers() {
        for (int i = 0; i < FCastDirectory.MAX_DISCOVERED; i++) {
            assertTrue(directory.serviceFound(id("r" + i), "r" + i, ip(10, 0, i / 250, i % 250 + 1), 46899, null));
        }
        assertFalse("the 65th is refused", directory.serviceFound(id("extra"), "extra", ip(10, 9, 9, 9), 46899, null));
        assertEquals(FCastDirectory.MAX_DISCOVERED, directory.snapshot().size());
        assertNotNull("the first receiver is still there", directory.find(endpoint("10.0.0.1")));
        directory.serviceLost(id("r3"));
        assertTrue("a place is free again", directory.serviceFound(id("extra"), "extra", ip(10, 9, 9, 9), 46899, null));
    }

    @Test
    public void aKnownReceiverCanStillRenameItselfWhenTheDirectoryIsFull() {
        for (int i = 0; i < FCastDirectory.MAX_DISCOVERED; i++) {
            directory.serviceFound(id("r" + i), "r" + i, ip(10, 0, 0, i + 1), 46899, null);
        }
        assertTrue(directory.serviceFound(id("r0"), "renamed", ip(10, 0, 0, 1), 46899, null));
        assertEquals("renamed", directory.find(endpoint("10.0.0.1")).getDisplayName());
    }

    @Test
    public void theNumberOfRememberedServiceNamesIsCapped() {
        // many names for a few addresses: the name table must not grow without limit
        int accepted = 0;
        for (int i = 0; i < 500; i++) {
            if (directory.serviceFound(id("n" + i), "n" + i, ip(10, 0, 0, i % 8 + 1), 46899, null)) {
                accepted++;
            }
        }
        assertEquals(FCastDirectory.MAX_SERVICE_IDS, accepted);
        assertTrue(directory.snapshot().size() <= 8);
    }

    // ---- manual and remembered

    @Test
    public void aManualReceiverIsListedAndMergesWithTheSameDiscoveredOne() {
        directory.setManual(Collections.singletonList(new FCastDirectory.Known(endpoint("192.168.0.239"), "Living room")));
        FCastDirectory.Entry manual = directory.snapshot().get(0);
        assertTrue(manual.isManual());
        assertFalse(manual.isDiscovered());
        assertFalse("a manual receiver is not shown as offline", manual.isOffline());
        assertEquals("Living room", manual.getDisplayName());

        found("FCast-pc", 239);
        assertEquals(1, directory.snapshot().size());
        FCastDirectory.Entry merged = directory.snapshot().get(0);
        assertTrue(merged.isManual() && merged.isDiscovered());
        assertEquals("the announced name wins", "FCast-pc", merged.getDisplayName());

        directory.serviceLost(id("FCast-pc"));
        assertEquals("the typed name is back", "Living room", directory.snapshot().get(0).getDisplayName());
        directory.setManual(Collections.<FCastDirectory.Known>emptyList());
        assertTrue(directory.snapshot().isEmpty());
    }

    @Test
    public void aManualReceiverWithoutNameShowsItsAddress() {
        directory.setManual(Collections.singletonList(new FCastDirectory.Known(endpoint("tv.local"), "")));
        assertEquals("tv.local:46899", directory.snapshot().get(0).getDisplayName());
    }

    @Test
    public void aRememberedReceiverIsOfflineUntilItIsSeen() {
        directory.setRemembered(Collections.singletonList(new FCastDirectory.Known(endpoint("192.168.0.239"), "Bedroom")));
        FCastDirectory.Entry remembered = directory.snapshot().get(0);
        assertTrue(remembered.isOffline());
        assertEquals("Bedroom", remembered.getDisplayName());
        found("FCast-bedroom", 239);
        assertFalse(directory.snapshot().get(0).isOffline());
        directory.serviceLost(id("FCast-bedroom"));
        assertTrue("remembered again, not gone", directory.snapshot().get(0).isOffline());
        directory.setRemembered(Collections.<FCastDirectory.Known>emptyList());
        assertTrue(directory.snapshot().isEmpty());
    }

    @Test
    public void manualAndRememberedSetsAreIndependent() {
        FCastDirectory.Known known = new FCastDirectory.Known(endpoint("192.168.0.5"), "x");
        directory.setManual(Collections.singletonList(known));
        directory.setRemembered(Collections.singletonList(known));
        directory.setManual(Collections.<FCastDirectory.Known>emptyList());
        assertEquals("still remembered", 1, directory.snapshot().size());
        assertTrue(directory.snapshot().get(0).isOffline());
    }

    // ---- staleness

    @Test
    public void whatARestartedScanDoesNotAnnounceAgainIsDroppedAfterTheGraceTime() {
        found("kept", 1);
        found("gone", 2);
        directory.discoveryRestarted();
        now.addAndGet(10_000);
        found("kept", 1);                  // announced again: confirmed
        assertEquals(2, directory.snapshot().size());
        now.addAndGet(25_000);             // 35 s after the restart
        List<FCastDirectory.Entry> entries = directory.snapshot();
        assertEquals(1, entries.size());
        assertEquals("kept", entries.get(0).getDisplayName());
    }

    @Test
    public void nothingIsDroppedForStalenessWhileNoScanRuns() {
        found("a", 1);
        now.addAndGet(10_000_000);
        assertEquals(1, directory.snapshot().size());
        directory.discoveryRestarted();
        directory.discoveryStopped();      // the scan ended again before anything could confirm
        now.addAndGet(10_000_000);
        assertEquals(1, directory.snapshot().size());
    }

    @Test
    public void staleDiscoveryDoesNotTouchManualOrRememberedReceivers() {
        directory.setManual(Collections.singletonList(new FCastDirectory.Known(endpoint("192.168.0.5"), "m")));
        found("x", 5);
        directory.discoveryRestarted();
        now.addAndGet(60_000);
        List<FCastDirectory.Entry> entries = directory.snapshot();
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).isManual());
        assertFalse(entries.get(0).isDiscovered());
        assertEquals("m", entries.get(0).getDisplayName());
    }

    @Test
    public void switchingTheDiscoveryOffForgetsOnlyWhatItFound() {
        directory.setManual(Collections.singletonList(new FCastDirectory.Known(endpoint("192.168.0.5"), "m")));
        found("x", 6);
        found("y", 5);
        directory.clearDiscovered();
        List<FCastDirectory.Entry> entries = directory.snapshot();
        assertEquals(1, entries.size());
        assertEquals("m", entries.get(0).getDisplayName());
        assertTrue("the table of service names was emptied too: a new announcement is accepted", found("x", 6));
    }

    // ---- listeners

    @Test
    public void listenersAreToldAboutChangesAndMayReadTheDirectory() {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger seen = new AtomicInteger();
        directory.addListener(() -> {
            calls.incrementAndGet();
            seen.set(directory.snapshot().size()); // would deadlock if it were called under the lock
        });
        found("a", 1);
        assertEquals(1, calls.get());
        assertEquals(1, seen.get());
        found("a", 1);
        assertEquals("an announcement that changes nothing is silent", 1, calls.get());
        directory.serviceLost(id("a"));
        assertEquals(2, calls.get());
        directory.setManual(Collections.singletonList(new FCastDirectory.Known(endpoint("192.168.0.9"), "m")));
        assertEquals(3, calls.get());
    }

    @Test
    public void aListenerThatThrowsDoesNotStopTheOthers() {
        final AtomicInteger second = new AtomicInteger();
        directory.addListener(() -> {
            throw new IllegalStateException("bug");
        });
        FCastDirectory.Listener counting = second::incrementAndGet;
        directory.addListener(counting);
        found("a", 1);
        assertEquals(1, second.get());
        directory.removeListener(counting);
        found("b", 2);
        assertEquals(1, second.get());
    }

    // ---- stored lists

    private static final class MemoryStorage implements de.yaacc.multiroom.discovery.SnapcastManualServers.Storage {
        String text;
        boolean failReading;

        @Override
        public String read() {
            if (failReading) {
                throw new IllegalStateException("unreadable");
            }
            return text;
        }

        @Override
        public void write(String value) {
            text = value;
        }
    }

    @Test
    public void storedReceiversRoundTripWithNames() {
        MemoryStorage storage = new MemoryStorage();
        FCastStoredReceivers list = new FCastStoredReceivers(storage, 16);
        assertEquals(FCastStoredReceivers.AddResult.ADDED, list.add("192.168.0.239", "Living\troom\n"));
        assertEquals(FCastStoredReceivers.AddResult.ADDED, list.add(endpoint("[fe80::1]:5000"), ""));
        List<FCastDirectory.Known> items = new FCastStoredReceivers(storage, 16).list();
        assertEquals(2, items.size());
        assertEquals("192.168.0.239:46899", items.get(0).getEndpoint().toString());
        assertEquals("Livingroom", items.get(0).getName());
        assertEquals("", items.get(1).getName());
    }

    @Test
    public void storedReceiversRefuseBadDuplicateAndTooMany() {
        FCastStoredReceivers list = new FCastStoredReceivers(new MemoryStorage(), 3);
        assertEquals(FCastStoredReceivers.AddResult.INVALID, list.add("http://x", "n"));
        assertEquals(FCastStoredReceivers.AddResult.INVALID, list.add((FCastEndpoint) null, "n"));
        assertEquals(FCastStoredReceivers.AddResult.ADDED, list.add("10.0.0.1", "a"));
        assertEquals(FCastStoredReceivers.AddResult.DUPLICATE, list.add("10.0.0.1:46899", "again"));
        assertEquals(FCastStoredReceivers.AddResult.ADDED, list.add("10.0.0.2", "b"));
        assertEquals(FCastStoredReceivers.AddResult.ADDED, list.add("10.0.0.3", "c"));
        assertEquals(FCastStoredReceivers.AddResult.FULL, list.add("10.0.0.4", "d"));
        assertEquals(3, list.list().size());
    }

    @Test
    public void storedReceiversCanBeRemoved() {
        FCastStoredReceivers list = new FCastStoredReceivers(new MemoryStorage(), 16);
        list.add("10.0.0.1", "a");
        list.add("10.0.0.2", "b");
        assertTrue(list.remove(endpoint("10.0.0.1")));
        assertFalse(list.remove(endpoint("10.0.0.1")));
        assertEquals(1, list.list().size());
        assertEquals("10.0.0.2:46899", list.list().get(0).getEndpoint().toString());
    }

    @Test
    public void aDamagedStoredTextIsReadAsFarAsItGoes() {
        MemoryStorage storage = new MemoryStorage();
        storage.text = "10.0.0.1:46899\tgood\n" + "garbage line\n" + "10.0.0.2\n" + "\n" + "10.0.0.1:46899\tdouble\n" + endpoint("[fe80::1]") + "\tv6\n" + "[fe80::1]:46899\tnot canonical\n"
                + "10.0.0.3:46899\t\u0000bad‮name\n";
        List<FCastDirectory.Known> items = new FCastStoredReceivers(storage, 16).list();
        assertEquals("only canonical, first occurrence, readable lines", 3, items.size());
        assertEquals("good", items.get(0).getName());
        assertEquals("v6", items.get(1).getName());
        assertEquals("badname", items.get(2).getName());
    }

    @Test
    public void anUnreadableStorageIsAnEmptyList() {
        MemoryStorage storage = new MemoryStorage();
        storage.failReading = true;
        assertTrue(new FCastStoredReceivers(storage, 16).list().isEmpty());
    }

    @Test
    public void aStoredTextLongerThanTheCapIsReadOnlyUpToTheCap() {
        MemoryStorage storage = new MemoryStorage();
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 100; i++) {
            text.append("10.0.1.").append(i).append(":46899\n");
        }
        storage.text = text.toString();
        assertEquals(16, new FCastStoredReceivers(storage, 16).list().size());
    }

    @Test
    public void replaceAllDropsDuplicatesAndWhatDoesNotFit() {
        MemoryStorage storage = new MemoryStorage();
        FCastStoredReceivers list = new FCastStoredReceivers(storage, 2);
        list.replaceAll(Arrays.asList(new FCastDirectory.Known(endpoint("10.0.0.1"), "a"), null, new FCastDirectory.Known(null, "x"),
                new FCastDirectory.Known(endpoint("10.0.0.1"), "dup"), new FCastDirectory.Known(endpoint("10.0.0.2"), "b"),
                new FCastDirectory.Known(endpoint("10.0.0.3"), "c")));
        assertEquals(2, list.list().size());
        list.replaceAll(Collections.<FCastDirectory.Known>emptyList());
        assertTrue(list.list().isEmpty());
        assertEquals("", storage.text);
    }
}
