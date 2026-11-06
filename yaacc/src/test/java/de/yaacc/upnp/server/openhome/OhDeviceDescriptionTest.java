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
package de.yaacc.upnp.server.openhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.fourthline.cling.binding.xml.UDA10DeviceDescriptorBinderImpl;
import org.fourthline.cling.binding.xml.UDA10ServiceDescriptorBinderImpl;
import org.fourthline.cling.model.Namespace;
import org.fourthline.cling.model.meta.DeviceDetails;
import org.fourthline.cling.model.meta.DeviceIdentity;
import org.fourthline.cling.model.meta.LocalDevice;
import org.fourthline.cling.model.meta.LocalService;
import org.fourthline.cling.model.profile.RemoteClientInfo;
import org.fourthline.cling.model.types.UDADeviceType;
import org.fourthline.cling.model.types.UDN;
import org.junit.Test;

import java.util.List;

/**
 * What a control point reads from the device: the description lists the five OpenHome services with the type and id OpenHome
 * control points look for, and every service has a description of its own. Plain JVM.
 */
public class OhDeviceDescriptionTest {

    private static LocalDevice device(OhRendererCore core) throws Exception {
        List<LocalService<?>> services = core.buildServices(1000);
        return new LocalDevice(new DeviceIdentity(new UDN("test-device")), new UDADeviceType("MediaRenderer"),
                new DeviceDetails("test renderer"), services.toArray(new LocalService<?>[0]));
    }

    private static OhRendererCore core() {
        return new OhRendererCore(null, (runnable, delay) -> runnable.run(),
                new OhRendererCore.Details("m", "https://example.org", "model", "room", "name"), "http-get:*:audio/mpeg:*");
    }

    @Test
    public void theDeviceDescriptionListsTheOpenHomeServices() throws Exception {
        String xml = new UDA10DeviceDescriptorBinderImpl().generate(device(core()), new RemoteClientInfo(), new Namespace());

        String[][] expected = {{"Product", "2"}, {"Playlist", "1"}, {"Info", "1"}, {"Time", "1"}, {"Volume", "2"}};
        for (String[] service : expected) {
            assertTrue(service[0] + " type in\n" + xml,
                    xml.contains("<serviceType>urn:av-openhome-org:service:" + service[0] + ":" + service[1] + "</serviceType>"));
            assertTrue(service[0] + " id in\n" + xml,
                    xml.contains("<serviceId>urn:av-openhome-org:serviceId:" + service[0] + "</serviceId>"));
        }
    }

    @Test
    public void everyServiceHasADescriptionWithItsActions() throws Exception {
        LocalDevice device = device(core());
        assertEquals(5, device.getServices().length);
        for (LocalService<?> service : device.getServices()) {
            String scpd = new UDA10ServiceDescriptorBinderImpl().generate(service);
            assertTrue(service.getServiceType() + " has actions", scpd.contains("<actionList>"));
            assertTrue(service.getServiceType() + " has state variables", scpd.contains("<serviceStateTable>"));
        }
    }

    @Test
    public void buildingTheServicesAgainDoesNotKeepTheOldOnesListening() throws Exception {
        OhRendererCore core = core();
        String[] names = {"playlist", "info", "time", "volume", "product"};

        for (LocalService<?> service : core.buildServices(1000)) {
            service.getManager().getPropertyChangeSupport(); // starts the manager: it listens to the state from now on
        }
        for (String name : names) {
            assertEquals(name, 1, core.listenerCount(name));
        }

        List<LocalService<?>> rebuilt = core.buildServices(1000);
        for (String name : names) {
            assertEquals(name + " old manager dropped", 0, core.listenerCount(name));
        }
        for (LocalService<?> service : rebuilt) {
            service.getManager().getPropertyChangeSupport();
        }
        for (String name : names) {
            assertEquals(name + " only the new manager", 1, core.listenerCount(name));
        }
    }
}
