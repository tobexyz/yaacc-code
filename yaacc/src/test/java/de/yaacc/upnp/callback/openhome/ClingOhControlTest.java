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
package de.yaacc.upnp.callback.openhome;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.types.ServiceType;
import org.junit.Test;

/** Finding the OpenHome services of a device; a device may have no services at all (Cling answers null then). */
public class ClingOhControlTest {

    private static Service<?, ?> service(String namespace, String type, int version) {
        Service<?, ?> service = mock(Service.class);
        when(service.getServiceType()).thenReturn(new ServiceType(namespace, type, version));
        return service;
    }

    @Test
    public void aDeviceWithoutServicesOffersNothingAndDoesNotCrash() {
        Device<?, ?, ?> device = mock(Device.class);
        doReturn(null).when(device).getServices();

        assertNull(ClingOhControl.findService(device, "Playlist"));
        assertFalse(ClingOhControl.offersPlaylist(device));
    }

    @Test
    public void aDeviceWithAnEmptyServiceListOffersNothing() {
        Device<?, ?, ?> device = mock(Device.class);
        doReturn(new Service[0]).when(device).getServices();

        assertFalse(ClingOhControl.offersPlaylist(device));
    }

    @Test
    public void aMissingDeviceOffersNothing() {
        assertNull(ClingOhControl.findService(null, "Playlist"));
    }

    @Test
    public void playlistAndProductTogetherMakeAnOpenHomeDevice() {
        Device<?, ?, ?> device = mock(Device.class);
        doReturn(new Service[]{
                service("av-openhome-org", "Playlist", 1), service("av-openhome-org", "Product", 2)}).when(device).getServices();

        assertTrue(ClingOhControl.offersPlaylist(device));
    }

    @Test
    public void aPlaylistWithoutProductIsNotEnoughAndOtherNamespacesDoNotCount() {
        Device<?, ?, ?> device = mock(Device.class);
        doReturn(new Service[]{
                service("av-openhome-org", "Playlist", 1), service("schemas-upnp-org", "Product", 2)}).when(device).getServices();

        assertFalse(ClingOhControl.offersPlaylist(device));
    }

    @Test
    public void theHighestVersionOfAServiceIsUsed() {
        Service<?, ?> v1 = service("av-openhome-org", "Playlist", 1);
        Service<?, ?> v2 = service("av-openhome-org", "Playlist", 2);
        Device<?, ?, ?> device = mock(Device.class);
        doReturn(new Service[]{v1, v2}).when(device).getServices();

        assertSame(v2, ClingOhControl.findService(device, "Playlist"));
    }

    @Test
    public void aServiceWithoutTypeIsSkipped() {
        Service<?, ?> broken = mock(Service.class);
        Device<?, ?, ?> device = mock(Device.class);
        doReturn(new Service[]{broken, null}).when(device).getServices();

        assertNull(ClingOhControl.findService(device, "Playlist"));
    }
}
