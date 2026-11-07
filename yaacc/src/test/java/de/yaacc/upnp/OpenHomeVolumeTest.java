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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.types.ServiceType;
import org.junit.Test;

/** Volume and mute of an OpenHome device without RenderingControl come from its OpenHome volume service. */
public class OpenHomeVolumeTest {

    private static Service<?, ?> service(String type, int version) {
        Service<?, ?> service = mock(Service.class);
        doReturn(new ServiceType("av-openhome-org", type, version)).when(service).getServiceType();
        return service;
    }

    private static Device<?, ?, ?> device(String... types) {
        Device<?, ?, ?> device = mock(Device.class);
        Service<?, ?>[] services = new Service[types.length];
        for (int i = 0; i < types.length; i++) {
            services[i] = service(types[i], 1);
        }
        doReturn(services).when(device).getServices();
        return device;
    }

    @Test
    public void anOpenHomeDeviceWithoutRenderingControlUsesTheOpenHomeVolume() {
        assertTrue(OpenHomeVolume.handles(device("Playlist", "Product", "Volume"), false));
    }

    @Test
    public void aDeviceWithRenderingControlKeepsUsingIt() {
        assertFalse(OpenHomeVolume.handles(device("Playlist", "Product", "Volume"), true));
    }

    @Test
    public void aDeviceWithoutVolumeServiceOrOpenHomeIsNotHandled() {
        assertFalse(OpenHomeVolume.handles(device("Playlist", "Product"), false));
        assertFalse(OpenHomeVolume.handles(device("Volume"), false));
        assertFalse(OpenHomeVolume.handles(null, false));
    }
}
