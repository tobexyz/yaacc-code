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
package de.yaacc.fcast.discovery;

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
 * An FCast receiver as a receiver of the receiver lists: a synthetic Cling {@link Device} that is <b>never registered</b> with
 * Cling's registry (so it is never advertised and never searched for), modelled on the Snapcast group device. It has no
 * services (the code that looks for AVTransport, RenderingControl or OpenHome services finds none) and no root. It is an immutable
 * snapshot of the receiver at one moment; two snapshots of one receiver are equal (the identity is the id, {@link FCastReceiverId}),
 * so a renamed receiver is the same receiver.
 *
 * <p><b>The name</b> is what the receiver announced, else what was typed or remembered, else its address (the receiver's own
 * {@code Initial} has no name on the desktop receiver); the lists show it with the plain-text product note " (FCast)", and
 * " (FCast, offline)" for a receiver that is only remembered from the last run (text names only, decision D14).
 */
public final class FCastReceiverDevice extends Device {
    private static final String PRODUCT_NOTE = " (FCast)";

    /** The text the user sees for an offline receiver; the app fills it from its string resources. */
    public static final class Labels {
        private final String offlineLabel;

        public Labels(String offlineLabel) {
            this.offlineLabel = offlineLabel == null ? "offline" : offlineLabel;
        }
    }

    private final FCastEndpoint endpoint;
    private final boolean offline;
    private final boolean manual;
    private final FCastServiceTxt hints;
    private final String name;
    private final String friendlyName;
    private final String offlineLabel;

    private FCastReceiverDevice(FCastDirectory.Entry entry, Labels labels) throws ValidationException {
        super(new DeviceIdentity(new UDN(entry.getId())));
        this.endpoint = entry.getEndpoint();
        this.offline = entry.isOffline();
        this.manual = entry.isManual();
        this.hints = entry.getHints();
        this.name = entry.getDisplayName();
        this.offlineLabel = labels.offlineLabel;
        this.friendlyName = name + (offline ? " (FCast, " + labels.offlineLabel + ")" : PRODUCT_NOTE);
    }

    /** @return the device of a directory entry, or null if it can not be made */
    public static FCastReceiverDevice of(FCastDirectory.Entry entry, Labels labels) {
        if (entry == null) {
            return null;
        }
        try {
            return new FCastReceiverDevice(entry, labels == null ? new Labels(null) : labels);
        } catch (ValidationException e) {
            return null; // not expected: a device without a type is not validated
        }
    }

    /** The receiver id of the lists. */
    public String getReceiverId() {
        return FCastReceiverId.of(endpoint);
    }

    public FCastEndpoint getEndpoint() {
        return endpoint;
    }

    /** The name without the product note (display text). */
    public String getName() {
        return name;
    }

    /** What the receiver announced (hints). */
    public FCastServiceTxt getHints() {
        return hints;
    }

    /** True if the receiver was typed in, pasted or scanned by the user. */
    public boolean isManual() {
        return manual;
    }

    /** True while the receiver is only remembered from the last run and has not been seen in this one. */
    public boolean isOffline() {
        return offline;
    }

    /**
     * True if a fresh snapshot of this entry with these labels would look the same as this one (the id is equal already). The lists
     * compare devices by object and redraw a row whose device object changed, so {@link FCastReceivers} keeps the old object as long as
     * this holds.
     */
    boolean showsSame(FCastDirectory.Entry entry, Labels labels) {
        return name.equals(entry.getDisplayName()) && offline == entry.isOffline() && manual == entry.isManual()
                && hints.equals(entry.getHints()) && offlineLabel.equals(labels == null ? new Labels(null).offlineLabel : labels.offlineLabel);
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

    /** No address or id in the text. */
    @Override
    public String toString() {
        return "FCastReceiverDevice{offline=" + offline + ", manual=" + manual + "}";
    }
}
