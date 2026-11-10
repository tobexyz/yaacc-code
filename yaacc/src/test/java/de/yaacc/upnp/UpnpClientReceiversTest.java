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
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.yaacc.multiroom.SnapcastGroupDevice;
import de.yaacc.multiroom.SnapcastGroupSource;
import de.yaacc.multiroom.control.SnapcastClient;
import de.yaacc.multiroom.control.SnapcastGroup;
import de.yaacc.multiroom.control.SnapcastVolume;
import de.yaacc.multiroom.discovery.SnapcastEndpoint;
import de.yaacc.multiroom.discovery.SnapcastServerKey;
import de.yaacc.upnp.registry.Registry;

/**
 * Snapcast groups in the receiver lists of the client: after the local device in the list, found by id before the
 * registry is asked (they are never in it), and a group that vanished is dropped from the stored selection by the
 * existing {@code getReceiverDevices()} behavior. A list without groups is what it was. Plain JVM, a spy over a
 * client without a context.
 */
public class UpnpClientReceiversTest {

    private static final String KEY = "selected_receivers";
    private static final SnapcastServerKey SERVER = SnapcastEndpoint.parse("192.168.1.5:1705").toKey();

    private static final class FakeGroups implements SnapcastGroupSource {
        final Map<String, SnapcastGroupDevice> byId = new HashMap<>();
        final List<SnapcastGroupDevice> ordered = new ArrayList<>();
        final List<Collection<String>> wakes = new ArrayList<>();

        void add(SnapcastGroupDevice device) {
            ordered.add(device);
            byId.put(device.getIdentity().getUdn().getIdentifierString(), device);
        }

        void remove(SnapcastGroupDevice device) {
            ordered.remove(device);
            byId.remove(device.getIdentity().getUdn().getIdentifierString());
        }

        @Override
        public List<SnapcastGroupDevice> groups() {
            return new ArrayList<>(ordered);
        }

        @Override
        public SnapcastGroupDevice findGroup(String receiverId) {
            return byId.get(receiverId);
        }

        @Override
        public void wake(Collection<String> receiverIds) {
            wakes.add(receiverIds);
        }
    }

    private UpnpClient client;
    private SharedPreferences preferences;
    private SharedPreferences.Editor editor;
    private FakeGroups groups;

    private static SnapcastGroupDevice group(String id, String name) {
        SnapcastClient member = new SnapcastClient("m-" + id, "member", "", true, new SnapcastVolume(50, false), 0);
        SnapcastGroup group = new SnapcastGroup(id, name, false, "Default", Collections.singletonList(member));
        return SnapcastGroupDevice.of(SERVER, group, false, new SnapcastGroupDevice.Labels("Snapcast group", "offline"));
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
        groups = new FakeGroups();
        client.setSnapcastGroups(groups);
    }

    private void stored(String... ids) {
        when(preferences.getStringSet(eq(KEY), any())).thenReturn(new HashSet<>(Arrays.asList(ids)));
    }

    private static String idOf(SnapcastGroupDevice device) {
        return device.getIdentity().getUdn().getIdentifierString();
    }

    @Test
    public void theGroupsFollowTheLocalDeviceInTheReceiverList() {
        SnapcastGroupDevice a = group("g-a", "Attic");
        SnapcastGroupDevice b = group("g-b", "Bath");
        groups.add(a);
        groups.add(b);

        List<Device<?, ?, ?>> list = new ArrayList<>(client.getDevicesProvidingAvTransportService());

        assertEquals(3, list.size());
        assertEquals("the local device stays first", UpnpClient.LOCAL_UID, list.get(0).getIdentity().getUdn().getIdentifierString());
        assertSame(a, list.get(1));
        assertSame(b, list.get(2));
    }

    @Test
    public void aListWithoutGroupsIsWhatItWas() {
        List<Device<?, ?, ?>> list = new ArrayList<>(client.getDevicesProvidingAvTransportService());

        assertEquals(1, list.size());
        assertEquals(UpnpClient.LOCAL_UID, list.get(0).getIdentity().getUdn().getIdentifierString());
    }

    @Test
    public void aClientWithoutAHubHasNoGroups() {
        UpnpClient plain = spy(new UpnpClient());

        assertEquals(1, plain.getDevicesProvidingAvTransportService().size());
        assertNull(plain.getDevice("snapcast:192.168.1.5:1705:g-a"));
    }

    @Test
    public void aGroupIsFoundByItsId() {
        SnapcastGroupDevice a = group("g-a", "Attic");
        groups.add(a);

        assertSame(a, client.getDevice(idOf(a)));
    }

    @Test
    public void aGroupThatIsNotKnownIsNull() {
        assertNull(client.getDevice("snapcast:192.168.1.5:1705:g-zzz"));
        assertNull(client.getDevice("snapcast:garbage"));
    }

    @Test
    public void aGroupIsNeverLookedUpInTheRegistry() {
        Registry registry = mock(Registry.class);
        doReturn(true).when(client).isInitialized();
        doReturn(registry).when(client).getRegistry();
        SnapcastGroupDevice a = group("g-a", "Attic");
        groups.add(a);

        assertSame(a, client.getDevice(idOf(a)));
        assertNull(client.getDevice("snapcast:192.168.1.5:1705:g-zzz"));

        verify(registry, never()).getDevice(any(UDN.class), anyBoolean());
    }

    @Test
    public void otherIdsStillGoToTheRegistry() {
        Registry registry = mock(Registry.class);
        doReturn(true).when(client).isInitialized();
        doReturn(registry).when(client).getRegistry();

        client.getDevice("uuid:1234");

        verify(registry).getDevice(any(UDN.class), eq(false));
    }

    @Test
    public void aGroupThatVanishedIsDroppedFromTheStoredSelectionAndTheOthersStay() {
        SnapcastGroupDevice a = group("g-a", "Attic");
        SnapcastGroupDevice gone = group("g-gone", "Gone");
        groups.add(a);
        groups.add(gone);
        stored(UpnpClient.LOCAL_UID, idOf(a), idOf(gone));
        groups.remove(gone);

        Collection<Device<?, ?, ?>> receivers = client.getReceiverDevices();

        assertEquals(2, receivers.size());
        assertTrue(receivers.contains(a));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> captor = ArgumentCaptor.forClass(Set.class);
        verify(editor).putStringSet(eq(KEY), captor.capture());
        assertEquals(new HashSet<>(Arrays.asList(UpnpClient.LOCAL_UID, idOf(a))), captor.getValue());
    }

    @Test
    public void aSelectedGroupThatStillExistsChangesNothingInTheStore() {
        SnapcastGroupDevice a = group("g-a", "Attic");
        groups.add(a);
        stored(idOf(a));

        assertEquals(1, client.getReceiverDevices().size());

        verify(editor, never()).putStringSet(nullable(String.class), any());
    }

    @Test
    public void aSelectedGroupIsCountedAsReady() {
        SnapcastGroupDevice a = group("g-a", "Attic");
        groups.add(a);
        stored(idOf(a), "snapcast:192.168.1.5:1705:g-gone");

        assertEquals(1, client.getReceiverDevicesReadyCount());
        assertNotNull(client.getDevice(idOf(a)));
    }
}
