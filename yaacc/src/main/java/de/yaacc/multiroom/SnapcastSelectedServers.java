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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.yaacc.multiroom.discovery.SnapcastAddressFilter;
import de.yaacc.multiroom.discovery.SnapcastDirectory;
import de.yaacc.multiroom.discovery.SnapcastEndpoint;
import de.yaacc.multiroom.discovery.SnapcastManualServers;
import de.yaacc.multiroom.discovery.SnapcastServerKey;

/**
 * Remembers the servers that hold a <b>selected</b> Snapcast group, so that the selection survives an app restart even
 * when the server was only discovered (a typed-in server is remembered by the manual list). Without it the directory is
 * empty after a restart, the group could not be resolved, and the stored selection would be dropped before the
 * receivers page had a chance to discover the server again.
 *
 * <p>What is stored is the same plain text as the manual list: one {@code host:port} per line, an address that was
 * seen on the network and passed the range filter, nothing else (no service name, no group id, no credentials). It is
 * read strictly (every line is parsed again and filtered again, at most {@value SnapcastDirectory#MAX_SERVERS}), and
 * handed to the directory as <i>remembered</i> servers, which the directory lists without any scan. A server leaves
 * the store as soon as no selected group refers to it. Pure Java; the storage is injected. Thread safe.
 */
public final class SnapcastSelectedServers {

    private final SnapcastManualServers.Storage storage;
    private final SnapcastDirectory directory;
    private final Object lock = new Object();

    public SnapcastSelectedServers(SnapcastManualServers.Storage storage, SnapcastDirectory directory) {
        this.storage = storage;
        this.directory = directory;
    }

    /** Lists the remembered servers in the directory (at start-up, before anything else asks for a group). */
    public void restore() {
        List<SnapcastEndpoint> servers;
        synchronized (lock) {
            servers = readLocked();
        }
        directory.setRememberedServers(servers);
    }

    /** @return the servers in the store */
    public List<SnapcastEndpoint> list() {
        synchronized (lock) {
            return readLocked();
        }
    }

    /**
     * The selection changed: remember the listed servers that hold a selected group, forget the others. A manual
     * server is left to the manual list.
     *
     * @param receiverIds the ids of the selected receivers (any kind; only {@code snapcast:} ids count); may be null
     */
    public void selectionChanged(Collection<String> receiverIds) {
        Set<SnapcastServerKey> referenced = new HashSet<>();
        if (receiverIds != null) {
            for (String id : receiverIds) {
                SnapcastGroupDevice.Id parsed = SnapcastGroupDevice.parseId(id);
                if (parsed != null) {
                    referenced.add(parsed.getServerKey());
                }
            }
        }
        List<SnapcastEndpoint> next = new ArrayList<>();
        if (!referenced.isEmpty()) {
            for (SnapcastDirectory.Entry entry : directory.servers()) {
                if (referenced.contains(entry.getKey()) && !entry.isManual() && isRememberable(entry.getEndpoint())
                        && next.size() < SnapcastDirectory.MAX_SERVERS) {
                    next.add(entry.getEndpoint());
                }
            }
        }
        synchronized (lock) {
            if (!next.equals(readLocked())) {
                writeLocked(next);
            }
        }
        directory.setRememberedServers(next);
    }

    /**
     * Forgets one server: its endpoint leaves the store and its remembered flag goes. For "remove server" when the
     * stored selection can not be read; the other remembered servers are left alone.
     */
    public void forget(SnapcastServerKey key) {
        if (key == null) {
            return;
        }
        List<SnapcastEndpoint> next = new ArrayList<>();
        synchronized (lock) {
            List<SnapcastEndpoint> stored = readLocked();
            for (SnapcastEndpoint endpoint : stored) {
                if (!endpoint.toKey().equals(key)) {
                    next.add(endpoint);
                }
            }
            if (next.size() != stored.size()) {
                writeLocked(next);
            }
        }
        directory.setRememberedServers(next);
    }

    private static boolean isRememberable(SnapcastEndpoint endpoint) {
        return endpoint != null && endpoint.getAddress() != null && SnapcastAddressFilter.isAccepted(endpoint.getAddress());
    }

    private List<SnapcastEndpoint> readLocked() {
        List<SnapcastEndpoint> servers = new ArrayList<>();
        String stored = storage.read();
        if (stored == null || stored.isEmpty()) {
            return servers;
        }
        for (String line : stored.split("\n", -1)) {
            SnapcastEndpoint endpoint = SnapcastEndpoint.parse(line);
            if (isRememberable(endpoint) && !servers.contains(endpoint)) {
                servers.add(endpoint);
                if (servers.size() >= SnapcastDirectory.MAX_SERVERS) {
                    break;
                }
            }
        }
        return servers;
    }

    private void writeLocked(List<SnapcastEndpoint> servers) {
        StringBuilder text = new StringBuilder();
        for (SnapcastEndpoint endpoint : servers) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(endpoint);
        }
        storage.write(text.toString());
    }
}
