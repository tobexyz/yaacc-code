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
package de.yaacc.upnp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.SharedPreferences;

import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.types.UDN;

import org.junit.Before;
import org.junit.Test;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;

import de.yaacc.fcast.discovery.FCastDirectory;
import de.yaacc.fcast.discovery.FCastReceiverDevice;
import de.yaacc.fcast.discovery.FCastReceiverSource;
import de.yaacc.fcast.discovery.FCastReceivers;
import de.yaacc.fcast.discovery.FCastStoredReceivers;
import de.yaacc.multiroom.discovery.SnapcastManualServers;
import de.yaacc.upnp.registry.Registry;

/** Task 3.4: the FCast receivers in the receiver list of {@link UpnpClient}, next to the local device and the Snapcast groups. */
public class UpnpClientFCastReceiversTest {
    private static final String KEY = "selected_receivers";

    private static final class FakeReceivers implements FCastReceiverSource {
        final List<FCastReceiverDevice> list = new ArrayList<>();
        final List<Collection<String>> selections = new ArrayList<>();

        @Override
        public List<FCastReceiverDevice> receivers() {
            return list;
        }

        @Override
        public FCastReceiverDevice findReceiver(String receiverId) {
            for (FCastReceiverDevice device : list) {
                if (device.getReceiverId().equals(receiverId)) {
                    return device;
                }
            }
            return null;
        }

        @Override
        public void selectionChanged(Collection<String> receiverIds) {
            selections.add(new HashSet<>(receiverIds));
        }
    }

    private UpnpClient client;
    private SharedPreferences preferences;
    private SharedPreferences.Editor editor;
    private FakeReceivers fake;

    private static FCastReceiverDevice device(String name, int last) throws Exception {
        FCastDirectory directory = new FCastDirectory(() -> 0L, 30_000);
        directory.serviceFound("_fcast._tcp|" + name, name, InetAddress.getByAddress(new byte[]{(byte) 192, (byte) 168, 0, (byte) last}), 46899, null);
        return FCastReceiverDevice.of(directory.snapshot().get(0), new FCastReceiverDevice.Labels("offline"));
    }

    @Before
    public void setUp() {
        Context context = mock(Context.class);
        when(context.getString(anyInt())).thenReturn(KEY);
        preferences = mock(SharedPreferences.class);
        editor = mock(SharedPreferences.Editor.class);
        when(preferences.edit()).thenReturn(editor);
        when(editor.putStringSet(nullable(String.class), any())).thenReturn(editor);
        client = spy(new UpnpClient());
        client.preferences = preferences;
        doReturn(context).when(client).getContext();
        fake = new FakeReceivers();
        client.setFCastReceivers(fake);
    }

    private void stored(String... ids) {
        when(preferences.getStringSet(eq(KEY), any())).thenReturn(new HashSet<>(Arrays.asList(ids)));
    }

    @Test
    public void theReceiversFollowTheLocalDeviceInTheList() throws Exception {
        FCastReceiverDevice a = device("Attic", 1);
        FCastReceiverDevice b = device("Bath", 2);
        fake.list.add(a);
        fake.list.add(b);
        List<Device<?, ?, ?>> list = new ArrayList<>(client.getDevicesProvidingAvTransportService());
        assertEquals(3, list.size());
        assertEquals(UpnpClient.LOCAL_UID, list.get(0).getIdentity().getUdn().getIdentifierString());
        assertSame(a, list.get(1));
        assertSame(b, list.get(2));
    }

    @Test
    public void aListWithoutFCastReceiversIsWhatItWas() {
        List<Device<?, ?, ?>> list = new ArrayList<>(client.getDevicesProvidingAvTransportService());
        assertEquals(1, list.size());
        assertEquals(UpnpClient.LOCAL_UID, list.get(0).getIdentity().getUdn().getIdentifierString());
    }

    @Test
    public void aClientWithoutAHubHasNoReceivers() {
        UpnpClient plain = spy(new UpnpClient());
        assertEquals(1, plain.getDevicesProvidingAvTransportService().size());
        assertNull(plain.getDevice("fcast:192.168.0.1:46899"));
    }

    @Test
    public void aReceiverIsFoundByItsIdAndAnUnknownOrBrokenOneIsNull() throws Exception {
        FCastReceiverDevice a = device("Attic", 1);
        fake.list.add(a);
        assertSame(a, client.getDevice("fcast:192.168.0.1:46899"));
        assertNull(client.getDevice("fcast:192.168.0.99:46899"));
        assertNull(client.getDevice("fcast:garbage"));
    }

    @Test
    public void aReceiverIsNeverLookedUpInTheRegistry() throws Exception {
        Registry registry = mock(Registry.class);
        doReturn(true).when(client).isInitialized();
        doReturn(registry).when(client).getRegistry();
        fake.list.add(device("Attic", 1));
        assertNotNull(client.getDevice("fcast:192.168.0.1:46899"));
        assertNull(client.getDevice("fcast:192.168.0.99:46899"));
        verify(registry, never()).getDevice(any(UDN.class), anyBoolean());
    }

    @Test
    public void theSelectionIsToldToTheSource() {
        client.setReceiverDeviceIds(new HashSet<>(Arrays.asList("fcast:192.168.0.1:46899", UpnpClient.LOCAL_UID)));
        assertEquals(1, fake.selections.size());
        assertTrue(fake.selections.get(0).contains("fcast:192.168.0.1:46899"));
    }

    @Test
    public void aSelectedReceiverThatTheSourceKnowsStaysSelectedAndAnUnknownOneIsDropped() throws Exception {
        fake.list.add(device("Attic", 1));
        stored("fcast:192.168.0.1:46899", "fcast:192.168.0.77:46899");
        Collection<Device<?, ?, ?>> selected = client.getReceiverDevices();
        assertEquals(1, selected.size());
        assertEquals("fcast:192.168.0.1:46899", selected.iterator().next().getIdentity().getUdn().getIdentifierString());
    }

    @Test
    public void aSelectedReceiverSurvivesARestartBecauseItIsRemembered() throws Exception {
        // the selection is stored; after a restart the directory is empty, but the remembered receiver is restored into it
        final String[] text = new String[1];
        SnapcastManualServers.Storage storage = new SnapcastManualServers.Storage() {
            @Override
            public String read() {
                return text[0];
            }

            @Override
            public void write(String value) {
                text[0] = value;
            }
        };
        FCastDirectory first = new FCastDirectory(() -> 0L, 30_000);
        first.serviceFound("_fcast._tcp|Attic", "Attic", InetAddress.getByAddress(new byte[]{(byte) 192, (byte) 168, 0, 1}), 46899, null);
        FCastReceivers beforeRestart = new FCastReceivers(first, () -> new FCastReceiverDevice.Labels("offline"), new FCastStoredReceivers(storage, 16));
        client.setFCastReceivers(beforeRestart);
        client.setReceiverDeviceIds(new HashSet<>(Arrays.asList("fcast:192.168.0.1:46899")));

        FCastReceivers afterRestart = new FCastReceivers(new FCastDirectory(() -> 0L, 30_000), () -> new FCastReceiverDevice.Labels("offline"),
                new FCastStoredReceivers(storage, 16));
        afterRestart.restore();
        client.setFCastReceivers(afterRestart);
        stored("fcast:192.168.0.1:46899");
        Collection<Device<?, ?, ?>> selected = client.getReceiverDevices();
        assertEquals("not dropped as unknown", 1, selected.size());
        assertTrue(((FCastReceiverDevice) selected.iterator().next()).isOffline());
    }

    @Test
    public void aNullSourceFallsBackToNone() {
        client.setFCastReceivers(null);
        assertEquals(1, client.getDevicesProvidingAvTransportService().size());
        assertNull(client.getDevice("fcast:192.168.0.1:46899"));
    }
}
