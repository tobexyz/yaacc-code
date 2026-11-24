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

import java.util.HashSet;
import java.util.Set;

/**
 * The names that players announced, by {@code client_id}, kept across restarts so that the lists of paired and approved players can
 * show a name next to the id. At most {@link #MAX_NAMES}; the oldest are dropped. An entry is {@code <sequence>:<id> <name>} (the
 * stored set has no order, the lowest sequence is the oldest). The names come from the network and are cleaned. Pure.
 */
public final class SendspinNames {

    public static final int MAX_NAMES = 64;
    private static final int ID_LENGTH = 43;

    private final StringSetStore store;

    public SendspinNames(StringSetStore store) {
        this.store = store;
    }

    private static long sequenceOf(String entry) {
        int colon = entry.indexOf(':');
        try {
            return colon <= 0 ? -1 : Long.parseLong(entry.substring(0, colon));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String idOf(String entry) {
        int colon = entry.indexOf(':');
        int space = entry.indexOf(' ', colon + 1);
        return colon < 0 || space < 0 ? null : entry.substring(colon + 1, space);
    }

    private static String nameIn(String entry) {
        int space = entry.indexOf(' ', entry.indexOf(':') + 1);
        return space < 0 ? null : entry.substring(space + 1);
    }

    /** @return the last name the player announced, or null */
    public synchronized String nameOf(String clientId) {
        if (clientId == null) {
            return null;
        }
        for (String entry : store.get(SendspinSettings.KEY_NAMES)) {
            if (clientId.equals(idOf(entry))) {
                return nameIn(entry);
            }
        }
        return null;
    }

    /** Remembers the name of a player; an empty name (after cleaning) or an id of the wrong shape is ignored. */
    public synchronized void remember(String clientId, String announced) {
        if (clientId == null || clientId.length() != ID_LENGTH) {
            return;
        }
        String name = SendspinRowText.clean(announced);
        if (name.isEmpty()) {
            return;
        }
        Set<String> entries = store.get(SendspinSettings.KEY_NAMES);
        long next = 0;
        Set<String> kept = new HashSet<>();
        for (String entry : entries) {
            next = Math.max(next, sequenceOf(entry) + 1);
            if (clientId.equals(idOf(entry))) {
                if (name.equals(nameIn(entry))) {
                    return; // unchanged
                }
            } else {
                kept.add(entry);
            }
        }
        kept.add(next + ":" + clientId + " " + name);
        while (kept.size() > MAX_NAMES) {
            String oldest = null;
            for (String entry : kept) {
                if (oldest == null || sequenceOf(entry) < sequenceOf(oldest)) {
                    oldest = entry;
                }
            }
            kept.remove(oldest);
        }
        store.put(SendspinSettings.KEY_NAMES, kept);
    }

    public synchronized void forget(String clientId) {
        Set<String> kept = new HashSet<>();
        for (String entry : store.get(SendspinSettings.KEY_NAMES)) {
            if (!clientId.equals(idOf(entry))) {
                kept.add(entry);
            }
        }
        store.put(SendspinSettings.KEY_NAMES, kept);
    }
}
