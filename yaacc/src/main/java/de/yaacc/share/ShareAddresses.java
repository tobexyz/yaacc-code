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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Builds the set of addresses that may fetch a share. Plain Java; the glue passes plain strings
 * and no name is ever resolved here (a host name from a device description is dropped, which the
 * access policy then treats as "not allowed").
 * <ul>
 * <li>Every chosen remote receiver contributes its own addresses (the host of its descriptor
 * URL and of its URL base).</li>
 * <li>The loopback addresses and this phone's own addresses are added ONLY when this device is
 * itself a chosen receiver: its in-app player then fetches from one of them. Otherwise any local
 * app that learned the key could use them.</li>
 * </ul>
 */
public final class ShareAddresses {

    private static final Collection<String> LOOPBACK = Collections.unmodifiableList(
            Arrays.asList("127.0.0.1", "::1"));

    private ShareAddresses() {
    }

    /**
     * @param receiverHosts      raw hosts of the chosen remote receivers (IP literals, optionally
     *                           bracketed; anything else is dropped), may be null
     * @param localDeviceChosen  true if this phone is one of the chosen receivers
     * @param localAddresses     this phone's own addresses, used only if {@code localDeviceChosen}; may be null
     * @return a new unmodifiable set of normalized addresses
     * ({@link ProxyAccessPolicy#normalizeAll}); empty if nothing usable is left
     */
    public static Set<String> build(Collection<String> receiverHosts, boolean localDeviceChosen,
                                    Collection<String> localAddresses) {
        Collection<String> raw = new ArrayList<>();
        if (receiverHosts != null) {
            raw.addAll(receiverHosts);
        }
        if (localDeviceChosen) {
            raw.addAll(LOOPBACK);
            if (localAddresses != null) {
                raw.addAll(localAddresses);
            }
        }
        return Collections.unmodifiableSet(new HashSet<>(ProxyAccessPolicy.normalizeAll(raw)));
    }
}
