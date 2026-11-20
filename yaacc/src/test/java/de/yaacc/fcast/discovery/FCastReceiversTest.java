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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import de.yaacc.multiroom.discovery.SnapcastManualServers;

/** Task 3.4: the device of a receiver, the list source and the memory of the selected receivers. */
public class FCastReceiversTest {

    private static final class MemoryStorage implements SnapcastManualServers.Storage {
        String text;
        final AtomicInteger writes = new AtomicInteger();

        @Override
        public String read() {
            return text;
        }

        @Override
        public void write(String value) {
            writes.incrementAndGet();
            text = value;
        }
    }

    private final FCastDirectory directory = new FCastDirectory(() -> 0L, 30_000);
    private final MemoryStorage storage = new MemoryStorage();
    private final FCastReceivers receivers = new FCastReceivers(directory, () -> new FCastReceiverDevice.Labels("offline"),
            new FCastStoredReceivers(storage, 16));

    private static InetAddress ip(int last) {
        try {
            return InetAddress.getByAddress(new byte[]{(byte) 192, (byte) 168, 0, (byte) last});
        } catch (java.net.UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private void found(String name, int last) {
        directory.serviceFound("_fcast._tcp|" + name, name, ip(last), 46899, null);
    }

    private static FCastEndpoint endpoint(String text) {
        return FCastEndpoint.parse(text);
    }

    // ---- the device

    @Test
    public void aDiscoveredReceiverIsADeviceWithTheProductNoteInItsName() {
        found("FCast-pc", 239);
        List<FCastReceiverDevice> list = receivers.receivers();
        assertEquals(1, list.size());
        FCastReceiverDevice device = list.get(0);
        assertEquals("fcast:192.168.0.239:46899", device.getIdentity().getUdn().getIdentifierString());
        assertEquals("fcast:192.168.0.239:46899", device.getReceiverId());
        assertEquals("FCast-pc (FCast)", device.getDisplayString());
        assertEquals("FCast-pc (FCast)", device.getDetails().getFriendlyName());
        assertEquals("FCast-pc", device.getName());
        assertEquals("192.168.0.239:46899", device.getEndpoint().toString());
        assertFalse(device.isOffline());
        assertFalse(device.isManual());
    }

    @Test
    public void theDeviceHasNoServicesAndNoRoot() {
        found("x", 1);
        FCastReceiverDevice device = receivers.receivers().get(0);
        assertNull(device.getServices());
        assertNull(device.getRoot());
        assertNull(device.getEmbeddedDevices());
        assertNull(device.findDevice(device.getIdentity().getUdn()));
    }

    @Test
    public void twoSnapshotsOfOneReceiverAreEqualEvenWhenItIsRenamed() {
        found("old name", 5);
        FCastReceiverDevice before = receivers.receivers().get(0);
        found("new name", 5);
        FCastReceiverDevice after = receivers.receivers().get(0);
        assertEquals(before, after);
        assertEquals(before.hashCode(), after.hashCode());
        assertEquals("new name (FCast)", after.getDisplayString());
        found("other", 6);
        assertNotEquals(before, receivers.findReceiver("fcast:192.168.0.6:46899"));
    }

    @Test
    public void aRememberedReceiverIsShownAsOffline() {
        directory.setRemembered(Collections.singletonList(new FCastDirectory.Known(endpoint("192.168.0.9"), "Bedroom")));
        FCastReceiverDevice device = receivers.receivers().get(0);
        assertTrue(device.isOffline());
        assertEquals("Bedroom (FCast, offline)", device.getDisplayString());
    }

    @Test
    public void aManualReceiverIsMarkedAndNamedByItsAddressWithoutAName() {
        directory.setManual(Collections.singletonList(new FCastDirectory.Known(endpoint("tv.local"), "")));
        FCastReceiverDevice device = receivers.receivers().get(0);
        assertTrue(device.isManual());
        assertFalse(device.isOffline());
        assertEquals("tv.local:46899 (FCast)", device.getDisplayString());
    }

    @Test
    public void noDeviceIsMadeFromNothing() {
        assertNull(FCastReceiverDevice.of(null, null));
    }

    @Test
    public void aMissingLabelFallsBackToOffline() {
        directory.setRemembered(Collections.singletonList(new FCastDirectory.Known(endpoint("192.168.0.9"), "x")));
        FCastReceiverDevice device = FCastReceiverDevice.of(directory.snapshot().get(0), null);
        assertEquals("x (FCast, offline)", device.getDisplayString());
        assertEquals("a null label text too", "x (FCast, offline)",
                FCastReceiverDevice.of(directory.snapshot().get(0), new FCastReceiverDevice.Labels(null)).getDisplayString());
    }

    @Test
    public void theTextOfADeviceNamesNeitherTheAddressNorTheId() {
        found("secret-name", 77);
        String text = receivers.receivers().get(0).toString();
        assertFalse(text, text.contains("192.168"));
        assertFalse(text, text.contains("secret-name"));
    }

    // ---- the list source

    @Test
    public void theListIsSortedByNameAndFindFollowsTheId() {
        found("zeta", 3);
        found("Alpha", 1);
        List<String> names = new ArrayList<>();
        for (FCastReceiverDevice device : receivers.receivers()) {
            names.add(device.getName());
        }
        assertEquals(Arrays.asList("Alpha", "zeta"), names);
        assertNotNull(receivers.findReceiver("fcast:192.168.0.3:46899"));
        assertNull(receivers.findReceiver("fcast:192.168.0.99:46899"));
        assertNull(receivers.findReceiver("fcast:garbage"));
        assertNull(receivers.findReceiver("snapcast:192.168.0.3:1705:g"));
        assertNull(receivers.findReceiver(null));
    }

    @Test
    public void theNoneSourceHasNoReceivers() {
        assertTrue(FCastReceiverSource.NONE.receivers().isEmpty());
        assertNull(FCastReceiverSource.NONE.findReceiver("fcast:192.168.0.3:46899"));
        FCastReceiverSource.NONE.selectionChanged(Collections.singleton("fcast:192.168.0.3:46899"));
    }

    // ---- remembering the selection

    @Test
    public void aSelectedReceiverIsRememberedWithItsNameAndComesBackAsOfflineAfterARestart() {
        found("FCast-pc", 239);
        receivers.selectionChanged(new HashSet<>(Arrays.asList("fcast:192.168.0.239:46899", "uuid:some-renderer", "local")));
        assertEquals("192.168.0.239:46899\tFCast-pc", storage.text);

        // a restart: a new directory, the same storage; nothing was scanned yet
        FCastDirectory fresh = new FCastDirectory(() -> 0L, 30_000);
        FCastReceivers restarted = new FCastReceivers(fresh, () -> new FCastReceiverDevice.Labels("offline"), new FCastStoredReceivers(storage, 16));
        assertTrue(restarted.receivers().isEmpty());
        restarted.restore();
        FCastReceiverDevice device = restarted.findReceiver("fcast:192.168.0.239:46899");
        assertNotNull("the selected id still matches a device, so the selection is not dropped", device);
        assertTrue(device.isOffline());
        assertEquals("FCast-pc (FCast, offline)", device.getDisplayString());
    }

    @Test
    public void aDeselectedReceiverIsForgotten() {
        found("FCast-pc", 239);
        receivers.selectionChanged(Collections.singleton("fcast:192.168.0.239:46899"));
        directory.serviceLost("_fcast._tcp|FCast-pc");
        assertEquals("remembered, so still listed (offline)", 1, receivers.receivers().size());
        receivers.selectionChanged(Collections.<String>emptySet());
        assertEquals("", storage.text);
        assertTrue(receivers.receivers().isEmpty());
    }

    @Test
    public void theSameSelectionIsNotWrittenAgain() {
        found("FCast-pc", 239);
        receivers.selectionChanged(Collections.singleton("fcast:192.168.0.239:46899"));
        int writes = storage.writes.get();
        receivers.selectionChanged(Collections.singleton("fcast:192.168.0.239:46899"));
        receivers.selectionChanged(new HashSet<>(Arrays.asList("fcast:192.168.0.239:46899", "uuid:other")));
        assertEquals(writes, storage.writes.get());
    }

    @Test
    public void aSelectedReceiverWithoutNameKeepsTheNameItHadBefore() {
        found("FCast-pc", 239);
        receivers.selectionChanged(Collections.singleton("fcast:192.168.0.239:46899"));
        directory.serviceLost("_fcast._tcp|FCast-pc");
        receivers.selectionChanged(Collections.singleton("fcast:192.168.0.239:46899"));
        assertEquals("192.168.0.239:46899\tFCast-pc", storage.text);
    }

    @Test
    public void aSelectedReceiverThatWasNeverNamedIsRememberedByItsAddress() {
        receivers.selectionChanged(Collections.singleton("fcast:192.168.0.50:46899"));
        assertEquals("192.168.0.50:46899", storage.text);
        assertEquals("192.168.0.50:46899 (FCast, offline)", receivers.receivers().get(0).getDisplayString());
    }

    @Test
    public void garbageIdsAndNullAreIgnored() {
        receivers.selectionChanged(null);
        receivers.selectionChanged(new HashSet<>(Arrays.asList("fcast:", "fcast:not an endpoint", "fcast:192.168.0.1:99999", null, "")));
        assertNull(storage.text);
        assertTrue(receivers.receivers().isEmpty());
    }

    @Test
    public void onlySixteenSelectedReceiversAreRemembered() {
        HashSet<String> ids = new HashSet<>();
        for (int i = 1; i <= 30; i++) {
            ids.add("fcast:10.0.0." + i + ":46899");
        }
        receivers.selectionChanged(ids);
        assertEquals(16, new FCastStoredReceivers(storage, 16).list().size());
    }
}
