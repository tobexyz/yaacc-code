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

import java.util.Objects;

/**
 * A device that speaks the protocol, found by yaacc's own discovery: where its control interface is. The address is
 * the one the device description was fetched from, never one a document names.
 */
public final class YxcEndpoint {
    private final String udn;
    private final String host;
    private final int port;
    private final String deviceIdSuffix;

    YxcEndpoint(String udn, String host, int port, String deviceIdSuffix) {
        this.udn = udn;
        this.host = host;
        this.port = port;
        this.deviceIdSuffix = deviceIdSuffix;
    }

    /** @return the UDN of the UPnP device, the key yaacc knows the device by */
    public String getUdn() {
        return udn;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    /** @return the last 12 hex digits of the UDN in lower case (the {@code device_id} of events), or {@code null} */
    public String getDeviceIdSuffix() {
        return deviceIdSuffix;
    }

    /** True when an event's {@code device_id} is this device's, or when either side has none to compare. */
    public boolean acceptsDeviceId(String eventDeviceId) {
        return eventDeviceId == null || deviceIdSuffix == null || deviceIdSuffix.equalsIgnoreCase(eventDeviceId.trim());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof YxcEndpoint)) {
            return false;
        }
        YxcEndpoint other = (YxcEndpoint) o;
        return port == other.port && Objects.equals(udn, other.udn) && Objects.equals(host, other.host);
    }

    @Override
    public int hashCode() {
        return Objects.hash(udn, host, port);
    }

    @Override
    public String toString() {
        return "YxcEndpoint{" + host + ":" + port + "}";
    }
}
