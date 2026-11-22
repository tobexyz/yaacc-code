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

import de.yaacc.fcast.protocol.FCastPin;

/**
 * Which key a receiver is pinned to (task 7.2, decision D24), and how a link's key is stored. Pure; the network and the storage
 * are behind the constructor arguments.
 *
 * <p>The order of trust: (1) the key from an {@code fcast://r/} link that the user added ({@link FCastPin.Source#LINK}); (2) the key
 * in the mDNS record of a receiver that is found now and announces version 4 or more ({@link FCastPin.Source#DISCOVERY}); (3) none,
 * and then the receiver is spoken to in v3. A stored key wins over an announced one: if they differ the receiver made a new key (or
 * somebody else announces at its address), the connection is refused and the user decides (remove and add the receiver again).
 */
public final class FCastPins {
    /** The directory lookup the discovery key needs. */
    public interface Directory {
        /** @return the receiver as known now, or null */
        FCastDirectory.Entry find(FCastEndpoint endpoint);
    }

    private final FCastPinStore store;
    private final Directory directory;

    public FCastPins(FCastPinStore store, Directory directory) {
        this.store = store;
        this.directory = directory;
    }

    /** @return the key to pin the connection to, or null if the receiver is not known to offer v4 */
    public FCastPin pinFor(FCastEndpoint endpoint) {
        if (endpoint == null) {
            return null;
        }
        FCastPin stored = store.get(endpoint);
        if (stored != null) {
            return stored;
        }
        FCastDirectory.Entry entry = directory.find(endpoint);
        if (entry == null || !entry.isDiscovered()) {
            return null;
        }
        FCastServiceTxt hints = entry.getHints();
        Integer version = hints.getProtocolVersion();
        if (version == null || version < 4) {
            return null;
        }
        return FCastPin.parse(hints.getFingerprint(), FCastPin.Source.DISCOVERY);
    }

    /**
     * The result of adding a receiver together with the key of its link. The key is stored when the receiver is in the list (new or
     * already there: a receiver that was typed in by its address gets its key when the user scans its code); a key that replaces or
     * adds one makes an already listed receiver count as added.
     *
     * @param pin may be null (no key in the link: nothing is stored)
     */
    public FCastStoredReceivers.AddResult remember(FCastEndpoint endpoint, FCastPin pin, FCastStoredReceivers.AddResult listed) {
        if (pin == null || (listed != FCastStoredReceivers.AddResult.ADDED && listed != FCastStoredReceivers.AddResult.DUPLICATE)) {
            return listed;
        }
        switch (store.put(endpoint, pin)) {
            case STORED:
            case REPLACED:
                return FCastStoredReceivers.AddResult.ADDED;
            case FULL:
                return FCastStoredReceivers.AddResult.FULL;
            case UNCHANGED:
            default:
                return listed;
        }
    }

    /** Forgets the key of a receiver that was removed. */
    public void forget(FCastEndpoint endpoint) {
        store.remove(endpoint);
    }
}
