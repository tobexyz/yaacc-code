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
package de.yaacc.upnp;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import org.fourthline.cling.model.meta.Device;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

/**
 * {@link UpnpClient#clearReceiverDevices()} for real (a spy over a client that needs no context):
 * the stored selection becomes EMPTY and a "receiver removed" event is fired for every receiver that
 * was selected, after the set is stored, because the views that follow the events read it back.
 * Nothing else (players, shares) is touched.
 */
public class UpnpClientClearReceiversTest {

    private UpnpClient client;
    private UpnpClientListener listener;
    private Device<?, ?, ?> tv;
    private Device<?, ?, ?> phone;

    @Before
    public void setUp() {
        client = spy(new UpnpClient());
        listener = mock(UpnpClientListener.class);
        client.addUpnpClientListener(listener);
        tv = mock(Device.class);
        phone = mock(Device.class);
        doNothing().when(client).setReceiverDeviceIds(any());
    }

    @Test
    public void theStoredSelectionBecomesEmpty() {
        doReturn(Arrays.asList(tv, phone)).when(client).getReceiverDevices();

        client.clearReceiverDevices();

        ArgumentCaptor<Set<String>> stored = ArgumentCaptor.forClass(Set.class);
        verify(client).setReceiverDeviceIds(stored.capture());
        assertTrue("an empty set is stored, not the local-device default", stored.getValue().isEmpty());
    }

    @Test
    public void everyPreviouslySelectedReceiverIsReportedRemoved() {
        doReturn(Arrays.asList(tv, phone)).when(client).getReceiverDevices();

        client.clearReceiverDevices();

        verify(listener).receiverDeviceRemoved(tv);
        verify(listener).receiverDeviceRemoved(phone);
        verify(listener, never()).receiverDeviceAdded(any());
    }

    @Test
    public void theSetIsStoredBeforeTheEventsAreFired() {
        doReturn(Collections.singletonList(tv)).when(client).getReceiverDevices();

        client.clearReceiverDevices();

        InOrder order = inOrder(client, listener);
        order.verify(client).setReceiverDeviceIds(any());
        order.verify(listener).receiverDeviceRemoved(tv);
    }

    @Test
    public void clearingAnEmptySelectionFiresNothingAndStaysEmpty() {
        doReturn(Collections.emptyList()).when(client).getReceiverDevices();

        client.clearReceiverDevices();

        ArgumentCaptor<Set<String>> stored = ArgumentCaptor.forClass(Set.class);
        verify(client).setReceiverDeviceIds(stored.capture());
        assertTrue(stored.getValue().isEmpty());
        verify(listener, never()).receiverDeviceRemoved(any());
    }
}
