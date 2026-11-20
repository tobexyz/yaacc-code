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

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/** Where the receiver lists get the FCast receivers from (the shape of the Snapcast group source). */
public interface FCastReceiverSource {

    /** No receivers; the default of a client that has no hub. */
    FCastReceiverSource NONE = new FCastReceiverSource() {
        @Override
        public List<FCastReceiverDevice> receivers() {
            return Collections.emptyList();
        }

        @Override
        public FCastReceiverDevice findReceiver(String receiverId) {
            return null;
        }

        @Override
        public void selectionChanged(Collection<String> receiverIds) {
            // nothing to remember
        }
    };

    /** The receivers as of now: discovered, typed in and remembered ones, sorted by name. Cheap: no network. */
    List<FCastReceiverDevice> receivers();

    /** @return the receiver with this id ({@code fcast:...}), or null if it is not known (any more) */
    FCastReceiverDevice findReceiver(String receiverId);

    /**
     * The stored selection changed (the ids of all selected receivers, of any kind): remember the selected FCast receivers across
     * a restart, forget the others. Does not block on the network.
     */
    void selectionChanged(Collection<String> receiverIds);
}
