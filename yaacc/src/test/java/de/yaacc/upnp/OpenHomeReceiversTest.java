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

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.DeviceIdentity;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.types.ServiceType;
import org.fourthline.cling.model.types.UDN;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Devices that speak OpenHome only are receivers, the ones that are listed already are not listed twice. */
public class OpenHomeReceiversTest {

    private static Service<?, ?> service(String namespace, String type, int version) {
        Service<?, ?> service = mock(Service.class);
        doReturn(new ServiceType(namespace, type, version)).when(service).getServiceType();
        return service;
    }

    private static Device<?, ?, ?> device(String udn, boolean openHome, boolean avTransport) throws Exception {
        Device<?, ?, ?> device = mock(Device.class);
        doReturn(new DeviceIdentity(new UDN(udn))).when(device).getIdentity();
        List<Service<?, ?>> services = new java.util.ArrayList<>();
        if (openHome) {
            services.add(service("av-openhome-org", "Playlist", 1));
            services.add(service("av-openhome-org", "Product", 2));
        }
        doReturn(services.toArray(new Service[0])).when(device).getServices();
        doReturn(avTransport ? service("schemas-upnp-org", "AVTransport", 1) : null).when(device).findService(
                org.mockito.ArgumentMatchers.any(ServiceType.class));
        return device;
    }

    @Test
    public void aDeviceThatSpeaksOpenHomeOnlyIsAdded() throws Exception {
        Device<?, ?, ?> only = device("only", true, false);

        List<Device<?, ?, ?>> added = OpenHomeReceivers.onlyOpenHome(Collections.singletonList(only), Collections.emptyList());

        assertEquals(Collections.singletonList(only), added);
    }

    @Test
    public void aDeviceWithAvTransportIsNotAddedAgain() throws Exception {
        Device<?, ?, ?> both = device("both", true, true);

        assertEquals(0, OpenHomeReceivers.onlyOpenHome(Collections.singletonList(both), Collections.emptyList()).size());
    }

    @Test
    public void aDeviceThatIsListedAlreadyIsNotListedTwice() throws Exception {
        Device<?, ?, ?> listed = device("same", true, false);
        Device<?, ?, ?> again = device("same", true, false);

        assertEquals(0, OpenHomeReceivers.onlyOpenHome(Collections.singletonList(again), Collections.singletonList(listed)).size());
    }

    @Test
    public void aDeviceTwiceInTheCandidatesIsAddedOnce() throws Exception {
        Device<?, ?, ?> a = device("twice", true, false);
        Device<?, ?, ?> b = device("twice", true, false);

        assertEquals(1, OpenHomeReceivers.onlyOpenHome(Arrays.asList(a, b), Collections.emptyList()).size());
    }

    @Test
    public void aDeviceWithoutTheServicesTheOpenHomePlayerNeedsIsNotAdded() throws Exception {
        Device<?, ?, ?> none = device("none", false, false);

        assertEquals(0, OpenHomeReceivers.onlyOpenHome(Collections.singletonList(none), Collections.emptyList()).size());
    }
}
