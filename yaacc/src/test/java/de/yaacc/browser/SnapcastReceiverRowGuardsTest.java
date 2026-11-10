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

import de.yaacc.multiroom.SnapcastGroupDevice;
import de.yaacc.multiroom.control.SnapcastClient;
import de.yaacc.multiroom.control.SnapcastGroup;
import de.yaacc.multiroom.control.SnapcastVolume;
import de.yaacc.multiroom.discovery.SnapcastEndpoint;
import de.yaacc.player.OpenHomeChoice;
import de.yaacc.share.ShareAddressResolver;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.upnp.callback.openhome.ClingOhControl;

/**
 * The places of the receiver rows that look at a device's services must tolerate a Snapcast group, which has none:
 * the status monitor does not poll it, the MusicCast feed leaves it out, the OpenHome checks say no, the share addresses
 * skip it, and the row list keeps it. Plain JVM.
 */
public class SnapcastReceiverRowGuardsTest {

    private static SnapcastGroupDevice group() {
        SnapcastClient member = new SnapcastClient("m", "Kitchen", "", true, new SnapcastVolume(50, false), 0);
        SnapcastGroup group = new SnapcastGroup("g-a", "Ground floor", false, "Default", Collections.singletonList(member));
        return SnapcastGroupDevice.of(SnapcastEndpoint.parse("192.168.1.5").toKey(), group, false,
                new SnapcastGroupDevice.Labels("Snapcast group", "offline"));
    }

    @Test
    public void theStatusMonitorDoesNotPollAGroupButPollsOtherReceivers() {
        assertFalse(RendererStatusMonitor.isPolled(group()));
        assertTrue(RendererStatusMonitor.isPolled(mock(Device.class)));
    }

    @Test
    public void aGroupIsNoLocalDeviceAndHasNoDescriptorUrl() {
        assertFalse(ReceiverRowBinder.isLocalDevice(group()));
        assertTrue(YxcRowFeed.descriptorUrls(Collections.<Device<?, ?, ?>>singletonList(group())).isEmpty());
    }

    @Test
    public void theServiceLookupsOfTheRowsFindNothingAndDoNotFail() {
        Device<?, ?, ?> device = group();

        assertNull(device.findService(new UDAServiceId("AVTransport")));
        assertNull(device.findService(new UDAServiceId("RenderingControl")));
        assertFalse(OpenHomeChoice.offersOpenHome(device));
        assertNull(ClingOhControl.findService(device, "Playlist"));
        assertFalse(ClingOhControl.offersPlaylist(device));
        assertEquals(0, device.findServiceTypes().length);
    }

    @Test
    public void theShareAddressesSkipAGroup() {
        UpnpClient upnpClient = mock(UpnpClient.class);
        SnapcastGroupDevice group = group();
        String id = group.getIdentity().getUdn().getIdentifierString();
        when(upnpClient.getDevice(anyString())).thenReturn((Device) group);

        Set<String> addresses = ShareAddressResolver.resolve(upnpClient, mock(Context.class), Collections.singleton(id));

        assertTrue("a group needs no share: no address of it", addresses.isEmpty());
    }

    @Test
    public void theReceiverListWatchesGroupsLikeAnyReceiver() {
        List<Device<?, ?, ?>> receivers = Arrays.<Device<?, ?, ?>>asList(group(), group());

        assertEquals(2, ReceiverLiveUpdates.watched(receivers).size());
    }
}
