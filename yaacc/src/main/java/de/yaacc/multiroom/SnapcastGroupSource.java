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

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Where {@code UpnpClient} gets the Snapcast groups from: the receivers that are not in Cling's registry. Kept as a
 * small interface so that the client works (and is tested) without any Snapcast machinery.
 */
public interface SnapcastGroupSource {

    /** No groups; the default of a client that has no hub. */
    SnapcastGroupSource NONE = new SnapcastGroupSource() {
        @Override
        public List<SnapcastGroupDevice> groups() {
            return Collections.emptyList();
        }

        @Override
        public SnapcastGroupDevice findGroup(String receiverId) {
            return null;
        }

        @Override
        public void wake(Collection<String> receiverIds) {
            // nothing to wake
        }
    };

    /** The groups of the servers that are listed, as of the last status of each server. Cheap: no network. */
    List<SnapcastGroupDevice> groups();

    /** @return the group with this receiver id ({@code snapcast:...}), or null if it is not known (any more) */
    SnapcastGroupDevice findGroup(String receiverId);

    /**
     * Selected receivers are missing: connect to the listed servers that hold the Snapcast ones among
     * {@code receiverIds} (and to no other), so that their groups are known soon. Does not block.
     */
    void wake(Collection<String> receiverIds);
}
