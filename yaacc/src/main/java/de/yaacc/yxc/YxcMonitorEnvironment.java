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
package de.yaacc.yxc;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The app side of {@link YxcGroupOperations}: the members are the devices {@link YxcMonitor} found by its own
 * discovery (addresses never come from user input), inputs come from what the monitor saw, and every involved device
 * is read again through the monitor afterwards.
 */
public final class YxcMonitorEnvironment implements YxcGroupOperations.Environment {

    /** Told which devices are busy with a group operation; called on the thread of the operation. */
    public interface BusyListener {
        void onBusyChanged(Set<String> busyUdns);
    }

    private final YxcMonitor monitor;
    private final BusyListener busyListener;

    public YxcMonitorEnvironment(YxcMonitor monitor, BusyListener busyListener) {
        this.monitor = monitor;
        this.busyListener = busyListener;
    }

    @Override
    public List<YxcGroupOperations.Member> members() {
        List<YxcGroupOperations.Member> result = new ArrayList<>();
        for (YxcDeviceSnapshot device : monitor.snapshot()) {
            YxcEndpoint endpoint = monitor.getEndpoint(device.getUdn());
            if (endpoint == null) {
                continue;
            }
            // no event port: the monitor's own requests keep the registration alive
            YxcTransport transport;
            try {
                transport = new YxcHttpTransport(endpoint.getHost(), endpoint.getPort(), 0,
                        YxcHttpTransport.DEFAULT_CONNECT_TIMEOUT_MILLIS, YxcHttpTransport.DEFAULT_READ_TIMEOUT_MILLIS);
            } catch (RuntimeException e) {
                continue; // an endpoint the transport refuses is not used
            }
            result.add(new YxcGroupOperations.Member(device.getUdn(),
                    new YxcGroupMember(endpoint.getHost(), YxcDeviceState.MAIN_ZONE, new YxcClient(transport))));
        }
        return result;
    }

    @Override
    public String lastPlainInput(String udn) {
        return monitor.getLastPlainInput(udn);
    }

    @Override
    public void refresh(String udn) {
        monitor.refreshNow(udn);
    }

    @Override
    public void busyChanged(Set<String> busyUdns) {
        busyListener.onBusyChanged(busyUdns);
    }
}
