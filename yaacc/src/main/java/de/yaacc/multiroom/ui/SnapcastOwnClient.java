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
package de.yaacc.multiroom.ui;

import de.yaacc.multiroom.client.SnapcastOwnIds;
import de.yaacc.multiroom.control.SnapcastGroup;

/**
 * Which client of a server's status is this phone's own (tasks 12.11 and 12C). With ONE client id for every server
 * ({@code SnapcastClientIdentity}), the app knows its own id without a session, so the entry of the phone is recognised on every
 * server, connected or offline (a ghost of this install). The id is the only thing that counts: names and host names are the server's
 * text and can be anything. Pure.
 *
 * <p>What the recognition is used for: the label "This phone" in the member rows, the power button on the card whose first member is
 * the own client (the one that titles the card), and the removal rule of an offline entry (never while a session runs).
 */
public final class SnapcastOwnClient {

    private SnapcastOwnClient() {
    }

    /** @return true when the id from a server's status is the app's own client id; false when either is missing or empty */
    public static boolean isOwn(String clientIdFromStatus, String ownId) {
        return clientIdFromStatus != null && ownId != null && !ownId.isEmpty() && ownId.equals(clientIdFromStatus);
    }

    /**
     * Whether the card of a group has the power button: the own client is the group's first member (the member whose name titles a card
     * of a group without a name of its own) or its only member. Any other group, a missing group or an unknown own id: no button.
     */
    public static boolean cardOffersPower(SnapcastGroup group, String ownId) {
        if (group == null || group.getClients().isEmpty()) {
            return false;
        }
        return isOwn(group.getClients().get(0).getId(), ownId);
    }

    /**
     * @return true when the id is the current id or one of this install's legacy ids (task 12.19): an entry of this phone, whether it is
     * the one it uses now or an old per-server entry that earlier builds left on a server. Other installs' ids are not.
     */
    public static boolean isOwnOrLegacy(String clientIdFromStatus, SnapcastOwnIds ids) {
        return ids != null && ids.isOwn(clientIdFromStatus);
    }

    /** @return true for an old per-server entry of this install (not for the current id): the row says "old entry" */
    public static boolean isLegacy(String clientIdFromStatus, SnapcastOwnIds ids) {
        return ids != null && ids.isLegacy(clientIdFromStatus);
    }

    /** As {@link #cardOffersPower(SnapcastGroup, String)} for the current id and the legacy ids: the first member is the phone's. */
    public static boolean cardOffersPowerForIds(SnapcastGroup group, SnapcastOwnIds ids) {
        if (group == null || group.getClients().isEmpty()) {
            return false;
        }
        return isOwnOrLegacy(group.getClients().get(0).getId(), ids);
    }
}
