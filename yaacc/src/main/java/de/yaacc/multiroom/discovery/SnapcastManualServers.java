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
package de.yaacc.multiroom.discovery;

import java.util.ArrayList;
import java.util.List;

/**
 * The snapservers the user typed in, kept as a plain list of {@code host:port} lines in a {@link Storage} (the app's
 * preferences). Nothing but the endpoint is stored: no credentials, no names from a server. Whatever is read back is
 * checked again with {@link SnapcastEndpoint#parse}, so a damaged or edited store cannot put anything else into the
 * list. Thread safe; pure (the storage is behind an interface).
 */
public final class SnapcastManualServers {

    /** The most manual servers; the same cap as the directory. */
    public static final int MAX_SERVERS = SnapcastDirectory.MAX_SERVERS;

    /** Where the list lives; a string in the preferences. */
    public interface Storage {
        /** @return the stored text, or null when there is none */
        String read();

        void write(String text);
    }

    /** The outcome of {@link #add}. */
    public enum AddResult {
        ADDED,
        /** The text is not a valid {@code host[:port]}. */
        INVALID,
        /** The server is in the list already (the same endpoint written another way counts). */
        DUPLICATE,
        /** The list holds {@link #MAX_SERVERS} servers. */
        FULL
    }

    private final Storage storage;
    private final Object lock = new Object();

    public SnapcastManualServers(Storage storage) {
        this.storage = storage;
    }

    /** @return the servers in the order they were added */
    public List<SnapcastEndpoint> list() {
        synchronized (lock) {
            return readLocked();
        }
    }

    /** Adds a server typed by the user (the text is not trimmed: white space is refused). */
    public AddResult add(String text) {
        SnapcastEndpoint endpoint = SnapcastEndpoint.parse(text);
        if (endpoint == null) {
            return AddResult.INVALID;
        }
        synchronized (lock) {
            List<SnapcastEndpoint> servers = readLocked();
            if (servers.contains(endpoint)) {
                return AddResult.DUPLICATE;
            }
            if (servers.size() >= MAX_SERVERS) {
                return AddResult.FULL;
            }
            servers.add(endpoint);
            writeLocked(servers);
            return AddResult.ADDED;
        }
    }

    /** @return true if the server was in the list */
    public boolean remove(SnapcastServerKey key) {
        synchronized (lock) {
            List<SnapcastEndpoint> servers = readLocked();
            boolean removed = servers.removeIf(endpoint -> endpoint.toKey().equals(key));
            if (removed) {
                writeLocked(servers);
            }
            return removed;
        }
    }

    private List<SnapcastEndpoint> readLocked() {
        List<SnapcastEndpoint> servers = new ArrayList<>();
        String stored = storage.read();
        if (stored == null || stored.isEmpty()) {
            return servers;
        }
        for (String line : stored.split("\n", -1)) {
            SnapcastEndpoint endpoint = SnapcastEndpoint.parse(line);
            if (endpoint != null && !servers.contains(endpoint)) {
                servers.add(endpoint);
                if (servers.size() >= MAX_SERVERS) {
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
