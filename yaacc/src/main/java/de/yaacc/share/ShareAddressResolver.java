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
package de.yaacc.share;

import android.content.Context;

import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.LocalDevice;
import org.fourthline.cling.model.meta.RemoteDevice;

import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import de.yaacc.upnp.UpnpClient;
import de.yaacc.util.InterfaceResolutionHelper;

/**
 * Collects the addresses that may fetch a share from the currently chosen receivers. Reads the
 * selection without changing it and never resolves a host name (no network call): a receiver
 * whose description names a host instead of an IP address contributes nothing, so with the
 * receiver restriction on it would be refused (the setting can be switched off).
 * <p>
 * A remote receiver contributes the host of its description URL and of its URL base. This phone
 * (the local dummy device or a local renderer) contributes the loopback addresses and its own
 * addresses, and only when it is a chosen receiver ({@link ShareAddresses}).
 */
public final class ShareAddressResolver {

    private ShareAddressResolver() {
    }

    /**
     * The addresses of the receivers currently stored as the selection.
     *
     * @return a new unmodifiable set of normalized addresses; empty if no chosen receiver is known
     */
    public static Set<String> resolve(UpnpClient upnpClient, Context context) {
        return resolve(upnpClient, context, upnpClient.getReceiverDeviceIds());
    }

    /**
     * The addresses of explicitly given receivers (the ones the user picked for this share),
     * independent of the stored selection.
     *
     * @param receiverIds the chosen receiver ids; null or empty gives an empty set
     * @return a new unmodifiable set of normalized addresses; empty if no chosen receiver is known
     */
    public static Set<String> resolve(UpnpClient upnpClient, Context context, Set<String> receiverIds) {
        boolean localChosen = false;
        List<String> hosts = new ArrayList<>();
        // a copy: the set from the preferences must not be modified
        for (String id : receiverIds == null ? new HashSet<String>() : new HashSet<>(receiverIds)) {
            if (UpnpClient.LOCAL_UID.equals(id)) {
                localChosen = true;
                continue;
            }
            Device<?, ?, ?> device = upnpClient.getDevice(id);
            if (device == null) {
                continue;
            }
            if (device instanceof LocalDevice || device instanceof UpnpClient.LocalDummyDevice) {
                localChosen = true;
                continue;
            }
            Device<?, ?, ?> root = device.getRoot();
            if (root instanceof RemoteDevice) {
                RemoteDevice remote = (RemoteDevice) root;
                addHost(hosts, remote.getIdentity() == null ? null : remote.getIdentity().getDescriptorURL());
                addHost(hosts, remote.getDetails() == null ? null : remote.getDetails().getBaseURL());
            }
        }
        List<String> own = new ArrayList<>();
        if (localChosen) {
            own.addAll(ownAddresses(context));
        }
        return ShareAddresses.build(hosts, localChosen, own);
    }

    /**
     * This phone's own addresses as text (the address used for the proxy URL and every address of
     * the selected interface), for the link policy and for the allowed set when this phone is a
     * chosen receiver. No name is resolved. Empty if the phone has no address.
     */
    public static Set<String> ownAddresses(Context context) {
        Set<String> own = new HashSet<>();
        String ip = InterfaceResolutionHelper.getIpAddress(context);
        if (ip != null && !ip.isEmpty()) {
            own.add(ip);
        }
        Iterator<InetAddress> bind = InterfaceResolutionHelper.getBindAddresses(context);
        while (bind.hasNext()) {
            // getHostAddress does not resolve anything
            String host = bind.next().getHostAddress();
            if (host != null && !host.isEmpty()) {
                own.add(host);
            }
        }
        return own;
    }

    private static void addHost(List<String> hosts, URL url) {
        if (url != null && url.getHost() != null && !url.getHost().isEmpty()) {
            // brackets of an IPv6 literal are stripped by ShareAddresses
            hosts.add(url.getHost());
        }
    }
}
