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
package de.yaacc.sendspin;

import de.yaacc.sendspin.hub.Endpoint;

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

/**
 * A Sendspin player as a receiver of the receiver lists: a synthetic Cling {@link Device} that is <b>never registered</b> with
 * Cling's registry (so it is never advertised or searched for), in the shape of the Snapcast group and the FCast receiver: no
 * services, no root. It is an immutable snapshot; two snapshots of one player are equal (the identity is the id of the lists), so a
 * player that announced its name later is the same receiver. The lists compare devices by object and redraw a row when the object
 * changes, so the hub keeps the old object while {@link #showsSame} holds.
 */
public final class SendspinReceiverDevice extends Device {
    private static final String PRODUCT_NOTE = " (Sendspin)";
    private static final String SERVER_NOTE = " (Sendspin server)";
    private static final String SELF_NOTE = " (Sendspin player, this phone)";

    private final Endpoint endpoint;
    private final String name;
    private final String friendlyName;
    private final boolean server;
    private final boolean self;

    private SendspinReceiverDevice(Endpoint endpoint, String name, boolean server, boolean self) throws ValidationException {
        super(new DeviceIdentity(new UDN(self ? SendspinAddress.SELF_ID : server ? SendspinAddress.serverIdOf(endpoint)
                : SendspinAddress.idOf(endpoint))));
        this.endpoint = endpoint;
        this.name = name;
        this.server = server;
        this.self = self;
        this.friendlyName = name + (self ? SELF_NOTE : server ? SERVER_NOTE : PRODUCT_NOTE);
    }

    /**
     * @param announcedName the name the player gave in its hello, or null before it connected (the address is shown then)
     * @return the device, or null if it can not be made
     */
    public static SendspinReceiverDevice of(Endpoint endpoint, String announcedName) {
        return make(endpoint, announcedName, false);
    }

    /**
     * A Sendspin <b>server</b> as a card of the receiver list (the controller role: it shows what the server plays and sends
     * commands; nothing is played to it).
     *
     * @param announcedName the name the server gave in its hello, or null before it connected
     */
    public static SendspinReceiverDevice ofServer(Endpoint endpoint, String announcedName) {
        return make(endpoint, announcedName, true);
    }

    /** The card of this phone as a Sendspin player: it shows who plays on the phone and controls its volume; nothing is played to it. */
    public static SendspinReceiverDevice ofSelf(String phoneName) {
        String cleaned = SendspinRowText.clean(phoneName);
        try {
            return new SendspinReceiverDevice(new Endpoint("localhost", Endpoint.DEFAULT_PORT, "/sendspin"), cleaned.isEmpty() ? "YAACC" : cleaned,
                    false, true);
        } catch (ValidationException e) {
            return null;
        }
    }

    private static SendspinReceiverDevice make(Endpoint endpoint, String announcedName, boolean server) {
        if (endpoint == null) {
            return null;
        }
        String cleaned = SendspinRowText.clean(announcedName);
        String shown = cleaned.isEmpty() ? SendspinAddress.format(endpoint) : cleaned;
        try {
            return new SendspinReceiverDevice(endpoint, shown, server, false);
        } catch (ValidationException e) {
            return null; // not expected: a device without a type is not validated
        }
    }

    /** True for the card of a Sendspin server (the controller), false for a player. */
    public boolean isServer() {
        return server;
    }

    /** True for the card of this phone as a player. */
    public boolean isSelf() {
        return self;
    }

    /** The receiver id of the lists. */
    public String getReceiverId() {
        return self ? SendspinAddress.SELF_ID : server ? SendspinAddress.serverIdOf(endpoint) : SendspinAddress.idOf(endpoint);
    }

    public Endpoint getEndpoint() {
        return endpoint;
    }

    /** The name without the product note. */
    public String getName() {
        return name;
    }

    /** True if a snapshot made with this announced name would look the same as this one. */
    boolean showsSame(String announcedName) {
        String cleaned = SendspinRowText.clean(announcedName);
        if (self) {
            return name.equals(cleaned.isEmpty() ? "YAACC" : cleaned);
        }
        return name.equals(cleaned.isEmpty() ? SendspinAddress.format(endpoint) : cleaned);
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

    /** No address or name in the text. */
    @Override
    public String toString() {
        return "SendspinReceiverDevice";
    }
}
