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
package de.yaacc.multiroom;

import org.fourthline.cling.model.Namespace;
import org.fourthline.cling.model.ValidationException;
import org.fourthline.cling.model.meta.Action;
import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.DeviceDetails;
import org.fourthline.cling.model.meta.DeviceIdentity;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.meta.StateVariable;
import org.fourthline.cling.model.meta.UDAVersion;
import org.fourthline.cling.model.resource.Resource;
import org.fourthline.cling.model.types.DeviceType;
import org.fourthline.cling.model.types.ServiceId;
import org.fourthline.cling.model.types.ServiceType;
import org.fourthline.cling.model.types.UDN;

import java.net.URI;
import java.util.Collection;
import java.util.List;

import de.yaacc.multiroom.control.SnapcastClient;
import de.yaacc.multiroom.control.SnapcastDisplayText;
import de.yaacc.multiroom.control.SnapcastGroup;
import de.yaacc.multiroom.discovery.SnapcastServerKey;

/**
 * A Snapcast group as a receiver of the receiver lists: a synthetic Cling {@link Device} that is <b>never registered</b>
 * with Cling's registry (so it is never advertised and never searched for), modeled on
 * {@code UpnpClient.LocalDummyDevice}. It has no services (the code that looks for AVTransport, RenderingControl or
 * OpenHome services finds none and treats it like the phone's own dummy device), and no root. It is an immutable
 * snapshot of the group at one moment; two snapshots of one group are equal (the identity is the id), so a renamed
 * group is the same receiver.
 *
 * <p><b>The id</b> is {@code snapcast:<server key>:<group id>}. The server key is the control endpoint and contains
 * colons ({@code host:port}, an IPv6 literal in brackets with colons inside), so the id is parsed <b>from the
 * right</b>: the group id is what follows the last colon, and the rest after the prefix must be a canonical server
 * key ({@link SnapcastServerKey#parse}). A group id from the server is accepted only when it is 1 to 64 characters of
 * letters, digits, {@code _} and {@code -} (the ids of a snapserver are UUIDs); a group with another id is not made
 * into a device, so a hostile server can not forge an ambiguous id.
 *
 * <p><b>The name</b> is the group's name, else the first member's name (its set name, else its host name; never its
 * id, which is a MAC address), else the generic label, cut at 64 characters, plus " (Snapcast)" (the product name) and
 * a marker when the server is offline. Names come from the server: they are display text and are cleaned with
 * {@link SnapcastDisplayText} before they get here; they are never an identity.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public final class SnapcastGroupDevice extends Device {

    /** The prefix of every receiver id of a group. */
    public static final String ID_PREFIX = "snapcast:";
    private static final int MAX_GROUP_ID_LENGTH = 64;
    private static final int MAX_NAME_LENGTH = 64;
    private static final String PRODUCT_SUFFIX = " (Snapcast)";

    /** The texts the user sees; the app fills them from its string resources. */
    public static final class Labels {
        private final String groupLabel;
        private final String offlineLabel;

        public Labels(String groupLabel, String offlineLabel) {
            this.groupLabel = groupLabel;
            this.offlineLabel = offlineLabel;
        }
    }

    /** A parsed receiver id. */
    public static final class Id {
        private final SnapcastServerKey serverKey;
        private final String groupId;

        Id(SnapcastServerKey serverKey, String groupId) {
            this.serverKey = serverKey;
            this.groupId = groupId;
        }

        public SnapcastServerKey getServerKey() {
            return serverKey;
        }

        public String getGroupId() {
            return groupId;
        }
    }

    private final SnapcastServerKey serverKey;
    private final String groupId;
    private final String streamId;
    private final int clientCount;
    private final boolean offline;
    private final String friendlyName;

    private SnapcastGroupDevice(SnapcastServerKey serverKey, SnapcastGroup group, boolean offline, String friendlyName)
            throws ValidationException {
        super(new DeviceIdentity(new UDN(idOf(serverKey, group.getId()))));
        this.serverKey = serverKey;
        this.groupId = group.getId();
        this.streamId = group.getStreamId();
        this.clientCount = group.getClients().size();
        this.offline = offline;
        this.friendlyName = friendlyName;
    }

    /**
     * Makes the device of a group.
     *
     * @param offline true while the control connection to the server is down (the group is the last one known)
     * @return the device, or null when the group's id is not safe to put into a receiver id
     */
    public static SnapcastGroupDevice of(SnapcastServerKey serverKey, SnapcastGroup group, boolean offline, Labels labels) {
        if (serverKey == null || group == null || !isSafeGroupId(group.getId())) {
            return null;
        }
        try {
            return new SnapcastGroupDevice(serverKey, group, offline, nameOf(group, offline, labels));
        } catch (ValidationException e) {
            return null; // not expected: a device without a type is not validated
        }
    }

    static String nameOf(SnapcastGroup group, boolean offline, Labels labels) {
        String base = clean(group.getName());
        if (base.isEmpty()) {
            for (SnapcastClient client : group.getClients()) {
                base = clean(client.getName());
                if (base.isEmpty()) {
                    base = clean(client.getHostName());
                }
                break; // the first member decides
            }
        }
        if (base.isEmpty()) {
            return labels.groupLabel + (offline ? " (" + labels.offlineLabel + ")" : "");
        }
        return base + (offline ? " (Snapcast, " + labels.offlineLabel + ")" : PRODUCT_SUFFIX);
    }

    private static String clean(String text) {
        return SnapcastDisplayText.of(text, MAX_NAME_LENGTH).trim();
    }

    private static boolean isSafeGroupId(String id) {
        if (id == null || id.isEmpty() || id.length() > MAX_GROUP_ID_LENGTH) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** The receiver id of a group. */
    public static String idOf(SnapcastServerKey serverKey, String groupId) {
        return ID_PREFIX + serverKey.asString() + ":" + groupId;
    }

    /** True for text that starts like a group id (it may still be malformed; {@link #parseId} decides). */
    public static boolean isSnapcastId(String id) {
        return id != null && id.startsWith(ID_PREFIX);
    }

    /**
     * Reads a receiver id from the right.
     *
     * @return the parts, or null when the text is not a well-formed group id
     */
    public static Id parseId(String id) {
        if (!isSnapcastId(id) || id.length() > ID_PREFIX.length() + 270 + 1 + MAX_GROUP_ID_LENGTH) {
            return null;
        }
        int lastColon = id.lastIndexOf(':');
        if (lastColon < ID_PREFIX.length()) {
            return null;
        }
        String groupId = id.substring(lastColon + 1);
        if (!isSafeGroupId(groupId)) {
            return null;
        }
        SnapcastServerKey key = SnapcastServerKey.parse(id.substring(ID_PREFIX.length(), lastColon));
        return key == null ? null : new Id(key, groupId);
    }

    public SnapcastServerKey getServerKey() {
        return serverKey;
    }

    public String getGroupId() {
        return groupId;
    }

    /** The stream the group plays when the snapshot was taken (an id of the server: display text). */
    public String getStreamId() {
        return streamId;
    }

    public int getClientCount() {
        return clientCount;
    }

    /** True while the server's control connection is down; the group is the last one known. */
    public boolean isOffline() {
        return offline;
    }

    @Override
    public String getDisplayString() {
        return friendlyName;
    }

    @Override
    public DeviceDetails getDetails() {
        return new DeviceDetails(friendlyName);
    }

    @Override
    public Service[] getServices() {
        return null;
    }

    @Override
    public Device[] getEmbeddedDevices() {
        return null;
    }

    @Override
    public Device getRoot() {
        return null;
    }

    @Override
    public Device findDevice(UDN udn) {
        return null;
    }

    @Override
    public Resource[] discoverResources(Namespace namespace) {
        return null;
    }

    @Override
    public Device newInstance(UDN arg0, UDAVersion arg1, DeviceType arg2, DeviceDetails arg3,
                              org.fourthline.cling.model.meta.Icon[] arg4, Service[] arg5, List arg6) throws ValidationException {
        return null;
    }

    @Override
    public Service newInstance(ServiceType serviceType, ServiceId serviceId, URI uri, URI uri1, URI uri2, Action[] actions,
                               StateVariable[] stateVariables) throws ValidationException {
        return null;
    }

    @Override
    public Device[] toDeviceArray(Collection collection) {
        return null;
    }

    @Override
    public Service[] newServiceArray(int i) {
        return null;
    }

    @Override
    public Service[] toServiceArray(Collection collection) {
        return null;
    }

    /** No address or id in the text: counts only. */
    @Override
    public String toString() {
        return "SnapcastGroupDevice{clients=" + clientCount + ", offline=" + offline + "}";
    }
}
