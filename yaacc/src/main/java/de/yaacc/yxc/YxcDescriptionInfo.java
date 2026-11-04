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

import java.util.Locale;

/**
 * What the UPnP device description of a device says about its YXC service (see {@link YxcDescriptionParser}): where the
 * control URL is, which version, on which host and port, and the identity (UDN, serial number) that ties the description
 * to the answer of {@code system/getDeviceInfo}. Immutable. Verified on the owner's devices (2026-11-02): the last 12 hex
 * digits of the UDN are the {@code device_id} and the serial number of the description is the {@code system_id}.
 * <p>
 * The identity values are not part of {@link #toString()} so that a log line does not carry them.
 */
public final class YxcDescriptionInfo {
    static final int DEFAULT_PORT = 80;
    private static final int UDN_SUFFIX_LENGTH = 12;

    private final String controlUrl;
    private final String version;
    private final String host;
    private final int port;
    private final String udn;
    private final String serialNumber;
    private final String friendlyName;

    YxcDescriptionInfo(String controlUrl, String version, String host, int port, String udn, String serialNumber,
                       String friendlyName) {
        this.controlUrl = controlUrl;
        this.version = version;
        this.host = host;
        this.port = port;
        this.udn = udn;
        this.serialNumber = serialNumber;
        this.friendlyName = friendlyName;
    }

    /** @return the path of the YXC control URL, for example {@code /YamahaExtendedControl/v1/}; never null */
    public String getControlUrl() {
        return controlUrl;
    }

    /** @return the {@code X_yxcVersion} text, {@code null} if absent */
    public String getVersion() {
        return version;
    }

    /** @return the host of {@code X_URLBase} (an IPv6 literal without brackets), {@code null} if absent or not usable */
    public String getHost() {
        return host;
    }

    /** @return the port of {@code X_URLBase}, 80 when it has none */
    public int getPort() {
        return port;
    }

    /** @return the UDN as written ({@code uuid:...}), {@code null} if absent */
    public String getUdn() {
        return udn;
    }

    /** @return the {@code serialNumber} of the description, {@code null} if absent */
    public String getSerialNumber() {
        return serialNumber;
    }

    public String getFriendlyName() {
        return friendlyName;
    }

    /**
     * @return {@code http://<host>[:port]<control url>} from the URL base of the description, {@code null} when the
     * description gave no usable URL base. A caller that fetched the description from an address should prefer that
     * address over the one a document names.
     */
    public String getControlBaseUrl() {
        if (host == null) {
            return null;
        }
        String authority = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        return "http://" + authority + (port == DEFAULT_PORT ? "" : ":" + port) + controlUrl;
    }

    /** @return the last 12 characters of the UDN when they are hex digits, in lower case; {@code null} otherwise */
    public String getUdnSuffix() {
        if (udn == null || udn.length() < UDN_SUFFIX_LENGTH) {
            return null;
        }
        String suffix = udn.substring(udn.length() - UDN_SUFFIX_LENGTH).toLowerCase(Locale.ROOT);
        for (int i = 0; i < suffix.length(); i++) {
            if (Character.digit(suffix.charAt(i), 16) < 0) {
                return null;
            }
        }
        return suffix;
    }

    /** True when the UDN suffix equals the {@code device_id} of {@code getDeviceInfo}, ignoring case. */
    public boolean matchesDeviceId(String deviceId) {
        String suffix = getUdnSuffix();
        return suffix != null && deviceId != null && suffix.equalsIgnoreCase(deviceId.trim());
    }

    /** True when the serial number of the description equals the {@code system_id} of {@code getDeviceInfo}, ignoring case. */
    public boolean matchesSystemId(String systemId) {
        return serialNumber != null && systemId != null && !systemId.trim().isEmpty() && serialNumber.equalsIgnoreCase(systemId.trim());
    }

    @Override
    public String toString() {
        return "YxcDescriptionInfo{controlUrl=" + controlUrl + ", version=" + version + "}";
    }
}
