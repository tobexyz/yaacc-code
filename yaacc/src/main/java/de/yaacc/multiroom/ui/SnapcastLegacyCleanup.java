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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.yaacc.multiroom.client.SnapcastOwnIds;
import de.yaacc.multiroom.control.SnapcastClient;
import de.yaacc.multiroom.control.SnapcastGroup;
import de.yaacc.multiroom.control.SnapcastServerStatus;

/**
 * Which entries "Remove old entries of this phone" deletes from a server (task 12.19). Pure.
 *
 * <p>Earlier builds gave every server a client id of its own ({@link de.yaacc.multiroom.client.SnapcastLegacyClientIds}); the entries
 * stay on the servers as offline ghosts, and the lists merge them away ({@link de.yaacc.multiroom.SnapcastMergedClients}). They can
 * only be found in the <b>full</b> status of the server: the filtered view hides exactly these entries. The rule: an entry whose id is
 * one of this install's legacy ids and that is <b>offline</b>; never a connected one, never the current id, never an id of another
 * install (an id that is not in the legacy list). The deletion itself goes through {@code SnapcastControls#deleteOfflineClient},
 * which reads a fresh status and decides again ({@link SnapcastClientRemoval}), so a client that came back meanwhile stays.
 * No id is ever part of a text.
 */
public final class SnapcastLegacyCleanup {

    /** The most entries one action removes. */
    public static final int MAX_REMOVABLE = 64;

    private SnapcastLegacyCleanup() {
    }

    /**
     * @param fullStatus the status of the server as it is (NOT the filtered view); null for none
     * @param ids        the phone's ids; none or without legacy ids removes nothing
     * @return the ids of the offline legacy entries in the order of the status, each once, at most {@value #MAX_REMOVABLE}
     */
    public static List<String> removable(SnapcastServerStatus fullStatus, SnapcastOwnIds ids) {
        List<String> result = new ArrayList<>();
        if (fullStatus == null || ids == null || ids.legacy().isEmpty()) {
            return result;
        }
        Set<String> seen = new HashSet<>();
        for (SnapcastGroup group : fullStatus.getGroups()) {
            for (SnapcastClient client : group.getClients()) {
                String id = client.getId();
                if (ids.isLegacy(id) && !ids.isCurrent(id) && !client.isConnected() && seen.add(id)) {
                    result.add(id);
                    if (result.size() >= MAX_REMOVABLE) {
                        return result;
                    }
                }
            }
        }
        return result;
    }
}
