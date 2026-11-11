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

import java.util.HashSet;
import java.util.Set;

import de.yaacc.multiroom.discovery.SnapcastDirectory;
import de.yaacc.multiroom.discovery.SnapcastManualServers;
import de.yaacc.multiroom.discovery.SnapcastServerKey;

/**
 * "Remove server" of the settings. Removing a server means <b>removed</b>, not hidden:
 * <ol>
 *   <li>it leaves the manual list and the directory takes the new list;</li>
 *   <li>the groups of that server leave the stored selection of receivers (a selection that names a server the user has
 *   removed would keep connecting to it on every play);</li>
 *   <li>the remembered flag and the stored endpoint of the server go (security review S23: a server that was both
 *   remembered from an earlier discovery and typed in stayed listed and connected after "remove" until the selection
 *   changed).</li>
 * </ol>
 * A server that is still announced on the network is listed again as a discovered one by the scan (when discovery is on):
 * the user can not remove what is on the network, only what they typed in or what was remembered. Pure Java; the
 * selection store is behind an interface.
 */
public final class SnapcastServerAdmin {

    /** Where the ids of the selected receivers live ({@code SharedPreferences} in the app). */
    public interface SelectionStore {
        Set<String> read();

        void write(Set<String> receiverIds);
    }

    /** What a removal did. Immutable. */
    public static final class Result {
        private final boolean wasManual;
        private final int selectionRemoved;

        Result(boolean wasManual, int selectionRemoved) {
            this.wasManual = wasManual;
            this.selectionRemoved = selectionRemoved;
        }

        /** The server was in the manual list. */
        public boolean wasManual() {
            return wasManual;
        }

        /** How many selected groups of the server were taken out of the stored selection. */
        public int getSelectionRemoved() {
            return selectionRemoved;
        }
    }

    private final SnapcastManualServers manualServers;
    private final SnapcastDirectory directory;
    private final SnapcastSelectedServers selectedServers;
    private final SelectionStore selection;

    public SnapcastServerAdmin(SnapcastManualServers manualServers, SnapcastDirectory directory,
                               SnapcastSelectedServers selectedServers, SelectionStore selection) {
        this.manualServers = manualServers;
        this.directory = directory;
        this.selectedServers = selectedServers;
        this.selection = selection;
    }

    /** Removes a server as described above. A null key or an unknown server changes nothing. */
    public Result remove(SnapcastServerKey key) {
        if (key == null) {
            return new Result(false, 0);
        }
        boolean wasManual = manualServers.remove(key);
        directory.setManualServers(manualServers.list());
        Set<String> remaining = null;
        int removed = 0;
        try {
            Set<String> stored = selection.read();
            remaining = withoutServer(stored, key);
            removed = stored.size() - remaining.size();
            if (removed > 0) {
                selection.write(remaining);
            }
        } catch (RuntimeException unreadable) {
            remaining = null; // the selection could not be read or written: the rest is done all the same
        }
        if (remaining != null) {
            // forgets the stored endpoint and the remembered flag of every server that no selected group refers to any more
            selectedServers.selectionChanged(remaining);
        } else {
            selectedServers.forget(key); // the selection is unknown: forget this server only, never the others
        }
        return new Result(wasManual, removed);
    }

    /** The ids without the groups of the server; everything else (the phone, UPnP receivers, other servers) stays. */
    static Set<String> withoutServer(Set<String> receiverIds, SnapcastServerKey key) {
        Set<String> kept = new HashSet<>();
        for (String id : receiverIds) {
            SnapcastGroupDevice.Id parsed = SnapcastGroupDevice.parseId(id);
            if (parsed != null && parsed.getServerKey().equals(key)) {
                continue;
            }
            kept.add(id);
        }
        return kept;
    }
}
