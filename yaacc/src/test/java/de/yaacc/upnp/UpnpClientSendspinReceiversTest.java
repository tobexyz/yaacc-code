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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.content.Context;

import de.yaacc.sendspin.SendspinAddress;
import de.yaacc.sendspin.SendspinReceiverDevice;
import de.yaacc.sendspin.SendspinReceiverSource;

import org.fourthline.cling.model.meta.Device;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;

/** The Sendspin players in the receiver lists of the client: after the local device, found by id, never in the registry. */
public class UpnpClientSendspinReceiversTest {

    private static final class FakeReceivers implements SendspinReceiverSource {
        final List<SendspinReceiverDevice> list = new ArrayList<>();
        final List<Collection<String>> selections = new ArrayList<>();

        @Override
        public List<SendspinReceiverDevice> receivers() {
            return list;
        }

        @Override
        public SendspinReceiverDevice findReceiver(String receiverId) {
            for (SendspinReceiverDevice device : list) {
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
    private FakeReceivers fake;

    private static SendspinReceiverDevice device(String address, String name) {
        return SendspinReceiverDevice.of(SendspinAddress.parse(address), name);
    }

    @Before
    public void setUp() {
        Context context = mock(Context.class);
        when(context.getString(anyInt())).thenReturn("selected_receivers");
        client = spy(new UpnpClient());
        android.content.SharedPreferences preferences = mock(android.content.SharedPreferences.class);
        android.content.SharedPreferences.Editor editor = mock(android.content.SharedPreferences.Editor.class);
        when(preferences.edit()).thenReturn(editor);
        when(editor.putStringSet(org.mockito.ArgumentMatchers.nullable(String.class), org.mockito.ArgumentMatchers.any())).thenReturn(editor);
        client.preferences = preferences;
        doReturn(context).when(client).getContext();
        fake = new FakeReceivers();
        client.setSendspinReceivers(fake);
    }

    @Test
    public void thePlayersFollowTheLocalDeviceInTheList() {
        SendspinReceiverDevice a = device("a.local", "Attic");
        SendspinReceiverDevice b = device("b.local", "Bath");
        fake.list.add(a);
        fake.list.add(b);
        List<Device<?, ?, ?>> list = new ArrayList<>(client.getDevicesProvidingAvTransportService());
        assertEquals(3, list.size());
        assertEquals(UpnpClient.LOCAL_UID, list.get(0).getIdentity().getUdn().getIdentifierString());
        assertSame(a, list.get(1));
        assertSame(b, list.get(2));
    }

    @Test
    public void aClientWithoutAHubHasNoPlayersAndIsWhatItWas() {
        UpnpClient plain = spy(new UpnpClient());
        assertEquals(1, plain.getDevicesProvidingAvTransportService().size());
        assertNull(plain.getDevice("sendspin:a.local:8928"));
    }

    @Test
    public void aPlayerIsFoundByItsIdAndAnUnknownOrBrokenOneIsNull() {
        SendspinReceiverDevice a = device("a.local", "Attic");
        fake.list.add(a);
        assertSame(a, client.getDevice("sendspin:a.local:8928"));
        assertNull(client.getDevice("sendspin:other.local:8928"));
        assertNull(client.getDevice("sendspin:bad host"));
    }

    @Test
    public void theSelectionIsToldToTheSource() {
        client.setReceiverDeviceIds(new HashSet<>(Arrays.asList("sendspin:a.local:8928", UpnpClient.LOCAL_UID)));
        assertEquals(1, fake.selections.size());
        assertTrue(fake.selections.get(0).contains("sendspin:a.local:8928"));
    }
}
