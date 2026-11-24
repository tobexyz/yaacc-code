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

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/** Where the receiver lists get the Sendspin players from (the shape of the FCast receiver source). */
public interface SendspinReceiverSource {

    /** No players; the default of a client that has no hub. */
    SendspinReceiverSource NONE = new SendspinReceiverSource() {
        @Override
        public List<SendspinReceiverDevice> receivers() {
            return Collections.emptyList();
        }

        @Override
        public SendspinReceiverDevice findReceiver(String receiverId) {
            return null;
        }

        @Override
        public void selectionChanged(Collection<String> receiverIds) {
            // nothing to remember
        }
    };

    /** The players as of now (none while the switch is off), sorted by name. Cheap: no network wait. */
    List<SendspinReceiverDevice> receivers();

    /** @return the player with this id ({@code sendspin:...}), or null if it is not known (any more) or the switch is off */
    SendspinReceiverDevice findReceiver(String receiverId);

    /** The stored selection changed (the ids of all selected receivers, of any kind). */
    void selectionChanged(Collection<String> receiverIds);
}
