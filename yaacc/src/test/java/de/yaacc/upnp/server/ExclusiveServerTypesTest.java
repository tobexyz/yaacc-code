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
package de.yaacc.upnp.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.fourthline.cling.model.types.ServiceType;
import org.junit.Test;

/**
 * The service types the app keeps of a device it discovers (everything else is dropped from the description, and answers to a
 * search are only looked at when one of these is advertised): the OpenHome device of another make must get through.
 */
public class ExclusiveServerTypesTest {

    private static boolean kept(String namespace, String type, int version) {
        ServiceType candidate = new ServiceType(namespace, type, version);
        for (ServiceType exclusive : YaaccUpnpServerService.EXCLUSIVE_SERVER_TYPES) {
            if (candidate.implementsVersion(exclusive)) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void theOpenHomeServicesOfThePlayerAreKept() {
        assertTrue(kept("av-openhome-org", "Playlist", 1));
        assertTrue(kept("av-openhome-org", "Playlist", 2));
        assertTrue(kept("av-openhome-org", "Product", 2));
        assertTrue(kept("av-openhome-org", "Product", 4));
        assertTrue(kept("av-openhome-org", "Info", 1));
        assertTrue(kept("av-openhome-org", "Time", 1));
        assertTrue(kept("av-openhome-org", "Volume", 2));
    }

    @Test
    public void otherOpenHomeServicesAreStillLeftOut() {
        assertFalse(kept("av-openhome-org", "Credentials", 1));
        assertFalse(kept("av-openhome-org", "Radio", 1));
    }

    @Test
    public void theUpnpAvServicesAreStillKept() {
        assertTrue(kept("schemas-upnp-org", "AVTransport", 1));
        assertTrue(kept("schemas-upnp-org", "RenderingControl", 1));
        assertTrue(kept("schemas-upnp-org", "ContentDirectory", 1));
    }

    @Test
    public void aServiceOfAnotherNamespaceIsNotMistakenForOpenHome() {
        assertFalse(kept("schemas-upnp-org", "Playlist", 1));
    }
}
