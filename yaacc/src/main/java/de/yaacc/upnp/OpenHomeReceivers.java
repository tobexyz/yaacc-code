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

import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.types.UDAServiceType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.yaacc.upnp.callback.openhome.ClingOhControl;

/**
 * Receivers that speak OpenHome and nothing else: a device with an OpenHome playlist but no AVTransport service (a Linn device
 * or upmpdcli in its OpenHome mode) would not be listed among the receivers otherwise. A device that has AVTransport anywhere in its
 * tree (this app's own renderer inside its media server) is listed by that already.
 */
public final class OpenHomeReceivers {
    private OpenHomeReceivers() {
    }

    /**
     * @param candidates the devices that offer an OpenHome playlist
     * @param listed     the devices that are listed already (they have an AVTransport service)
     * @return the candidates that are not listed, have no AVTransport service and offer what the OpenHome player needs
     */
    public static List<Device<?, ?, ?>> onlyOpenHome(Collection<Device<?, ?, ?>> candidates, Collection<Device<?, ?, ?>> listed) {
        Set<String> known = new HashSet<>();
        for (Device<?, ?, ?> device : listed) {
            known.add(udn(device));
        }
        List<Device<?, ?, ?>> result = new ArrayList<>();
        for (Device<?, ?, ?> device : candidates) {
            if (device == null || device.getIdentity() == null || !ClingOhControl.offersPlaylist(device)
                    || device.findService(new UDAServiceType("AVTransport")) != null) {
                continue;
            }
            if (known.add(udn(device))) {
                result.add(device);
            }
        }
        return result;
    }

    private static String udn(Device<?, ?, ?> device) {
        return device.getIdentity() == null || device.getIdentity().getUdn() == null
                ? "" : device.getIdentity().getUdn().getIdentifierString();
    }
}
