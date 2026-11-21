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
package de.yaacc.browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;

import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.types.UDAServiceId;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import de.yaacc.fcast.discovery.FCastDirectory;
import de.yaacc.fcast.discovery.FCastEndpoint;
import de.yaacc.fcast.discovery.FCastReceiverDevice;
import de.yaacc.player.OpenHomeChoice;
import de.yaacc.share.ShareAddressResolver;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.upnp.callback.openhome.ClingOhControl;

/**
 * Task 4.5: the places of the app that look at a device's services or description must tolerate an FCast receiver, which has
 * none (it is a synthetic device that is never registered): the status monitor does not poll it, a share is allowed for its
 * address, the OpenHome checks say no, and the rows keep it. Plain JVM.
 */
public class FCastReceiverRowGuardsTest {

    private static FCastReceiverDevice receiver(String endpoint) {
        FCastDirectory directory = new FCastDirectory(() -> 0L, 30_000);
        directory.setManual(Collections.singletonList(new FCastDirectory.Known(FCastEndpoint.parse(endpoint), "Living room")));
        return FCastReceiverDevice.of(directory.snapshot().get(0), null);
    }

    @Test
    public void theStatusMonitorDoesNotPollAnFCastReceiverButPollsOtherReceivers() {
        assertFalse(RendererStatusMonitor.isPolled(receiver("192.168.0.239")));
        assertTrue(RendererStatusMonitor.isPolled(mock(Device.class)));
    }

    @Test
    public void aShareForAnFCastReceiverIsAllowedForItsAddress() {
        UpnpClient upnpClient = mock(UpnpClient.class);
        FCastReceiverDevice device = receiver("192.168.0.239:46899");
        String id = device.getIdentity().getUdn().getIdentifierString();
        when(upnpClient.getDevice(anyString())).thenReturn((Device) device);

        Set<String> addresses = ShareAddressResolver.resolve(upnpClient, mock(Context.class), Collections.singleton(id));

        assertEquals(Collections.singleton("192.168.0.239"), addresses);
    }

    @Test
    public void aShareForAnFCastReceiverWithAnIpv6AddressIsAllowedForTheBareAddress() {
        UpnpClient upnpClient = mock(UpnpClient.class);
        FCastReceiverDevice device = receiver("[2001:db8::5]:46899");
        String id = device.getIdentity().getUdn().getIdentifierString();
        when(upnpClient.getDevice(anyString())).thenReturn((Device) device);

        Set<String> addresses = ShareAddressResolver.resolve(upnpClient, mock(Context.class), Collections.singleton(id));

        assertEquals(1, addresses.size());
        assertTrue(addresses.iterator().next().contains("2001:db8"));
        assertFalse(addresses.iterator().next().contains("["));
    }

    @Test
    public void anFCastReceiverIsNoLocalDeviceAndHasNoDescriptorUrl() {
        assertFalse(ReceiverRowBinder.isLocalDevice(receiver("192.168.0.239")));
        assertTrue(YxcRowFeed.descriptorUrls(Collections.<Device<?, ?, ?>>singletonList(receiver("192.168.0.239"))).isEmpty());
    }

    @Test
    public void theServiceLookupsOfTheRowsFindNothingAndDoNotFail() {
        Device<?, ?, ?> device = receiver("192.168.0.239");

        assertNull(device.findService(new UDAServiceId("AVTransport")));
        assertNull(device.findService(new UDAServiceId("RenderingControl")));
        assertFalse(OpenHomeChoice.offersOpenHome(device));
        assertNull(ClingOhControl.findService(device, "Playlist"));
        assertFalse(ClingOhControl.offersPlaylist(device));
        assertEquals(0, device.findServiceTypes().length);
    }

    @Test
    public void theReceiverListWatchesFCastReceiversLikeAnyReceiver() {
        List<Device<?, ?, ?>> receivers = Arrays.<Device<?, ?, ?>>asList(receiver("192.168.0.1"), receiver("192.168.0.2"));

        assertEquals(2, ReceiverLiveUpdates.watched(receivers).size());
    }
}
