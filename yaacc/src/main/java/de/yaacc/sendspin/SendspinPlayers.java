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

import de.yaacc.sendspin.hub.Endpoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The players (or servers) the user added by address: at most {@link SendspinSettings#MAX_PLAYERS} (servers: {@link
 * SendspinSettings#MAX_SERVERS}), stored as {@code host:port} texts. Pure.
 */
public final class SendspinPlayers {

    private final StringSetStore store;
    private final String key;
    private final int max;
    private final int defaultPort;

    public SendspinPlayers(StringSetStore store) {
        this(store, SendspinSettings.KEY_PLAYERS, SendspinSettings.MAX_PLAYERS, Endpoint.DEFAULT_PORT);
    }

    /** The list of servers: its own key, its own maximum and the server port as the default. */
    public static SendspinPlayers servers(StringSetStore store) {
        return new SendspinPlayers(store, SendspinSettings.KEY_SERVERS, SendspinSettings.MAX_SERVERS, SendspinAddress.DEFAULT_SERVER_PORT);
    }

    private SendspinPlayers(StringSetStore store, String key, int max, int defaultPort) {
        this.store = store;
        this.key = key;
        this.max = max;
        this.defaultPort = defaultPort;
    }

    /** @return the endpoints in a fixed order; entries that no longer parse are left out */
    public synchronized List<Endpoint> list() {
        List<Endpoint> out = new ArrayList<>();
        for (String text : new TreeSet<>(store.get(key))) {
            Endpoint endpoint = SendspinAddress.parse(text, defaultPort);
            if (endpoint != null && !out.contains(endpoint)) {
                out.add(endpoint);
            }
        }
        return out;
    }

    /** Result of an add. */
    public enum AddResult {
        ADDED, ALREADY_THERE, NOT_AN_ADDRESS, TOO_MANY
    }

    public synchronized AddResult add(String text) {
        Endpoint endpoint = SendspinAddress.parse(text, defaultPort);
        if (endpoint == null) {
            return AddResult.NOT_AN_ADDRESS;
        }
        Set<String> set = new TreeSet<>(store.get(key));
        String stored = SendspinAddress.format(endpoint);
        if (set.contains(stored)) {
            return AddResult.ALREADY_THERE;
        }
        if (set.size() >= max) {
            return AddResult.TOO_MANY;
        }
        set.add(stored);
        store.put(key, set);
        return AddResult.ADDED;
    }

    public synchronized void remove(Endpoint endpoint) {
        Set<String> set = new TreeSet<>(store.get(key));
        if (set.remove(SendspinAddress.format(endpoint))) {
            store.put(key, set);
        }
    }
}
